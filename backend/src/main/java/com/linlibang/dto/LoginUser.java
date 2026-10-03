package com.linlibang.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 登录用户信息（存放在 Sa-Token 会话里，由框架托管，不再手写 Redis 缓存）
 *
 * 只放身份相关的轻量字段：不含余额、不含密码。
 * 通过 {@link com.linlibang.utils.AuthUtil#getLoginUser()} 获取。
 */
@Data
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long id;

    /** 登录账号（手机号，内置管理员为 super） */
    private String phone;

    /** 昵称 */
    private String nickname;

    /** 头像URL */
    private String avatar;

    /** 性别：0未知 1男 2女 */
    private Integer gender;

    /** 角色ID：1管理员 2普通用户 */
    private Long roleId;

    /** 角色编码：admin / user */
    private String roleCode;

    /** 兼容叫法：用户名（即昵称），方便 loginUser.getUsername() 取用 */
    public String getUsername() {
        return nickname;
    }

    /** 是否管理员（供业务代码做粗粒度判断；精确鉴权仍走 @SaCheckPermission） */
    public boolean isAdmin() {
        return "admin".equals(roleCode);
    }
}
