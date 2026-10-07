import { defineConfig } from '@playwright/test'

/**
 * 布局回归端口：默认 5174。并行切片通过单一 KK_LAYOUT_PORT 覆盖，
 * vite.layout.config 读取同一变量，保证 webServer 与 baseURL 指向同一端口。
 */
const layoutPort = Number(process.env.KK_LAYOUT_PORT) || 5174

/** 真实浏览器回归：通过轻量静态 preview 加载预构建真实 React 组件，不启动后端或模型 */
export default defineConfig({
  testDir: './browser-tests',
  testMatch: '**/*.pw.ts',
  outputDir: '../reports/layout',
  use: {
    baseURL: `http://127.0.0.1:${layoutPort}`,
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npm run preview:layout',
    url: `http://127.0.0.1:${layoutPort}/browser-tests/chat-layout-harness.html`,
    reuseExistingServer: false,
    timeout: 15000,
  },
})
