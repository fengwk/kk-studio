import { afterEach, describe, expect, test, vi } from 'vitest'
import {
  acquireDirectoryLock,
  createProcessSupervisor,
  processScopeCapabilities,
} from '../../src/native/index.js'
import { PosixProcessSupervisor } from '../../src/native/posix-supervisor.js'
import { WindowsProcessSupervisor } from '../../src/native/process-supervisor-windows.js'
import {
  becomeChildSubreaper,
  convergeAdoptedChildren,
  directChildren,
  establishSession,
  membersSnapshot,
  processIdentity,
  reapOrphans,
  sessionOf,
  startOrphanReaper,
  supportsSessionEnumeration,
} from '../../src/native/posix-session.js'

/**
 * 能力自述与平台分派必须按平台如实证伪：未验收的限制要显式暴露，不支持的平台必须失败关闭，
 * 绝不能把「当前平台没验证」当成「已经支持」。本文件用受控的 platform getter 在 Linux 主机上
 * 驱动其它平台分支，验证的是真实的判定逻辑，而不是伪造返回值。
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

describe('native capabilities per platform', () => {
  test('linux declares session lifecycle with orphan adoption and no limits', () => {
    // Linux 分支是纯能力自述（无 native 调用）：在任何主机上都必须如实且精确。
    const capabilities = withPlatform('linux', () => processScopeCapabilities())
    expect(capabilities).toEqual({
      platform: 'linux',
      sessionConvergence: true,
      jobMembership: false,
      orphanAdoption: true,
      unverifiedLimits: [],
    })
  })

  test('darwin exposes session lifecycle without orphan adoption', () => {
    const capabilities = withPlatform('darwin', () => processScopeCapabilities())
    expect(capabilities.platform).toBe('darwin')
    expect(capabilities.sessionConvergence).toBe(true)
    expect(capabilities.orphanAdoption).toBe(false)
    expect(capabilities.jobMembership).toBe(false)
    // 没有收养者只限制未登记且故意脱离的后代，不取消会话生命周期。
    expect(capabilities.unverifiedLimits).toHaveLength(1)
    expect(capabilities.unverifiedLimits[0]).toContain('child subreaper')
    expect(capabilities.unverifiedLimits[0]).not.toContain('strict')
  })

  test('win32 declares job membership only and no session convergence', () => {
    const capabilities = withPlatform('win32', () => processScopeCapabilities())
    expect(capabilities.platform).toBe('win32')
    expect(capabilities.jobMembership).toBe(true)
    expect(capabilities.sessionConvergence).toBe(false)
    expect(capabilities.orphanAdoption).toBe(false)
    expect(capabilities.unverifiedLimits).toHaveLength(1)
  })

  test('an unknown platform reports every native guarantee as unavailable', () => {
    const capabilities = withPlatform('freebsd', () => processScopeCapabilities())
    expect(capabilities.platform).toBe('freebsd')
    expect(capabilities.sessionConvergence).toBe(false)
    expect(capabilities.jobMembership).toBe(false)
    expect(capabilities.orphanAdoption).toBe(false)
    expect(capabilities.unverifiedLimits[0]).toContain('not supported on freebsd')
  })
})

describe('native entry points fail closed on unsupported platforms', () => {
  test('an unsupported platform cannot create a process supervisor', () => {
    expect(() => withPlatform('freebsd', () => createProcessSupervisor())).toThrow(/does not support freebsd/)
  })

  test('darwin selects the POSIX supervisor without requiring a subreaper', async () => {
    // 工厂在模拟的 darwin 上仍返回现有 POSIX 实现；缺少收养者不是拒绝创建的理由。
    const supervisor = withPlatform('darwin', () => createProcessSupervisor())
    expect(supervisor).toBeInstanceOf(PosixProcessSupervisor)
    await expect(supervisor.close()).resolves.toBeUndefined()
  })

  test.runIf(process.platform === 'linux')('linux selects the POSIX supervisor with orphan adoption', async () => {
    // 工厂在受支持平台上不降级：仍返回 POSIX 实现（子进程收养由构造函数启用）。
    const supervisor = createProcessSupervisor()
    expect(supervisor).toBeInstanceOf(PosixProcessSupervisor)
    // 没有启动任何进程：真实 close 必须直接收敛，不需要任何假绑定。
    await expect(supervisor.close()).resolves.toBeUndefined()
  })

  test('win32 selects the Job-based supervisor and closes cleanly with no processes', async () => {
    const supervisor = withPlatform('win32', () => createProcessSupervisor())
    expect(supervisor).toBeInstanceOf(WindowsProcessSupervisor)
    // 没有启动任何进程：真实 close 必须直接收敛，不需要任何假绑定。
    await expect(supervisor.close()).resolves.toBeUndefined()
  })

  test('an unsupported platform cannot take a directory lock', async () => {
    await expect(withPlatform('freebsd', () => acquireDirectoryLock('/tmp/kk-studio-unsupported-lock'))).rejects.toThrow(
      /lock is not supported on freebsd/,
    )
  })
})

describe('posix session primitives respect platform availability', () => {
  test('non-linux platforms have no subreaper or orphan reaping', () => {
    withPlatform('darwin', () => {
      expect(becomeChildSubreaper()).toBe(false)
      expect(() => reapOrphans()).not.toThrow()
      const stop = startOrphanReaper(10)
      expect(typeof stop).toBe('function')
      stop()
      // macOS 的孤儿被重新挂到 launchd：没有可核实的收养集合，直接判定为空。
      expect(directChildren(1, [])).toEqual([])
    })
    withPlatform('freebsd', () => {
      expect(becomeChildSubreaper()).toBe(false)
    })
  })

  test('non-linux platforms treat adopted children as nothing to converge', async () => {
    await expect(withPlatform('darwin', () => convergeAdoptedChildren(1, 10, []))).resolves.toBe(true)
  })

  test('session enumeration is unavailable off linux/darwin', () => {
    withPlatform('freebsd', () => {
      expect(supportsSessionEnumeration()).toBe(false)
      expect(() => establishSession()).toThrow(/not supported on freebsd/)
      expect(sessionOf(1)).toBe(-1)
      expect(processIdentity(1)).toBeNull()
      expect(membersSnapshot(1, -1)).toBeNull()
    })
  })

  test('darwin advertises session enumeration', () => {
    withPlatform('darwin', () => {
      expect(supportsSessionEnumeration()).toBe(true)
    })
  })
})
