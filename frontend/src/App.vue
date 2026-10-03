<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { computed, ref, watch } from 'vue'
import { useUserStore } from '@/stores/user'
import { getUnreadCount } from '@/api/order'
import { Bell, HomeFilled, Edit, Tickets, ChatLineSquare, User, Setting } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 未读通知数：轮询拉取（聊天已下线，通知不再依赖 WebSocket） */
const notifyUnread = ref(0)

let notifyTimer: ReturnType<typeof setInterval> | null = null

async function refreshNotifyUnread() {
  if (!userStore.isLoggedIn) { notifyUnread.value = 0; return }
  try {
    const r = await getUnreadCount()
    notifyUnread.value = r.data?.count || 0
  } catch { notifyUnread.value = 0 }
}

watch(() => userStore.isLoggedIn, (loggedIn) => {
  if (loggedIn) {
    refreshNotifyUnread()
    notifyTimer = setInterval(refreshNotifyUnread, 30000)
  } else {
    notifyUnread.value = 0
    if (notifyTimer) { clearInterval(notifyTimer); notifyTimer = null }
  }
}, { immediate: true })

/** 这些页面用独立全屏壳子渲染（不带左侧导航/顶栏） */
const isFullPage = computed(() =>
  ['login', 'register', 'pay', 'pay-success'].includes(route.name as string)
)

/** 根据当前路由反查默认展开的菜单 key */
const activeMenu = computed(() => {
  if (route.path === '/') return '/'
  if (route.path.startsWith('/publish')) return '/publish'
  if (route.path.startsWith('/orders')) return '/orders'
  if (route.path.startsWith('/messages')) return '/messages'
  if (route.path.startsWith('/profile') || route.path.startsWith('/profile/edit')) return '/profile'
  if (route.path.startsWith('/admin')) return '/admin'
  return ''
})

const menuItems = computed(() => {
  const items = [
    { path: '/',            icon: HomeFilled,      label: '首页' },
    { path: '/publish',     icon: Edit,             label: '发布求助' },
    { path: '/orders',      icon: Tickets,          label: '我的订单',  needLogin: true },
    { path: '/messages',    icon: ChatLineSquare,   label: '消息中心',  needLogin: true },
    { path: '/profile',     icon: User,             label: '个人中心',  needLogin: true },
    { path: '/admin',       icon: Setting,          label: '管理后台',  needLogin: true, needAdmin: true },
  ]
  return items.filter(item => {
    if ((item as any).needLogin && !userStore.isLoggedIn) return false
    if ((item as any).needAdmin && !userStore.isAdmin) return false
    return true
  })
})

function go(path: string) {
  if (path === '/publish' && !userStore.isLoggedIn) { router.push('/login'); return }
  router.push(path)
}
</script>

<template>
  <!-- 全屏壳子：Element Plus Container 布局 -->
  <el-container class="app-shell" v-if="!isFullPage">
    <!-- 左侧导航 -->
    <el-aside width="200px" class="sidebar">
      <div class="logo-area" @click="router.push('/')">
        <span class="logo-icon">🏘️</span>
        <span class="logo-text">社区互助平台</span>
      </div>

      <el-menu
        :default-active="activeMenu"
        background-color="#fff"
        text-color="#61666d"
        active-text-color="#00B96B"
        class="nav-menu"
      >
        <el-menu-item
          v-for="item in menuItems"
          :key="item.path"
          :index="item.path"
          @click="go(item.path)"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <!-- 右侧主体 -->
    <el-container>
      <!-- 顶部栏 -->
      <el-header height="52px" class="topbar">
        <div class="topbar-left">
          <span class="topbar-title">
            <template v-if="route.path === '/'">首页推荐</template>
            <template v-else-if="route.path.startsWith('/publish')">发布求助</template>
            <template v-else-if="route.path.startsWith('/orders')">我的订单</template>
            <template v-else-if="route.path.startsWith('/messages')">消息中心</template>
            <template v-else-if="route.path.startsWith('/profile')">个人中心</template>
            <template v-else-if="route.path.startsWith('/help')">求助详情</template>
            <template v-else-if="route.path.startsWith('/admin')">管理后台</template>
            <template v-else>社区互助平台</template>
          </span>
        </div>
        <div class="topbar-right">
          <template v-if="userStore.isLoggedIn">
            <el-badge :value="notifyUnread" :max="99" :hidden="notifyUnread === 0" class="msg-badge-wrap">
              <el-button :icon="Bell" circle @click="notifyUnread = 0; router.push('/messages')" />
            </el-badge>
            <div class="user-area" @click="router.push('/profile')">
              <el-avatar :size="32" :src="userStore.avatar || '/default-avatar.svg'" />
              <span class="user-name">{{ userStore.nickname }}</span>
            </div>
          </template>
          <template v-else>
            <el-button type="primary" size="small" round @click="router.push('/login')">登录</el-button>
          </template>
        </div>
      </el-header>

      <!-- 内容区 -->
        <el-main class="content-area">
          <router-view />
        </el-main>
      </el-container>
  </el-container>

  <!-- 登录/注册全屏页 -->
  <div v-else class="full-page-shell">
    <router-view />
  </div>
</template>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body, #app { height: 100%; overflow: hidden; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif; background: #f4f5f7; }
a { text-decoration: none; color: inherit; cursor: pointer; }
::-webkit-scrollbar { width: 6px; }
::-webkit-scrollbar-thumb { background: #c1c1c1; border-radius: 3px; }
::-webkit-scrollbar-track { background: transparent; }
</style>

<style scoped>
.app-shell { height: 100vh; overflow: hidden; background: #f4f5f7; }

/* ==================== 左侧导航 ==================== */
.sidebar {
  background: #fff;
  border-right: 1px solid #e8eaed;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

.logo-area {
  padding: 18px 16px 12px;
  cursor: pointer;
  text-align: center;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.logo-icon { font-size: 28px; }
.logo-text { font-size: 18px; font-weight: 700; color: #00B96B; }

.nav-menu {
  border-right: none !important;
  flex: 1;
  padding: 4px 0;
}

.nav-menu :deep(.el-menu-item) {
  margin: 2px 8px;
  border-radius: 6px;
  height: 44px;
  line-height: 44px;
}

.nav-menu :deep(.el-menu-item.is-active) {
  background: #e8f5e9 !important;
  font-weight: 600;
}

/* ==================== 顶部栏 ==================== */
.topbar {
  background: #fff;
  border-bottom: 1px solid #e8eaed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}

.topbar-left { display: flex; align-items: center; }
.topbar-title { font-size: 15px; font-weight: 600; color: #18191c; }

.topbar-right { display: flex; align-items: center; gap: 16px; }

.msg-badge-wrap :deep(.el-badge__content) {
  font-size: 10px;
}

.user-area {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 8px;
  transition: background 0.15s;
}

.user-area:hover { background: #f1f2f3; }

.user-name { font-size: 13px; color: #61666d; }

/* ==================== 内容区 ==================== */
.content-area {
  --el-main-padding: 0;
  overflow-y: auto;
  overflow-x: hidden;
  background: #f4f5f7;
}

.full-page-shell {
  height: 100vh;
  overflow: auto;
  background: #f4f5f7;
}
</style>
