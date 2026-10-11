import { afterEach, describe, expect, test, vi } from 'vitest'
import { SYSTEM_PROXY_SESSION_FAILED, systemProxyErrorWithCode } from '../../src/native/system-proxy-errors.js'
import type {
  WindowsAutoProxyOptions,
  WindowsProxyBindings,
  WindowsProxyConfig,
  WindowsProxyEntry,
  WindowsProxyStatusCallback,
} from '../../src/native/system-proxy-windows.js'
import {
  WindowsSystemProxyResolver,
  buildAutoProxyOptions,
  interpretAutomatic,
  loadWindowsProxyBindings,
  matchesBypass,
  selectManualProxy,
} from '../../src/native/system-proxy-windows.js'

/**
 * Windows 代理解析全部由行为等价的假端口驱动：假端口精确复刻 WinHTTP 的契约——
 *   * 每个 GetProxyForUrlEx 只产生**一次**终态回调（COMPLETE 或 REQUEST_ERROR）；
 *   * 关闭 pending 的 resolver 句柄会产生 REQUEST_ERROR（OPERATION_CANCELLED）而不是静默丢弃；
 *   * resolver 句柄永不产生 HANDLE_CLOSING，只有 session 会；
 *   * 关闭句柄后仍可能投递回调，所以注册必须保留到能证明不会再回调为止。
 * 加载器本身用可变的假 koffi 驱动，验证 GFree、配置缺失与 API 失败的分支。
 */
const h = vi.hoisted(() => {
  const state = {
    ieResult: 1,
    autoDetect: 0,
    autoConfigUrl: 0n as bigint | null,
    proxy: 0n as bigint | null,
    bypass: null as bigint | null,
    lastError: 0,
    globalFreed: [] as unknown[],
    globalFreeFailures: new Set<bigint>(),
    globalFreeThrows: new Set<bigint>(),
    closedHandles: [] as unknown[],
    closeResult: 1,
    closeThrows: false,
    closeSetsLastError: null as number | null,
    allocThrows: false,
    encodeThrows: false,
    freed: [] as unknown[],
    freeThrows: new Set<bigint>(),
    contextSlot: 0x9n,
    statusCallbackFlags: 0,
    strings: new Map<bigint, string>(),
    registered: [] as unknown[],
    unregistered: [] as unknown[],
    openResult: 0x100n as bigint | null,
    setOptionResult: 1,
    setStatusCallbackResult: 0n,
    createResolverResult: 0,
    resolverHandle: 0x500n as bigint | null,
    getProxyForUrlExStatus: 997,
    getProxyResultStatus: 0,
    proxyEntriesCount: 0,
    proxyEntriesPointer: 0n as bigint | null,
    decodeResult: null as unknown,
  }
  return { state }
})

