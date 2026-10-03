package com.linlibang.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.alipay.api.*;
import com.alipay.api.domain.AlipayTradeAppPayModel;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.linlibang.config.RocketMQConfig;
import com.linlibang.config.alipayConfig;
import com.linlibang.dto.Result;
import com.linlibang.entity.HelpRequest;
import com.linlibang.entity.Order;
import com.linlibang.entity.PayOrder;
import com.linlibang.mapper.HelpRequestMapper;
import com.linlibang.mapper.OrderMapper;
import com.linlibang.mapper.PayOrderMapper;
import com.linlibang.mapper.UserMapper;
import com.linlibang.service.PayService;
import com.linlibang.utils.RedisUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;


/**
 * 支付服务实现（余额支付 + 支付宝）
 */
@Slf4j
@Service
public class PayServiceImpl implements PayService {


    @Resource
    private alipayConfig alipayConfig;


    @Resource
    private OrderMapper orderMapper;

    /** 求助详情缓存前缀 */
    private static final String HELP_ITEM_KEY = "help:item:";

    @Resource
    private HelpRequestMapper helpRequestMapper;


    @Resource
    private PayOrderMapper payOrderMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private RedisUtils redisUtils;
    @Autowired
    private RocketMQTemplate rocketMQTemplate;


    //余额图款
    @Override
    @Transactional
    public Result refundOrder(Long helpId)  {
        // 操作者取自当前登录态（退款逻辑本身未改动）
        Long publisherId = StpUtil.getLoginIdAsLong();
        // 校验支付订单
        PayOrder payOrder = payOrderMapper.selectByHelpId(helpId);
        if (payOrder == null) {
            return Result.fail("支付订单不存在");
        }
        if (payOrder.getStatus() != 1) {
            return Result.fail(payOrder.getStatus() == 2 ? "该订单已退款" : "该订单未支付，无需退款");
        }
        //求助是否存在并且是否是自已的求助
        HelpRequest help = helpRequestMapper.selectById(helpId);
        if (help == null) {
            return Result.fail("求助不存在");
        }
        if (!help.getUserId().equals(publisherId)) {
            return Result.fail("只能退款自己发布的求助");
        }
        BigDecimal refundAmount = payOrder.getAmount();
        if(payOrder.getChannel() != null && payOrder.getChannel() == 0){
            Long completedCount = orderMapper.countCompletedByHelpId(helpId);
            if (completedCount != null && completedCount > 0 && help.getReward() != null) {
                refundAmount = refundAmount.subtract(
                        help.getReward().multiply(BigDecimal.valueOf(completedCount)));
            }
            if (refundAmount.signum() > 0) {
                userMapper.addBalance(publisherId, refundAmount);
            }
        }else if (payOrder.getChannel() != null && payOrder.getChannel() == 1){
            //跟支付流程一样 订单号和支付宝号二选一
            try{
                String trade_no = payOrder.getPayNo();
                BigDecimal refund = payOrder.getAmount();
                String refundReason = "用户主动退款";
                AlipayClient client = new DefaultAlipayClient(alipayConfig.getGatewayUrl(), alipayConfig.getAppId(), alipayConfig.getAppPrivateKey(), alipayConfig.getFormat(), alipayConfig.getCharset(), alipayConfig.getAlipayPublicKey(),alipayConfig.getSignType());
                AlipayTradeRefundRequest alipay_request = new AlipayTradeRefundRequest();
                AlipayTradeRefundModel model=new AlipayTradeRefundModel();
                model.setOutTradeNo(trade_no);
                model.setRefundAmount(refund.toString());
                model.setRefundReason(refundReason);
                model.setOutRequestNo(payOrder.getPayNo() + "-REFUND");
                alipay_request.setBizModel(model);
                AlipayTradeRefundResponse alipay_response = client.execute(alipay_request);
                log.info("支付宝退款响应：{}", alipay_response.getBody());
                if(alipay_response.isSuccess()){
                    log.info("支付宝退款成功");
                }
            }catch  (AlipayApiException e) {
                log.error("退款失败: helpId={}", helpId, e);
                throw new IllegalStateException("退款失败：" + e.getMessage(), e);
            }
        }else{
            log.info("支付异常");
            return Result.fail("支付异常");
        }
        return Result.ok("退款成功，已退回余额", refundAmount);
    }

