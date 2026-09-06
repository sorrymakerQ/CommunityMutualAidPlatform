package com.linlibang.job;

import com.linlibang.entity.HelpRequest;
import com.linlibang.entity.Notification;
import com.linlibang.entity.Order;
import com.linlibang.entity.OrderStatusLog;
import com.linlibang.mapper.HelpRequestMapper;
import com.linlibang.mapper.NotificationMapper;
import com.linlibang.mapper.OrderMapper;
import com.linlibang.mapper.OrderStatusLogMapper;
import com.linlibang.utils.RedisUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;

/**
 * 订单超时扫描任务（每 5 分钟）
 *
 * 背景：订单超时取消时长已改为 1 天（24 小时），
 * 而 RocketMQ 内置延迟等级最大仅 2 小时，无法用延迟消息实现，
 * 故改用定时任务扫描"超过 1 天仍未完成"的订单批量取消。
 *
 * 业务规则：
 *   - 超时取消订单时释放名额（与用户取消一致）：求助 accepted_num - 1、恢复招募中，
 *     空出的名额可重新招募，避免多人求助部分完成后资金无出口、形成"僵尸满员单"；
 *   - 每单独立事务 + 状态机 CAS，重复扫描天然幂等。
 */
@Slf4j
@Component
public class OrderTimeoutScanJob {

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private OrderStatusLogMapper orderStatusLogMapper;

    @Resource
    private HelpRequestMapper helpRequestMapper;

    @Resource
    private NotificationMapper notificationMapper;

    @Resource
    private RedisUtils redisUtils;

    @Resource
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @PostConstruct
    private void init() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 每 5 分钟扫描一次超时订单（启动 60 秒后开始） */
    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    public void scanTimeoutOrders() {
        List<Order> timeoutOrders = orderMapper.selectTimeoutOrders();
        if (timeoutOrders.isEmpty()) {
            return;
        }
        log.info("扫描到 {} 笔超时未完成订单，开始自动取消", timeoutOrders.size());
        int cancelled = 0;
        for (Order order : timeoutOrders) {
            try {
                if (cancelTimeoutOrder(order)) {
                    cancelled++;
                }
            } catch (Exception e) {
                log.error("超时订单取消失败, orderId={}", order.getId(), e);
            }
        }
        if (cancelled > 0) {
            log.info("超时自动取消完成：{} 笔", cancelled);
        }
    }

    /**
     * 取消单个超时订单（独立事务）
     *
     * @return true = 本次实际取消；false = 幂等作废（已被处理）
     */
    private Boolean cancelTimeoutOrder(Order order) {
        return transactionTemplate.execute(status -> {
            // 1. 状态机 CAS：仅当仍处于 已接单(1)/进行中(2) 时取消
            int rows = orderMapper.updateStatusIfIn(order.getId(), Arrays.asList(1, 2), 4,
                    "超时未完成，系统自动取消");
            if (rows == 0) {
                return Boolean.FALSE;  // 已抢先完成/取消 -> 幂等作废
            }
            // 审计：系统超时取消（from → 4）
            try {
                OrderStatusLog audit = new OrderStatusLog();
                audit.setOrderId(order.getId());
                audit.setFromStatus(order.getStatus());
                audit.setToStatus(4);
                audit.setOperatorId(null);
                audit.setOperatorType("SYSTEM");
                audit.setReason("超时自动取消（超过24小时未完成）");
                orderStatusLogMapper.insert(audit);
            } catch (Exception e) {
                log.warn("超时取消审计记录失败: orderId={}", order.getId(), e);
            }

            // 2. 释放名额并恢复招募（与用户取消一致）：超时取消若不释放名额，
            //    多人求助部分完成后资金无出口、且会形成"僵尸满员单"。
            HelpRequest help = helpRequestMapper.selectById(order.getHelpId());
            if (help != null && helpRequestMapper.releaseSlot(help.getId()) > 0) {
                // 空出名额后求助回到招募中，需重新写入 GEO 并清理缓存
                redisUtils.delete("help:item:" + help.getId());
                if (help.getLng() != null && help.getLat() != null) {
                    redisUtils.geoAdd("help:location", help.getLng(), help.getLat(), help.getId().toString());
                }
            } else {
                redisUtils.delete("help:item:" + order.getHelpId());
            }

            // 3. 通知接单者（幂等：先查后插 + uk_related_type 唯一索引兜底）
            try {
                if (notificationMapper.selectByUserIdRelatedIdAndType(order.getHelperId(), order.getId(), 1) == null) {
                    Notification notification = new Notification();
                    notification.setUserId(order.getHelperId());
                    notification.setTitle("订单已自动取消");
                    notification.setContent("您的订单超过24小时未完成，系统已自动取消");
                    notification.setType(1);
                    notification.setRelatedId(order.getId());
                    notificationMapper.insert(notification);
                }
            } catch (DuplicateKeyException e) {
                log.debug("超时取消通知已存在，跳过: orderId={}", order.getId());
            }
            return Boolean.TRUE;
        });
    }
}
