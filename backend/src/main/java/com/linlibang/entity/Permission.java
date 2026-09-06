package com.linlibang.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限实体（RBAC 功能层："你能做什么"）
 * 如：help:publish 发布求助 / order:accept 接单 / message:send 发送私信
 */
@Data
public class Permission {

    /** 权限ID */
    private Long id;

    /** 权限码（对应 @SaCheckPermission 的值） */
    private String code;

    /** 功能名 */
    private String name;

    /** 类型 1接口 2按钮 3菜单 */
    private Integer type;

    /** 排序 */
    private Integer sort;

    /** 创建时间 */
    private LocalDateTime createTime;
}
