import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './browser-tests',
  testMatch: 'subagent-tree.browser.ts',
  outputDir: '../reports/subagent-tree',
  use: { baseURL: 'http://127.0.0.1:4189', browserName: 'chromium', headless: true },
  webServer: {
    command: 'npx vite --config vite.subagent-tree.config.ts',
    url: 'http://127.0.0.1:4189/browser-tests/subagent-tree-harness.html',
    reuseExistingServer: false,
  },
})
