package com.linlibang.cache;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.linlibang.utils.RedisUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 三级缓存组件：L1 Caffeine 本地缓存 → L2 Redis → L3 数据库（调用方通过 dbLoader 提供）
 *
 * 查询链路：L1（纯内存，免网络 IO）→ L2（跨实例共享）→ L3（唯一数据源，逐级回源回填）。
 *
 * Redis 三大缓存问题的应对：
 *
 * 1. 缓存穿透（查询 DB 中根本不存在的数据，缓存永不命中 → 每次都打库）
 *    —— 空值缓存：DB 查询确认不存在时，写入空值哨兵（短 TTL 1 分钟），
 *       期间所有请求在 L1/L2 就被拦截，不再穿透到 DB；
 *
 * 2. 缓存击穿（热点 key 过期瞬间，高并发同时回源 DB）
 *    —— 互斥锁（SETNX）：同一 key 同一时刻只有一个线程查 DB 回填，
 *       其余线程挂起等待（CompletableFuture，不空耗 CPU），
 *       本实例持锁线程回填完成后立即唤醒；等待超时则直接查 DB 兜底（可用性优先，不阻塞请求）；
 *
 * 3. 缓存雪崩（大量 key 同时过期，DB 瞬间承压）
 *    —— TTL 随机抖动：写入 L2 时在基础 TTL 上叠加 0~20% 随机增量，
 *       打散批量写入（如缓存预热）的集中过期时间。
 *
 * L1/L2 一致性（有界不一致——本地缓存在各 JVM 内存里，无法与 Redis 同事务，做不到强一致）：
 *   1. 写路径（put/evict）同步失效本实例 L1 + L2；
 *   2. 同时经 Redis Pub/Sub 广播失效消息，其他实例收到后清各自 L1（多实例部署）；
 *   3. 1 秒后"延迟二次失效"：清 L1 + 补一次广播（evict 场景再删一次 L2），
 *      覆盖"删除前已读到旧值、还在途未回填"的竞态请求；
 *   4. L1 30 秒短 TTL 最终兜底：Pub/Sub 丢消息/广播失败时，脏数据窗口 ≤ 30 秒。
 */
@Slf4j
@Component
public class MultiLevelCache {

    // ==================== 参数（量级与项目原两级缓存保持一致） ====================

    /** L1 本地缓存 TTL：30 秒（本地过期后回源 L2，脏数据窗口上限） */
    private static final long L1_TTL_SECONDS = 30;
    /** L1 最大条目数：1 万 */
    private static final long L1_MAX_ENTRIES = 10_000;
    /** 空值缓存 TTL：1 分钟（期间重复查不存在的 id 不再打库，过期后再次确认） */
    private static final long NULL_TTL_SECONDS = 60;
    /** 互斥锁 TTL：10 秒（防止持锁线程崩溃导致死锁，超时自动释放） */
    private static final long LOCK_TTL_SECONDS = 10;
    /** 未抢到锁的挂起等待时长：持锁线程回填完成后唤醒（本实例），超时查库兜底 */
    private static final long FILL_WAIT_MS = 1000;
    /** 互斥锁 Key 前缀 */
    private static final String LOCK_KEY_PREFIX = "lock:cache:";
    /** L1 失效广播频道（Redis Pub/Sub）：写侧变更后通知所有实例清各自本地缓存 */
    public static final String INVALIDATE_CHANNEL = "linlibang:cache:invalidate";
    /** 延迟二次失效间隔（毫秒）：覆盖"广播已到、但变更前的旧值仍在读取在途并回填 L1"的竞态 */
    private static final long SECOND_INVALIDATE_DELAY_MS = 1000;
    /**
     * 空值哨兵：用空串标记"DB 确认不存在"。
     * 契约：本组件缓存的值都是对象的 JSON 序列化，不可能为空串，可安全区分。
     */
    private static final String NULL_MARKER = "";

    /** L1 本地缓存 */
    private final Cache<String, String> l1 = Caffeine.newBuilder()
            .maximumSize(L1_MAX_ENTRIES)
            .expireAfterWrite(Duration.ofSeconds(L1_TTL_SECONDS))
            .build();

    /** 回填等待队列：key → 唤醒信号；未抢到锁的线程挂起在此，本实例持锁线程回填完成后唤醒全部等待者 */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> fillWaiters = new ConcurrentHashMap<>();

