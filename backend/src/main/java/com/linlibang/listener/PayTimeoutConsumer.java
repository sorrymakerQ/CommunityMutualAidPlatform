package com.linlibang.listener;

import com.linlibang.cache.MultiLevelCache;
import com.linlibang.config.RocketMQConfig;
import com.linlibang.entity.HelpRequest;
import com.linlibang.entity.Notification;
import com.linlibang.entity.PayOrder;
import com.linlibang.mapper.HelpRequestMapper;
import com.linlibang.mapper.NotificationMapper;
import com.linlibang.mapper.PayOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Value;
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
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = RocketMQConfig.PAY_TIMEOUT_TOPIC,
        consumerGroup = RocketMQConfig.PAY_TIMEOUT_CONSUMER_GROUP)
public class PayTimeoutConsumer implements RocketMQListener<PayOrder> {

    @Resource
    private HelpRequestMapper helpRequestMapper;

    @Resource
    private PayOrderMapper payOrderMapper;

    @Resource
    private NotificationMapper notificationMapper;

    @Resource
    private MultiLevelCache multiLevelCache;

    /**
     * 与生产端同一个配置项（rocketmq.pay-timeout.delay-level），只用它决定通知里的时长措辞。
     * 默认 16 = 30 分钟；联调调成 3（10 秒）时，通知文案会自动变成「超过10秒未支付」。
     */
    @Value("${rocketmq.pay-timeout.delay-level:" + RocketMQConfig.DELAY_LEVEL_30M + "}")
    private int payTimeoutDelayLevel;

    @Override
    @Transactional
    public void onMessage(PayOrder msg) {
        // 1. 先判空再取值（原代码先 getHelpId() 再判空，为 null 时会 NPE）
        if (msg == null || msg.getHelpId() == null) {
            return;
        }
        Long helpId = msg.getHelpId();

        // 2. 消息里带的是投递那一刻（30 分钟前）的状态快照，必须重新查库拿最新状态。
        PayOrder payOrder = payOrderMapper.selectByHelpId(helpId);
        if (payOrder == null || payOrder.getStatus() != 0) {
            // 已支付 / 已取消 → 什么都不做
            return;
        }
        HelpRequest helpRequest = helpRequestMapper.selectById(helpId);
        if (helpRequest == null || helpRequest.getStatus() != 0) {
            return;
        }
        // 3. 先抢占支付单：0（待支付）→ 2（已取消）。
        if (payOrderMapper.updateStatusIf(helpId, 0, 2) == 0) {
            log.info("支付超时作废跳过：支付单已非待支付状态, helpId={}", helpId);
            return;
        }
        // 4. 求助 0（待支付）→ 4（已取消）
        if (helpRequestMapper.updateStatusIf(helpId, 0, 4) == 0) {
            throw new IllegalStateException("支付超时作废失败：求助状态已变更, helpId=" + helpId);
        }

        // 缓存失效放在事务提交后：若在事务内先删缓存，别的线程会立刻回源读到尚未提交的
        // 「待支付」旧状态并重新回填，缓存反而被写脏。L1（Caffeine）+ L2（Redis）一起失效。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                multiLevelCache.evict("help:item:" + helpId);
            }
        });
        log.info("支付超时作废成功：helpId={}", helpId);

        try {
            if (notificationMapper.selectByUserIdRelatedIdAndType(helpRequest.getUserId(), helpId, 1) == null) {
                Notification notification = new Notification();
                notification.setUserId(helpRequest.getUserId());
                notification.setTitle("求助支付超时已取消");
                notification.setContent("您发布的求助「" + helpRequest.getTitle() + "」超过"
                        + RocketMQConfig.delayLevelDesc(payTimeoutDelayLevel) + "未支付，已自动取消");
                notification.setType(1);
                notification.setRelatedId(helpId);
                notificationMapper.insert(notification);
            }
        } catch (DuplicateKeyException e) {
            log.debug("支付超时通知已存在，跳过: helpId={}", helpId);
        }
    }
}
