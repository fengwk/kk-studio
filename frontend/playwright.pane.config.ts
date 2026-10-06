import { defineConfig } from '@playwright/test'

/** Pane 控制面真实浏览器回归：独立端口与独立报告目录，不影响 layout / install 矩阵。 */
export default defineConfig({
  testDir: './browser-tests',
  testMatch: 'pane-control.browser.ts',
  outputDir: '../reports/pane',
  use: {
    baseURL: 'http://127.0.0.1:5184',
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npm run build:pane && npx vite preview --config vite.pane.config.ts',
    url: 'http://127.0.0.1:5184/browser-tests/pane-control-harness.html',
    reuseExistingServer: false,
    timeout: 60000,
  },
})
