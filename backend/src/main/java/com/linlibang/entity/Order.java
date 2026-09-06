package com.linlibang.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单实体
 */
@Data
public class Order {

    /** 主键ID */
    private Long id;

    /** 关联的求助ID */
    private Long helpId;

    /** 发布者用户ID */
    private Long publisherId;

    /** 接单者用户ID */
    private Long helperId;

    /** 状态：1已接单 2进行中 3已完成 4已取消 5已评价 */
    private Integer status;

    /**
     * 接单序号（唯一键 uk_help_helper_seq 的组成部分）：
     * 活跃订单 = 1，取消订单 = 0。
     * 取消时置 0 释放唯一键位，同一人取消后可重新接同一求助；
     * 支持多次"取消-重接"时由取消逻辑自增（当前业务一轮即可）。
     */
    private Integer seq;

    /** 取消原因 */
    private String cancelReason;

    /** 接单时间 */
    private LocalDateTime acceptTime;

    /** 完成时间 */
    private LocalDateTime finishTime;

    /** 发布者对帮助者的评分 */
    private Integer publisherScore;

    /** 帮助者对发布者的评分 */
    private Integer helperScore;

    /** 发布者对帮助者的评价 */
    private String publisherComment;

    /** 帮助者对发布者的评价 */
    private String helperComment;

    /** 乐观锁版本号（每次修改 +1，评价/取消/完成时并发更新校验） */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
