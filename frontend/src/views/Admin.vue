<script setup lang="ts">
/**
 * PC 端管理后台
 * el-container 布局 + el-menu 侧边导航 + el-table 数据展示
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getDashboardStats } from '@/api/order'
import { getAdminUserList, toggleUserStatus, updateUserRole, getRoleList, kickoutUser } from '@/api/user'
import { getAdminHelpList, deleteHelp, updateAdminHelpStatus } from '@/api/help'
import type { RoleInfo } from '@/types'
import { useConfirm } from '@/utils/confirm'

const { confirm } = useConfirm()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const activeMenu = ref<'dashboard' | 'users' | 'helps'>('dashboard')

// ==================== 数据概览 ====================
const stats = reactive({
  userCount: 0,
  helpCount: 0,
  pendingHelpCount: 0,
  finishedOrderCount: 0
})

async function loadStats() {
  loading.value = true
  try {
    const res = await getDashboardStats()
    if (res.data) Object.assign(stats, res.data)
  } catch { /* 拦截器已提示 */ }
  finally { loading.value = false }
}

// ==================== 用户管理 ====================
const users = ref<any[]>([])
const userTotal = ref(0)
const userKeyword = ref('')

async function loadUsers() {
  loading.value = true
  try {
    const res = await getAdminUserList({ page: 1, size: 200, keyword: userKeyword.value || undefined })
    if (res.data) {
      users.value = res.data.list || []
      userTotal.value = res.data.total || users.value.length
    }
  } catch {
    users.value = []
  } finally {
    loading.value = false
  }
}

async function handleToggleStatus(user: any) {
  const action = user.status !== 0 ? '禁用' : '启用'
  if (!await confirm(`${action}用户`, `确定要${action}用户「${user.nickname}」吗？`)) return
  const newStatus = user.status !== 0 ? 0 : 1
  try {
    await toggleUserStatus(user.id, newStatus)
    user.status = newStatus
    ElMessage.success(`${action}成功`)
  } catch { /* 拦截器已提示 */ }
}

/** 踢用户下线（该账号所有设备需重新登录） */
async function handleKickout(user: any) {
  if (!await confirm('踢下线', `确定要把用户「${user.nickname}」踢下线吗？该用户所有设备都需要重新登录。`)) return
  try {
    await kickoutUser(user.id)
    ElMessage.success('已踢下线')
  } catch { /* 拦截器已提示 */ }
}

// ==================== 角色管理（RBAC） ====================
const roles = ref<RoleInfo[]>([])
const roleDialogVisible = ref(false)
const roleDialogUser = ref<any>(null)
const selectedRoleId = ref<number>(2)
const roleSaving = ref(false)

/** 角色ID → 名称映射（表格展示用） */
const roleNameMap = computed(() => {
  const m: Record<number, string> = {}
  roles.value.forEach(r => { m[r.id] = r.name })
  return m
})

async function loadRoles() {
  try {
    const res = await getRoleList()
    if (res.data) roles.value = res.data
  } catch { /* 拦截器已提示 */ }
}

function openRoleDialog(user: any) {
  roleDialogUser.value = user
  selectedRoleId.value = user.roleId || 2
  roleDialogVisible.value = true
}

async function saveRole() {
  roleSaving.value = true
  try {
    await updateUserRole(roleDialogUser.value.id, selectedRoleId.value)
    roleDialogUser.value.roleId = selectedRoleId.value
    ElMessage.success('角色已更新')
    roleDialogVisible.value = false
  } catch { /* 拦截器已提示 */ }
  finally { roleSaving.value = false }
}

// ==================== 求助管理 ====================
const helps = ref<any[]>([])
const helpTotal = ref(0)
const helpStatusFilter = ref<number | undefined>(undefined)

async function loadHelps() {
  loading.value = true
  try {
    const res = await getAdminHelpList({ page: 1, size: 200, status: helpStatusFilter.value })
    if (res.data) {
      helps.value = res.data.list || []
      helpTotal.value = res.data.total || helps.value.length
    }
  } catch {
    helps.value = []
  } finally {
    loading.value = false
  }
}

