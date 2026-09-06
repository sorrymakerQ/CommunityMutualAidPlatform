<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getOrderList, cancelOrder, completeOrder, rateOrder } from '@/api/order'
import { getMyHelps, cancelHelp } from '@/api/help'
import { useConfirm } from '@/utils/confirm'
import ChatBox from '@/components/ChatBox.vue'

const { confirm, prompt } = useConfirm()
const route = useRoute()
const router = useRouter()
const tab = ref<string>((route.query.role as string) || 'publisher')
const items = ref<any[]>([])
const loading = ref(true)
const chatOrderId = ref(0)

// ===== 状态文案 =====
const orderStatusMap: Record<number, string> = { 1: '已接单', 2: '进行中', 3: '已完成', 4: '已取消', 5: '已评价' }
const helpStatusMap: Record<number, string> = { 0: '待支付', 1: '招募中', 2: '已满员', 3: '已完成', 4: '已取消' }

/** 状态数字 → el-tag 的 type（语义色） */
function statusTagType(s: number): 'warning' | 'primary' | 'success' | 'info' | 'danger' {
  const map: Record<number, 'warning' | 'primary' | 'success' | 'info' | 'danger'> = {
    1: 'warning',   // 待接单/已接单 - 黄
    2: 'primary',   // 进行中 - 蓝
    3: 'success',   // 已完成 - 绿
    4: 'info',      // 已取消 - 灰
    5: 'danger'     // 已评价 - 紫（这里借用 danger，因 el-tag 没有 purple）
  }
  return map[s] || 'info'
}

async function load() {
  loading.value = true
  try {
    if (tab.value === 'publisher') {
      // 我发布的 → 查求助表 tb_help_request
      const r = await getMyHelps({ page: 1, size: 50 })
      items.value = (r.data?.list || []).map((h: any) => ({ ...h, _type: 'help' }))
    } else {
      // 我接的单 → 查订单表 tb_order
      const r = await getOrderList({ role: 'helper', page: 1, size: 50 })
      items.value = (r.data?.list || []).map((o: any) => ({ ...o, _type: 'order' }))
    }
  } catch {
    items.value = []
  } finally {
    loading.value = false
  }
}

// ===== 订单操作 =====
async function doCancelOrder(o: any) {
  const r = await prompt('取消订单', '请输入取消原因：')
  if (!r) return
  try {
    await cancelOrder(o.id, r as string)
    ElMessage.success('已取消')
    load()
  } catch { /* 拦截器已提示 */ }
}

async function doFinish(o: any) {
  if (!await confirm('确认完成？', '确认后将给对方增加信用分，此操作不可撤销。')) return
  try {
    await completeOrder(o.id)
    ElMessage.success('已完成')
    load()
  } catch { /* 拦截器已提示 */ }
}

async function doReview(o: any) {
  const s = await prompt('评价', '请给本次服务打分', { type: 'number', placeholder: '1-5分' })
  if (!s || isNaN(+s) || +s < 1 || +s > 5) {
    ElMessage.warning('请输入 1-5 分')
    return
  }
  const c = await prompt('评价', '写下你的评价（可选）', { placeholder: '评价内容...' })
  if (c === false) return
  try {
    await rateOrder(o.id, +s, c as string || '')
    ElMessage.success('已评价')
    load()
  } catch { /* 拦截器已提示 */ }
}

// ===== 求助操作 =====
async function doCancelHelp(h: any) {
  if (!await confirm('取消求助', '确定要取消这条求助吗？')) return
  try {
    await cancelHelp(h.id)
    ElMessage.success('已取消')
    load()
  } catch { /* 拦截器已提示 */ }
}

watch(tab, load)
onMounted(load)

function goDetail(item: any) {
  if (item._type === 'help') {
    router.push(`/help/${item.id}`)
  } else {
    router.push(`/order/${item.id}`)
  }
}

function formatDate(dateStr: string): string {
  if (!dateStr) return '-'
  return dateStr.slice(0, 10)
}
</script>

