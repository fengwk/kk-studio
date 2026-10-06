import { defineConfig } from '@playwright/test'

/**
 * Slice C 的独立浏览器回归入口：独立端口 5176、独立输出目录与独立构建站点，
 * 与其它 harness 配置错峰，不共享 report 目录。
 */
export default defineConfig({
  testDir: './browser-tests',
  testMatch: 'tool-card.pw.ts',
  outputDir: '../reports/tool-card',
  use: {
    baseURL: 'http://127.0.0.1:5176',
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npx vite build --config vite.tool-card.config.ts && npx vite preview --config vite.tool-card.config.ts',
    url: 'http://127.0.0.1:5176/browser-tests/tool-card-harness.html',
    reuseExistingServer: false,
    timeout: 120_000,
  },
})
