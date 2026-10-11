import { afterEach, describe, expect, test, vi } from 'vitest'

/**
 * 载入失败的分类：`null`（有证据的来源缺失 -> 直连）**只**允许出现在「共享库文件不存在」这一种情况；
 * 权限拒绝、无效 ELF/ABI、符号缺失都必须抛出而不是降级为直连。Koffi 被替换成可注入失败的假实现，
 * 每个用例用全新的模块实例（`vi.resetModules`）来避开生产侧的载入缓存。
 */
const h = vi.hoisted(() => ({
  loadFailures: new Map<string, string>(),
  symbolFailures: new Set<string>(),
}))

vi.mock('koffi', () => {
  const fake = {
    load: (name: string) => {
      const failure = h.loadFailures.get(name)
      if (failure !== undefined) {
        throw new Error(failure)
      }
      return {
        func: (definition: string) => {
          const symbol = definition.match(/(\w+)\s*\(/u)?.[1] ?? ''
          if (h.symbolFailures.has(symbol)) {
            throw new Error(`Cannot find function '${symbol}' in shared library`)
          }
          return Object.assign(() => 0, { async: () => undefined })
        },
      }
    },
    struct: (name: unknown) => ({ name: typeof name === 'string' ? name : 'anonymous' }),
    alloc: () => 0n,
    encode: () => undefined,
    decode: () => null,
  }
  return { ...fake, default: fake }
})

async function freshModule(): Promise<typeof import('../../src/native/system-proxy-linux.js')> {
  vi.resetModules()
  return import('../../src/native/system-proxy-linux.js')
}

afterEach(() => {
  h.loadFailures.clear()
  h.symbolFailures.clear()
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
