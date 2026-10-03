package com.linlibang.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付订单实体（发起求助时生成，支付成功后求助才上首页）
 *
 * 一个求助对应一笔支付单（uk_help_id 兜底），但可能对应 N 条 tb_order（履约单）。
 */
@Data
public class PayOrder {

    /** 支付渠道：余额 */
    public static final int CHANNEL_BALANCE = 0;

    /** 支付渠道：支付宝 */
    public static final int CHANNEL_ALIPAY = 1;

    /** 支付订单ID */
    private Long id;

    /** 商户支付单号（对外唯一，作支付宝 out_trade_no；回调按它反查本单） */
    private String payNo;

    /** 关联求助ID */
    private Long helpId;

    /** 发布者ID */
    private Long publisherId;

    /** 支付总额（单价×需要人数） */
    private BigDecimal amount;

    /** 状态：0待支付 1已支付 2已取消 */
    private Integer status;

    /**
     * 支付渠道：0余额 1支付宝（见 CHANNEL_* 常量）。
     * NULL = 尚未支付 —— 渠道在支付成功那一刻才确定，创建支付单时还不知道用户会选哪条路。
     */
    private Integer channel;

    /** 支付时间 */
    private LocalDateTime payTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}
