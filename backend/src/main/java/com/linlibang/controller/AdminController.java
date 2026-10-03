package com.linlibang.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.linlibang.dto.Result;
import com.linlibang.service.AdminService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * 管理端控制器
 *
 * RBAC：所有管理接口统一按「权限码」鉴权（不再写死角色名），
 * 权限码取自 tb_permission，由 tb_role_permission 关联到角色：
 *   user:manage  — 用户管理（列表/禁用启用/改角色/角色列表）
 *   user:kickout — 踢用户下线
 *   help:manage  — 管理求助（改状态/下架/删除）
 * 目前只有 admin 角色拥有这三项权限；普通用户只有 help:publish / order:accept / message:send。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    @Resource
    private AdminService adminService;

    // ==================== 用户管理（user:manage） ====================

    @SaCheckRole("admin")
    @GetMapping("/stats")
    public Result getStats() {
        return adminService.getStats();
    }

    @SaCheckRole("admin")
    @GetMapping("/users")
    public Result getUserList(
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size) {
        return adminService.getUserList(page, size);
    }

    /** 启用/禁用用户（禁用会顺带踢下线） */
    @SaCheckRole("admin")
    @PutMapping("/user/{id}/status")
    public Result updateUserStatus(@PathVariable Long id, @RequestParam Integer status) {
        return adminService.updateUserStatus(id, status);
    }

    /** 角色列表（只有 管理员 / 普通用户） */
    @SaCheckRole("admin")
    @GetMapping("/roles")
    public Result getRoleList() {
        return adminService.getRoleList();
    }

    /** 为用户分配角色（改角色即改权限集，改完强制重新登录） */
    @SaCheckRole("admin")
    @PutMapping("/user/{id}/role")
    public Result updateUserRole(@PathVariable Long id, @RequestParam Long roleId) {
        if (roleId == null) {
            return Result.fail("角色不能为空");
        }
        return adminService.updateUserRole(id, roleId);
    }

    // ==================== 踢人下线（user:kickout） ====================

    @SaCheckRole("admin")
    @PostMapping("/user/{id}/kickout")
    public Result kickoutUser(@PathVariable Long id) {
        return adminService.kickoutUser(id);
    }

    // ==================== 求助管理（help:manage） ====================

    @SaCheckRole("admin")
    @GetMapping("/helps")
    public Result getHelpList(
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size,
            @RequestParam(required = false) Integer status) {
        return adminService.getHelpList(page, size, status);
    }

    /** 修改任意用户求助的状态（下架 = status 4） */
    @SaCheckPermission("help:manage")
    @PutMapping("/help/{id}/status")
    public Result updateHelpStatus(@PathVariable Long id, @RequestParam Integer status) {
        return adminService.updateHelpStatus(id, status);
    }

    /** 逻辑删除求助 */
    @SaCheckPermission("help:manage")
    @DeleteMapping("/help/{id}")
    public Result deleteHelp(@PathVariable Long id) {
        return adminService.deleteHelp(id);
    }
}
