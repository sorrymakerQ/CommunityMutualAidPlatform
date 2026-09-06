package com.linlibang.job;

import com.linlibang.entity.Notification;
import com.linlibang.entity.Order;
import com.linlibang.mapper.NotificationMapper;
import com.linlibang.mapper.OrderMapper;
import com.linlibang.mapper.ReviewMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * 评价对账任务（每 10 分钟）——"缺分/漏分"检测
 *
 * 业务规则：订单完成后双方都应评价（发布者评接单者 + 接单者评发布者，共 2 条 tb_review）。
 * 本任务扫描"已完成(status=3)但评价不齐"的订单：
 *   - 找出缺评的一方，补发评价提醒通知；
 *   - 日志输出缺评订单清单（漏分可查）。
 * 提醒幂等：uk_related_type(order_id, type=3) 唯一索引——完成提醒/补评提醒不重复。
 */
@Slf4j
@Component
public class ReviewReconcileJob {

    @Resource
    private ReviewMapper reviewMapper;

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private NotificationMapper notificationMapper;

    /** 每 10 分钟扫描一次（启动 2 分钟后开始） */
    @Scheduled(fixedDelay = 600000, initialDelay = 120000)
    public void reconcileMissingReviews() {
        List<Long> missingOrderIds = reviewMapper.selectMissingReviewOrderIds();
        if (missingOrderIds.isEmpty()) {
            return;
        }
        int remindCount = 0;
        for (Long orderId : missingOrderIds) {
            try {
                Order order = orderMapper.selectById(orderId);
                if (order == null) {
                    continue;
                }
                // 分别检查双方是否已评
                boolean pubReviewed = reviewMapper.selectByOrderAndFrom(orderId, order.getPublisherId()) != null;
                boolean helperReviewed = reviewMapper.selectByOrderAndFrom(orderId, order.getHelperId()) != null;

                if (!pubReviewed) {
                    remindCount += sendRemind(order, order.getPublisherId(), "您还未评价接单者「订单" + orderId + "」的服务");
                }
                if (!helperReviewed) {
                    remindCount += sendRemind(order, order.getHelperId(), "您还未评价发布者「订单" + orderId + "」");
                }
            } catch (Exception e) {
                log.error("评价对账失败, orderId={}", orderId, e);
            }
        }
        log.info("评价对账完成：检测到 {} 笔订单评价不齐，补发提醒 {} 条（漏分清单 orderIds={}）",
                missingOrderIds.size(), remindCount, missingOrderIds);
    }

    /**
     * 给缺评方补发提醒（幂等：uk_related_type(order_id, type=3) 已存在则不重复发）
     *
     * @return 1 = 本次新发提醒；0 = 已提醒过/重复
     */
    private int sendRemind(Order order, Long userId, String content) {
        try {
            if (notificationMapper.selectByUserIdRelatedIdAndType(userId, order.getId(), 3) == null) {
                Notification notification = new Notification();
                notification.setUserId(userId);
                notification.setTitle("请完成评价");
                notification.setContent(content);
                notification.setType(3);  // 评价提醒
                notification.setRelatedId(order.getId());
                notificationMapper.insert(notification);
                return 1;
            }
        } catch (DuplicateKeyException e) {
            log.debug("评价提醒已存在，跳过: orderId={}, userId={}", order.getId(), userId);
        }
        return 0;
    }
}
