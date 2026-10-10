import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './browser-tests',
  testMatch: 'loading-skeleton.pw.ts',
  outputDir: '../reports/loading-skeleton',
  use: {
    baseURL: 'http://127.0.0.1:5189',
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'on',
  },
  webServer: {
    command: 'npx vite build --config vite.loading-skeleton.config.ts && npx vite preview --config vite.loading-skeleton.config.ts',
    url: 'http://127.0.0.1:5189/browser-tests/loading-skeleton-harness.html',
    reuseExistingServer: false,
    timeout: 60000,
  },
})
