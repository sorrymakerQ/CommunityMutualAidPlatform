package com.linlibang.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.linlibang.config.RocketMQConfig;
import com.linlibang.entity.HelpApply;
import com.linlibang.entity.CreditLog;
import com.linlibang.entity.OrderStatusLog;
import com.linlibang.mapper.CreditLogMapper;
import com.linlibang.mapper.HelpApplyMapper;
import com.linlibang.mapper.OrderStatusLogMapper;
import com.linlibang.mapper.HelpRequestMapper;
import com.linlibang.mapper.OrderMapper;
import com.linlibang.mapper.UserCreditMapper;
import com.linlibang.mapper.UserMapper;
import com.linlibang.dto.Result;
import com.linlibang.entity.HelpRequest;
import com.linlibang.entity.Order;
import com.linlibang.entity.User;
import com.linlibang.entity.UserCredit;
import com.linlibang.service.OrderService;
import com.linlibang.exception.OptimisticLockConflictException;
import com.linlibang.utils.RedisUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 订单服务实现类
 * 核心流程：接单 → 进行中 → 完成 → 评价
 * 使用 JdbcTemplate DAO 进行数据库操作，所有 SQL 手写
 */
@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private HelpRequestMapper helpRequestMapper;

    @Resource
    private HelpApplyMapper helpApplyMapper;

    @Resource
    private CreditLogMapper creditLogMapper;

    @Resource
    private OrderStatusLogMapper orderStatusLogMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private UserCreditMapper userCreditMapper;

    @Resource
    private RedisUtils redisUtils;

    /** 用户信息缓存前缀（与 UserServiceImpl 一致，信用分变更后失效） */
    private static final String USER_CACHE_PREFIX = "user:info:";

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private PlatformTransactionManager transactionManager;

    /** 编程式事务模板：单次独立事务 */
    private TransactionTemplate transactionTemplate;

    @PostConstruct
    private void initTransactionTemplate() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 求助分页缓存前缀 */
    private static final String HELP_PAGE_KEY = "help:page:";

    /** 清除求助分页缓存（数据变更时保持 Redis 与 DB 一致） */
    private void clearHelpCache() {
        Set<String> keys = redisUtils.scanKeys(HELP_PAGE_KEY + "*");
        if (keys != null && !keys.isEmpty()) {
            redisUtils.delete(keys);
        }
    }

    /**
     * 订单状态审计埋点（与业务同事务，随业务提交/回滚）
     */
    private void auditOrderStatus(Long orderId, Integer fromStatus, Integer toStatus,
                                  Long operatorId, String operatorType, String reason) {
        try {
            OrderStatusLog log = new OrderStatusLog();
            log.setOrderId(orderId);
            log.setFromStatus(fromStatus);
            log.setToStatus(toStatus);
            log.setOperatorId(operatorId);
            log.setOperatorType(operatorType);
            log.setReason(reason);
            orderStatusLogMapper.insert(log);
        } catch (Exception e) {
            // 审计失败不阻断业务（降级：日志记录），但同事务内正常不会失败
            log.warn("订单状态审计记录失败: orderId={}", orderId, e);
        }
    }

    @Override
    public Result acceptOrder(Long helpId, Long helperId) {
        // 老板审批制：提交接单申请，等待发布者确认（不再直接占名额）
        HelpRequest help = helpRequestMapper.selectById(helpId);
        if (help == null) {
            return Result.fail("求助不存在或已删除");
        }
        if (help.getStatus() != 1) {
            if (help.getStatus() == 0) return Result.fail("该求助尚未支付，暂不可接");
            if (help.getStatus() == 2) return Result.fail("该求助已招满");
            return Result.fail("该求助已结束");
        }
        if (help.getUserId().equals(helperId)) {
            return Result.fail("不能申请接自己发布的求助");
        }
        // 已录用者不能重复申请；被拒绝者可重新申请（insertOrReopen 自动重置为待确认）
        HelpApply existing = helpApplyMapper.selectByHelpAndHelper(helpId, helperId);
        if (existing != null && existing.getStatus() == 1) {
            return Result.fail("您已被录用，正在接单中，请勿重复申请");
        }
        // 幂等：唯一键 (help_id, helper_id) 兜底并发重复申请
        helpApplyMapper.insertOrReopen(helpId, helperId);
        return Result.ok("申请已提交，等待发布者确认");
    }

    @Override
    public Result getApplyList(Long helpId, Long publisherId) {
        HelpRequest help = helpRequestMapper.selectById(helpId);
        if (help == null) return Result.fail("求助不存在");
        if (!help.getUserId().equals(publisherId)) return Result.fail("无权查看他人的求助申请");

        List<HelpApply> applies = helpApplyMapper.selectByHelpId(helpId);
        List<Long> helperIds = applies.stream()
                .map(HelpApply::getHelperId).distinct().collect(Collectors.toList());
        Map<Long, User> userMap = helperIds.isEmpty() ? Collections.emptyMap()
                : userMapper.selectByIds(helperIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        // 信用分已拆分到 tb_user_credit，批量补查（一次 IN，避免 N+1）
        Map<Long, Integer> creditMap = helperIds.isEmpty() ? Collections.emptyMap()
                : userCreditMapper.selectByUserIds(helperIds).stream()
                        .collect(Collectors.toMap(UserCredit::getUserId, UserCredit::getCredit, (a, b) -> a));

        List<Map<String, Object>> resultList = new ArrayList<>();
        for (HelpApply ap : applies) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", ap.getId());
            item.put("helperId", ap.getHelperId());
            item.put("status", ap.getStatus());
            item.put("createTime", ap.getCreateTime());
            User helper = userMap.get(ap.getHelperId());
            if (helper != null) {
                item.put("helperName", helper.getNickname());
                item.put("helperAvatar", helper.getAvatar());
                item.put("helperCredit", creditMap.getOrDefault(ap.getHelperId(), 100));
                item.put("helperHelpCount", helper.getHelpCount());
            }
            resultList.add(item);
        }
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("helpId", helpId);
        resultMap.put("helperNum", help.getHelperNum());
        resultMap.put("acceptedNum", help.getAcceptedNum());
        resultMap.put("status", help.getStatus());
        resultMap.put("list", resultList);
        return Result.ok(resultMap);
    }

    @Override
    @Transactional
    public Result approveApply(Long applyId, Long publisherId, String reason) {
        HelpApply apply = helpApplyMapper.selectById(applyId);
        if (apply == null) return Result.fail("申请不存在");
        if (apply.getStatus() != 0) {
            return Result.fail(apply.getStatus() == 1 ? "该申请已处理" : "该申请已被拒绝");
        }
        HelpRequest help = helpRequestMapper.selectById(apply.getHelpId());
        if (help == null) return Result.fail("求助不存在");
        if (!help.getUserId().equals(publisherId)) return Result.fail("无权操作他人的求助");
        if (help.getStatus() != 1) {
            return Result.fail(help.getStatus() == 2 ? "该求助已招满" : "该求助已结束");
        }

        // 1. 申请 0→1 + 记录审批理由（CAS）
        if (helpApplyMapper.approveOrReject(applyId, 0, 1, reason) == 0) {
            throw new IllegalStateException("申请状态已变更，请刷新后重试");
        }
        // 2. 生成订单（进行中；seq=1 占住唯一键 uk(help_id, helper_id, seq)，
        //    同一人对同一求助的活跃订单只允许一条，取消时 seq 置 0 释放键位）
        Order order = new Order();
        order.setHelpId(help.getId());
        order.setPublisherId(help.getUserId());
        order.setHelperId(apply.getHelperId());
        order.setStatus(2);  // 进行中
        order.setSeq(1);     // 活跃订单占位
        orderMapper.insert(order);
        // 审计：订单创建（from=null → 2，操作人=发布者）
        auditOrderStatus(order.getId(), null, 2, publisherId, "USER", "老板审批同意");
        // 3. 名额 CAS 占位（核心防超卖）：并发审批下数据库行锁裁决只有一个成功；
        //    失败（已满员/状态变更）抛异常整体回滚（订单与申请状态一并回滚）
        if (helpRequestMapper.acceptSlot(help.getId()) == 0) {
            throw new IllegalStateException("该求助已招满，请刷新后再试");
        }
        // 4. 满员后才拒绝其余待确认申请；未满员则保留继续招募
        //    （修复：之前无条件批量拒绝，导致多人求助只招到部分人时误拒其他待确认申请）
        HelpRequest afterAccept = helpRequestMapper.selectById(help.getId());
        if (afterAccept != null && afterAccept.getStatus() != null && afterAccept.getStatus() == 2) {
            helpApplyMapper.rejectPendingByHelpId(help.getId());
        }
        // Redis 操作放到事务提交后，避免 DB 回滚后 Redis 残留脏数据
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisUtils.delete("help:item:" + help.getId());
                clearHelpCache();
            }
        });

        // 5. 通知被录用者（RocketMQ 不可用时不影响审批；理由随通知下发）
        try {
            Map<String, Object> notifData = new HashMap<>();
            notifData.put("userId", apply.getHelperId());
            notifData.put("title", "您的接单申请被通过了！");
            String content = "您在「" + help.getTitle() + "」的申请已被发布者通过，请尽快联系";
            if (reason != null && !reason.trim().isEmpty()) {
                content += "（老板留言：" + reason.trim() + "）";
            }
            notifData.put("content", content);
            notifData.put("type", 2);
            notifData.put("relatedId", order.getId());
            rocketMQTemplate.convertAndSend(RocketMQConfig.NOTIFICATION_TOPIC, notifData);
        } catch (Exception e) {
            // RocketMQ 不可用，通知稍后补发
        }

        return Result.ok("已同意该申请，订单已生成", order.getId());
    }

    @Override
    public Result rejectApply(Long applyId, Long publisherId, String reason) {
        HelpApply apply = helpApplyMapper.selectById(applyId);
        if (apply == null) return Result.fail("申请不存在");
        HelpRequest help = helpRequestMapper.selectById(apply.getHelpId());
        if (help == null || !help.getUserId().equals(publisherId)) {
            return Result.fail("无权操作他人的求助");
        }
        if (apply.getStatus() != 0) return Result.fail("该申请已处理");
        // 拒绝 + 记录理由（CAS）
        helpApplyMapper.approveOrReject(applyId, 0, 2, reason);

        // 通知申请人（理由随通知下发，RocketMQ 不可用不影响审批）
        try {
            Map<String, Object> notifData = new HashMap<>();
            notifData.put("userId", apply.getHelperId());
            notifData.put("title", "您的接单申请被拒绝了");
            String content = "很遗憾，您在「" + help.getTitle() + "」的接单申请未被通过";
            if (reason != null && !reason.trim().isEmpty()) {
                content += "。理由：" + reason.trim();
            }
            notifData.put("content", content);
            notifData.put("type", 2);
            notifData.put("relatedId", help.getId());
            rocketMQTemplate.convertAndSend(RocketMQConfig.NOTIFICATION_TOPIC, notifData);
        } catch (Exception e) {
            // RocketMQ 不可用，通知稍后补发
        }

        return Result.ok("已拒绝该申请");
    }

    @Override
    public Result cancelOrder(Long orderId, Long userId, String reason) {
        // 版本号乐观锁：单次事务，冲突 -> 整体回滚 -> 直接返回失败（不再退避重试）
        try {
            return transactionTemplate.execute(status -> doCancelInTx(orderId, userId, reason));
        } catch (OptimisticLockConflictException e) {
            return Result.fail(e.getMessage());
        }
    }

    /** 取消订单的事务内逻辑（乐观锁冲突抛异常 -> 事务回滚） */
    private Result doCancelInTx(Long orderId, Long userId, String reason) {
        // 1. 查询订单（使用 SQL 语句：SELECT * FROM tb_order WHERE id = ?）
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }

        // 2. 只有订单参与者可以取消
        if (!order.getPublisherId().equals(userId) && !order.getHelperId().equals(userId)) {
            return Result.fail("无权取消此订单");
        }

        // 3. 只能取消"已接单"或"进行中"状态的订单
        if (order.getStatus() != 1 && order.getStatus() != 2) {
            return Result.fail("当前订单状态不允许取消");
        }

        // 4. 版本号乐观锁更新订单状态：
        //    version 与读到的不一致 = 期间已被其他事务修改 -> 抛冲突触发整体回滚重试
        int fromStatus = order.getStatus();
        order.setStatus(4);  // 已取消
        order.setCancelReason(reason);
        order.setSeq(0);     // 释放唯一键位（uk_help_helper_seq）：取消后同一人可重新接同一求助
        int rows = orderMapper.updateByIdWithVersion(order);
        if (rows == 0) {
            throw new OptimisticLockConflictException("订单状态已变化，取消失败，请刷新后重试");
        }
        // 审计：用户取消（from → 4）
        auditOrderStatus(orderId, fromStatus, 4, userId, "USER", reason);

        // 5. 释放求助名额（多人求助：accepted_num - 1 并恢复招募中，
        //    空出的名额可继续招人；releaseSlot 为原子条件更新，无需重试）
        HelpRequest help = helpRequestMapper.selectById(order.getHelpId());
        if (help != null && helpRequestMapper.releaseSlot(help.getId()) > 0) {
            // Redis 操作放到事务提交后，避免 DB 回滚后 Redis 残留脏数据
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisUtils.delete("help:item:" + help.getId());
                    clearHelpCache();
                    redisUtils.geoAdd("help:location", help.getLng(), help.getLat(), help.getId().toString());
                }
            });
        }

        // 6. 扣除取消方信用分（tb_user_credit 原子增减，单语句行锁防丢更新）+ 信用分流水
        UserCredit cancelCredit = userCreditMapper.selectByUserId(userId);
        if (cancelCredit != null && cancelCredit.getCredit() > 0) {
            userCreditMapper.updateCreditDelta(userId, -5);  // 扣5分，SQL 内 GREATEST(0,..) 兜底不为负
            // 行为分流水（与扣分同事务，信用分增减全记录）
            CreditLog creditLog = new CreditLog();
            creditLog.setUserId(userId);
            creditLog.setDelta(-5);
            creditLog.setReason("取消订单");
            creditLog.setOrderId(orderId);
            creditLogMapper.insert(creditLog);
            // 事务提交后失效用户缓存（含 credit 的 user:info），保证展示一致性
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisUtils.delete(USER_CACHE_PREFIX + userId);
                }
            });
        }

        return Result.ok("订单已取消");
    }

    @Override
    public Result finishOrder(Long orderId, Long userId) {
        // 版本号乐观锁：单次事务，冲突 -> 整体回滚 -> 直接返回失败（不再退避重试）
        try {
            return transactionTemplate.execute(status -> doFinishInTx(orderId, userId));
        } catch (OptimisticLockConflictException e) {
            return Result.fail(e.getMessage());
        }
    }

    /** 完成订单的事务内逻辑（乐观锁冲突抛异常 -> 事务回滚） */
    private Result doFinishInTx(Long orderId, Long userId) {
        // 1. 查询订单
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }

        // 2. 只有发布者可以确认完成
        if (!order.getPublisherId().equals(userId)) {
            return Result.fail("只有发布者可以确认完成");
        }

        // 3. 只能完成"进行中"的订单
        if (order.getStatus() != 2) {
            return Result.fail("当前状态不允许完成");
        }

        // 4. 版本号乐观锁更新订单状态
        order.setStatus(3);  // 已完成
        order.setFinishTime(java.time.LocalDateTime.now());
        int rows = orderMapper.updateByIdWithVersion(order);
        if (rows == 0) {
            throw new OptimisticLockConflictException("订单状态已变化，完成失败，请刷新后重试");
        }
        // 审计：发布者确认完成（2 → 3）
        auditOrderStatus(orderId, 2, 3, userId, "USER", "发布者确认完成");

        // 5. 多人求助的"整体完成"判断：仅当该求助没有其他未完成订单时，
        //    求助整体才置为已完成（否则其他人还在进行中，求助保持原状态）
        HelpRequest help = helpRequestMapper.selectById(order.getHelpId());
        if (help != null) {
            Long otherActive = orderMapper.countOtherActiveByHelpId(help.getId(), orderId);
            if (otherActive == null || otherActive == 0) {
                // 我是最后一个完成的 -> 求助整体转已完成（原子条件更新，无需重试）
                if (helpRequestMapper.updateStatusIfIn(help.getId(), Arrays.asList(1, 2), 3) == 0) {
                    throw new OptimisticLockConflictException("求助状态已变化，完成失败，请稍后重试");
                }
            }
            // Redis 操作放到事务提交后，避免 DB 回滚后 Redis 残留脏数据
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisUtils.delete("help:item:" + help.getId());
                    clearHelpCache();
                }
            });
        }

        // 6. 酬劳结算：发布者支付的总酬劳中，每位接单者结算单价份额
        //    钱一直在发布者账户里冻结？不——支付时已全额扣除，沉淀在 pay_order。
        //    这里按"每人 reward 单价"结算给接单者，多人求助每位各得一份；
        //    结算与订单状态变更同事务：状态 CAS 成功才结算，天然幂等（重复完成被 CAS 拦截）。
        if (help != null && help.getReward() != null && help.getReward().compareTo(java.math.BigDecimal.ZERO) > 0) {
            userMapper.addBalance(order.getHelperId(), help.getReward());
            // 结算后失效接单者用户缓存（余额展示一致性）
            Long settleHelperId = order.getHelperId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisUtils.delete(USER_CACHE_PREFIX + settleHelperId);
                }
            });
        }

        // 7. 增加接单者信用分和帮助次数
        //    credit：tb_user_credit 原子增减（单语句行锁防丢更新）+ 流水（同事务）
        //    helpCount：仍属 tb_user，保留乐观锁更新
        User helper = userMapper.selectById(order.getHelperId());
        if (helper != null) {
            userCreditMapper.updateCreditDelta(order.getHelperId(), 10);  // 加10分
            helper.setHelpCount(helper.getHelpCount() + 1);
            int uRows = userMapper.updateByIdWithVersion(helper);
            if (uRows == 0) {
                throw new OptimisticLockConflictException("用户信息已被并发修改，完成失败，请稍后重试");
            }
            // 行为分流水（与加分同事务，信用分增减全记录）
            CreditLog creditLog = new CreditLog();
            creditLog.setUserId(order.getHelperId());
            creditLog.setDelta(10);
            creditLog.setReason("完成订单");
            creditLog.setOrderId(orderId);
            creditLogMapper.insert(creditLog);
            // 事务提交后失效用户缓存（含 credit 的 user:info），保证展示一致性
            Long helperId = order.getHelperId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisUtils.delete(USER_CACHE_PREFIX + helperId);
                }
            });
        }

        // 8. 发送评价提醒通知（RocketMQ 不可用时不影响完成）
        try {
            Map<String, Object> notifData = new HashMap<>();
            notifData.put("userId", order.getPublisherId());
            notifData.put("title", "订单已完成");
            notifData.put("content", "请对邻居的服务进行评价");
            notifData.put("type", 3);
            notifData.put("relatedId", orderId);
            rocketMQTemplate.convertAndSend(RocketMQConfig.NOTIFICATION_TOPIC, notifData);
        } catch (Exception e) {
            // RocketMQ 不可用
        }

        return Result.ok("订单已完成，请评价");
    }

    @Override
    public Result reviewOrder(Long orderId, Long userId, Integer score, String comment) {
        // 评价异步化：主线程只做轻量校验 + 投递 RocketMQ，落库由 ReviewConsumer 完成。
        // 理由：评价不是用户请求链路上的关键动作，异步后接口秒回；
        //      消费端用版本号乐观锁保证双方并发评价不丢，评完双方后置 5（业务终态）。
        // 1. 评分范围（null 安全，防止 NPE）
        if (score == null || score < 1 || score > 5) {
            return Result.fail("评分范围为1-5分");
        }

        // 2. 快速失败校验：订单存在性 + 参与者（防止无效消息堆积）
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!order.getPublisherId().equals(userId) && !order.getHelperId().equals(userId)) {
            return Result.fail("无权评价此订单");
        }
        // 状态校验：仅"已完成(3)"可评价。进行中/已取消订单评价会造成：
        //   1) 合谋刷信用分（下单即互评 5 星再取消，循环 +2）
        //   2) 对已取消订单补差评打击对方信用分
        if (order.getStatus() != 3 && order.getStatus() != 5) {
            return Result.fail(order.getStatus() == 4 ? "订单已取消，无法评价" : "订单完成后才能评价");
        }
        // 3. 已评过则直接拒绝（大部分重复请求在这里拦截；并发极端情况由消费端 CAS 兜底）
        if (order.getPublisherId().equals(userId) && order.getPublisherScore() != null) {
            return Result.fail("已经评价过了");
        }
        if (order.getHelperId().equals(userId) && order.getHelperScore() != null) {
            return Result.fail("已经评价过了");
        }

        // 4. 投递评价消息（RocketMQ 不可用则降级返回失败，由用户重试）
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("orderId", orderId);
            data.put("userId", userId);
            data.put("score", score);
            data.put("comment", comment);
            rocketMQTemplate.convertAndSend(RocketMQConfig.REVIEW_TOPIC, data);
        } catch (Exception e) {
            return Result.fail("评价提交失败，请稍后重试");
        }
        return Result.ok("评价已提交，正在生效");
    }

    @Override
    public Result getOrderById(Long orderId, Long userId) {
        // 1. 查询订单
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }

        // 越权校验：仅订单参与方可查看详情，防止遍历订单 ID 获取他人手机号
        if (!order.getPublisherId().equals(userId) && !order.getHelperId().equals(userId)) {
            return Result.fail(403, "无权查看此订单");
        }

        // 2. 组装详情
        Map<String, Object> detail = new HashMap<>();
        detail.put("id", order.getId());
        detail.put("helpId", order.getHelpId());
        detail.put("publisherId", order.getPublisherId());
        detail.put("helperId", order.getHelperId());
        detail.put("status", order.getStatus());
        detail.put("cancelReason", order.getCancelReason());
        detail.put("acceptTime", order.getAcceptTime());
        detail.put("finishTime", order.getFinishTime());
        detail.put("publisherScore", order.getPublisherScore());
        detail.put("helperScore", order.getHelperScore());
        detail.put("publisherComment", order.getPublisherComment());
        detail.put("helperComment", order.getHelperComment());
        detail.put("createTime", order.getCreateTime());

        // 3. 求助信息
        HelpRequest help = helpRequestMapper.selectById(order.getHelpId());
        if (help != null) {
            detail.put("helpTitle", help.getTitle());
            detail.put("helpDescription", help.getDescription());
            detail.put("helpReward", help.getReward());
            detail.put("helpAddress", help.getAddress());
            detail.put("helpStatus", help.getStatus());
        }

        // 4. 发布者信息（信用分已拆分：单独查 tb_user_credit）
        User publisher = userMapper.selectById(order.getPublisherId());
        if (publisher != null) {
            UserCredit publisherCredit = userCreditMapper.selectByUserId(publisher.getId());
            Map<String, Object> publisherInfo = new HashMap<>();
            publisherInfo.put("id", publisher.getId());
            publisherInfo.put("nickname", publisher.getNickname());
            publisherInfo.put("avatar", publisher.getAvatar());
            publisherInfo.put("phone", maskPhone(publisher.getPhone()));
            publisherInfo.put("credit", publisherCredit != null ? publisherCredit.getCredit() : 100);
            publisherInfo.put("helpCount", publisher.getHelpCount());
            detail.put("publisher", publisherInfo);
        }

        // 5. 接单者信息（信用分已拆分：单独查 tb_user_credit）
        User helper = userMapper.selectById(order.getHelperId());
        if (helper != null) {
            UserCredit helperCredit = userCreditMapper.selectByUserId(helper.getId());
            Map<String, Object> helperInfo = new HashMap<>();
            helperInfo.put("id", helper.getId());
            helperInfo.put("nickname", helper.getNickname());
            helperInfo.put("avatar", helper.getAvatar());
            helperInfo.put("phone", maskPhone(helper.getPhone()));
            helperInfo.put("credit", helperCredit != null ? helperCredit.getCredit() : 100);
            helperInfo.put("helpCount", helper.getHelpCount());
            detail.put("helper", helperInfo);
        }

        return Result.ok(detail);
    }

    @Override
    public Result getMyOrders(Long userId, String role, Integer page, Integer size) {
        int pageNum = page != null ? page : 1;
        int pageSize = size != null ? size : 10;
        int offset = (pageNum - 1) * pageSize;

        List<Order> orders = orderMapper.selectByUserIdAndRole(userId, role, offset, pageSize);
        Long total = orderMapper.selectCountByUserIdAndRole(userId, role);

        // 批量查询关联的求助和用户信息（避免 N+1 问题）
        // 空集合守卫：MyBatis <foreach> 空 list 会生成 IN () 导致 SQL 语法错误
        Map<Long, HelpRequest> helpMap = Collections.emptyMap();
        Map<Long, User> userMap = Collections.emptyMap();

        if (!orders.isEmpty()) {
            List<Long> helpIds = orders.stream()
                    .map(Order::getHelpId).distinct().collect(Collectors.toList());
            List<Long> otherUserIds = orders.stream()
                    .map(o -> o.getPublisherId().equals(userId) ? o.getHelperId() : o.getPublisherId())
                    .distinct().collect(Collectors.toList());

            if (!helpIds.isEmpty()) {
                helpMap = helpRequestMapper.selectByIds(helpIds).stream()
                        .collect(Collectors.toMap(HelpRequest::getId, h -> h, (a, b) -> a));
            }
            if (!otherUserIds.isEmpty()) {
                userMap = userMapper.selectByIds(otherUserIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
            }
        }

        // 组装返回数据（关联求助标题和对方信息）
        List<Map<String, Object>> enrichedList = new ArrayList<>();
        for (Order order : orders) {
            Map<String, Object> item = BeanUtil.beanToMap(order);

            // 求助信息
            HelpRequest help = helpMap.get(order.getHelpId());
            if (help != null) {
                item.put("helpTitle", help.getTitle());
                item.put("reward", help.getReward());
            }

            // 对方信息
            Long otherUserId = order.getPublisherId().equals(userId)
                    ? order.getHelperId()
                    : order.getPublisherId();
            User otherUser = userMap.get(otherUserId);
            if (otherUser != null) {
                item.put("otherName", otherUser.getNickname());
                item.put("otherAvatar", otherUser.getAvatar());
            }

            enrichedList.add(item);
        }

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", enrichedList);
        resultMap.put("total", total);

        return Result.ok(resultMap);
    }

    /** 手机号脱敏：138****1234 */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
