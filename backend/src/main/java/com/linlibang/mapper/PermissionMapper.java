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
     * 查询某用户的全部权限码（Sa-Token getPermissionList 用）
     *
     * 单角色模型：tb_user.role_id 为单值 + tb_role_permission 主键 (role_id, permission_id) 唯一
     * ⇒ 结果天然去重，禁止加 DISTINCT（会引入临时表去重，实测 2.03ms → 0.07ms）
     */
    @Select("SELECT p.code FROM tb_permission p " +
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
