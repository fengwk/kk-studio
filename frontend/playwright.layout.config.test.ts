import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import layoutConfig from './playwright.layout.config'

describe('playwright layout config', () => {
  it('enables retain-on-failure trace to capture initial failure diagnostics without retries', () => {
    // 布局测试在未开启重试的环境下运行，配置必须为 retain-on-failure 才能在首轮失败时落盘 trace.zip。
    // 若配置为 on-first-retry，在 retries=0 或首次超时崩溃时不会留存任何 trace 现场。
    expect(layoutConfig.use?.trace).toBe('retain-on-failure')
    expect(layoutConfig.use?.trace).not.toBe('on-first-retry')

    const source = readFileSync(path.resolve(__dirname, 'playwright.layout.config.ts'), 'utf8')
    expect(source).toContain("trace: 'retain-on-failure'")
    expect(source).not.toContain("trace: 'on-first-retry'")
  })

  it('captures screenshots only on failure to assist layout error diagnosis', () => {
    expect(layoutConfig.use?.screenshot).toBe('only-on-failure')

    const source = readFileSync(path.resolve(__dirname, 'playwright.layout.config.ts'), 'utf8')
    expect(source).toContain("screenshot: 'only-on-failure'")
  })

  it('preserves default layout test execution and webServer behaviors without loosening thresholds', () => {
    expect(layoutConfig.testDir).toBe('./browser-tests')
    expect(layoutConfig.testMatch).toBe('**/*.pw.ts')
    expect(layoutConfig.outputDir).toBe('../reports/layout')
    expect(layoutConfig.use?.baseURL).toBe('http://127.0.0.1:5174')
    expect(layoutConfig.use?.browserName).toBe('chromium')
    expect(layoutConfig.use?.headless).toBe(true)

    // 不引入额外重试或放宽超时设置，保留所有默认 timeout/workers/retries 行为
    expect(layoutConfig.retries).toBeUndefined()
    expect(layoutConfig.timeout).toBeUndefined()
    expect(layoutConfig.workers).toBeUndefined()

    expect(layoutConfig.webServer).toEqual({
      command: 'npx vite --port 5174',
      port: 5174,
      reuseExistingServer: !process.env.CI,
      timeout: 15000,
    })
  })
})
