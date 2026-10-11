import { afterEach, describe, expect, test } from 'vitest'
import {
  createSuspendedJob,
  installWindowsNativeBindingsForTest,
  releaseDirectoryLock,
  type WindowsNativeBindings,
} from '../../src/native/win32.js'

/**
 * 用假原生绑定在**非 Windows 主机**上驱动真实 `JobScope` 代码路径。
 *
 * <p>这是本轮唯一能在 Linux 上回归的核心性质：`awaitExit` 绝不能把 helper 的 Node 事件循环同步阻塞住。若它仍以
 * `WaitForSingleObject(handle, INFINITE)` 等待，父进程被强杀时内核关闭 IPC 通道产生的 `disconnect` 永远无法被派发，
 * 收尾就永远不会发生。断言两点：(1) 传给 `WaitForSingleObject` 的等待毫秒数始终是有限小值（不是 `INFINITE`）；
 * (2) 等待期间定时器仍在跑（事件循环未被阻塞）。
 */
const WAIT_OBJECT_0 = 0
const WAIT_TIMEOUT = 0x102
const WAIT_FAILED = 0xffff_ffff
const INFINITE = 0xffff_ffff

interface Harness {
  bindings: WindowsNativeBindings
  waits: number[]
  inJob: { value: number }
  closed: number[]
  ticks: { value: number }
}

function harness(): Harness {
  const waits: number[] = []
  const inJob = { value: 1 }
  const closed: number[] = []
  const ticks = { value: 0 }
  let waitCalls = 0
  const kernel = {
    CreateJobObjectW: () => 10n,
    SetInformationJobObject: () => 1,
    CreateProcessW: (_a: unknown, _c: unknown, _p: unknown, _t: unknown, _i: unknown, _f: unknown, _e: unknown, _d: unknown, _s: unknown, info: Record<string, unknown>) => {
      info.hProcess = 1n
      info.hThread = 2n
      info.dwProcessId = 4242
      return 1
    },
    ResumeThread: () => 1,
    WaitForSingleObject: (_handle: unknown, millis: number) => {
      waits.push(millis)
      waitCalls += 1
      // 前几次强制超时：必须有真实的异步让出，事件循环才可能跑起来。
      return waitCalls <= 3 ? WAIT_TIMEOUT : WAIT_OBJECT_0
    },
    GetExitCodeProcess: (_process: unknown, exitCode: number[]) => {
      exitCode[0] = 7
      return 1
    },
    TerminateJobObject: () => 1,
    QueryInformationJobObject: (_job: unknown, _c: unknown, buffer: Record<string, number>) => {
      buffer.ActiveProcesses = 0
      return 1
    },
    OpenJobObjectW: () => 10n,
    IsProcessInJob: (_process: unknown, _job: unknown, result: number[]) => {
      result[0] = inJob.value
      return 1
    },
    OpenProcess: () => 3n,
    CloseHandle: (handle: bigint) => {
      closed.push(Number(handle))
      return 1
    },
    TerminateProcess: () => 1,
    SetHandleInformation: () => 1,
    GetStdHandle: () => 5n,
    GetCurrentProcess: () => 9n,
    InitializeProcThreadAttributeList: (_list: unknown, _count: unknown, _flags: unknown, size: { value: number }) => {
      size.value = 112
      return 1
    },
    UpdateProcThreadAttribute: () => 1,
    DeleteProcThreadAttributeList: () => undefined,
    GetLastError: () => 0,
    GetFileAttributesW: () => 0x80,
    CreateFileW: () => 6n,
    LockFileEx: () => 1,
    UnlockFileEx: () => 1,
  }
  const advapi = {
    GetNamedSecurityInfoW: () => 0,
    SetNamedSecurityInfoW: () => 0,
    OpenProcessToken: () => 1,
    GetTokenInformation: () => 1,
    InitializeAcl: () => 1,
    AddAccessAllowedAceEx: () => 1,
    GetAce: () => 1,
    GetLengthSid: () => 12,
    EqualSid: () => 1,
    LocalFree: () => null,
  }
  const types = {
    startupInfoEx: { size: 112 },
    jobExtendedLimit: { size: 144 },
    jobBasicAccounting: { size: 48 },
    sizeBox: { size: 8 },
    acl: { size: 8 },
    aceHeader: { size: 4 },
    tokenUser: { size: 16 },
  }
  return { bindings: { kernel, advapi, types } as unknown as WindowsNativeBindings, waits, inJob, closed, ticks }
}

afterEach(() => {
  installWindowsNativeBindingsForTest(null)
})

describe('windows job scope (mocked native bindings)', () => {
  test('awaitExit polls without blocking the event loop and never waits with INFINITE', async () => {
    const fake = harness()
    installWindowsNativeBindingsForTest(fake.bindings)
    const scope = await createSuspendedJob('kk-studio-test-job', { cwd: process.cwd(), args: ['fake-command'] }, {})
    scope.resume()

    const ticker = setInterval(() => {
      fake.ticks.value += 1
    }, 1)
    try {
      const report = await scope.awaitExit()
      expect(report.exitCode).toBe(7)
    } finally {
      clearInterval(ticker)
      scope.close()
    }

    // 绝不出现同步无限等待：每个等待毫秒数都是有限的（0），且事件循环在等待期间确实跑过定时器。
    expect(fake.waits.length).toBeGreaterThan(1)
    expect(fake.waits.every((millis) => millis !== INFINITE && millis !== WAIT_FAILED)).toBe(true)
    expect(fake.waits.every((millis) => Number.isFinite(millis) && millis <= 1000)).toBe(true)
    expect(fake.ticks.value).toBeGreaterThan(0)
  })

  test('confirmDescendant confirms an in-job child and fails closed for a foreign pid', async () => {
    const fake = harness()
    installWindowsNativeBindingsForTest(fake.bindings)
    const scope = await createSuspendedJob('kk-studio-test-job', { cwd: process.cwd(), args: ['fake-command'] }, {})
    try {
      expect(() => scope.confirmDescendant(4242)).not.toThrow()
      fake.inJob.value = 0
      // 不属于本 Job 的 pid 必须拒绝，绝不调用 AssignProcessToJobObject 把别人的进程劫持进来。
      expect(() => scope.confirmDescendant(process.pid)).toThrow(/not a member/)
    } finally {
      scope.close()
    }
  })

  test('releaseDirectoryLock always closes the handle and aggregates unlock failures', () => {
    const calls: string[] = []
    const base = {
      GetLastError: () => 5,
      UnlockFileEx: () => {
        calls.push('unlock')
        return 0
      },
      CloseHandle: () => {
        calls.push('close')
        return 1
      },
    }
    expect(() => releaseDirectoryLock(base, 6n, {})).toThrow(/unlock the directory lock file \(5\)/)
    // 解锁失败后仍然关闭了句柄。
    expect(calls).toEqual(['unlock', 'close'])

    const both = {
      GetLastError: () => 6,
      UnlockFileEx: () => 0,
      CloseHandle: () => 0,
    }
    expect(() => releaseDirectoryLock(both, 6n, {})).toThrow(/unlock.*; cannot close/)

    const ok = {
      GetLastError: () => 0,
      UnlockFileEx: () => 1,
      CloseHandle: () => 1,
    }
    expect(() => releaseDirectoryLock(ok, 6n, {})).not.toThrow()
  })
})
