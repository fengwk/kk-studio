import { execFileSync } from 'node:child_process'
import path from 'node:path'
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

    // 独立静态基座：执行专用构建与 preview，以真实 harness URL 进行 HTTP readiness 探测，严禁复用已有服务器（fail-closed）
    expect(layoutConfig.webServer).toEqual({
      command: 'npm run build:layout && npm run preview:layout',
      url: 'http://127.0.0.1:5174/browser-tests/chat-layout-harness.html',
      reuseExistingServer: false,
      timeout: 15000,
    })
  })

  it('configures isolated static mpa build and strict preview in vite.layout.config', () => {
    // 在真实 Node 子进程内通过 Vite 官方 loadConfigFromFile 解析配置，避免 jsdom realm 污染与 source regex 虚假断言
    const script = `
      import { loadConfigFromFile } from 'vite';
      import path from 'node:path';
      const loaded = await loadConfigFromFile({ command: 'build', mode: 'production' }, path.resolve('vite.layout.config.ts'));
      const config = loaded?.config ?? {};
      console.log(JSON.stringify({
        appType: config.appType,
        outDir: config.build?.outDir,
        emptyOutDir: config.build?.emptyOutDir,
        inputs: config.build?.rollupOptions?.input,
        preview: config.preview,
      }));
    `
    const stdout = execFileSync(process.execPath, ['--input-type=module', '-e', script], {
      cwd: __dirname,
      encoding: 'utf8',
      timeout: 10000,
    })
    const effective = JSON.parse(stdout)

    expect(effective.appType).toBe('mpa')
    expect(effective.outDir).toBe(path.resolve(__dirname, '../reports/layout-site'))
    expect(effective.emptyOutDir).toBe(true)

    expect(effective.preview).toEqual({
      host: '127.0.0.1',
      port: 5174,
      strictPort: true,
    })

    const inputKeys = Object.keys(effective.inputs || {}).sort()
    expect(inputKeys.length).toBeGreaterThanOrEqual(8)
    expect(inputKeys).toContain('chat-layout-harness')
    expect(inputKeys).toContain('debug-preview-harness')
    for (const key of inputKeys) {
      expect(effective.inputs[key]).toBe(path.resolve(__dirname, `browser-tests/${key}.html`))
    }
  })
})
