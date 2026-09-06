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
            "(user_id, category_id, title, description, images, reward, total_reward, address, lng, lat, " +
            "status, helper_num, accepted_num, urgent, view_count, create_time, update_time, is_deleted) " +
            "VALUES (#{userId}, #{categoryId}, #{title}, #{description}, #{images}, #{reward}, #{totalReward}, " +
            "#{address}, #{lng}, #{lat}, #{status}, #{helperNum}, #{acceptedNum}, #{urgent}, #{viewCount}, NOW(), NOW(), 0)")
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
            "<if test='address != null'>, address = #{address}</if>" +
            "<if test='lng != null'>, lng = #{lng}</if>" +
            "<if test='lat != null'>, lat = #{lat}</if>" +
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
            "<if test='address != null'>, address = #{address}</if>" +
            "<if test='lng != null'>, lng = #{lng}</if>" +
            "<if test='lat != null'>, lat = #{lat}</if>" +
            "<if test='status != null'>, status = #{status}</if>" +
            "<if test='urgent != null'>, urgent = #{urgent}</if>" +
            "<if test='viewCount != null'>, view_count = #{viewCount}</if>" +
            "<if test='isDeleted != null'>, is_deleted = #{isDeleted}</if>" +
            ", version = version + 1" +
            " WHERE id = #{id} AND version = #{version}" +
            "</script>")
    int updateByIdWithVersion(HelpRequest help);

    /**
     * 状态机 CAS 更新（乐观锁兜底）：
     * 仅当状态仍为 expectedStatus 时更新，返回影响行数（0 = 已被并发变更）
     */
    @Update("UPDATE tb_help_request SET status = #{newStatus}, update_time = NOW() " +
            "WHERE id = #{id} AND status = #{expectedStatus} AND is_deleted = 0")
    int updateStatusIf(@Param("id") Long id,
                       @Param("expectedStatus") Integer expectedStatus,
                       @Param("newStatus") Integer newStatus);

    /**
     * 接单占名额（多人求助的核心原子操作）：
     * 仅当求助处于招募中(status=1)且有名额(accepted_num < helper_num)时，
     * accepted_num + 1；若加完即满员(accepted_num >= helper_num)，状态转2(已满员)。
     * 返回影响行数：1 = 占位成功；0 = 已满员/状态不对（调用方应报"已招满"）。
     *
     * ⚠️ MySQL SET 子句从左到右求值：status 的 CASE 里 accepted_num 已是 +1 后的新值，
     * 故判满条件用 accepted_num >= helper_num（新值语义），而非 accepted_num + 1 >= helper_num
     * （旧值语义）——写后者会提前一个名额误判满员（3 人求助第 2 人就关招募）。
     */
    @Update("UPDATE tb_help_request " +
            "SET accepted_num = accepted_num + 1, " +
            "    status = CASE WHEN accepted_num >= helper_num THEN 2 ELSE status END, " +
            "    version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = 1 AND accepted_num < helper_num AND is_deleted = 0")
    int acceptSlot(@Param("id") Long id);

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

    /**
     * 状态机 CAS 更新（多期望状态版）：仅当状态在 statuses 中时更新
     */
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
     * 待支付超时兜底查询：待支付(status=0)超过 30 分钟的求助
     * （RocketMQ 延迟消息丢失时的对账兜底，见 HelpReconcileJob）
     */
    @Select("SELECT * FROM tb_help_request WHERE status = 0 AND is_deleted = 0 " +
            "AND create_time < DATE_SUB(NOW(), INTERVAL 30 MINUTE)")
    List<HelpRequest> selectPendingTimeoutHelps();

    /**
     * 僵尸求助查询：已满员(status=2)超过 1 天且没有任何活跃/已完成订单的求助
     * （所有订单被超时取消后遗留的僵尸状态，见 HelpReconcileJob）
     */
    @Select("SELECT h.* FROM tb_help_request h WHERE h.status = 2 AND h.is_deleted = 0 " +
            "AND h.update_time < DATE_SUB(NOW(), INTERVAL 1 DAY) " +
            "AND NOT EXISTS (SELECT 1 FROM tb_order o WHERE o.help_id = h.id AND o.status IN (1, 2, 3))")
    List<HelpRequest> selectZombieHelps();

    /**
     * 根据ID列表和状态查询求助（附近搜索用）
     */
    @Select("<script>" +
            "SELECT * FROM tb_help_request WHERE is_deleted = 0 AND status = #{status} " +
            "AND id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "ORDER BY urgent DESC, create_time DESC" +
            "</script>")
    List<HelpRequest> selectByIdsAndStatus(@Param("ids") List<Long> ids, @Param("status") Integer status);

    /**
     * 分页查询用户的求助列表
     */
    @Select("SELECT * FROM tb_help_request WHERE user_id = #{userId} AND is_deleted = 0 " +
            "ORDER BY create_time DESC LIMIT #{offset}, #{size}")
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
            "AND (title LIKE CONCAT('%',#{keyword},'%') OR description LIKE CONCAT('%',#{keyword},'%'))</if>" +
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
            "AND (title LIKE CONCAT('%',#{keyword},'%') OR description LIKE CONCAT('%',#{keyword},'%'))</if>" +
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
            "SELECT * FROM tb_help_request WHERE status = 1 AND is_deleted = 0" +
            "<if test='categoryId != null'> AND category_id = #{categoryId}</if>" +
            " ORDER BY create_time DESC LIMIT #{offset}, #{size}" +
            "</script>")
    List<HelpRequest> selectPage(@Param("offset") int offset, @Param("size") int size,
                                 @Param("categoryId") Long categoryId);

    /**
     * 管理员分页查询求助（不过滤状态）
     */
    @Select("<script>" +
            "SELECT * FROM tb_help_request WHERE is_deleted = 0" +
            "<if test='status != null'> AND status = #{status}</if>" +
            " ORDER BY create_time DESC LIMIT #{offset}, #{size}" +
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
