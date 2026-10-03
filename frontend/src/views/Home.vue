<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getHelpList, getCategoryList } from '@/api/help'
import { regionName } from '@/data/regions'
import type { HelpRequest, Category } from '@/types'

const router = useRouter()

const categories = ref<Category[]>([])
const activeCategoryId = ref(0)
const helpList = ref<HelpRequest[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const loading = ref(false)

onMounted(async () => {
  await fetchCategories()
  await loadData()
})

async function fetchCategories() {
  try {
    const res = await getCategoryList()
    if (res.data) categories.value = res.data
  } catch { /* 拦截器已提示 */ }
}

async function loadData() {
  loading.value = true
  try {
    const params: Record<string, unknown> = { page: page.value, size: pageSize.value }
    if (activeCategoryId.value) params.categoryId = activeCategoryId.value
    if (keyword.value) params.keyword = keyword.value
    const res = await getHelpList(params)
    if (res.data) {
      helpList.value = res.data.list || []
      total.value = res.data.total || 0
      console.log(helpList.value)
    }
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

function switchCat(id: number) {
  activeCategoryId.value = id
  page.value = 1
  loadData()
}

function onSearch() {
  page.value = 1
  loadData()
}

function onPageChange(p: number) {
  page.value = p
  loadData()
}

function onPageSizeChange(size: number) {
  pageSize.value = size
  page.value = 1
  loadData()
}

/** 时间格式化：几分钟前 / 几小时前 / 几天前 */
function fmtTime(d: string): string {
  if (!d) return ''
  const diff = Date.now() - new Date(d).getTime()
  const m = Math.floor(diff / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return `${m}分钟前`
  const h = Math.floor(diff / 3600000)
  if (h < 24) return `${h}小时前`
  return `${Math.floor(diff / 86400000)}天前`
}

/** 状态数字 → 显示文案（保留原版 st1/st2/st3/st4 样式类） */
function statusText(status: number): string {
  return status === 0 ? '待支付' : status === 1 ? '招募中' : status === 2 ? '已满员' : status === 3 ? '已完成' : '已取消'
}
</script>

<template>
  <div class="home">
    <!-- 搜索栏 -->
    <div class="search-row">
      <el-input
        v-model="keyword"
        class="search-box"
        placeholder="搜索求助..."
        clearable
        @keyup.enter="onSearch"
        @clear="onSearch"
      >
        <template #append>
          <el-button class="search-btn" @click="onSearch">🔍</el-button>
        </template>
      </el-input>
    </div>

    <!-- 分类标签 -->
    <div class="cat-row">
      <span class="cat-tag" :class="{ on: activeCategoryId === 0 }" @click="switchCat(0)">全部</span>
      <span
        v-for="c in categories"
        :key="c.id"
        class="cat-tag"
        :class="{ on: activeCategoryId === c.id }"
        @click="switchCat(c.id)"
      >
        {{ c.icon }} {{ c.name }}
      </span>
    </div>

    <!-- 结果统计 -->
    <div class="top-bar">
      <span class="result-info">共 <b>{{ total }}</b> 条，本页 <b>{{ helpList.length }}</b> 条</span>
    </div>

    <!-- 卡片网格：撑满剩余空间 -->
    <div class="card-area" v-loading="loading"  element-loading-text="加载中..."  >
      <div v-if="helpList.length > 0" class="card-grid">
        <div
          v-for="h in helpList"
          :key="h.id"
          class="card"
          @click="router.push('/help/' + h.id)"
        >
          <div class="card-img">
            <span class="card-cat-icon">{{ categories.find(c => c.id === h.categoryId)?.icon || '📌' }}</span>
            <span v-if="h.urgent === 1" class="urgent-badge">紧急</span>
            <span class="card-status" :class="'st' + h.status">{{ statusText(h.status) }}</span>
          </div>
          <div class="card-body">
            <h3 class="card-title" :title="h.title">{{ h.title }}</h3>
            <p class="card-desc">{{ h.description?.slice(0, 80) }}{{ (h.description?.length ?? 0) > 80 ? '...' : '' }}</p>
            <div class="card-meta">
              <span class="meta-loc">📍 {{ regionName(h.addressId) }}</span>
              <span class="meta-reward" v-if="h.reward > 0">¥{{ h.reward }}</span>
            </div>
            <div class="card-footer">
              <span class="footer-author">👤 {{ h.publisherName }}</span>
              <span class="footer-time">{{ fmtTime(h.createTime) }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 空态 -->
      <el-empty
        v-else-if="!loading"
        description="暂无匹配的求助"
        :image-size="120"
      >
        <el-button v-if="keyword || activeCategoryId" @click="keyword = ''; switchCat(0)">
          清空筛选
        </el-button>
      </el-empty>
    </div>

    <!-- 底部分页 -->
    <div class="bottom-bar" v-if="total > 0">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @current-change="onPageChange"
        @size-change="onPageSizeChange"
      />
    </div>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  min-height: 100%;
  padding: 20px 24px;
}

/* 搜索 */
.search-row { margin-bottom: 14px; display: flex; justify-content: center; flex-shrink: 0; }

.search-box {
  width: 500px;
}
.search-box :deep(.el-input__wrapper) {
  background: #f8fafb;
  border-radius: 8px 0 0 8px;
  box-shadow: 0 0 0 1px #dde0e3 inset;
}
.search-box :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #00c777 inset;
}
.search-box :deep(.el-input-group__append) {
  background: #e8fcef;
  border: 1px solid #dde0e3;
  border-left: none;
  border-radius: 0 8px 8px 0;
  padding: 0;
}
.search-box :deep(.el-input-group__append .el-button) {
  border: none;
  background: transparent;
  padding: 0 16px;
  font-size: 16px;
  height: 100%;
}
.search-box :deep(.el-input-group__append .el-button:hover) {
  background: #c8f0d2;
}

/* 分类 */
.cat-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
  flex-shrink: 0;
}

.cat-tag {
  display: inline-block;
  padding: 4px 14px;
  background: #f8fafb;
  border: 1px solid #dde0e3;
  border-radius: 6px;
  font-size: 13px;
  color: #61666d;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}

.cat-tag:hover { background: #e8fcef; color: #00a855; }

.cat-tag.on {
  background: linear-gradient(135deg, #00B96B, #00c777);
  border-color: #00c777;
  color: #fff;
}

/* 顶部信息栏 */
.top-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
  flex-shrink: 0;
}

.result-info { font-size: 13px; color: #9499a0; }
.result-info b { color: #61666d; }

/* 卡片区域：按内容自然高度显示 */
.card-area {
  margin-top: 30px;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 14px;
}

.card {
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s;
  display: flex;
  flex-direction: column;
}

.card:hover {
  transform: translateY(-4px);
  box-shadow: 0 12px 32px rgba(0, 175, 95, 0.22);
}

.card-img {
  height: 160px;
  background: linear-gradient(135deg, #c8f0d2, #8ee0a8);
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  flex-shrink: 0;
}

.card-cat-icon { font-size: 52px; }

.urgent-badge {
  position: absolute;
  top: 8px;
  left: 8px;
  padding: 2px 8px;
  background: #ff4d4f;
  color: #fff;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}

.card-status {
  position: absolute;
  top: 8px;
  right: 8px;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
}

.st1 { background: #ffe0b2; color: #e65100; font-weight: 600; }
.st2 { background: #bbdefb; color: #0d47a1; font-weight: 600; }
.st3 { background: #c8f0d2; color: #1b5e20; font-weight: 600; }
.st4 { background: #ffcdd2; color: #b71c1c; font-weight: 600; }

.card-body {
  padding: 16px 18px 18px;
  flex: 1;
  display: flex;
  flex-direction: column;
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: #18191c;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-bottom: 10px;
}

.card-desc {
  font-size: 13px;
  color: #9499a0;
  line-height: 1.6;
  height: 62px;
  overflow: hidden;
  margin-bottom: auto;
}

.card-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #9499a0;
  margin-bottom: 10px;
  margin-top: 12px;
}

.meta-reward { color: #e65100; font-weight: 600; }

.card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: #c0c4cc;
  padding-top: 10px;
  border-top: 1px solid #f1f2f3;
}

.footer-author {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.footer-time {
  flex-shrink: 0;
}

/* 底部分页：固定在屏幕底部，水平居中 */
.bottom-bar {
  position: fixed;
  bottom: 0;
  left: 200px;              /* 避开左侧 200px 的导航栏 */
  right: 0;
  display: flex;
  justify-content: center;
  padding: 20px 0 10px;
  background: #f4f5f7;
  z-index: 10;
}
</style>
