<script setup lang="ts">
/**
 * 订单详情页面
 * 展示订单完整信息，支持取消、完成、评价等操作
 */
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getOrderDetail, cancelOrder, completeOrder, rateOrder } from '@/api/order'
import { useUserStore } from '@/stores/user'
import { useConfirm } from '@/utils/confirm'
import NavBar from '@/components/NavBar.vue'
import { regionName } from '@/data/regions'

const { confirm, prompt } = useConfirm()
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const order = ref<any>(null)
const loading = ref(true)

const orderId = computed(() => Number(route.params.id))

const statusMap: Record<number, string> = {
  1: '已接单', 2: '进行中', 3: '已完成', 4: '已取消', 5: '已评价'
}



/** 状态 → el-tag type */
const statusTagType = computed<'warning' | 'primary' | 'success' | 'info' | 'danger'>(() => {
  if (!order.value) return 'info'
  const map: Record<number, 'warning' | 'primary' | 'success' | 'info' | 'danger'> = {
    1: 'warning', 2: 'primary', 3: 'success', 4: 'info', 5: 'danger'
  }
  return map[order.value.status] || 'info'
})

/** 是否是我发布的 */
const isMyHelp = computed(() => {
  if (!order.value || !userStore.userInfo) return false
  return order.value.publisherId === userStore.userInfo.id
})

/** 是否是我接的单 */
const isMyAccept = computed(() => {
  if (!order.value || !userStore.userInfo) return false
  return order.value.helperId === userStore.userInfo.id
})

/**
 * 时间线数据：根据订单状态和时间戳生成流程节点
 * 每个节点包含：标题 / 时间 / 类型（决定颜色）/ 是否已完成
 */
const timeline = computed(() => {
  if (!order.value) return []
  const o = order.value
  const nodes: Array<{
    title: string
    time: string
    type: 'primary' | 'success' | 'warning' | 'danger' | 'info'
    done: boolean
  }> = [
    { title: '发布求助', time: fmtTime(o.helpCreateTime || o.createTime), type: 'primary', done: true },
    { title: '接单成功', time: fmtTime(o.createTime), type: 'success', done: true },
  ]
  if (o.finishTime && o.status === 3) {
    nodes.push({ title: '订单完成', time: fmtTime(o.finishTime), type: 'success', done: true })
  }
  if (o.status === 5) {
    if (o.finishTime) {
      nodes.push({ title: '订单完成', time: fmtTime(o.finishTime), type: 'success', done: true })
    }
    nodes.push({ title: '双方已评价', time: '', type: 'success', done: true })
  }
  if (o.status === 4) {
    nodes.push({ title: '订单取消', time: '', type: 'danger', done: true })
  }
  return nodes
})

onMounted(async () => {
  await loadDetail()
})