vi.mock('koffi', () => {
  const state = h.state
  const handlers: Record<string, (...args: any[]) => unknown> = {
    WinHttpOpen: () => state.openResult,
    WinHttpSetOption: () => state.setOptionResult,
    WinHttpSetStatusCallback: (_handle: unknown, _callback: unknown, flags: number) => {
      state.statusCallbackFlags = flags
      return state.setStatusCallbackResult
    },
    WinHttpCreateProxyResolver: (_session: unknown, out: unknown[]) => {
      if (state.createResolverResult === 0) {
        out[0] = state.resolverHandle
      }
      return state.createResolverResult
    },
    WinHttpGetProxyForUrlEx: () => state.getProxyForUrlExStatus,
    WinHttpGetProxyResult: (_resolver: unknown, result: Record<string, unknown>) => {
      if (state.getProxyResultStatus === 0) {
        result.cEntries = state.proxyEntriesCount
        result.pEntries = state.proxyEntriesPointer
      }
      return state.getProxyResultStatus
    },
    WinHttpFreeProxyResult: () => undefined,
    WinHttpCloseHandle: (handle: unknown) => {
      state.closedHandles.push(handle)
      if (state.closeSetsLastError !== null) {
        state.lastError = state.closeSetsLastError
      }
      if (state.closeThrows) {
        throw new Error('native close failed')
      }
      return state.closeResult
    },
    WinHttpGetIEProxyConfigForCurrentUser: (config: Record<string, unknown>) => {
      config.fAutoDetect = state.autoDetect
      config.lpszAutoConfigUrl = state.autoConfigUrl
      config.lpszProxy = state.proxy
      config.lpszProxyBypass = state.bypass
      return state.ieResult
    },
    GetLastError: () => state.lastError,
    GlobalFree: (memory: unknown) => {
      state.globalFreed.push(memory)
      if (typeof memory === 'bigint') {
        if (state.globalFreeThrows.has(memory)) {
          throw new Error('global free failed')
        }
        if (state.globalFreeFailures.has(memory)) {
          // 失败时 GlobalFree 返回传入的句柄本身。
          return memory
        }
      }
      return null
    },
  }
  const library = {
    func: (definition: string) => {
      const name = definition.match(/(\w+)\s*\(/u)?.[1] ?? ''
      const handler = handlers[name]
      const fn = (...args: unknown[]): unknown => (handler === undefined ? 0 : handler(...args))
      return Object.assign(fn, { async: (..._args: unknown[]): void => undefined })
    },
  }
  const decode = Object.assign((): unknown => state.decodeResult, {
    string16: (pointer: bigint): string => state.strings.get(pointer) ?? '',
  })
  const koffi = {
    load: () => library,
    struct: (nameOrDef: unknown) => ({ name: typeof nameOrDef === 'string' ? nameOrDef : 'anonymous' }),
    proto: () => ({ name: 'callback' }),
    pointer: () => ({ name: 'pointer' }),
    alloc: () => {
      if (state.allocThrows) {
        throw new Error('native allocation failed')
      }
      return state.contextSlot
    },
    encode: () => {
      if (state.encodeThrows) {
        throw new Error('native encoding failed')
      }
    },
    sizeof: () => 8,
    decode,
    free: (pointer: unknown) => {
      state.freed.push(pointer)
      if (typeof pointer === 'bigint' && state.freeThrows.has(pointer)) {
        throw new Error('koffi free failed')
      }
    },
    register: (callback: unknown) => {
      state.registered.push(callback)
      return BigInt(state.registered.length)
    },
    unregister: (token: unknown) => {
      state.unregistered.push(token)
    },
  }
  return { ...koffi, default: koffi }
})

const ERROR_IO_PENDING = 997
const ERROR_WINHTTP_OPERATION_CANCELLED = 12_017
const ERROR_WINHTTP_AUTODETECTION_FAILED = 12_180
const ERROR_WINHTTP_UNABLE_TO_DOWNLOAD_SCRIPT = 12_167

function entry(overrides: Partial<WindowsProxyEntry> = {}): WindowsProxyEntry {
  return { proxy: true, bypass: false, scheme: 1, host: 'proxy', port: 8080, ...overrides }
}

/** 行为等价的假 WinHTTP 端口。 */
class FakeBindings {
  config: WindowsProxyConfig = { autoDetect: false, autoConfigUrl: null, proxy: null, bypass: null }
  configError: Error | null = null
  statusCallbackError: Error | null = null
  startStatus = ERROR_IO_PENDING
  startError: Error | null = null
  entries: WindowsProxyEntry[] = []
  resultError: Error | null = null
  readonly closeFailures = new Set<bigint>()
  readonly closeThrows = new Set<bigint>()
  unregisterError: Error | null = null
  autoSessionClosing = true
  readonly session = 0x100n
  readonly callbackToken = 0xabc
  callback: WindowsProxyStatusCallback | null = null
  readonly unregistered: unknown[] = []
  readonly created: bigint[] = []
  readonly started: Array<{ resolver: bigint; url: string; options: WindowsAutoProxyOptions; context: bigint }> = []
  readonly closedHandles: bigint[] = []
  openedSessions = 0
  lastErrorValue = 0
  private nextResolver = 0x500n

  build(): WindowsProxyBindings {
    return {
      readCurrentUserProxyConfig: () => {
        if (this.configError !== null) {
          throw this.configError
        }
        return this.config
      },
      openAsyncSession: () => {
        this.openedSessions += 1
        return this.session
      },
      setStatusCallback: (_session, callback) => {
        if (this.statusCallbackError !== null) {
          throw this.statusCallbackError
        }
        this.callback = callback
        return this.callbackToken
      },
      unregisterCallback: (token) => {
        if (this.unregisterError !== null) {
          throw this.unregisterError
        }
        this.unregistered.push(token)
      },
      createProxyResolver: () => {
        const handle = this.nextResolver
        this.nextResolver += 1n
        this.created.push(handle)
        return handle
      },
      getProxyForUrlEx: (resolver, url, options, context) => {
        if (this.startError !== null) {
          throw this.startError
        }
        this.started.push({ resolver, url, options, context })
        return this.startStatus
      },
      readProxyResult: () => {
        if (this.resultError !== null) {
          throw this.resultError
        }
        return this.entries
      },
      closeHandle: (handle) => {
        this.closedHandles.push(handle)
        if (this.closeThrows.has(handle)) {
          throw new Error('native close failed')
        }
        if (handle === this.session && this.autoSessionClosing) {
          queueMicrotask(() => this.emitClosing())
        }
        return !this.closeFailures.has(handle)
      },
      lastError: () => this.lastErrorValue,
    }
  }

  emitComplete(context: bigint): void {
    this.callback?.(this.resolverFor(context), context, { kind: 'complete' })
  }

  emitError(context: bigint, code: number): void {
    this.callback?.(this.resolverFor(context), context, { kind: 'error', code })
  }

  emitClosing(): void {
    this.callback?.(this.session, 0n, { kind: 'closing' })
  }

  lastContext(): bigint {
    return this.started.at(-1)?.context ?? 0n
  }

  private resolverFor(context: bigint): bigint {
    return this.started.find((call) => call.context === context)?.resolver ?? 0n
  }
}

async function flush(): Promise<void> {
  for (let index = 0; index < 8; index += 1) {
    await Promise.resolve()
  }
}

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  h.state.closeThrows = false
  h.state.closeSetsLastError = null
  h.state.allocThrows = false
  h.state.encodeThrows = false
  h.state.openResult = 0x100n
  h.state.setOptionResult = 1
  h.state.closeResult = 1
  h.state.lastError = 0
  h.state.freed = []
  h.state.closedHandles = []
  h.state.freeThrows.clear()
})

