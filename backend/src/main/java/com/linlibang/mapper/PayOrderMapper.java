package com.linlibang.mapper;

import com.linlibang.entity.PayOrder;
import org.apache.ibatis.annotations.*;

/**
 * 支付订单 Mapper（余额支付）
 */
@Mapper
public interface PayOrderMapper {

    /**
     * 插入支付订单（发布求助时生成，初始待支付、渠道为空）
     */
    @Insert("INSERT INTO tb_pay_order (pay_no, help_id, publisher_id, amount, status, create_time) " +
            "VALUES (#{payNo}, #{helpId}, #{publisherId}, #{amount}, 0, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PayOrder payOrder);

    /**
     * 按求助ID查支付订单
     */
    @Select("SELECT * FROM tb_pay_order WHERE help_id = #{helpId}")
    PayOrder selectByHelpId(@Param("helpId") Long helpId);

    /**
     * 按商户支付单号查支付订单（支付宝异步通知的入口）：
     * 回调参数 out_trade_no 就是发起支付时传出去的 pay_no
     */
    @Select("SELECT * FROM tb_pay_order WHERE pay_no = #{payNo}")
    PayOrder selectByPayNo(@Param("payNo") String payNo);

    /**
     * 支付成功标记
     */
    @Update("UPDATE tb_pay_order SET status = 1, channel = #{channel}, pay_time = NOW() " +
            "WHERE help_id = #{helpId} AND status = 0")
    int markPaid(@Param("helpId") Long helpId, @Param("channel") Integer channel);

    @Update("update tb_pay_order set status = #{status} where help_id = #{helpId}")
    void updateStatus(Long helpId, int status);


    /**
     * 条件更新状态（CAS 乐观流转）：仅当支付单当前状态 = expectStatus 时才改为 newStatus。
     * 支付超时作废用：0（待支付）→ 2（已取消）。
     * 返回影响行数：1 = 抢到本次流转；0 = 状态已被别的链路改掉（如回调刚置为已支付），调用方应放弃处理。
     *
     * 注意 WHERE 里的 status = #{expectStatus} 是这段逻辑的全部意义所在，不能省：
     * 少了它就会把「已支付(1)」的订单强行改成「已取消(2)」，等于把用户已付的钱作废掉。
     */
    @Update("UPDATE tb_pay_order SET status = #{newStatus} " +
            "WHERE help_id = #{helpId} AND status = #{expectStatus}")
    int updateStatusIf(@Param("helpId") Long helpId,
                       @Param("expectStatus") Integer expectStatus,
                       @Param("newStatus") Integer newStatus);
}
