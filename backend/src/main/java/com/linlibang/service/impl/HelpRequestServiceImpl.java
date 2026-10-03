package com.linlibang.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONUtil;
import com.linlibang.entity.Order;
import com.linlibang.entity.PayOrder;
import com.linlibang.mapper.CategoryMapper;
import com.linlibang.mapper.HelpRequestMapper;
import com.linlibang.mapper.PayOrderMapper;
import com.linlibang.mapper.UserMapper;
import com.linlibang.dto.HelpRequestDTO;
import com.linlibang.dto.Result;
import com.linlibang.entity.Category;
import com.linlibang.entity.HelpRequest;
import com.linlibang.entity.User;
import com.linlibang.service.HelpRequestService;
import com.linlibang.service.PayService;
import com.linlibang.cache.MultiLevelCache;
import com.linlibang.entity.UserCredit;
import com.linlibang.mapper.UserCreditMapper;
import com.linlibang.utils.OrderNoUtils;
import com.linlibang.utils.RedisUtils;
import com.linlibang.config.RocketMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 求助服务实现类
 *
 * Redis 缓存策略：
 *   1. 每条求助独立缓存：help:item:{id}，TTL 30分钟
 *   2. 首页列表先查 Redis → 未命中才查 MySQL → 回写 Redis
 *   3. 浏览次数 Redis 计数 → 定时异步刷回 MySQL
 */
@Slf4j
@Service
public class HelpRequestServiceImpl implements HelpRequestService {

    @Resource
    private HelpRequestMapper helpRequestMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private UserCreditMapper userCreditMapper;

    @Resource
    private CategoryMapper categoryMapper;

    @Resource
    private com.linlibang.mapper.OrderMapper orderMapper;

    @Resource
    private RedisUtils redisUtils;

    @Resource
    private MultiLevelCache multiLevelCache;

    @Resource
    private PayOrderMapper payOrderMapper;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    /**
     * 支付超时延迟等级：默认 16 = 30 分钟（RocketMQ 内置等级，表见 RocketMQConfig）。
     * 本地联调想快速验证「超时自动作废」，把 application.yml 的
     * rocketmq.pay-timeout.delay-level 改成 3（= 10 秒）即可，不用改代码。
     */
    @Value("${rocketmq.pay-timeout.delay-level:" + RocketMQConfig.DELAY_LEVEL_30M + "}")
    private int payTimeoutDelayLevel;

    @Resource
    private PayService payService;

    /** 单条求助缓存前缀 */
    private static final String HELP_ITEM_KEY = "help:item:";
    /** 浏览次数 Key */
    private static final String HELP_VIEW_KEY = "help:views:";
    /** 缓存过期时间（分钟） */
    private static final long CACHE_TTL = 30;

    // ==================== 缓存读写 ====================

    /** 只读缓存（L1 → L2），未命中返回 null，不回源 */
    private HelpRequest getFromCache(Long id) {
        return multiLevelCache.getIfPresent(HELP_ITEM_KEY + id, HelpRequest.class);
    }

    /** 写一条求助到缓存（L2 带随机 TTL + 回填 L1） */
    private void setToCache(HelpRequest help) {
        multiLevelCache.put(HELP_ITEM_KEY + help.getId(), help, CACHE_TTL, TimeUnit.MINUTES);
    }

    /** 删一条缓存（L2 + L1 同步失效） */
    private void delCache(Long id) {
        multiLevelCache.evict(HELP_ITEM_KEY + id);
    }

    // ==================== 缓存预热 ====================

