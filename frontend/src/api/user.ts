/**
 * 用户相关接口
 */
import request, { type Result } from '@/request'
import type {
  AuthResponse,
  LoginParams,
  RegisterParams,
  RoleInfo,
  UpdateUserParams,
  UserInfo,
} from '@/types'

/** 用户登录 */
export function login(params: LoginParams) {
  return request<Result<AuthResponse>>('/user/login', { method: 'POST', data: params })
}

/** 用户注册 */
export function register(params: RegisterParams) {
  return request<Result<AuthResponse>>('/user/register', { method: 'POST', data: params })
}

/** 获取当前用户信息 */
export function getUserInfo() {
  return request<Result<UserInfo>>('/user/me', { method: 'GET' })
}

/** 更新用户资料 */
export function updateUserInfo(params: UpdateUserParams) {
  return request<Result<UserInfo>>('/user/update', { method: 'PUT', data: params })
}

/** 管理员 - 用户列表 */
export function getAdminUserList(params: { page: number; size: number; keyword?: string }) {
  return request<Result<any>>('/admin/users', { method: 'GET', params })
}

/** 管理员 - 禁用/启用用户 */
export function toggleUserStatus(userId: number, status: number) {
  return request<Result<null>>(`/admin/user/${userId}/status?status=${status}`, { method: 'PUT' })
}

/** 管理员 - 分配角色 */
export function updateUserRole(userId: number, roleId: number) {
  return request<Result<null>>(`/admin/user/${userId}/role?roleId=${roleId}`, { method: 'PUT' })
}

/** 管理员 - 踢用户下线 */
export function kickoutUser(userId: number) {
  return request<Result<null>>(`/admin/user/${userId}/kickout`, { method: 'POST' })
}

/** 管理员 - 角色列表 */
export function getRoleList() {
  return request<Result<RoleInfo[]>>('/admin/roles', { method: 'GET' })
}
