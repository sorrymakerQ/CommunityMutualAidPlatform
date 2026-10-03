<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { getNotifications, markNotificationRead, markAllRead } from '@/api/order'

/** 通知类型文案 */
const typeMap: Record<number, string> = { 1: '系统', 2: '订单', 3: '评价' }

const notices = ref<any[]>([])
const sel = ref<any>(null)

const unreadNotice = computed(() => notices.value.filter(m => m.isRead === 0).length)

async function loadNotices() {
  try {
    const r = await getNotifications({ page: 1, size: 50 })
    notices.value = r.data?.list || []
    // 刷新后按 id 重新关联选中项，避免轮询把详情面板刷空
    if (sel.value) {
      sel.value = notices.value.find(m => m.id === sel.value!.id) || null
    }
  } catch {
    notices.value = []
  }
}

async function readNotice(m: any) {
  sel.value = m
  if (m.isRead === 1) return
  try {
    await markNotificationRead(m.id)
    m.isRead = 1
  } catch {}
}

async function readAllNotices() {
  try {
    await markAllRead()
    notices.value.forEach(m => m.isRead = 1)
    ElMessage.success('已全部标为已读')
  } catch {}
}

// 通知不再走 WebSocket，改为轮询刷新（未读数由 App.vue 单独轮询）
let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  loadNotices()
  timer = setInterval(loadNotices, 30000)
})

onUnmounted(() => {
  if (timer) { clearInterval(timer); timer = null }
})
</script>

<template>
  <div class="msgs">
    <div class="msgs-layout">
      <!-- ========== 左侧列表 ========== -->
      <div class="list-panel">
        <div class="sub-head">
          <span class="sub-tit">
            通知
            <el-badge v-if="unreadNotice" :value="unreadNotice" :max="99" class="head-badge" />
          </span>
          <el-button link type="primary" size="small" @click="readAllNotices">
            全部已读
          </el-button>
        </div>

        <div class="list-body">
          <div v-if="notices.length">
            <div
              v-for="m in notices"
              :key="m.id"
              class="msg-row"
              :class="{ un: m.isRead === 0, ac: sel?.id === m.id }"
              @click="readNotice(m)"
            >
              <div class="mr-top">
                <span class="mr-dot" :class="{ read: m.isRead === 1 }"></span>
                <span class="mr-title" :class="{ dim: m.isRead === 1 }">{{ m.title }}</span>
              </div>
              <p class="mr-preview">{{ m.content?.slice(0, 35) }}{{ m.content?.length > 35 ? '...' : '' }}</p>
              <div class="mr-foot">
                <el-tag size="small" effect="plain">{{ typeMap[m.type] }}</el-tag>
                <span class="mr-time">{{ m.createTime?.slice(0, 16) }}</span>
              </div>
            </div>
          </div>
          <el-empty
            v-else
            description="暂无通知"
            :image-size="80"
          />
        </div>
      </div>

      <!-- ========== 右侧详情 ========== -->
      <div class="detail-panel">
        <template v-if="sel">
          <h2 class="dt-title">{{ sel.title }}</h2>
          <div class="dt-meta">
            <el-tag size="small">{{ typeMap[sel.type] }}通知</el-tag>
            <span class="dt-time">{{ sel.createTime }}</span>
          </div>
          <el-divider />
          <div class="dt-body">{{ sel.content }}</div>
        </template>

        <el-empty
          v-else
          description="点击左侧查看详情"
          :image-size="120"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.msgs {
  padding: 20px 24px;
  height: 100%;
}
.msgs-layout {
  display: flex;
  height: calc(100vh - 120px);
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}

/* ==================== 左侧列表 ==================== */
.list-panel {
  width: 340px;
  border-right: 1px solid #e8eaed;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.sub-head {
  padding: 14px 16px;
  border-bottom: 1px solid #f1f2f3;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}
.sub-tit {
  font-size: 13px;
  font-weight: 600;
  color: #18191c;
  display: inline-flex;
  align-items: center;
}
/* 徽标数字紧贴文字 */
.head-badge :deep(.el-badge__content) {
  transform: translate(6px, -6px);
  height: 16px;
  padding: 0 5px;
  line-height: 16px;
  font-size: 10px;
}

.list-body {
  flex: 1;
  overflow-y: auto;
}

/* 单条消息行 */
.msg-row {
  padding: 14px 16px;
  border-bottom: 1px solid #f5f5f5;
  cursor: pointer;
  transition: background 0.1s;
}
.msg-row:hover { background: #f6f7f8; }
.msg-row.ac { background: #f0fdf6; }
.msg-row.un { background: #fbfffb; }

.mr-top {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}
.mr-dot {
  width: 6px;
  height: 6px;
  background: #00B96B;
  border-radius: 50%;
  flex-shrink: 0;
}
.mr-dot.read { background: #d9d9d9; }
.mr-title {
  font-size: 13px;
  font-weight: 600;
  color: #18191c;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mr-title.dim { font-weight: 400; color: #9499a0; }
.mr-preview {
  font-size: 12px;
  color: #9499a0;
  margin: 0 0 6px 12px;
}
.mr-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-left: 12px;
}
.mr-time { font-size: 11px; color: #c0c4cc; }

/* ==================== 右侧详情 ==================== */
.detail-panel {
  flex: 1;
  padding: 24px 32px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.dt-title {
  font-size: 18px;
  font-weight: 600;
  color: #18191c;
  margin-bottom: 10px;
}

.dt-meta {
  display: flex;
  align-items: center;
  gap: 12px;
}

.dt-time { font-size: 12px; color: #c0c4cc; }

.dt-body {
  font-size: 14px;
  color: #61666d;
  line-height: 1.8;
  white-space: pre-wrap;
}
</style>
