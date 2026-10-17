import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('vite dev proxy', () => {
  it('binds to loopback and keeps Vite host allowlisting enabled by default', () => {
    const source = readFileSync(path.resolve(process.cwd(), 'vite.config.ts'), 'utf8')
    const server = source.match(/server: \{([\s\S]*?)\n\s{2}\},/)

    expect(server?.[1]).toContain("host: '127.0.0.1'")
    expect(server?.[1]).not.toContain('allowedHosts')
  })

  it('rewrites the application event WebSocket origin to the backend target', () => {
    const source = readFileSync(path.resolve(process.cwd(), 'vite.config.ts'), 'utf8')
    const apiProxy = source.match(/'\/api': \{([\s\S]*?)\n\s{6}\},/)

    expect(apiProxy?.[1]).toContain("target: process.env.API_PROXY_TARGET || 'http://127.0.0.1:8080'")
    expect(apiProxy?.[1]).toContain('changeOrigin: true')
    expect(apiProxy?.[1]).toContain('rewriteWsOrigin: true')
    expect(apiProxy?.[1]).toContain('ws: true')
  })
})

describe('install verification targets', () => {
  it('keeps UI tests and coverage pointed at existing files', () => {
    // 迁移目录后，Vitest 不应悄悄遗漏安装器依赖的 UI 测试或覆盖目标。
    const source = readFileSync(path.resolve(process.cwd(), 'vite.install.config.ts'), 'utf8')
    const targets = [...source.matchAll(/'(src\/[^']+)'/gu)].map(match => match[1])
    expect(targets).toEqual(expect.arrayContaining([
      'src/shared/ui/feedback/Toast.test.tsx',
      'src/shared/ui/controls/Select.test.tsx',
      'src/shared/ui/feedback/Toast.tsx',
    ]))
    for (const target of targets) {
      expect(existsSync(path.resolve(process.cwd(), target)), target).toBe(true)
    }
  })
})
