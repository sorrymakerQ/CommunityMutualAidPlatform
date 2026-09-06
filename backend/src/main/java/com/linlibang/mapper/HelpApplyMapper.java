package com.linlibang.mapper;

import com.linlibang.entity.HelpApply;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 接单申请 Mapper（老板审批制）
 */
@Mapper
public interface HelpApplyMapper {

    /**
     * 提交申请（同一求助同一人唯一；已拒绝者可重新申请）
     */
    @Insert("INSERT INTO tb_help_apply (help_id, helper_id, status, create_time) " +
            "VALUES (#{helpId}, #{helperId}, 0, NOW()) " +
            "ON DUPLICATE KEY UPDATE status = 0, update_time = NOW()")
    int insertOrReopen(@Param("helpId") Long helpId, @Param("helperId") Long helperId);

    /**
     * 查询某人的申请状态（是否已申请/同意/拒绝）
     */
    @Select("SELECT * FROM tb_help_apply WHERE help_id = #{helpId} AND helper_id = #{helperId}")
    HelpApply selectByHelpAndHelper(@Param("helpId") Long helpId, @Param("helperId") Long helperId);

    /**
     * 按ID查申请
     */
    @Select("SELECT * FROM tb_help_apply WHERE id = #{id}")
    HelpApply selectById(@Param("id") Long id);

    /**
     * 某求助的申请列表（发布者审批用）
     */
    @Select("SELECT * FROM tb_help_apply WHERE help_id = #{helpId} ORDER BY status, create_time")
    List<HelpApply> selectByHelpId(@Param("helpId") Long helpId);

    /**
     * 状态机 CAS：仅当处于 expectedStatus 时更新为 newStatus（0→1 同意 / 0→2 拒绝 / 1→2 满员自动拒绝）
     */
    @Update("UPDATE tb_help_apply SET status = #{newStatus}, update_time = NOW() " +
            "WHERE id = #{id} AND status = #{expectedStatus}")
    int updateStatusIf(@Param("id") Long id,
                       @Param("expectedStatus") Integer expectedStatus,
                       @Param("newStatus") Integer newStatus);

    /**
     * 老板审批：状态机 CAS + 记录审批理由（同意/拒绝时调用）
     */
    @Update("UPDATE tb_help_apply SET status = #{newStatus}, handle_reason = #{reason}, update_time = NOW() " +
            "WHERE id = #{id} AND status = #{expectedStatus}")
    int approveOrReject(@Param("id") Long id,
                        @Param("expectedStatus") Integer expectedStatus,
                        @Param("newStatus") Integer newStatus,
                        @Param("reason") String reason);

    /**
     * 求助结束时把待确认申请批量置为已拒绝（满员自动拒绝 / 求助取消作废）
     */
    @Update("UPDATE tb_help_apply SET status = 2, update_time = NOW() " +
            "WHERE help_id = #{helpId} AND status = 0")
    int rejectPendingByHelpId(@Param("helpId") Long helpId);
}
