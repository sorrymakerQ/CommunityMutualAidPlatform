<script setup lang="ts">
/**
 * 待支付页
 * 路由：/pay（别名 /pay.html，兼容历史外链）
 * 支付方式：余额支付（直接扣款）/ 支付宝支付（跳转收银台 + 轮询支付结果）
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getHelpDetail, payHelp, payHelpByAlipay } from '@/api/help'
import { getUserInfo } from '@/api/user'
import type { HelpRequest } from '@/types'

const route = useRoute()
const router = useRouter()

// ========== 状态 ==========

/** 订单号（求助ID，来自路由 query） */
const helpId = ref<number>(0)
/** 求助详情 */
const help = ref<HelpRequest | null>(null)
/** 当前余额（登录态才有，null 表示未知） */
const balance = ref<number | null>(null)
/** 选中的支付方式 */
const selected = ref<'balance' | 'alipay'>('balance')
/** 支付请求中（防重复提交） */
const paying = ref(false)
/** 详情加载中 */
const loading = ref(true)
/** 加载失败 */
const error = ref(false)
/** 失败是否可重试 */
const retryable = ref(false)
const errorTitle = ref('')
const errorMsg = ref('')
/** 支付中遮罩（仅余额支付这类瞬时动作用；支付宝不遮挡页面） */
const overlay = reactive({ show: false, title: '', sub: '' })

/** 支付宝支付轮询定时器 */
let pollTimer: ReturnType<typeof setInterval> | null = null
/** 支付宝等待支付的常驻顶部提示（非遮挡，支付结束/离开页面时关闭） */
let payTip: ReturnType<typeof ElMessage> | null = null

// ========== 计算属性 ==========

/** 需支付金额（元） */
const amount = computed<number>(() => Number(help.value?.totalReward || 0) || 0)
/** 免费发布（金额为 0） */
const isFree = computed<boolean>(() => amount.value <= 0)
/** 金额整数位 / 小数位（大字排版用） */
const amountInt = computed<string>(() => money(amount.value).split('.')[0])
const amountDec = computed<string>(() => money(amount.value).split('.')[1])
/** 是否展示底部支付栏 */
const showPaybar = computed<boolean>(
  () => !loading.value && !error.value && !!help.value && help.value.status === 0
)
/** 支付按钮文案 */
const payButtonText = computed<string>(() => (isFree.value ? '免费发布' : '立即支付'))

// ========== 方法 ==========

/** 金额格式化：保留两位小数 */
function money(n: number | string | null | undefined): string {
  return Number(n || 0).toFixed(2)
}

/** 求助状态码 → 文案 */
function statusText(s: number): string {
  const map: Record<number, string> = {
    0: '待支付',
    1: '招募中',
    2: '已满员',
    3: '已完成',
    4: '已取消'
  }
  return map[s] || '未知'
}

