/**
 * 订单相关 API 接口
 */
import request from '@/utils/request'
import type { Order, OrderListParams, Notification } from '@/types'

/**
 * 接单（老板审批制：提交接单申请，等待发布者确认）
 */
export function acceptOrder(helpId: number): Promise<{ data: { orderId: number } }> {
  return request.post(`/order/accept/${helpId}`)
}

/**
 * 发布者：查看求助的接单申请列表
 */
export function getApplyList(helpId: number): Promise<{ data: any }> {
  return request.get(`/order/apply-list/${helpId}`)
}

/**
 * 发布者：同意接单申请（生成订单+占名额），可附审批理由
 */
export function approveApply(applyId: number, reason?: string): Promise<{ success: boolean }> {
  return request.post(`/order/apply/${applyId}/approve`, { reason: reason || '' })
}

/**
 * 发布者：拒绝接单申请（带理由通知申请人）
 */
export function rejectApply(applyId: number, reason?: string): Promise<{ success: boolean }> {
  return request.post(`/order/apply/${applyId}/reject`, { reason: reason || '' })
}

/**
 * 获取订单列表
 */
export function getOrderList(params: OrderListParams): Promise<{ data: { list: Order[]; total: number } }> {
  return request.get('/order/my', { params })
}

/**
 * 获取订单详情
 */
export function getOrderDetail(orderId: number): Promise<{ data: Order }> {
  return request.get(`/order/${orderId}`)
}

/**
 * 完成订单
 */
export function completeOrder(orderId: number): Promise<{ success: boolean }> {
  return request.put(`/order/${orderId}/finish`)
}

/**
 * 取消订单
 */
export function cancelOrder(orderId: number, reason: string): Promise<{ success: boolean }> {
  return request.put(`/order/${orderId}/cancel`, { reason })
}

/**
 * 评价订单
 */
export function rateOrder(orderId: number, score: number, comment: string): Promise<{ success: boolean }> {
  return request.put(`/order/${orderId}/review`, { score, comment })
}

// ==================== 消息通知相关 ====================

/**
 * 获取通知列表
 */
export function getNotifications(params: { page: number; size: number }): Promise<{ data: { list: Notification[]; total: number } }> {
  return request.get('/notifications', { params })
}

/**
 * 标记通知为已读
 */
export function markNotificationRead(id: number): Promise<{ success: boolean }> {
  return request.put(`/notifications/${id}/read`)
}

/**
 * 获取未读通知数量
 */
export function getUnreadCount(): Promise<{ data: { count: number } }> {
  return request.get('/notifications/unread-count')
}

/**
 * 全部标记为已读
 */
export function markAllRead(): Promise<{ success: boolean }> {
  return request.put('/notifications/read-all')
}

// ==================== 管理后台相关 ====================

/**
 * 获取管理后台统计数据
 */
export function getDashboardStats(): Promise<{ data: any }> {
  return request.get('/admin/stats')
}