describe('windows manual proxy selection', () => {
  test('uses the protocol table, then a bare fallback, and direct when the protocol is absent', () => {
    expect(selectManualProxy('http=proxy:8080;https=secure:8443', null, 'http://api.example.com/v1')).toBe('http://proxy:8080')
    expect(selectManualProxy('http=proxy:8080;https=secure:8443', null, 'https://api.example.com/v1')).toBe('http://secure:8443')
    // 裸 host:port 是所有协议的默认代理；缺省端口补齐协议默认端口。
    expect(selectManualProxy('fallback:3128', null, 'http://api.example.com/')).toBe('http://fallback:3128')
    expect(selectManualProxy('http=proxy', null, 'http://api.example.com/')).toBe('http://proxy:80')
    // 只有 https 的代理时 http 直连；socks-only 也不被当作 http 代理。
    expect(selectManualProxy('https=secure:8443', null, 'http://api.example.com/')).toBeNull()
    expect(selectManualProxy('socks=socks5:1080', null, 'http://api.example.com/')).toBeNull()
    expect(selectManualProxy(null, null, 'http://api.example.com/')).toBeNull()
  })

  test('applies real windows bypass semantics without re-implementing NO_PROXY', () => {
    expect(matchesBypass('<local>', 'intranet', 'http')).toBe(true)
    expect(matchesBypass('<local>', 'intranet.example', 'http')).toBe(false)
    expect(matchesBypass('*.corp.example.com', 'x.corp.example.com', 'https')).toBe(true)
    expect(matchesBypass('*.corp.example.com', 'corp.example.com', 'https')).toBe(false)
    expect(matchesBypass('192.168.*', '192.168.1.5', 'http')).toBe(true)
    expect(matchesBypass('http://skip.example.com', 'skip.example.com', 'https')).toBe(false)
    expect(matchesBypass('http://skip.example.com', 'skip.example.com', 'http')).toBe(true)
    expect(matchesBypass('exact.example.com', 'exact.example.com', 'http')).toBe(true)
    // 命中 bypass 时该协议直连。
    expect(selectManualProxy('http=proxy:8080', '<local>', 'http://intranet/')).toBeNull()
  })

  test('fails closed on malformed or credentialed manual entries', () => {
    expect(() => selectManualProxy('http=::bad::', null, 'http://api.example.com/')).toThrow(/malformed/)
    expect(() => selectManualProxy('http=socks5://proxy:1080', null, 'http://api.example.com/')).toThrow(/does not support/)
    expect(() => selectManualProxy('http=user:pass@proxy:8080', null, 'http://api.example.com/')).toThrow(/does not support/)
    expect(() => selectManualProxy(null, null, 'ws://api.example.com/')).toThrow(/malformed/)
    // 无法解析成 URL 或不是等价 http/https URL 的输入都失败关闭。
    expect(() => selectManualProxy(null, null, 'not a url')).toThrow(/malformed/)
  })

  test('accepts scheme-prefixed manual entries with trailing slashes and rejects an empty host', () => {
    expect(selectManualProxy('http=http://proxy:8080/', null, 'http://api.example.com/')).toBe('http://proxy:8080')
    // 只有分隔符、没有有效条目：直连而不是报错。
    expect(selectManualProxy(' ; ', null, 'http://api.example.com/')).toBeNull()
    expect(() => selectManualProxy('http=http://', null, 'http://api.example.com/')).toThrow(/malformed/)
  })

  test('uses an https protocol-table entry as the same unauthenticated http proxy for https targets', () => {
    // `https=` 是目标协议表键，值仍是同一个无认证 HTTP 代理；缺省端口补齐 443。
    expect(selectManualProxy('https=secure:8443', null, 'https://api.example.com/')).toBe('http://secure:8443')
  })

  test('rejects a literal https proxy value and values carrying path, query, fragment, or a zero port', () => {
    // `http=` 的值里字面 `https://` 指的是 HTTPS 代理（TLS 到代理），本守护进程不支持，绝不降级成 http://。
    expect(() => selectManualProxy('http=https://proxy:8443', null, 'http://api.example.com/')).toThrow(/does not support/)
    expect(() => selectManualProxy('http=proxy:8080/path', null, 'http://api.example.com/')).toThrow(/malformed/)
    expect(() => selectManualProxy('http=proxy:8080?q', null, 'http://api.example.com/')).toThrow(/malformed/)
    expect(() => selectManualProxy('http=proxy:8080#f', null, 'http://api.example.com/')).toThrow(/malformed/)
    expect(() => selectManualProxy('http=proxy:0', null, 'http://api.example.com/')).toThrow(/malformed/)
  })

  test('bypass tokens cover wildcard, domain suffix, exact, and skipped empty tokens', () => {
    expect(matchesBypass('*', 'anything.example', 'http')).toBe(true)
    expect(matchesBypass('.example.com', 'sub.example.com', 'http')).toBe(true)
    expect(matchesBypass('.example.com', 'other.test', 'http')).toBe(false)
    expect(matchesBypass('exact.example.com', 'other.example.com', 'http')).toBe(false)
    // 空 token 与只剩 scheme 的 token 要跳过，而不是当成模式。
    expect(matchesBypass(' ; foo.example.com', 'foo.example.com', 'http')).toBe(true)
    expect(matchesBypass('http://', 'host.example', 'http')).toBe(false)
    expect(matchesBypass('http://;host.example', 'host.example', 'http')).toBe(true)
  })
})

