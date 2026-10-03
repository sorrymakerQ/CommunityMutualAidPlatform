<script setup lang="ts">
/**
 * 发布求助页面
 * 表单进阶范式：radio-button 单选 / el-input / el-input-number / el-switch / el-upload
 */
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { publishHelp, getCategoryList } from '@/api/help'
import { uploadImage } from '@/api/upload'
import { newIdempotencyKey } from '@/request'
import { regions } from '@/data/regions'
import type { Category } from '@/types'
import type { FormInstance, FormRules, UploadRawFile } from 'element-plus'

const router = useRouter()

// ========== 状态 ==========

const categories = ref<Category[]>([])
const formRef = ref<FormInstance>()

const formData = reactive({
  categoryId: 0,
  title: '',
  description: '',
  reward: 0,
  addressId: 0,
  addressDetail: '',
  urgent: 0,
  helperNum: 1
})

/** 已上传图片 URL 列表 */
const imageList = ref<string[]>([])
/** 图片上传状态（true 时禁用上传按钮） */
const uploading = ref(false)
/** 表单提交中 */
const submitting = ref(false)
/** 幂等键：表单挂载时生成，提交复用——双击/回车/重试发出的是同一个键，后端 SETNX 才能识别重复 */
const publishIdemKey = ref(newIdempotencyKey())

/** 省市区级联选择器配置（静态数据：value=id，emitPath:false 直接取叶子 value（区县 id）） */
const cascaderProps = {
  value: 'value',
  label: 'label',
  children: 'children',
  emitPath: false
}

// ========== 校验规则 ==========

const rules: FormRules = {
  categoryId: [
    {
      required: true,
      validator: (_r, v, cb) => (v ? cb() : cb(new Error('请选择求助分类'))),
      trigger: 'change'
    }
  ],
  title: [
    { required: true, message: '请输入求助标题', trigger: 'blur' },
    { min: 5, max: 50, message: '标题长度 5~50 字', trigger: 'blur' }
  ],
  description: [
    { required: true, message: '请输入详细描述', trigger: 'blur' },
    { min: 10, max: 500, message: '描述长度 10~500 字', trigger: 'blur' }
  ],
  addressId: [
    {
      required: true,
      validator: (_r, v, cb) => (v ? cb() : cb(new Error('请选择省市区地址'))),
      trigger: 'change'
    }
  ]
}

// ========== 生命周期 ==========

onMounted(async () => {
  await fetchCategories()
})

// ========== 方法 ==========

async function fetchCategories(): Promise<void> {
  try {
    const res = await getCategoryList()
    console.log(res.data)
    if (res.data) categories.value = res.data
  } catch {
    /* 拦截器已提示 */
  }
}

/**
 * 自定义上传（不走 el-upload 默认的 action，直接调 uploadImage）
 * 这样能复用原来的 OSS 上传逻辑，也能拿到我们后端返回的 URL。
 */
async function customUpload(options: { file: UploadRawFile }): Promise<void> {
  uploading.value = true
  try {
    const res = await uploadImage(options.file, 'help')
    if (res.data?.url) {
      imageList.value.push(res.data.url)
    }
  } catch {
    ElMessage.error(`${options.file.name} 上传失败`)
  } finally {
    uploading.value = false
  }
}

/** 上传前校验：大小 + 类型 */
function beforeUpload(file: UploadRawFile): boolean {
  if (imageList.value.length >= 9) {
    ElMessage.warning('最多上传 9 张图片')
    return false
  }
  if (!file.type.startsWith('image/')) {
    ElMessage.error('只能上传图片')
    return false
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('图片不能超过 5MB')
    return false
  }
  return true
}



/** 删除已上传图片 */
function removeImage(idx: number): void {
  imageList.value.splice(idx, 1)
}

