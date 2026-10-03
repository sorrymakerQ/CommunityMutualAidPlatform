package com.linlibang.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Redis 工具类
 * 封装项目实际使用的 Redis 操作（String / Number / 分布式锁）。
 *
 * 本地缓存（Caffeine L1）已统一收敛到 MultiLevelCache 组件，
 * 本类只负责纯粹的 Redis 读写，不维护任何本地状态。
 */
@Component
public class RedisUtils {

    /**
     * 安全释放锁：仅当锁值与预期一致时才删除（Lua 原子执行）。
     * 防止"锁超时自动过期后被他人持有，旧持有者误删他人的锁"。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // ==================== 字符串操作 ====================

    /** 存入字符串（带过期时间） */
    public void set(String key, String value, long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    /** 存入字符串（不过期，用于浏览计数等持久计数） */
    public void set(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }


    /** 获取字符串 */
    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    /** 删除单个键 */
    public Boolean delete(String key) {
        return stringRedisTemplate.delete(key);
    }

    // ==================== 分布式锁 ====================

    /** 安全释放锁：锁值匹配才删除（Lua 原子执行） */
    public void releaseLock(String key, String expectedValue) {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), expectedValue);
    }

    // ==================== 数值操作 ====================

    /** 自增 1 */
    public Long increment(String key) {
        return stringRedisTemplate.opsForValue().increment(key);
    }

}
