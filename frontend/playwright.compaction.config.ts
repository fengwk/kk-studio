import { defineConfig } from '@playwright/test'

/**
 * H 切片专用真实浏览器回归：只跑压缩摘要卡片 harness，独立端口 5186，
 * 不进入全 layout 构建与全 layout 矩阵。
 */
export default defineConfig({
  testDir: './browser-tests',
  testMatch: 'compaction-card.browser.ts',
  outputDir: '../reports/compaction',
  use: {
    baseURL: 'http://127.0.0.1:5186',
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npx vite --host 127.0.0.1 --port 5186 --strictPort',
    url: 'http://127.0.0.1:5186/browser-tests/compaction-card.html',
    reuseExistingServer: false,
    timeout: 30000,
  },
})