describe('windows automatic proxy resolution', () => {
  test('returns the canonical http proxy for a PAC result and reads the result before freeing', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: false, autoConfigUrl: 'http://pac.example/proxy.pac', proxy: null, bypass: null }
    fake.entries = [entry({ host: 'pac-proxy', port: 3128 })]
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const pending = resolver.resolve('http://api.example.com/', new AbortController().signal)
    const call = fake.started[0]
    expect(call?.options.flags).toBe(2) // WINHTTP_AUTOPROXY_CONFIG_URL
    fake.emitComplete(fake.lastContext())
    await expect(pending).resolves.toBe('http://pac-proxy:3128')
    await expect(resolver.close()).resolves.toBeUndefined()
    expect(fake.unregistered).toEqual([fake.callbackToken])
  })

  test('treats an explicit direct answer and an empty result as direct', () => {
    expect(interpretAutomatic([entry({ proxy: false })])).toBeNull()
    expect(interpretAutomatic([])).toBeNull()
    expect(interpretAutomatic([entry({ port: 0 })])).toBe('http://proxy:80')
  })

  test('brackets a bare IPv6 PAC proxy host into a canonical http url', () => {
    // WinHTTP 可能返回不带方括号的裸 IPv6 地址；对外必须是合法 URI authority。
    expect(interpretAutomatic([entry({ host: '::1' })])).toBe('http://[::1]:8080')
  })

  test('rejects unsupported proxy schemes and malformed hosts from a PAC result', () => {
    expect(() => interpretAutomatic([entry({ scheme: 4 })])).toThrow(/does not support/)
    expect(() => interpretAutomatic([entry({ host: '' })])).toThrow(/malformed/)
  })

  test('a PAC script failure is an error, not direct', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: false, autoConfigUrl: 'http://pac.example/proxy.pac', proxy: null, bypass: null }
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const pending = resolver.resolve('http://api.example.com/', new AbortController().signal)
    fake.emitError(fake.lastContext(), ERROR_WINHTTP_UNABLE_TO_DOWNLOAD_SCRIPT)
    await expect(pending).rejects.toThrow(/proxy lookup failed \(code 12167\)/)
    await expect(resolver.close()).resolves.toBeUndefined()
  })

  test('a WPAD-only absence resolves to direct while a configured PAC stays an error', async () => {
    const wpad = new FakeBindings()
    wpad.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    const wpadResolver = new WindowsSystemProxyResolver(wpad.build())
    const wpadPending = wpadResolver.resolve('http://api.example.com/', new AbortController().signal)
    wpad.emitError(wpad.lastContext(), ERROR_WINHTTP_AUTODETECTION_FAILED)
    await expect(wpadPending).resolves.toBeNull()
    await expect(wpadResolver.close()).resolves.toBeUndefined()

    const pac = new FakeBindings()
    pac.config = { autoDetect: true, autoConfigUrl: 'http://pac.example/proxy.pac', proxy: null, bypass: null }
    const pacResolver = new WindowsSystemProxyResolver(pac.build())
    const pacPending = pacResolver.resolve('http://api.example.com/', new AbortController().signal)
    pac.emitError(pac.lastContext(), ERROR_WINHTTP_AUTODETECTION_FAILED)
    await expect(pacPending).rejects.toThrow(/proxy lookup failed \(code 12180\)/)
    await expect(pacResolver.close()).resolves.toBeUndefined()
  })

  test('a synchronous start failure closes the handle and never fabricates a result', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    fake.startStatus = 12_005 // ERROR_WINHTTP_INVALID_URL
    const resolver = new WindowsSystemProxyResolver(fake.build())
    await expect(resolver.resolve('http://api.example.com/', new AbortController().signal)).rejects.toThrow(
      /proxy lookup failed \(code 12005\)/,
    )
    expect(fake.closedHandles).toContain(0x500n)
  })

  test('builds auto-detect plus config-url options, and never auto-logs on', () => {
    expect(buildAutoProxyOptions({ autoDetect: false, autoConfigUrl: 'x', proxy: null, bypass: null })).toEqual({
      flags: 2,
      autoDetectFlags: 0,
      autoConfigUrl: 'x',
    })
    expect(buildAutoProxyOptions({ autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null })).toEqual({
      flags: 1,
      autoDetectFlags: 3,
      autoConfigUrl: null,
    })
  })
})

