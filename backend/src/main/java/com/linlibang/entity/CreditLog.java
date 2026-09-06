package com.linlibang.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 信用分流水实体（信用分增减全记录，缺分/漏分可查）
 */
@Data
public class CreditLog {

    /** 流水ID */
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 变动值（正加负减） */
    private Integer delta;

    /** 变动原因：评价5星/评价低分/完成订单/取消订单 */
    private String reason;

    /** 关联订单ID（可空） */
    private Long orderId;

    /** 变动时间 */
    private LocalDateTime createTime;
}
