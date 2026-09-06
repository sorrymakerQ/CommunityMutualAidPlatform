<script setup lang="ts">
/**
 * 求助详情页面
 * 展示求助完整信息，支持接单、取消、查看订单等操作
 */
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getHelpDetail, cancelHelp, payHelp } from '@/api/help'
import { acceptOrder, getApplyList, approveApply, rejectApply } from '@/api/order'
import { useUserStore } from '@/stores/user'
import { useConfirm } from '@/utils/confirm'
import NavBar from '@/components/NavBar.vue'
import type { HelpRequest } from '@/types'

const { confirm } = useConfirm()
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

// ========== 状态 ==========
const helpInfo = ref<HelpRequest | null>(null)
const pageLoading = ref<boolean>(true)
const actionLoading = ref<boolean>(false)
const paying = ref<boolean>(false)
const loadError = ref<boolean>(false)

// ========== 计算属性 ==========

/** 求助ID */
const helpId = computed(() => Number(route.params.id))

/** 是否是自己的求助 */
const isOwner = computed(() => {
  if (!helpInfo.value || !userStore.userInfo) return false
  return helpInfo.value.userId === userStore.userInfo.id
})

/** 是否显示接单按钮（待接单且不是自己的） */
const showAcceptBtn = computed(() => {
  if (!helpInfo.value) return false
  return helpInfo.value.status === 1 && !isOwner.value
})

/** 是否可以取消（待支付或招募中且是自己的） */
const showCancelBtn = computed(() => {
  if (!helpInfo.value) return false
  return (helpInfo.value.status === 0 || helpInfo.value.status === 1) && isOwner.value
})

/** 待支付（发布者）：显示支付按钮 */
const showPayBtn = computed(() => {
  return helpInfo.value?.status === 0 && isOwner.value
})

/** 当前订单ID（发布者或接单者均可查看） */
const myOrderId = computed(() => {
  if (!helpInfo.value || !userStore.userInfo) return null
  if (helpInfo.value.currentHelperId === userStore.userInfo.id) {
    return helpInfo.value.currentOrderId
  }
  if (isOwner.value && helpInfo.value.currentOrderId) {
    return helpInfo.value.currentOrderId
  }
  return null
})

/** 是否显示查看订单按钮 */
const showViewOrder = computed(() => {
  return helpInfo.value?.status === 2 && myOrderId.value !== null
})

/** 是否显示已被接单提示 */
const showTaken = computed(() => {
  return helpInfo.value?.status === 2 && myOrderId.value === null && !isOwner.value
})

/** 是否显示私信按钮 */
const showChat = computed(() => {
  if (!helpInfo.value || !userStore.isLoggedIn) return false
  return !isOwner.value && helpInfo.value.status < 3
})

/** 状态文字 */
const statusText = computed(() => {
  const map: Record<number, string> = { 0: '待支付', 1: '招募中', 2: '已满员', 3: '已完成', 4: '已取消' }
  return helpInfo.value ? (map[helpInfo.value.status] || '未知') : ''
})

/** 状态对应的 el-tag type */
const statusTagType = computed<'warning' | 'primary' | 'success' | 'info'>(() => {
  const map: Record<number, 'warning' | 'primary' | 'success' | 'info'> = {
    0: 'warning',
    1: 'warning',
    2: 'primary',
    3: 'success',
    4: 'info'
  }
  return helpInfo.value ? (map[helpInfo.value.status] || 'info') : 'info'
})

// ========== 生命周期 ==========

onMounted(async () => {
  await fetchDetail()
})

// ========== 方法 ==========

/** 获取求助详情 */
async function fetchDetail(): Promise<void> {
  pageLoading.value = true
  loadError.value = false
  try {
    const res = await getHelpDetail(helpId.value)
    if (res.data) {
      helpInfo.value = res.data
    } else {
      loadError.value = true
    }
  } catch {
    loadError.value = true
    /* 拦截器已提示 */
  } finally {
    pageLoading.value = false
  }
}

/** 接单（老板审批制：提交申请） */
async function handleAccept(): Promise<void> {
  if (actionLoading.value) return

  if (!userStore.isLoggedIn) {
    const ok = await confirm('需要登录', '接单需要先登录账号，是否前往登录？', {
      confirmText: '去登录',
      cancelText: '稍后'
    })
    if (ok) router.push('/login')
    return
  }

  actionLoading.value = true
  try {
    await acceptOrder(helpId.value)
    ElMessage.success('申请已提交，等待发布者确认！')
    await fetchDetail()
  } catch {
    /* 拦截器已提示 */
  } finally {
    actionLoading.value = false
  }
}

