import { afterEach, describe, expect, test, vi } from 'vitest'
import type { LinuxLookupOutcome } from '../../src/native/system-proxy-linux.js'

/**
 * 载入失败的分类：`null`（有证据的来源缺失 -> 直连）**只**允许出现在「共享库文件不存在」这一种情况；
 * 权限拒绝、无效 ELF/ABI、符号缺失都必须抛出而不是降级为直连。
 *
 * <p>同一个假 Koffi 还用来驱动**真实绑定闭包**的 FFI 所有权：GError 出参 slot 必须被 `koffi.free` 恰好释放一次，
 * `gchar**`/`GError` 无论如何都要被尝试释放，且任何原生异常都不能让结算丢失或逃逸。每个用例用全新的模块实例
 * （`vi.resetModules`）来避开生产侧的载入缓存。
 */
const h = vi.hoisted(() => ({
  loadFailures: new Map<string, string>(),
  symbolFailures: new Set<string>(),
  slots: new Map<number, unknown>(),
  freed: [] as unknown[],
  freeThrows: false,
  decodedStrings: new Map<number, string>(),
  strfreevCalls: [] as unknown[],
  errorFreeCalls: [] as unknown[],
  strfreevThrows: false,
  errorFreeThrows: false,
  resultPointer: 0x700n,
  gerrorPointer: 0x800n,
  plan: {} as {
    throws?: boolean
    dispatchFails?: boolean
    decodeThrows?: boolean
    first?: string
    gerrorCode?: number
    noResult?: boolean
  },
}))

