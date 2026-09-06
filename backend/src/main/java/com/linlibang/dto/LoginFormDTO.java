package com.linlibang.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 登录表单DTO
 * 账号支持：11 位手机号 或 系统内置账号（如超级管理员 super）
 */
@Data
public class LoginFormDTO {

    /** 账号：手机号 或 内置账号名（super） */
    @NotBlank(message = "账号不能为空")
    @Size(max = 20, message = "账号长度不能超过20位")
    private String phone;

    /** 密码 */
    @NotBlank(message = "密码不能为空")
    private String password;
}
