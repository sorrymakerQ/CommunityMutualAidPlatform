package com.linlibang.mapper;

import com.linlibang.entity.HelpRequest;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 求助 Mapper 接口（MyBatis 注解方式）
 */
@Mapper
public interface HelpRequestMapper {

    /**
     * 插入求助，自动回填ID
     */
    @Insert("INSERT INTO tb_help_request " +
            "(user_id, category_id, title, description, images, reward, total_reward, address_id, address_detail, " +
            "status, helper_num, accepted_num, urgent, view_count, create_time, update_time, is_deleted) " +
            "VALUES (#{userId}, #{categoryId}, #{title}, #{description}, #{images}, #{reward}, #{totalReward}, " +
            "#{addressId}, #{addressDetail}, #{status}, #{helperNum}, #{acceptedNum}, #{urgent}, #{viewCount}, NOW(), NOW(), 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(HelpRequest help);

    /**
     * 根据ID查询求助
     */
    @Select("SELECT * FROM tb_help_request WHERE id = #{id} AND is_deleted = 0")
    HelpRequest selectById(@Param("id") Long id);

    /**
     * 批量查询求助（用于避免 N+1 查询）
     */
    @Select("<script>" +
            "SELECT * FROM tb_help_request WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            " AND is_deleted = 0" +
            "</script>")
    List<HelpRequest> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 根据ID动态更新求助（只更新非空字段）
     */
    @Update("<script>" +
            "UPDATE tb_help_request SET update_time = NOW()" +
            "<if test='userId != null'>, user_id = #{userId}</if>" +
            "<if test='categoryId != null'>, category_id = #{categoryId}</if>" +
            "<if test='title != null'>, title = #{title}</if>" +
            "<if test='description != null'>, description = #{description}</if>" +
            "<if test='images != null'>, images = #{images}</if>" +
            "<if test='reward != null'>, reward = #{reward}</if>" +
            "<if test='addressId != null'>, address_id = #{addressId}</if>" +
            "<if test='addressDetail != null'>, address_detail = #{addressDetail}</if>" +
            "<if test='status != null'>, status = #{status}</if>" +
            "<if test='urgent != null'>, urgent = #{urgent}</if>" +
            "<if test='viewCount != null'>, view_count = #{viewCount}</if>" +
            "<if test='isDeleted != null'>, is_deleted = #{isDeleted}</if>" +
            " WHERE id = #{id}" +
            "</script>")
    int updateById(HelpRequest help);

    /**
     * 乐观锁更新：仅当 version 与读到的值一致时才更新，同时 version + 1。
     * 返回影响行数：1 = 成功；0 = 数据已被他人修改（调用方应重试）。
     * 注意：help 必须来自 selectById 查询结果（version 不能为 null），否则永不匹配。
     */
    @Update("<script>" +
            "UPDATE tb_help_request SET update_time = NOW()" +
            "<if test='userId != null'>, user_id = #{userId}</if>" +
            "<if test='categoryId != null'>, category_id = #{categoryId}</if>" +
            "<if test='title != null'>, title = #{title}</if>" +
            "<if test='description != null'>, description = #{description}</if>" +
            "<if test='images != null'>, images = #{images}</if>" +
            "<if test='reward != null'>, reward = #{reward}</if>" +
            "<if test='addressId != null'>, address_id = #{addressId}</if>" +
            "<if test='addressDetail != null'>, address_detail = #{addressDetail}</if>" +
            "<if test='status != null'>, status = #{status}</if>" +
            "<if test='urgent != null'>, urgent = #{urgent}</if>" +
            "<if test='viewCount != null'>, view_count = #{viewCount}</if>" +
            "<if test='isDeleted != null'>, is_deleted = #{isDeleted}</if>" +
            ", version = version + 1" +
            " WHERE id = #{id} AND version = #{version}" +
            "</script>")
    int updateByIdWithVersion(HelpRequest help);

    /**

     */
    @Update("UPDATE tb_help_request " +
            "SET accepted_num = accepted_num + 1, " +
            "    status = CASE WHEN accepted_num >= helper_num THEN 2 ELSE status END, " +
            "    version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = 1 AND accepted_num < helper_num AND is_deleted = 0")
    int acceptSlot(@Param("id") Long id);

    /**
     * 支付成功收尾：待支付(0) → 招募中(1)，求助由此上首页（首页/搜索只查 status=1）。
     */
    @Update("UPDATE tb_help_request SET status = 1, version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = 0 AND is_deleted = 0")
    int markPaid(@Param("id") Long id);

    /**
     * 取消/超时释放名额：accepted_num - 1，状态恢复招募中(1)（空出名额可继续招募）。
     * 返回影响行数：1 = 释放成功；0 = 已无名额可释放（异常态，忽略即可）
     */
    @Update("UPDATE tb_help_request " +
            "SET accepted_num = accepted_num - 1, " +
            "    status = 1, " +
            "    version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND accepted_num > 0 AND status IN (1, 2) AND is_deleted = 0")
    int releaseSlot(@Param("id") Long id);


    @Update("<script>" +
            "UPDATE tb_help_request SET status = #{newStatus}, update_time = NOW() " +
            "WHERE id = #{id} AND status IN " +
            "<foreach collection='statuses' item='s' open='(' separator=',' close=')'>#{s}</foreach>" +
            " AND is_deleted = 0" +
            "</script>")
    int updateStatusIfIn(@Param("id") Long id,
                         @Param("statuses") List<Integer> statuses,
                         @Param("newStatus") Integer newStatus);

    /**
     * 条件更新状态（CAS 乐观流转）：仅当求助当前状态 = expectStatus 时才改为 newStatus。
     * 支付超时作废用：0（待支付）→ 4（已取消）。
     * 返回影响行数：1 = 抢到本次流转；0 = 状态已被别的链路改掉，调用方应放弃处理。
     *
     * 注意 WHERE 里的 status = #{expectStatus} 不能省：少了它就会把已支付/已满员的求助
     * 强行改成「已取消」，调用方那句 `== 0` 判断也将永远不成立（行存在就返回 1）。
     */
    @Update("UPDATE tb_help_request SET status = #{newStatus}, version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = #{expectStatus} AND is_deleted = 0")
    int updateStatusIf(@Param("id") Long id,
                       @Param("expectStatus") Integer expectStatus,
                       @Param("newStatus") Integer newStatus);

    /**
     * 待支付超时查询：待支付(status=0)超过 30 分钟的求助。
     * 原先由 HelpReconcileJob 定时扫描做兜底，该任务已删除，本方法当前无人调用。
     */
    @Select("SELECT * FROM tb_help_request WHERE status = 0 AND is_deleted = 0 " +
            "AND create_time < DATE_SUB(NOW(), INTERVAL 30 MINUTE)")
    List<HelpRequest> selectPendingTimeoutHelps();

    /**
     * 分页查询用户的求助列表
     */
    @Select("SELECT id,user_id,category_id,title,description,images,reward,total_reward,address_id,address_detail,status,helper_num,accepted_num,urgent,view_count FROM tb_help_request WHERE id>#{offset} AND user_id = #{userId} AND is_deleted = 0 " +
            "ORDER BY create_time DESC LIMIT  #{size}")
    List<HelpRequest> selectByUserIdPaged(@Param("userId") Long userId,
                                          @Param("offset") int offset,
                                          @Param("size") int size);

    /**
     * 用户求助总数
     */
    @Select("SELECT COUNT(*) FROM tb_help_request WHERE user_id = #{userId} AND is_deleted = 0")
    Long selectCountByUserId(@Param("userId") Long userId);

    /**
     * 搜索求助（关键词 + 分类筛选）
     */
    @Select("<script>" +
            "SELECT * FROM tb_help_request WHERE status = 1 AND is_deleted = 0" +
            "<if test='categoryId != null'> AND category_id = #{categoryId}</if>" +
            "<if test='keyword != null and keyword != \"\"'> " +
            "AND (title in (select title from tb_help_request where title like CONCAT('%', #{keyword}, '%'))) </if>" +
            " ORDER BY urgent DESC, create_time DESC LIMIT #{offset}, #{size}" +
            "</script>")
    List<HelpRequest> search(@Param("keyword") String keyword,
                             @Param("categoryId") Long categoryId,
                             @Param("offset") int offset,
                             @Param("size") int size);

    /**
     * 搜索求助总数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM tb_help_request WHERE status = 1 AND is_deleted = 0" +
            "<if test='categoryId != null'> AND category_id = #{categoryId}</if>" +
            "<if test='keyword != null and keyword != \"\"'> " +
            "AND (title in (select title from tb_help_request where title like CONCAT('%', #{keyword}, '%')))</if>" +
            "</script>")
    Long searchCount(@Param("keyword") String keyword, @Param("categoryId") Long categoryId);

    /**
     * 查询求助总数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM tb_help_request WHERE status = 1 AND is_deleted = 0" +
            "<if test='categoryId != null'> AND category_id = #{categoryId}</if>" +
            "</script>")
    Long selectCount(@Param("categoryId") Long categoryId);

    @Select("SELECT COUNT(*) FROM tb_help_request WHERE status = #{status} AND is_deleted = 0")
    Long selectCountByStatus(@Param("status") Integer status);

    @Select("<script>" +
            "SELECT id,user_id,category_id,title,description,images,reward,total_reward,address_id,address_detail,status,helper_num,accepted_num,urgent,view_count FROM tb_help_request WHERE id >= (select a.id from tb_help_request a order by id asc limit #{offset},1) AND status = 1 AND is_deleted = 0" +
            "<if test='categoryId != null'> AND category_id = #{categoryId}</if>" +
            "   LIMIT  #{size}" +
            "</script>")
    List<HelpRequest> selectPage(@Param("offset") int offset, @Param("size") int size,
                                 @Param("categoryId") Long categoryId);

    /**
     * 管理员分页查询求助（不过滤状态）
     */
    @Select("<script>" +
            "SELECT id,user_id,category_id,title,description,images,reward,total_reward,address_id,address_detail,status,helper_num,accepted_num,urgent,view_count FROM tb_help_request WHERE id > #{offset}  AND is_deleted = 0" +
            "<if test='status != null'> AND status = #{status}</if>" +
            " ORDER BY create_time DESC id DESC LIMIT #{size}" +
            "</script>")
    List<HelpRequest> selectPageAll(@Param("offset") int offset, @Param("size") int size,
                                    @Param("status") Integer status);

    /**
     * 管理员查询求助总数（不过滤状态）
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM tb_help_request WHERE is_deleted = 0" +
            "<if test='status != null'> AND status = #{status}</if>" +
            "</script>")
    Long selectCountAll(@Param("status") Integer status);
}
