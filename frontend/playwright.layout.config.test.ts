import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import layoutConfig from './playwright.layout.config'

describe('playwright layout config', () => {
  // 零重试保留首轮失败 trace，避免无现场
  it('enables retain-on-failure trace to capture initial failure diagnostics without retries', () => {
    expect(layoutConfig.use?.trace).toBe('retain-on-failure')
  })

  // 失败时记录截图辅助排查
  it('captures screenshots only on failure to assist layout error diagnosis', () => {
    expect(layoutConfig.use?.screenshot).toBe('only-on-failure')
  })

  // 验证 webServer 命令、真实 harness URL readiness 及 fail-closed 隔离，不放宽超时与重试
  it('preserves default layout test execution and webServer behaviors without loosening thresholds', () => {
    expect(layoutConfig.testDir).toBe('./browser-tests')
    expect(layoutConfig.testMatch).toBe('**/*.pw.ts')
    expect(layoutConfig.outputDir).toBe('../reports/layout')
    expect(layoutConfig.use?.baseURL).toBe('http://127.0.0.1:5174')
    expect(layoutConfig.use?.browserName).toBe('chromium')
    expect(layoutConfig.use?.headless).toBe(true)
    expect(layoutConfig.retries).toBeUndefined()
    expect(layoutConfig.timeout).toBeUndefined()
    expect(layoutConfig.workers).toBeUndefined()

    expect(layoutConfig.webServer).toEqual({
      command: 'npm run build:layout && npm run preview:layout',
      url: 'http://127.0.0.1:5174/browser-tests/chat-layout-harness.html',
      reuseExistingServer: false,
      timeout: 15000,
    })
  })

  // 在 Node 子进程通过 loadConfigFromFile 验证 vite.layout.config 生效配置，避免 jsdom realm 限制
  it('configures isolated static mpa build and strict preview in vite.layout.config', () => {
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

    // 输入集合精确等于 browser-tests 下实际 harness html 文件列表
    const browserTestsDir = path.resolve(__dirname, 'browser-tests')
    const harnessFiles = fs
      .readdirSync(browserTestsDir)
      .filter((file) => file.endsWith('-harness.html'))
      .sort()

    const expectedInputs = Object.fromEntries(
      harnessFiles.map((file) => [file.replace(/\.html$/, ''), path.resolve(browserTestsDir, file)]),
    )

    expect(effective.inputs).toEqual(expectedInputs)
  })
})
