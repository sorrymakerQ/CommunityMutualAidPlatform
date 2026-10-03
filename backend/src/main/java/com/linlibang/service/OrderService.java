package com.linlibang.service;

import com.linlibang.dto.Result;

/**
 * 订单服务接口
 *
 * 涉及"当前登录用户"的方法不再接收 userId/helperId 参数，
 * 一律由实现类内部用 StpUtil.getLoginIdAsLong() 获取。
 */
public interface OrderService {

    /**
     * 接单（即接即录用）：校验通过后直接生成订单并占用名额
     *
     * @param helpId 求助ID
     * @return 接单结果（data 为生成的订单ID）
     */
    Result acceptOrder(Long helpId);

    /**
     * 取消订单
     *
     * @param orderId 订单ID
     * @param reason  取消原因
     * @return 取消结果
     */
    Result cancelOrder(Long orderId, String reason);

    /**
     * 完成订单
     *
     * @param orderId 订单ID
     * @return 完成结果
     */
    Result finishOrder(Long orderId);

    /**
     * 评价订单
     *
     * @param orderId 订单ID
     * @param score   评分（1-5）
     * @param comment 评价内容
     * @return 评价结果
     */
    Result reviewOrder(Long orderId, Integer score, String comment);

    /**
     * 查询我的订单
     *
     * @param role 角色：publisher-发布者，helper-接单者
     * @param page 页码
     * @param size 每页条数
     * @return 订单列表
     */
    Result getMyOrders(String role, Integer page, Integer size);

    /**
     * 查询订单详情（含关联数据）
     *
     * @param orderId 订单ID
     * @return 订单详情
     */
    Result getOrderById(Long orderId);
}
