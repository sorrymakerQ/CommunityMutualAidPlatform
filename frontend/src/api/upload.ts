/**
 * 文件上传接口（图片传到阿里云 OSS）
 */
import request, { type Result } from '@/request'

/**
 * 上传单张图片
 * @param folder 存储目录：'avatar' | 'help'
 */
export function uploadImage(file: File, folder: 'avatar' | 'help' = 'help') {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('folder', folder)
  return request<Result<{ url: string }>>('/upload/image', {
    method: 'POST',
    data: formData,
    // 必须显式覆盖实例默认的 application/json，
    // 由 axios 自动补上正确的 boundary
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}