async function handleDeleteHelp(help: any) {
  if (!await confirm('删除求助', `确定要删除求助「${help.title}」吗？此操作不可恢复。`)) return
  try {
    await deleteHelp(help.id)
    ElMessage.success('已删除')
    helps.value = helps.value.filter(h => h.id !== help.id)
    helpTotal.value--
  } catch { /* 拦截器已提示 */ }
}

/** 下架求助：状态置为 4（已取消），首页不再展示 */
async function handleHelpOffline(help: any) {
  if (!await confirm('下架求助', `确定要下架求助「${help.title}」吗？下架后不会再出现在首页。`)) return
  try {
    await updateAdminHelpStatus(help.id, 4)
    help.status = 4
    ElMessage.success('已下架')
  } catch { /* 拦截器已提示 */ }
}

function viewHelpDetail(id: number) {
  router.push(`/help/${id}`)
}

// ==================== 菜单切换 ====================
function onMenuSelect(index: string) {
  activeMenu.value = index as 'dashboard' | 'users' | 'helps'
  if (activeMenu.value === 'dashboard') loadStats()
  else if (activeMenu.value === 'users') loadUsers()
  else if (activeMenu.value === 'helps') loadHelps()
}

// ==================== 格式化 ====================
function formatDate(dateStr: string) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleDateString('zh-CN')
}

const statusHelpMap: Record<number, string> = { 0: '待支付', 1: '招募中', 2: '已满员', 3: '已完成', 4: '已取消' }

/** 求助状态 → el-tag type */
function helpStatusTagType(s: number): 'warning' | 'primary' | 'success' | 'info' {
  const map: Record<number, 'warning' | 'primary' | 'success' | 'info'> = {
    1: 'warning', 2: 'primary', 3: 'success', 4: 'info'
  }
  return map[s] || 'info'
}

// ==================== 初始化 ====================
onMounted(() => {
  if (!userStore.isAdmin) {
    router.replace('/')
    return
  }
  loadStats()
  loadRoles()
})
</script>

