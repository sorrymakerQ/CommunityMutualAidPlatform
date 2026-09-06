package com.linlibang.mapper;

import com.linlibang.entity.Permission;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 权限 Mapper（RBAC 功能层）
 */
@Mapper
public interface PermissionMapper {

    /**
     * 查询某角色的全部权限码（Sa-Token getPermissionList 用）
     */
    @Select("SELECT p.code FROM tb_permission p " +
            "JOIN tb_role_permission rp ON rp.permission_id = p.id " +
            "WHERE rp.role_id = #{roleId} AND p.type = 1")
    List<String> selectCodesByRoleId(@Param("roleId") Long roleId);

    /**
     * 查询某用户的全部权限码（跨角色聚合，多角色用户可用）
     */
    @Select("SELECT DISTINCT p.code FROM tb_permission p " +
            "JOIN tb_role_permission rp ON rp.permission_id = p.id " +
            "JOIN tb_user u ON u.role_id = rp.role_id " +
            "WHERE u.id = #{userId} AND p.type = 1")
    List<String> selectCodesByUserId(@Param("userId") Long userId);

    /**
     * 查询所有权限（管理端配置用）
     */
    @Select("SELECT * FROM tb_permission ORDER BY sort, id")
    List<Permission> selectAll();
}
