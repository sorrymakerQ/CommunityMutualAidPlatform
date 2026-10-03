package com.linlibang.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色实体（RBAC 身份层："你是谁"）
 * 如：admin 管理员 / user 普通用户
 */
@Data
public class Role {

    /** 角色ID */
    private Long id;

    /** 角色编码（Sa-Token 校验用，小写）：admin/user */
    private String code;

    /** 角色名称：管理员/普通用户 */
    private String name;

    /** 描述 */
    private String description;

    /** 状态 1启用 0禁用 */
    private Integer status;

    /** 系统内置角色（1=内置，不可删除），如内置管理员 */
    private Integer isBuiltin;

    /** 创建时间 */
    private LocalDateTime createTime;
}