    /**
     * 应用启动完毕后异步预热缓存
     * 将首页热点数据提前加载到 Redis，用户第一次访问就是缓存命中
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void preloadCache() {
        try {
            List<HelpRequest> list = helpRequestMapper.selectPage(0, 50, null);
            for (HelpRequest h : list) {
                setToCache(h);
                // 同步浏览次数到 Redis，防止重启后浏览计数归零
                if (h.getViewCount() != null && h.getViewCount() > 0) {
                    redisUtils.set(HELP_VIEW_KEY + h.getId(), String.valueOf(h.getViewCount()));
                }
            }
            log.info("缓存预热完成：{} 条求助已加载到 Redis", list.size());
        } catch (Exception e) {
            log.error("缓存预热失败", e);
        }
    }

    // ==================== 数据库读写 ====================
    private HelpRequest getHelpWithCache(Long id) {
        return multiLevelCache.get(
                HELP_ITEM_KEY + id, HelpRequest.class, CACHE_TTL, TimeUnit.MINUTES,
                key -> helpRequestMapper.selectById(
                        Long.valueOf(key.substring(HELP_ITEM_KEY.length()))));
    }

    // ==================== 业务方法 ====================

    @Override
    @Transactional
    public Result publishHelp(HelpRequestDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        // 服务层兜底：负数酬劳会被当成"支付即加钱"，属高危刷钱漏洞，必须拦截
        if (dto.getReward() != null && dto.getReward().compareTo(BigDecimal.ZERO) < 0) {
            return Result.fail("酬劳金额不能为负数");
        }

        HelpRequest help = new HelpRequest();
        help.setUserId(userId);
        help.setCategoryId(dto.getCategoryId());
        help.setTitle(dto.getTitle());
        help.setDescription(dto.getDescription());
        if (dto.getImages() != null && !dto.getImages().isEmpty()) {
            help.setImages(String.join(",", dto.getImages()));
        }
        help.setReward(dto.getReward());
        help.setAddressId(dto.getAddressId());
        help.setAddressDetail(dto.getAddressDetail());
        help.setUrgent(dto.getUrgent() != null ? dto.getUrgent() : 0);
        // 待支付：支付成功后才上首页（首页/搜索只查 status=1）
        help.setStatus(0);
        // 需要人数（默认1人）
        int helperNum = dto.getHelperNum() != null ? dto.getHelperNum() : 1;
        help.setHelperNum(helperNum);
        help.setAcceptedNum(0);  // 初始无人接单
        // 支付总额 = 每人单价 × 需要人数（发布时计算存储）
        BigDecimal totalReward = (dto.getReward() != null ? dto.getReward() : BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(helperNum));
        help.setTotalReward(totalReward);
        help.setViewCount(0);

        // 1. 写入 MySQL（待支付状态）
        helpRequestMapper.insert(help);

        // 2. 生成支付订单（待支付，余额/支付宝共用同一张单）
        PayOrder payOrder = new PayOrder();
        // 商户支付单号：对外唯一标识，作支付宝 out_trade_no；
        // 支付回调按它反查支付单（不能用 tb_order.order_no —— 支付阶段那张表还没有行）
        payOrder.setPayNo(OrderNoUtils.generate());
        payOrder.setHelpId(help.getId());
        payOrder.setPublisherId(help.getUserId());
        payOrder.setAmount(totalReward);
        // channel 留空：渠道在支付成功那一刻才确定（余额=0 / 支付宝=1）
        payOrderMapper.insert(payOrder);

        // 3. 注册事务回调：提交成功后才写 Redis/投递消息，回滚则跳过
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                setToCache(help);
                // 投递支付超时检查消息（延迟等级见 payTimeoutDelayLevel，生产 = 30 分钟；
                // 事务已提交，避免回滚后误投）
                try {
                    Message<PayOrder> msg = MessageBuilder.withPayload(payOrder).build();
                    rocketMQTemplate.syncSend(RocketMQConfig.PAY_TIMEOUT_TOPIC, msg,
                            3000, payTimeoutDelayLevel);
                } catch (Exception e) {
                    log.warn("支付超时消息投递失败，超时取消暂时失效: helpId={}", help.getId(), e);
                }
            }
        });

        return Result.ok("求助发布成功，请尽快支付", help.getId());
    }

    @Override
    public Result getHelpList(Integer page, Integer size, Long categoryId, String keyword) {
        int pageNum = page != null ? page : 1;
        int pageSize = size != null ? size : 10;

        log.info("查询求助列表：page={}, size={}, categoryId={}, keyword={}", page, size, categoryId, keyword);
        // 有搜索关键词 → 走搜索，不走缓存
        if (keyword != null && !keyword.trim().isEmpty()) {
            return searchHelp(keyword, categoryId, page, size);
        }

        // 查 MySQL（带分类筛选）
        List<HelpRequest> list = helpRequestMapper.selectPage((pageNum - 1) * pageSize, pageSize, categoryId);
        Long total = helpRequestMapper.selectCount(categoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("list", enrichHelpList(list));
        result.put("total", total);
        // 单条求助进缓存（分页列表缓存已移除：原实现只写不读，纯占 Redis 空间）
        for (HelpRequest h : list) {
            setToCache(h);
        }
        log.info(result.toString());

        return Result.ok(result);
    }

    /**
     * 批量补查发布者/分类/地址，优化了n+1问题
     *
     * @param helps 原始求助列表
     */
    private List<Map<String, Object>> enrichHelpList(List<HelpRequest> helps) {
        if (helps == null || helps.isEmpty()) {
            return new ArrayList<>();
        }

        // 批量查询发布者和分类（空集合守卫防 SQL 语法错误）
        List<Long> userIds = helps.stream()
                .map(HelpRequest::getUserId).distinct().collect(Collectors.toList());
        List<Long> categoryIds = helps.stream()
                .map(HelpRequest::getCategoryId).distinct().collect(Collectors.toList());

        Map<Long, User> userMap = userIds.isEmpty() ? Collections.emptyMap()
                : userMapper.selectByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        // 信用分已拆分到 tb_user_credit，批量补查（一次 IN，避免 N+1）
        Map<Long, Integer> creditMap = userIds.isEmpty() ? Collections.emptyMap()
                : userCreditMapper.selectByUserIds(userIds).stream()
                        .collect(Collectors.toMap(UserCredit::getUserId, UserCredit::getCredit, (a, b) -> a));
        Map<Long, Category> categoryMap = categoryIds.isEmpty() ? Collections.emptyMap()
                : categoryMapper.selectByIds(categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, c -> c, (a, b) -> a));
        List<Map<String, Object>> resultList = new ArrayList<>(helps.size());
        for (HelpRequest help : helps) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", help.getId());
            item.put("userId", help.getUserId());
            item.put("categoryId", help.getCategoryId());
            item.put("title", help.getTitle());
            item.put("description", help.getDescription());
            item.put("images", help.getImages());
            item.put("reward", help.getReward());
            item.put("totalReward", help.getTotalReward());
            item.put("addressId", help.getAddressId());
            item.put("addressDetail", help.getAddressDetail());
            item.put("urgent", help.getUrgent());
            item.put("status", help.getStatus());
            item.put("helperNum", help.getHelperNum());
            item.put("acceptedNum", help.getAcceptedNum());
            item.put("createTime", help.getCreateTime());

