package com.linlibang.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import com.linlibang.config.RocketMQConfig;
import com.linlibang.entity.CreditLog;
import com.linlibang.entity.OrderStatusLog;
import com.linlibang.mapper.CreditLogMapper;
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
import com.linlibang.utils.RedisUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
    private CreditLogMapper creditLogMapper;

    @Resource
    private OrderStatusLogMapper orderStatusLogMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private UserCreditMapper userCreditMapper;

    @Resource
    private RedisUtils redisUtils;

    /** Redisson 客户端（接单分布式锁，按 helpId 粒度互斥） */
    @Resource
    private RedissonClient redissonClient;

    /** 接单分布式锁 Key 前缀 */
    private static final String ACCEPT_LOCK_KEY = "lock:order:accept:";

    /** 用户信息缓存前缀（与 UserServiceImpl 一致，信用分变更后失效） */
    private static final String HELP_ITEM_KEY = "help:item:";

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    /** 评价落库与信用分结算（原 RocketMQ 异步消费，现同步调用） */
    @Resource
    private ReviewSettleService reviewSettleService;

    @Resource
    private PlatformTransactionManager transactionManager;

    /** 编程式事务模板：单次独立事务 */
    private TransactionTemplate transactionTemplate;

    @PostConstruct
    private void initTransactionTemplate() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
    public Result acceptOrder(Long helpId) {
        Long helperId = StpUtil.getLoginIdAsLong();
        // Redisson 分布式锁：同一求助的并发接单串行化（多实例部署下也互斥）。
        // 唯一键 uk_help_helper_seq(help_id, helper_id, seq) 仍作最终幂等兜底——锁超时/故障时数据依然正确。
        RLock lock = redissonClient.getLock(ACCEPT_LOCK_KEY + helpId);
        boolean locked = false;
        try {
            // 最多等 3 秒抢锁，抢不到快速失败；租约 10 秒，业务极快无需看门狗续期
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                return Result.fail("系统繁忙，请稍后重试");
            }
            return doAcceptOrder(helpId, helperId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("系统繁忙，请稍后重试");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 接单的核心逻辑（锁由 acceptOrder 统一持有，此处无锁）。
     *
     * 接单即录用：校验通过后直接生成订单并占用名额，不再有"提交申请 → 发布者审批"环节。
     * DB 写入走编程式事务：插订单 + 名额 CAS 同事务，任一步失败整体回滚。
     */
    private Result doAcceptOrder(Long helpId, Long helperId) {
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
            return Result.fail("不能接自己发布的求助");
        }
        // 幂等前置校验：已接过且未结束（status 1/2）直接拒绝，避免重复建单
        Order active = orderMapper.selectActiveByHelpIdAndHelper(helpId, helperId);
        if (active != null) {
            return Result.fail("您已接下该求助，请勿重复接单");
        }
        try {
            return transactionTemplate.execute(status -> doAcceptInTx(help, helperId));
        } catch (DuplicateKeyException e) {
            // 并发重复接单兜底：唯一键 uk_help_helper_seq 拦截
            return Result.fail("您已接下该求助，请勿重复接单");
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }

    /** 接单的事务内逻辑：生成订单 + 占用名额（CAS 防超卖） */
    private Result doAcceptInTx(HelpRequest help, Long helperId) {
        // 1. 生成订单（接单即生效，直接进入"进行中"；seq=1 占住唯一键
        //    uk_help_helper_seq，取消时 seq 置 0 释放键位，同一人可重新接同一求助）
        Order order = new Order();
        order.setHelpId(help.getId());
        order.setPublisherId(help.getUserId());
        order.setHelperId(helperId);
        // 订单总金额：接单时把求助的发布总金额快照下来（每人单价 × 需要人数）
        order.setTotalAmount(help.getTotalReward() != null ? help.getTotalReward() : BigDecimal.ZERO);
        order.setStatus(2);  // 进行中
        order.setSeq(1);     // 活跃订单占位
        orderMapper.insert(order);
        // 审计：订单创建（from=null → 2，操作人=接单者）
        auditOrderStatus(order.getId(), null, 2, helperId, "USER", "接单");

        // 2. 名额 CAS 占位（核心防超卖）：并发接单由数据库行锁裁决只有一个成功；
        //    失败（已满员/状态变更）抛异常整体回滚（订单一并回滚）
        if (helpRequestMapper.acceptSlot(help.getId()) == 0) {
            throw new IllegalStateException("该求助已招满，请刷新后重试");
        }

        // 3. Redis 清理与通知放到事务提交后，避免 DB 回滚后残留脏数据/误发通知
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisUtils.delete("help:item:" + help.getId());
                notifyPublisherAccepted(help, order.getId());
            }
        });

        return Result.ok("接单成功", order.getId());
    }

    /** 接单成功后通知发布者（RocketMQ 不可用不影响接单） */
    private void notifyPublisherAccepted(HelpRequest help, Long orderId) {
        try {
            Map<String, Object> notifData = new HashMap<>();
            notifData.put("userId", help.getUserId());
            notifData.put("title", "有人接了您的求助");
            notifData.put("content", "邻居已接下「" + help.getTitle() + "」，请及时与对方沟通");
            notifData.put("type", 2);
            notifData.put("relatedId", orderId);
            rocketMQTemplate.convertAndSend(RocketMQConfig.NOTIFICATION_TOPIC, notifData);
        } catch (Exception e) {
            // RocketMQ 不可用，通知稍后补发
        }
    }

    @Override
    @Transactional
    public Result cancelOrder(Long orderId, String reason) {
        Long userId = StpUtil.getLoginIdAsLong();
        // 版本号乐观锁：单次事务，冲突 -> 整体回滚 -> 直接返回失败（不再退避重试）
        try {
            return doCancelInTx(orderId, userId, reason);
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }

    /** 取消订单的事务内逻辑（乐观锁冲突抛异常 -> 事务回滚） */

    private Result doCancelInTx(Long orderId, Long userId, String reason) {
        // 1. 查询订单
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

        int fromStatus = order.getStatus();
        order.setStatus(4);  // 已取消
        order.setCancelReason(reason);
        order.setSeq(0);     // 释放唯一键位（uk_help_helper_seq）：取消后同一人可重新接同一求助
        int rows = orderMapper.updateByIdWithVersion(order);
        if (rows == 0) {
            throw new IllegalStateException("订单状态已变化，取消失败，请刷新后重试");
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
        }
        return Result.ok("订单已取消");
    }

    @Override
    @Transactional
    public Result finishOrder(Long orderId) {
        Long userId = StpUtil.getLoginIdAsLong();
        try {
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
                throw new IllegalStateException("订单状态已变化，完成失败，请刷新后重试");
            }
            // 审计：发布者确认完成（2 → 3）
            auditOrderStatus(orderId, 2, 3, userId, "USER", "发布者确认完成");

            // 5. 多人求助的"整体完成"判断：仅当该求助没有其他未完成订单时，
            HelpRequest help = helpRequestMapper.selectById(order.getHelpId());
            if (help != null) {
                Long otherActive = orderMapper.countOtherActiveByHelpId(help.getId(), orderId);
                if (otherActive == null || otherActive == 0) {
                    // 我是最后一个完成的 -> 求助整体转已完成（原子条件更新，无需重试）
                    if (helpRequestMapper.updateStatusIfIn(help.getId(), Arrays.asList(1, 2), 3) == 0) {
                        throw new IllegalStateException("求助状态已变化，完成失败，请稍后重试");
                    }
                }
                // Redis 操作放到事务提交后，避免 DB 回滚后 Redis 残留脏数据
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        redisUtils.delete("help:item:" + help.getId());
                    }
                });
            }

            // 6. 酬劳结算：直接发到余额里面
            if (help != null && help.getReward() != null && help.getReward().compareTo(java.math.BigDecimal.ZERO) > 0) {
                userMapper.addBalance(order.getHelperId(), help.getReward());
            }

            // 7. 增加接单者信用分和帮助次数
            //    credit：tb_user_credit
            //    helpCount：仍属 tb_user，
            User helper = userMapper.selectById(order.getHelperId());
            if (helper != null) {
                userCreditMapper.updateCreditDelta(order.getHelperId(), 10);  // 加10分
                helper.setHelpCount(helper.getHelpCount() + 1);
                int uRows = userMapper.updateByIdWithVersion(helper);
                if (uRows == 0) {
                    throw new IllegalStateException("用户信息已被并发修改，完成失败，请稍后重试");
                }
                // 行为分流水（与加分同事务，信用分增减全记录）
                CreditLog creditLog = new CreditLog();
                creditLog.setUserId(order.getHelperId());
                creditLog.setDelta(10);
                creditLog.setReason("完成订单");
                creditLog.setOrderId(orderId);
                creditLogMapper.insert(creditLog);
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
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }



    @Override
    public Result reviewOrder(Long orderId, Integer score, String comment) {
        Long userId = StpUtil.getLoginIdAsLong();
        // 评价改为同步落库（原 RocketMQ 异步通道已移除）：
        // 前置仍是轻量校验（快速失败，避免无效事务），落库 + 信用分结算由 ReviewSettleService 完成，
        // 并发双方评价用版本号乐观锁 + 本地重试保证不丢分。
        // 1. 评分范围（null 安全，防止 NPE）
        if (score == null || score < 1 || score > 5) {
            return Result.fail("评分范围为1-5分");
        }

        // 2. 快速失败校验：订单存在性 + 参与者
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!order.getPublisherId().equals(userId) && !order.getHelperId().equals(userId)) {
            return Result.fail("无权评价此订单");
        }
        // 状态校验：仅"已完成(3)/已评价(5)"可评价。进行中/已取消订单评价会造成：
        //   1) 合谋刷信用分（下单即互评 5 星再取消，循环 +2）
        //   2) 对已取消订单补差评打击对方信用分
        if (order.getStatus() != 3 && order.getStatus() != 5) {
            return Result.fail(order.getStatus() == 4 ? "订单已取消，无法评价" : "订单完成后才能评价");
        }
        // 3. 已评过则直接拒绝（并发极端情况由落库端的唯一键兜底）
        if (order.getPublisherId().equals(userId) && order.getPublisherScore() != null) {
            return Result.fail("已经评价过了");
        }
        if (order.getHelperId().equals(userId) && order.getHelperScore() != null) {
            return Result.fail("已经评价过了");
        }

        // 4. 同步落库 + 结算信用分（原：投递 RocketMQ，由 ReviewConsumer 消费）
        return reviewSettleService.review(orderId, userId, score, comment);
    }

    @Override
    public Result getOrderById(Long orderId) {
        Long userId = StpUtil.getLoginIdAsLong();
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
            detail.put("helpAddressId", help.getAddressId());
            detail.put("helpAddressDetail", help.getAddressDetail());
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
    public Result getMyOrders(String role, Integer page, Integer size) {
        Long userId = StpUtil.getLoginIdAsLong();
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
