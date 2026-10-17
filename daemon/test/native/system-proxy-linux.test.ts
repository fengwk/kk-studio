import { describe, expect, test } from 'vitest'
import type { LinuxLookupOutcome, LinuxProxyBindings } from '../../src/native/system-proxy-linux.js'
import { LinuxSystemProxyResolver, interpretLinuxProxy, loadLinuxProxyBindings } from '../../src/native/system-proxy-linux.js'

/**
 * Linux 代理逻辑用受控假端口驱动：真实的 GLib/GIO 边界只在宿主可用时做只读集成验证。
 * 假端口精确复刻端口契约——decode/释放都在端口内完成，查询只结算一次。
 */
class FakeLinuxProxy {
  resolver: bigint | null = 0x1n
  cancellable: bigint | null = 0x2n
  readonly cancelled: bigint[] = []
  readonly unrefed: bigint[] = []
  readonly lookups: Array<{ resolver: bigint; uri: string; cancellable: bigint }> = []
  autoOutcome: LinuxLookupOutcome | null = null
  cancelError: Error | null = null
  unrefError: Error | null = null
  private settle: ((outcome: LinuxLookupOutcome) => void) | null = null

  bindings(): LinuxProxyBindings {
    return {
      defaultResolver: () => this.resolver,
      createCancellable: () => this.cancellable,
      cancelCancellable: (cancellable) => {
        this.cancelled.push(cancellable)
        if (this.cancelError !== null) {
          throw this.cancelError
        }
      },
      unref: (object) => {
        this.unrefed.push(object)
        if (this.unrefError !== null) {
          throw this.unrefError
        }
      },
      startLookup: (resolver, uri, cancellable, settle) => {
        this.lookups.push({ resolver, uri, cancellable })
        this.settle = settle
        if (this.autoOutcome !== null) {
          settle(this.autoOutcome)
        }
      },
    }
  }

  complete(outcome: LinuxLookupOutcome): void {
    this.settle?.(outcome)
  }
}

function abortedSignal(): AbortSignal {
  const controller = new AbortController()
  controller.abort()
  return controller.signal
}

describe('linux system proxy interpretation', () => {
  test('maps direct:// to a direct connection and http proxies to a canonical url', () => {
    expect(interpretLinuxProxy('direct://')).toBeNull()
    expect(interpretLinuxProxy('http://proxy.example:8080')).toBe('http://proxy.example:8080')
    // 省略端口时补协议默认端口，保证对外始终是规范的 http://host:port。
    expect(interpretLinuxProxy('http://proxy.example')).toBe('http://proxy.example:80')
  })

  test('fails closed on unsupported protocols, credentials, and malformed candidates', () => {
    expect(() => interpretLinuxProxy('socks5://proxy.example:1080')).toThrow(/does not support/)
    expect(() => interpretLinuxProxy('https://proxy.example:8443')).toThrow(/does not support/)
    expect(() => interpretLinuxProxy('http://user:secret@proxy.example:8080')).toThrow(/does not support/)
    expect(() => interpretLinuxProxy('http://')).toThrow(/malformed/)
    expect(() => interpretLinuxProxy('proxy.example:8080')).toThrow(/malformed/)
  })

  test('accepts a root path and a bracketed IPv6 host, but only a bare direct:// token', () => {
    // OS provider 常见的根路径尾斜杠可接受，对外仍是规范 http://host:port。
    expect(interpretLinuxProxy('http://proxy:8080/')).toBe('http://proxy:8080')
    // IPv6 主机必须带方括号，端口仍规范化。
    expect(interpretLinuxProxy('http://[::1]:8080')).toBe('http://[::1]:8080')
    // direct:// 只表示直连本身；带 host 的取值语义不明，失败关闭。
    expect(() => interpretLinuxProxy('direct://host')).toThrow(/malformed/)
  })

  test('rejects proxy candidates carrying path, query, fragment, or a zero port', () => {
    // 额外的 path/query/fragment 或显式 0 端口都不能被悄悄丢弃，一律失败关闭。
    expect(() => interpretLinuxProxy('http://proxy:8080/path')).toThrow(/malformed/)
    expect(() => interpretLinuxProxy('http://proxy:8080?q')).toThrow(/malformed/)
    expect(() => interpretLinuxProxy('http://proxy:8080#f')).toThrow(/malformed/)
    expect(() => interpretLinuxProxy('http://proxy:0')).toThrow(/malformed/)
  })

  test('an empty candidate list resolves to direct', () => {
    expect(interpretLinuxProxy(null)).toBeNull()
  })
})

