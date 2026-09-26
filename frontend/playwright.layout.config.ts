import { defineConfig } from '@playwright/test'

/** 离线布局回归：使用真实浏览器，不启动 Backend 或调用模型。 */
export default defineConfig({
  testDir: './browser-tests',
  testMatch: '**/*.pw.ts',
  outputDir: '../reports/layout',
  use: { browserName: 'chromium', headless: true },
})
