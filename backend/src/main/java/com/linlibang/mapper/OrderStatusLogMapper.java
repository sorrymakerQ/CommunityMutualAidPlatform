package com.linlibang.mapper;

import com.linlibang.entity.OrderStatusLog;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 订单状态审计 Mapper
 */
@Mapper
public interface OrderStatusLogMapper {

    /**
     * 记录一次订单状态变更（与业务同事务）
     */
    @Insert("INSERT INTO tb_order_status_log " +
            "(order_id, from_status, to_status, operator_id, operator_type, reason, create_time) " +
            "VALUES (#{orderId}, #{fromStatus}, #{toStatus}, #{operatorId}, #{operatorType}, #{reason}, NOW())")
    int insert(OrderStatusLog log);

    /**
     * 查询某订单的完整状态履历（审计查询）
     */
    @Select("SELECT * FROM tb_order_status_log WHERE order_id = #{orderId} ORDER BY id")
    List<OrderStatusLog> selectByOrderId(@Param("orderId") Long orderId);

    /**
     * 查询某操作人的操作履历
     */
    @Select("SELECT * FROM tb_order_status_log WHERE operator_id = #{operatorId} ORDER BY id DESC LIMIT #{limit}")
    List<OrderStatusLog> selectByOperatorId(@Param("operatorId") Long operatorId, @Param("limit") int limit);
}
