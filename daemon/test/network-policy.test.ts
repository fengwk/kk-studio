import { describe, expect, it } from 'vitest'

import { ProxyConfigurationError, ProxyPolicy, type ProxyChoice } from '../src/network.js'
import type { SystemProxyResolver } from '../src/contracts.js'

/** 只检查真实策略输出，不访问 DNS 或外网。 */

const PROXY_URL = 'http://proxy.invalid:8080'

async function select(policy: ProxyPolicy, target: string): Promise<ProxyChoice> {
  return policy.select(target, new AbortController().signal)
}

async function assertDirect(policy: ProxyPolicy, target: string): Promise<void> {
  expect(await select(policy, target), target).toEqual({ kind: 'direct' })
}

async function assertProxy(policy: ProxyPolicy, target: string): Promise<string> {
  const choice = await select(policy, target)
  expect(choice.kind, target).toBe('proxy')
  return (choice as { kind: 'proxy'; url: string }).url
}

/** 明确直连的系统回退：没有 OS 代理来源。 */
function directResolver(): SystemProxyResolver {
  return {
    async resolve(): Promise<string | null> {
      return null
    },
    async close(): Promise<void> {},
  }
}

/** 模拟系统回退：固定返回一个代理 URL，并记录调用次数与最近一次 uri。 */
function recordingFallback(): {
  resolver: SystemProxyResolver
  calls: () => number
  last: () => string
} {
  let calls = 0
  let last = ''
  return {
    resolver: {
      async resolve(uri: string): Promise<string | null> {
        calls++
        last = uri
        return 'http://os.invalid:9000'
      },
      async close(): Promise<void> {},
    },
    calls: () => calls,
    last: () => last,
  }
}

/** 显式配置的协议代理 + no_proxy 环境入口；两个协议共用同一代理地址。 */
function policy(proxyUrl: string | null, noProxyHosts: string): ProxyPolicy {
  return ProxyPolicy.fromEnvironment(
    { http_proxy: proxyUrl ?? '', https_proxy: proxyUrl ?? '', no_proxy: noProxyHosts },
    directResolver(),
  )
}

function assertRedacted(operation: () => unknown): void {
  let error: unknown
  try {
    operation()
  } catch (caught) {
    error = caught
  }
  expect(error).toBeInstanceOf(ProxyConfigurationError)
  expect((error as Error).message).toBe('Invalid HTTP proxy or bypass configuration')
  expect((error as Error).cause).toBeUndefined()
}

