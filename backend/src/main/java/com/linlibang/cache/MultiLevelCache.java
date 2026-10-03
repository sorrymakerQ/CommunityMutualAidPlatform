package com.linlibang.cache;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.linlibang.utils.RedisUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 二级缓存：L1 Caffeine（进程内）→ L2 Redis（跨实例）→ L3 数据库
 *
 * 读：L1 → L2 → DB，逐级回源并回填；
 * 写：写 L2 并回填 L1；
 * 删：L1 与 L2 同步失效。
 *
 * 缓存三大问题的解决：
 *
 * 1. 缓存穿透（查 DB 中根本不存在的数据，缓存永不命中 → 每次都打库）
 *    —— 空值哨兵：DB 确认不存在时写入空串标记（短 TTL 60 秒），期间请求在 L1/L2 即被拦截。
 *       用空串是因为缓存值都是对象的 JSON 序列化结果，序列化一个对象不可能得到空串，
 *       所以空串可以安全地区分「没缓存」和「缓存了不存在」。
 *
 * 2. 缓存击穿（热点 key 过期瞬间，高并发同时回源 DB）
 *    —— 逻辑过期（不使用任何锁）：缓存值自带逻辑过期时间，物理 TTL 设为逻辑 TTL 的 2 倍。
 *       读到逻辑过期时，只放行一个线程同步回源刷新，其余线程直接返回旧值，
 *       既不阻塞、也不打库，避免热点 key 失效瞬间大量请求涌向 DB。
 *
 * 3. 缓存雪崩（大量 key 同时过期，DB 瞬间承压）
 *    —— TTL 随机抖动：写 L2 时在基础 TTL 上叠加 0~20% 随机增量，
 *       打散批量写入（如缓存预热）的集中过期时间。
 */
@Component
public class MultiLevelCache {

    /** L1 本地缓存 TTL（秒） */
    private static final long L1_TTL_SECONDS = 30;
    /** L1 最大条目数 */
    private static final long L1_MAX_ENTRIES = 10_000;
    /** 空值哨兵 TTL（秒）：期间重复查不存在的 id 不再打库 */
    private static final long NULL_TTL_SECONDS = 60;
    /** 物理 TTL 相对逻辑 TTL 的倍数：逻辑过期后仍留有旧值供降级返回 */
    private static final int PHYSICAL_TTL_FACTOR = 2;
    /** 空值哨兵标记：空串表示「DB 确认不存在」 */
    private static final String NULL_MARKER = "";

    /** L1 本地缓存 */
    private final Cache<String, String> l1 = Caffeine.newBuilder()
            .maximumSize(L1_MAX_ENTRIES)
            .expireAfterWrite(Duration.ofSeconds(L1_TTL_SECONDS))
            .build();

    /** 正在回源刷新的 key：保证逻辑过期后只有一个线程查库，其余直接返回旧值（防击穿） */
    private final ConcurrentHashMap<String, Boolean> refreshing = new ConcurrentHashMap<>();

    @Resource
    private RedisUtils redisUtils;

