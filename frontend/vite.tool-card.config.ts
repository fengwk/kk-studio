import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { UserConfig } from 'vite'
import react from '@vitejs/plugin-react'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

/**
 * Slice C 专用浏览器 harness 站点：只构建 tool-card harness，使用独立端口与输出目录，
 * 避免与其它切片的 harness（chat-layout / debug-preview）共享构建产物或端口。
 */
const config: UserConfig = {
  appType: 'mpa',
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  build: {
    outDir: path.resolve(__dirname, '../reports/tool-card-site'),
    emptyOutDir: true,
    rollupOptions: {
      input: {
        'tool-card-harness': path.resolve(__dirname, 'browser-tests/tool-card-harness.html'),
      },
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 5176,
    strictPort: true,
  },
}

export default config