describe('proxy policy', () => {
  it('is explicit and never falls back', async () => {
    const direct = policy(null, '')
    const proxy = policy(PROXY_URL, '')
    for (const scheme of ['http', 'https', 'ws', 'wss']) {
      await assertDirect(direct, `${scheme}://remote.invalid`)
      expect(await assertProxy(proxy, `${scheme}://remote.invalid`)).toBe(PROXY_URL)
    }
    await assertDirect(proxy, 'ftp://remote.invalid')
    await assertDirect(proxy, 'mailto:test@example.com')
    await expect(proxy.select(null as unknown as string, new AbortController().signal)).rejects.toThrow()
    await expect(proxy.select('http:/path', new AbortController().signal)).rejects.toThrow()
  })

  it('bypass matches hosts, domains and literals', async () => {
    const selector = policy(PROXY_URL, 'localhost,127.*,10.0.0.1,.example.com,*.other.test,[::1],2001:db8::1')
    for (const host of [
      'localhost',
      'LOCALHOST',
      '127.0.0.9',
      '10.0.0.1',
      'example.com',
      'a.example.com',
      'example.com.',
      'other.test',
      'a.other.test',
      '[::1]',
      '[0:0:0:0:0:0:0:1]',
      '[2001:0db8:0:0:0:0:0:1]',
    ]) {
      await assertDirect(selector, `http://${host}`)
    }
    for (const host of [
      'notexample.com',
      'example.com.evil',
      '127.evil',
      '10.0.0.2',
      '[::2]',
      'elsewhere.invalid',
    ]) {
      await assertProxy(selector, `https://${host}`)
    }
    await assertDirect(policy(PROXY_URL, '*'), 'https://any.invalid')
    expect(new URL(await assertProxy(policy('http://[::1]:8888', ''), 'http://any.invalid')).hostname).toBe('[::1]')
  })

  it('bypass ports are protocol aware', async () => {
    const selector = policy(PROXY_URL, 'example.com:443,[::1]:80,127.*:8080')
    await assertDirect(selector, 'https://example.com')
    await assertDirect(selector, 'wss://a.example.com')
    await assertDirect(selector, 'http://[::1]')
    await assertDirect(selector, 'http://127.2.3.4:8080')
    await assertProxy(selector, 'http://example.com')
    await assertProxy(selector, 'https://[::1]')
    await assertProxy(selector, 'http://127.2.3.4')
  })

  it('matches IPv4 CIDR bounds and non-zero host bits', async () => {
    const private16 = policy(PROXY_URL, '192.168.0.0/16')
    await assertDirect(private16, 'http://192.168.0.0')
    await assertDirect(private16, 'https://192.168.255.255')
    await assertProxy(private16, 'http://192.169.0.0')
    await assertProxy(private16, 'http://192.167.255.255')

    const cgnat = policy(PROXY_URL, '100.64.0.0/10')
    await assertDirect(cgnat, 'http://100.64.0.0')
    await assertDirect(cgnat, 'http://100.127.255.255')
    await assertProxy(cgnat, 'http://100.128.0.0')
    await assertProxy(cgnat, 'http://100.63.255.255')

    const all4 = policy(PROXY_URL, '0.0.0.0/0')
    await assertDirect(all4, 'http://8.8.8.8')
    await assertDirect(all4, 'http://10.0.0.0')

    const single = policy(PROXY_URL, '10.0.0.0/32')
    await assertDirect(single, 'http://10.0.0.0')
    await assertProxy(single, 'http://10.0.0.1')

    const hostBits = policy(PROXY_URL, '192.168.1.5/16')
    await assertDirect(hostBits, 'http://192.168.99.99')
    await assertProxy(hostBits, 'http://192.169.0.1')
  })

  it('matches IPv6 CIDR across prefix widths', async () => {
    const doc = policy(PROXY_URL, '2001:db8::/32')
    await assertDirect(doc, 'http://[2001:db8::1]')
    await assertDirect(doc, 'https://[2001:DB8:0:0:0:0:0:1]')
    await assertProxy(doc, 'http://[2001:db9::1]')

    await assertDirect(policy(PROXY_URL, '::/0'), 'http://[2001:db8::1]')
    const single = policy(PROXY_URL, '2001:db8::1/128')
    await assertDirect(single, 'http://[2001:db8::1]')
    await assertProxy(single, 'http://[2001:db8::2]')

    const bits = policy(PROXY_URL, '2001:db8::/126')
    await assertDirect(bits, 'http://[2001:db8::3]')
    await assertProxy(bits, 'http://[2001:db8::4]')
  })

  it('keeps address family and IPv4-mapped IPv6', async () => {
    await assertProxy(policy(PROXY_URL, '0.0.0.0/0'), 'http://[2001:db8::1]')
    await assertProxy(policy(PROXY_URL, '::/0'), 'http://8.8.8.8')
    await assertProxy(policy(PROXY_URL, '::/0'), 'http://remote.invalid')
    await assertProxy(policy(PROXY_URL, '10.0.0.0/8'), 'http://remote.invalid')

    const mapped = policy(PROXY_URL, '::ffff:192.168.0.0/120')
    await assertDirect(mapped, 'http://[::ffff:192.168.0.5]')
    await assertProxy(mapped, 'http://[::ffff:192.168.1.5]')
    await assertProxy(mapped, 'http://192.168.0.5')
    await assertProxy(policy(PROXY_URL, '192.168.0.0/16'), 'http://[::ffff:192.168.0.5]')
  })

  it('interoperates with hosts and environment fallback', async () => {
    const userNoProxy =
      'localhost,127.0.0.1,localaddress,.localdomain.com,.kk1.fun,192.168.0.0/16,' +
      '100.64.0.0/10,.local,.minimaxi.com,langbase.netease.com,.internal'
    const selector = policy(PROXY_URL, userNoProxy)
    for (const host of [
      'localhost',
      '127.0.0.1',
      'localaddress',
      'a.localdomain.com',
      'api.kk1.fun',
      '192.168.10.20',
      '100.100.0.1',
      'x.local',
      'api.minimaxi.com',
      'langbase.netease.com',
      'svc.internal',
    ]) {
      await assertDirect(selector, `http://${host}`)
    }
    for (const host of ['127.0.0.2', '192.169.0.1', '100.128.0.1', 'public.invalid']) {
      await assertProxy(selector, `http://${host}`)
    }

    const fallback = recordingFallback()
    const environment = ProxyPolicy.fromEnvironment(
      { no_proxy: '192.168.0.0/16,100.64.0.0/10,2001:db8::/32' },
      fallback.resolver,
    )
    await assertDirect(environment, 'http://192.168.1.1')
    await assertDirect(environment, 'wss://100.64.5.5')
    await assertDirect(environment, 'https://[2001:db8::9]')
    expect(fallback.calls()).toBe(0)
    expect(await select(environment, 'http://192.169.0.1')).toEqual({
      kind: 'proxy',
      url: 'http://os.invalid:9000',
    })
    expect(await select(environment, 'https://100.128.0.1')).toEqual({
      kind: 'proxy',
      url: 'http://os.invalid:9000',
    })
    expect(fallback.calls()).toBe(2)
  })

  it('rejects invalid configuration with a redacted failure', () => {
    for (const value of [
      'secret.invalid:80',
      'https://secret.invalid:80',
      'http://user:secret@host:80',
      'http://secret.invalid',
      'http://secret.invalid:0',
      'http://secret.invalid:65536',
      'http://secret.invalid:-1',
      'http://secret.invalid:80/',
      'http://secret.invalid:80/path',
      'http://secret.invalid:80?q=secret',
      'http://secret.invalid:80#secret',
      'http://secret invalid:80',
      'http://[fe80::1%25secret]:80',
    ]) {
      assertRedacted(() => policy(value, ''))
      assertRedacted(() => ProxyPolicy.fromEnvironment({ https_proxy: value }, directResolver()))
    }
    for (const value of [
      ',',
      'host,',
      ' ',
      '10.0.0.0/33',
      '10.0.0.0/',
      '/8',
      '10.0.0.0/8/8',
      '10.0.0/8',
      '1.2.3.x/8',
      '10.0.0.256/8',
      '10.0.0.0.0/8',
      '10.0.0.0/abc',
      '10.0.0.0/-1',
      '10.0.0.0/8:80',
      'host/8',
      '*.example.com/24',
      '2001:db8::/129',
      '::ffff:1.2.3.4/129',
      '1:2:3:4:5:6:7:8:9/64',
      'fe80::1%eth0/64',
      '[::1]/128',
      'foo*bar',
      '*.*',
      '.*',
      '*.127.*',
      '.::1',
      '.127.0.0.1',
      '192.*',
      '[::1',
      '[localhost]',
      '[::1]bad',
      '[::1]:',
      'host:0',
      'host:65536',
      'host:abc',
      'host:123456',
      'host:-1',
      '::::',
      'fe80::1%eth0',
      'http://host',
    ]) {
      assertRedacted(() => policy(PROXY_URL, value))
    }
    assertRedacted(() => ProxyPolicy.fromEnvironment({ http_proxy: undefined }, directResolver()))
  })

  it('honours environment precedence and fallback', async () => {
    const fallback = recordingFallback()
    let selector = ProxyPolicy.fromEnvironment(
      {
        http_proxy: PROXY_URL,
        HTTP_PROXY: 'invalid-secret',
        HTTPS_PROXY: 'http://secure.invalid:8443',
        no_proxy: 'bypass.invalid',
        NO_PROXY: '*',
      },
      fallback.resolver,
    )
    expect(new URL(await assertProxy(selector, 'ws://remote.invalid')).hostname).toBe('proxy.invalid')
    expect(new URL(await assertProxy(selector, 'wss://remote.invalid')).hostname).toBe('secure.invalid')
    await assertDirect(selector, 'https://bypass.invalid')
    expect(fallback.calls()).toBe(0)

    selector = ProxyPolicy.fromEnvironment({ http_proxy: PROXY_URL, no_proxy: 'bypass.invalid' }, fallback.resolver)
    await assertDirect(selector, 'https://bypass.invalid')
    expect(fallback.calls()).toBe(0)
    await assertProxy(selector, 'wss://remote.invalid/path?q=x%20y')
    const forwarded = new URL(fallback.last())
    expect(forwarded.protocol).toBe('https:')
    expect(forwarded.search).toBe('?q=x%20y')
    await assertProxy(selector, 'https://remote.invalid')

    selector = ProxyPolicy.fromEnvironment(
      { http_proxy: '', HTTP_PROXY: PROXY_URL, no_proxy: '', NO_PROXY: '*' },
      fallback.resolver,
    )
    await assertDirect(selector, 'http://remote.invalid')
    await assertProxy(selector, 'wss://remote.invalid')

    selector = ProxyPolicy.fromEnvironment({}, fallback.resolver)
    await assertProxy(selector, 'ws://remote.invalid')
    expect(new URL(fallback.last()).protocol).toBe('http:')
    await assertDirect(ProxyPolicy.fromEnvironment({}, directResolver()), 'https://remote.invalid')
    await assertDirect(ProxyPolicy.fromEnvironment({ NO_PROXY: '*' }, fallback.resolver), 'http://remote.invalid')
    expect(
      new URL(await assertProxy(ProxyPolicy.fromEnvironment({ HTTP_PROXY: PROXY_URL }, directResolver()), 'http://remote.invalid'))
        .hostname,
    ).toBe('proxy.invalid')
    await assertDirect(ProxyPolicy.fromEnvironment({ http_proxy: PROXY_URL }, directResolver()), 'https://remote.invalid')
  })
})
