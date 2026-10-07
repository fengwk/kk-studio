import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import layoutConfig from './playwright.layout.config'

/** 设置/清除 KK_LAYOUT_PORT；port 为 undefined 表示变量缺失。 */
function applyLayoutPort(port: string | undefined) {
  if (port === undefined) {
    delete process.env.KK_LAYOUT_PORT
  } else {
    process.env.KK_LAYOUT_PORT = port
  }
}

/** 子进程环境：显式区分“变量缺失”与“变量为空/非法”。 */
function childEnv(port: string | undefined) {
  const env = { ...process.env }
  applyLayoutPortOn(env, port)
  return env
}

function applyLayoutPortOn(env: NodeJS.ProcessEnv, port: string | undefined) {
  if (port === undefined) {
    delete env.KK_LAYOUT_PORT
  } else {
    env.KK_LAYOUT_PORT = port
  }
}

/** 在 Node 子进程通过 loadConfigFromFile 读取 vite.layout.config 的 preview 配置。 */
function loadVitePreviewConfig(port: string | undefined) {
  const script = `
    import { loadConfigFromFile } from 'vite';
    import path from 'node:path';
    const loaded = await loadConfigFromFile({ command: 'build', mode: 'production' }, path.resolve('vite.layout.config.ts'));
    console.log(JSON.stringify(loaded?.config?.preview ?? null));
  `
  const stdout = execFileSync(process.execPath, ['--input-type=module', '-e', script], {
    cwd: __dirname,
    encoding: 'utf8',
    timeout: 10000,
    env: childEnv(port),
  })
  return JSON.parse(stdout)
}

/**
 * 用给定 KK_LAYOUT_PORT 加载布局配置，验证该变量同时驱动 preview 与 baseURL/webServer。
 * 非法端口会让两个配置一起抛错，断言由调用方负责。
 */
async function loadLayoutConfigsWithPort(port: string | undefined) {
  const previous = process.env.KK_LAYOUT_PORT
  applyLayoutPort(port)
  try {
    vi.resetModules()
    const playwright = (await import('./playwright.layout.config')).default
    return { playwright, preview: loadVitePreviewConfig(port) }
  } finally {
    applyLayoutPort(previous)
    vi.resetModules()
  }
}

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
      command: 'npm run preview:layout',
      url: 'http://127.0.0.1:5174/browser-tests/chat-layout-harness.html',
      reuseExistingServer: false,
      timeout: 15000,
    })
  })

  // 并行切片隔离：单一 KK_LAYOUT_PORT 必须同时驱动 webServer、baseURL 与 vite preview。
  it('routes layout port through the single KK_LAYOUT_PORT override', async () => {
    const { playwright, preview } = await loadLayoutConfigsWithPort('5185')

    expect(playwright.use?.baseURL).toBe('http://127.0.0.1:5185')
    expect(playwright.webServer).toEqual({
      command: 'npm run preview:layout',
      url: 'http://127.0.0.1:5185/browser-tests/chat-layout-harness.html',
      reuseExistingServer: false,
      timeout: 15000,
    })
    expect(preview).toEqual({ host: '127.0.0.1', port: 5185, strictPort: true })
  })

  // 变量缺失时才用默认 5174，默认命令不变。
  it('falls back to the default port only when KK_LAYOUT_PORT is absent', async () => {
    const { playwright, preview } = await loadLayoutConfigsWithPort(undefined)

    expect(playwright.use?.baseURL).toBe('http://127.0.0.1:5174')
    expect(preview).toEqual({ host: '127.0.0.1', port: 5174, strictPort: true })
  })

  // 显式配置的非法端口必须明确失败，不得静默退回默认端口。
  it('rejects invalid KK_LAYOUT_PORT values instead of silently using the default', async () => {
    for (const invalid of ['', 'http-5185', '5185 ', '0', '70000']) {
      await expect(loadLayoutConfigsWithPort(invalid)).rejects.toThrow(/KK_LAYOUT_PORT/)
    }

    expect(() => loadVitePreviewConfig('0')).toThrow(/KK_LAYOUT_PORT/)
    expect(() => loadVitePreviewConfig('abc')).toThrow(/KK_LAYOUT_PORT/)
  })

  it('builds fresh assets before starting the browser server readiness budget', () => {
    // 编译是测试准备步骤，不应占用静态服务器的启动预算或复用旧产物。
    const manifest = JSON.parse(fs.readFileSync(path.resolve(__dirname, 'package.json'), 'utf8'))
    expect(manifest.scripts['test:layout']).toBe(
      'npm run build:layout && playwright test --config playwright.layout.config.ts',
    )
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
      env: childEnv(undefined),
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
