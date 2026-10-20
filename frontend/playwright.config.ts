import { defineConfig } from '@playwright/test'
import { resolveBrowserPort } from './browser-test-config'

const browserPort = resolveBrowserPort()

/**
 * KK Studio 前端收敛后的统一 Playwright 浏览器测试配置。
 *
 * 通过具名 projects 划分既有套件（layout / pane / install / plugin / composer-editor /
 * compaction / subagent-tree / tool-card），保留各套件的匹配规则、输出路径、超时与截图策略，
 * 统一在单一 preview 静态基座上运行。
 */
export default defineConfig({
  testDir: './browser-tests',
  outputDir: '../reports/browser',
  use: {
    baseURL: `http://127.0.0.1:${browserPort}`,
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npm run preview:browser',
    url: `http://127.0.0.1:${browserPort}/browser-tests/chat-layout-harness.html`,
    reuseExistingServer: false,
    timeout: 30000,
  },
  projects: [
    {
      name: 'layout',
      testMatch: '**/*.pw.ts',
      testIgnore: [
        '**/environment-install.pw.ts',
        '**/tool-card.pw.ts',
        '**/plugin-*.pw.ts',
      ],
      outputDir: '../reports/layout',
      use: {
        screenshot: 'only-on-failure',
      },
    },
    {
      name: 'pane',
      testMatch: ['**/pane-control.browser.ts', '**/chat-branch.browser.ts'],
      outputDir: '../reports/pane',
      timeout: 60000,
      use: {
        screenshot: 'only-on-failure',
      },
    },
    {
      name: 'install',
      testMatch: '**/environment-install.pw.ts',
      outputDir: '../reports/install-browser',
      use: {
        permissions: ['clipboard-read', 'clipboard-write'],
        screenshot: 'only-on-failure',
      },
    },
    {
      name: 'plugin',
      testMatch: ['**/plugin-connect.pw.ts', '**/plugin-workflow-config.pw.ts'],
      outputDir: '../reports/browser/plugins',
      use: {
        screenshot: 'on',
      },
    },
    {
      name: 'composer-editor',
      testMatch: '**/composer-editor-editing.browser.ts',
      outputDir: '../reports/composer-editor',
      use: {
        screenshot: 'only-on-failure',
      },
    },
    {
      name: 'compaction',
      testMatch: '**/compaction-card.browser.ts',
      outputDir: '../reports/compaction',
      use: {
        screenshot: 'only-on-failure',
      },
    },
    {
      name: 'subagent-tree',
      testMatch: '**/subagent-tree.browser.ts',
      outputDir: '../reports/subagent-tree',
      use: {
        screenshot: 'only-on-failure',
      },
    },
    {
      name: 'tool-card',
      testMatch: '**/tool-card.pw.ts',
      outputDir: '../reports/tool-card',
      timeout: 120000,
      use: {
        screenshot: 'only-on-failure',
      },
    },
  ],
})
