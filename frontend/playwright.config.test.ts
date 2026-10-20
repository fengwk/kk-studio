import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import playwrightConfig, { resolveBrowserPort } from './playwright.config'

/** 设置/清除 KK_BROWSER_PORT；port 为 undefined 表示变量缺失。 */
function applyBrowserPort(port: string | undefined) {
  if (port === undefined) {
    delete process.env.KK_BROWSER_PORT
  } else {
    process.env.KK_BROWSER_PORT = port
  }
}

/** 子进程环境变量隔离。 */
function childEnv(port: string | undefined) {
  const env = { ...process.env }
  if (port === undefined) {
    delete env.KK_BROWSER_PORT
  } else {
    env.KK_BROWSER_PORT = port
  }
  return env
}

/** 在 Node 子进程通过 loadConfigFromFile 读取 vite.browser.config 的配置，避免 jsdom realm 限制。 */
function loadViteBrowserConfig(port: string | undefined) {
  const script = `
    import { loadConfigFromFile } from 'vite';
    import path from 'node:path';
    const loaded = await loadConfigFromFile({ command: 'build', mode: 'production' }, path.resolve('vite.browser.config.ts'));
    const config = loaded?.config ?? {};
    console.log(JSON.stringify({
      appType: config.appType,
      outDir: config.build?.outDir,
      emptyOutDir: config.build?.emptyOutDir,
      inputs: config.build?.rollupOptions?.input,
      preview: config.preview,
      server: config.server,
    }));
  `
  const stdout = execFileSync(process.execPath, ['--input-type=module', '-e', script], {
    cwd: __dirname,
    encoding: 'utf8',
    timeout: 10000,
    env: childEnv(port),
  })
  return JSON.parse(stdout)
}

describe('playwright projects inventory', () => {
  const expectedProjects = [
    'layout',
    'pane',
    'install',
    'plugin',
    'composer-editor',
    'compaction',
    'subagent-tree',
    'tool-card',
  ]

  // 验证原有的 8 套独立套件全部收敛为具名 project，无遗漏无重复
  it('registers all 8 required projects without duplicates or omissions', () => {
    const projectNames = playwrightConfig.projects?.map((p) => p.name) ?? []
    expect(projectNames).toEqual(expectedProjects)
    expect(new Set(projectNames).size).toBe(expectedProjects.length)
  })

  // 验证各 project 保留原有的输出目录、超时、截图与权限配置
  it('preserves suite-specific output directories, timeouts, and options', () => {
    const projects = playwrightConfig.projects ?? []
    const byName = Object.fromEntries(projects.map((p) => [p.name, p]))

    expect(byName.layout.outputDir).toBe('../reports/layout')
    expect(byName.pane.outputDir).toBe('../reports/pane')
    expect(byName.pane.timeout).toBe(60000)
    expect(byName.install.outputDir).toBe('./.reports/install-browser')
    expect(byName.install.use?.permissions).toEqual(['clipboard-read', 'clipboard-write'])
    expect(byName.plugin.outputDir).toBe('../reports/browser/plugins')
    expect(byName.plugin.use?.screenshot).toBe('on')
    expect(byName['composer-editor'].outputDir).toBe('../reports/composer-editor')
    expect(byName.compaction.outputDir).toBe('../reports/compaction')
    expect(byName['subagent-tree'].outputDir).toBe('../reports/subagent-tree')
    expect(byName['tool-card'].outputDir).toBe('../reports/tool-card')
    expect(byName['tool-card'].timeout).toBe(120000)
  })

  // 验证全局 webServer 配置与默认 URL
  it('configures unified preview webServer with fail-closed timeout and strict readiness', () => {
    expect(playwrightConfig.webServer).toEqual({
      command: 'npm run preview:browser',
      url: 'http://127.0.0.1:5174/browser-tests/chat-layout-harness.html',
      reuseExistingServer: false,
      timeout: 30000,
    })
  })
})

