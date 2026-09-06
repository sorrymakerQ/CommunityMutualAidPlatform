package com.linlibang.service.impl;

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
import com.linlibang.utils.RedisUtils;
import com.linlibang.config.RocketMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;

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

    @Resource
    private PayService payService;

    /** 定位兜底：前端未提供坐标时使用（application.yml 里配置） */
    @Value("${linlibang.default-location.lng:116.397428}")
    private Double defaultLng;
    @Value("${linlibang.default-location.lat:39.90923}")
    private Double defaultLat;
    @Value("${linlibang.default-location.address:北京市东城区天安门广场}")
    private String defaultAddress;

    /** 单条求助缓存前缀 */
    private static final String HELP_ITEM_KEY = "help:item:";
    /** 分页列表缓存前缀 */
    private static final String HELP_PAGE_KEY = "help:page:";
    /** Redis GEO Key 前缀 */
    private static final String HELP_GEO_KEY = "help:location";
    /** 浏览次数 Key */
    private static final String HELP_VIEW_KEY = "help:views:";
    /** 缓存过期时间（分钟） */
    private static final long CACHE_TTL = 30;
    /** 默认搜索半径（公里） */
    private static final int DEFAULT_RADIUS = 5;

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

    /** 清除所有分页列表缓存（数据变更时调用，使用 SCAN 避免阻塞） */
    private void clearPageCache() {
        Set<String> keys = redisUtils.scanKeys(HELP_PAGE_KEY + "*");
        if (keys != null && !keys.isEmpty()) {
            redisUtils.delete(keys);
        }
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

    // ==================== 缓存查询（三级缓存） ====================

    /**
     * 三级缓存查询单条求助：L1 Caffeine → L2 Redis → L3 MySQL
     *
     * 三大缓存问题均由 MultiLevelCache 统一处理：
     *   1. 穿透：DB 确认不存在 → 空值缓存 1 分钟，重复请求不再打库；
     *   2. 击穿：热点 key 过期瞬间 SETNX 互斥锁，单线程回源，其余自旋等待；
     *   3. 雪崩：TTL 30 分钟 + 0~20% 随机抖动，打散集中过期。
     */
    private HelpRequest getHelpWithCache(Long id) {
        return multiLevelCache.get(
                HELP_ITEM_KEY + id, HelpRequest.class, CACHE_TTL, TimeUnit.MINUTES,
                key -> helpRequestMapper.selectById(
                        Long.valueOf(key.substring(HELP_ITEM_KEY.length()))));
    }

    // ==================== 业务方法 ====================

    @Override
    @Transactional
    public Result publishHelp(HelpRequestDTO dto, Long userId) {
        // 定位兜底：前端未提供有效坐标时用配置的默认值，保证入库和 GEO 都不为空
        // （前端可能因浏览器不支持 / 用户拒绝授权 / 定位超时导致 lng=lat=0 或 null）
        boolean lngInvalid = dto.getLng() == null || dto.getLng() == 0.0;
        boolean latInvalid = dto.getLat() == null || dto.getLat() == 0.0;
        if (lngInvalid || latInvalid) {
            dto.setLng(defaultLng);
            dto.setLat(defaultLat);
            if (dto.getAddress() == null || dto.getAddress().trim().isEmpty()) {
                dto.setAddress(defaultAddress);
            }
            log.warn("publishHelp: 用户 {} 未提供坐标，兜底为默认位置 ({}, {})",
                    userId, defaultLng, defaultLat);
        }

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
        help.setAddress(dto.getAddress());
        help.setLng(dto.getLng());
        help.setLat(dto.getLat());
        help.setUrgent(dto.getUrgent() != null ? dto.getUrgent() : 0);
        // 待支付：支付成功后才上首页（首页/搜索/附近只查 status=1）
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

        // 2. 生成支付订单（待支付，余额支付流程）
        PayOrder payOrder = new PayOrder();
        payOrder.setHelpId(help.getId());
        payOrder.setPublisherId(help.getUserId());
        payOrder.setAmount(totalReward);
        payOrderMapper.insert(payOrder);

        // 3. 注册事务回调：提交成功后才写 Redis/投递消息，回滚则跳过
        //    待支付求助只写详情缓存（发布者可查看确认），
        //    不写 GEO、不进分页缓存——支付成功后由支付服务上首页
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                setToCache(help);
                // 投递支付超时检查消息（延迟等级16 = 30 分钟，事务已提交，避免回滚后误投）
                try {
                    Message<Long> msg = MessageBuilder.withPayload(help.getId()).build();
                    rocketMQTemplate.syncSend(RocketMQConfig.PAY_TIMEOUT_TOPIC, msg,
                            3000, RocketMQConfig.DELAY_LEVEL_30M);
                } catch (Exception e) {
                    log.warn("支付超时消息投递失败，超时取消暂时失效: helpId={}", help.getId(), e);
                }
            }
        });

        return Result.ok("求助发布成功，请尽快支付", help.getId());
    }

    @Override
    public Result getNearbyHelp(Double lng, Double lat, Integer radius, Integer page, Integer size,
                                Long categoryId, String keyword) {
        int pageNum = page != null ? page : 1;
        int pageSize = size != null ? size : 10;

        // 没有位置 → 走分页/搜索
        if (lng == null || lat == null) {
            // 有搜索关键词 → 走搜索，不走缓存
            if (keyword != null && !keyword.trim().isEmpty()) {
                return searchHelp(keyword, categoryId, page, size);
            }

            // ① 查分页缓存（Key 后缀区分分类）
            String cacheSuffix = (categoryId != null ? ":c" + categoryId : "") + ":s" + pageSize;
            String pageKey = HELP_PAGE_KEY + pageNum + cacheSuffix;
            String cachedJson = redisUtils.get(pageKey);
            if (cachedJson != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cachedResult = JSONUtil.toBean(cachedJson, Map.class);
                return Result.ok(cachedResult);
            }

            // ② 未命中 → 查 MySQL（带分类筛选）
            List<HelpRequest> list = helpRequestMapper.selectPage((pageNum - 1) * pageSize, pageSize, categoryId);
            Long total = helpRequestMapper.selectCount(categoryId);

            // ③批量查发布者 + 分类（避免 N+1），组装成前端需要的富对象
            List<Map<String, Object>> enriched = enrichHelpList(list, null);

            // ④回写 Redis 分页缓存（5分钟 + 随机0~60秒，避免大量缓存同时过期引发雪崩）
            Map<String, Object> result = new HashMap<>();
            result.put("list", enriched);
            result.put("total", total);
            long pageTtlSeconds = 300 + (long) (Math.random() * 60);
            redisUtils.set(pageKey, JSONUtil.toJsonStr(result), pageTtlSeconds, TimeUnit.SECONDS);
            // ⑤同时把每条求助也缓存
            for (HelpRequest h : list) {
                setToCache(h);
            }

            return Result.ok(result);
        }

        // ① GEO 搜索附近 ID
        int searchRadius = radius != null ? radius : DEFAULT_RADIUS;
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoResults =
                redisUtils.geoSearch(HELP_GEO_KEY, lng, lat, searchRadius);

        if (geoResults.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("list", Collections.emptyList());
            result.put("total", 0);
            return Result.ok("附近暂无求助", result);
        }

        // ② 收集 ID + 距离
        List<Long> helpIds = geoResults.stream()
                .map(r -> Long.valueOf(r.getContent().getName()))
                .collect(Collectors.toList());

        Map<Long, Double> distanceMap = geoResults.stream()
                .collect(Collectors.toMap(
                        r -> Long.valueOf(r.getContent().getName()),
                        r -> r.getDistance().getValue() * 1000));

        // ③ 分化：从 Redis 命中的 + 未命中的
        List<HelpRequest> cachedList = new ArrayList<>();
        List<Long> missedIds = new ArrayList<>();

        for (Long id : helpIds) {
            HelpRequest cached = getFromCache(id);
            if (cached != null) {
                cachedList.add(cached);
            } else {
                missedIds.add(id);
            }
        }

        // ④ 未命中 → 查 MySQL → 回写 Redis
        List<HelpRequest> dbList = Collections.emptyList();
        if (!missedIds.isEmpty()) {
            dbList = helpRequestMapper.selectByIdsAndStatus(missedIds, 1);
            for (HelpRequest h : dbList) {
                setToCache(h);  // 回写缓存，下次直接命中
            }
        }

        // ⑤ 合并（Redis 命中 + DB 回源），过滤 status=1
        List<HelpRequest> allList = new ArrayList<>(cachedList);
        allList.addAll(dbList);
        allList.removeIf(h -> h.getStatus() != 1);

        // ⑤½ 关键词过滤（GEO 搜索不支持全文检索，在内存中过滤）
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim().toLowerCase();
            allList.removeIf(h ->
                !(h.getTitle() != null && h.getTitle().toLowerCase().contains(kw)) &&
                !(h.getDescription() != null && h.getDescription().toLowerCase().contains(kw))
            );
        }

        // ⑥ 批量查询发布者和分类 + 组装成前端富对象
        List<Map<String, Object>> resultList = enrichHelpList(allList, distanceMap);

        // ⑦ 按距离排序 + 分页
        resultList.sort(Comparator.comparingDouble(m -> (Double) m.get("distance")));

        int total = resultList.size();
        int from = (pageNum - 1) * pageSize;
        int to = Math.min(from + pageSize, total);
        List<Map<String, Object>> paged = from < total
                ? resultList.subList(from, to) : Collections.emptyList();

        Map<String, Object> result = new HashMap<>();
        result.put("list", paged);
        result.put("total", total);
        return Result.ok(result);
    }

    /**
     * 将 List&lt;HelpRequest&gt; 组装成前端需要的富对象列表。
     * 一次性批量查询发布者和分类（避免 N+1），把 publisherName / publisherAvatar
     * / categoryName / categoryIcon / distance 全都拼上去。
     *
     * @param helps       原始求助列表
     * @param distanceMap 距离表（附近搜索场景才有；null 表示无距离信息）
     */
    private List<Map<String, Object>> enrichHelpList(List<HelpRequest> helps,
                                                     Map<Long, Double> distanceMap) {
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
            item.put("address", help.getAddress());
            item.put("lng", help.getLng());
            item.put("lat", help.getLat());
            item.put("urgent", help.getUrgent());
            item.put("status", help.getStatus());
            item.put("helperNum", help.getHelperNum());
            item.put("acceptedNum", help.getAcceptedNum());
            item.put("createTime", help.getCreateTime());
            item.put("distance", distanceMap != null
                    ? distanceMap.getOrDefault(help.getId(), 0.0)
                    : 0.0);

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

        // ③ Redis 原子自增浏览次数 + 异步刷回 MySQL
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
        detail.put("address", help.getAddress());
        detail.put("lng", help.getLng());
        detail.put("lat", help.getLat());
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
    public Result cancelHelp(Long helpId, Long userId) {
        HelpRequest help = helpRequestMapper.selectById(helpId);
        if (help == null) return Result.fail("求助不存在");
        if (!help.getUserId().equals(userId)) return Result.fail("只能取消自己发布的求助");
        // 待支付(0)或招募中(1)可取消（已支付招募中的取消需原路退款）
        if (help.getStatus() != 0 && help.getStatus() != 1) return Result.fail("当前状态不允许取消");
        int originStatus = help.getStatus();

        // 防白嫖：招募中但已有接单者正在服务（已接单/进行中订单）时禁止取消。
        // 多人求助只要还有空缺名额 status 保持 1，不校验的话发布者可
        // 让邻居干完活再取消求助全额退款，接单者的服务无法结算。
        Long activeOrders = orderMapper.countActiveByHelpId(helpId);
        if (activeOrders != null && activeOrders > 0) {
            return Result.fail("已有邻居正在服务中，请先处理相关订单（完成或取消）再取消求助");
        }

        if (originStatus == 0) {
            // 未支付：支付订单一并作废（无款可退）
            payOrderMapper.updateStatusIf(helpId, 0, 2);
        } else {
            // 已支付：原路退款（refundOrder 与求助取消同一事务——
            // 退款失败则抛异常全套回滚，杜绝"求助取消了钱没退"）
            Result refund = payService.refundOrder(helpId, userId);
            if (refund == null || !refund.getSuccess()) {
                throw new IllegalStateException(refund != null ? refund.getMessage() : "退款失败，取消操作已回滚");
            }
        }

        help.setStatus(4);
        helpRequestMapper.updateById(help);

        // 更新 Redis 缓存（而非删除，保留数据）
        setToCache(help);
        redisUtils.geoRemove(HELP_GEO_KEY, helpId.toString());
        clearPageCache();

        return Result.ok(originStatus == 0 ? "求助已取消" : "求助已取消，款项已退回余额");
    }

    @Override
    public Result getMyHelp(Long userId, Integer page, Integer size) {
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

    /**
     * 定时任务：每5分钟扫描所有浏览计数 Key，批量同步到 MySQL
     * 兜底策略：防止异步写库丢失
     */
    @Scheduled(fixedRate = 300000)
    public void batchSyncViews() {
        // 使用 SCAN 扫描 Redis 中所有 help:views:* 的 Key（非阻塞）
        Set<String> keys = redisUtils.scanKeys(HELP_VIEW_KEY + "*");
        if (keys == null || keys.isEmpty()) return;

        int count = 0;
        for (String key : keys) {
            try {
                String idStr = key.substring(HELP_VIEW_KEY.length());
                Long helpId = Long.valueOf(idStr);
                String val = redisUtils.get(key);
                if (val != null) {
                    int viewCount = Integer.parseInt(val);
                    HelpRequest h = new HelpRequest();
                    h.setId(helpId);
                    h.setViewCount(viewCount);
                    helpRequestMapper.updateById(h);
                    count++;
                }
            } catch (Exception e) {
                log.error("批量刷浏览量失败: key={}", key, e);
            }
        }
        if (count > 0) {
            log.info("定时批量刷浏览量完成: {} 条", count);
        }
    }

    // ==================== Redis ↔ MySQL 定时对账 ====================

    /**
     * 每 10 分钟执行一次 Redis 与 MySQL 对账
     *
     * 正向同步（MySQL → Redis）：确保所有待接单求助的缓存和 GEO 位置存在
     * 反向清理（Redis → MySQL）：删除 MySQL 中已不存在或已取消的过期缓存
     *
     * 设计原则：MySQL 是唯一数据源，Redis 是缓存加速层。
     * 对账任务作为兜底，修复 afterCommit 删缓存丢失等异常场景。
     */
    @Scheduled(fixedRate = 600000)  // 10 分钟
    public void reconcileCache() {
        log.info("========== 开始对账 Redis ↔ MySQL ==========");
        int cacheFilled = 0;
        int geoFilled = 0;
        int cacheCleaned = 0;
        int geoCleaned = 0;

        // ==================== 正向同步 ====================

        // ① 缓存对账：遍历 MySQL 中所有待接单的求助，确保 Redis 缓存存在
        Set<String> cachedKeys = redisUtils.scanKeys(HELP_ITEM_KEY + "*");
        Set<Long> cachedIds = Collections.emptySet();
        if (cachedKeys != null) {
            cachedIds = cachedKeys.stream()
                    .map(k -> Long.valueOf(k.substring(HELP_ITEM_KEY.length())))
                    .collect(Collectors.toSet());
        }

        // 分页查询 MySQL 中所有 status=1（待接单）的求助
        int batchSize = 200;
        int offset = 0;
        while (true) {
            List<HelpRequest> batch = helpRequestMapper.selectPage(offset, batchSize, null);
            if (batch.isEmpty()) break;

            for (HelpRequest h : batch) {
                // 只检查待接单的求助（已完成/已取消的不强制缓存）
                if (h.getStatus() == null || h.getStatus() != 1) continue;

                // 缓存缺失 → 补写
                if (!cachedIds.contains(h.getId())) {
                    setToCache(h);
                    cacheFilled++;
                }

                // GEO 缺失 → 补写（用 GEOPOS 检查存在性，避免并发重复写）
                if (!redisUtils.geoExists(HELP_GEO_KEY, h.getId().toString())
                        && h.getLng() != null && h.getLat() != null) {
                    redisUtils.geoAdd(HELP_GEO_KEY, h.getLng(), h.getLat(), h.getId().toString());
                    geoFilled++;
                }
            }
            offset += batchSize;
        }

        // ==================== 反向清理 ====================

        // ② 缓存清理：Redis 中有但 MySQL 中已删除/不存在的 key → 删除
        if (cachedKeys != null && !cachedKeys.isEmpty()) {
            List<Long> idsToCheck = new ArrayList<>(cachedIds);
            // 分批查 MySQL
            for (int i = 0; i < idsToCheck.size(); i += 500) {
                int to = Math.min(i + 500, idsToCheck.size());
                List<Long> subIds = idsToCheck.subList(i, to);
                List<HelpRequest> existing = helpRequestMapper.selectByIds(subIds);
                Set<Long> existingIds = existing.stream()
                        .map(HelpRequest::getId).collect(Collectors.toSet());

                for (Long id : subIds) {
                    if (!existingIds.contains(id)) {
                        delCache(id);
                        cacheCleaned++;
                    }
                }
            }
        }

        // ③ GEO 清理：GEO 中但 MySQL 不存在或状态不是待接单的 → 删除
        Set<String> geoMembersRaw = redisUtils.zSetRange(HELP_GEO_KEY, 0, -1);
        if (geoMembersRaw != null && !geoMembersRaw.isEmpty()) {
            List<Long> geoIds = geoMembersRaw.stream()
                    .map(Long::valueOf).collect(Collectors.toList());

            for (int i = 0; i < geoIds.size(); i += 500) {
                int to = Math.min(i + 500, geoIds.size());
                List<Long> subIds = geoIds.subList(i, to);
                List<HelpRequest> existing = helpRequestMapper.selectByIds(subIds);
                // GEO 里只保留 status=1（待接单）的求助
                Set<Long> validIds = existing.stream()
                        .filter(h -> h.getStatus() != null && h.getStatus() == 1)
                        .map(HelpRequest::getId).collect(Collectors.toSet());

                for (Long id : subIds) {
                    if (!validIds.contains(id)) {
                        redisUtils.geoRemove(HELP_GEO_KEY, id.toString());
                        geoCleaned++;
                    }
                }
            }
        }

        log.info("对账完成：缓存补写{} | GEO补写{} | 缓存清理{} | GEO清理{}",
                cacheFilled, geoFilled, cacheCleaned, geoCleaned);
    }

    @Override
    public Result searchHelp(String keyword, Long categoryId, Integer page, Integer size) {
        int pageNum = page != null ? page : 1;
        int pageSize = size != null ? size : 10;
        int offset = (pageNum - 1) * pageSize;

        List<HelpRequest> list = helpRequestMapper.search(keyword, categoryId, offset, pageSize);
        Long total = helpRequestMapper.searchCount(keyword, categoryId);

        // 批量查发布者 + 分类，返回富对象（与首页列表结构一致）
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", enrichHelpList(list, null));
        resultMap.put("total", total);
        return Result.ok(resultMap);
    }
}
