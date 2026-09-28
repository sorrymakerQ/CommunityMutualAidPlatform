package com.linlibang.exception;

/**
 * 乐观锁版本冲突异常
 *
 * 含义：线程读到的 version 与数据库当前 version 不一致（期间数据已被其他事务修改），
 * 条件更新影响行数为 0。调用方捕获后：同步接口直接返回失败提示；
 * 评价等异步消费场景向上抛出，交由 RocketMQ 重投。
 */
public class OptimisticLockConflictException extends RuntimeException {

    public OptimisticLockConflictException(String message) {
        super(message);
    }
}
