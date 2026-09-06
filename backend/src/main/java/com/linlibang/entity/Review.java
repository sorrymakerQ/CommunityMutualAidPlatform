package com.linlibang.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评价实体（独立评价模块）
 * 每笔订单最多两条：发布者评接单者、接单者评发布者
 */
@Data
public class Review {

    /** 评价ID */
    private Long id;

    /** 关联订单ID */
    private Long orderId;

    /** 评价人ID */
    private Long fromUserId;

    /** 被评价人ID */
    private Long toUserId;

    /** 评分 1-5 */
    private Integer score;

    /** 评价内容 */
    private String comment;

    /** 评价时间 */
    private LocalDateTime createTime;
}
