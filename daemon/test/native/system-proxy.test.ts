import { afterEach, describe, expect, test, vi } from 'vitest'
import { UnsupportedPlatformError, createSystemProxyResolver } from '../../src/native/index.js'
import { DarwinSystemProxyResolver } from '../../src/native/system-proxy-darwin.js'
import { LinuxSystemProxyResolver } from '../../src/native/system-proxy-linux.js'
import { WindowsSystemProxyResolver } from '../../src/native/system-proxy-windows.js'

/**
 * 系统代理工厂只选择平台实现，不加载原生库。显式环境代理因此不会被系统依赖阻断；
 * 未知平台仍然失败，而不是降级成直连。
 */
function withPlatform<T>(platform: NodeJS.Platform, body: () => T): T {
  const spy = vi.spyOn(process, 'platform', 'get').mockReturnValue(platform)
  try {
    return body()
  } finally {
    spy.mockRestore()
  }
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.doUnmock('koffi')
  vi.resetModules()
})

describe('system proxy resolver factory', () => {
  test('an unknown platform fails without a process-confinement diagnostic', () => {
    expect(() => withPlatform('freebsd', () => createSystemProxyResolver())).toThrow(UnsupportedPlatformError)
    try {
      withPlatform('freebsd', () => createSystemProxyResolver())
      throw new Error('unsupported platform must fail')
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      expect(message).toContain('does not support freebsd')
      expect(message).not.toContain('strict')
    }
  })

  test('darwin selects the CFNetwork resolver and close is idempotent before any lookup', async () => {
    const resolver = withPlatform('darwin', () => createSystemProxyResolver())
    expect(resolver).toBeInstanceOf(DarwinSystemProxyResolver)
    await expect(resolver.close()).resolves.toBeUndefined()
    await expect(resolver.close()).resolves.toBeUndefined()
  })

  test.runIf(process.platform === 'linux')('linux selects the real GLib/GIO resolver without resolving', async () => {
    const resolver = createSystemProxyResolver()
    expect(resolver).toBeInstanceOf(LinuxSystemProxyResolver)
    await expect(resolver.close()).resolves.toBeUndefined()
  })

  test('win32 selects the WinHTTP resolver without loading it during construction', async () => {
    const resolver = withPlatform('win32', () => createSystemProxyResolver())
    expect(resolver).toBeInstanceOf(WindowsSystemProxyResolver)
    await expect(resolver.close()).resolves.toBeUndefined()
  })

  test('construction does not load native proxy libraries on any supported platform', async () => {
    vi.resetModules()
    const load = vi.fn(() => {
      throw new Error('native proxy library must stay unloaded until resolve')
    })
    vi.doMock('koffi', () => ({ default: { load } }))
    const native = await import('../../src/native/system-proxy.js')
    for (const platform of ['darwin', 'linux', 'win32'] as const) {
      const resolver = withPlatform(platform, () => native.createSystemProxyResolver())
      await expect(resolver.close()).resolves.toBeUndefined()
    }
    expect(load).not.toHaveBeenCalled()
  })
})
