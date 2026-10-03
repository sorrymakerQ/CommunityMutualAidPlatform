package com.linlibang.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体
 */
@Data
public class User {

    /** 主键ID */
    private Long id;

    /** 手机号 */
    private String phone;

    /** 密码（BCrypt加密） */
    private String password;

    /** 昵称 */
    private String nickname;

    /** 头像URL */
    private String avatar;

    /** 性别：0未知 1男 2女 */
    private Integer gender;

    /** 所在地址ID（关联 tb_address 三级行） */
    private Long addressId;

    /** 帮助次数 */
    private Integer helpCount;

    /** 账户余额（支付求助总额用，简单余额体系） */
    private java.math.BigDecimal balance;

    /** 个人简介 */
    private String intro;

    /** 角色ID（关联 tb_role，默认2=普通用户） */
    private Long roleId;

    /** 状态：1正常 0禁用 */
    private Integer status;

    /** 系统内置账号（1=内置，不可禁用/删除，如超级管理员） */
    private Integer isBuiltin;

    /** 乐观锁版本号（每次修改 +1，更新时校验，不一致则重试） */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除：0未删除 1已删除 */
    private Integer isDeleted;
}
