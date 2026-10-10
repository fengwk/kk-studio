import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { UserConfig } from 'vite'
import react from '@vitejs/plugin-react'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

const port = Number(process.env.KK_PANE_PORT ?? process.env.KK_BROWSER_PORT ?? 5184)

/** Pane 控制面真实浏览器回归：只构建该切片自己的基座，独立端口 5184。 */
const config: UserConfig = {
  appType: 'mpa',
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  build: {
    outDir: path.resolve(__dirname, '../reports/pane-site'),
    emptyOutDir: true,
    rollupOptions: {
      input: {
        'pane-control-harness': path.resolve(__dirname, 'browser-tests/pane-control-harness.html'),
        'chat-branch-harness': path.resolve(__dirname, 'browser-tests/chat-branch-harness.html'),
      },
    },
  },
  preview: {
    host: '127.0.0.1',
    port,
    strictPort: true,
  },
}

export default config
