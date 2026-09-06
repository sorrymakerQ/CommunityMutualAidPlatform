package com.linlibang.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.linlibang.service.AdminService;
import com.linlibang.dto.Result;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/admin")
public class AdminController {

    @Resource
    private AdminService adminService;

    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @GetMapping("/stats")
    public Result getStats() {
        return adminService.getStats();
    }

    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @GetMapping("/users")
    public Result getUserList(
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size) {
        return adminService.getUserList(page, size);
    }

    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @PutMapping("/user/{id}/status")
    public Result updateUserStatus(@PathVariable Long id, @RequestParam Integer status) {
        return adminService.updateUserStatus(id, status);
    }

    /** 角色列表（管理端分配角色用） */
    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @GetMapping("/roles")
    public Result getRoleList() {
        return adminService.getRoleList();
    }

    /** 为用户分配角色（RBAC：改角色即改权限集） */
    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @PutMapping("/user/{id}/role")
    public Result updateUserRole(@PathVariable Long id, @RequestParam Long roleId) {
        if (roleId == null) {
            return Result.fail("角色不能为空");
        }
        return adminService.updateUserRole(id, roleId);
    }

    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @GetMapping("/helps")
    public Result getHelpList(
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size,
            @RequestParam(required = false) Integer status) {
        return adminService.getHelpList(page, size, status);
    }

    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @DeleteMapping("/help/{id}")
    public Result deleteHelp(@PathVariable Long id) {
        return adminService.deleteHelp(id);
    }
}