describe('windows cancellation and callback lifecycle', () => {
  test('an already-aborted signal never opens a session', async () => {
    const fake = new FakeBindings()
    const controller = new AbortController()
    controller.abort()
    const resolver = new WindowsSystemProxyResolver(fake.build())
    await expect(resolver.resolve('http://api.example.com/', controller.signal)).rejects.toThrow(/cancelled/)
    expect(fake.openedSessions).toBe(0)
    await expect(resolver.close()).resolves.toBeUndefined()
  })

  test('cancelling closes the resolver handle and waits for the operation-cancelled terminal callback', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const controller = new AbortController()
    const pending = resolver.resolve('http://api.example.com/', controller.signal)
    const context = fake.lastContext()
    controller.abort()
    // 取消的第一步就是关闭 resolver 句柄，但真正的结束是终态回调。
    expect(fake.closedHandles).toContain(0x500n)
    fake.emitError(context, ERROR_WINHTTP_OPERATION_CANCELLED)
    await expect(pending).rejects.toThrow(/cancelled/)
    await expect(resolver.close()).resolves.toBeUndefined()
  })

  test('a late duplicate complete/error after the terminal is ignored and never re-settles', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    fake.entries = [entry({ host: 'late', port: 8080 })]
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const pending = resolver.resolve('http://api.example.com/', new AbortController().signal)
    const context = fake.lastContext()
    fake.emitComplete(context)
    await expect(pending).resolves.toBe('http://late:8080')
    // 终态之后的迟到回调必须被忽略，且绝不能提前 unregister。
    fake.emitComplete(context)
    fake.emitError(context, ERROR_WINHTTP_UNABLE_TO_DOWNLOAD_SCRIPT)
    expect(fake.unregistered).toEqual([])
    await expect(resolver.close()).resolves.toBeUndefined()
    expect(fake.unregistered).toEqual([fake.callbackToken])
  })

  test('unregisters only after the session closing callback, never before', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    fake.entries = [entry()]
    fake.autoSessionClosing = false
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const pending = resolver.resolve('http://api.example.com/', new AbortController().signal)
    fake.emitComplete(fake.lastContext())
    await expect(pending).resolves.toBe('http://proxy:8080')
    const closing = resolver.close()
    await flush()
    // session 句柄已关闭，但最后一次回调还没到：绝不能 unregister。
    expect(fake.closedHandles).toContain(fake.session)
    expect(fake.unregistered).toEqual([])
    fake.emitClosing()
    await expect(closing).resolves.toBeUndefined()
    expect(fake.unregistered).toEqual([fake.callbackToken])
  })

  test('close cancels pending work and waits for its terminal callback', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const pending = resolver.resolve('http://api.example.com/', new AbortController().signal)
    const context = fake.lastContext()
    const closing = resolver.close()
    fake.emitError(context, ERROR_WINHTTP_OPERATION_CANCELLED)
    await expect(pending).rejects.toThrow(/cancelled/)
    await expect(closing).resolves.toBeUndefined()
    await expect(resolver.resolve('http://api.example.com/', new AbortController().signal)).rejects.toThrow(/closed/)
  })

  test('a resolver handle that cannot be closed keeps the registration, makes the resolver fatal, and fails close', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    fake.closeFailures.add(0x500n)
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const pending = resolver.resolve('http://api.example.com/', new AbortController().signal)
    fake.emitComplete(fake.lastContext())
    await expect(pending).rejects.toThrow(/handle could not be released/)
    // 无法证明不会再回调：future 不再创建句柄，close 显式失败且不 unregister。
    await expect(resolver.resolve('http://api.example.com/', new AbortController().signal)).rejects.toThrow(/unusable/)
    await expect(resolver.close()).rejects.toThrow(/not confirmed/)
    expect(fake.unregistered).toEqual([])
  })

  test('an unconfirmed session teardown keeps the registration and fails close', async () => {
    vi.useFakeTimers()
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    fake.autoSessionClosing = false
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const pending = resolver.resolve('http://api.example.com/', new AbortController().signal)
    fake.emitComplete(fake.lastContext())
    fake.entries = []
    void pending.catch(() => undefined)
    const closing = resolver.close()
    const assertion = expect(closing).rejects.toThrow(/not confirmed/)
    await vi.advanceTimersByTimeAsync(10_000)
    await assertion
    expect(fake.unregistered).toEqual([])
    await expect(resolver.resolve('http://api.example.com/', new AbortController().signal)).rejects.toThrow(/unusable/)
  })

  test('close is idempotent', async () => {
    const fake = new FakeBindings()
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const first = resolver.close()
    const second = resolver.close()
    expect(first).toBe(second)
    await expect(first).resolves.toBeUndefined()
  })

  test('resolves a manual proxy table through resolve(), with bypass as direct and no async session', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: false, autoConfigUrl: null, proxy: 'http=proxy:8080', bypass: 'skip.example.com' }
    const resolver = new WindowsSystemProxyResolver(fake.build())
    await expect(resolver.resolve('http://api.example.com/', new AbortController().signal)).resolves.toBe('http://proxy:8080')
    await expect(resolver.resolve('http://skip.example.com/', new AbortController().signal)).resolves.toBeNull()
    expect(fake.openedSessions).toBe(0)
    await expect(resolver.close()).resolves.toBeUndefined()
  })

  test('reuses one async session across lookups', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const first = resolver.resolve('http://api.example.com/', new AbortController().signal)
    fake.emitComplete(fake.lastContext())
    await expect(first).resolves.toBeNull()
    const second = resolver.resolve('http://api.example.com/', new AbortController().signal)
    fake.emitComplete(fake.lastContext())
    await expect(second).resolves.toBeNull()
    expect(fake.openedSessions).toBe(1)
    await expect(resolver.close()).resolves.toBeUndefined()
  })

  test('a failed callback registration releases the session and leaves the resolver retryable', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    fake.statusCallbackError = new Error('the operating system proxy session could not be opened (code 0)')
    const resolver = new WindowsSystemProxyResolver(fake.build())
    await expect(resolver.resolve('http://api.example.com/', new AbortController().signal)).rejects.toThrow(
      /session could not be opened/,
    )
    // 没有留下半初始化的 session：句柄已释放，重试会重新建立。
    expect(fake.closedHandles).toContain(fake.session)
    fake.statusCallbackError = null
    const pending = resolver.resolve('http://api.example.com/', new AbortController().signal)
    expect(fake.openedSessions).toBe(2)
    fake.emitComplete(fake.lastContext())
    await expect(pending).resolves.toBeNull()
    await expect(resolver.close()).resolves.toBeUndefined()
  })

  test('a native start failure is surfaced as a fixed error and releases the resolver handle', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    fake.startError = new Error('the operating system proxy lookup failed')
    const resolver = new WindowsSystemProxyResolver(fake.build())
    await expect(resolver.resolve('http://api.example.com/', new AbortController().signal)).rejects.toThrow(/lookup failed/)
    expect(fake.closedHandles).toContain(0x500n)
    await expect(resolver.close()).resolves.toBeUndefined()
  })

  test('an unusable PAC result rejects instead of falling back to direct', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: false, autoConfigUrl: 'http://pac.example/proxy.pac', proxy: null, bypass: null }
    fake.entries = [entry({ scheme: 4 })]
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const pending = resolver.resolve('http://api.example.com/', new AbortController().signal)
    fake.emitComplete(fake.lastContext())
    await expect(pending).rejects.toThrow(/does not support/)
    await expect(resolver.close()).resolves.toBeUndefined()
  })

  test('a resolver handle that cannot be closed on the sync failure path becomes fatal', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    fake.startStatus = 12_005
    fake.closeFailures.add(0x500n)
    const resolver = new WindowsSystemProxyResolver(fake.build())
    await expect(resolver.resolve('http://api.example.com/', new AbortController().signal)).rejects.toThrow(
      /handle could not be released/,
    )
    await expect(resolver.close()).rejects.toThrow(/not confirmed/)
    expect(fake.unregistered).toEqual([])
  })

  test('a session handle that cannot be closed fails close and keeps the registration', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    fake.closeFailures.add(fake.session)
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const pending = resolver.resolve('http://api.example.com/', new AbortController().signal)
    fake.emitComplete(fake.lastContext())
    await expect(pending).resolves.toBeNull()
    await expect(resolver.close()).rejects.toThrow(/not confirmed/)
    expect(fake.unregistered).toEqual([])
  })

  test('a session close that throws is an unconfirmed teardown reported with a fixed message', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    fake.closeThrows.add(fake.session)
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const pending = resolver.resolve('http://api.example.com/', new AbortController().signal)
    fake.emitComplete(fake.lastContext())
    await expect(pending).resolves.toBeNull()
    // 关闭抛错绝不能被当作已释放：固定文案，且不回显原生异常原文。
    const failure = await resolver.close().catch((error: Error) => error)
    expect((failure as Error).message).toBe('the operating system proxy session teardown was not confirmed')
    expect(fake.unregistered).toEqual([])
    await expect(resolver.resolve('http://api.example.com/', new AbortController().signal)).rejects.toThrow(/unusable/)
  })

  test('a failed registration whose session close also throws is aggregate and fatal, never a fake cleanup', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    fake.statusCallbackError = systemProxyErrorWithCode(SYSTEM_PROXY_SESSION_FAILED, 0)
    fake.closeThrows.add(fake.session)
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const failure = (await resolver
      .resolve('http://api.example.com/', new AbortController().signal)
      .catch((error: unknown) => error)) as AggregateError
    // 注册失败与关闭未被确认两个固定诊断都保留：主失败取注册失败，绝不伪称会话已清理。
    expect(failure).toBeInstanceOf(AggregateError)
    expect(failure.message).toBe('the operating system proxy session could not be opened (code 0)')
    expect(failure.errors.map((error: Error) => error.message)).toEqual([
      'the operating system proxy session could not be opened (code 0)',
      'the operating system proxy session teardown was not confirmed',
    ])
    expect(fake.unregistered).toEqual([])
    await expect(resolver.resolve('http://api.example.com/', new AbortController().signal)).rejects.toThrow(/unusable/)
  })

  test('a native close that throws is an unproven release, never a success', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    fake.closeThrows.add(0x500n)
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const pending = resolver.resolve('http://api.example.com/', new AbortController().signal)
    fake.emitComplete(fake.lastContext())
    // 关闭抛错不能被当成「已释放」：本次查询按句柄失败结算，解析器进入不可用终态。
    await expect(pending).rejects.toThrow(/handle could not be released/)
    await expect(resolver.resolve('http://api.example.com/', new AbortController().signal)).rejects.toThrow(/unusable/)
    await expect(resolver.close()).rejects.toThrow(/not confirmed/)
    expect(fake.unregistered).toEqual([])
  })

  test('a failed callback unregistration keeps the registration and fails close without echoing native text', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    fake.unregisterError = new Error('native unregister exploded')
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const pending = resolver.resolve('http://api.example.com/', new AbortController().signal)
    fake.emitComplete(fake.lastContext())
    await expect(pending).resolves.toBeNull()
    const failure = await resolver.close().catch((error: Error) => error)
    expect((failure as Error).message).toBe('the operating system proxy session teardown was not confirmed')
    await expect(resolver.resolve('http://api.example.com/', new AbortController().signal)).rejects.toThrow(/unusable/)
  })

  test('a lookup that never reaches a terminal event makes close fail and keeps the registration', async () => {
    vi.useFakeTimers()
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    const resolver = new WindowsSystemProxyResolver(fake.build())
    const abandoned = resolver.resolve('http://api.example.com/', new AbortController().signal)
    void abandoned.catch(() => undefined)
    const closing = resolver.close()
    const assertion = expect(closing).rejects.toThrow(/not confirmed/)
    await vi.advanceTimersByTimeAsync(10_000)
    await assertion
    expect(fake.unregistered).toEqual([])
    await expect(resolver.resolve('http://api.example.com/', new AbortController().signal)).rejects.toThrow(/unusable/)
  })

  test('a config read failure is never treated as no-proxy', async () => {
    const fake = new FakeBindings()
    fake.config = { autoDetect: true, autoConfigUrl: null, proxy: null, bypass: null }
    const resolver = new WindowsSystemProxyResolver(fake.build())
    let configError: Error | null = new Error('the current-user proxy configuration could not be read (code 12004)')
    fake.configError = configError
    await expect(resolver.resolve('http://api.example.com/', new AbortController().signal)).rejects.toThrow(
      /could not be read \(code 12004\)/,
    )
    configError = null
    fake.configError = null
    const pending = resolver.resolve('http://api.example.com/', new AbortController().signal)
    fake.emitComplete(fake.lastContext())
    await expect(pending).resolves.toBeNull()
    await expect(resolver.close()).resolves.toBeUndefined()
  })
})

