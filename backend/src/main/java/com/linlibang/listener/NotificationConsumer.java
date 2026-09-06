package com.linlibang.listener;

import cn.hutool.json.JSONUtil;
import com.linlibang.config.RocketMQConfig;
import com.linlibang.entity.Notification;
import com.linlibang.mapper.NotificationMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通知消费者（RocketMQ）
 *
 * 消费通知消息并落库。
 * 幂等：tb_notification 的 uk_related_type(related_id, type) 唯一索引兜底，
 * 重复投递/并发插入时 DuplicateKeyException 视为消费成功，直接确认。
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = RocketMQConfig.NOTIFICATION_TOPIC,
        consumerGroup = RocketMQConfig.NOTIFICATION_CONSUMER_GROUP)
public class NotificationConsumer implements RocketMQListener<String> {

    @Resource
    private NotificationMapper notificationMapper;

    @Override
    public void onMessage(String message) {
        Map<String, Object> data;
        try {
            data = JSONUtil.parseObj(message);
        } catch (Exception e) {
            // 消息体非法：无法重试成功，直接确认丢弃，避免死循环
            log.error("通知消息体非法，丢弃: {}", message, e);
            return;
        }

        try {
            Notification notification = new Notification();
            notification.setUserId(Long.valueOf(data.get("userId").toString()));
            notification.setTitle((String) data.get("title"));
            notification.setContent((String) data.get("content"));
            notification.setType(((Number) data.get("type")).intValue());
            notification.setRelatedId(data.get("relatedId") != null
                    ? Long.valueOf(data.get("relatedId").toString())
                    : null);
            notification.setCreateTime(LocalDateTime.now());

            notificationMapper.insert(notification);
        } catch (DuplicateKeyException e) {
            // 重复投递被唯一索引拦截，视为消费成功
            log.debug("重复通知已跳过: relatedId={}, type={}",
                    data.get("relatedId"), data.get("type"));
        }
    }
}
