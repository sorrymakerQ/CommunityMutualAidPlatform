package com.linlibang.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户信用分实体（从 tb_user 垂直拆分，user_id 主键一对一）
 *
 * 与 tb_credit_log 流水表配合：
 *   本表只存"当前值"，每次增减同步插一条流水，缺分/漏分可对账。
 */
@Data
public class UserCredit {

    /** 用户ID（主键，同时是外键 → tb_user.id） */
    private Long userId;

    /** 当前信用分（初始 100） */
    private Integer credit;

    /** 最后变更时间 */
    private LocalDateTime updateTime;
}
