import { afterEach, describe, expect, test, vi } from 'vitest'
import {
  DarwinSystemProxyResolver,
  interpretDarwinCandidate,
  loadDarwinProxyBindings,
  resetDarwinProxyBindingsForTests,
  type DarwinPacOutcome,
  type DarwinPacSource,
  type DarwinProxyBindings,
  type DarwinProxyCandidate,
} from '../../src/native/system-proxy-darwin.js'

/**
 * macOS 代理状态机用受控端口驱动。真实 CFNetwork 只在 darwin 上做只读集成；
 * 这里的假端口不代表真实 macOS，也不允许把查询失败改写成直连。
 */
class FakeDarwinProxy {
  readonly lookups: string[] = []
  readonly pacs: DarwinPacSource[] = []
  candidates: DarwinProxyCandidate[] = [{ type: 'none', host: null, port: null, username: null, password: null, pacUrl: null, pacScript: null }]
  pacOutcome: DarwinPacOutcome = { kind: 'proxies', proxies: [] }
  copyError: Error | null = null
  loadCount = 0

  bindings(): DarwinProxyBindings {
    this.loadCount += 1
    return {
      copyProxiesForUrl: (uri) => {
        this.lookups.push(uri)
        if (this.copyError !== null) {
          throw this.copyError
        }
        return this.candidates
      },
      executePac: async (source) => {
        this.pacs.push(source)
        return this.pacOutcome
      },
    }
  }
}

function candidate(partial: Partial<DarwinProxyCandidate>): DarwinProxyCandidate {
  return { type: 'http', host: 'proxy.example', port: 8080, username: null, password: null, pacUrl: null, pacScript: null, ...partial }
}

function signal(): AbortSignal {
  return new AbortController().signal
}

afterEach(() => {
  resetDarwinProxyBindingsForTests()
  vi.restoreAllMocks()
  vi.doUnmock('koffi')
  vi.resetModules()
})

describe('darwin system proxy interpretation', () => {
  test('maps an explicit direct candidate to null and an http proxy to a canonical url', () => {
    expect(interpretDarwinCandidate(candidate({ type: 'none' }))).toBeNull()
    expect(interpretDarwinCandidate(candidate({}))).toBe('http://proxy.example:8080')
    expect(interpretDarwinCandidate(candidate({ port: 80 }))).toBe('http://proxy.example:80')
    expect(interpretDarwinCandidate(candidate({ host: '::1', port: 3128 }))).toBe('http://[::1]:3128')
    // kCFProxyTypeHTTPS 是 HTTPS 目标的 CONNECT 代理，不是 TLS-to-proxy。
    expect(interpretDarwinCandidate(candidate({ type: 'https' }))).toBe('http://proxy.example:8080')
  })

  test('fails closed for unsupported proxy types, credentials, and malformed endpoints', () => {
    expect(() => interpretDarwinCandidate(candidate({ type: 'socks' }))).toThrow(/does not support/)
    expect(() => interpretDarwinCandidate(candidate({ type: 'ftp' }))).toThrow(/does not support/)
    expect(() => interpretDarwinCandidate(candidate({ username: 'user' }))).toThrow(/does not support/)
    expect(() => interpretDarwinCandidate(candidate({ password: 'secret' }))).toThrow(/does not support/)
    expect(() => interpretDarwinCandidate(candidate({ host: '', port: 8080 }))).toThrow(/malformed/)
    expect(() => interpretDarwinCandidate(candidate({ port: 0 }))).toThrow(/malformed/)
    expect(() => interpretDarwinCandidate(candidate({ port: NaN }))).toThrow(/malformed/)
    expect(() => interpretDarwinCandidate(candidate({ port: 1.5 }))).toThrow(/malformed/)
    expect(() => interpretDarwinCandidate(candidate({ host: 'user@proxy.example' }))).toThrow(/malformed/)
    expect(() => interpretDarwinCandidate(candidate({ host: 'proxy.example/path' }))).toThrow(/malformed/)
  })
})

