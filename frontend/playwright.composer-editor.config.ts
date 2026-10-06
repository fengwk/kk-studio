import { defineConfig } from '@playwright/test'

/**
 * ComposerEditor / ThreadComposer 真实 contenteditable 回归（独立于共享 layout 套件）。
 *
 * 运行（在 `frontend/`）：
 *   npx vite build --config vite.composer-editor.config.ts
 *   npx playwright test --config playwright.composer-editor.config.ts
 */
export default defineConfig({
  testDir: './browser-tests',
  testMatch: '**/composer-editor-editing.browser.ts',
  outputDir: '../reports/composer-editor',
  use: {
    baseURL: 'http://127.0.0.1:5182',
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npx vite preview --config vite.composer-editor.config.ts',
    url: 'http://127.0.0.1:5182/browser-tests/composer-editor-harness.html',
    reuseExistingServer: false,
    timeout: 30000,
  },
})
