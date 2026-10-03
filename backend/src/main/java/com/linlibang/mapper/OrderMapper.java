package com.linlibang.mapper;

import com.linlibang.entity.Order;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 订单 Mapper 接口（MyBatis 注解方式）
 */
@Mapper
public interface OrderMapper {

    /**
     * 插入订单，自动回填ID
     */
    @Insert("INSERT INTO tb_order " +
            "(help_id, publisher_id, helper_id, total_amount, status, seq, cancel_reason, " +
            "accept_time, finish_time, publisher_score, helper_score, publisher_comment, helper_comment, " +
            "create_time, update_time) " +
            "VALUES (#{helpId}, #{publisherId}, #{helperId}, #{totalAmount}, #{status}, #{seq}, #{cancelReason}, " +
            "NOW(), #{finishTime}, #{publisherScore}, #{helperScore}, #{publisherComment}, #{helperComment}, " +
            "NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Order order);

    /**
     * 根据ID查询订单
     */
    @Select("SELECT * FROM tb_order WHERE id = #{id}")
    Order selectById(@Param("id") Long id);

    /**
     * 根据ID动态更新订单（只更新非空字段）
     */
    @Update("<script>" +
            "UPDATE tb_order SET update_time = NOW()" +
            "<if test='helpId != null'>, help_id = #{helpId}</if>" +
            "<if test='publisherId != null'>, publisher_id = #{publisherId}</if>" +
            "<if test='helperId != null'>, helper_id = #{helperId}</if>" +
            "<if test='status != null'>, status = #{status}</if>" +
            "<if test='cancelReason != null'>, cancel_reason = #{cancelReason}</if>" +
            "<if test='acceptTime != null'>, accept_time = #{acceptTime}</if>" +
            "<if test='finishTime != null'>, finish_time = #{finishTime}</if>" +
            "<if test='publisherScore != null'>, publisher_score = #{publisherScore}</if>" +
            "<if test='helperScore != null'>, helper_score = #{helperScore}</if>" +
            "<if test='publisherComment != null'>, publisher_comment = #{publisherComment}</if>" +
            "<if test='helperComment != null'>, helper_comment = #{helperComment}</if>" +
            " WHERE id = #{id}" +
            "</script>")
    int updateById(Order order);

    /**
     * 乐观锁更新：仅当 version 与读到的值一致时才更新，同时 version + 1。
     * 返回影响行数：1 = 成功；0 = 数据已被他人修改（调用方应重试）。
     * 注意：order 必须来自 selectById 查询结果（version 不能为 null），否则永不匹配。
     */
    @Update("<script>" +
            "UPDATE tb_order SET update_time = NOW()" +
            "<if test='helpId != null'>, help_id = #{helpId}</if>" +
            "<if test='publisherId != null'>, publisher_id = #{publisherId}</if>" +
            "<if test='helperId != null'>, helper_id = #{helperId}</if>" +
            "<if test='status != null'>, status = #{status}</if>" +
            "<if test='seq != null'>, seq = #{seq}</if>" +
            "<if test='cancelReason != null'>, cancel_reason = #{cancelReason}</if>" +
            "<if test='acceptTime != null'>, accept_time = #{acceptTime}</if>" +
            "<if test='finishTime != null'>, finish_time = #{finishTime}</if>" +
            "<if test='publisherScore != null'>, publisher_score = #{publisherScore}</if>" +
            "<if test='helperScore != null'>, helper_score = #{helperScore}</if>" +
            "<if test='publisherComment != null'>, publisher_comment = #{publisherComment}</if>" +
            "<if test='helperComment != null'>, helper_comment = #{helperComment}</if>" +
            ", version = version + 1" +
            " WHERE id = #{id} AND version = #{version}" +
            "</script>")
    int updateByIdWithVersion(Order order);

    /**
     * 状态机 CAS 取消订单（乐观锁）：
     * 仅当订单仍处于 statuses 中的任一状态时生效，
     * 返回影响行数（0 = 已被用户抢先完成/取消，超时消息幂等作废）
     */
    @Update("<script>" +
            "UPDATE tb_order SET status = #{newStatus}, cancel_reason = #{cancelReason}, " +
            "seq = CASE WHEN #{newStatus} = 4 THEN 0 ELSE seq END, update_time = NOW() " +
            "WHERE id = #{id} AND status IN " +
            "<foreach collection='statuses' item='s' open='(' separator=',' close=')'>#{s}</foreach>" +
            "</script>")
    int updateStatusIfIn(@Param("id") Long id,
                         @Param("statuses") List<Integer> statuses,
                         @Param("newStatus") Integer newStatus,
                         @Param("cancelReason") String cancelReason);

    /**
     * 按状态统计订单数
     */
    @Select("SELECT COUNT(*) FROM tb_order WHERE status = #{status}")
    Long selectCountByStatus(@Param("status") Integer status);

    /**
     * 按用户ID和角色分页查询订单
     */
    @Select("<script>" +
            "SELECT * FROM tb_order WHERE " +
            "<choose>" +
            "<when test='role == \"publisher\"'>publisher_id = #{userId}</when>" +
            "<when test='role == \"helper\"'>helper_id = #{userId}</when>" +
            "<otherwise>(publisher_id = #{userId} OR helper_id = #{userId})</otherwise>" +
            "</choose>" +
            " ORDER BY create_time DESC LIMIT #{offset}, #{size}" +
            "</script>")
    List<Order> selectByUserIdAndRole(@Param("userId") Long userId,
                                      @Param("role") String role,
                                      @Param("offset") int offset,
                                      @Param("size") int size);

    /**
     * 按用户ID和角色统计订单总数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM tb_order WHERE " +
            "<choose>" +
            "<when test='role == \"publisher\"'>publisher_id = #{userId}</when>" +
            "<when test='role == \"helper\"'>helper_id = #{userId}</when>" +
            "<otherwise>(publisher_id = #{userId} OR helper_id = #{userId})</otherwise>" +
            "</choose>" +
            "</script>")
    Long selectCountByUserIdAndRole(@Param("userId") Long userId, @Param("role") String role);

    /**
     * 查询某求助的当前接单人的活跃订单（status=1已接单 / 2进行中）
     */
    @Select("SELECT * FROM tb_order WHERE help_id = #{helpId} AND status IN (1, 2) ORDER BY create_time DESC LIMIT 1")
    Order selectActiveByHelpId(@Param("helpId") Long helpId);

    /**
     * 查询某求助下"指定接单者"的活跃订单（status 1/2）。
     * 接单即录用后用于幂等前置校验：同一人对同一求助只允许一条活跃订单
     * （并发场景由唯一键 uk_help_helper_seq 兜底）。
     */
    @Select("SELECT * FROM tb_order WHERE help_id = #{helpId} AND helper_id = #{helperId} " +
            "AND status IN (1, 2) ORDER BY create_time DESC LIMIT 1")
    Order selectActiveByHelpIdAndHelper(@Param("helpId") Long helpId, @Param("helperId") Long helperId);

    /**
     * 统计某求助下"除指定订单外"的未完成订单数（status 1/2）。
     * 用于多人求助：最后一个未完成订单结束时，求助整体才置为已完成。
     */
    @Select("SELECT COUNT(*) FROM tb_order " +
            "WHERE help_id = #{helpId} AND status IN (1, 2) AND id != #{excludeOrderId}")
    Long countOtherActiveByHelpId(@Param("helpId") Long helpId, @Param("excludeOrderId") Long excludeOrderId);

    /**
     * 统计某求助下所有未完成订单数（status 1/2）。。
     */
    @Select("SELECT COUNT(*) FROM tb_order WHERE help_id = #{helpId} AND status IN (1, 2)")
    Long countActiveByHelpId(@Param("helpId") Long helpId);

    /**
     * 统计某求助下已完成(3)/已评价(5)的订单数。
     * 用于退款时扣减已结算给接单者的酬劳，避免"部分完成 + 全额退款"导致重复退款。
     */
    @Select("SELECT COUNT(*) FROM tb_order WHERE help_id = #{helpId} AND status IN (3, 5)")
    Long countCompletedByHelpId(@Param("helpId") Long helpId);

    /**
     * 查询超时未完成订单（status 1/2 且 accept_time 超过 1 天），供定时扫描取消
     */
    @Select("SELECT * FROM tb_order " +
            "WHERE status IN (1, 2) AND accept_time < DATE_SUB(NOW(), INTERVAL 1 DAY)")
    List<Order> selectTimeoutOrders();
}
