import vue from '@vitejs/plugin-vue'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = process.env.DEV_API_TARGET || env.DEV_API_TARGET || 'http://127.0.0.1:8080'
  return {
    plugins: [vue()],
    server: { proxy: { '/api': { target, changeOrigin: true } } },
    test: { environment: 'node', include: ['tests/**/*.test.ts'] },
  }
})