    @Override
    public Result balancepay(Long helpId) {
        Long userId = StpUtil.getLoginIdAsLong();
        // 校验求助是否存在
        HelpRequest help = helpRequestMapper.selectById(helpId);
        if (help == null) {
            return Result.fail("求助不存在");
        }
        //校验是否是自已发的求助
        if (!help.getUserId().equals(userId)) {
            return Result.fail("只能支付自己发布的求助");
        }
        //校验求助是否存在支付
        if (help.getStatus() != 0) {
            return Result.fail(help.getStatus() == 1 ? "该求助已支付，正在招募中" : "该求助当前状态不可支付");
        }
        //校验支付订单
        PayOrder payOrder = payOrderMapper.selectByHelpId(helpId);
        if (payOrder == null) {
            return Result.fail("支付订单不存在");
        }
        //查看订单是否被支付
        if (payOrder.getStatus() != 0) {
            return Result.fail(payOrder.getStatus() == 1 ? "该求助已支付，请勿重复支付" : "该支付订单已取消");
        }
        //校验余额是否为零
        if (payOrder.getAmount() == null || payOrder.getAmount().signum() < 0) {
            return Result.fail("支付金额非法，请联系客服");
        }
        //余额扣减
        int rows = userMapper.deductBalance(userId, payOrder.getAmount());
        if (rows == 0) {
            return Result.fail("余额不足，请先充值");
        }

        if (payOrderMapper.markPaid(helpId, PayOrder.CHANNEL_BALANCE) == 0) {
            return Result.fail("该求助已支付，请勿重复支付");
        }
        helpRequestMapper.markPaid(helpId);
        payOrder.setStatus(1);
        payOrder.setChannel(PayOrder.CHANNEL_BALANCE);
        rocketMQTemplate.convertAndSend(RocketMQConfig.PAY_TOPIC, payOrder);

        return Result.ok("支付成功，求助已发布到首页", payOrder.getAmount());
    }

    @Override
    public Result alipay(Long helpId) {
        HelpRequest help = helpRequestMapper.selectById(helpId);
        if (help == null) {
            return Result.fail("求助不存在");
        }
        // 支付阶段的单据是 tb_pay_order：tb_order 要等人接单才生成，此刻必然为空
        // （原实现从这里取 order.getOrderNo()，必然 NPE，收银台根本出不来）
        PayOrder payOrder = payOrderMapper.selectByHelpId(helpId);
        if (payOrder == null) {
            return Result.fail("支付订单不存在");
        }
        if (payOrder.getStatus() != 0) {
            return Result.fail(payOrder.getStatus() == 1 ? "该求助已支付，请勿重复支付" : "该支付订单已取消");
        }
        // 从配置类读取支付宝参数
        AlipayClient alipayClient = new DefaultAlipayClient(
                alipayConfig.getGatewayUrl(),
                alipayConfig.getAppId(),
                alipayConfig.getAppPrivateKey(),
                alipayConfig.getFormat(),
                alipayConfig.getCharset(),
                alipayConfig.getAlipayPublicKey(),
                alipayConfig.getSignType());
        AlipayTradePagePayRequest alipayRequest = new AlipayTradePagePayRequest();
        AlipayTradeAppPayModel model = new AlipayTradeAppPayModel();
        // out_trade_no 用商户支付单号：异步通知靠它 selectByPayNo 反查；
        // 同一笔交易重试必须复用同一个号，否则支付宝侧会生成两笔订单
        model.setOutTradeNo(payOrder.getPayNo());
        model.setTotalAmount(payOrder.getAmount().toPlainString());
        model.setSubject(String.valueOf(help.getTitle()));
        model.setProductCode("FAST_INSTANT_TRADE_PAY");
        model.setTimeoutExpress("30m");
        alipayRequest.setBizModel(model);
        alipayRequest.setNotifyUrl(alipayConfig.getNotifyUrl());
        alipayRequest.setReturnUrl(alipayConfig.getReturnUrl());
        String payResult;
        try {
            payResult = alipayClient.pageExecute(alipayRequest).getBody();
            log.info("支付宝收银台表单已生成: helpId={}, payNo={}", helpId, payOrder.getPayNo());
        } catch (Exception e) {
            log.error("支付宝支付失败: helpId={}, payNo={}", helpId, payOrder.getPayNo(), e);
            throw new RuntimeException(e);
        }
        return Result.ok("支付成功", payResult);
    }