describe('linux system proxy resolver behaviour', () => {
  test('an evidenced missing desktop library resolves to direct without touching gio', async () => {
    // 库缺失是有证据的来源缺失：操作系统没有代理可委托，等价直连，而不是查询错误。
    const resolver = new LinuxSystemProxyResolver(null)
    await expect(resolver.resolve('http://example.com/', new AbortController().signal)).resolves.toBeNull()
    await resolver.close()
  })

  test('resolves the first candidate and releases the cancellable exactly once', async () => {
    const fake = new FakeLinuxProxy()
    fake.autoOutcome = { kind: 'proxies', first: 'http://proxy:3128' }
    const resolver = new LinuxSystemProxyResolver(fake.bindings())
    await expect(resolver.resolve('http://example.com/', new AbortController().signal)).resolves.toBe('http://proxy:3128')
    expect(fake.lookups).toHaveLength(1)
    expect(fake.lookups[0]?.uri).toBe('http://example.com/')
    expect(fake.unrefed).toEqual([fake.cancellable])
    await resolver.close()
  })

  test('a lookup error is reported with its numeric code and never falls back to direct', async () => {
    const fake = new FakeLinuxProxy()
    fake.autoOutcome = { kind: 'error', code: 13 }
    const resolver = new LinuxSystemProxyResolver(fake.bindings())
    await expect(resolver.resolve('http://example.com/', new AbortController().signal)).rejects.toThrow(
      /proxy lookup failed \(code 13\)/,
    )
    expect(fake.unrefed).toEqual([fake.cancellable])
    await resolver.close()
  })

  test('missing native handles fail closed with a fixed diagnostic', async () => {
    const noResolver = new FakeLinuxProxy()
    noResolver.resolver = null
    await expect(
      new LinuxSystemProxyResolver(noResolver.bindings()).resolve('http://example.com/', new AbortController().signal),
    ).rejects.toThrow(/resolver is unavailable/)

    const noCancellable = new FakeLinuxProxy()
    noCancellable.cancellable = null
    await expect(
      new LinuxSystemProxyResolver(noCancellable.bindings()).resolve('http://example.com/', new AbortController().signal),
    ).rejects.toThrow(/resolver is unavailable/)
  })

  test('an already-aborted signal never starts the lookup', async () => {
    const fake = new FakeLinuxProxy()
    const resolver = new LinuxSystemProxyResolver(fake.bindings())
    await expect(resolver.resolve('http://example.com/', abortedSignal())).rejects.toThrow(/cancelled/)
    expect(fake.lookups).toHaveLength(0)
  })

  test('cancellation is never rewritten into direct by an evidenced absent library', async () => {
    // 缺失库把「没有来源」映射为直连；已取消的查询必须先以取消结束，不能被改写成直连。
    const resolver = new LinuxSystemProxyResolver(null)
    await expect(resolver.resolve('http://example.com/', abortedSignal())).rejects.toThrow(/cancelled/)
    await resolver.close()
  })

  test('an abort mid-flight cancels the cancellable and settles as cancelled after the lookup ends', async () => {
    const fake = new FakeLinuxProxy()
    const resolver = new LinuxSystemProxyResolver(fake.bindings())
    const controller = new AbortController()
    const pending = resolver.resolve('http://example.com/', controller.signal)
    controller.abort()
    expect(fake.cancelled).toEqual([fake.cancellable])
    // 取消只是请求中断：lookup 结束后才结算并释放。
    fake.complete({ kind: 'proxies', first: 'http://proxy:3128' })
    await expect(pending).rejects.toThrow(/cancelled/)
    expect(fake.unrefed).toEqual([fake.cancellable])
    await resolver.close()
  })

  test('close aborts in-flight lookups and waits for them to release their native buffers', async () => {
    const fake = new FakeLinuxProxy()
    const resolver = new LinuxSystemProxyResolver(fake.bindings())
    const pending = resolver.resolve('http://example.com/', new AbortController().signal)
    const closing = resolver.close()
    expect(fake.cancelled).toEqual([fake.cancellable])
    fake.complete({ kind: 'error', code: 19 })
    await expect(pending).rejects.toThrow(/cancelled/)
    await expect(closing).resolves.toBeUndefined()
    expect(fake.unrefed).toEqual([fake.cancellable])
    // close 之后不再服务。
    await expect(resolver.resolve('http://example.com/', new AbortController().signal)).rejects.toThrow(/closed/)
  })

  test('close is memoized: both callers wait for the real lookup and its release', async () => {
    const fake = new FakeLinuxProxy()
    const resolver = new LinuxSystemProxyResolver(fake.bindings())
    const pending = resolver.resolve('http://example.com/', new AbortController().signal)
    const first = resolver.close()
    const second = resolver.close()
    expect(second).toBe(first)
    let settled = false
    void first.then(() => {
      settled = true
    })
    await Promise.resolve()
    // lookup 还没结束：两次 close 都不能提前成功，也不能提前释放。
    expect(settled).toBe(false)
    expect(fake.unrefed).toEqual([])
    fake.complete({ kind: 'proxies', first: 'direct://' })
    await expect(pending).rejects.toThrow(/cancelled/)
    await expect(first).resolves.toBeUndefined()
    await expect(second).resolves.toBeUndefined()
    expect(fake.unrefed).toEqual([fake.cancellable])
  })

  test('a failing native cancel neither stops the other cancels nor leaves the lookup pending', async () => {
    const fake = new FakeLinuxProxy()
    fake.cancelError = new Error('native cancel failed')
    const resolver = new LinuxSystemProxyResolver(fake.bindings())
    const pending = resolver.resolve('http://example.com/', new AbortController().signal)
    const closing = resolver.close()
    fake.complete({ kind: 'error', code: 19 })
    await expect(pending).rejects.toThrow(/cancelled/)
    // 取消失败也必须等到查询真正结束并释放，然后用固定文案显式上报（不回显原生异常文本）。
    const failure = await closing.catch((error: Error) => error)
    expect((failure as Error).message).toBe('the operating system proxy lookup cancellation could not be requested')
    expect(fake.cancelled).toEqual([fake.cancellable])
    expect(fake.unrefed).toEqual([fake.cancellable])
  })

  test('a synchronous cancel failure never escapes the abort dispatch as an uncaught exception', async () => {
    const fake = new FakeLinuxProxy()
    fake.cancelError = new Error('native cancel failed')
    const resolver = new LinuxSystemProxyResolver(fake.bindings())
    const controller = new AbortController()
    const pending = resolver.resolve('http://example.com/', controller.signal)
    const uncaught: unknown[] = []
    const onUncaught = (error: unknown): void => {
      uncaught.push(error)
    }
    process.on('uncaughtException', onUncaught)
    try {
      controller.abort()
      await new Promise((resolve) => {
        setImmediate(resolve)
      })
    } finally {
      process.off('uncaughtException', onUncaught)
    }
    expect(uncaught).toEqual([])
    // 固定错误在 lookup 结束并释放之后才 reject 当前 resolve，不让取消失败静默溜走。
    fake.complete({ kind: 'proxies', first: 'http://proxy:3128' })
    await expect(pending).rejects.toThrow(/cancellation could not be requested/)
    expect(fake.unrefed).toEqual([fake.cancellable])
  })

  test('a failing native release is reported as an unconfirmed cleanup and makes the resolver unusable', async () => {
    const fake = new FakeLinuxProxy()
    fake.autoOutcome = { kind: 'proxies', first: 'direct://' }
    fake.unrefError = new Error('native release failed')
    const resolver = new LinuxSystemProxyResolver(fake.bindings())
    const failure = await resolver
      .resolve('http://example.com/', new AbortController().signal)
      .catch((error: Error) => error)
    // 释放抛错必须以固定文案上报，绝不假装释放完成，也不回显原生异常原文。
    expect((failure as Error).message).toBe('the operating system proxy handle could not be released')
    // 未确认的清理必须阻止未来再分配句柄，并在 close 上显式失败（不能因为查询已结束就伪称释放已确认）。
    await expect(resolver.resolve('http://example.com/', new AbortController().signal)).rejects.toThrow(/unusable/)
    await expect(resolver.close()).rejects.toThrow(/handle could not be released/)
    // 只尝试释放一次：重复释放会触发 use-after-free。
    expect(fake.unrefed).toEqual([fake.cancellable])
  })

  test('a lookup failure and a release failure are both reported without masking each other', async () => {
    const fake = new FakeLinuxProxy()
    fake.autoOutcome = { kind: 'error', code: 5 }
    fake.unrefError = new Error('native release failed')
    const resolver = new LinuxSystemProxyResolver(fake.bindings())
    const failure = (await resolver
      .resolve('http://example.com/', new AbortController().signal)
      .catch((error: unknown) => error)) as AggregateError
    // 主失败与释放失败都保留在固定 AggregateError 中，message 取主失败，不含任何原生原文。
    expect(failure).toBeInstanceOf(AggregateError)
    expect(failure.message).toMatch(/lookup failed/)
    expect(failure.errors.map((error: Error) => error.message)).toEqual([
      expect.stringMatching(/lookup failed/),
      expect.stringMatching(/handle could not be released/),
    ])
  })

  test('a cancel failure, a lookup failure and a release failure are all reported', async () => {
    const fake = new FakeLinuxProxy()
    fake.cancelError = new Error('native cancel failed')
    fake.unrefError = new Error('native release failed')
    fake.autoOutcome = null
    const resolver = new LinuxSystemProxyResolver(fake.bindings())
    const controller = new AbortController()
    const pending = resolver.resolve('http://example.com/', controller.signal)
    // 同步取消失败不从 abort 派发逃逸；lookup 随后以主失败结束。
    controller.abort()
    fake.complete({ kind: 'error', code: 7 })
    const failure = (await pending.catch((error: unknown) => error)) as Error
    // 三个安全诊断都要出现：取消、查询、释放，且都不是原生原文。
    const messages = [failure.message, ...(failure instanceof AggregateError ? failure.errors.map((error: Error) => error.message) : [])]
    expect(messages.some((message) => /cancellation could not be requested/.test(message))).toBe(true)
    expect(messages.some((message) => /lookup failed/.test(message))).toBe(true)
    expect(messages.some((message) => /handle could not be released/.test(message))).toBe(true)
    expect(JSON.stringify(messages)).not.toMatch(/native (cancel|release) failed/)
    await expect(resolver.close()).rejects.toThrow(/handle could not be released/)
  })
})

describe('linux system proxy real bindings', () => {
  test.runIf(process.platform === 'linux')('the optional desktop library is loaded on this host', () => {
    // 宿主只读探测：有库就拿到端口，没有库就是有证据的来源缺失（null）。
    const bindings = loadLinuxProxyBindings()
    expect(bindings === null || typeof bindings.defaultResolver === 'function').toBe(true)
  })

  test.runIf(process.platform === 'linux')('a real lookup terminates and releases its native buffers', async () => {
    const resolver = new LinuxSystemProxyResolver(loadLinuxProxyBindings())
    const controller = new AbortController()
    // 宿主可能配置了 http 代理、可能直连、也可能只配了本守护进程不支持的协议；只验证真实调用能结束且不泄露。
    let outcome: string | null | 'unsupported'
    try {
      outcome = await resolver.resolve('http://example.invalid/', controller.signal)
    } catch (error) {
      expect((error as Error).message).toMatch(/does not support/)
      outcome = 'unsupported'
    }
    expect(outcome === null || outcome === 'unsupported' || /^http:\/\/[^/]+:\d+$/u.test(outcome)).toBe(true)
    await expect(resolver.close()).resolves.toBeUndefined()
  })
})