describe('windows bindings loader (mocked winhttp)', () => {
  test('dispatches to the Windows resolver on win32', () => {
    const spy = vi.spyOn(process, 'platform', 'get').mockReturnValue('win32')
    try {
      expect(loadWindowsProxyBindings()).toBeTruthy()
    } finally {
      spy.mockRestore()
    }
  })

  test('maps the current-user config and frees every returned IE string', () => {
    h.state.ieResult = 1
    h.state.autoDetect = 1
    h.state.autoConfigUrl = 0x11n
    h.state.proxy = 0x22n
    h.state.bypass = null
    h.state.strings.set(0x11n, 'http://pac.example/proxy.pac')
    h.state.strings.set(0x22n, 'http=proxy:8080')
    h.state.globalFreed = []
    const config = loadWindowsProxyBindings().readCurrentUserProxyConfig()
    expect(config).toEqual({
      autoDetect: true,
      autoConfigUrl: 'http://pac.example/proxy.pac',
      proxy: 'http=proxy:8080',
      bypass: null,
    })
    expect(h.state.globalFreed).toEqual([0x11n, 0x22n])
  })

  test('treats a missing configuration as direct but other API failures as errors', () => {
    h.state.ieResult = 0
    h.state.lastError = 2
    expect(loadWindowsProxyBindings().readCurrentUserProxyConfig()).toEqual({
      autoDetect: false,
      autoConfigUrl: null,
      proxy: null,
      bypass: null,
    })
    h.state.lastError = 12_004
    expect(() => loadWindowsProxyBindings().readCurrentUserProxyConfig()).toThrow(/could not be read \(code 12004\)/)
    h.state.ieResult = 1
  })

  test('frees every returned IE string and reports an unconfirmed global free without echoing anything', () => {
    const bindings = loadWindowsProxyBindings()
    h.state.ieResult = 1
    h.state.autoDetect = 0
    h.state.autoConfigUrl = 0x31n
    h.state.proxy = 0x32n
    h.state.bypass = 0x33n
    h.state.strings.set(0x31n, 'http://pac.example/proxy.pac')
    h.state.strings.set(0x32n, 'http=proxy:8080')
    h.state.strings.set(0x33n, '<local>')

    // happy：GlobalFree 返回 NULL，配置正常返回，三个字符串都释放。
    h.state.globalFreed = []
    expect(bindings.readCurrentUserProxyConfig()).toEqual({
      autoDetect: false,
      autoConfigUrl: 'http://pac.example/proxy.pac',
      proxy: 'http=proxy:8080',
      bypass: '<local>',
    })
    expect(h.state.globalFreed).toEqual([0x31n, 0x32n, 0x33n])

    // 释放失败：GlobalFree 返回原句柄。必须显式失败，但仍尝试释放其余每一个字符串。
    h.state.globalFreed = []
    h.state.globalFreeFailures.add(0x32n)
    const failure = (() => {
      try {
        bindings.readCurrentUserProxyConfig()
        return null
      } catch (error) {
        return error as Error
      }
    })()
    expect(failure?.message).toBe('the operating system proxy handle could not be released')
    expect(h.state.globalFreed).toEqual([0x31n, 0x32n, 0x33n])

    // 原生释放抛错同样不得被当作成功，也不回显原生异常原文。
    h.state.globalFreed = []
    h.state.globalFreeFailures.clear()
    h.state.globalFreeThrows.add(0x31n)
    expect(() => bindings.readCurrentUserProxyConfig()).toThrow(/handle could not be released/)
    expect(h.state.globalFreed).toEqual([0x31n, 0x32n, 0x33n])
    h.state.globalFreeThrows.clear()
    h.state.autoConfigUrl = 0n
    h.state.proxy = 0n
    h.state.bypass = null
  })

  test('registers the terminal callbacks plus the documented handle flag on a non-null context', () => {
    const bindings = loadWindowsProxyBindings()
    h.state.statusCallbackFlags = 0
    bindings.setStatusCallback(0x100n, () => undefined)
    // REQUEST_ERROR | GETPROXYFORURL_COMPLETE | WINHTTP_CALLBACK_FLAG_HANDLES(HANDLE_CREATED|HANDLE_CLOSING)
    expect(h.state.statusCallbackFlags).toBe(0x1_20_0801)
    h.state.unregistered = []
  })

  test('reads the handle close BOOL and opens the async session with a context', () => {
    const bindings = loadWindowsProxyBindings()
    h.state.closeResult = 1
    expect(bindings.closeHandle(0x123n)).toBe(true)
    h.state.closeResult = 0
    expect(bindings.closeHandle(0x124n)).toBe(false)
    h.state.closeResult = 1
    expect(bindings.openAsyncSession()).toBe(0x100n)
    expect(bindings.createProxyResolver(0x100n)).toBe(0x500n)
  })

  test('reports a session that cannot be opened or configured, releasing the half-built handle', () => {
    const bindings = loadWindowsProxyBindings()
    h.state.openResult = null
    h.state.lastError = 0
    expect(() => bindings.openAsyncSession()).toThrow(/session could not be opened \(code 0\)/)
    h.state.openResult = 0x200n
    h.state.setOptionResult = 0
    h.state.lastError = 1234
    h.state.closedHandles = []
    expect(() => bindings.openAsyncSession()).toThrow(/session could not be opened \(code 1234\)/)
    expect(h.state.closedHandles).toEqual([0x200n])
    h.state.openResult = 0x100n
    h.state.setOptionResult = 1
  })

  test('frees the copied session context exactly once after successful configuration', () => {
    expect(loadWindowsProxyBindings().openAsyncSession()).toBe(0x100n)
    expect(h.state.freed).toEqual([h.state.contextSlot])
    expect(h.state.closedHandles).toEqual([])
  })

  test.each(['allocation', 'encoding', 'free'] as const)('releases the half-built session after %s failure', (failure) => {
    h.state.openResult = 0x200n
    h.state.allocThrows = failure === 'allocation'
    h.state.encodeThrows = failure === 'encoding'
    if (failure === 'free') {
      h.state.freeThrows.add(h.state.contextSlot)
    }
    let error: unknown
    try {
      loadWindowsProxyBindings().openAsyncSession()
    } catch (caught) {
      error = caught
    }
    expect(error).toBeInstanceOf(Error)
    expect(String(error)).not.toContain('native allocation failed')
    expect(String(error)).not.toContain('native encoding failed')
    expect(String(error)).not.toContain('koffi free failed')
    expect(h.state.closedHandles).toEqual([0x200n])
    expect(h.state.freed).toEqual(failure === 'allocation' ? [] : [h.state.contextSlot])
  })

  test.each(['success', 'false', 'throw'] as const)('preserves the configuration error before a %s close', (close) => {
    h.state.openResult = 0x200n
    h.state.setOptionResult = 0
    h.state.lastError = 1234
    h.state.closeSetsLastError = 9999
    h.state.closeResult = close === 'false' ? 0 : 1
    h.state.closeThrows = close === 'throw'
    let error: unknown
    try {
      loadWindowsProxyBindings().openAsyncSession()
    } catch (caught) {
      error = caught
    }
    expect(String(error)).toContain('(code 1234)')
    expect(String(error)).not.toContain('(code 9999)')
    expect(String(error)).not.toContain('native close failed')
    if (close !== 'success') {
      expect(error).toBeInstanceOf(AggregateError)
      expect((error as AggregateError).errors).toHaveLength(2)
      expect((error as AggregateError).errors[1].message).toContain('handle could not be released')
    }
    expect(h.state.freed).toEqual([h.state.contextSlot])
    expect(h.state.closedHandles).toEqual([0x200n])
  })

  test('translates raw status callbacks into pointer, error, complete, and closing events', () => {
    const bindings = loadWindowsProxyBindings()
    const events: unknown[] = []
    const token = bindings.setStatusCallback(0x100n, (handle, context, event) => events.push([handle, context, event]))
    const raw = h.state.registered.at(-1) as (handle: unknown, context: unknown, status: unknown, info: unknown) => void
    // 数字句柄也要归一化（HANDLE_CLOSING 只对 session 派发）。
    raw(0x100, 0n, 0x800, null)
    // REQUEST_ERROR 带 WINHTTP_ASYNC_RESULT 指针时解出真实错误码。
    h.state.decodeResult = { dwError: 12_167 }
    raw(0x100n, 7n, 0x20_0000, 0x999n)
    // 指针缺失时退化为 -1，绝不臆造错误码。
    raw(0x100n, 7n, 0x20_0000, null)
    raw(0x100n, 7n, 0x100_0000, null)
    // 未订阅的状态被忽略。
    raw(0x100n, 7n, 0x1, null)
    expect(events).toEqual([
      [0x100n, 0n, { kind: 'closing' }],
      [0x100n, 7n, { kind: 'error', code: 12_167 }],
      [0x100n, 7n, { kind: 'error', code: -1 }],
      [0x100n, 7n, { kind: 'complete' }],
    ])
    bindings.unregisterCallback(token)
    expect(h.state.unregistered).toEqual([token])
  })

  test('rejects an invalid previous status callback instead of keeping a half-registered session', () => {
    const bindings = loadWindowsProxyBindings()
    h.state.setStatusCallbackResult = 0xffff_ffff_ffff_ffffn
    expect(() => bindings.setStatusCallback(0x100n, () => undefined)).toThrow(/session could not be opened/)
    h.state.setStatusCallbackResult = 0n
  })

  test('creates resolvers, starts lookups, and maps result entries with the scheme/host/port fields', () => {
    const bindings = loadWindowsProxyBindings()
    expect(bindings.getProxyForUrlEx(0x500n, 'http://api.example.com/', { flags: 2, autoDetectFlags: 0, autoConfigUrl: 'x' }, 3n)).toBe(997)

    h.state.createResolverResult = 5
    expect(() => bindings.createProxyResolver(0x100n)).toThrow(/resolver could not be created \(code 5\)/)
    h.state.createResolverResult = 0
    h.state.resolverHandle = null
    expect(() => bindings.createProxyResolver(0x100n)).toThrow(/resolver could not be created/)
    h.state.resolverHandle = 0x500n

    h.state.getProxyResultStatus = 0
    h.state.proxyEntriesCount = 2
    h.state.proxyEntriesPointer = 0x777n
    h.state.strings.set(0x888n, 'proxy.example')
    h.state.strings.set(0x999n, 'direct.example')
    h.state.decodeResult = [
      { fProxy: 1, fBypass: 0, proxyScheme: 1, pwszProxy: 0x888n, proxyPort: 8080 },
      { fProxy: 0, fBypass: 1, proxyScheme: 3, pwszProxy: 0x999n, proxyPort: 0 },
    ]
    expect(bindings.readProxyResult(0x500n)).toEqual([
      { proxy: true, bypass: false, scheme: 1, host: 'proxy.example', port: 8080 },
      { proxy: false, bypass: true, scheme: 3, host: 'direct.example', port: 0 },
    ])
    // 没有条目或缺指针都按空结果处理，绝不索引空指针。
    h.state.proxyEntriesCount = 0
    expect(bindings.readProxyResult(0x500n)).toEqual([])
    h.state.proxyEntriesCount = 2
    h.state.proxyEntriesPointer = null
    expect(bindings.readProxyResult(0x500n)).toEqual([])
    // 主叫失败时抛出带码的固定错误。
    h.state.getProxyResultStatus = 12_018
    expect(() => bindings.readProxyResult(0x500n)).toThrow(/result could not be read \(code 12018\)/)
    h.state.getProxyResultStatus = 0
    expect(Number.isInteger(bindings.lastError())).toBe(true)
  })
})
