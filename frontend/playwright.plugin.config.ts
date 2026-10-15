import { defineConfig } from '@playwright/test'

const pluginPort = 5218

export default defineConfig({
  testDir: './browser-tests',
  testMatch: 'plugin-connect.pw.ts',
  outputDir: '../reports/browser/plugins',
  use: {
    baseURL: `http://127.0.0.1:${pluginPort}`,
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'on',
  },
  webServer: {
    command: 'env KK_LAYOUT_PORT=5218 npm run preview:layout',
    url: `http://127.0.0.1:${pluginPort}/browser-tests/resource-card-harness.html?plugins`,
    reuseExistingServer: false,
    timeout: 15000,
  },
})