// ========== 老板审批（发布者选人） ==========

const applyDialogVisible = ref(false)
const applyList = ref<any[]>([])
const applyLoading = ref(false)
const approvingId = ref<number | null>(null)

/** 发布者且招募中：显示"申请管理"按钮 */
const showApplyManage = computed(() => {
  return helpInfo.value?.status === 1 && isOwner.value
})

async function openApplyDialog(): Promise<void> {
  applyDialogVisible.value = true
  await loadApplies()
}

async function loadApplies(): Promise<void> {
  applyLoading.value = true
  try {
    const res = await getApplyList(helpId.value)
    if (res.data) {
      applyList.value = res.data.list || []
    }
  } catch {
    applyList.value = []
  } finally {
    applyLoading.value = false
  }
}

/** 同意申请：填写理由（可选）→ 生成订单 + 占名额 */
async function handleApprove(apply: any): Promise<void> {
  const reason = await promptReason('同意申请', `同意「${apply.helperName}」的申请？可填写留言（选填）`)
  if (reason === null) return  // 用户取消
  approvingId.value = apply.id
  try {
    await approveApply(apply.id, reason)
    ElMessage.success(`已同意「${apply.helperName}」的申请`)
    await loadApplies()
    await fetchDetail()
  } catch {
    /* 拦截器已提示 */
  } finally {
    approvingId.value = null
  }
}

/** 拒绝申请：填写理由（通知申请人） */
async function handleReject(apply: any): Promise<void> {
  const reason = await promptReason('拒绝申请', `拒绝「${apply.helperName}」的申请？建议填写理由（将通知对方）`, true)
  if (reason === null) return  // 用户取消
  approvingId.value = apply.id
  try {
    await rejectApply(apply.id, reason)
    ElMessage.success(`已拒绝「${apply.helperName}」的申请`)
    await loadApplies()
  } catch {
    /* 拦截器已提示 */
  } finally {
    approvingId.value = null
  }
}

/** 弹窗输入理由 */
async function promptReason(title: string, message: string, required = false): Promise<string | null> {
  try {
    const { value } = await ElMessageBox.prompt(message, title, {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: required ? '请填写理由（将通知申请人）' : '选填，将通知申请人',
      inputValidator: (v: string) => {
        if (required && (!v || !v.trim())) return '请填写理由'
        return true
      },
      inputValue: ''
    })
    return (value || '').trim()
  } catch {
    return null
  }
}

/** 支付求助（余额支付，支付成功后上首页） */
async function handlePay(): Promise<void> {
  if (paying.value) return
  paying.value = true
  try {
    await payHelp(helpId.value)
    ElMessage.success('支付成功，求助已发布到首页！')
    await fetchDetail()
  } catch {
    /* 拦截器已提示 */
  } finally {
    paying.value = false
  }
}

/** 取消求助 */
async function handleCancel(): Promise<void> {
  if (actionLoading.value) return
  if (!await confirm('取消求助', '确定要取消这个求助吗？已有人接单将自动取消订单。')) return

  actionLoading.value = true
  try {
    await cancelHelp(helpId.value)
    ElMessage.success('求助已取消')
    await fetchDetail()
  } catch {
    /* 拦截器已提示 */
  } finally {
    actionLoading.value = false
  }
}

/** 格式化时间 */
function formatTime(dateStr: string): string {
  if (!dateStr) return ''
  const diff = Date.now() - new Date(dateStr).getTime()
  const hours = Math.floor(diff / 3600000)
  if (hours < 1) return '刚刚'
  if (hours < 24) return `${hours}小时前`
  return `${Math.floor(diff / 86400000)}天前`
}

/** 格式化距离 */
function formatDistance(meters: number): string {
  if (!meters) return '未知'
  if (meters < 1000) return `${Math.round(meters)}m`
  return `${(meters / 1000).toFixed(1)}km`
}

/** 查看订单详情 */
function goToMyOrder(): void {
  if (myOrderId.value) {
    router.push(`/order/${myOrderId.value}`)
  }
}
</script>

