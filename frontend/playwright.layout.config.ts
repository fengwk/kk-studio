import { defineConfig } from '@playwright/test'

/** 真实浏览器回归：通过轻量测试 Vite webServer 加载真实 React 组件，不启动后端或模型 */
export default defineConfig({
  testDir: './browser-tests',
  testMatch: '**/*.pw.ts',
  outputDir: '../reports/layout',
  use: {
    baseURL: 'http://127.0.0.1:5174',
    browserName: 'chromium',
    headless: true,
  },
  webServer: {
    command: 'npx vite --port 5174',
    port: 5174,
    reuseExistingServer: !process.env.CI,
    timeout: 15000,
  },
})
