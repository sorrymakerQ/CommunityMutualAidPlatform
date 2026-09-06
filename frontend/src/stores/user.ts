/**
 * 用户状态管理（Pinia Store）
 * 管理用户登录状态、用户信息、Token等
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getUserInfo, login as loginApi, register as registerApi } from '@/api/user'
import type { UserInfo, LoginParams, RegisterParams } from '@/types'
import { showToast } from '@/utils/toast'

export const useUserStore = defineStore('user', () => {
  // ========== 状态 ==========
  const userInfo = ref<UserInfo | null>(null)
  const token = ref<string>(localStorage.getItem('token') || '')
  const isLoggedIn = ref<boolean>(!!token.value)

  // ========== 计算属性 ==========

  /** 是否为管理员（RBAC：admin 或 super_admin 都可进管理后台） */
  const isAdmin = computed(() => userInfo.value?.roleName === 'admin' || userInfo.value?.roleName === 'super_admin')

  /** 用户昵称 */
  const nickname = computed(() => userInfo.value?.nickname || '未设置昵称')

  /** 用户头像 */
  const avatar = computed(() => userInfo.value?.avatar || '')

  // ========== 方法 ==========

  /**
   * 用户登录
   */
  async function login(phone: string, password: string): Promise<boolean> {
    try {
      const params: LoginParams = { phone, password }
      const res = await loginApi(params)
      if (res.data && res.data.token) {
        token.value = res.data.token
        localStorage.setItem('token', res.data.token)
        if (res.data.userInfo) {
          userInfo.value = res.data.userInfo
          localStorage.setItem('userInfo', JSON.stringify(res.data.userInfo))
        }
        isLoggedIn.value = true
        // 登录成功后获取完整用户信息
        await fetchUserInfo()
        return true
      }
      return false
    } catch {
      return false
    }
  }

  /**
   * 用户注册
   */
  async function register(phone: string, password: string, nickname: string): Promise<boolean> {
    try {
      const params: RegisterParams = { phone, password, nickname }
      const res = await registerApi(params)
      if (res.success) {
        return true
      }
      return false
    } catch {
      return false
    }
  }

  /**
   * 获取用户信息
   */
  async function fetchUserInfo(): Promise<void> {
    try {
      const res = await getUserInfo()
      if (res.data) {
        userInfo.value = res.data
        localStorage.setItem('userInfo', JSON.stringify(res.data))
      }
    } catch {
      // 获取用户信息失败，可能token过期，需要重新登录
    }
  }

  /**
   * 退出登录
   */
  function logout(): void {
    token.value = ''
    userInfo.value = null
    isLoggedIn.value = false
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
    showToast('已退出登录')
    // 使用路由跳转而非刷新页面，保留 SPA 状态
    if (typeof window !== 'undefined') {
      window.location.href = '/'
    }
  }

  /**
   * 从本地存储恢复用户状态
   */
  function restoreState(): void {
    const savedToken = localStorage.getItem('token')
    if (savedToken) {
      token.value = savedToken
      isLoggedIn.value = true
      const savedUserInfo = localStorage.getItem('userInfo')
      if (savedUserInfo) {
        try {
          userInfo.value = JSON.parse(savedUserInfo)
        } catch {
          userInfo.value = null
        }
      }
    }
  }

  // 初始化时尝试恢复状态
  restoreState()

  return {
    // 状态
    userInfo,
    token,
    isLoggedIn,
    // 计算属性
    isAdmin,
    nickname,
    avatar,
    // 方法
    login,
    register,
    fetchUserInfo,
    logout
  }
})
