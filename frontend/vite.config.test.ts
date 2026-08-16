import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('vite dev proxy', () => {
  it('rewrites the application event WebSocket origin to the backend target', () => {
    const source = readFileSync(path.resolve(process.cwd(), 'vite.config.ts'), 'utf8')
    const apiProxy = source.match(/'\/api': \{([\s\S]*?)\n\s{6}\},/)

    expect(apiProxy?.[1]).toContain('rewriteWsOrigin: true')
    expect(apiProxy?.[1]).toContain('ws: true')
  })
})
