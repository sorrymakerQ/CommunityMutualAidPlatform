<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getOrderList, getUnreadCount } from '@/api/order'
import { getMyHelps } from '@/api/help'
import { useConfirm } from '@/utils/confirm'
import { regionName } from '@/data/regions'

const { confirm } = useConfirm()
const router = useRouter()
const userStore = useUserStore()
const userInfo = computed(() => userStore.userInfo)
const isLoggedIn = computed(() => userStore.isLoggedIn)
const isAdmin = computed(() => userStore.isAdmin)

const unread = ref(0)
const pubCount = ref(0)
const accCount = ref(0)
const activeTab = ref<'publish' | 'help'>('publish')
const orders = ref<any[]>([])
const loading = ref(false)

const helpStatusMap: Record<number, string> = { 1: '招募中', 2: '已满员', 3: '已完成', 4: '已取消' }
const orderStatusMap: Record<number, string> = { 1: '已接单', 2: '进行中', 3: '已完成', 4: '已取消', 5: '已评价' }

/** 状态数字 → el-tag type */
function statusTagType(s: number): 'warning' | 'primary' | 'success' | 'info' | 'danger' {
  const map: Record<number, 'warning' | 'primary' | 'success' | 'info' | 'danger'> = {
    1: 'warning', 2: 'primary', 3: 'success', 4: 'info', 5: 'danger'
  }
  return map[s] || 'info'
}

/** 头像兜底（与 App.vue / OrderDetail.vue 保持一致的静态资源） */
function defaultAvatar(_name?: string): string {
  return '/default-avatar.svg'
}

