/**
 * 求助相关 API 接口
 */
import request from '@/utils/request'
import type { PublishHelpParams, HelpListParams, HelpRequest, HelpListResponse, Category } from '@/types'

/**
 * 获取求助分类列表
 */
export function getCategoryList(): Promise<{ data: Category[] }> {
  return request.get('/category/list')
}

/**
 * 获取附近的求助列表
 * @param params lng, lat, radius, page, size
 */
export function getHelpList(params: HelpListParams & { radius?: number }): Promise<HelpListResponse> {
  return request.get('/help/nearby', { params })
}

/**
 * 获取求助详情
 */
export function getHelpDetail(id: number): Promise<{ data: HelpRequest }> {
  return request.get(`/help/${id}`)
}

/**
 * 发布求助
 * @param idempotencyKey 幂等键（可选）：同一动作重试复用同一键，后端识别重复提交
 */
export function publishHelp(params: PublishHelpParams, idempotencyKey?: string): Promise<{ data: { id: number } }> {
  return request.post('/help/publish', params, idempotencyKey
    ? ({ idempotencyKey } as any)
    : undefined)
}

/**
 * 支付求助（余额支付，支付成功后求助才上首页）
 */
export function payHelp(id: number): Promise<{ success: boolean; data: number }> {
  return request.post(`/pay/${id}`)
}

/**
 * 取消求助（仅发布者本人可操作）
 */
export function cancelHelp(id: number): Promise<{ success: boolean }> {
  return request.put(`/help/${id}/cancel`)
}

/**
 * 管理员 - 删除求助
 */
export function deleteHelp(id: number): Promise<{ success: boolean }> {
  return request.delete(`/admin/help/${id}`)
}

/**
 * 管理员 - 获取求助列表（含举报信息）
 */
export function getAdminHelpList(params: { page: number; size: number; status?: number }): Promise<{ data: any }> {
  return request.get('/admin/helps', { params })
}

/**
 * 获取我发布的求助列表
 */
export function getMyHelps(params: { page: number; size: number; status?: number }): Promise<{ data: any }> {
  return request.get('/help/my', { params })
}