async function loadDetail() {
  loading.value = true
  try {
    const r = await getOrderDetail(orderId.value)
    if (r.data) {
      order.value = r.data
    }
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

function fmtTime(t: string) {
  if (!t) return '-'
  return t.replace('T', ' ').slice(0, 19)
}

async function doCancel() {
  const reason = await prompt('取消订单', '请输入取消原因：')
  if (!reason) return
  try {
    await cancelOrder(order.value.id, reason as string)
    ElMessage.success('已取消')
    await loadDetail()
  } catch { /* 拦截器已提示 */ }
}

async function doFinish() {
  if (!await confirm('确认完成？', '确认后将给对方增加信用分。')) return
  try {
    await completeOrder(order.value.id)
    ElMessage.success('已完成')
    await loadDetail()
  } catch { /* 拦截器已提示 */ }
}

async function doReview() {
  const s = await prompt('评价', '请给本次服务打分', { type: 'number', placeholder: '1-5分' })
  if (!s || isNaN(+s) || +s < 1 || +s > 5) {
    ElMessage.warning('请输入 1-5 分')
    return
  }
  const c = await prompt('评价', '写下你的评价（可选）', { placeholder: '评价内容...' })
  if (c === false) return
  try {
    await rateOrder(order.value.id, +s, c as string || '')
    ElMessage.success('已评价')
    await loadDetail()
  } catch { /* 拦截器已提示 */ }
}
</script>

<template>
  <div class="detail-page" v-loading="loading" element-loading-text="加载中...">
    <NavBar title="订单详情" :show-back="true" back-text="返回" />

    <div v-if="!loading && order" class="detail-wrapper">
      <div class="detail-container">
        <!-- ========== 左侧：主体 ========== -->
        <div class="detail-main">
          <!-- 订单头部 -->
          <div class="main-header">
            <div class="header-row">
              <h1 class="detail-title">{{ order.helpTitle || '订单详情' }}</h1>
              <el-tag :type="statusTagType" effect="light">
                {{ statusMap[order.status] }}
              </el-tag>
            </div>
            <div class="header-meta">
              <span>订单编号：{{ order.id }}</span>
              <span>创建时间：{{ fmtTime(order.createTime) }}</span>
            </div>
          </div>

          <!-- 求助信息 -->
          <div class="section-card">
            <h3 class="section-title">求助信息</h3>
            <div class="info-grid">
              <div class="info-row"><span class="lbl">标题</span><span>{{ order.helpTitle }}</span></div>
              <div class="info-row"><span class="lbl">描述</span><span>{{ order.helpDescription || '无' }}</span></div>
              <div class="info-row"><span class="lbl">地址</span><span>📍 {{ regionName(order.helpAddressId) || '未知' }}{{ order.helpAddressDetail ? ` ${order.helpAddressDetail}` : '' }}</span></div>
              <div class="info-row"><span class="lbl">酬劳</span><span class="reward">¥{{ (order.helpReward || 0).toFixed(2) }}</span></div>
            </div>
          </div>

          <!-- 双方信息 -->
          <div class="two-col">
            <!-- 发布者 -->
            <div class="section-card">
              <h3 class="section-title">发布者</h3>
              <div v-if="order.publisher" class="user-info">
                <img :src="order.publisher.avatar || '/default-avatar.svg'" class="avatar" />
                <div class="user-detail">
                  <div class="name">{{ order.publisher.nickname }}</div>
                  <div class="phone">{{ order.publisher.phone || '未留电话' }}</div>
                  <div class="credit">⭐ 信用 {{ order.publisher.credit || 100 }}</div>
                </div>
              </div>
              <div v-if="order.publisherScore" class="rating-block">
                <div class="rating-header">
                  <span>Ta 的评价</span>
                  <el-rate :model-value="order.publisherScore" disabled size="small" />
                </div>
                <p class="rating-text">{{ order.publisherComment || '（无评论）' }}</p>
              </div>
            </div>

            <!-- 接单者 -->
            <div class="section-card">
              <h3 class="section-title">接单者</h3>
              <div v-if="order.helper" class="user-info">
                <img :src="order.helper.avatar || '/default-avatar.svg'" class="avatar" />
                <div class="user-detail">
                  <div class="name">{{ order.helper.nickname }}</div>
                  <div class="phone">{{ order.helper.phone || '未留电话' }}</div>
                  <div class="credit">⭐ 信用 {{ order.helper.credit || 100 }}</div>
                </div>
              </div>
              <div v-if="order.helperScore" class="rating-block">
                <div class="rating-header">
                  <span>Ta 的评价</span>
                  <el-rate :model-value="order.helperScore" disabled size="small" />
                </div>
                <p class="rating-text">{{ order.helperComment || '（无评论）' }}</p>
              </div>
            </div>
          </div>

          <!-- 订单流程时间线 -->
          <div class="section-card">
            <h3 class="section-title">订单流程</h3>
            <el-timeline>
              <el-timeline-item
                v-for="(node, i) in timeline"
                :key="i"
                :type="node.type"
                :timestamp="node.time"
                placement="top"
              >
                {{ node.title }}
              </el-timeline-item>
            </el-timeline>

            <!-- 取消原因单独展示 -->
            <div v-if="order.cancelReason" class="cancel-block">
              <span class="lbl">取消原因</span>
              <span class="cancel-reason">{{ order.cancelReason }}</span>
            </div>
          </div>
        </div>

        <!-- ========== 右侧：操作栏 ========== -->
        <div class="detail-sidebar">
          <!-- 主操作按钮 -->
          <div class="sidebar-card action-card">
            <template v-if="order.status < 4">
              <el-button
                v-if="isMyHelp && order.status === 1"
                type="danger"
                plain
                size="large"
                class="btn-action"
                @click="doCancel"
              >
                取消订单
              </el-button>

              <el-button
                v-if="isMyHelp && order.status === 2"
                type="success"
                size="large"
                class="btn-action"
                @click="doFinish"
              >
                确认完成
              </el-button>

              <el-button
                v-if="isMyAccept && order.status === 1"
                type="danger"
                plain
                size="large"
                class="btn-action"
                @click="doCancel"
              >
                取消接单
              </el-button>
            </template>

            <template v-if="order.status === 3">
              <el-button
                v-if="isMyHelp && !order.publisherScore"
                type="primary"
                size="large"
                class="btn-action"
                @click="doReview"
              >
                评价接单者
              </el-button>
              <el-button
                v-if="isMyAccept && !order.helperScore"
                type="primary"
                size="large"
                class="btn-action"
                @click="doReview"
              >
                评价发布者
              </el-button>
            </template>

            <div v-if="order.status >= 4" class="btn-hint">
              {{ order.status === 4 ? '订单已取消' : '订单已完成' }}
            </div>
          </div>

          <!-- 返回列表 -->
          <div class="sidebar-card">
            <el-button
              size="large"
              class="btn-action"
              @click="router.push('/orders')"
            >
              <el-icon><ArrowLeft /></el-icon>
              <span style="margin-left: 4px;">返回订单列表</span>
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.detail-page { min-height: 100%; background: #f4f5f7; }
.detail-wrapper { display: flex; justify-content: center; padding: 24px 20px 40px; }
.detail-container { display: flex; gap: 24px; width: 100%; max-width: 1100px; }

.detail-main { flex: 1; min-width: 0; }

/* 订单头部 */
.main-header {
  background: #fff;
  border-radius: 10px;
  padding: 24px 28px;
  box-shadow: 0 1px 2px rgba(0,0,0,0.04);
  margin-bottom: 16px;
}
.header-row { display: flex; align-items: center; gap: 14px; margin-bottom: 12px; }
.detail-title { font-size: 22px; font-weight: 700; color: #1a1a1a; }
.header-meta { font-size: 13px; color: #999; display: flex; gap: 20px; }

/* 卡片区块 */
.section-card {
  background: #fff;
  border-radius: 10px;
  padding: 20px 28px;
  box-shadow: 0 1px 2px rgba(0,0,0,0.04);
  margin-bottom: 16px;
}
.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #1a1a1a;
  margin-bottom: 16px;
  padding-bottom: 10px;
  border-bottom: 1px solid #f0f0f0;
}

/* 求助信息网格 */
.info-grid { display: flex; flex-direction: column; gap: 10px; }
.info-row { display: flex; font-size: 14px; color: #333; gap: 12px; }
.info-row .lbl { color: #999; min-width: 70px; flex-shrink: 0; }
.reward { color: #e65100; font-weight: 700; font-size: 18px; }

/* 双方信息 */
.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }

.user-info { display: flex; align-items: center; gap: 14px; }
.avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  object-fit: cover;
  background: #eee;
}
.user-detail { flex: 1; }
.name { font-size: 15px; font-weight: 600; color: #1a1a1a; }
.phone { font-size: 13px; color: #9499a0; margin-top: 2px; }
.credit { font-size: 13px; color: #ff9500; margin-top: 2px; }

/* 评分 */
.rating-block {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #f0f0f0;
}
.rating-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: #666;
  margin-bottom: 6px;
}
.rating-text {
  font-size: 13px;
  color: #61666d;
  line-height: 1.6;
}

/* 取消原因块 */
.cancel-block {
  display: flex;
  gap: 12px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px dashed #f0f0f0;
  font-size: 14px;
}
.cancel-block .lbl { color: #999; min-width: 70px; flex-shrink: 0; }
.cancel-reason { color: #c62828; }

/* 右侧侧边栏 */
.detail-sidebar { width: 280px; flex-shrink: 0; }
.sidebar-card {
  background: #fff;
  border-radius: 10px;
  padding: 20px;
  box-shadow: 0 1px 2px rgba(0,0,0,0.04);
  margin-bottom: 16px;
}

.btn-action {
  width: 100%;
  margin-bottom: 10px;
  font-weight: 600;
}
.btn-action:last-child { margin-bottom: 0; }

/* 只读文字提示 */
.btn-hint {
  text-align: center;
  padding: 12px 0;
  color: #999;
  font-size: 14px;
  background: #f5f5f5;
  border-radius: 8px;
}

@media (max-width: 860px) {
  .detail-container { flex-direction: column; }
  .detail-sidebar { width: 100%; }
  .two-col { grid-template-columns: 1fr; }
}
</style>
