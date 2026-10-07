import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { UserConfig } from 'vite'
import react from '@vitejs/plugin-react'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const browserTestsDir = path.resolve(__dirname, 'browser-tests')

/**
 * 布局回归端口：默认 5174。并行切片通过单一 KK_LAYOUT_PORT 覆盖，
 * playwright.layout.config 读取同一变量，保证 webServer 与 baseURL 一致。
 */
const layoutPort = Number(process.env.KK_LAYOUT_PORT) || 5174

const harnessInputs = Object.fromEntries(
  fs
    .readdirSync(browserTestsDir)
    .filter((file) => file.endsWith('-harness.html'))
    .sort()
    .map((file) => [
      file.replace(/\.html$/, ''),
      path.resolve(browserTestsDir, file),
    ])
)

const config: UserConfig = {
  appType: 'mpa',
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  build: {
    outDir: path.resolve(__dirname, '../reports/layout-site'),
    emptyOutDir: true,
    rollupOptions: {
      input: harnessInputs,
    },
  },
  preview: {
    host: '127.0.0.1',
    port: layoutPort,
    strictPort: true,
  },
}

export default config
