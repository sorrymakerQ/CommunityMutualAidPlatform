package com.linlibang.dto;

import lombok.Data;

/**
 * 用户信息 DTO（脱敏，不含密码）
 */
@Data
public class UserDTO {

    private Long id;

    private String phone;

    private String nickname;

    private String avatar;

    /** 性别：0-未知，1-男，2-女 */
    private Integer gender;

    /** 所在小区 */
    private String community;

    /** 经度 */
    private Double lng;

    /** 纬度 */
    private Double lat;

    /** 信用分 */
    private Integer credit;

    /** 累计帮助次数 */
    private Integer helpCount;

    /** 账户余额（元） */
    private java.math.BigDecimal balance;

    /** 个人简介 */
    private String intro;

    /** 角色ID（关联 tb_role） */
    private Long roleId;

    /** 角色编码（如 admin/user/reviewer，前端判断权限用） */
    private String roleName;
}
