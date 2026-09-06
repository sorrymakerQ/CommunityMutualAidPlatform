/**
 * 用户相关 API 接口
 */
import request from '@/utils/request'
import type { LoginParams, RegisterParams, AuthResponse, UserInfo, UpdateUserParams, RoleInfo } from '@/types'

/**
 * 用户登录
 */
export function login(params: LoginParams): Promise<AuthResponse> {
  return request.post('/user/login', params)
}

/**
 * 用户注册
 */
export function register(params: RegisterParams): Promise<AuthResponse> {
  return request.post('/user/register', params)
}

/**
 * 获取当前用户信息
 */
export function getUserInfo(): Promise<{ data: UserInfo }> {
  return request.get('/user/me')
}

/**
 * 更新用户资料
 */
export function updateUserInfo(params: UpdateUserParams): Promise<{ data: UserInfo }> {
  return request.put('/user/update', params)
}

/**
 * 管理员 - 获取用户列表
 */
export function getAdminUserList(params: { page: number; size: number; keyword?: string }): Promise<{ data: any }> {
  return request.get('/admin/users', { params })
}

/**
 * 管理员 - 禁用/启用用户
 */
export function toggleUserStatus(userId: number, status: number): Promise<{ success: boolean }> {
  return request.put(`/admin/user/${userId}/status?status=${status}`)
}

/**
 * 管理员 - 为用户分配角色（RBAC）
 */
export function updateUserRole(userId: number, roleId: number): Promise<{ success: boolean }> {
  return request.put(`/admin/user/${userId}/role?roleId=${roleId}`)
}

/**
 * 管理员 - 角色列表（分配角色下拉用）
 */
export function getRoleList(): Promise<{ success: boolean; data: RoleInfo[] }> {
  return request.get('/admin/roles')
}
