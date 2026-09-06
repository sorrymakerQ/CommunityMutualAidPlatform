package com.linlibang.utils;

import com.linlibang.exception.OptimisticLockConflictException;

import java.util.function.Supplier;

/**
 * 乐观锁重试模板
 *
 * 版本号乐观锁的固定三步：
 *   1. 读数据（同时读出 version）
 *   2. 内存中修改
 *   3. 条件更新：UPDATE ... SET version = version + 1 WHERE id = ? AND version = 读到的值
 *      影响行数为 0 -> 说明期间有人改过 -> 抛 OptimisticLockConflictException
 *      本工具捕获冲突后小睡退避再重试，直到成功或超过最大次数。
 *
 * ⚠️ 使用前提（重要）：调用方必须保证每次 attempt 在【新事务】中执行。
 * MySQL 默认 REPEATABLE READ，同一事务内重复 SELECT 拿到的是事务开始的旧快照，
 * 永远读到旧 version，重试永远失败。所以正确写法是：
 *   OptimisticLockUtils.retry(() -> transactionTemplate.execute(status -> doXxxInTx(...)))
 */
public class OptimisticLockUtils {

    /** 默认最大重试次数 */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /** 用默认次数重试（3 次） */
    public static <T> T retry(Supplier<T> attempt) {
        return retry(DEFAULT_MAX_RETRIES, attempt);
    }

    /**
     * @param maxRetries 最大尝试次数（>=1）
     * @param attempt    一次完整尝试（读 -> 改 -> 条件更新），冲突时抛 OptimisticLockConflictException
     */
    public static <T> T retry(int maxRetries, Supplier<T> attempt) {
        OptimisticLockConflictException last = null;
        for (int i = 1; i <= maxRetries; i++) {
            try {
                return attempt.get();
            } catch (OptimisticLockConflictException e) {
                last = e;
                if (i < maxRetries) {
                    sleepBackoff(i);
                }
            }
        }
        // 重试耗尽：抛出最后一次冲突，由全局异常处理器转成友好提示
        throw last;
    }

    /** 线性退避：10ms、20ms、30ms……给并发对手留出提交窗口 */
    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(10L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