            User publisher = userMap.get(help.getUserId());
            if (publisher != null) {
                item.put("publisherName", publisher.getNickname());
                item.put("publisherAvatar", publisher.getAvatar());
                item.put("publisherCredit", creditMap.getOrDefault(help.getUserId(), 100));
            }

            Category category = categoryMap.get(help.getCategoryId());
            if (category != null) {
                item.put("categoryName", category.getName());
                item.put("categoryIcon", category.getIcon());
            }

            resultList.add(item);
        }
        return resultList;
    }

    @Override
    public Result getHelpById(Long helpId) {
        // ① 查缓存，未命中直接查 DB 并回写（无锁）
        HelpRequest help = getHelpWithCache(helpId);
        if (help == null) {
            return Result.fail("求助不存在或已删除");
        }

        Long newViewCount = redisUtils.increment(HELP_VIEW_KEY + helpId);
        help.setViewCount(newViewCount.intValue());
        setToCache(help);
        syncViewToDb(helpId, newViewCount.intValue());

        // ⑤ 组装详情
        Map<String, Object> detail = new HashMap<>();
        detail.put("id", help.getId());
        detail.put("userId", help.getUserId());
        detail.put("title", help.getTitle());
        detail.put("description", help.getDescription());
        detail.put("images", help.getImages() != null
                ? Arrays.asList(help.getImages().split(","))
                : Collections.emptyList());
        detail.put("reward", help.getReward());
        detail.put("totalReward", help.getTotalReward());
        detail.put("addressId", help.getAddressId());
        detail.put("addressDetail", help.getAddressDetail());
        detail.put("status", help.getStatus());
        detail.put("urgent", help.getUrgent());
        detail.put("helperNum", help.getHelperNum());
        detail.put("acceptedNum", help.getAcceptedNum());
        detail.put("viewCount", newViewCount);
        detail.put("createTime", help.getCreateTime());

        User publisher = userMapper.selectById(help.getUserId());
        if (publisher != null) {
            // 信用分已拆分到 tb_user_credit，单独补查
            UserCredit publisherCredit = userCreditMapper.selectByUserId(help.getUserId());
            detail.put("publisherName", publisher.getNickname());
            detail.put("publisherAvatar", publisher.getAvatar());
            detail.put("publisherCredit", publisherCredit != null ? publisherCredit.getCredit() : 100);
            detail.put("publisherHelpCount", publisher.getHelpCount());
        }

        Category category = categoryMapper.selectById(help.getCategoryId());
        if (category != null) {
            detail.put("categoryName", category.getName());
            detail.put("categoryIcon", category.getIcon());
        }

        // 查询当前接单人的信息（如果有活跃订单）
        Order activeOrder = orderMapper.selectActiveByHelpId(helpId);
        if (activeOrder != null) {
            detail.put("currentHelperId", activeOrder.getHelperId());
            detail.put("currentOrderId", activeOrder.getId());
        }

        return Result.ok(detail);
    }

    @Override
    @Transactional
    public Result cancelHelp(Long helpId) {
        Long userId = StpUtil.getLoginIdAsLong();
        HelpRequest help = helpRequestMapper.selectById(helpId);
        if (help == null) return Result.fail("求助不存在");
        if (!help.getUserId().equals(userId)) {
            return Result.fail("只能取消自己发布的求助");
        }
        // 待支付(0)或招募中(1)可取消（已支付招募中的取消需原路退款）
        if (help.getStatus() != 0 && help.getStatus() != 1) {
            return Result.fail("当前状态不允许取消");
        }
        int originStatus = help.getStatus();


        if (originStatus == 0) {
            // 未支付：支付订单一并作废（无款可退）
            payOrderMapper.updateStatus(helpId, 2);
        } else {
            // 已支付：原路退款（refundOrder 与求助取消同一事务——
            // 退款失败则抛异常全套回滚，杜绝"求助取消了钱没退"）
            Result refund = payService.refundOrder(helpId);
            if (refund == null || !refund.getSuccess()) {
                throw new IllegalStateException(refund != null ? refund.getMessage() : "退款失败，取消操作已回滚");
            }
        }
        help.setStatus(4);
        helpRequestMapper.updateById(help);

        // 更新 Redis 缓存（而非删除，保留数据）
        setToCache(help);

        return Result.ok(originStatus == 0 ? "求助已取消" : "求助已取消，款项已退回余额");
    }

    @Override
    public Result getMyHelp(Integer page, Integer size) {
        Long userId = StpUtil.getLoginIdAsLong();
        int pageNum = page != null ? page : 1;
        int pageSize = size != null ? size : 10;
        int offset = (pageNum - 1) * pageSize;

        List<HelpRequest> list = helpRequestMapper.selectByUserIdPaged(userId, offset, pageSize);
        Long total = helpRequestMapper.selectCountByUserId(userId);

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", list);
        resultMap.put("total", total);
        return Result.ok(resultMap);
    }

    // ==================== 异步刷浏览量 ====================

    /**
     * 异步将 Redis 中的浏览量刷回 MySQL
     */
    @Async
    public void syncViewToDb(Long helpId, int count) {
        try {
            HelpRequest h = new HelpRequest();
            h.setId(helpId);
            h.setViewCount(count);
            helpRequestMapper.updateById(h);
            log.debug("浏览量异步刷库: helpId={}, count={}", helpId, count);
        } catch (Exception e) {
            log.error("浏览量刷库失败: helpId={}", helpId, e);
        }
    }

    @Override
    public Result searchHelp(String keyword, Long categoryId, Integer page, Integer size) {
        int pageNum = page != null ? page : 1;
        int pageSize = size != null ? size : 10;
        int offset = (pageNum - 1) * pageSize;

        List<HelpRequest> list = helpRequestMapper.search(keyword, categoryId, offset, pageSize);
        Long total = helpRequestMapper.searchCount(keyword, categoryId);

        //
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", list);
        resultMap.put("total", total);
        return Result.ok(resultMap);
    }

    // ==================== 管理端操作（权限码 help:manage） ====================

    @Override
    public Result adminUpdateStatus(Long helpId, Integer status) {
        if (status == null || status < 1 || status > 4) {
            return Result.fail("状态非法（1招募中 2进行中 3已完成 4已取消/下架）");
        }
        HelpRequest help = helpRequestMapper.selectById(helpId);
        if (help == null) {
            return Result.fail("求助不存在");
        }
        // 管理员强制改状态：走动态更新（不做乐观锁校验，允许覆盖任意状态）
        HelpRequest update = new HelpRequest();
        update.setId(helpId);
        update.setStatus(status);
        helpRequestMapper.updateById(update);
        // 立即失效缓存，避免列表/详情继续返回旧状态
        evictHelpCache(helpId);
        return Result.ok(status == 4 ? "求助已下架" : "求助状态已更新");
    }

    @Override
    public void evictHelpCache(Long helpId) {
        delCache(helpId);
    }
}
