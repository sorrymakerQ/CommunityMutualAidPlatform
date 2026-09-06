package com.linlibang.service;

import com.linlibang.dto.Result;

/**
 * 订单服务接口
 */
public interface OrderService {

    /**
     * 申请接单（老板审批制）：提交申请，等待发布者确认
     *
     * @param helpId  求助ID
     * @param helperId 申请者ID
     * @return 申请结果
     */
    Result acceptOrder(Long helpId, Long helperId);

    /**
     * 发布者：查看求助的申请列表
     */
    Result getApplyList(Long helpId, Long publisherId);

    /**
     * 发布者：同意申请（生成订单 + 占名额，满员自动拒绝剩余申请）
     *
     * @param reason 审批理由（可空，会通知给申请人）
     */
    Result approveApply(Long applyId, Long publisherId, String reason);

    /**
     * 发布者：拒绝申请（带理由并通知申请人）
     */
    Result rejectApply(Long applyId, Long publisherId, String reason);

    /**
     * 取消订单
     *
     * @param orderId 订单ID
     * @param userId  操作者ID
     * @param reason  取消原因
     * @return 取消结果
     */
    Result cancelOrder(Long orderId, Long userId, String reason);

    /**
     * 完成订单
     *
     * @param orderId 订单ID
     * @param userId  操作者ID
     * @return 完成结果
     */
    Result finishOrder(Long orderId, Long userId);

    /**
     * 评价订单
     *
     * @param orderId 订单ID
     * @param userId  评价者ID
     * @param score   评分（1-5）
     * @param comment 评价内容
     * @return 评价结果
     */
    Result reviewOrder(Long orderId, Long userId, Integer score, String comment);

    /**
     * 查询我的订单
     *
     * @param userId 用户ID
     * @param role   角色：publisher-发布者，helper-接单者
     * @param page   页码
     * @param size   每页条数
     * @return 订单列表
     */
    Result getMyOrders(Long userId, String role, Integer page, Integer size);

    /**
     * 查询订单详情（含关联数据）
     *
     * @param orderId 订单ID
     * @param userId  当前用户ID
     * @return 订单详情
     */
    Result getOrderById(Long orderId, Long userId);
}
