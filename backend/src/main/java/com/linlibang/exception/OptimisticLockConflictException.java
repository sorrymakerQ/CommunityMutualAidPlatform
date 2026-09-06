package com.linlibang.exception;

/**
 * 乐观锁版本冲突异常
 *
 * 含义：线程读到的 version 与数据库当前 version 不一致（期间数据已被其他事务修改），
 * 条件更新影响行数为 0。由 OptimisticLockUtils 捕获后触发重试；
 * 重试耗尽仍冲突时抛出到全局异常处理器，返回"数据已被他人修改"。
 */
public class OptimisticLockConflictException extends RuntimeException {

    public OptimisticLockConflictException(String message) {
        super(message);
    }
}
