<script setup lang="ts">
/**
 * 支付成功页
 * 路由：/pay-success（别名 /pay-success.html，兼容历史外链）
 * 订单信息通过 query 传入；金额缺失时（例如直接刷新本页）向后端补查
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getHelpDetail } from '@/api/help'

const route = useRoute()

// ========== 状态 ==========

/** 订单号（求助ID，来自路由 query） */
const helpId = ref<number>(0)
/** 支付金额（优先取 query，缺失时向后端补查） */
const amount = ref<string>('')
/** 支付时间（来自路由 query） */
const payTime = ref<string>('')
/** 金额补查中 */
const loading = ref(true)

// ========== 计算属性 ==========

/** 支付金额文案 */
const amountText = computed<string>(() => money(amount.value))
/** 支付时间文案（缺失时用当前时间兜底） */
const payTimeText = computed<string>(() => formatTime(payTime.value || new Date()))

// ========== 方法 ==========

/** 金额格式化：保留两位小数 */
function money(n: number | string | null | undefined): string {
  return Number(n || 0).toFixed(2)
}

/**
 * 时间格式化：兼容 'yyyy-MM-dd HH:mm:ss' 字符串与 Date
 * @param s 时间
 */
function formatTime(s: string | Date): string {
  if (!s) return '-'
  const d = new Date(String(s).replace(/-/g, '/'))
  if (isNaN(d.getTime())) return String(s)
  const p = (x: number): string => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

// ========== 生命周期 ==========

onMounted(async () => {
  helpId.value = Number(route.query.helpId || 0)
  amount.value = String(route.query.amount || '')
  payTime.value = String(route.query.payTime || '')

  // 金额缺失时向后端补查（静默：补查失败按 ¥0.00 展示，不打扰用户）
  if (!amount.value && helpId.value) {
    try {
      const res = await getHelpDetail(helpId.value, true)
      if (res.data) amount.value = money(res.data.totalReward)
    } catch {
      /* 补查失败：忽略 */
    }
  }

  loading.value = false
})
</script>

<template>
  <div class="pay-success-page">
    <!-- 加载中（金额缺失时需向后端补查） -->
    <div v-if="loading" class="loading">加载中...</div>

    <!-- 支付结果 -->
    <div v-else class="container">
      <div class="hero">
        <div class="check">
          <span class="ring"></span>
          <div class="circle">
            <svg viewBox="0 0 24 24"><path d="M4 12.5l5 5L20 6.5" /></svg>
          </div>
        </div>
        <div class="hero-title">支付成功</div>
        <div class="hero-sub">感谢您的使用，求助已发布到首页</div>
      </div>

      <div class="info-card">
        <div class="info-row">
          <span class="info-label">订单号</span>
          <span class="info-value">{{ helpId || '-' }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">支付金额</span>
          <span class="info-value amount">¥{{ amountText }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">支付时间</span>
          <span class="info-value">{{ payTimeText }}</span>
        </div>
      </div>

      <div class="actions">
        <router-link class="btn btn-primary" to="/">返回首页</router-link>
        <router-link
          class="btn btn-ghost"
          :to="{ name: 'help-detail', params: { id: helpId } }"
        >
          查看求助详情
        </router-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pay-success-page {
  --brand: #00b96b;
  --brand-dark: #00a35e;
  --text: #303133;
  --muted: #909399;
  --bg: #f5f6f8;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  color: var(--text);
  background: var(--bg);
  -webkit-font-smoothing: antialiased;
}

.container {
  max-width: 560px;
  width: 100%;
  margin: 0 auto;
  padding: 40px 16px 24px;
  flex: 1;
}

/* ==================== 顶部成功区 ==================== */
.hero {
  text-align: center;
  margin-bottom: 24px;
}
.check {
  width: 86px;
  height: 86px;
  margin: 0 auto 20px;
  position: relative;
}
.check .circle {
  width: 86px;
  height: 86px;
  border-radius: 50%;
  background: var(--brand);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 10px 30px rgba(0, 185, 107, 0.35);
  animation: pop 0.5s cubic-bezier(0.175, 0.885, 0.32, 1.275);
}
.check .circle svg {
  width: 46px;
  height: 46px;
  fill: none;
  stroke: #fff;
  stroke-width: 5;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.check .circle svg path {
  stroke-dasharray: 48;
  stroke-dashoffset: 48;
  animation: draw 0.45s 0.25s ease forwards;
}
.ring {
  position: absolute;
  inset: -10px;
  border-radius: 50%;
  border: 2px solid rgba(0, 185, 107, 0.25);
  animation: ripple 1.6s ease-out infinite;
}
@keyframes pop {
  0% {
    transform: scale(0.4);
    opacity: 0;
  }
  100% {
    transform: scale(1);
    opacity: 1;
  }
}
@keyframes draw {
  to {
    stroke-dashoffset: 0;
  }
}
@keyframes ripple {
  0% {
    transform: scale(0.9);
    opacity: 1;
  }
  100% {
    transform: scale(1.25);
    opacity: 0;
  }
}
.hero-title {
  font-size: 24px;
  font-weight: 700;
}
.hero-sub {
  font-size: 14px;
  color: var(--muted);
  margin-top: 10px;
}

/* ==================== 信息卡片 ==================== */
.info-card {
  background: #fff;
  border-radius: 16px;
  padding: 8px 20px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}
.info-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 0;
  border-bottom: 1px solid #f0f2f5;
}
.info-row:last-child {
  border-bottom: none;
}
.info-label {
  font-size: 14px;
  color: var(--muted);
  flex-shrink: 0;
}
.info-value {
  font-size: 15px;
  color: var(--text);
  text-align: right;
  word-break: break-all;
}
.info-value.amount {
  color: #ff6b00;
  font-weight: 700;
  font-size: 20px;
}

/* ==================== 按钮区 ==================== */
.actions {
  margin-top: 28px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.btn {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 48px;
  border-radius: 14px;
  font-size: 16px;
  cursor: pointer;
  text-decoration: none;
  border: none;
}
.btn-primary {
  background: var(--brand);
  color: #fff;
  font-weight: 600;
  box-shadow: 0 6px 16px rgba(0, 185, 107, 0.25);
}
.btn-primary:hover {
  background: var(--brand-dark);
}
.btn-ghost {
  background: #fff;
  color: #606266;
  border: 1px solid #dcdfe6;
}

.loading {
  text-align: center;
  padding: 80px 0;
  color: var(--muted);
}
</style>
