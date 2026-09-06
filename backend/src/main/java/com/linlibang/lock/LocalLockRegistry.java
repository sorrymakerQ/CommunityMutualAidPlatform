package com.linlibang.lock;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存锁注册表（JVM 本地锁）
 *
 * 设计定位（两层防线）：
 *   1. 内存锁（本类）—— 性能/体验层：只挡"本实例内"的并发，
 *      零网络开销、无过期时间、无看门狗问题，锁生命周期完全可靠；
 *   2. 数据库乐观锁（状态机 CAS + 唯一索引）—— 正确性层：
 *      无论内存锁是否失效（多实例部署、进程被杀），
 *      UPDATE ... WHERE status = ? 的原子性保证数据永远正确。
 *
 * 注意：多实例部署时各实例的锁互不感知，跨实例并发由 CAS 兜底，
 * 内存锁只是减少本实例内打到数据库的无谓竞争。
 */
@Component
public class LocalLockRegistry {

    /**
     * 每把业务 key 对应一把 ReentrantLock。
     * 公平锁（fair=true）：等待线程按 FIFO 排队，先来先得，避免"插队"导致的饥饿。
     * 注意两点：
     *   1. 无参 tryLock() 即使公平锁下也会插队（JDK 有意设计），只有 lock() / tryLock(timeout) 遵守公平；
     *   2. 公平锁排队唤醒开销更高，锁竞争激烈时吞吐低于非公平锁。
     */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 获取指定 key 的锁（同一 key 全局唯一实例）
     */
    public ReentrantLock getLock(String key) {
        return locks.computeIfAbsent(key, k -> new ReentrantLock(true));
    }

    /**
     * 获取指定 id 的锁（缓存击穿等按 id 加锁的场景）
     */
    public ReentrantLock getLock(Long id) {
        return getLock(String.valueOf(id));
    }

    /**
     * 当前注册表内锁的数量（观测用）
     */
    public int size() {
        return locks.size();
    }
}
