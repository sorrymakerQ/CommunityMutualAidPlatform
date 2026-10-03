<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { updateUserInfo } from '@/api/user'
import { uploadImage } from '@/api/upload'
import { regions, regionName } from '@/data/regions'
import type { UpdateUserParams } from '@/types'
import type { FormInstance, FormRules, UploadRawFile } from 'element-plus'

const router = useRouter()
const userStore = useUserStore()
const userInfo = computed(() => userStore.userInfo)

const formRef = ref<FormInstance>()
const submitting = ref(false)
const uploadingAvatar = ref(false)
const formData = reactive<UpdateUserParams>({
  nickname: '',
  avatar: '',
  gender: 0,
  addressId: undefined,
  intro: ''
})

/** 省市区级联选择器配置（静态数据：value=id，emitPath:false 直接取叶子 value（区县 id）） */
const cascaderProps = {
  value: 'value',
  label: 'label',
  children: 'children',
  emitPath: false
}

/** 校验规则 */
const rules: FormRules = {
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { min: 2, max: 20, message: '昵称长度 2~20 位', trigger: 'blur' }
  ]
}

onMounted(() => {
  if (userInfo.value) {
    formData.nickname = userInfo.value.nickname || ''
    formData.avatar = userInfo.value.avatar || ''
    formData.gender = userInfo.value.gender || 0
    formData.addressId = userInfo.value.addressId
    formData.intro = userInfo.value.intro || ''
  }
})

/** 头像上传：走 uploadImage API，替代 el-upload 默认的 action */
async function customUploadAvatar(options: { file: UploadRawFile }): Promise<void> {
  uploadingAvatar.value = true
  try {
    const res = await uploadImage(options.file, 'avatar')
    if (res.data?.url) {
      formData.avatar = res.data.url
      ElMessage.success('头像上传成功')
    }
  } catch {
    /* 拦截器已提示 */
  } finally {
    uploadingAvatar.value = false
  }
}

/** 上传前校验 */
function beforeAvatarUpload(file: UploadRawFile): boolean {
  if (!file.type.startsWith('image/')) {
    ElMessage.error('只能上传图片')
    return false
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('头像不能超过 5MB')
    return false
  }
  return true
}

/** 保存 */
async function handleSave(): Promise<void> {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  submitting.value = true
  try {
    await updateUserInfo({
      nickname: formData.nickname?.trim(),
      avatar: formData.avatar,
      gender: formData.gender,
      addressId: formData.addressId,
      intro: formData.intro?.trim()
    })
    await userStore.fetchUserInfo()
    ElMessage.success('保存成功')
    router.back()
  } catch {
    /* 拦截器已提示 */
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="edit-page">
    <div class="edit-container">
      <div class="edit-card">
        <h1 class="page-title">编辑个人资料</h1>

        <el-form
          ref="formRef"
          :model="formData"
          :rules="rules"
          label-position="top"
          size="large"
        >
          <!-- 头像上传 -->
          <el-form-item label="头像">
            <div class="avatar-row">
              <el-avatar
                :size="72"
                :src="formData.avatar || '/default-avatar.svg'"
              />
              <el-upload
                :show-file-list="false"
                :http-request="customUploadAvatar as any"
                :before-upload="beforeAvatarUpload"
                accept="image/*"
              >
                <el-button :loading="uploadingAvatar">
                  <el-icon><Upload /></el-icon>
                  <span style="margin-left: 4px;">更换头像</span>
                </el-button>
              </el-upload>
            </div>
          </el-form-item>

          <!-- 昵称 -->
          <el-form-item label="昵称" prop="nickname" required>
            <el-input
              v-model="formData.nickname"
              maxlength="20"
              show-word-limit
              placeholder="请输入昵称"
              clearable
            >
              <template #prefix>
                <el-icon><User /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <!-- 性别 -->
          <el-form-item label="性别">
            <el-radio-group v-model="formData.gender">
              <el-radio-button :value="1">男</el-radio-button>
              <el-radio-button :value="2">女</el-radio-button>
              <el-radio-button :value="0">保密</el-radio-button>
            </el-radio-group>
          </el-form-item>

          <!-- 所在地区（省市区级联） -->
          <el-form-item label="所在地区">
            <el-cascader
              v-model="formData.addressId"
              :props="cascaderProps"
              :options="regions as any"
              placeholder="请选择省 / 市 / 区县"
              clearable
              style="width: 100%;"
            />
            <div v-if="userInfo?.addressId" class="address-current">
              当前地址：{{ regionName(userInfo.addressId) }}
            </div>
          </el-form-item>

          <!-- 简介 -->
          <el-form-item label="个人简介">
            <el-input
              v-model="formData.intro"
              type="textarea"
              maxlength="200"
              show-word-limit
              :autosize="{ minRows: 4, maxRows: 8 }"
              placeholder="介绍一下自己，让邻居更了解你..."
            />
          </el-form-item>

          <!-- 手机号（只读） -->
          <el-form-item label="手机号">
            <el-input
              :model-value="userInfo?.phone || ''"
              disabled
              readonly
            >
              <template #prefix>
                <el-icon><Iphone /></el-icon>
              </template>
              <template #append>
                <span class="hint">暂不支持修改</span>
              </template>
            </el-input>
          </el-form-item>

          <!-- 按钮 -->
          <div class="btn-row">
            <el-button @click="router.back()">取消</el-button>
            <el-button
              type="primary"
              :loading="submitting"
              @click="handleSave"
            >
              {{ submitting ? '保存中...' : '保存修改' }}
            </el-button>
          </div>
        </el-form>
      </div>
    </div>
  </div>
</template>

<style scoped>
.edit-page { padding: 24px 0; }
.edit-container { max-width: 680px; margin: 0 auto; padding: 0 24px; }

.edit-card {
  background: #fff;
  border-radius: 12px;
  padding: 36px 40px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.05);
}

.page-title {
  font-size: 22px;
  font-weight: 700;
  color: #1a1a1a;
  margin-bottom: 24px;
}

/* form-item 底部间距收紧 */
:deep(.el-form-item) {
  margin-bottom: 20px;
}
:deep(.el-form-item__label) {
  font-weight: 600;
  color: #303133;
  padding-bottom: 6px;
}

/* 头像行 */
.avatar-row {
  display: flex;
  align-items: center;
  gap: 16px;
}

/* 手机号后缀提示 */
.hint {
  font-size: 12px;
  color: #bbb;
}

/* 当前地址提示 */
.address-current {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}

/* 按钮行 */
.btn-row {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 12px;
  padding-top: 24px;
  border-top: 1px solid #f0f0f0;
}
</style>