    @Override
    @Transactional
    /**
     * 支付宝异步通知：验签 → 二次校验 → 幂等更新（支付单 0→1、求助 0→1）
     *
     * @return true = 已确认收到（含重复通知/无需处理的场景，支付宝停止重推）
     *         false = 处理失败，响应 fail 让支付宝按 4m/10m/.../15h 重推
     */
    public Boolean notify(HttpServletRequest notifyData) throws AlipayApiException {
        log.info("支付宝回调");

        // 获取支付宝 POST 过来反馈信息，将异步通知中收到的待验证所有参数都存放到 map 中
        Map<String, String> params = new HashMap<String, String>();
        // 获取请求参数Map
        Map requestParams = notifyData.getParameterMap();
        // 遍历请求参数Map，将参数名和参数值存入params
        for (Iterator iter = requestParams.keySet().iterator(); iter.hasNext(); ) {
            String name = (String) iter.next(); // 获取参数名
            String[] values = (String[]) requestParams.get(name); // 获取参数值数组
            String valueStr = "";
            // 遍历参数值数组，将多个参数值用逗号拼接
            for (int i = 0; i < values.length; i++) {
                valueStr = (i == values.length - 1) ? valueStr + values[i] : valueStr + values[i] + ",";
            }
            params.put(name, valueStr); // 将参数名和拼接后的参数值存入params
        }
        // 使用支付宝提供的验签方法验证签名
        boolean signVerified = AlipaySignature.rsaCheckV1(params, alipayConfig.getAlipayPublicKey(), alipayConfig.getCharset(),alipayConfig.getSignType());
        if (signVerified && params.get("trade_status").equals("TRADE_SUCCESS")) {
            // 1. 查询订单
            String outTradeNo = params.get("out_trade_no"); // 获取商户订单号
            PayOrder payOrder = payOrderMapper.selectByPayNo(outTradeNo);
            if(payOrder==null){
                log.error("支付订单号不存在");
                return false;
            }

            if(new BigDecimal(params.get("total_amount")).compareTo(payOrder.getAmount()) != 0){
                log.error("支付宝回调总金额出错");
                return false;
            }
            if(!alipayConfig.getSellerId().equals(params.get("seller_id"))){
                log.error("支付宝回调卖家账号出错");
                return false;
            }
            // 3. 更新订单状态
            HelpRequest help = helpRequestMapper.selectById(payOrder.getHelpId());
            if(help == null){
                log.error("求助不存在");
                return false;
            }
            rocketMQTemplate.asyncSend(RocketMQConfig.PAY_TOPIC, payOrder, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    log.info("支付成功事件投递成功: helpId={}", payOrder.getHelpId());
                }

                @Override
                public void onException(Throwable e) {
                    // 异步发送失败只能在这里感知：记日志（
                    log.error("支付成功事件投递失败: helpId={}", payOrder.getHelpId(), e);
                }
            });

            Integer row = payOrderMapper.markPaid(payOrder.getHelpId(), PayOrder.CHANNEL_ALIPAY);
            Integer row2 = helpRequestMapper.markPaid(payOrder.getHelpId());
            log.info("支付宝异步通知处理完成: helpId={}, payNo={}, row={}, row2={}", payOrder.getHelpId(), outTradeNo, row, row2);
            if (row == 0) {
                log.info("支付宝重复通知，已幂等跳过: helpId={}, payNo={}", payOrder.getHelpId(), outTradeNo);
                return true;
            }
            if (row2 == 0) {
                log.warn("支付单已置为已支付，但求助非待支付状态（可能被管理员改过）: helpId={}", payOrder.getHelpId());
            }
            return true;
            }
        return false;
        }
    }