/** 时间戳 → 相对时间（口径同 Home.vue） */
function fmtTime(d?: string): string {
  if (!d) return ''
  const diff = Date.now() - new Date(d).getTime()
  const m = Math.floor(diff / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return `${m}分钟前`
  const h = Math.floor(diff / 3600000)
  if (h < 24) return `${h}小时前`
  return `${Math.floor(diff / 86400000)}天前`
}

onMounted(async () => {
  if (!isLoggedIn.value) return
  const [r1, r2, r3] = await Promise.allSettled([
    getUnreadCount(),
    getMyHelps({ page: 1, size: 1 }),
    getOrderList({ page: 1, size: 1, role: 'helper' })
  ])
  unread.value = (r1 as any).value?.data?.count || 0
  pubCount.value = (r2 as any).value?.data?.total || 0
  accCount.value = (r3 as any).value?.data?.total || 0
  loadOrders()
})

async function loadOrders() {
  loading.value = true
  try {
    if (activeTab.value === 'publish') {
      const res = await getMyHelps({ page: 1, size: 20 })
      orders.value = (res.data?.list || []).map((h: any) => ({ ...h, _type: 'help' }))
    } else {
      const res = await getOrderList({ page: 1, size: 20, role: 'helper' })
      orders.value = (res.data?.list || []).map((o: any) => ({ ...o, _type: 'order' }))
    }
  } catch {
    orders.value = []
  } finally {
    loading.value = false
  }
}

function onTabChange() {
  loadOrders()
}

async function logout() {
  if (await confirm('确定退出登录？')) {
    userStore.logout()
    router.push('/login')
  }
}

function goDetail(row: any) {
  if (row._type === 'help') {
    router.push(`/help/${row.id}`)
  } else {
    router.push(`/order/${row.id}`)
  }
}
</script>

<template>
  <!-- 未登录：官方 el-result 引导 -->
  <div class="profile" v-if="!isLoggedIn">
    <el-result
      icon="info"
      title="登录后查看个人中心"
      sub-title="查看求助记录、管理订单、编辑资料"
    >
      <template #extra>
        <el-button type="primary" size="large" @click="router.push('/login')">
          立即登录
        </el-button>
      </template>
    </el-result>
  </div>

  <!-- 已登录 -->
  <div class="profile" v-else-if="userInfo">
    <!-- 顶部横幅 -->
    <div class="banner">
      <div class="banner-inner">
        <img :src="userInfo.avatar || '/default-avatar.svg'" class="banner-avatar" />
        <div class="banner-info">
          <h1 class="banner-name">
            {{ userInfo.nickname }}
            <el-tag v-if="isAdmin" type="warning" effect="dark" size="small" round>
              管理员
            </el-tag>
          </h1>
          <p class="banner-bio">{{ userInfo.intro || '还没有填写简介...' }}</p>
          <div class="banner-meta">
            <span>📱 {{ userInfo.phone }}</span>
            <span>📍 {{ regionName(userInfo.addressId) || '未设置地址' }}</span>
          </div>
        </div>
        <div class="banner-actions">
          <el-button plain @click="router.push('/profile/edit')" class="banner-edit">
            <el-icon><Edit /></el-icon>
            <span style="margin-left: 4px;">编辑资料</span>
          </el-button>
          <el-button type="danger" plain @click="logout">
            <el-icon><SwitchButton /></el-icon>
            <span style="margin-left: 4px;">退出登录</span>
          </el-button>
        </div>
      </div>
    </div>

    <!-- 主体双栏 -->
    <div class="profile-body">
      <!-- 左侧统计 + 菜单 -->
      <div class="side-panel">
        <!-- 统计卡 -->
        <div class="side-card stats-card">
          <div class="side-stat" @click="router.push('/orders?role=publisher')">
            <span class="ss-num">{{ pubCount }}</span>
            <span class="ss-label">我的求助</span>
          </div>
          <div class="side-stat" @click="router.push('/orders?role=helper')">
            <span class="ss-num">{{ accCount }}</span>
            <span class="ss-label">我的接单</span>
          </div>
          <div class="side-stat">
            <span class="ss-num green">{{ userInfo.balance ?? 0 }}</span>
            <span class="ss-label">余额(元)</span>
          </div>
          <div class="side-stat">
            <span class="ss-num green">{{ userInfo.credit || 0 }}</span>
            <span class="ss-label">信用分</span>
          </div>
          <div class="side-stat">
            <span class="ss-num">{{ userInfo.helpCount || 0 }}</span>
            <span class="ss-label">帮助次数</span>
          </div>
        </div>

        <!-- 快捷菜单 -->
        <div class="side-card menu-card">
          <div class="menu-item" @click="router.push('/messages')">
            <span>💬 消息中心</span>
            <el-badge v-if="unread" :value="unread" :max="99" class="menu-badge" />
          </div>
          <div class="menu-item" @click="router.push('/profile/edit')">
            <span>✏️ 编辑资料</span>
          </div>
        </div>
      </div>

      <!-- 右侧订单列表 -->
      <div class="main-panel">
        <el-tabs v-model="activeTab" class="panel-tabs" @tab-change="onTabChange">
          <el-tab-pane label="我发布的" name="publish" />
          <el-tab-pane label="我接的单" name="help" />
        </el-tabs>

        <div class="table-wrapper" v-loading="loading" element-loading-text="加载中...">
          <!-- 我发布的（求助表） -->
          <el-table
            v-if="activeTab === 'publish' && orders.length"
            :data="orders"
            :header-cell-style="{ background: '#f6f7f8', color: '#909399', fontSize: '12px', fontWeight: '600' }"
            row-key="id"
            @row-click="goDetail"
          >
            <el-table-column prop="id" label="ID" width="70" class-name="col-id" />
            <el-table-column prop="title" label="标题" show-overflow-tooltip min-width="200" />
            <el-table-column label="招募" width="110">
              <template #default="{ row }">
                <span v-if="row.helperNum > 1" class="recruit-text">
                  {{ row.acceptedNum || 0 }}/{{ row.helperNum }} 人
                </span>
                <span v-else class="dim">-</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)" size="small" effect="light">
                  {{ helpStatusMap[row.status] }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="悬赏" width="90">
              <template #default="{ row }">
                <span v-if="row.reward > 0" class="reward-text">¥{{ row.reward }}</span>
                <span v-else class="dim">-</span>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="110">
              <template #default="{ row }">
                <span class="dim">{{ row.createTime?.slice(0, 10) }}</span>
              </template>
            </el-table-column>
          </el-table>

          <!-- 我接的单（卡片列表） -->
          <div v-else-if="activeTab === 'help' && orders.length" class="order-cards">
            <div
              v-for="order in orders"
              :key="order.id"
              class="order-card"
              @click="goDetail(order)"
            >
              <img
                :src="order.otherAvatar || defaultAvatar(order.otherName)"
                class="order-avatar"
                alt=""
                @error="($event.target as HTMLImageElement).src = defaultAvatar(order.otherName)"
              />
              <div class="order-main">
                <div class="order-title">{{ order.helpTitle || `求助 #${order.helpId}` }}</div>
                <div class="order-sub">
                  <span class="order-publisher">来自 {{ order.otherName || '邻居' }}</span>
                  <span class="order-time">接单于 {{ fmtTime(order.acceptTime) }}</span>
                </div>
              </div>
              <div class="order-side">
                <div class="order-reward">
                  <span v-if="order.reward > 0">¥{{ order.reward }}</span>
                  <span v-else class="dim">公益单</span>
                </div>
                <el-tag :type="statusTagType(order.status)" size="small" effect="light">
                  {{ orderStatusMap[order.status] }}
                </el-tag>
              </div>
            </div>
          </div>

          <!-- 空态 -->
          <el-empty
            v-else-if="!loading"
            description="暂无记录"
            :image-size="100"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.profile { display: flex; flex-direction: column; height: 100%; }

/* 未登录引导：撑满可用高度 */
.profile > :deep(.el-result) {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

/* ==================== 顶部横幅 ==================== */
.banner {
  background: linear-gradient(135deg, #00B96B 0%, #00a35a 50%, #2e7d32 100%);
  flex-shrink: 0;
}

.banner-inner {
  display: flex;
  align-items: center;
  gap: 28px;
  padding: 32px 36px;
}

.banner-avatar {
  width: 88px;
  height: 88px;
  border-radius: 50%;
  border: 3px solid rgba(255, 255, 255, 0.5);
  object-fit: cover;
  flex-shrink: 0;
  background: #fff;
}

.banner-info { flex: 1; min-width: 0; }

.banner-name {
  font-size: 24px;
  font-weight: 700;
  color: #fff;
  margin-bottom: 4px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.banner-bio {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.85);
  margin-bottom: 8px;
}

.banner-meta {
  display: flex;
  gap: 24px;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.75);
}

.banner-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex-shrink: 0;
}

.banner-edit{
  margin-left: 12px;
}

/* 横幅按钮：定制成白底半透明 */
.banner-actions :deep(.el-button) {
  background: rgba(255, 255, 255, 0.9);
  border-color: transparent;
  color: #303133;
}
.banner-actions :deep(.el-button:hover) {
  background: #fff;
}
.banner-actions :deep(.el-button--danger.is-plain) {
  background: rgba(255, 255, 255, 0.15);
  border-color: rgba(255, 255, 255, 0.3);
  color: #fff;
}
.banner-actions :deep(.el-button--danger.is-plain:hover) {
  background: rgba(255, 77, 79, 0.9);
  border-color: transparent;
  color: #fff;
}

/* ==================== 主体双栏 ==================== */
.profile-body {
  flex: 1;
  display: flex;
  gap: 0;
  overflow: hidden;
}

/* 左侧面板 */
.side-panel {
  width: 260px;
  flex-shrink: 0;
  padding: 20px 16px;
  overflow-y: auto;
  border-right: 1px solid #e8eaed;
  background: #fff;
}

.side-card { margin-bottom: 16px; }

/* 统计卡：2x2 网格 */
.stats-card {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  background: #f6f7f8;
  border-radius: 8px;
  padding: 4px;
}

.side-stat {
  text-align: center;
  padding: 14px 8px;
  cursor: pointer;
  border-radius: 6px;
  transition: background 0.15s;
}
.side-stat:hover { background: #fff; }

.ss-num {
  display: block;
  font-size: 24px;
  font-weight: 700;
  color: #18191c;
}
.ss-num.green { color: #00B96B; }
.ss-label { font-size: 11px; color: #9499a0; }

/* 快捷菜单 */
.menu-card {
  background: #f6f7f8;
  border-radius: 8px;
  overflow: hidden;
}

.menu-item {
  padding: 14px 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: #18191c;
  cursor: pointer;
  transition: background 0.15s;
}
.menu-item:hover { background: #fff; }
.menu-badge :deep(.el-badge__content) {
  transform: translate(0, 0);
  position: static;
}

/* ==================== 右侧主面板 ==================== */
.main-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #f4f5f7;
}

.panel-tabs {
  background: #fff;
  padding: 0 24px;
  flex-shrink: 0;
  margin-bottom: 0;
}
.panel-tabs :deep(.el-tabs__item.is-active) {
  color: #00B96B;
  font-weight: 600;
}
.panel-tabs :deep(.el-tabs__active-bar) {
  background: #00B96B;
}
.panel-tabs :deep(.el-tabs__item:hover) {
  color: #00B96B;
}
.panel-tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}

.table-wrapper {
  flex: 1;
  overflow-y: auto;
  padding: 16px 24px 24px;
}

/* 表格样式：跟 MyOrders 保持一致 */
.table-wrapper :deep(.el-table) {
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}
.table-wrapper :deep(.el-table__row) {
  cursor: pointer;
}
.table-wrapper :deep(.col-id) {
  color: #c0c4cc;
}

.reward-text { color: #e65100; font-weight: 500; }
.dim { color: #c0c4cc; font-size: 12px; }

/* ==================== 我接的单：卡片列表 ==================== */
.order-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.order-card {
  display: flex;
  align-items: center;
  gap: 14px;
  background: #fff;
  border-radius: 10px;
  padding: 16px 18px;
  cursor: pointer;
  border: 1px solid #f0f1f2;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
  transition: box-shadow 0.2s, transform 0.2s, border-color 0.2s;
}
.order-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.08);
  border-color: #d9f2e5;
}

.order-avatar {
  width: 46px;
  height: 46px;
  border-radius: 50%;
  object-fit: cover;
  background: #f6f7f8;
  flex-shrink: 0;
}

.order-main { flex: 1; min-width: 0; }

.order-title {
  font-size: 14px;
  font-weight: 600;
  color: #18191c;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 5px;
}

.order-sub {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: #9499a0;
}

.order-publisher { color: #5e6d66; }
.order-time::before { content: '· '; color: #d5d9dc; }

.order-side {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}

.order-reward {
  color: #e65100;
  font-weight: 600;
  font-size: 15px;
  min-width: 56px;
  text-align: right;
}

/* 我发布的表格：招募进度列 */
.recruit-text {
  color: #00a35a;
  font-weight: 500;
  font-size: 13px;
}
</style>
