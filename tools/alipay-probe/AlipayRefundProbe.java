import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 支付宝沙箱退款调用工具
 *
 * 配置全部从 application.yml 读取（不重复硬编码密钥），
 * 沙箱/正式由 alipay.gateway-url 决定，代码不用改。
 *
 * ── 用法 ────────────────────────────────────────────────
 *   1) 只查交易状态（先确认这笔能退）
 *      java AlipayRefundProbe <application.yml> <out_trade_no>
 *
 *   2) 部分退款
 *      java AlipayRefundProbe <application.yml> <out_trade_no> 0.01
 *
 *   3) 全额退款（按查询到的订单总额退）
 *      java AlipayRefundProbe <application.yml> <out_trade_no> full
 *
 *   4) 指定 out_request_no（验证幂等：同一个号调两次只会退一笔）
 *      java AlipayRefundProbe <application.yml> <out_trade_no> full MY-REFUND-001
 * ────────────────────────────────────────────────────────
 *
 * 判定标准：
 *   · 查询返回 ACQ.TRADE_NOT_EXIST   → 单号不存在（还没付过款）
 *   · 查询返回 TRADE_SUCCESS / TRADE_FINISHED → 可以退款
 *   · 退款返回 isSuccess=true 且 fund_change=Y → 资金确实退回
 *   · 退款返回 ACQ.TRADE_HAS_SUCCESS 等 → 看 subMsg
 *   · 返回 invalid-app-id / sign check fail → 配置问题
 */
