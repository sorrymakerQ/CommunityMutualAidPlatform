package com.linlibang.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付订单实体（发起求助时生成，余额支付成功后求助才上首页）
 */
@Data
public class PayOrder {

    /** 支付订单ID */
    private Long id;

    /** 关联求助ID */
    private Long helpId;

    /** 发布者ID */
    private Long publisherId;

    /** 支付总额（单价×需要人数） */
    private BigDecimal amount;

    /** 状态：0待支付 1已支付 2已取消 */
    private Integer status;

    /** 支付时间 */
    private LocalDateTime payTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}
