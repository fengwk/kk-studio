import { defineConfig } from '@playwright/test'

/** 真实浏览器回归：通过轻量静态 preview 加载预构建真实 React 组件，不启动后端或模型 */
export default defineConfig({
  testDir: './browser-tests',
  testMatch: '**/*.pw.ts',
  outputDir: '../reports/layout',
  use: {
    baseURL: 'http://127.0.0.1:5174',
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npm run build:layout && npm run preview:layout',
    url: 'http://127.0.0.1:5174/browser-tests/chat-layout-harness.html',
    reuseExistingServer: false,
    timeout: 15000,
  },
})