<template>
  <!-- Element Plus 官方三段式布局 -->
  <el-container class="admin-layout">
    <!-- ========== 左侧侧边栏 ========== -->
    <el-aside width="220px" class="admin-aside">
      <div class="sidebar-logo">
        <span class="logo-icon">🏘️</span>
        <span class="logo-text">社区互助平台管理</span>
      </div>

      <el-menu
        :default-active="activeMenu"
        class="sidebar-menu"
        background-color="#001529"
        text-color="rgba(255,255,255,0.7)"
        active-text-color="#fff"
        @select="onMenuSelect"
      >
        <el-menu-item index="dashboard">
          <el-icon><DataAnalysis /></el-icon>
          <span>数据概览</span>
        </el-menu-item>
        <el-menu-item index="users">
          <el-icon><User /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
        <el-menu-item index="helps">
          <el-icon><Document /></el-icon>
          <span>求助管理</span>
        </el-menu-item>
      </el-menu>

      <div class="sidebar-footer">
        <el-button link class="back-btn" @click="router.push('/')">
          <el-icon><ArrowLeft /></el-icon>
          返回首页
        </el-button>
      </div>
    </el-aside>

    <!-- ========== 右侧主内容 ========== -->
    <el-container>
      <!-- 顶部标题栏 -->
      <el-header height="56px" class="admin-header">
        <h1 class="header-title">
          <template v-if="activeMenu === 'dashboard'">数据概览</template>
          <template v-else-if="activeMenu === 'users'">用户管理</template>
          <template v-else>求助管理</template>
        </h1>
        <div class="header-user">
          <el-icon><Avatar /></el-icon>
          <span>管理员：{{ userStore.nickname }}</span>
        </div>
      </el-header>

      <!-- 内容主体 -->
      <el-main class="admin-main" v-loading="loading" element-loading-text="加载中...">
        <!-- ========== Tab 1: 数据概览 ========== -->
        <template v-if="activeMenu === 'dashboard' && !loading">
          <div class="stats-row">
            <div class="stat-card" style="--card-color: #4285F4">
              <span class="stat-num">{{ stats.userCount }}</span>
              <span class="stat-label">注册用户</span>
            </div>
            <div class="stat-card" style="--card-color: #00B96B">
              <span class="stat-num">{{ stats.helpCount }}</span>
              <span class="stat-label">求助总数</span>
            </div>
            <div class="stat-card" style="--card-color: #FF9500">
              <span class="stat-num">{{ stats.pendingHelpCount }}</span>
              <span class="stat-label">待处理求助</span>
            </div>
            <div class="stat-card" style="--card-color: #9C27B0">
              <span class="stat-num">{{ stats.finishedOrderCount }}</span>
              <span class="stat-label">已完成订单</span>
            </div>
          </div>
        </template>

        <!-- ========== Tab 2: 用户管理 ========== -->
        <template v-if="activeMenu === 'users'">
          <div class="table-toolbar">
            <div class="toolbar-left">
              <el-input
                v-model="userKeyword"
                placeholder="搜索用户昵称或手机号..."
                clearable
                style="width: 260px;"
                @keyup.enter="loadUsers"
                @clear="loadUsers"
              >
                <template #prefix>
                  <el-icon><Search /></el-icon>
                </template>
              </el-input>
              <el-button type="primary" @click="loadUsers">搜索</el-button>
            </div>
            <span class="toolbar-count">共 {{ userTotal }} 个用户</span>
          </div>

          <el-table
            v-if="users.length > 0"
            :data="users"
            class="admin-table"
            :header-cell-style="{ background: '#fafafa', color: '#606266', fontWeight: '600' }"
          >
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="nickname" label="昵称" min-width="120" show-overflow-tooltip />
            <el-table-column prop="phone" label="手机号" width="130" />
            <el-table-column prop="credit" label="信用分" width="90">
              <template #default="{ row }">{{ row.credit || 0 }}</template>
            </el-table-column>
            <el-table-column prop="helpCount" label="帮助次数" width="100">
              <template #default="{ row }">{{ row.helpCount || 0 }}</template>
            </el-table-column>
            <el-table-column label="注册时间" width="130">
              <template #default="{ row }">
                <span class="dim">{{ formatDate(row.createTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="角色" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.roleId === 1 ? 'warning' : 'info'">
                  {{ roleNameMap[row.roleId] || '未知' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status !== 0 ? 'success' : 'danger'" size="small">
                  {{ row.status !== 0 ? '正常' : '已禁用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="240" fixed="right">
              <template #default="{ row }">
                <template v-if="row.roleId !== 1">
                  <el-button
                    :type="row.status !== 0 ? 'danger' : 'success'"
                    size="small"
                    plain
                    @click="handleToggleStatus(row)"
                  >
                    {{ row.status !== 0 ? '禁用' : '启用' }}
                  </el-button>
                  <el-button
                    size="small"
                    @click="openRoleDialog(row)"
                  >
                    角色
                  </el-button>
                </template>
                <el-button
                  v-if="row.id !== (userStore.userInfo?.id || 0)"
                  size="small"
                  type="warning"
                  plain
                  @click="handleKickout(row)"
                >
                  踢下线
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-empty v-else-if="!loading" description="暂无用户数据" />

          <!-- 角色分配弹窗（RBAC） -->
          <el-dialog
            v-model="roleDialogVisible"
            title="分配角色"
            width="420px"
            :close-on-click-modal="false"
          >
            <template v-if="roleDialogUser">
              <p class="perm-dialog-hint">
                为 <strong>{{ roleDialogUser.nickname }}</strong> 分配角色：
              </p>
              <el-select v-model="selectedRoleId" placeholder="选择角色" style="width: 100%;">
                <el-option
                  v-for="r in roles"
                  :key="r.id"
                  :value="r.id"
                  :label="`${r.name}（${r.description || r.code}）`"
                />
              </el-select>
            </template>
            <template #footer>
              <el-button @click="roleDialogVisible = false" :disabled="roleSaving">取消</el-button>
              <el-button type="primary" @click="saveRole" :loading="roleSaving">
                保存
              </el-button>
            </template>
          </el-dialog>
        </template>

        <!-- ========== Tab 3: 求助管理 ========== -->
        <template v-if="activeMenu === 'helps'">
          <div class="table-toolbar">
            <el-radio-group
              v-model="helpStatusFilter"
              @change="loadHelps"
            >
              <el-radio-button :value="undefined">全部</el-radio-button>
              <el-radio-button
                v-for="(label, val) in statusHelpMap"
                :key="val"
                :value="Number(val)"
              >
                {{ label }}
              </el-radio-button>
            </el-radio-group>
            <span class="toolbar-count">共 {{ helpTotal }} 条</span>
          </div>

          <el-table
            v-if="helps.length > 0"
            :data="helps"
            class="admin-table"
            :header-cell-style="{ background: '#fafafa', color: '#606266', fontWeight: '600' }"
          >
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
            <el-table-column label="发布者" width="120" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.publisherName || '用户' + row.userId }}
              </template>
            </el-table-column>
            <el-table-column prop="categoryName" label="分类" width="100">
              <template #default="{ row }">{{ row.categoryName || '-' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="helpStatusTagType(row.status)" size="small">
                  {{ statusHelpMap[row.status] || '未知' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发布时间" width="130">
              <template #default="{ row }">
                <span class="dim">{{ formatDate(row.createTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="230" fixed="right">
              <template #default="{ row }">
                <el-button size="small" @click="viewHelpDetail(row.id)">查看</el-button>
                <el-button
                  v-if="row.status !== 4"
                  size="small"
                  type="warning"
                  plain
                  @click="handleHelpOffline(row)"
                >
                  下架
                </el-button>
                <el-button size="small" type="danger" plain @click="handleDeleteHelp(row)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-empty v-else-if="!loading" description="暂无求助数据" />
        </template>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-layout {
  height: 100vh;
}

/* ==================== 左侧侧边栏 ==================== */
.admin-aside {
  background: #001529;
  color: #fff;
  display: flex;
  flex-direction: column;
}

.sidebar-logo {
  height: 56px;
  padding: 0 20px;
  display: flex;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}
.logo-icon { font-size: 24px; }
.logo-text { font-size: 16px; font-weight: 700; }

/* 菜单 */
.sidebar-menu {
  flex: 1;
  border-right: none;
}
.sidebar-menu :deep(.el-menu-item) {
  border-left: 3px solid transparent;
}
.sidebar-menu :deep(.el-menu-item.is-active) {
  background-color: #1890ff !important;
  border-left-color: #1890ff;
}
.sidebar-menu :deep(.el-menu-item:hover) {
  background-color: rgba(255, 255, 255, 0.08) !important;
}

.sidebar-footer {
  padding: 12px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
}
.back-btn {
  color: rgba(255, 255, 255, 0.7) !important;
}
.back-btn:hover {
  color: #fff !important;
}

/* ==================== 顶部标题栏 ==================== */
.admin-header {
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.05);
}

.header-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  margin: 0;
}

.header-user {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #909399;
}

/* ==================== 主内容 ==================== */
.admin-main {
  background: #f0f2f5;
  padding: 24px;
}

/* 统计卡片 */
.stats-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
}

.stat-card {
  background: #fff;
  border-radius: 8px;
  padding: 28px 24px;
  text-align: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  border-top: 3px solid var(--card-color);
  transition: transform 0.2s, box-shadow 0.2s;
}
.stat-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.1);
}

.stat-num {
  font-size: 36px;
  font-weight: 700;
  color: var(--card-color);
  display: block;
}

.stat-label {
  font-size: 14px;
  color: #909399;
  margin-top: 8px;
}

/* 工具栏 */
.table-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  gap: 16px;
}

.toolbar-left {
  display: flex;
  gap: 8px;
  align-items: center;
}

.toolbar-count {
  font-size: 13px;
  color: #909399;
}

/* 表格 */
.admin-table {
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.dim {
  color: #909399;
  font-size: 12px;
}

/* 权限弹窗 */
.perm-dialog-hint {
  margin-bottom: 16px;
  color: #606266;
  font-size: 14px;
}

.perm-checkbox-group {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.perm-checkbox-group :deep(.el-checkbox) {
  margin-right: 0;
  padding: 10px 14px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  transition: border-color 0.2s;
}

.perm-checkbox-group :deep(.el-checkbox:hover) {
  border-color: #1890ff;
}

.perm-code-tag {
  color: #909399;
  font-size: 12px;
  margin-left: 6px;
}

/* 响应式 */
@media (max-width: 1024px) {
  .stats-row { grid-template-columns: repeat(2, 1fr); }
}
</style>
