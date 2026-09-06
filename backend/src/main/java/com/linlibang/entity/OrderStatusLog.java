package com.linlibang.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单状态审计实体（谁在何时把订单从什么状态改成什么状态）
 */
@Data
public class OrderStatusLog {

    /** 流水ID */
    private Long id;

    /** 订单ID */
    private Long orderId;

    /** 变更前状态（创建时为 NULL） */
    private Integer fromStatus;

    /** 变更后状态 */
    private Integer toStatus;

    /** 操作人ID（系统操作为 NULL） */
    private Long operatorId;

    /** 操作方：USER-用户 SYSTEM-系统 */
    private String operatorType;

    /** 变更原因 */
    private String reason;

    /** 变更时间 */
    private LocalDateTime createTime;
}
