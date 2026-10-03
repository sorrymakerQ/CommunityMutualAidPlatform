/**
 * 订单 + 通知 + 管理后台接口
 */
import request, { type Result } from '@/request'
import type { Notification, Order, OrderListParams } from '@/types'

/** 接单（即接即录用：校验通过直接生成订单并占用名额） */
export function acceptOrder(helpId: number) {
  return request<Result<{ orderId: number }>>(`/order/accept/${helpId}`, { method: 'POST' })
}

/** 我的订单列表 */
export function getOrderList(params: OrderListParams) {
  return request<Result<{ list: Order[]; total: number }>>('/order/my', { method: 'GET', params })
}

/** 订单详情 */
export function getOrderDetail(orderId: number) {
  return request<Result<Order>>(`/order/${orderId}`, { method: 'GET' })
}

/** 完成订单 */
export function completeOrder(orderId: number) {
  return request<Result<null>>(`/order/${orderId}/finish`, { method: 'PUT' })
}

/** 取消订单 */
export function cancelOrder(orderId: number, reason: string) {
  return request<Result<null>>(`/order/${orderId}/cancel`, { method: 'PUT', data: { reason } })
}

/** 评价订单 */
export function rateOrder(orderId: number, score: number, comment: string) {
  return request<Result<null>>(`/order/${orderId}/review`, {
    method: 'PUT',
    data: { score, comment },
  })
}

// ==================== 通知 ====================

/** 通知列表 */
export function getNotifications(params: { page: number; size: number }) {
  return request<Result<{ list: Notification[]; total: number }>>('/notifications', {
    method: 'GET',
    params,
  })
}

/** 标记通知已读 */
export function markNotificationRead(id: number) {
  return request<Result<null>>(`/notifications/${id}/read`, { method: 'PUT' })
}

/** 未读通知数 */
export function getUnreadCount() {
  return request<Result<{ count: number }>>('/notifications/unread-count', { method: 'GET' })
}

/** 全部标记已读 */
export function markAllRead() {
  return request<Result<null>>('/notifications/read-all', { method: 'PUT' })
}

// ==================== 管理后台 ====================

/** 管理后台统计 */
export function getDashboardStats() {
  return request<Result<any>>('/admin/stats', { method: 'GET' })
}
