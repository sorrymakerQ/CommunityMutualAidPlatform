package com.linlibang.mapper;

import com.linlibang.entity.CreditLog;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 信用分流水 Mapper
 */
@Mapper
public interface CreditLogMapper {

    /**
     * 记录信用分变动流水
     */
    @Insert("INSERT INTO tb_credit_log (user_id, delta, reason, order_id, create_time) " +
            "VALUES (#{userId}, #{delta}, #{reason}, #{orderId}, NOW())")
    int insert(CreditLog creditLog);

    /**
     * 查询某用户信用分流水（正负明细）
     */
    @Select("SELECT * FROM tb_credit_log WHERE user_id = #{userId} ORDER BY id DESC LIMIT #{limit}")
    List<CreditLog> selectByUserId(@Param("userId") Long userId, @Param("limit") int limit);
}