<template>
  <div class="orders">
    <!-- Tab 切换 -->
    <el-tabs v-model="tab" class="orders-tabs">
      <el-tab-pane label="我发布的" name="publisher" />
      <el-tab-pane label="我接的单" name="helper" />
    </el-tabs>

    <!-- 我发布的（求助表） -->
    <div v-if="tab === 'publisher'" v-loading="loading" element-loading-text="加载中...">
      <el-table
        v-if="items.length"
        :data="items"
        class="orders-table"
        :header-cell-style="{ background: '#f6f7f8', color: '#909399', fontSize: '12px', fontWeight: '600' }"
        row-key="id"
        @row-click="goDetail"
      >
        <el-table-column prop="id" label="ID" width="80" class-name="col-id" />
        <el-table-column prop="title" label="标题" show-overflow-tooltip min-width="220" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small" effect="light">
              {{ helpStatusMap[row.status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="悬赏" width="100">
          <template #default="{ row }">
            <span v-if="row.reward > 0" class="reward-text">¥{{ row.reward }}</span>
            <span v-else class="dim">-</span>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" width="120">
          <template #default="{ row }">
            <span class="dim">{{ formatDate(row.createTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 1"
              type="danger"
              size="small"
              plain
              @click.stop="doCancelHelp(row)"
            >
              取消
            </el-button>
            <span v-else class="dim">-</span>
          </template>
        </el-table-column>
      </el-table>

      <el-empty
        v-else-if="!loading"
        description="暂无发布的求助"
        :image-size="120"
      >
        <el-button type="primary" @click="router.push('/publish')">
          去发布一条
        </el-button>
      </el-empty>
    </div>

    <!-- 我接的单（订单表） -->
    <div v-else v-loading="loading" element-loading-text="加载中...">
      <el-table
        v-if="items.length"
        :data="items"
        class="orders-table"
        :header-cell-style="{ background: '#f6f7f8', color: '#909399', fontSize: '12px', fontWeight: '600' }"
        row-key="id"
        @row-click="goDetail"
      >
        <el-table-column prop="id" label="ID" width="80" class-name="col-id" />
        <el-table-column prop="helpTitle" label="求助标题" show-overflow-tooltip min-width="180" />
        <el-table-column prop="otherName" label="发布者" width="120" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small" effect="light">
              {{ orderStatusMap[row.status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="酬劳" width="100">
          <template #default="{ row }">
            <span v-if="row.reward > 0" class="reward-text">¥{{ row.reward }}</span>
            <span v-else class="dim">-</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="120">
          <template #default="{ row }">
            <span class="dim">{{ formatDate(row.createTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 2">
              <el-button type="primary" size="small" plain @click.stop="chatOrderId = row.id">
                私信
              </el-button>
              <el-button type="danger" size="small" plain @click.stop="doCancelOrder(row)">
                取消
              </el-button>
            </template>
            <template v-else-if="row.status === 3 && !row.helperScore">
              <el-button type="primary" size="small" @click.stop="doReview(row)">
                评价
              </el-button>
            </template>
            <span v-else class="dim">-</span>
          </template>
        </el-table-column>
      </el-table>

      <el-empty
        v-else-if="!loading"
        description="暂无订单"
        :image-size="120"
      >
        <el-button type="primary" @click="router.push('/')">
          去首页看看
        </el-button>
      </el-empty>
    </div>

    <ChatBox
      v-if="chatOrderId > 0"
      :order-id="chatOrderId"
      :show="true"
      @close="chatOrderId = 0"
    />
  </div>
</template>

<style scoped>
.orders {
  padding: 20px 24px;
}

/* Tab 样式微调 —— 保留品牌绿 */
.orders-tabs {
  margin-bottom: 16px;
}
.orders-tabs :deep(.el-tabs__item.is-active) {
  color: #00B96B;
  font-weight: 600;
}
.orders-tabs :deep(.el-tabs__active-bar) {
  background: #00B96B;
}
.orders-tabs :deep(.el-tabs__item:hover) {
  color: #00B96B;
}

/* 表格 */
.orders-table {
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}
.orders-table :deep(.el-table__row) {
  cursor: pointer;
}
.orders-table :deep(.col-id) {
  color: #c0c4cc;
}

/* 单元格文字样式 */
.reward-text {
  color: #e65100;
  font-weight: 500;
}
.dim {
  color: #c0c4cc;
  font-size: 12px;
}
</style>
