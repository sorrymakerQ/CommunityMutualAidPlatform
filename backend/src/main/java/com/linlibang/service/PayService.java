package com.linlibang.service;

import com.alipay.api.AlipayApiException;
import com.linlibang.dto.Result;

import javax.servlet.http.HttpServletRequest;

/**
 * 支付服务（余额支付 + 支付宝）
 *
 * 资金方法族（全链路 BigDecimal + 单事务回滚）：
 *   createOrder —— 发布求助时生成待支付单（等价资源预占，见 publishHelp）
 *   yuePay     —— 余额支付：扣余额 CAS + 支付单 0→1 + 业务生效（即 balancepay 方法）
 *   paySuccess —— 支付成功收尾（并入 yuePay 事务尾部）
 *   refundOrder—— 退款：业务取消时按渠道原路退回，全套回滚
 *
 * 操作者（发布者）统一由实现类用 StpUtil.getLoginIdAsLong() 获取，不再由上层传入。
 */
public interface PayService {

    /**
     * 退款（refundOrder）：取消已支付求助时按支付渠道原路退回
     */
    Result refundOrder(Long helpId) ;

    //余额支付
    Result balancepay(Long helpId);

    //支付宝支付 发送请求
    Result alipay(Long helpId);


    //支付宝支付回调
    Boolean notify(HttpServletRequest notifyData) throws AlipayApiException;
}
