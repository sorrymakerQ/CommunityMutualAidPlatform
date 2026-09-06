package com.linlibang.listener;

import com.linlibang.config.RocketMQConfig;
import com.linlibang.entity.HelpRequest;
import com.linlibang.entity.Notification;
import com.linlibang.entity.PayOrder;
import com.linlibang.mapper.HelpRequestMapper;
import com.linlibang.mapper.NotificationMapper;
import com.linlibang.mapper.PayOrderMapper;
import com.linlibang.utils.RedisUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;

/**
 * 支付超时消费者（RocketMQ 延迟消息，等级 16 = 30 分钟）
 *
 * 发布求助时投递，30 分钟后检查：求助与支付订单仍处于"待支付"则自动取消。
 * 幂等：双状态机 CAS（0→4 / 0→2），已支付/已取消/重复投递全部作废。
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = RocketMQConfig.PAY_TIMEOUT_TOPIC,
        consumerGroup = RocketMQConfig.PAY_TIMEOUT_CONSUMER_GROUP)
public class PayTimeoutConsumer implements RocketMQListener<String> {

    @Resource
    private HelpRequestMapper helpRequestMapper;

    @Resource
    private PayOrderMapper payOrderMapper;

    @Resource
    private NotificationMapper notificationMapper;

    @Resource
    private RedisUtils redisUtils;

    @Override
    @Transactional
    public void onMessage(String message) {
        Long helpId;
        try {
            helpId = Long.valueOf(message);
        } catch (NumberFormatException e) {
            log.error("支付超时消息体非法，丢弃: {}", message, e);
            return;
        }

        // 1. 双状态机 CAS：仅当都处于"待支付"才取消（已支付/已取消/不存在 -> 幂等作废）
        PayOrder payOrder = payOrderMapper.selectByHelpId(helpId);
        if (payOrder == null || payOrder.getStatus() != 0) {
            return;
        }
        HelpRequest help = helpRequestMapper.selectById(helpId);
        if (help == null || help.getStatus() != 0) {
            return;
        }
        // 支付订单 0→2（已取消）
        if (payOrderMapper.updateStatusIf(helpId, 0, 2) == 0) {
            return;  // 并发下已被支付，作废
        }
        // 求助 0→4（已取消）
        helpRequestMapper.updateStatusIf(helpId, 0, 4);

        // 2. 事务提交后清详情缓存
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisUtils.delete("help:item:" + helpId);
            }
        });

        // 3. 通知发布者（幂等：先查后插 + uk_related_type 唯一索引兜底）
        try {
            if (notificationMapper.selectByUserIdRelatedIdAndType(help.getUserId(), helpId, 1) == null) {
                Notification notification = new Notification();
                notification.setUserId(help.getUserId());
                notification.setTitle("求助支付超时已取消");
                notification.setContent("您发布的求助「" + help.getTitle() + "」超过30分钟未支付，已自动取消");
                notification.setType(1);
                notification.setRelatedId(helpId);
                notificationMapper.insert(notification);
            }
        } catch (DuplicateKeyException e) {
            log.debug("支付超时通知已存在，跳过: helpId={}", helpId);
        }
    }
}
