import koffi from 'koffi'
import { describe, expect, test } from 'vitest'
import { interpretDarwinCandidate, loadDarwinProxyBindings } from '../../src/native/system-proxy-darwin.js'

// 使用私有的 CF 代理字典与内联 PAC，不改宿主系统设置，也不访问外部网络。
describe.runIf(process.platform === 'darwin')('Darwin native proxy ABI', () => {
  test('selects the Secure Web Proxy as an HTTP tunnel for HTTPS targets', () => {
    const core = koffi.load('/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation')
    const network = koffi.load('/System/Library/Frameworks/CFNetwork.framework/CFNetwork')
    const dictionary = core.func('void *CFDictionaryCreateMutable(void *allocator, int64_t capacity, void *keys, void *values)')
    const set = core.func('void CFDictionarySetValue(void *dictionary, void *key, void *value)')
    const string = core.func('void *CFStringCreateWithCString(void *allocator, const char *text, uint32_t encoding)')
    const number = core.func('void *CFNumberCreate(void *allocator, int64_t type, void *value)')
    const url = core.func('void *CFURLCreateWithString(void *allocator, void *text, void *base)')
    const proxies = network.func('void *CFNetworkCopyProxiesForURL(void *url, void *settings)')
    const count = core.func('int64_t CFArrayGetCount(void *array)')
    const first = core.func('void *CFArrayGetValueAtIndex(void *array, int64_t index)')
    const get = core.func('void *CFDictionaryGetValue(void *dictionary, void *key)')
    const equal = core.func('bool CFEqual(void *left, void *right)')
    const readString = core.func('bool CFStringGetCString(void *string, void *buffer, int64_t size, uint32_t encoding)')
    const readNumber = core.func('bool CFNumberGetValue(void *number, int64_t type, void *value)')
    const release = core.func('void CFRelease(void *value)')
    const owned: bigint[] = []
    const own = (pointer: unknown): bigint => {
      expect(pointer).not.toBeNull()
      const value = pointer as bigint
      owned.push(value)
      return value
    }
    const key = (name: string): bigint => koffi.decode(network.symbol(name), 'void *') as bigint
    const integer = (value: number): bigint => {
      const bytes = Buffer.alloc(4)
      bytes.writeInt32LE(value)
      return own(number(null, 3, bytes))
    }
    try {
      const settings = own(dictionary(null, 0, core.symbol('kCFTypeDictionaryKeyCallBacks'), core.symbol('kCFTypeDictionaryValueCallBacks')))
      set(settings, key('kCFNetworkProxiesHTTPSEnable'), integer(1))
      set(settings, key('kCFNetworkProxiesHTTPSProxy'), own(string(null, '127.0.0.1', 0x08000100)))
      set(settings, key('kCFNetworkProxiesHTTPSPort'), integer(8989))
      const target = own(url(null, own(string(null, 'https://example.test/', 0x08000100)), null))
      const selected = own(proxies(target, settings))
      expect(Number(count(selected))).toBeGreaterThan(0)
      const candidate = first(selected, 0)
      const type = get(candidate, key('kCFProxyTypeKey'))
      const tunnel = Boolean(equal(type, key('kCFProxyTypeHTTPS')))
      expect(tunnel || Boolean(equal(type, key('kCFProxyTypeHTTP')))).toBe(true)
      const host = Buffer.alloc(128)
      const port = Buffer.alloc(4)
      expect(Boolean(readString(get(candidate, key('kCFProxyHostNameKey')), host, host.length, 0x08000100))).toBe(true)
      expect(Boolean(readNumber(get(candidate, key('kCFProxyPortNumberKey')), 3, port))).toBe(true)
      expect(interpretDarwinCandidate({
        type: tunnel ? 'https' : 'http',
        host: host.toString('utf8', 0, host.indexOf(0)),
        port: port.readInt32LE(),
        username: null,
        password: null,
        pacUrl: null,
        pacScript: null
      })).toBe('http://127.0.0.1:8989')
    } finally {
      for (const pointer of owned.reverse()) {
        release(pointer)
      }
    }
  })

  test('executes inline PAC through the real CFNetwork callback and run loop', async () => {
    const result = await loadDarwinProxyBindings().executePac(
      { kind: 'script', value: 'function FindProxyForURL(url, host) { return "PROXY 127.0.0.1:8989"; }' },
      'https://example.test/',
      () => false
    )
    expect(result.kind).toBe('proxies')
    if (result.kind === 'proxies') {
      expect(result.proxies).toHaveLength(1)
      expect(interpretDarwinCandidate(result.proxies[0]!)).toBe('http://127.0.0.1:8989')
    }
  }, 20_000)
})
