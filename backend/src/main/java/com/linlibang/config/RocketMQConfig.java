package com.linlibang.config;

/**
 * RocketMQ 常量配置
 * Topic 与消费组统一在此登记，避免散落硬编码。
 *
 * 目前只保留「支付超时 30 分钟」这一条延迟消息通道：
 *   - 支付超时 30 分钟：RocketMQ 内置延迟等级 16 实现；
 *   - 订单超时 1 天：RocketMQ 内置延迟等级最大仅 2 小时（等级 18），
 *     原先靠定时任务扫描（OrderTimeoutScanJob）实现，该任务已删除，当前没有自动超时取消；
 *   - 通知：仍走 MQ（见 NOTIFICATION_TOPIC）；
 *   - 评价：已从 MQ 改为同步落库（见 ReviewSettleService），相关 Topic 已删除。
 */
public class RocketMQConfig {

    // ==================== 通知 ====================

    /** 通知 Topic */
    public static final String NOTIFICATION_TOPIC = "notification-topic";

    /** 通知消费组 */
    public static final String NOTIFICATION_CONSUMER_GROUP = "notification-group";

    // ==================== 支付超时（30 分钟延迟） ====================

    /** 支付超时 Topic（发布求助时投递，30 分钟延迟后检查是否已支付） */
    public static final String PAY_TIMEOUT_TOPIC = "pay-timeout-topic";

    /** 支付超时消费组 */
    public static final String PAY_TIMEOUT_CONSUMER_GROUP = "pay-timeout-group";

    /** 二次修改支付状态重新验证 */
    public static final String PAY_TOPIC = "pay-topic";

    /** 二次修改支付状态重新验证消费组 */
    public static final String PAY_CONSUMER_GROUP = "pay-group";

    /**
     * RocketMQ 内置延迟等级 16 = 30 分钟（默认值，实际取值见 application.yml 的
     * rocketmq.pay-timeout.delay-level，可临时调小做联调）。
     * 等级表：1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h
     * 即 1=1秒 2=5秒 3=10秒 4=30秒 …… 16=30分钟 17=1小时 18=2小时。
     * 注意：只能填内置等级，没有"任意秒数"；写 10 不是 10 秒而是 10 分钟。
     */
    public static final int DELAY_LEVEL_30M = 16;

    /** 延迟等级 3 = 10 秒（本地联调验证支付超时取消时用，见 application.yml 注释） */
    public static final int DELAY_LEVEL_10S = 3;

    /** 各延迟等级的可读文案，下标 = 等级 - 1，与上面的等级表一一对应 */
    private static final String[] DELAY_LEVEL_DESC = {
            "1秒", "5秒", "10秒", "30秒",
            "1分钟", "2分钟", "3分钟", "4分钟", "5分钟", "6分钟",
            "7分钟", "8分钟", "9分钟", "10分钟", "20分钟", "30分钟",
            "1小时", "2小时"};

    /**
     * 把延迟等级翻译成「30分钟」这类可读文案，用于超时通知的措辞。
     * 之所以由等级反推而不是写死「30分钟」：延迟等级现在可配置，联调时会调小，
     * 写死的话测试时推给用户的文案就是错的。
     */
    public static String delayLevelDesc(int level) {
        return level >= 1 && level <= DELAY_LEVEL_DESC.length ? DELAY_LEVEL_DESC[level - 1] : "一段时间";
    }
}
