package com.linlibang.mapper;

import com.linlibang.entity.Review;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 评价 Mapper（独立评价模块）
 */
@Mapper
public interface ReviewMapper {

    /**
     * 插入评价（唯一键 uk_order_from 兜底：同一订单同一评价人只能评一次）
     */
    @Insert("INSERT INTO tb_review (order_id, from_user_id, to_user_id, score, comment, create_time) " +
            "VALUES (#{orderId}, #{fromUserId}, #{toUserId}, #{score}, #{comment}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Review review);

    /**
     * 查询某订单某评价人的评价（幂等检查）
     */
    @Select("SELECT * FROM tb_review WHERE order_id = #{orderId} AND from_user_id = #{fromUserId}")
    Review selectByOrderAndFrom(@Param("orderId") Long orderId, @Param("fromUserId") Long fromUserId);

    /**
     * 已完成(status=3)但评价不齐的订单（缺分/漏分对账用）
     * 正常订单应同时存在"发布者评接单者"与"接单者评发布者"两条评价
     */
    @Select("SELECT o.id FROM tb_order o WHERE o.status = 3 AND (" +
            "NOT EXISTS (SELECT 1 FROM tb_review r WHERE r.order_id = o.id AND r.from_user_id = o.publisher_id)" +
            " OR NOT EXISTS (SELECT 1 FROM tb_review r WHERE r.order_id = o.id AND r.from_user_id = o.helper_id))")
    List<Long> selectMissingReviewOrderIds();
}
