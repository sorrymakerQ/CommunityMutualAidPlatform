package com.linlibang.mapper;

import org.apache.ibatis.annotations.*;

/**
 * 角色-权限关联 Mapper（RBAC 关联层）
 * 表结构：tb_role_permission(role_id, permission_id)，联合主键
 */
@Mapper
public interface RolePermissionMapper {

    /**
     * 为角色绑定一个权限（忽略重复绑定）
     */
    @Insert("INSERT IGNORE INTO tb_role_permission (role_id, permission_id) VALUES (#{roleId}, #{permissionId})")
    int insert(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId);

    /**
     * 解除角色与权限的绑定
     */
    @Delete("DELETE FROM tb_role_permission WHERE role_id = #{roleId} AND permission_id = #{permissionId}")
    int delete(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId);

    /**
     * 删除某角色的全部权限绑定（改角色权限时先清后插）
     */
    @Delete("DELETE FROM tb_role_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);
}
