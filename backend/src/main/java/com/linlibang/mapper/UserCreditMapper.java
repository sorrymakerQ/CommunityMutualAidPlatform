package com.linlibang.mapper;

import com.linlibang.entity.UserCredit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 用户信用分 Mapper
 *
 * 一致性设计：
 *   1. 变更一律走 updateCreditDelta —— 单条 UPDATE 原子自增（credit = credit + delta），
 *      并发不丢更新；GREATEST(0, ...) 兜底不为负；
 *   2. 调用方负责在同一事务内写 tb_credit_log 流水（主表与流水同生共死）；
 *   3. 事务提交后由调用方删除用户缓存 user:info:{userId}（Cache-Aside）。
 */
@Mapper
public interface UserCreditMapper {

    /** 注册时初始化（credit=100），与 tb_user 插入同事务 */
    @Insert("INSERT INTO tb_user_credit (user_id, credit) VALUES (#{userId}, 100)")
    int insertDefault(@Param("userId") Long userId);

    /** 单查（主键，展示路径极少用，一般走批量） */
    @Select("SELECT user_id, credit, update_time FROM tb_user_credit WHERE user_id = #{userId}")
    UserCredit selectByUserId(@Param("userId") Long userId);

    /** 批量查（列表展示：一次 IN 拿到所有人的当前分） */
    @Select("<script>" +
            "SELECT user_id, credit, update_time FROM tb_user_credit WHERE user_id IN " +
            "<foreach collection='userIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<UserCredit> selectByUserIds(@Param("userIds") List<Long> userIds);

    /**
     * 原子增减：credit = credit + delta（单语句，行锁串行化并发变更，天然防丢失更新）
     *
     * @return 影响行数：0 = 该用户无信用分记录（未注册/数据缺失）
     */
    @Update("UPDATE tb_user_credit SET credit = GREATEST(0, credit + #{delta}) WHERE user_id = #{userId}")
    int updateCreditDelta(@Param("userId") Long userId, @Param("delta") int delta);
}
