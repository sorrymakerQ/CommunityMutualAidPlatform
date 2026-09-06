package com.linlibang.mapper;

import com.linlibang.entity.PayOrder;
import org.apache.ibatis.annotations.*;

/**
 * 支付订单 Mapper（余额支付）
 */
@Mapper
public interface PayOrderMapper {

    /**
     * 插入支付订单（发布求助时生成，初始待支付）
     */
    @Insert("INSERT INTO tb_pay_order (help_id, publisher_id, amount, status, create_time) " +
            "VALUES (#{helpId}, #{publisherId}, #{amount}, 0, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PayOrder payOrder);

    /**
     * 按求助ID查支付订单
     */
    @Select("SELECT * FROM tb_pay_order WHERE help_id = #{helpId}")
    PayOrder selectByHelpId(@Param("helpId") Long helpId);

    /**
     * 状态机 CAS：仅当处于 expectedStatus 时更新为 newStatus（0→1 支付 / 0→2 取消）
     * 返回影响行数（0 = 状态已变更，幂等作废）
     */
    @Update("UPDATE tb_pay_order SET status = #{newStatus}, pay_time = NOW() " +
            "WHERE help_id = #{helpId} AND status = #{expectedStatus}")
    int updateStatusIf(@Param("helpId") Long helpId,
                       @Param("expectedStatus") Integer expectedStatus,
                       @Param("newStatus") Integer newStatus);
}
