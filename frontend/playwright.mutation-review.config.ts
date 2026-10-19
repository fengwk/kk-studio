import { defineConfig } from '@playwright/test'

/**
 * Mutation Review 独立浏览器回归入口：独立端口 5178、独立输出目录，不污染其它切片 harness。
 */
export default defineConfig({
  testDir: './browser-tests',
  testMatch: 'mutation-review.pw.ts',
  outputDir: '../reports/mutation-review',
  use: {
    baseURL: 'http://127.0.0.1:5178',
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npx vite build --config vite.mutation-review.config.ts && npx vite preview --config vite.mutation-review.config.ts',
    url: 'http://127.0.0.1:5178/browser-tests/mutation-review-harness.html',
    reuseExistingServer: false,
    timeout: 120_000,
  },
})