describe('darwin system proxy resolver behaviour', () => {
  test('does not load bindings until resolve is used, and close stays idempotent', async () => {
    const fake = new FakeDarwinProxy()
    const resolver = new DarwinSystemProxyResolver(() => fake.bindings())
    await resolver.close()
    await resolver.close()
    expect(fake.loadCount).toBe(0)
  })

  test('resolves one explicit http proxy selected by the operating system', async () => {
    const fake = new FakeDarwinProxy()
    fake.candidates = [candidate({})]
    const resolver = new DarwinSystemProxyResolver(() => fake.bindings())
    await expect(resolver.resolve('https://example.test/path', signal())).resolves.toBe('http://proxy.example:8080')
    expect(fake.lookups).toEqual(['https://example.test/path'])
    expect(fake.loadCount).toBe(1)
    await resolver.close()
  })

  test('maps the macOS Secure Web Proxy selection to an HTTP CONNECT endpoint', async () => {
    const fake = new FakeDarwinProxy()
    fake.candidates = [candidate({ type: 'https', host: 'secure-web-proxy.example', port: 3128 })]
    const resolver = new DarwinSystemProxyResolver(() => fake.bindings())
    await expect(resolver.resolve('https://example.test/', signal())).resolves.toBe('http://secure-web-proxy.example:3128')
    await resolver.close()
  })

  test('returns null only when the operating system selected direct', async () => {
    const fake = new FakeDarwinProxy()
    const resolver = new DarwinSystemProxyResolver(() => fake.bindings())
    await expect(resolver.resolve('http://127.0.0.1/', signal())).resolves.toBeNull()
    await resolver.close()
  })

  test('follows one PAC url and fails when that lookup returns an empty list', async () => {
    const fake = new FakeDarwinProxy()
    fake.candidates = [candidate({ type: 'pac-url', host: null, port: null, pacUrl: 'http://pac.example/proxy.pac' })]
    fake.pacOutcome = { kind: 'proxies', proxies: [] }
    const resolver = new DarwinSystemProxyResolver(() => fake.bindings())
    await expect(resolver.resolve('https://example.test/', signal())).rejects.toThrow(/could not be read/)
    expect(fake.pacs).toEqual([{ kind: 'url', value: 'http://pac.example/proxy.pac' }])
    await resolver.close()
  })

  test('uses the proxy selected by PAC and rejects a second autoconfiguration entry', async () => {
    const fake = new FakeDarwinProxy()
    fake.candidates = [candidate({ type: 'pac-url', host: null, port: null, pacUrl: 'http://pac.example/proxy.pac' })]
    fake.pacOutcome = { kind: 'proxies', proxies: [candidate({ host: 'pac-proxy.example', port: 9 })] }
    const resolver = new DarwinSystemProxyResolver(() => fake.bindings())
    await expect(resolver.resolve('https://example.test/', signal())).resolves.toBe('http://pac-proxy.example:9')

    fake.pacOutcome = { kind: 'proxies', proxies: [candidate({ type: 'pac-url', pacUrl: 'http://nested.example/p.pac' })] }
    await expect(resolver.resolve('https://example.test/next', signal())).rejects.toThrow(/malformed/)
    await resolver.close()
  })

  test('uses CFNetwork for inline PAC scripts instead of executing JavaScript locally', async () => {
    const fake = new FakeDarwinProxy()
    fake.candidates = [candidate({ type: 'pac-script', pacScript: 'function FindProxyForURL() { return "DIRECT"; }' })]
    fake.pacOutcome = { kind: 'proxies', proxies: [candidate({ type: 'none' })] }
    const resolver = new DarwinSystemProxyResolver(() => fake.bindings())
    await expect(resolver.resolve('https://example.test/', signal())).resolves.toBeNull()
    expect(fake.pacs).toEqual([{ kind: 'script', value: fake.candidates[0]!.pacScript }])
    await resolver.close()
  })

  test('a cancelled signal fails one lookup and never echoes the target or proxy', async () => {
    const controller = new AbortController()
    controller.abort()
    const fake = new FakeDarwinProxy()
    const resolver = new DarwinSystemProxyResolver(() => fake.bindings())
    const secret = 'https://user:secret-token@example.test/private'
    await expect(resolver.resolve(secret, controller.signal)).rejects.toThrow(/cancelled/)
    expect(fake.loadCount).toBe(0)
    try {
      await resolver.resolve(secret, controller.signal)
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      expect(message).not.toContain('secret-token')
      expect(message).not.toContain('example.test')
    }
    await resolver.close()
  })

  test('a binding failure is reported once per lookup and does not fall back to direct', async () => {
    const marker = 'Authorization-secret-token'
    const fake = new FakeDarwinProxy()
    fake.copyError = new Error(`native failed for ${marker}`)
    const resolver = new DarwinSystemProxyResolver(() => fake.bindings())
    await expect(resolver.resolve('https://example.test/', signal())).rejects.toThrow(/proxy lookup failed/)
    try {
      await resolver.resolve('https://example.test/', signal())
      throw new Error('binding failure must not resolve')
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      expect(message).not.toContain(marker)
      expect(message).not.toContain('example.test')
    }
    await resolver.close()
  })

  test('loader failure is not cached as a successful direct connection', async () => {
    let loads = 0
    const resolver = new DarwinSystemProxyResolver(() => {
      loads += 1
      throw new Error('CFNetwork header leaked')
    })
    await expect(resolver.resolve('https://example.test/', signal())).rejects.toThrow(/proxy lookup failed/)
    await expect(resolver.resolve('https://example.test/again', signal())).rejects.toThrow(/proxy lookup failed/)
    expect(loads).toBe(2)
    await resolver.close()
  })

  test.runIf(process.platform === 'darwin')('real CFNetwork resolves a public URL', async () => {
    const resolver = new DarwinSystemProxyResolver(loadDarwinProxyBindings)
    const uri = 'https://example.test/system-proxy-probe'
    try {
      const selected = await resolver.resolve(uri, signal())
      expect(selected === null || /^http:\/\/[^@\s]+(?::\d+)?$/.test(selected)).toBe(true)
    } finally {
      await resolver.close()
    }
  })
})
