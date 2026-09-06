package com.linlibang.config;

import cn.dev33.satoken.stp.StpInterface;
import com.linlibang.mapper.PermissionMapper;
import com.linlibang.mapper.RoleMapper;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 权限扩展——基于 RBAC 三张表动态加载角色和权限
 * 每次鉴权时触发：角色查 tb_role，权限码查 tb_role_permission JOIN tb_permission
 * （登录态存储维持现状，不依赖 Redis 缓存快照）
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    @Resource
    private RoleMapper roleMapper;

    @Resource
    private PermissionMapper permissionMapper;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long userId = Long.valueOf(loginId.toString());
        List<String> codes = permissionMapper.selectCodesByUserId(userId);
        return codes != null ? codes : Collections.emptyList();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = Long.valueOf(loginId.toString());
        List<String> roles = roleMapper.selectCodesByUserId(userId);
        return roles != null ? roles : Collections.emptyList();
    }
}
