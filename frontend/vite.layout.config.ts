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
 * 只在变量缺失时取默认值；显式给出的非法端口直接报错，不静默回退。
 */
function resolveLayoutPort(): number {
  const raw = process.env.KK_LAYOUT_PORT
  if (raw === undefined) {
    return 5174
  }
  if (!/^\d+$/.test(raw)) {
    throw new Error(`KK_LAYOUT_PORT 必须是十进制端口号，当前为 "${raw}"`)
  }
  const port = Number(raw)
  if (port < 1 || port > 65535) {
    throw new Error(`KK_LAYOUT_PORT 必须在 1-65535 之间，当前为 "${raw}"`)
  }
  return port
}

const layoutPort = resolveLayoutPort()

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