vi.mock('koffi', () => {
  const state = h
  const library = {
    func: (definition: string) => {
      const symbol = definition.match(/(\w+)\s*\(/u)?.[1] ?? ''
      if (state.symbolFailures.has(symbol)) {
        throw new Error(`Cannot find function '${symbol}' in shared library`)
      }
      if (symbol === 'g_strfreev') {
        return (array: unknown) => {
          state.strfreevCalls.push(array)
          if (state.strfreevThrows) {
            throw new Error('strfreev failed')
          }
        }
      }
      if (symbol === 'g_error_free') {
        return (error: unknown) => {
          state.errorFreeCalls.push(error)
          if (state.errorFreeThrows) {
            throw new Error('errorFree failed')
          }
        }
      }
      if (symbol === 'g_proxy_resolver_lookup') {
        const fn = (): bigint => 0n
        return Object.assign(fn, {
          async: (
            _resolver: unknown,
            _uri: unknown,
            _cancellable: unknown,
            errorSlot: unknown,
            callback: (failure: unknown, result: unknown) => void,
          ): void => {
            if (state.plan.throws === true) {
              throw new Error('native dispatch failed')
            }
            if (state.plan.gerrorCode !== undefined) {
              state.slots.set(Number(errorSlot), state.gerrorPointer)
            }
            if (state.plan.dispatchFails === true) {
              callback(new Error('native dispatch failed'), undefined)
              return
            }
            callback(null, state.plan.noResult === true ? undefined : state.resultPointer)
          },
        })
      }
      return (): bigint => 0n
    },
  }
  const koffi = {
    load: (name: string) => {
      const failure = state.loadFailures.get(name)
      if (failure !== undefined) {
        throw new Error(failure)
      }
      return library
    },
    struct: (name: unknown) => ({ name: typeof name === 'string' ? name : 'anonymous' }),
    alloc: () => {
      const slot = 1000 + state.slots.size
      state.slots.set(slot, null)
      return BigInt(slot)
    },
    encode: (slot: unknown, _type: unknown, value: unknown) => {
      state.slots.set(Number(slot), value)
    },
    free: (pointer: unknown) => {
      state.freed.push(pointer)
      if (state.freeThrows) {
        throw new Error('koffi free failed')
      }
    },
    decode: (pointer: unknown, type: unknown) => {
      if (type === 'void *') {
        return state.slots.get(Number(pointer)) ?? null
      }
      if (type === 'char *') {
        if (state.plan.decodeThrows === true) {
          throw new Error('native decode failed')
        }
        return state.plan.first ?? 'http://proxy:3128'
      }
      if (typeof type === 'object' && type !== null && (type as { name?: string }).name === 'GError') {
        return { code: state.plan.gerrorCode ?? 0 }
      }
      return null
    },
  }
  return { ...koffi, default: koffi }
})

async function freshModule(): Promise<typeof import('../../src/native/system-proxy-linux.js')> {
  vi.resetModules()
  return import('../../src/native/system-proxy-linux.js')
}

function resetState(): void {
  h.loadFailures.clear()
  h.symbolFailures.clear()
  h.slots.clear()
  h.freed = []
  h.freeThrows = false
  h.decodedStrings.clear()
  h.strfreevCalls = []
  h.errorFreeCalls = []
  h.strfreevThrows = false
  h.errorFreeThrows = false
  h.plan = {}
}

afterEach(() => {
  resetState()
})

describe('linux bindings loader failure classification', () => {
  test('loads the port when all three optional desktop libraries are present', async () => {
    const { loadLinuxProxyBindings } = await freshModule()
    expect(loadLinuxProxyBindings()).not.toBeNull()
    expect(typeof loadLinuxProxyBindings()?.defaultResolver).toBe('function')
  })

  test('a genuinely missing shared library is an evidenced absence and maps to direct', async () => {
    h.loadFailures.set(
      'libgio-2.0.so.0',
      'Failed to load shared library: cannot open shared object file: No such file or directory',
    )
    const { loadLinuxProxyBindings } = await freshModule()
    expect(loadLinuxProxyBindings()).toBeNull()
  })

  test('a permission failure is never downgraded to direct', async () => {
    h.loadFailures.set('libgio-2.0.so.0', 'Failed to load shared library: cannot open shared object file: Permission denied')
    const { loadLinuxProxyBindings } = await freshModule()
    expect(() => loadLinuxProxyBindings()).toThrow(/Permission denied/)
  })

  test('an invalid ABI/library format is never downgraded to direct', async () => {
    h.loadFailures.set('libgio-2.0.so.0', 'Failed to load shared library: invalid ELF header')
    const { loadLinuxProxyBindings } = await freshModule()
    expect(() => loadLinuxProxyBindings()).toThrow(/invalid ELF header/)
  })

  test('a missing symbol is never downgraded to direct', async () => {
    h.symbolFailures.add('g_proxy_resolver_get_default')
    const { loadLinuxProxyBindings } = await freshModule()
    expect(() => loadLinuxProxyBindings()).toThrow(/Cannot find function/)
  })
})

describe('linux real bindings native ownership', () => {
  async function realBindings(): Promise<
    NonNullable<Awaited<ReturnType<typeof import('../../src/native/system-proxy-linux.js').loadLinuxProxyBindings>>>
  > {
    const { loadLinuxProxyBindings } = await freshModule()
    const bindings = loadLinuxProxyBindings()
    expect(bindings).not.toBeNull()
    return bindings!
  }

  function runLookup(bindings: { startLookup: (...args: never[]) => void }): LinuxLookupOutcome[] {
    const outcomes: LinuxLookupOutcome[] = []
    ;(bindings.startLookup as unknown as (r: bigint, u: string, c: bigint, s: (o: LinuxLookupOutcome) => void) => void)(
      0x1n,
      'http://example.com/',
      0x2n,
      (outcome) => outcomes.push(outcome),
    )
    return outcomes
  }

  test('decodes the first candidate and releases the gchar** and the GError slot exactly once', async () => {
    const bindings = await realBindings()
    h.plan = { first: 'http://proxy:3128' }
    const outcomes = runLookup(bindings)
    expect(outcomes).toEqual([{ kind: 'proxies', first: 'http://proxy:3128', cleanupFailed: false }])
    expect(h.strfreevCalls).toEqual([h.resultPointer])
    expect(h.errorFreeCalls).toEqual([])
    expect(h.freed).toHaveLength(1)
  })

  test('a GError settles as the numeric lookup failure and still frees the error and the slot', async () => {
    const bindings = await realBindings()
    h.plan = { noResult: true, gerrorCode: 13 }
    const outcomes = runLookup(bindings)
    expect(outcomes).toEqual([{ kind: 'error', code: 13, cleanupFailed: false }])
    expect(h.strfreevCalls).toEqual([])
    expect(h.errorFreeCalls).toEqual([h.gerrorPointer])
    expect(h.freed).toHaveLength(1)
  })

  test('a decode failure never reports success and still attempts every release', async () => {
    const bindings = await realBindings()
    h.plan = { decodeThrows: true }
    const outcomes = runLookup(bindings)
    // 主失败（解码不到结果）与清理失败同时上报。
    expect(outcomes).toEqual([{ kind: 'error', code: -1, cleanupFailed: true }])
    // gchar** 与 slot 都仍被尝试释放。
    expect(h.strfreevCalls).toEqual([h.resultPointer])
    expect(h.freed).toHaveLength(1)
  })

  test('an unconfirmed release is reported as cleanup failure, never as a decoded success', async () => {
    const bindings = await realBindings()
    h.plan = { first: 'http://proxy:3128' }
    h.strfreevThrows = true
    const outcomes = runLookup(bindings)
    expect(outcomes).toEqual([{ kind: 'proxies', first: 'http://proxy:3128', cleanupFailed: true }])
    expect(h.freed).toHaveLength(1)
  })

  test('a failing slot release is reported instead of a success', async () => {
    const bindings = await realBindings()
    h.plan = { first: 'http://proxy:3128' }
    h.freeThrows = true
    const outcomes = runLookup(bindings)
    expect(outcomes).toEqual([{ kind: 'proxies', first: 'http://proxy:3128', cleanupFailed: true }])
  })

  test('a native error-free failure is surfaced while the primary lookup failure is preserved', async () => {
    const bindings = await realBindings()
    h.plan = { noResult: true, gerrorCode: 19 }
    h.errorFreeThrows = true
    const outcomes = runLookup(bindings)
    // 主失败（GError）与清理失败同时保留，互不掩盖。
    expect(outcomes).toEqual([{ kind: 'error', code: 19, cleanupFailed: true }])
    expect(h.freed).toHaveLength(1)
  })

  test('a synchronous dispatch failure frees the allocated slot and settles exactly once', async () => {
    const bindings = await realBindings()
    h.plan = { throws: true }
    const outcomes = runLookup(bindings)
    expect(outcomes).toEqual([{ kind: 'error', code: -1, cleanupFailed: false }])
    expect(h.freed).toHaveLength(1)
  })

  test('a dispatch failure reported by the native layer is never a fabricated success', async () => {
    const bindings = await realBindings()
    h.plan = { dispatchFails: true }
    const outcomes = runLookup(bindings)
    expect(outcomes).toEqual([{ kind: 'error', code: -1, cleanupFailed: false }])
    expect(h.strfreevCalls).toEqual([])
    expect(h.freed).toHaveLength(1)
  })

  test('the resolver never hangs when the native path fails, and settles exactly once', async () => {
    const { LinuxSystemProxyResolver, loadLinuxProxyBindings } = await freshModule()
    h.plan = { decodeThrows: true }
    const resolver = new LinuxSystemProxyResolver(loadLinuxProxyBindings())
    await expect(resolver.resolve('http://example.com/', new AbortController().signal)).rejects.toThrow(/lookup failed/)
    expect(h.freed).toHaveLength(1)
    await expect(resolver.close()).rejects.toThrow(/handle could not be released/)
    await expect(resolver.resolve('http://example.com/', new AbortController().signal)).rejects.toThrow(/closed/)
  })
})
