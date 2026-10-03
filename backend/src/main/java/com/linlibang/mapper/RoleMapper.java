package com.linlibang.mapper;

import com.linlibang.entity.Role;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 角色 Mapper（RBAC 身份层）
 */
@Mapper
public interface RoleMapper {

    /**
     * 根据ID查询角色
     */
    @Select("SELECT * FROM tb_role WHERE id = #{id}")
    Role selectById(@Param("id") Long id);

    /**
     * 根据编码查询角色（如 admin/user）
     */
    @Select("SELECT * FROM tb_role WHERE code = #{code} AND status = 1")
    Role selectByCode(@Param("code") String code);

    /**
     * 查询用户的角色编码列表（Sa-Token getRoleList 用）
     */
    @Select("SELECT r.code FROM tb_role r " +
            "JOIN tb_user u ON u.role_id = r.id " +
            "WHERE u.id = #{userId} AND r.status = 1")
    List<String> selectCodesByUserId(@Param("userId") Long userId);

    /**
     * 查询所有角色（管理端分配角色用）
     */
    @Select("SELECT * FROM tb_role WHERE status = 1 ORDER BY id")
    List<Role> selectAll();
}