describe('test file matching coverage and exclusivity', () => {
  // 简易 glob 匹配，用于断言测试文件精确落入且仅落入一个 project
  function matchesPattern(filename: string, pattern: string): boolean {
    if (pattern === '**/*.pw.ts') {
      return filename.endsWith('.pw.ts')
    }
    if (pattern === '**/environment-install.pw.ts' || pattern === 'environment-install.pw.ts') {
      return filename === 'environment-install.pw.ts'
    }
    if (pattern === '**/tool-card.pw.ts' || pattern === 'tool-card.pw.ts') {
      return filename === 'tool-card.pw.ts'
    }
    if (pattern === '**/plugin-*.pw.ts') {
      return filename.startsWith('plugin-') && filename.endsWith('.pw.ts')
    }
    if (pattern === '**/composer-editor-editing.browser.ts' || pattern === 'composer-editor-editing.browser.ts') {
      return filename === 'composer-editor-editing.browser.ts'
    }
    if (pattern === '**/compaction-card.browser.ts' || pattern === 'compaction-card.browser.ts') {
      return filename === 'compaction-card.browser.ts'
    }
    if (pattern === '**/subagent-tree.browser.ts' || pattern === 'subagent-tree.browser.ts') {
      return filename === 'subagent-tree.browser.ts'
    }
    if (pattern.startsWith('**/')) {
      return filename === pattern.slice(3)
    }
    return filename === pattern
  }

  function matchesProject(filename: string, project: NonNullable<typeof playwrightConfig.projects>[number]): boolean {
    const matchesPatterns = (patterns: unknown) => {
      if (typeof patterns === 'string') return matchesPattern(filename, patterns)
      if (Array.isArray(patterns)) return patterns.some((p) => matchesPattern(filename, String(p)))
      return false
    }

    const matched = matchesPatterns(project.testMatch)
    if (!matched) return false

    if (project.testIgnore) {
      const ignored = matchesPatterns(project.testIgnore)
      if (ignored) return false
    }
    return true
  }

  // 验证 browser-tests 下全部 37 个测试文件被项目划分且互不重叠
  it('maps every browser test file to exactly one project with zero overlap or omission', () => {
    const browserTestsDir = path.resolve(__dirname, 'browser-tests')
    const allTestFiles = fs
      .readdirSync(browserTestsDir)
      .filter((file) => (file.endsWith('.pw.ts') || file.endsWith('.browser.ts')) && file !== 'fixture.ts')
      .sort()

    expect(allTestFiles.length).toBe(37)

    const projects = playwrightConfig.projects ?? []
    for (const testFile of allTestFiles) {
      const matchingProjects = projects.filter((project) => matchesProject(testFile, project))
      expect(
        matchingProjects.map((p) => p.name),
        `file ${testFile} must match exactly 1 project`,
      ).toHaveLength(1)
    }
  })
})

describe('browser port contract (KK_BROWSER_PORT)', () => {
  // 缺省时默认使用 5174
  it('defaults to 5174 when KK_BROWSER_PORT is undefined', () => {
    const previous = process.env.KK_BROWSER_PORT
    applyBrowserPort(undefined)
    try {
      expect(resolveBrowserPort()).toBe(5174)
    } finally {
      applyBrowserPort(previous)
    }
  })

  // 显式指定合法端口生效
  it('resolves valid decimal port string', () => {
    const previous = process.env.KK_BROWSER_PORT
    applyBrowserPort('5188')
    try {
      expect(resolveBrowserPort()).toBe(5188)
    } finally {
      applyBrowserPort(previous)
    }
  })

  // 显式传入非法值时受控抛错，绝不静默回退
  it('throws descriptive error on invalid port values without silent fallback', () => {
    const previous = process.env.KK_BROWSER_PORT
    try {
      for (const invalid of ['', 'http-5174', '5174 ', '0', '65536', 'abc', '-1']) {
        applyBrowserPort(invalid)
        expect(() => resolveBrowserPort()).toThrow(/KK_BROWSER_PORT/)
      }
    } finally {
      applyBrowserPort(previous)
    }
  })

  // 验证通过环境变量覆盖时，vite browser preview 端口与配置同步
  it('passes port override to vite browser config', () => {
    const effective = loadViteBrowserConfig('5190')
    expect(effective.preview).toEqual({ host: '127.0.0.1', port: 5190, strictPort: true })
    expect(effective.server).toEqual({ host: '127.0.0.1', port: 5190, strictPort: true })
  })
})

describe('vite browser build contract', () => {
  // 验证所有 HTML 输入（包括 compaction-card.html）统一被收集为 MPA 入口
  it('registers all 29 HTML harnesses including compaction-card.html', () => {
    const effective = loadViteBrowserConfig(undefined)
    const inputs = effective.inputs
    const keys = Object.keys(inputs)
    expect(keys).toContain('compaction-card')
    expect(keys).toContain('chat-layout-harness')
    expect(keys).toContain('tool-card-harness')
    expect(keys).toContain('subagent-tree-harness')
    expect(keys).toContain('environment-install-harness')
    expect(keys).toContain('pane-control-harness')
    expect(keys).toContain('composer-editor-harness')
    expect(keys.length).toBe(29)
  })

  // 验证静态构建目标与 MPA 模式
  it('configures mpa appType and isolated outDir', () => {
    const effective = loadViteBrowserConfig(undefined)
    expect(effective.appType).toBe('mpa')
    expect(effective.outDir).toBe(path.resolve(__dirname, '../reports/browser-site'))
    expect(effective.emptyOutDir).toBe(true)
  })
})

describe('package.json scripts contract', () => {
  // 验证 CI 门禁保留 test:layout 且调用方式指向 project layout
  it('preserves test:layout script gate pointing to unified project', () => {
    const manifest = JSON.parse(fs.readFileSync(path.resolve(__dirname, 'package.json'), 'utf8'))
    expect(manifest.scripts['test:layout']).toBe('npm run build:browser && playwright test --project layout')
    expect(manifest.scripts['test:pane']).toBe('npm run build:browser && playwright test --project pane')
    expect(manifest.scripts['test:install:browser']).toBe('npm run build:browser && playwright test --project install')
    expect(manifest.scripts['build:browser']).toBe('vite build --config vite.browser.config.ts')
    expect(manifest.scripts['preview:browser']).toBe('vite preview --config vite.browser.config.ts')
    // 旧配置脚本必须不存在
    expect(manifest.scripts['build:layout']).toBeUndefined()
    expect(manifest.scripts['preview:layout']).toBeUndefined()
    expect(manifest.scripts['build:pane']).toBeUndefined()
  })
})
