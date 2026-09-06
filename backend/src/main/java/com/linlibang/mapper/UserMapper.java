package com.linlibang.mapper;

import com.linlibang.entity.User;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 用户 Mapper 接口（MyBatis 注解方式）
 */
@Mapper
public interface UserMapper {

    /**
     * 根据手机号查询用户
     */
    @Select("SELECT * FROM tb_user WHERE phone = #{phone} AND is_deleted = 0")
    User selectByPhone(@Param("phone") String phone);

    /**
     * 根据ID查询用户
     */
    @Select("SELECT * FROM tb_user WHERE id = #{id} AND is_deleted = 0")
    User selectById(@Param("id") Long id);

    /**
     * 批量查询用户（用于避免 N+1 查询）
     */
    @Select("<script>" +
            "SELECT * FROM tb_user WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            " AND is_deleted = 0" +
            "</script>")
    List<User> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 插入用户，自动回填ID
     */
    @Insert("INSERT INTO tb_user " +
            "(phone, password, nickname, avatar, gender, community, lng, lat, " +
            "help_count, balance, intro, role_id, status, is_builtin, create_time, update_time, is_deleted) " +
            "VALUES (#{phone}, #{password}, #{nickname}, #{avatar}, #{gender}, #{community}, #{lng}, #{lat}, " +
            "#{helpCount}, 0.00, #{intro}, #{roleId}, #{status}, 0, NOW(), NOW(), 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    /**
     * 根据ID动态更新用户（只更新非空字段）
     */
    @Update("<script>" +
            "UPDATE tb_user SET update_time = NOW()" +
            "<if test='password != null'>, password = #{password}</if>" +
            "<if test='nickname != null'>, nickname = #{nickname}</if>" +
            "<if test='avatar != null'>, avatar = #{avatar}</if>" +
            "<if test='gender != null'>, gender = #{gender}</if>" +
            "<if test='community != null'>, community = #{community}</if>" +
            "<if test='lng != null'>, lng = #{lng}</if>" +
            "<if test='lat != null'>, lat = #{lat}</if>" +
            "<if test='helpCount != null'>, help_count = #{helpCount}</if>" +
            "<if test='intro != null'>, intro = #{intro}</if>" +
            "<if test='roleId != null'>, role_id = #{roleId}</if>" +
            "<if test='status != null'>, status = #{status}</if>" +
            " WHERE id = #{id} AND is_deleted = 0" +
            "</script>")
    int updateById(User user);

    /**
     * 乐观锁更新：仅当 version 与读到的值一致时才更新，同时 version + 1。
     * 返回影响行数：1 = 成功；0 = 期间数据已被他人修改（调用方应重试）。
     * 注意：user 必须来自 selectById 查询结果（version 不能为 null），否则永不匹配。
     */
    @Update("<script>" +
            "UPDATE tb_user SET update_time = NOW()" +
            "<if test='password != null'>, password = #{password}</if>" +
            "<if test='nickname != null'>, nickname = #{nickname}</if>" +
            "<if test='avatar != null'>, avatar = #{avatar}</if>" +
            "<if test='gender != null'>, gender = #{gender}</if>" +
            "<if test='community != null'>, community = #{community}</if>" +
            "<if test='lng != null'>, lng = #{lng}</if>" +
            "<if test='lat != null'>, lat = #{lat}</if>" +
            "<if test='helpCount != null'>, help_count = #{helpCount}</if>" +
            "<if test='intro != null'>, intro = #{intro}</if>" +
            "<if test='roleId != null'>, role_id = #{roleId}</if>" +
            "<if test='status != null'>, status = #{status}</if>" +
            ", version = version + 1" +
            " WHERE id = #{id} AND is_deleted = 0 AND version = #{version}" +
            "</script>")
    int updateByIdWithVersion(User user);

    /**
     * 统计用户总数
     */
    @Select("SELECT COUNT(*) FROM tb_user WHERE is_deleted = 0")
    Long selectCount();

    /**
     * 分页查询用户列表
     */
    @Select("SELECT * FROM tb_user WHERE is_deleted = 0 ORDER BY create_time DESC LIMIT #{offset}, #{size}")
    List<User> selectPage(@Param("offset") int offset, @Param("size") int size);

    /**
     * 余额扣减（CAS 防超扣）：
     * 仅当余额充足时扣减，返回影响行数（0 = 余额不足，调用方应返回"余额不足"）
     */
    @Update("UPDATE tb_user SET balance = balance - #{amount}, update_time = NOW() " +
            "WHERE id = #{userId} AND is_deleted = 0 AND balance >= #{amount}")
    int deductBalance(@Param("userId") Long userId, @Param("amount") java.math.BigDecimal amount);

    /**
     * 余额加回（退款原路退回用，BigDecimal 全链路）
     */
    @Update("UPDATE tb_user SET balance = balance + #{amount}, update_time = NOW() " +
            "WHERE id = #{userId} AND is_deleted = 0")
    int addBalance(@Param("userId") Long userId, @Param("amount") java.math.BigDecimal amount);
}