/** 当前时间：yyyy-MM-dd HH:mm:ss */
function formatNow(): string {
  const d = new Date()
  const p = (x: number): string => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 选择支付方式 */
function selectMethod(m: 'balance' | 'alipay'): void {
  selected.value = m
}

/** 返回上一页（无来路时回首页） */
function goBack(): void {
  if (window.history.state?.back) router.back()
  else router.push('/')
}

/** 加载求助详情 + 账户余额 */
async function load(): Promise<void> {
  if (!helpId.value) {
    loading.value = false
    error.value = true
    retryable.value = false
    errorTitle.value = '缺少订单号'
    errorMsg.value = '链接不完整，请从发布页面重新进入'
    return
  }

  loading.value = true
  error.value = false
  help.value = null

  try {
    // 静默：失败由下面的错误卡片兜底，避免"卡片 + 提示"重复打扰
    const res = await getHelpDetail(helpId.value, true)
    help.value = res.data
  } catch (err) {
    loading.value = false
    error.value = true
    retryable.value = true
    errorTitle.value = '加载失败'
    errorMsg.value = err instanceof Error ? err.message : '网络异常，请稍后重试'
    return
  }

  // 余额（登录态才有；失败就隐藏余额，不打扰用户）
  try {
    const res = await getUserInfo()
    if (res.data?.balance != null) balance.value = Number(res.data.balance)
  } catch {
    /* 忽略 */
  }

  // 默认选中：免费或余额充足选余额，余额不足选支付宝
  if (isFree.value) selected.value = 'balance'
  else if (balance.value != null && balance.value < amount.value) selected.value = 'alipay'
  else selected.value = 'balance'

  loading.value = false
}

/** 重新加载 */
function retry(): void {
  load()
}

/** 显示遮罩 */
function showOverlay(title: string, sub = ''): void {
  overlay.show = true
  overlay.title = title
  overlay.sub = sub
}

/** 隐藏遮罩 */
function hideOverlay(): void {
  overlay.show = false
}

/** 顶部挂一条常驻提示（不遮挡页面，用户仍可操作/查看本页） */
function showPayTip(message: string): void {
  closePayTip()
  payTip = ElMessage({
    message,
    type: 'info',
    duration: 0, // 常驻，支付结束后手动关闭
    showClose: true,
    grouping: true
  })
}

/** 关闭常驻提示 */
function closePayTip(): void {
  if (payTip) {
    payTip.close()
    payTip = null
  }
}

/** 清理轮询 */
function clearPoll(): void {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/**
 * 后台静默轮询支付状态：支付宝付完（异步通知/查单把状态置为 1）后本页自动跳结果页
 * 不弹遮罩，只在顶部挂一条提示，避免挡住页面
 */
function startPolling(): void {
  let tries = 0
  clearPoll()
  showPayTip('已打开支付宝收银台，请在弹窗中完成支付；支付完成后本页会自动跳转')

  pollTimer = setInterval(async () => {
    tries++
    try {
      const res = await getHelpDetail(helpId.value, true)
      if (res.data?.status === 1) {
        clearPoll()
        closePayTip()
        goSuccess()
      }
    } catch {
      /* 忽略单次失败 */
    }
    // 最多 5 分钟
    if (tries >= 150) {
      clearPoll()
      closePayTip()
      paying.value = false
      ElMessage.warning('未查询到支付结果；若已付款，请稍后在「我的订单」中查看')
    }
  }, 2000)
}

/** 点击支付：按选中的方式分流（不限制点击次数，点几次就走几次） */
function confirmPay(): void {
  if (selected.value === 'balance') {
    if (balance.value != null && balance.value < amount.value) {
      ElMessage.warning('余额不足，请选择支付宝支付')
      return
    }
    doBalancePay()
    return
  }

  // 支付宝：必须在"点击"的同步上下文里先把窗口开出来，
  // 否则等接口回来再 window.open 就不算用户手势，会被浏览器当弹窗拦截
  const win = window.open('', '_blank')
  if (!win) {
    ElMessage.warning('浏览器拦截了新窗口，请允许本站弹窗后重试')
    return
  }
  doAlipayPay(win)
}

/** 余额支付：POST /pay/{helpId}，成功后跳支付成功页 */
async function doBalancePay(): Promise<void> {
  paying.value = true
  showOverlay('正在支付...', '请稍候，正在从余额扣款')
  try {
    await payHelp(helpId.value)
    goSuccess()
  } catch {
    /* 拦截器已提示 */
    paying.value = false
    hideOverlay()
  }
}

/**
 * 支付宝支付：POST /pay/alipay/{helpId} 取回收银台表单 HTML，
 * 写入已经开好的窗口（表单自带自动提交脚本，会跳到支付宝收银台支付），
 * 然后后台静默轮询求助状态，支付完成后本页自动跳结果页。
 * 注意：整个过程不弹遮挡层，本页保持可读可操作
 * @param win confirmPay 里同步打开的新窗口
 */
async function doAlipayPay(win: Window): Promise<void> {
  paying.value = true

  let form = ''
  try {
    const res = await payHelpByAlipay(helpId.value)
    form = res.data
  } catch {
    /* 拦截器已提示 */
    paying.value = false
    win.close() // 关掉空窗口，别留一个白页
    return
  }

  if (!form) {
    paying.value = false
    win.close()
    ElMessage.warning('未获取到支付表单，请稍后重试')
    return
  }

  // 写入支付宝返回的表单（含自动提交脚本），该窗口随即进入收银台
  win.document.open()
  win.document.write(form)
  win.document.close()

  startPolling()
}

/** 支付成功 → 携带订单信息跳结果页 */
function goSuccess(): void {
  router.push({
    name: 'pay-success',
    query: {
      helpId: String(helpId.value),
      amount: money(amount.value),
      payTime: formatNow()
    }
  })
}

// ========== 生命周期 ==========

onMounted(() => {
  helpId.value = Number(route.query.helpId || 0)
  load()
})

onBeforeUnmount(() => {
  clearPoll()
  closePayTip()
})
</script>

<template>
  <div class="pay-page">
    <!-- 背景装饰 -->
    <div class="bg-decor">
      <div class="blob blob-1"></div>
      <div class="blob blob-2"></div>
      <div class="blob blob-3"></div>
    </div>

    <!-- 顶部导航 -->
    <div class="navbar">
      <span class="back" @click="goBack()"><span class="arrow">‹</span>返回</span>
      <span class="title">待支付</span>
      <span class="spacer"></span>
    </div>

    <!-- 主体 -->
    <main class="container">
      <!-- 加载中 -->
      <div v-if="loading" class="card state">
        <div class="spinner"></div>
        加载中...
      </div>

      <!-- 加载失败 / 缺少订单号 -->
      <div v-else-if="error" class="card state">
        <div class="icon info">!</div>
        <div class="title">{{ errorTitle }}</div>
        <div class="sub">{{ errorMsg }}</div>
        <router-link v-if="!retryable" class="btn btn-primary" to="/">返回首页</router-link>
        <button v-else type="button" class="btn btn-primary" @click="retry">重新加载</button>
      </div>

      <!-- 已支付 / 非待支付 -->
      <div v-else-if="help && help.status !== 0" class="card state">
        <div class="icon" :class="help.status === 1 ? 'ok' : 'info'">
          {{ help.status === 1 ? '✓' : '·' }}
        </div>
        <div class="title">
          {{ help.status === 1 ? '支付成功' : '当前状态：' + statusText(help.status) }}
        </div>
        <div class="sub">
          {{ help.status === 1 ? '求助已发布到首页，等待邻居响应' : '该求助已不在待支付状态' }}
        </div>
        <router-link class="btn btn-primary" :to="{ name: 'help-detail', params: { id: helpId } }">
          查看求助
        </router-link>
      </div>

      <!-- 待支付主内容 -->
      <template v-else>
        <!-- 金额主卡片 -->
        <div class="card hero">
          <div class="hero-top">
            <span class="hero-no">订单号：{{ helpId }}</span>
            <span class="badge">待支付</span>
          </div>
          <div class="hero-title">{{ help?.title }}</div>
          <div class="hero-amount-label">{{ isFree ? '本次发布无需支付' : '需支付金额' }}</div>
          <div class="hero-amount">
            <span class="sym">¥</span><span class="int">{{ amountInt }}</span
            ><span class="dec">.{{ amountDec }}</span>
          </div>
        </div>

        <!-- 支付方式 -->
        <div class="card">
          <div class="methods-head">
            <div class="methods-title">选择支付方式</div>
            <div class="methods-sub">支付完成后求助将发布到首页，等待邻居响应</div>
          </div>

          <!-- 余额 / 免费发布 -->
          <div
            class="method"
            :class="{ selected: selected === 'balance' }"
            @click="selectMethod('balance')"
          >
            <div class="method-icon balance">¥</div>
            <div class="method-info">
              <div class="method-name-row">
                <span class="method-name">{{ isFree ? '免费发布' : '余额支付' }}</span>
                <span v-if="isFree" class="pill rec">无需扣款</span>
              </div>
              <div class="method-desc">
                <template v-if="isFree">免费发布，无需扣款</template>
                <template v-else-if="balance == null">使用账户余额直接支付</template>
                <template v-else-if="balance < amount">
                  当前余额 ¥{{ money(balance) }}
                  <span class="insufficient">（余额不足）</span>
                </template>
                <template v-else>当前余额 ¥{{ money(balance) }}</template>
              </div>
            </div>
            <div class="method-check">✓</div>
          </div>

          <!-- 支付宝 -->
          <div
            v-if="!isFree"
            class="method"
            :class="{ selected: selected === 'alipay' }"
            @click="selectMethod('alipay')"
          >
            <div class="method-icon alipay">支</div>
            <div class="method-info">
              <div class="method-name-row">
                <span class="method-name">支付宝支付</span>
                <span class="pill rec">推荐</span>
              </div>
              <div class="method-desc">使用支付宝安全快捷支付</div>
            </div>
            <div class="method-check">✓</div>
          </div>
        </div>

        <!-- 提示 -->
        <div class="note">
          <span class="dot">·</span>
          <span>余额支付将直接从账户余额扣除；支付宝支付将跳转至支付宝收银台完成付款。</span>
        </div>
      </template>
    </main>

    <!-- 底部支付栏 -->
    <div v-show="showPaybar" class="paybar">
      <div class="paybar-inner">
        <div class="paybar-amount">
          <div class="lbl">需支付</div>
          <div class="val"><span class="small">¥</span>{{ money(amount) }}</div>
        </div>
        <button type="button" class="btn btn-ghost" @click="goBack()">稍后支付</button>
        <button class="btn btn-primary" @click="confirmPay()">
          {{ payButtonText }}
        </button>
      </div>
    </div>

    <!-- 支付中遮罩（仅余额支付的瞬时动作用） -->
    <div v-if="overlay.show" class="overlay">
      <div class="overlay-box">
        <div class="spinner"></div>
        <div class="t1">{{ overlay.title }}</div>
        <div class="t2">{{ overlay.sub }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pay-page {
  --brand: #00b96b;
  --brand-dark: #009a58;
  --brand-soft: #e6f8ef;
  --alipay: #1677ff;
  --amount: #ff6b00;
  --danger: #f56c6c;
  --text: #1f2329;
  --text-2: #4e5969;
  --muted: #86909c;
  --border: #e5e6eb;
  --bg: #f4f6f8;
  min-height: 100vh;
  color: var(--text);
  background: var(--bg);
  -webkit-font-smoothing: antialiased;
}

/* ==================== 背景装饰 ==================== */
.bg-decor {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
  background: linear-gradient(165deg, #e8fbf1 0%, #f5f7f9 46%, #eaf2ff 100%);
}
.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  opacity: 0.5;
}
.blob-1 {
  width: 340px;
  height: 340px;
  left: -110px;
  top: -90px;
  background: #b8f0d2;
}
.blob-2 {
  width: 300px;
  height: 300px;
  right: -90px;
  top: 120px;
  background: #c4dcff;
}
.blob-3 {
  width: 260px;
  height: 260px;
  left: 20%;
  bottom: -120px;
  background: #ffecd2;
  opacity: 0.35;
}

/* ==================== 顶部导航 ==================== */
.navbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  height: 52px;
  padding: 0 14px;
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: saturate(180%) blur(14px);
  border-bottom: 1px solid rgba(0, 0, 0, 0.04);
}
.navbar .back {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--text-2);
  font-size: 15px;
  cursor: pointer;
  user-select: none;
  transition: color 0.15s;
}
.navbar .back:hover {
  color: var(--brand);
}
.navbar .back .arrow {
  font-size: 20px;
  line-height: 1;
}
.navbar .title {
  flex: 1;
  text-align: center;
  font-size: 16px;
  font-weight: 600;
}
.navbar .spacer {
  width: 58px;
}

/* ==================== 容器 ==================== */
.container {
  position: relative;
  z-index: 1;
  max-width: 640px;
  margin: 0 auto;
  padding: 18px 16px calc(96px + env(safe-area-inset-bottom));
}

.card {
  background: #fff;
  border-radius: 20px;
  padding: 20px;
  margin-bottom: 14px;
  box-shadow: 0 8px 28px rgba(23, 32, 43, 0.06);
  animation: rise 0.4s ease both;
}
@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* ==================== 金额主卡片 ==================== */
.hero {
  position: relative;
  overflow: hidden;
  background: linear-gradient(135deg, #00c97a 0%, #00a561 100%);
  color: #fff;
  padding: 22px 20px 24px;
}
.hero::after {
  content: '';
  position: absolute;
  right: -40px;
  top: -40px;
  width: 180px;
  height: 180px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.12);
}
.hero::before {
  content: '';
  position: absolute;
  right: 30px;
  bottom: -70px;
  width: 150px;
  height: 150px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
}
.hero-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  position: relative;
  z-index: 1;
}
.hero-no {
  font-size: 12.5px;
  color: rgba(255, 255, 255, 0.85);
  letter-spacing: 0.3px;
}
.badge {
  font-size: 12px;
  padding: 3px 12px;
  border-radius: 999px;
  background: rgba(255, 183, 0, 0.18);
  color: #ffe6b3;
  border: 1px solid rgba(255, 205, 100, 0.5);
  font-weight: 600;
}
.hero-title {
  font-size: 17px;
  font-weight: 600;
  line-height: 1.5;
  margin-bottom: 18px;
  position: relative;
  z-index: 1;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.hero-amount-label {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.85);
  margin-bottom: 6px;
  position: relative;
  z-index: 1;
}
.hero-amount {
  display: flex;
  align-items: baseline;
  position: relative;
  z-index: 1;
}
.hero-amount .sym {
  font-size: 24px;
  font-weight: 600;
  margin-right: 4px;
}
.hero-amount .int {
  font-size: 46px;
  font-weight: 800;
  letter-spacing: -1px;
  line-height: 1;
}
.hero-amount .dec {
  font-size: 24px;
  font-weight: 600;
}