/** 提交发布 */
async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  submitting.value = true
  try {
    const res = await publishHelp({
      categoryId: formData.categoryId,
      title: formData.title.trim(),
      description: formData.description.trim(),
      images: imageList.value,
      reward: formData.reward,
      addressId: formData.addressId,
      addressDetail: formData.addressDetail,
      urgent: formData.urgent,
      helperNum: formData.helperNum
    }, publishIdemKey.value)
    // 后端 publishHelp 返回的 data 是 helpId 本身（数字），不是 { id } 对象
    const helpId = (res as any).data
    ElMessage.success('发布成功！请支付后发布到首页')
    // 发布成功才换新键：之后的"再发布"是新动作；失败时键不变，重试仍是同一动作
    publishIdemKey.value = newIdempotencyKey()
    // 跳到待支付页（路由 /pay，旧地址 /pay.html 由别名兼容）
    setTimeout(() => {
      if (helpId) router.push({ name: 'pay', query: { helpId } })
      else router.push('/')
    }, 800)
  } catch {
    /* 拦截器已提示；幂等键不换，用户重试/重发会被后端识别为同一动作 */
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="publish-page">
    <div class="publish-container">
      <!-- 顶部标题 -->
      <div class="page-header">
        <h1 class="page-title">发布求助</h1>
      </div>

      <el-form
        ref="formRef"
        :model="formData"
        :rules="rules"
        label-position="top"
        size="large"
      >
        <!-- 分类选择 -->
        <div class="form-card">
          <el-form-item label="选择分类" prop="categoryId" required>
            <el-radio-group v-model="formData.categoryId" class="cat-group">
              <el-radio-button
                v-for="cat in categories"
                :key="cat.id"
                :value="cat.id"
              >
                <span class="cat-icon">{{ cat.icon }}</span>
                {{ cat.name }}
              </el-radio-button>
            </el-radio-group>
          </el-form-item>
        </div>

        <!-- 标题 -->
        <div class="form-card">
          <el-form-item label="求助标题" prop="title" required>
            <el-input
              v-model="formData.title"
              maxlength="50"
              show-word-limit
              placeholder="简单概括你需要什么帮助（5-50 字）"
              clearable
            />
          </el-form-item>
        </div>

        <!-- 详细描述 -->
        <div class="form-card">
          <el-form-item label="详细描述" prop="description" required>
            <el-input
              v-model="formData.description"
              type="textarea"
              maxlength="500"
              show-word-limit
              :autosize="{ minRows: 5, maxRows: 10 }"
              placeholder="详细描述你遇到的情况和需要的帮助（10-500 字）"
            />
          </el-form-item>
        </div>

        <!-- 地址（省市区级联） -->
        <div class="form-card">
          <el-form-item label="地址信息" prop="addressId" required>
            <el-cascader
              v-model="formData.addressId"
              :props="cascaderProps"
              :options="regions as any"
              placeholder="请选择省 / 市 / 区县"
              clearable
              style="width: 100%;"
            />
            <span class="hint">按省市区选择地址</span>
          </el-form-item>
          <el-form-item label="详细地址" prop="addressDetail">
            <el-input
              v-model="formData.addressDetail"
              maxlength="100"
              show-word-limit
              placeholder="小区 / 楼栋 / 门牌号等（选填）"
              clearable
            />
          </el-form-item>
        </div>

        <!-- 酬劳 -->
        <div class="form-card">
          <el-form-item label="酬劳金额（选填）">
            <el-input-number
              v-model="formData.reward"
              :min="0"
              :max="9999"
              :step="10"
              :precision="2"
              controls-position="right"
              style="width: 240px;"
            />
            <span class="hint">元 · 打赏能吸引更多邻居帮忙</span>
          </el-form-item>
        </div>

        <!-- 需要人数 -->
        <div class="form-card">
          <el-form-item label="需要人数">
            <el-input-number
              v-model="formData.helperNum"
              :min="1"
              :max="20"
              controls-position="right"
              style="width: 240px;"
            />
            <span class="hint">人 · 需支付总额：¥{{ (formData.reward || 0) * formData.helperNum }}</span>
          </el-form-item>
        </div>

        <!-- 图片上传 -->
        <div class="form-card">
          <el-form-item label="添加图片（选填，最多 9 张）">
            <el-upload
              :file-list="imageList.map((url, i) => ({ name: `image-${i}`, url }))"
              list-type="picture-card"
              :http-request="customUpload as any"
              :before-upload="beforeUpload"
              :on-remove="(_file, list) => imageList = list.map(f => f.url as string)"
              :show-file-list="true"
              accept="image/*"
              multiple
            >
              <el-icon><Plus /></el-icon>
              <template #tip>
                <div class="upload-tip">支持 JPG/PNG，单张不超过 5MB</div>
              </template>
            </el-upload>
          </el-form-item>
        </div>

        <!-- 紧急开关 -->
        <div class="form-card">
          <el-form-item>
            <div class="urgent-row">
              <div class="urgent-info">
                <div class="urgent-label">
                  <el-icon color="#e6a23c"><Warning /></el-icon>
                  标记为紧急
                </div>
                <p class="urgent-desc">紧急求助会优先展示给邻居</p>
              </div>
              <el-switch
                v-model="formData.urgent"
                :active-value="1"
                :inactive-value="0"
                active-color="#f56c6c"
                inactive-color="#dcdfe6"
                inline-prompt
                active-text="紧急"
                inactive-text="普通"
              />
            </div>
          </el-form-item>
        </div>

        <!-- 提交按钮 -->
        <el-button
          type="primary"
          size="large"
          :loading="submitting"
          class="submit-btn"
          @click="handleSubmit"
        >
          {{ submitting ? '发布中...' : '立即发布' }}
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.publish-page {
  min-height: 100%;
  background: #f5f5f5;
  padding: 20px 24px 40px;
}

.publish-container {
  max-width: 800px;
  margin: 0 auto;
}

/* ==================== 顶部 ==================== */
.page-header {
  padding: 4px 0 20px;
}
.page-title {
  font-size: 26px;
  font-weight: 700;
  color: #303133;
  margin: 0;
}

/* ==================== 卡片 ==================== */
.form-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px 24px 4px;
  margin-bottom: 16px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}

/* 卡片内 form-item 底部间距缩到最小 */
.form-card :deep(.el-form-item) {
  margin-bottom: 16px;
}
.form-card :deep(.el-form-item__label) {
  font-weight: 600;
  color: #303133;
  padding-bottom: 8px;
}

/* ==================== 分类横排按钮 ==================== */
.cat-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  width: 100%;
}

/* 让 radio-button 支持换行 + 图标间距 */
.cat-group :deep(.el-radio-button) {
  margin: 0;
}
.cat-group :deep(.el-radio-button__inner) {
  border-radius: 8px !important;
  border: 1px solid #dcdfe6 !important;
  padding: 8px 16px;
  font-weight: normal;
}
.cat-icon {
  margin-right: 4px;
  font-size: 15px;
}

/* ==================== 地址行 ==================== */
.address-row {
  display: flex;
  gap: 10px;
  width: 100%;
}

/* ==================== 酬劳提示 ==================== */
.hint {
  margin-left: 12px;
  font-size: 13px;
  color: #909399;
}

/* ==================== 图片上传 ==================== */
.upload-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 6px;
}

/* ==================== 紧急开关 ==================== */
.urgent-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}
.urgent-info { flex: 1; }
.urgent-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.urgent-desc {
  font-size: 12px;
  color: #909399;
  margin-top: 6px;
}

/* ==================== 提交按钮 ==================== */
.submit-btn {
  width: 100%;
  height: 50px;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 4px;
  border-radius: 12px;
  margin-top: 12px;
}
</style>