    @Resource
    private RedisUtils redisUtils;

    /** Pub/Sub 发布用（广播 L1 失效消息） */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 延迟二次失效调度器（单线程守护线程，应用关停时静默丢弃，靠 TTL 兜底） */
    private final ScheduledExecutorService secondInvalidator = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cache-l1-second-invalidate");
        t.setDaemon(true);
        return t;
    });

    // ==================== 读：三级查询 ====================

    /**
     * 三级读：L1 → L2 → L3（带互斥锁），未命中逐级回源。
     *
     * @param key      缓存键
     * @param clazz    值类型（内部以 JSON 字符串存储于 L1/L2）
     * @param ttl      L2 基础过期时间（实际写入时叠加 0~20% 随机抖动防雪崩）
     * @param unit     过期时间单位
     * @param dbLoader L3 回源函数：入参为缓存键，返回查库结果（null 表示 DB 中不存在）
     * @return 缓存/DB 中的对象；DB 中不存在时返回 null
     */
    public <T> T get(String key, Class<T> clazz, long ttl, TimeUnit unit,
                     Function<String, T> dbLoader) {
        // ① L1 → L2（命中则直接返回，空值哨兵也在此拦截）
        String json = readThrough(key);
        if (json != null) {
            return fromJson(json, clazz);
        }

        // ② 未命中 → 抢互斥锁，保证同一 key 只有一个线程回源 DB（防击穿）
        String lockKey = LOCK_KEY_PREFIX + key;
        String lockValue = UUID.randomUUID().toString();
        if (redisUtils.setIfAbsent(lockKey, lockValue, LOCK_TTL_SECONDS, TimeUnit.SECONDS)) {
            try {
                // 双重检查：抢锁期间可能已有其他线程完成回填
                json = readThrough(key);
                if (json != null) {
                    return fromJson(json, clazz);
                }
                // ③ L3 数据库回源
                T data = dbLoader.apply(key);
                if (data == null) {
                    // 防穿透：缓存空值哨兵（短 TTL），期间请求不再打库
                    redisUtils.set(key, NULL_MARKER, NULL_TTL_SECONDS, TimeUnit.SECONDS);
                    l1.put(key, NULL_MARKER);
                    notifyWaiters(key, null);
                    return null;
                }
                put(key, data, ttl, unit);
                notifyWaiters(key, null);
                return data;
            } catch (RuntimeException e) {
                // 回源失败：唤醒等待线程走查库兜底，异常继续上抛给本次请求
                notifyWaiters(key, e);
                throw e;
            } finally {
                redisUtils.releaseLock(lockKey, lockValue);
            }
        }

        // ④ 未抢到锁：注册等待，挂起在 CompletableFuture 上（不空耗 CPU），
        //    本实例持锁线程回填完成后唤醒；跨实例持锁则等超时后兜底
        CompletableFuture<Void> waiter = fillWaiters.computeIfAbsent(key, k -> new CompletableFuture<>());
        // 注册后 double-check：持锁线程可能刚好在注册前完成回填（此时无人唤醒这个新 future）
        json = readThrough(key);
        if (json != null) {
            fillWaiters.remove(key, waiter);
            return fromJson(json, clazz);
        }
        try {
            waiter.get(FILL_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            // 持锁线程回源失败：直接走查库兜底
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            // 超时：其他实例持锁回填中（本地无人能唤醒）或回填极慢
        } finally {
            fillWaiters.remove(key, waiter);
        }
        // 被唤醒/超时后重读缓存：命中返回，仍未命中查库兜底（不回写，避免与持锁线程竞争写）
        json = readThrough(key);
        if (json != null) {
            return fromJson(json, clazz);
        }
        log.warn("缓存回填等待超时，直接查库兜底: key={}", key);
        return dbLoader.apply(key);
    }

    /**
     * 只读缓存（L1 → L2），未命中返回 null，不触发回源。
     * 用于调用方自己做批量 DB 回填的场景（如 GEO 搜索按 ID 批量取）。
     */
    public <T> T getIfPresent(String key, Class<T> clazz) {
        String json = readThrough(key);
        return json != null ? fromJson(json, clazz) : null;
    }

    // ==================== 写 / 失效 ====================

    /**
     * 写缓存（L2 + 回填 L1），用于预热、更新后刷新。
     * L2 的 TTL 叠加 0~20% 随机抖动，防止批量写入的 key 同时过期（防雪崩）。
     */
    public void put(String key, Object value, long ttl, TimeUnit unit) {
        if (value == null) {
            redisUtils.set(key, NULL_MARKER, NULL_TTL_SECONDS, TimeUnit.SECONDS);
            l1.put(key, NULL_MARKER);
            return;
        }
        String json = JSONUtil.toJsonStr(value);
        long ttlSeconds = unit.toSeconds(ttl);
        // 雪崩防护：基础 TTL + [0, 20%) 随机抖动
        long jitterBound = ttlSeconds / 5 + 1;
        long finalTtl = ttlSeconds + ThreadLocalRandom.current().nextLong(jitterBound);
        redisUtils.set(key, json, finalTtl, TimeUnit.SECONDS);
        l1.put(key, json);
        // 通知其他实例丢弃旧 L1；延迟二次失效清掉在途请求回填的旧值（L2 保留新值）
        broadcastInvalidate(key);
        scheduleSecondInvalidate(key, false);
    }

    /**
     * 删除缓存（L2 + L1 同步失效 + 广播其他实例失效 L1），用于数据变更后的 Cache-Aside 删除。
     * 1 秒后延迟二次失效：再删一次 L2 并补一次广播，防止"删除后、在途回源请求把旧值写回 L2"。
     */
    public void evict(String key) {
        redisUtils.delete(key);
        l1.invalidate(key);
        broadcastInvalidate(key);
        scheduleSecondInvalidate(key, true);
    }

    // ==================== L1 一致性（多实例广播失效） ====================

    /** 广播 L1 失效：Pub/Sub 即发即弃，失败只告警不影响主流程，各实例靠 30 秒 TTL 自然收敛 */
    private void broadcastInvalidate(String key) {
        try {
            stringRedisTemplate.convertAndSend(INVALIDATE_CHANNEL, key);
        } catch (Exception e) {
            log.warn("L1 失效广播失败（靠 TTL 兜底）: key={}", key, e);
        }
    }

    /**
     * 延迟二次失效（"延迟双删"的本地调度版）：
     * 首次删除/广播只能挡住"之后的新请求"，挡不住"删除前已读到旧值、还没写回 L1 的在途请求"。
     * 1 秒后再清一次 L1 并补一次广播；evict 场景同时再删一次 L2，
     * 防止在途回源把旧值写回 Redis。
     *
     * @param alsoDeleteL2 true = evict 场景（L2 已删，再删一次防回写旧值）；false = put 场景（L2 是新值，不能删）
     */
    private void scheduleSecondInvalidate(String key, boolean alsoDeleteL2) {
        try {
            secondInvalidator.schedule(() -> {
                if (alsoDeleteL2) {
                    redisUtils.delete(key);
                }
                l1.invalidate(key);
                broadcastInvalidate(key);
            }, SECOND_INVALIDATE_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // 应用关停中，交给 TTL 兜底
        }
    }

    /**
     * 收到失效广播（含自己发的，幂等无害）：只清本实例 L1，不动 L2（L2 由发起方处理）。
     * 由 CacheInvalidateConfig 的订阅容器回调。
     */
    public void onInvalidateBroadcast(String key) {
        l1.invalidate(key);
    }

    // ==================== 内部方法 ====================

    /**
     * 唤醒本实例所有等待该 key 回填的线程（由持锁线程在回填完成后调用）。
     * 多个等待线程共用同一个 future，complete 一次全部唤醒。
     *
     * @param error null = 回填成功（正常唤醒）；非 null = 回源失败（等待者走查库兜底）
     */
    private void notifyWaiters(String key, Exception error) {
        CompletableFuture<Void> waiter = fillWaiters.remove(key);
        if (waiter == null) {
            return;
        }
        if (error != null) {
            waiter.completeExceptionally(error);
        } else {
            waiter.complete(null);
        }
    }

    /** L1 → L2 逐级读，命中 L2 时回填 L1 */
    private String readThrough(String key) {
        String json = l1.getIfPresent(key);
        if (json != null) {
            return json;
        }
        json = redisUtils.get(key);
        if (json != null) {
            l1.put(key, json);
        }
        return json;
    }

    /** 反序列化：空值哨兵 → null */
    private <T> T fromJson(String json, Class<T> clazz) {
        if (NULL_MARKER.equals(json)) {
            return null;
        }
        return JSONUtil.toBean(json, clazz);
    }
}
