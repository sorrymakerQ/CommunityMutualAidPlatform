package com.linlibang.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接单申请实体（老板审批制）
 * 邻居申请接单 → 发布者（老板）同意后生成订单并占名额
 */
@Data
public class HelpApply {

    /** 申请ID */
    private Long id;

    /** 求助ID */
    private Long helpId;

    /** 申请接单者ID */
    private Long helperId;

    /** 状态：0待确认 1已同意 2已拒绝 */
    private Integer status;

    /** 审批理由（老板同意/拒绝时的说明，可空） */
    private String handleReason;

    /** 申请时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
