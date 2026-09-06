<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import type { FormInstance, FormRules } from 'element-plus'

const router = useRouter()
const userStore = useUserStore()

// 表单模型
const formRef = ref<FormInstance>()
const formData = reactive({
  phone: '',
  nickname: '',
  password: '',
  confirmPassword: ''
})
const loading = ref(false)

// 校验规则：手机号 / 昵称 / 密码 / 确认密码
// 确认密码用 validator 做交叉校验，依赖 formData.password
const rules: FormRules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { min: 2, max: 20, message: '昵称长度 2~20 位', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度 6~20 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== formData.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

async function handleRegister() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return  // 校验未通过，Element Plus 会自动显示错误
  }

  loading.value = true
  try {
    const ok = await userStore.register(
      formData.phone,
      formData.password,
      formData.nickname.trim()
    )
    if (ok) {
      ElMessage.success('注册成功，即将跳转登录')
      setTimeout(() => router.push('/login'), 1200)
    }
    // 注册失败的具体原因（比如"该手机号已注册"）由 request 拦截器统一提示
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="register-page">
    <el-card class="register-card" shadow="always" :body-style="{ padding: 0 }">
      <div class="card-inner">
        <!-- 左侧：品牌 -->
        <div class="card-left">
          <div class="brand">
            <div class="brand-icon">🏘️</div>
            <h1>社区互助平台</h1>
            <p>加入社区，互帮互助</p>
          </div>
          <div class="brand-tips">
            <div class="tip-item">
              <el-icon><CircleCheckFilled /></el-icon>
              <span>发布身边的求助任务</span>
            </div>
            <div class="tip-item">
              <el-icon><CircleCheckFilled /></el-icon>
              <span>帮助邻居，积累信用分</span>
            </div>
            <div class="tip-item">
              <el-icon><CircleCheckFilled /></el-icon>
              <span>实时消息，即时沟通</span>
            </div>
          </div>
        </div>

        <!-- 右侧：注册表单 -->
        <div class="card-right">
          <h2 class="form-title">创建账号</h2>

          <el-form
            ref="formRef"
            :model="formData"
            :rules="rules"
            label-position="top"
            size="large"
            @keyup.enter="handleRegister"
          >
            <el-form-item label="手机号" prop="phone">
              <el-input
                v-model="formData.phone"
                type="tel"
                maxlength="11"
                placeholder="请输入 11 位手机号"
                clearable
              >
                <template #prefix>
                  <el-icon><Iphone /></el-icon>
                </template>
              </el-input>
            </el-form-item>

            <el-form-item label="昵称" prop="nickname">
              <el-input
                v-model="formData.nickname"
                maxlength="20"
                show-word-limit
                placeholder="给自己起个名字（2-20 字）"
                clearable
              >
                <template #prefix>
                  <el-icon><User /></el-icon>
                </template>
              </el-input>
            </el-form-item>

            <el-form-item label="密码" prop="password">
              <el-input
                v-model="formData.password"
                type="password"
                maxlength="20"
                placeholder="6-20 位密码"
                show-password
              >
                <template #prefix>
                  <el-icon><Lock /></el-icon>
                </template>
              </el-input>
            </el-form-item>

            <el-form-item label="确认密码" prop="confirmPassword">
              <el-input
                v-model="formData.confirmPassword"
                type="password"
                maxlength="20"
                placeholder="再次输入密码"
                show-password
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
              @click="handleRegister"
            >
              {{ loading ? '注册中...' : '注 册' }}
            </el-button>
          </el-form>

          <div class="switch-row">
            <span>已有账号？</span>
            <el-link type="primary" :underline="false" @click="router.push('/login')">
              立即登录
              <el-icon><ArrowRight /></el-icon>
            </el-link>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.register-page {
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

.register-card {
  width: 820px;
  max-width: 100%;
  border-radius: 16px;
  overflow: hidden;
  border: none;
}

.card-inner {
  display: flex;
  min-height: 620px;
}

/* ==================== 左侧品牌区 ==================== */
.card-left {
  width: 340px;
  flex-shrink: 0;
  color: #fff;
  background:
    radial-gradient(400px 200px at 80% 20%, rgba(255,255,255,0.15), transparent 70%),
    linear-gradient(160deg, #00B96B 0%, #009a57 60%, #007a44 100%);
  padding: 44px 32px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 40px;
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

.brand-tips {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.tip-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  opacity: 0.92;
}
.tip-item .el-icon {
  font-size: 16px;
  opacity: 0.85;
}

/* ==================== 右侧表单区 ==================== */
.card-right {
  flex: 1;
  padding: 40px 44px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  background: #fff;
}
.form-title {
  font-size: 22px;
  font-weight: 700;
  color: #1a1a1a;
  margin-bottom: 20px;
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

/* 缩小 form-item 间距，让 4 个字段能舒服排下 */
:deep(.el-form-item) {
  margin-bottom: 16px;
}
</style>
