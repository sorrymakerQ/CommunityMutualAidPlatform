package com.linlibang.service;

import com.linlibang.dto.Result;

/**
 * 支付服务（简单余额支付，不调外部支付 API）
 *
 * 资金方法族（全链路 BigDecimal + 单事务回滚）：
 *   createOrder —— 发布求助时生成待支付单（等价资源预占，见 publishHelp）
 *   yuePay     —— 余额支付：扣余额 CAS + 支付单 0→1 + 业务生效（即 pay 方法）
 *   paySuccess —— 支付成功收尾（并入 yuePay 事务尾部）
 *   refundOrder—— 退款：业务取消时原路退回余额 + 支付单 1→2，全套回滚
 */
public interface PayService {

    /**
     * 余额支付（yuePay）：发布者余额扣减 → 支付订单已支付 → 求助上首页
     */
    Result pay(Long helpId, Long userId);

    /**
     * 退款（refundOrder）：取消已支付求助时原路退回余额
     * 幂等：支付单已取消/未支付则直接作废；事务内任一步失败全套回滚
     */
    Result refundOrder(Long helpId, Long publisherId);
}