public class AlipayRefundProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法:");
            System.out.println("  java AlipayRefundProbe <application.yml> <out_trade_no>                # 只查询");
            System.out.println("  java AlipayRefundProbe <application.yml> <out_trade_no> 0.01           # 部分退款");
            System.out.println("  java AlipayRefundProbe <application.yml> <out_trade_no> full           # 全额退款");
            System.out.println("  java AlipayRefundProbe <application.yml> <out_trade_no> full <退款单号>  # 指定 out_request_no");
            return;
        }
        String ymlPath = args[0];
        String outTradeNo = args[1];
        String amountArg = args.length > 2 ? args[2].trim() : null;
        // 默认用「支付单号 + -REFUND」这种稳定派生，重试复用同一个号 —— 支付宝按 out_request_no 幂等
        String outRequestNo = args.length > 3 ? args[3].trim() : outTradeNo + "-REFUND";

        String yml = new String(Files.readAllBytes(Paths.get(ymlPath)), StandardCharsets.UTF_8);

        String appId = pick(yml, "app-id");
        String privateKey = pick(yml, "app-private-key");
        String publicKey = pick(yml, "alipay-public-key");
        String gateway = pick(yml, "gateway-url");

        System.out.println("================ 环境 ================");
        System.out.println("gateway      : " + gateway);
        System.out.println("app-id       : " + appId);
        System.out.println("out_trade_no : " + outTradeNo);

        DefaultAlipayClient client = new DefaultAlipayClient(
                gateway, appId, privateKey, "JSON", "UTF-8", publicKey, "RSA2");
        // 退款常在事务内发起，必须限制超时，避免外部接口卡住
        client.setConnectTimeout(3000);
        client.setReadTimeout(10000);

        // ---------- ① 先查交易状态 ----------
        System.out.println();
        System.out.println("================ ① alipay.trade.query ================");
        String tradeStatus = null;
        String totalAmount = null;
        try {
            AlipayTradeQueryRequest req = new AlipayTradeQueryRequest();
            AlipayTradeQueryModel m = new AlipayTradeQueryModel();
            m.setOutTradeNo(outTradeNo);
            req.setBizModel(m);
            AlipayTradeQueryResponse resp = client.execute(req);
            System.out.println("success     : " + resp.isSuccess());
            System.out.println("code/subCode: " + resp.getCode() + " / " + resp.getSubCode());
            System.out.println("subMsg      : " + resp.getSubMsg());
            System.out.println("tradeNo     : " + resp.getTradeNo());
            System.out.println("tradeStatus : " + resp.getTradeStatus());
            System.out.println("totalAmount : " + resp.getTotalAmount());
            System.out.println("buyerLogonId: " + resp.getBuyerLogonId());
            tradeStatus = resp.getTradeStatus();
            totalAmount = resp.getTotalAmount();
        } catch (Exception e) {
            System.out.println("调用异常: " + e.getClass().getName() + " - " + e.getMessage());
            return;
        }

        if (amountArg == null) {
            System.out.println();
            System.out.println("[只查询模式] 未传退款金额，结束。要退款请追加金额或 full。");
            return;
        }

        // ---------- ② 退款前先判断这笔是否可退 ----------
        System.out.println();
        System.out.println("================ ② 可退性检查 ================");
        if (tradeStatus == null) {
            System.out.println("✗ 交易不存在或未支付，无法退款。");
            System.out.println("  沙箱联调请先用沙箱买家账号完成一笔支付，拿到真实的 out_trade_no。");
            return;
        }
        if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
            System.out.println("✗ 当前交易状态为 " + tradeStatus + "，只有 TRADE_SUCCESS / TRADE_FINISHED 可退款。");
            return;
        }
        System.out.println("✓ 交易状态 " + tradeStatus + "，可退款。");

        String refundAmount = "full".equalsIgnoreCase(amountArg) ? totalAmount : amountArg;
        if (refundAmount == null || refundAmount.trim().isEmpty()) {
            System.out.println("✗ 未能取得订单总额，无法全额退款，请显式传金额。");
            return;
        }

        // ---------- ③ 退款 ----------
        System.out.println();
        System.out.println("================ ③ alipay.trade.refund ================");
        System.out.println("refund_amount  : " + refundAmount);
        System.out.println("out_request_no : " + outRequestNo);
        try {
            AlipayTradeRefundRequest req = new AlipayTradeRefundRequest();
            AlipayTradeRefundModel m = new AlipayTradeRefundModel();
            m.setOutTradeNo(outTradeNo);
            m.setRefundAmount(refundAmount);
            m.setOutRequestNo(outRequestNo);
            m.setRefundReason("用户取消求助");
            req.setBizModel(m);
            AlipayTradeRefundResponse resp = client.execute(req);

            System.out.println("success      : " + resp.isSuccess());
            System.out.println("code/subCode : " + resp.getCode() + " / " + resp.getSubCode());
            System.out.println("subMsg       : " + resp.getSubMsg());
            System.out.println("tradeNo      : " + resp.getTradeNo());
            System.out.println("refundFee    : " + resp.getRefundFee());
            System.out.println("fundChange   : " + resp.getFundChange() + "   (Y = 资金确实发生变动)");
            System.out.println("gmtRefundPay : " + resp.getGmtRefundPay());
            System.out.println("buyerLogonId : " + resp.getBuyerLogonId());

            System.out.println();
            if (resp.isSuccess() && "Y".equals(resp.getFundChange())) {
                System.out.println("✓ 退款成功，资金已原路退回。");
                System.out.println("  可再用同一个 out_request_no 跑一次：支付宝会返回同一笔结果，不会重复退款（这就是幂等）。");
            } else if (resp.isSuccess()) {
                System.out.println("△ 接口受理成功但 fund_change 不是 Y，通常表示该笔退款未产生实际资金变动，请核对账单。");
            } else {
                System.out.println("✗ 退款失败，看上面 subCode / subMsg。");
            }
        } catch (Exception e) {
            System.out.println("调用异常: " + e.getClass().getName() + " - " + e.getMessage());
        }
    }

    /** 从 application.yml 里按 key 取值（够用即可，不引入 YAML 库） */
    private static String pick(String yml, String key) {
        Matcher m = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE)
                .matcher(yml);
        if (m.find()) {
            String v = m.group(1).trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            return v;
        }
        return null;
    }
}
