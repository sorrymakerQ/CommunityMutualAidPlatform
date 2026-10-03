package com.linlibang.service.impl;

import com.linlibang.dto.Result;
import com.linlibang.entity.CreditLog;
import com.linlibang.entity.Order;
import com.linlibang.entity.OrderStatusLog;
import com.linlibang.entity.Review;
import com.linlibang.mapper.CreditLogMapper;
import com.linlibang.mapper.OrderMapper;
import com.linlibang.mapper.OrderStatusLogMapper;
import com.linlibang.mapper.ReviewMapper;
import com.linlibang.mapper.UserCreditMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * 评价落库与信用分结算（同步执行）
 *
 * 原实现是 RocketMQ 异步消费者（ReviewConsumer），按"RocketMQ 只留支付超时延迟消息"的要求
 * 改为同步调用，业务规则一字未改：
 *   1. 写 tb_review（uk_order_from 唯一键幂等，同一人同一订单只能评一次）；
 *   2. 同步订单冗余评分字段（publisher_score/helper_score），双方都评后订单置 5（业务终态）+ 审计；
 *   3. 信用分"评价分"结算（5 星 +2、1-2 星 -5、3-4 星不变）+ 记 tb_credit_log 流水；
 *   4. 事务 + 版本号乐观锁：并发双方评价不丢分；冲突改为本地重试（原为 MQ 重投）。
 */
@Slf4j
@Service
public class ReviewSettleService {

    /** 评价分规则：5 星好评 +2，1-2 星差评 -5，3-4 星不变 */
    private static final int GOOD_REVIEW_DELTA = 2;
    private static final int BAD_REVIEW_DELTA = -5;

    /** 乐观锁冲突时的本地重试次数（替代原 RocketMQ 重投） */
    private static final int MAX_RETRY = 3;

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private ReviewMapper reviewMapper;

    @Resource
    private CreditLogMapper creditLogMapper;

    @Resource
    private UserCreditMapper userCreditMapper;

    @Resource
    private OrderStatusLogMapper orderStatusLogMapper;

    @Resource
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @PostConstruct
    private void init() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 评价落库 + 结算（同步）
     *
     * @return 成功 = 已落库并结算；失败 = 已评过 / 无权 / 订单状态不允许（幂等作废，不重试）
     */
    public Result review(Long orderId, Long userId, Integer score, String comment) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                Result result = transactionTemplate.execute(status ->
                        doReviewInTx(orderId, userId, score, comment));
                return result != null ? result : Result.fail("评价失败，请稍后重试");
            } catch (IllegalStateException e) {
                // 版本冲突：对方刚评价过，重试可拿到最新快照（原为抛出让 MQ 重投）
                if (attempt == MAX_RETRY) {
                    log.warn("评价乐观锁冲突重试 {} 次仍失败: orderId={}, userId={}", MAX_RETRY, orderId, userId);
                    return Result.fail("评价提交繁忙，请稍后重试");
                }
                log.info("评价乐观锁冲突，第 {} 次重试: orderId={}, userId={}", attempt, orderId, userId);
            }
        }
        return Result.fail("评价提交繁忙，请稍后重试");
    }

    /**
     * 评价落库（单次事务，版本冲突抛 IllegalStateException 交由外层重试）
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
        // 已取消订单不结算信用分，防止合谋刷分与恶意差评。
        if (order.getStatus() != 3 && order.getStatus() != 5) {
            return Result.fail(order.getStatus() == 4 ? "订单已取消，评价作废" : "订单未完成，评价作废");
        }

        // 3. 幂等：该订单该评价人已评过 -> 作废（先查 + 唯一键兜底）
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
            // 并发重复提交被 uk_order_from 拦下 -> 幂等作废（不结算两次信用分）
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
            throw new IllegalStateException("订单刚被对方评价，重试中");
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
        }

        return Result.ok("评价成功");
    }
}
