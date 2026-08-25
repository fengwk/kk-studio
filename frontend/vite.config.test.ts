import { readFileSync } from 'node:fs'
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
