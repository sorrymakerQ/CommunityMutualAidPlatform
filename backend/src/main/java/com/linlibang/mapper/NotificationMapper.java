package com.linlibang.mapper;

import com.linlibang.entity.Notification;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 通知 Mapper 接口（MyBatis 注解方式）
 */
@Mapper
public interface NotificationMapper {

    /**
     * 插入通知，自动回填ID
     */
    @Insert("INSERT INTO tb_notification " +
            "(user_id, title, content, type, is_read, related_id, create_time) " +
            "VALUES (#{userId}, #{title}, #{content}, #{type}, #{isRead}, #{relatedId}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Notification notification);

    /**
     * 根据ID查询通知
     */
    @Select("SELECT * FROM tb_notification WHERE id = #{id}")
    Notification selectById(@Param("id") Long id);

    /**
     * 分页查询用户的通知列表
     */
    @Select("SELECT * FROM tb_notification WHERE user_id = #{userId} " +
            "ORDER BY create_time DESC LIMIT #{offset}, #{size}")
    List<Notification> selectByUserIdPaged(@Param("userId") Long userId,
                                           @Param("offset") int offset,
                                           @Param("size") int size);

    /**
     * 用户通知总数
     */
    @Select("SELECT COUNT(*) FROM tb_notification WHERE user_id = #{userId}")
    Long selectCountByUserId(@Param("userId") Long userId);

    /**
     * 未读通知数量
     */
    @Select("SELECT COUNT(*) FROM tb_notification WHERE user_id = #{userId} AND is_read = 0")
    Long selectUnreadCount(@Param("userId") Long userId);

    /**
     * 查询用户所有未读通知
     */
    @Select("SELECT * FROM tb_notification WHERE user_id = #{userId} AND is_read = 0")
    List<Notification> selectUnreadByUserId(@Param("userId") Long userId);

    /**
     * 更新通知的已读状态
     */
    @Update("UPDATE tb_notification SET is_read = #{isRead} WHERE id = #{id}")
    int updateIsRead(@Param("id") Long id, @Param("isRead") Integer isRead);

    /**
     * 根据接收者 + 关联ID + 类型查通知（用于幂等去重）。
     * 唯一键 uk_user_related_type(user_id, related_id, type)：同一订单/求助可分别通知双方，
     * 避免此前 (related_id, type) 唯一键导致"一人收到后另一方被误判重复而漏发"。
     */
    @Select("SELECT * FROM tb_notification WHERE user_id = #{userId} AND related_id = #{relatedId} AND type = #{type} LIMIT 1")
    Notification selectByUserIdRelatedIdAndType(@Param("userId") Long userId,
                                                @Param("relatedId") Long relatedId,
                                                @Param("type") Integer type);
}
