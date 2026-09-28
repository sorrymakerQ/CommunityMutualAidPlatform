package com.linlibang.listener;

import cn.hutool.json.JSONUtil;
import com.linlibang.config.RocketMQConfig;
import com.linlibang.dto.Result;
import com.linlibang.entity.CreditLog;
import com.linlibang.entity.Order;
import com.linlibang.entity.OrderStatusLog;
import com.linlibang.entity.Review;
import com.linlibang.exception.OptimisticLockConflictException;
import com.linlibang.mapper.CreditLogMapper;
import com.linlibang.mapper.OrderMapper;
import com.linlibang.mapper.OrderStatusLogMapper;
import com.linlibang.mapper.ReviewMapper;
import com.linlibang.mapper.UserCreditMapper;
import com.linlibang.utils.RedisUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Map;

/**
 * 评价异步消费者（RocketMQ）—— 独立评价模块的落库端
 *
 * 主线程只投递，本消费者完成评价落库：
 *   1. 写 tb_review（评价记录，与用户/订单业务解耦，uk_order_from 唯一键幂等）；
 *   2. 同步订单冗余评分字段（查询兼容），双方都评后订单置 5（业务终态）；
 *   3. 信用分"评价分"结算（双轨规则：被评 5 星 +2、1-2 星 -5、3-4 星不变）
 *      + 记 tb_credit_log 流水（缺分/漏分可查）；
 *   4. 事务 + 版本号乐观锁：并发双方评价评分不丢，冲突抛异常交由 RocketMQ 重投。
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = RocketMQConfig.REVIEW_TOPIC,
        consumerGroup = RocketMQConfig.REVIEW_CONSUMER_GROUP)
public class ReviewConsumer implements RocketMQListener<String> {

    /** 评价分规则：5 星好评 +2，1-2 星差评 -5，3-4 星不变 */
    private static final int GOOD_REVIEW_DELTA = 2;
    private static final int BAD_REVIEW_DELTA = -5;

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private ReviewMapper reviewMapper;

    @Resource
    private CreditLogMapper creditLogMapper;

    @Resource
    private UserCreditMapper userCreditMapper;

    @Resource
    private RedisUtils redisUtils;

    @Resource
    private OrderStatusLogMapper orderStatusLogMapper;

    @Resource
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @PostConstruct
    private void init() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void onMessage(String message) {
        Map<String, Object> data;
        try {
            data = JSONUtil.parseObj(message);
        } catch (Exception e) {
            log.error("评价消息体非法，丢弃: {}", message, e);
            return;
        }

        Long orderId = Long.valueOf(data.get("orderId").toString());
        Long userId = Long.valueOf(data.get("userId").toString());
        Integer score = Integer.valueOf(data.get("score").toString());
        String comment = (String) data.get("comment");

        try {
            Result result = transactionTemplate.execute(status ->
                    doReviewInTx(orderId, userId, score, comment));
            // Result.fail("已经评价过了") 等业务失败视为消息消费成功（幂等作废），不重投
            if (result == null || !result.getSuccess()) {
                log.debug("评价消费作废: orderId={}, userId={}, msg={}",
                        orderId, userId, result != null ? result.getMessage() : "null");
            }
        } catch (OptimisticLockConflictException e) {
            // 版本冲突：抛出让 RocketMQ 重投（再次消费时大概率已成功或幂等作废）
            throw e;
        }
    }

    /**
     * 评价落库（单次事务，冲突抛异常交由 RocketMQ 重投）
     */
    private Result doReviewInTx(Long orderId, Long userId, Integer score, String comment) {
        // 1. 查询订单（新事务 -> 最新快照）
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }

        // 2. 判定评价方向（评价人 → 被评价人）
        Long toUserId;
        if (order.getPublisherId().equals(userId)) {
            toUserId = order.getHelperId();
        } else if (order.getHelperId().equals(userId)) {
            toUserId = order.getPublisherId();
        } else {
            return Result.fail("无权评价此订单");
        }

        // 2.5 状态校验（防刷分）：仅已完成(3)/已评价(5)可结算。
        // 消息在途期间订单可能被取消——已取消订单不结算信用分，防止合谋刷分与恶意差评。
        if (order.getStatus() != 3 && order.getStatus() != 5) {
            return Result.fail(order.getStatus() == 4 ? "订单已取消，评价作废" : "订单未完成，评价作废");
        }

        // 3. 幂等：该订单该评价人已评过 → 作废（先查 + 唯一键兜底）
        if (reviewMapper.selectByOrderAndFrom(orderId, userId) != null) {
            return Result.fail("已经评价过了");
        }

        // 4. 写评价记录（独立评价模块核心表）
        Review review = new Review();
        review.setOrderId(orderId);
        review.setFromUserId(userId);
        review.setToUserId(toUserId);
        review.setScore(score);
        review.setComment(comment);
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // 并发重复投递被 uk_order_from 拦下 -> 幂等作废（不结算两次信用分）
            return Result.fail("已经评价过了");
        }

        // 5. 订单冗余评分字段同步（查询兼容），双方都评后置 5（业务终态）
        if (order.getPublisherId().equals(userId)) {
            order.setPublisherScore(score);
            order.setPublisherComment(comment);
        } else {
            order.setHelperScore(score);
            order.setHelperComment(comment);
        }
        if (order.getPublisherScore() != null && order.getHelperScore() != null) {
            order.setStatus(5);  // 已评价
        }
        int rows = orderMapper.updateByIdWithVersion(order);
        if (rows == 0) {
            throw new OptimisticLockConflictException("订单刚被对方评价，重试中");
        }
        // 审计：双方评价完成 → 5（业务终态）
        if (order.getStatus() == 5) {
            try {
                OrderStatusLog audit = new OrderStatusLog();
                audit.setOrderId(orderId);
                audit.setFromStatus(3);
                audit.setToStatus(5);
                audit.setOperatorId(userId);
                audit.setOperatorType("USER");
                audit.setReason("双方评价完成");
                orderStatusLogMapper.insert(audit);
            } catch (Exception e) {
                log.warn("评价终态审计记录失败: orderId={}", orderId, e);
            }
        }

        // 6. 评价分结算（双轨中的"评价分"）+ 信用分流水
        int delta = 0;
        if (score != null && score == 5) {
            delta = GOOD_REVIEW_DELTA;
        } else if (score != null && score <= 2) {
            delta = BAD_REVIEW_DELTA;
        }
        if (delta != 0) {
            // 信用分已拆分到 tb_user_credit：原子增减（单语句行锁防丢更新），与流水同事务
            userCreditMapper.updateCreditDelta(toUserId, delta);
            CreditLog creditLog = new CreditLog();
            creditLog.setUserId(toUserId);
            creditLog.setDelta(delta);
            creditLog.setReason(score == 5 ? "评价5星" : "评价低分");
            creditLog.setOrderId(orderId);
            creditLogMapper.insert(creditLog);
            // 事务提交后失效用户缓存（含 credit 的 user:info），保证展示一致性
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisUtils.delete("user:info:" + toUserId);
                }
            });
        }

        return Result.ok("评价成功");
    }
}
