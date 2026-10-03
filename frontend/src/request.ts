/**
 * Axios 请求封装
 * 统一注入 Token、写操作幂等键、响应剥壳与错误提示
 */
import axios, { type AxiosRequestConfig } from 'axios'
import { showToast } from '@/utils/toast'

// 扩展 axios 配置：让 silent / idempotencyKey 有类型，调用处不用 as any
declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 静默请求：失败不弹提示，由调用方自行兜底 */
    silent?: boolean
    /** 幂等键：同一动作重试复用同一键，后端据此识别重复提交 */
    idempotencyKey?: string
  }
}

/** 后端统一响应结构（对应 com.linlibang.dto.Result） */
export interface Result<T = any> {
  success: boolean
  code: number
  message: string
  data: T
}

const myAxios = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
})

// 请求拦截器：注入 Token + 写操作补幂等键
myAxios.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.satoken = token
  }
  if (['POST', 'PUT', 'DELETE'].includes((config.method || 'get').toUpperCase())) {
    config.headers['X-Request-Id'] = config.idempotencyKey || newIdempotencyKey()
    delete config.idempotencyKey
  }
  return config
})

// 响应拦截器：剥壳 + 统一报错
myAxios.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.success || res.code === 200 || res.code === 0) {
      return res
    }
    showToast(res.message || '请求失败', 'warning')
    return Promise.reject(new Error(res.message || '请求失败'))
  },
  (error) => {
    // 静默请求（如后台轮询）不打扰用户
    if (error.config?.silent) {
      return Promise.reject(error)
    }
    const status = error.response?.status
    if (status === 401) {
      showToast('登录已过期，请重新登录', 'warning')
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
    } else if (status === 403) {
      showToast('没有操作权限', 'error')
    } else if (status === 404) {
      showToast('请求的资源不存在', 'warning')
    } else if (status === 500) {
      showToast('服务器错误，请稍后重试', 'error')
    } else if (error.code === 'ECONNABORTED') {
      showToast('请求超时，请重试', 'warning')
    } else {
      showToast('网络连接失败，请检查网络', 'error')
    }
    return Promise.reject(error)
  },
)

/** 生成幂等键（UUID v4），业务方用于"同一动作复用同一键" */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
  })
}

/**
 * 拦截器已剥壳，泛型就是调用方实际拿到的类型（不再是 AxiosResponse）
 * 两种写法等价：
 *   request<Result<UserInfo>>('/user/me', { method: 'GET' })
 *   request.get<Result<UserInfo>>('/user/me')
 */
interface Http {
  <T = any>(url: string, config?: AxiosRequestConfig): Promise<T>
  get<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>
  post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T>
  put<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T>
  delete<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>
}

export default myAxios as unknown as Http