    /**
     * 读缓存：L1 → L2 → L3。
     *
     * @param key      缓存键
     * @param clazz    值类型（内部以 JSON 字符串存储于 L1/L2）
     * @param ttl      L2 逻辑过期时间（物理 TTL 为其 2 倍并叠加随机抖动）
     * @param unit     过期时间单位
     * @param dbLoader L3 回源函数：入参为缓存键，返回查库结果（null 表示 DB 中不存在）
     * @return 缓存或 DB 中的对象；DB 中确实不存在时返回 null
     */
    public <T> T get(String key, Class<T> clazz, long ttl, TimeUnit unit,
                     Function<String, T> dbLoader) {
        String json = readRaw(key);

        // 防穿透：命中空值哨兵，直接返回 null，不再打库
        if (NULL_MARKER.equals(json)) {
            return null;
        }

        if (json != null) {
            CachedValue cached = JSONUtil.toBean(json, CachedValue.class);
            if (cached.getExpireAt() == null || cached.getData() == null) {
                // 兼容改动前写入的旧格式缓存（直接存的对象 JSON）
                return JSONUtil.toBean(json, clazz);
            }
            if (System.currentTimeMillis() < cached.getExpireAt()) {
                return JSONUtil.toBean(cached.getData(), clazz);
            }
            // 逻辑过期：只放行一个线程回源刷新，其余线程直接返回旧值（防击穿）
            if (refreshing.putIfAbsent(key, Boolean.TRUE) == null) {
                try {
                    refresh(key, ttl, unit, dbLoader);
                } finally {
                    refreshing.remove(key);
                }
            }
            return JSONUtil.toBean(cached.getData(), clazz);
        }

        // 完全没有缓存（首次访问或物理已过期）→ 同步回源
        T data = dbLoader.apply(key);
        if (data == null) {
            writeNull(key);
            return null;
        }
        put(key, data, ttl, unit);
        return data;
    }

    /**
     * 只读缓存（L1 → L2），未命中返回 null，不触发回源。
     * 用于调用方自己做批量 DB 回填的场景。
     */
    public <T> T getIfPresent(String key, Class<T> clazz) {
        String json = readRaw(key);
        if (json == null || NULL_MARKER.equals(json)) {
            return null;
        }
        CachedValue cached = JSONUtil.toBean(json, CachedValue.class);
        if (cached.getExpireAt() == null || cached.getData() == null) {
            return JSONUtil.toBean(json, clazz);
        }
        return JSONUtil.toBean(cached.getData(), clazz);
    }

    /**
     * 写缓存：写 L2 并回填 L1。
     * 逻辑过期时间 = 当前时间 + ttl；
     * 物理 TTL = ttl × 2 再叠加 0~20% 随机增量（防雪崩）。
     */
    public void put(String key, Object value, long ttl, TimeUnit unit) {
        long logicalTtlMs = unit.toMillis(ttl);

        long physicalSeconds = unit.toSeconds(ttl) * PHYSICAL_TTL_FACTOR;
        long jitterBound = physicalSeconds / 5 + 1;
        physicalSeconds += ThreadLocalRandom.current().nextLong(jitterBound);

        CachedValue cached = new CachedValue();
        cached.setExpireAt(System.currentTimeMillis() + logicalTtlMs);
        cached.setData(JSONUtil.toJsonStr(value));

        String json = JSONUtil.toJsonStr(cached);
        redisUtils.set(key, json, physicalSeconds, TimeUnit.SECONDS);
        l1.put(key, json);
    }

    /** 删缓存：L1 与 L2 同步失效 */
    public void evict(String key) {
        redisUtils.delete(key);
        l1.invalidate(key);
    }

    // ==================== 内部方法 ====================

    /** L1 → L2 逐级读原始字符串，命中 L2 时回填 L1；未命中返回 null */
    private String readRaw(String key) {
        String json = l1.getIfPresent(key);
        if (json == null) {
            json = redisUtils.get(key);
            if (json != null) {
                l1.put(key, json);
            }
        }
        return json;
    }

    /** 回源并回填（仅由抢到刷新权的线程调用） */
    private <T> void refresh(String key, long ttl, TimeUnit unit, Function<String, T> dbLoader) {
        T data = dbLoader.apply(key);
        if (data == null) {
            writeNull(key);
        } else {
            put(key, data, ttl, unit);
        }
    }

    /** 写入空值哨兵（防穿透） */
    private void writeNull(String key) {
        redisUtils.set(key, NULL_MARKER, NULL_TTL_SECONDS, TimeUnit.SECONDS);
        l1.put(key, NULL_MARKER);
    }

    /** 缓存值：数据 JSON + 逻辑过期时间戳（毫秒） */
    public static class CachedValue {
        private Long expireAt;
        private String data;

        public Long getExpireAt() {
            return expireAt;
        }

        public void setExpireAt(Long expireAt) {
            this.expireAt = expireAt;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }
    }
}
