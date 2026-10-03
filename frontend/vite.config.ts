import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// Vite 构建配置
export default defineConfig({
  plugins: [
    vue(),
    // Element Plus API 自动导入：ElMessage / ElMessageBox / ElLoading 等
    AutoImport({
      resolvers: [ElementPlusResolver()],
      dts: 'src/auto-imports.d.ts'
    }),
    // Element Plus 组件按需自动注册：<el-button> 直接用，不用手写 import
    Components({
      resolvers: [ElementPlusResolver()],
      dts: 'src/components.d.ts'
    })
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    port: 3000,
    proxy: {
      // 代理后端API请求
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
