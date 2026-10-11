import { afterEach, describe, expect, test, vi } from 'vitest'
import { UnsupportedPlatformError, createSystemProxyResolver } from '../../src/native/index.js'
import { LinuxSystemProxyResolver } from '../../src/native/system-proxy-linux.js'

/**
 * 系统代理工厂的平台分派必须与进程收敛保持一致：受支持平台返回真实实现，其它平台失败关闭而不是降级。
 * win32 分派由 system-proxy-windows.test.ts 在假 WinHTTP 绑定下覆盖。
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
})

describe('system proxy resolver factory', () => {
  test('darwin and unknown platforms fail closed with the strict-platform diagnostic', () => {
    expect(() => withPlatform('darwin', () => createSystemProxyResolver())).toThrow(UnsupportedPlatformError)
    expect(() => withPlatform('darwin', () => createSystemProxyResolver())).toThrow(/strict native process confinement is unavailable on darwin/)
    expect(() => withPlatform('freebsd', () => createSystemProxyResolver())).toThrow(/does not support freebsd/)
  })

  test.runIf(process.platform === 'linux')('linux selects the real GLib/GIO resolver', async () => {
    const resolver = createSystemProxyResolver()
    expect(resolver).toBeInstanceOf(LinuxSystemProxyResolver)
    // 没有配置也不会抛：真实宿主只读，不为测试改动 gnome/系统代理配置。
    await expect(resolver.close()).resolves.toBeUndefined()
  })
})
