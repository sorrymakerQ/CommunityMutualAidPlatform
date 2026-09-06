package com.linlibang.config;

/**
 * RocketMQ 常量配置
 * Topic 与消费组统一在此登记，避免散落硬编码。
 *
 * 延迟消息说明：
 *   - 支付超时 30 分钟：RocketMQ 内置延迟等级 16 实现；
 *   - 订单超时 1 天：RocketMQ 内置延迟等级最大仅 2 小时（等级 18），
 *     因此订单超时改用定时任务扫描（见 OrderTimeoutScanJob），不走 MQ。
 */
public class RocketMQConfig {

    // ==================== 通知 ====================

    /** 通知 Topic */
    public static final String NOTIFICATION_TOPIC = "linlibang-notification-topic";

    /** 通知消费组 */
    public static final String NOTIFICATION_CONSUMER_GROUP = "linlibang-notification-group";

    // ==================== 支付超时（30 分钟延迟） ====================

    /** 支付超时 Topic（发布求助时投递，30 分钟延迟后检查是否已支付） */
    public static final String PAY_TIMEOUT_TOPIC = "linlibang-pay-timeout-topic";

    /** 支付超时消费组 */
    public static final String PAY_TIMEOUT_CONSUMER_GROUP = "linlibang-pay-timeout-group";

    // ==================== 评价异步 ====================

    /** 评价 Topic（主线程只投递，消费端落库） */
    public static final String REVIEW_TOPIC = "linlibang-review-topic";

    /** 评价消费组 */
    public static final String REVIEW_CONSUMER_GROUP = "linlibang-review-group";

    /**
     * RocketMQ 内置延迟等级 16 = 30 分钟。
     * 等级表：1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h
     */
    public static final int DELAY_LEVEL_30M = 16;
}
