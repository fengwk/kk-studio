import { describe, expect, it } from 'vitest'
import layoutConfig from './playwright.layout.config'

describe('playwright layout config', () => {
  it('enables retain-on-failure trace to capture initial failure diagnostics without retries', () => {
    // 零重试环境也必须保留首轮失败的 trace，不能依赖 on-first-retry。
    expect(layoutConfig.use?.trace).toBe('retain-on-failure')
  })

  it('captures screenshots only on failure to assist layout error diagnosis', () => {
    // 失败截图补充 DOM 与网络时间线，成功用例不保留额外诊断文件。
    expect(layoutConfig.use?.screenshot).toBe('only-on-failure')
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