/* ==================== 支付方式 ==================== */
.methods-head {
  margin-bottom: 14px;
}
.methods-title {
  font-size: 16px;
  font-weight: 700;
}
.methods-sub {
  font-size: 12.5px;
  color: var(--muted);
  margin-top: 4px;
}

.method {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 15px 16px;
  border: 1.5px solid var(--border);
  border-radius: 16px;
  margin-bottom: 12px;
  cursor: pointer;
  transition: all 0.18s ease;
  background: #fff;
  position: relative;
}
.method:last-child {
  margin-bottom: 0;
}
.method:hover {
  border-color: #c9cdd4;
  transform: translateY(-1px);
  box-shadow: 0 6px 18px rgba(23, 32, 43, 0.07);
}
.method.selected {
  border-color: var(--brand);
  background: linear-gradient(0deg, rgba(0, 185, 107, 0.05), rgba(0, 185, 107, 0.02));
  box-shadow: 0 8px 22px rgba(0, 185, 107, 0.14);
}
.method-icon {
  width: 46px;
  height: 46px;
  border-radius: 13px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 19px;
  font-weight: 800;
}
.method-icon.balance {
  background: linear-gradient(135deg, #00c97a, #00a561);
}
.method-icon.alipay {
  background: linear-gradient(135deg, #38a1ff, #0f6aff);
}
.method-info {
  flex: 1;
  min-width: 0;
}
.method-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.method-name {
  font-size: 16px;
  font-weight: 600;
}
.pill {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  font-weight: 600;
}
.pill.rec {
  color: var(--brand);
  background: var(--brand-soft);
}
.method-desc {
  font-size: 12.5px;
  color: var(--muted);
  margin-top: 4px;
}
.method-desc .insufficient {
  color: var(--danger);
  font-weight: 500;
}
.method-check {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  flex-shrink: 0;
  border: 2px solid #d0d3d9;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 13px;
  transition: all 0.18s ease;
}
.method.selected .method-check {
  background: var(--brand);
  border-color: var(--brand);
}

/* ==================== 底部提示 ==================== */
.note {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  font-size: 12px;
  color: var(--muted);
  padding: 2px 6px;
}
.note .dot {
  color: var(--brand);
  font-weight: 700;
  line-height: 1.5;
}

/* ==================== 底部支付栏 ==================== */
.paybar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 20;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: saturate(180%) blur(14px);
  border-top: 1px solid rgba(0, 0, 0, 0.05);
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
}
.paybar-inner {
  max-width: 640px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: 12px;
}
.paybar-amount {
  flex-shrink: 0;
}
.paybar-amount .lbl {
  font-size: 11.5px;
  color: var(--muted);
}
.paybar-amount .val {
  font-size: 22px;
  font-weight: 800;
  color: var(--amount);
  line-height: 1.1;
}
.paybar-amount .val .small {
  font-size: 14px;
}
.btn {
  border: none;
  cursor: pointer;
  font-family: inherit;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.18s ease;
  text-decoration: none;
}
.btn-ghost {
  flex-shrink: 0;
  height: 46px;
  padding: 0 18px;
  border-radius: 14px;
  background: #fff;
  border: 1px solid var(--border);
  color: var(--text-2);
  font-size: 14px;
  font-weight: 600;
}
.btn-ghost:hover {
  border-color: #c9cdd4;
}
.btn-primary {
  flex: 1;
  height: 48px;
  border-radius: 14px;
  background: linear-gradient(135deg, #00c97a, #00a561);
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 1px;
  box-shadow: 0 8px 20px rgba(0, 185, 107, 0.3);
}
.btn-primary:hover {
  transform: translateY(-1px);
  box-shadow: 0 10px 24px rgba(0, 185, 107, 0.4);
}
.btn-primary:active {
  transform: translateY(0);
}
.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
}

/* ==================== 状态态（加载/结果） ==================== */
.state {
  text-align: center;
  padding: 40px 10px;
}
.state .spinner {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: 3px solid #e6f8ef;
  border-top-color: var(--brand);
  margin: 0 auto 14px;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.state .icon {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  margin: 0 auto 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 38px;
  color: #fff;
}
.state .icon.ok {
  background: linear-gradient(135deg, #00c97a, #00a561);
  box-shadow: 0 12px 28px rgba(0, 185, 107, 0.3);
}
.state .icon.info {
  background: #c9cdd4;
}
.state .title {
  font-size: 19px;
  font-weight: 700;
  margin-bottom: 8px;
}
.state .sub {
  font-size: 13.5px;
  color: var(--muted);
  margin-bottom: 22px;
  line-height: 1.6;
}
.state .btn-primary {
  flex: none;
  padding: 0 28px;
  height: 44px;
}

/* ==================== 支付中遮罩 ==================== */
.overlay {
  position: fixed;
  inset: 0;
  z-index: 50;
  background: rgba(0, 0, 0, 0.42);
  display: flex;
  align-items: center;
  justify-content: center;
  backdrop-filter: blur(3px);
}
.overlay-box {
  background: #fff;
  border-radius: 20px;
  padding: 30px 28px;
  width: 300px;
  text-align: center;
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.2);
  animation: rise 0.25s ease;
}
.overlay-box .spinner {
  margin: 0 auto 16px;
}
.overlay-box .t1 {
  font-size: 16px;
  font-weight: 700;
  margin-bottom: 8px;
}
.overlay-box .t2 {
  font-size: 12.5px;
  color: var(--muted);
  line-height: 1.7;
}

@media (max-width: 480px) {
  .hero-amount .int {
    font-size: 40px;
  }
  .method {
    padding: 13px 14px;
  }
}
</style>
