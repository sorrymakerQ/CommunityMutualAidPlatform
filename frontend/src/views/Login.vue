<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import type { FormInstance, FormRules } from 'element-plus'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

// 表单模型
const formRef = ref<FormInstance>()
const formData = reactive({ phone: '', password: '' })
const loading = ref(false)

// 校验规则：手机号必填 + 11 位数字；密码必填 + 长度 6~20
const rules: FormRules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度 6~20 位', trigger: 'blur' }
  ]
}

const demoAccounts = [
  { role: '普通用户', phone: '13800000001', password: '123456', name: '张大爷', icon: '👴' },
  { role: '普通用户', phone: '13800000002', password: '123456', name: '小李子', icon: '🧑' },
  { role: '管理员',   phone: '13800000008', password: '123456', name: '管理员阿明', icon: '👑' },
]

function fillAccount(acc: typeof demoAccounts[0]) {
  formData.phone = acc.phone
  formData.password = acc.password
  // 填充后清一下已有的校验错误提示
  formRef.value?.clearValidate()
}

async function handleLogin() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return  // 校验未通过，表单会自动显示错误提示
  }

  loading.value = true
  try {
    const ok = await userStore.login(formData.phone, formData.password)
    if (ok) {
      ElMessage.success('登录成功')
      router.push((route.query.redirect as string) || '/')
    }
    // 登录失败的具体原因（手机号未注册 / 密码错误 / 账号禁用）由 request 拦截器统一提示，
    // 这里不再重复弹提示，避免同时冒两条。
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always" :body-style="{ padding: 0 }">
      <div class="card-inner">
        <!-- 左侧：品牌 + 演示账号 -->
        <div class="card-left">
          <div class="brand">
            <div class="brand-icon">🏘️</div>
            <h1>社区互助平台</h1>
            <p>邻里互助，温暖社区</p>
          </div>

          <div class="demo-section">
            <div class="demo-title">演示账号 · 点击填充</div>
            <div
              v-for="acc in demoAccounts"
              :key="acc.phone"
              class="demo-card"
              @click="fillAccount(acc)"
            >
              <span class="demo-card-icon">{{ acc.icon }}</span>
              <div class="demo-card-body">
                <div class="demo-card-role">
                  {{ acc.role }}
                  <el-tag v-if="acc.role === '管理员'" size="small" type="warning" effect="dark" style="margin-left: 6px;">
                    admin
                  </el-tag>
                </div>
                <div class="demo-card-phone">{{ acc.phone }}</div>
                <div class="demo-card-pwd">密码 {{ acc.password }}</div>
              </div>
            </div>
          </div>
        </div>

        <!-- 右侧：登录表单 -->
        <div class="card-right">
          <h2 class="form-title">账号登录</h2>

          <el-form
            ref="formRef"
            :model="formData"
            :rules="rules"
            label-position="top"
            size="large"
            @keyup.enter="handleLogin"
          >
            <el-form-item label="手机号" prop="phone">
              <el-input
                v-model="formData.phone"
                type="tel"
                maxlength="11"
                placeholder="请输入手机号"
                clearable
              >
                <template #prefix>
                  <el-icon><Iphone /></el-icon>
                </template>
              </el-input>
            </el-form-item>

            <el-form-item label="密码" prop="password">
              <el-input
                v-model="formData.password"
                type="password"
                placeholder="请输入密码"
                show-password
                clearable
              >
                <template #prefix>
                  <el-icon><Lock /></el-icon>
                </template>
              </el-input>
            </el-form-item>

            <el-button
              type="primary"
              size="large"
              :loading="loading"
              class="submit-btn"
              @click="handleLogin"
            >
              {{ loading ? '登录中...' : '登 录' }}
            </el-button>
          </el-form>

          <div class="switch-row">
            <span>还没有账号？</span>
            <el-link type="primary" :underline="false" @click="router.push('/register')">
              立即注册
              <el-icon><ArrowRight /></el-icon>
            </el-link>
          </div>
          <div class="switch-row">
            <el-link :underline="false" @click="router.push('/')">进入首页</el-link>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(1200px 600px at 10% 10%, rgba(64, 158, 255, 0.12), transparent 60%),
    radial-gradient(1000px 500px at 90% 90%, rgba(103, 194, 58, 0.15), transparent 60%),
    linear-gradient(135deg, #f4f7fa 0%, #eaf4ee 100%);
  padding: 24px;
}

.login-card {
  width: 820px;
  max-width: 100%;
  border-radius: 16px;
  overflow: hidden;
  border: none;
}

.card-inner {
  display: flex;
  min-height: 540px;
}

/* ==================== 左侧品牌区 ==================== */
.card-left {
  width: 340px;
  flex-shrink: 0;
  color: #fff;
  background:
    radial-gradient(400px 200px at 80% 20%, rgba(255,255,255,0.15), transparent 70%),
    linear-gradient(160deg, #00B96B 0%, #009a57 60%, #007a44 100%);
  padding: 36px 28px;
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.brand { text-align: center; }
.brand-icon {
  font-size: 56px;
  line-height: 1;
  margin-bottom: 10px;
}
.brand h1 {
  font-size: 26px;
  font-weight: 700;
  margin-bottom: 6px;
  letter-spacing: 2px;
}
.brand p {
  font-size: 13px;
  opacity: 0.88;
}

/* 演示账号 */
.demo-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.demo-title {
  font-size: 11px;
  opacity: 0.75;
  letter-spacing: 2px;
  margin-bottom: 4px;
}
.demo-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  background: rgba(255,255,255,0.12);
  border: 1px solid rgba(255,255,255,0.22);
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
}
.demo-card:hover {
  background: rgba(255,255,255,0.22);
  transform: translateX(3px);
}
.demo-card-icon { font-size: 24px; flex-shrink: 0; }
.demo-card-body { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.demo-card-role { font-size: 11px; opacity: 0.85; letter-spacing: 1px; display: flex; align-items: center; }
.demo-card-phone { font-size: 14px; font-weight: 600; font-family: 'Courier New', monospace; letter-spacing: 0.5px; }
.demo-card-pwd { font-size: 11px; opacity: 0.85; font-family: 'Courier New', monospace; }

/* ==================== 右侧表单区 ==================== */
.card-right {
  flex: 1;
  padding: 48px 44px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  background: #fff;
}
.form-title {
  font-size: 22px;
  font-weight: 700;
  color: #1a1a1a;
  margin-bottom: 28px;
}
.submit-btn {
  width: 100%;
  margin-top: 4px;
  font-weight: 600;
  letter-spacing: 4px;
}
.switch-row {
  text-align: center;
  margin-top: 14px;
  font-size: 13px;
  color: #909399;
}
.switch-row .el-link { font-size: 13px; margin-left: 2px; }
</style>
