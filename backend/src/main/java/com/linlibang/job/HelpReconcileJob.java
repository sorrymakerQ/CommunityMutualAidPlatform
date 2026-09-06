package com.linlibang.job;

import com.linlibang.dto.Result;
import com.linlibang.entity.HelpRequest;
import com.linlibang.entity.Notification;
import com.linlibang.mapper.HelpRequestMapper;
import com.linlibang.mapper.NotificationMapper;
import com.linlibang.mapper.PayOrderMapper;
import com.linlibang.service.PayService;
import com.linlibang.utils.RedisUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;

/**
 * 业务对账扫描兜底任务（每 10 分钟）——关键链路兜底
 *
 * 1. 待支付超时兜底：RocketMQ 30 分钟延迟消息丢失时，
 *    待支付求助将永不过期 -> 本任务兜底取消（CAS 幂等，与消息消费互不冲突）；
 * 2. 僵尸求助清理：已满员求助的所有订单被超时取消（名额不释放设计）
 *    后无任何活跃/已完成订单，求助永久停在满员态 -> 本任务自动结束并退款。
 */
@Slf4j
@Component
public class HelpReconcileJob {

    @Resource
    private HelpRequestMapper helpRequestMapper;

    @Resource
    private PayOrderMapper payOrderMapper;

    @Resource
    private NotificationMapper notificationMapper;

    @Resource
    private RedisUtils redisUtils;

    @Resource
    private PayService payService;

    @Resource
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @PostConstruct
    private void init() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 每 10 分钟扫描一次（启动 3 分钟后开始） */
    @Scheduled(fixedDelay = 600000, initialDelay = 180000)
    public void reconcile() {
        cancelPendingTimeoutHelps();
        closeZombieHelps();
    }

    // ==================== 1. 待支付超时兜底 ====================

    private void cancelPendingTimeoutHelps() {
        List<HelpRequest> pendingList = helpRequestMapper.selectPendingTimeoutHelps();
        if (pendingList.isEmpty()) {
            return;
        }
        int cancelled = 0;
        for (HelpRequest help : pendingList) {
            try {
                if (cancelPendingTimeoutHelp(help)) {
                    cancelled++;
                }
            } catch (Exception e) {
                log.error("待支付超时兜底取消失败, helpId={}", help.getId(), e);
            }
        }
        log.info("待支付超时兜底：扫描 {} 条，取消 {} 条（延迟消息兜底）", pendingList.size(), cancelled);
    }

    /** 取消单个待支付超时求助（独立事务；CAS 幂等，与 PayTimeoutConsumer 互不冲突） */
    private Boolean cancelPendingTimeoutHelp(HelpRequest help) {
        return transactionTemplate.execute(status -> {
            // 求助 0→4（已取消）
            if (helpRequestMapper.updateStatusIf(help.getId(), 0, 4) == 0) {
                return Boolean.FALSE;  // 已支付/已取消 -> 幂等作废
            }
            // 支付订单 0→2（作废，未支付无款可退）
            payOrderMapper.updateStatusIf(help.getId(), 0, 2);
            // 清缓存
            redisUtils.delete("help:item:" + help.getId());
            // 通知发布者（幂等）
            sendNotification(help.getUserId(), help, "求助支付超时已取消",
                    "您发布的求助「" + help.getTitle() + "」超过30分钟未支付，已自动取消（系统兜底）", 1L);
            return Boolean.TRUE;
        });
    }

    // ==================== 2. 僵尸求助清理（自动结束 + 退款） ====================

    private void closeZombieHelps() {
        List<HelpRequest> zombieList = helpRequestMapper.selectZombieHelps();
        if (zombieList.isEmpty()) {
            return;
        }
        int closed = 0;
        for (HelpRequest help : zombieList) {
            try {
                if (closeZombieHelp(help)) {
                    closed++;
                }
            } catch (Exception e) {
                log.error("僵尸求助清理失败, helpId={}", help.getId(), e);
            }
        }
        log.info("僵尸求助清理：扫描 {} 条，结束 {} 条", zombieList.size(), closed);
    }

    /** 结束单个僵尸求助（求助 2→4 + 原路退款，独立事务） */
    private Boolean closeZombieHelp(HelpRequest help) {
        return transactionTemplate.execute(status -> {
            // 求助 2→4（已取消）
            if (helpRequestMapper.updateStatusIf(help.getId(), 2, 4) == 0) {
                return Boolean.FALSE;  // 状态已变更 -> 幂等作废
            }
            // 已支付款项原路退款（同一事务，失败全套回滚）
            Result refund = payService.refundOrder(help.getId(), help.getUserId());
            if (refund == null || !refund.getSuccess()) {
                throw new IllegalStateException(refund != null ? refund.getMessage() : "僵尸求助退款失败");
            }
            // 清理 GEO 与缓存（僵尸求助不在 GEO——满员后 GEO 已清；缓存保险清理）
            redisUtils.delete("help:item:" + help.getId());
            // 通知发布者（幂等）
            sendNotification(help.getUserId(), help, "求助已自动结束",
                    "您的求助「" + help.getTitle() + "」长时间无人完成，系统已自动结束并退款", 2L);
            return Boolean.TRUE;
        });
    }

    // ==================== 工具 ====================

    /** 发通知（幂等：按 (relatedId, type) 先查后插 + 唯一索引兜底） */
    private void sendNotification(Long userId, HelpRequest help, String title, String content, Long type) {
        try {
            if (notificationMapper.selectByUserIdRelatedIdAndType(userId, help.getId(), type.intValue()) == null) {
                Notification notification = new Notification();
                notification.setUserId(userId);
                notification.setTitle(title);
                notification.setContent(content);
                notification.setType(type.intValue());
                notification.setRelatedId(help.getId());
                notificationMapper.insert(notification);
            }
        } catch (DuplicateKeyException e) {
            log.debug("兜底通知已存在，跳过: helpId={}, type={}", help.getId(), type);
        }
    }
}
