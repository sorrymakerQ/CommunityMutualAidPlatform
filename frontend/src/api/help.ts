/**
 * 求助相关接口
 */
import request, { type Result } from '@/request'
import type {
  Category,
  HelpListParams,
  HelpListResponse,
  HelpRequest,
  PublishHelpParams,
} from '@/types'

/** 分类列表 */
export function getCategoryList() {
  return request<Result<Category[]>>('/category/list', { method: 'GET' })
}

/** 求助列表（分页 / 关键词搜索） */
export function getHelpList(params: HelpListParams) {
  return request<Result<HelpListResponse['data']>>('/help/list', { method: 'GET', params })
}

/** 求助详情 */
export function getHelpDetail(id: number, silent = false) {
  return request<Result<HelpRequest>>(`/help/${id}`, { method: 'GET', silent })
}

/** 发布求助 */
export function publishHelp(params: PublishHelpParams, idempotencyKey?: string) {
  return request<Result<number>>('/help/publish', { method: 'POST', data: params, idempotencyKey })
}

/** 余额支付（支付成功后求助才上首页） */
export function payHelp(id: number) {
  return request<Result<number>>(`/pay/${id}`, { method: 'POST' })
}

/** 支付宝支付：data 为收银台表单 HTML，需在新窗口写入并提交 */
export function payHelpByAlipay(id: number) {
  return request<Result<string>>(`/pay/alipay/${id}`, { method: 'POST' })
}

/** 取消求助（仅发布者本人） */
export function cancelHelp(id: number) {
  return request<Result<null>>(`/help/${id}/cancel`, { method: 'PUT' })
}

/** 管理员 - 删除求助 */
export function deleteHelp(id: number) {
  return request<Result<null>>(`/admin/help/${id}`, { method: 'DELETE' })
}

/** 管理员 - 修改求助状态（下架 = status 4） */
export function updateAdminHelpStatus(id: number, status: number) {
  return request<Result<null>>(`/admin/help/${id}/status?status=${status}`, { method: 'PUT' })
}

/** 管理员 - 求助列表（含举报信息） */
export function getAdminHelpList(params: { page: number; size: number; status?: number }) {
  return request<Result<any>>('/admin/helps', { method: 'GET', params })
}

/** 我发布的求助 */
export function getMyHelps(params: { page: number; size: number; status?: number }) {
  return request<Result<any>>('/help/my', { method: 'GET', params })
}
