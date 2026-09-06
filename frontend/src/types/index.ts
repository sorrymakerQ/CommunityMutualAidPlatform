/**
 * 社区互助平台 - 全局 TypeScript 类型定义
 */

// ==================== 用户相关 ====================

/** 用户信息 */
export interface UserInfo {
  id: number
  phone: string
  nickname: string
  avatar: string
  gender: number            // 0-未知 1-男 2-女
  community: string         // 所在小区
  lng: number               // 经度
  lat: number               // 纬度
  credit: number            // 信用分
  helpCount: number         // 帮助次数
  balance?: number          // 账户余额（元）
  intro: string             // 个人简介
  roleId?: number           // 角色ID（RBAC，关联 tb_role）
  roleName?: string         // 角色编码：admin/user/reviewer
  createTime: string
}

/** 角色信息（RBAC） */
export interface RoleInfo {
  id: number
  code: string              // admin/user/reviewer
  name: string              // 管理员/普通用户/审核员
  description?: string
  status: number
}

/** 登录请求参数 */
export interface LoginParams {
  phone: string
  password: string
}

/** 注册请求参数 */
export interface RegisterParams {
  phone: string
  password: string
  nickname: string
}

/** 登录/注册响应 */
export interface AuthResponse {
  success: boolean
  token: string
  userInfo: UserInfo
  message?: string
}

/** 更新用户资料参数 */
export interface UpdateUserParams {
  nickname?: string
  avatar?: string
  gender?: number
  community?: string
  intro?: string
}

// ==================== 求助相关 ====================

/** 求助信息 */
export interface HelpRequest {
  id: number
  userId: number
  categoryId: number
  categoryName: string
  categoryIcon?: string     // 分类图标
  title: string
  description: string
  images: string[]
  reward: number            // 酬劳单价（元/人）
  totalReward?: number      // 支付总额（单价×需要人数）
  address: string
  lng: number
  lat: number
  status: number            // 0-待支付 1-招募中 2-已满员(进行中) 3-已完成 4-已取消
  urgent: number            // 0-普通 1-紧急
  helperNum?: number        // 需要人数（多人求助）
  acceptedNum?: number      // 已接人数
  viewCount: number         // 浏览次数
  publisherName: string     // 发布者昵称
  publisherAvatar: string   // 发布者头像
  publisherCredit?: number  // 发布者信用分
  publisherHelpCount?: number // 发布者帮助次数
  distance: number          // 距离（米）
  currentHelperId?: number  // 当前接单者ID（如有活跃订单）
  currentOrderId?: number   // 当前订单ID（如有活跃订单）
  createTime: string
}

/** 发布求助参数 */
export interface PublishHelpParams {
  categoryId: number
  title: string
  description: string
  images: string[]
  reward: number
  address: string
  lng: number
  lat: number
  urgent: number
  helperNum?: number        // 需要人数（1~20，默认1）
}

/** 求助列表查询参数 */
export interface HelpListParams {
  page?: number
  size?: number
  categoryId?: number      // 分类ID筛选
  keyword?: string         // 关键词搜索
  lng?: number             // 经度（用于附近排序）
  lat?: number             // 纬度
  radius?: number          // 搜索半径（米）
  status?: number          // 状态筛选
  urgent?: number          // 是否紧急
  userId?: number          // 按用户筛选
}

/** 求助列表响应 */
export interface HelpListResponse {
  success: boolean
  data: {
    list: HelpRequest[]
    total: number
  }
}

// ==================== 订单相关 ====================

/** 订单信息 */
export interface Order {
  id: number
  helpId: number
  helpTitle: string
  reward: number            // 酬劳金额
  publisherId: number
  publisherName: string
  publisherAvatar: string
  helperId: number
  helperName: string
  helperAvatar: string
  helperPhone: string
  otherName: string         // 对方昵称
  otherAvatar: string       // 对方头像
  status: number            // 1-已接单 2-进行中 3-已完成 4-已取消 5-已评价
  publisherScore?: number   // 发布者评分
  helperScore?: number      // 接单者评分
  publisherComment?: string // 发布者评价内容
  helperComment?: string    // 接单者评价内容
  createTime: string
}

/** 订单列表查询参数 */
export interface OrderListParams {
  page?: number
  size?: number
  role: 'publisher' | 'helper' | 'all'  // publisher-我发布的 helper-我接的单
  status?: number
}

// ==================== 分类相关 ====================

/** 求助分类 */
export interface Category {
  id: number
  name: string
  icon: string             // 图标（emoji或图标类名）
  sort: number             // 排序
  helpCount?: number        // 该分类下的求助数量
}

// ==================== 通知相关 ====================

/** 通知消息 */
export interface Notification {
  id: number
  userId: number
  type: number             // 1-订单更新 2-系统通知 3-评价提醒
  title: string
  content: string
  isRead: number           // 0-未读 1-已读
  relatedId: number        // 关联的业务ID
  createTime: string
}

// ==================== 通用响应 ====================
// 说明：项目里 axios 拦截器解包后直接返回 { data, message } 结构，
//       各 api 方法自行定义精确的 Promise<{ data: T }> 返回类型，无需通用 wrapper。
