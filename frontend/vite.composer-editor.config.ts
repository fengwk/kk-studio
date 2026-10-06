import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { UserConfig } from 'vite'
import react from '@vitejs/plugin-react'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

/**
 * ComposerEditor 真实 contenteditable 回归专用构建：只打包编辑器 harness，
 * 静态 preview 使用独立端口（5182），与共享 layout 预览（5174）互不干扰。
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
    outDir: path.resolve(__dirname, '../reports/composer-editor-site'),
    emptyOutDir: true,
    rollupOptions: {
      input: {
        'composer-editor-harness': path.resolve(
          __dirname,
          'browser-tests/composer-editor-harness.html',
        ),
      },
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 5182,
    strictPort: true,
  },
}

export default config
