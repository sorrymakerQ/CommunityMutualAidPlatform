/**
 * 社区互助平台 - 路由配置
 * 路由守卫：登录检查 + 角色权限检查
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { checkRoutePermission } from '@/utils/permission'

// 懒加载页面组件
const Home = () => import('@/views/Home.vue')
const Login = () => import('@/views/Login.vue')
const Register = () => import('@/views/Register.vue')
const PublishHelp = () => import('@/views/PublishHelp.vue')
const HelpDetail = () => import('@/views/HelpDetail.vue')
const MapView = () => import('@/views/MapView.vue')
const Profile = () => import('@/views/Profile.vue')
const EditProfile = () => import('@/views/EditProfile.vue')
const MyOrders = () => import('@/views/MyOrders.vue')
const OrderDetail = () => import('@/views/OrderDetail.vue')
const Messages = () => import('@/views/Messages.vue')
const Admin = () => import('@/views/Admin.vue')

const routes: RouteRecordRaw[] = [
  { path: '/',          name: 'home',          component: Home,          meta: { title: '社区互助平台 - 首页' } },
  { path: '/login',     name: 'login',         component: Login,         meta: { title: '登录' } },
  { path: '/register',  name: 'register',      component: Register,      meta: { title: '注册' } },
  { path: '/publish',   name: 'publish',       component: PublishHelp,   meta: { title: '发布求助', requireAuth: true } },
  { path: '/help/:id',  name: 'help-detail',   component: HelpDetail,    meta: { title: '求助详情' } },
  { path: '/map',       name: 'map',           component: MapView,       meta: { title: '附近地图' } },
  { path: '/profile',   name: 'profile',       component: Profile,       meta: { title: '个人中心', requireAuth: true } },
  { path: '/profile/edit', name: 'profile-edit', component: EditProfile, meta: { title: '编辑资料', requireAuth: true } },
  { path: '/orders',    name: 'orders',        component: MyOrders,      meta: { title: '我的订单', requireAuth: true } },
  { path: '/order/:id', name: 'order-detail',  component: OrderDetail,  meta: { title: '订单详情', requireAuth: true } },
  { path: '/messages',  name: 'messages',      component: Messages,      meta: { title: '消息中心', requireAuth: true } },
  { path: '/admin',     name: 'admin',         component: Admin,         meta: { title: '管理后台', requireAuth: true, requireAdmin: true } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() { return { top: 0 } }
}
)

// 全局前置守卫
router.beforeEach((to, _from, next) => {
  // 设置页面标题
  if (to.meta?.title) document.title = to.meta.title as string

  // 权限检查
  if (!checkRoutePermission(to)) {
    const userInfo = localStorage.getItem('userInfo')
    const roleName = userInfo ? JSON.parse(userInfo).roleName : null

    // 未登录 → 跳登录
    if (!localStorage.getItem('token')) {
      next({ name: 'login', query: { redirect: to.fullPath } })
      return
    }

    // 不是管理员但访问管理员页面 → 跳首页
    if (to.meta?.requireAdmin && roleName !== 'admin' && roleName !== 'super_admin') {
      next({ name: 'home' })
      return
    }

    // 其他情况 → 跳登录
    next({ name: 'login', query: { redirect: to.fullPath } })
    return
  }

  next()
}
)

export default router
