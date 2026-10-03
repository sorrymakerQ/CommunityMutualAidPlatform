package com.linlibang.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单号生成器
 *
 * 格式：LB + yyyyMMddHHmmssSSS + 6 位随机数字
 * 示例：LB20260930153045123456（共 22 位）
 *
 * 设计说明：
 * 1. 时间前缀：便于按时间排查/排序，并天然降低碰撞概率；
 * 2. 随机后缀：满足"随机订单号"的要求；
 * 3. 唯一性：由 tb_pay_order 的 uk_pay_no 唯一索引兜底（毫秒 + 6 位随机的碰撞概率可忽略）。
 *
 * 注意：本工具现在为「支付单」（tb_pay_order.pay_no）生成对外单号，它同时就是支付宝的
 *      out_trade_no；履约单 tb_order 已不再持有自己的编号（见 scripts/sql/011）。
 */
public final class OrderNoUtils {

    /** 订单号前缀 */
    private static final String PREFIX = "LB";

    /** 时间部分：精确到毫秒 */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private OrderNoUtils() {
    }

    /**
     * 生成一个新的订单号（每次调用都不同）
     *
     * @return 订单号，如 LB20260930153045123456
     */
    public static String generate() {
        String time = LocalDateTime.now().format(TIME_FORMAT);
        // 6 位随机数（100000 ~ 999999，避免出现前导 0 造成长度不一致）
        int random = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return PREFIX + time + random;
    }
}