<template>
  <div class="detail-page" v-loading="pageLoading" element-loading-text="加载中...">
    <!-- 顶部导航 -->
    <NavBar title="求助详情" :show-back="true" back-text="返回" />

    <!-- 加载失败 -->
    <el-result
      v-if="!pageLoading && loadError"
      icon="error"
      title="加载详情失败"
      sub-title="可能是网络问题，请稍后重试"
    >
      <template #extra>
        <el-button type="primary" @click="fetchDetail">重新加载</el-button>
      </template>
    </el-result>

    <!-- 详情内容 -->
    <div v-if="!pageLoading && !loadError && helpInfo" class="detail-wrapper">
      <div class="detail-container">
        <!-- ========== 左侧：主体内容 ========== -->
        <div class="detail-main">
          <!-- 标题与元信息 -->
          <div class="main-header">
            <h1 class="detail-title">{{ helpInfo.title }}</h1>
            <div class="header-meta">
              <el-tag v-if="helpInfo.urgent === 1" type="danger" effect="dark" size="small">紧急</el-tag>
              <el-tag :type="statusTagType" effect="light" size="small">{{ statusText }}</el-tag>
              <el-tag v-if="helpInfo.helperNum > 1" type="info" effect="plain" size="small">
                已接 {{ helpInfo.acceptedNum || 0 }}/{{ helpInfo.helperNum }} 人
              </el-tag>
              <span class="time-text">{{ formatTime(helpInfo.createTime) }}</span>
            </div>
          </div>

          <!-- 详细描述 -->
          <div class="section-card">
            <h3 class="section-title">详细描述</h3>
            <p class="description-text">{{ helpInfo.description }}</p>
          </div>

          <!-- 相关图片 -->
          <div v-if="helpInfo.images && helpInfo.images.length > 0" class="section-card">
            <h3 class="section-title">相关图片</h3>
            <div class="images-grid">
              <el-image
                v-for="(img, index) in helpInfo.images"
                :key="index"
                :src="img"
                :preview-src-list="helpInfo.images"
                :initial-index="index"
                fit="cover"
                class="detail-image"
                preview-teleported
              />
            </div>
          </div>
        </div>

        <!-- ========== 右侧：侧边栏 ========== -->
        <div class="detail-sidebar">
          <!-- 发布者信息 -->
          <div class="sidebar-card">
            <h3 class="sidebar-section-title">发布者</h3>
            <div class="publisher-info">
              <img
                class="publisher-avatar"
                :src="helpInfo.publisherAvatar || '/default-avatar.svg'"
                alt="发布者头像"
              />
              <div class="publisher-detail">
                <div class="publisher-name">{{ helpInfo.publisherName }}</div>
                <div class="publisher-help">
                  <span class="help-count">已帮助 {{ helpInfo.publisherHelpCount || 0 }} 次</span>
                </div>
              </div>
            </div>
            <div class="credit-row">
              <div class="credit-display">
                <span class="credit-score">{{ helpInfo.publisherCredit ?? 100 }}</span>
                <span class="credit-label">信用分</span>
              </div>
            </div>
          </div>

          <!-- 求助信息 -->
          <div class="sidebar-card">
            <h3 class="sidebar-section-title">求助信息</h3>
            <div class="info-list">
              <div class="info-item">
                <span class="info-label">分类</span>
                <el-tag type="primary" effect="light" size="small">{{ helpInfo.categoryName }}</el-tag>
              </div>
              <div class="info-item">
                <span class="info-label">地址</span>
                <span class="info-value">{{ helpInfo.address }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">距离</span>
                <span class="info-value distance-value">{{ formatDistance(helpInfo.distance) }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">酬劳</span>
                <span class="info-value reward-value">¥{{ helpInfo.reward.toFixed(2) }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">浏览</span>
                <span class="info-value">{{ helpInfo.viewCount }} 次</span>
              </div>
            </div>
          </div>

          <!-- 操作按钮 -->
          <div class="sidebar-card action-card">
            <!-- 待支付：发布者支付，他人提示 -->
            <div v-if="showPayBtn" class="btn-action">
              <el-button
                  type="warning"
                  size="large"
                  class="btn-accept"
                  :loading="paying"
                  @click="handlePay"
              >
                {{ paying ? '支付中...' : `去支付 ¥${helpInfo.totalReward ?? 0}` }}
              </el-button>
            </div>
            <div v-if="helpInfo.status === 0 && !isOwner" class="btn-hint btn-taken">
              该求助待支付，暂未发布
            </div>
            <div v-if="showApplyManage" class="btn-action">
              <el-button
                  type="primary"
                  plain
                  size="large"
                  class="btn-action"
                  @click="openApplyDialog"
              >
                申请管理（选人）
              </el-button>
            </div>
            <div class="btn-action">
              <el-button
                  v-if="showCancelBtn"
                  type="danger"
                  plain
                  size="large"
                  class="btn-action"
                  :loading="actionLoading"
                  @click="handleCancel"
              >
                取消求助
              </el-button>
            </div >
            <div class="btn-action">
              <el-button
                  v-if="showChat"
                  type="primary"
                  plain
                  size="large"
                  class="btn-action"
                  @click="router.push('/messages?chat=help:' + helpId)"
              >
                私信
              </el-button>
            </div>
            <div class="btn-action">
              <el-button
                  v-if="showAcceptBtn"
                  type="primary"
                  size="large"
                  class="btn-accept"
                  :loading="actionLoading"
                  @click="handleAccept"
              >
                {{ actionLoading ? '接单中...' : '我要接单' }}
              </el-button>
            </div>

            <div class="btn-action">
              <el-button
                  v-if="showViewOrder"
                  type="primary"
                  size="large"
                  class="btn-accept"
                  @click="goToMyOrder"
              >
                查看订单详情
              </el-button>
            </div>


            <div v-if="showTaken" class="btn-hint btn-taken">
              {{ isOwner ? '您的求助已招满' : '该求助已招满' }}
            </div>

            <div v-if="helpInfo.status >= 3" class="btn-hint btn-disabled">
              {{ helpInfo.status === 3 ? '该求助已完成' : '该求助已取消' }}
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- 申请管理弹窗（老板审批） -->
  <el-dialog
    v-model="applyDialogVisible"
    :title="`接单申请（已招 ${helpInfo?.acceptedNum ?? 0}/${helpInfo?.helperNum ?? 1} 人）`"
    width="480px"
    :close-on-click-modal="false"
  >
    <div v-loading="applyLoading" class="apply-list">
      <el-empty v-if="!applyLoading && applyList.length === 0" description="还没有邻居申请接单" :image-size="60" />
      <div v-for="apply in applyList" :key="apply.id" class="apply-item">
        <el-avatar :src="apply.helperAvatar" :size="40">{{ apply.helperName?.[0] }}</el-avatar>
        <div class="apply-info">
          <div class="apply-name">
            {{ apply.helperName }}
            <span v-if="apply.status === 1" class="apply-tag ok">已录用</span>
            <span v-else-if="apply.status === 2" class="apply-tag no">已拒绝</span>
          </div>
          <div class="apply-meta">信用分 {{ apply.helperCredit ?? 100 }} · 帮助 {{ apply.helperHelpCount ?? 0 }} 次</div>
          <div v-if="apply.handleReason" class="apply-reason">
            {{ apply.status === 1 ? '录用留言' : '拒绝理由' }}：{{ apply.handleReason }}
          </div>
        </div>
        <div v-if="apply.status === 0" class="apply-actions">
          <el-button
            type="success" size="small"
            :loading="approvingId === apply.id"
            @click="handleApprove(apply)"
          >同意</el-button>
          <el-button
            type="danger" size="small" plain
            :loading="approvingId === apply.id"
            @click="handleReject(apply)"
          >拒绝</el-button>
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
/* ========================================
   页面容器
   ======================================== */
.detail-page {
  min-height: 100%;
  background: #f5f5f5;
}

.detail-wrapper {
  display: flex;
  justify-content: center;
  padding: 24px 20px 40px;
}

.detail-container {
  display: flex;
  gap: 24px;
  width: 100%;
  max-width: 1100px;
}

/* ========================================
   左侧：主体内容
   ======================================== */
.detail-main {
  flex: 1;
  min-width: 0;
}

/* 标题区域 */
.main-header {
  background: #ffffff;
  border-radius: 10px;
  padding: 28px 28px 24px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  margin-bottom: 16px;
}

.detail-title {
  font-size: 24px;
  font-weight: 700;
  color: #1a1a1a;
  line-height: 1.4;
  word-break: break-word;
}

.header-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 16px;
}

.time-text {
  font-size: 13px;
  color: #999999;
  margin-left: auto;
}

/* 内容区块卡片 */
.section-card {
  background: #ffffff;
  border-radius: 10px;
  padding: 24px 28px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  margin-bottom: 16px;
}

.section-title {
  font-size: 17px;
  font-weight: 600;
  color: #1a1a1a;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f0f0;
}

/* 描述文字 */
.description-text {
  font-size: 15px;
  color: #333333;
  line-height: 1.9;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 图片网格 */
.images-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.detail-image {
  width: 100%;
  aspect-ratio: 1;
  border-radius: 6px;
  background: #eee;
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.detail-image:hover {
  transform: scale(1.03);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
}

/* ========================================
   右侧：侧边栏
   ======================================== */
.detail-sidebar {
  width: 320px;
  flex-shrink: 0;
}

.sidebar-card {
  background: #ffffff;
  border-radius: 10px;
  padding: 24px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  margin-bottom: 16px;
}

.sidebar-section-title {
  font-size: 15px;
  font-weight: 600;
  color: #1a1a1a;
  margin-bottom: 18px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f0f0;
}

/* 发布者信息 */
.publisher-info {
  display: flex;
  align-items: center;
  gap: 14px;
}

.publisher-avatar {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  object-fit: cover;
  background: #eee;
  flex-shrink: 0;
}

.publisher-detail {
  flex: 1;
  min-width: 0;
}

.publisher-name {
  font-size: 16px;
  font-weight: 600;
  color: #1a1a1a;
}

.publisher-help {
  margin-top: 6px;
}

.help-count {
  font-size: 12px;
  color: #FF9500;
  background: rgba(255, 149, 0, 0.08);
  padding: 2px 8px;
  border-radius: 4px;
}

.credit-row {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid #f0f0f0;
}

.credit-display {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.credit-score {
  font-size: 28px;
  font-weight: 700;
  color: #00B96B;
}

.credit-label {
  font-size: 12px;
  color: #999999;
  padding-top: 4px;
}

/* 信息列表 */
.info-list {
  display: flex;
  flex-direction: column;
}

.info-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px solid #f5f5f5;
}

.info-item:last-child { border-bottom: none; padding-bottom: 0; }
.info-item:first-child { padding-top: 0; }

.info-label {
  font-size: 13px;
  color: #999999;
  flex-shrink: 0;
}

.info-value {
  font-size: 14px;
  color: #333333;
  text-align: right;
  word-break: break-word;
}

.distance-value {
  color: #00B96B;
  font-weight: 500;
}

.reward-value {
  color: #FF6B00;
  font-weight: 700;
  font-size: 18px;
}

/* 操作按钮区 */
.action-card {
  padding: 20px 24px;
}

.btn-action {
  display: block;
  width: 100%;
  margin-bottom: 12px;
  font-weight: 600;
}

.btn-accept{
  width: 100%;
}

.btn-action:last-child { margin-bottom: 0; }

/* 只读状态提示（不能点，只是文字提示） */
.btn-hint {
  text-align: center;
  padding: 12px 0;
  border-radius: 8px;
  font-size: 14px;
  cursor: default;
  margin-bottom: 12px;
}
.btn-hint:last-child { margin-bottom: 0; }

.btn-disabled {
  background: #e8e8e8;
  color: #999999;
}
.btn-taken {
  background: #fff7e6;
  color: #d46b08;
  border: 1px solid #ffd591;
}

/* ========================================
   申请管理弹窗样式
   ======================================== */
.apply-list {
  max-height: 420px;
  overflow-y: auto;
}

.apply-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 4px;
  border-bottom: 1px solid #f0f0f0;
}

.apply-item:last-child {
  border-bottom: none;
}

.apply-info {
  flex: 1;
  min-width: 0;
}

.apply-name {
  font-weight: 600;
  font-size: 15px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.apply-meta {
  font-size: 12px;
  color: #999;
  margin-top: 2px;
}

.apply-reason {
  font-size: 12px;
  color: #e6a23c;
  margin-top: 2px;
  line-height: 1.5;
}

.apply-tag {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 8px;
}

.apply-tag.ok { background: #f0f9eb; color: #67c23a; }
.apply-tag.no { background: #fef0f0; color: #f56c6c; }

.apply-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

/* ========================================
   响应式：平板及以下回退为单栏
   ======================================== */
@media (max-width: 860px) {
  .detail-container { flex-direction: column; }
  .detail-sidebar { width: 100%; }
  .detail-wrapper { padding: 16px 12px 32px; }
  .main-header { padding: 20px 16px; }
  .detail-title { font-size: 20px; }
  .section-card { padding: 20px 16px; }
  .sidebar-card { padding: 20px 16px; }
}
</style>
