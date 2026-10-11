import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { existsSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import type { Kernel32, WindowsNativeBindings } from '../../src/native/win32.js'
import { installWindowsNativeBindingsForTest } from '../../src/native/win32.js'
import {
  WindowsManagedProcess,
  WindowsProcessSupervisor,
} from '../../src/native/process-supervisor-windows.js'
import * as state from '../../src/native/state.js'

/**
 * 在非 Windows 主机上用假原生绑定驱动真实 `process-supervisor-windows.ts`。
 *
 * <p>覆盖 Windows 父进程侧状态机里只有真实 Windows 才会自然发生、却属于「收敛/清理必须如实上报」的分支：
 * Job 打开失败、收敛失败时保留证据、句柄关闭失败仍然上报、启动前 helper 已死等。假绑定只提供调用序列，
 * 不断言任何真实 Windows 行为；被管理的「helper」是一个受控的 Node 子进程，不涉及真实 Job 语义。
 */
interface Control {
  openJob: bigint | null
  activeProcesses: number
  queryJob: number
  closeHandle: number
  terminateJob: number
  getLastError: number
}

function bindings(control: Control): WindowsNativeBindings {
  const kernel = {
    CreateJobObjectW: () => 10n,
    SetInformationJobObject: () => 1,
    CreateProcessW: () => 0,
    ResumeThread: () => 1,
    WaitForSingleObject: () => 0,
    GetExitCodeProcess: () => 1,
    TerminateJobObject: () => control.terminateJob,
    QueryInformationJobObject: (_j: unknown, _c: unknown, buffer: Record<string, number>) => {
      buffer.ActiveProcesses = control.activeProcesses
      return control.queryJob
    },
    OpenJobObjectW: () => control.openJob,
    IsProcessInJob: (_p: unknown, _j: unknown, result: number[]) => {
      result[0] = 1
      return 1
    },
    OpenProcess: () => 3n,
    CloseHandle: () => control.closeHandle,
    TerminateProcess: () => 1,
    SetHandleInformation: () => 1,
    GetStdHandle: () => 5n,
    GetCurrentProcess: () => 9n,
    InitializeProcThreadAttributeList: () => 1,
    UpdateProcThreadAttribute: () => 1,
    DeleteProcThreadAttributeList: () => undefined,
    GetLastError: () => control.getLastError,
    GetFileAttributesW: () => 0x80,
    LocalFree: () => null,
    CreateFileW: () => 6n,
    LockFileEx: () => 1,
    UnlockFileEx: () => 1,
  } as unknown as Kernel32
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
  return { kernel, advapi, types } as unknown as WindowsNativeBindings
}

const children: ChildProcessWithoutNullStreams[] = []
const stateDirs: string[] = []

function fakeHelper(lifetimeMillis = 400): ChildProcessWithoutNullStreams {
  const child = spawn(process.execPath, ['-e', `setTimeout(() => process.exit(0), ${lifetimeMillis})`], {
    stdio: ['pipe', 'pipe', 'pipe', 'ipc'],
  }) as ChildProcessWithoutNullStreams
  children.push(child)
  return child
}

async function stateDir(): Promise<string> {
  const dir = await state.createStateDir()
  stateDirs.push(dir)
  return dir
}

afterEach(async () => {
  for (const child of children.splice(0)) {
    if (child.exitCode === null && child.signalCode === null) {
      child.kill('SIGKILL')
    }
  }
  for (const dir of stateDirs.splice(0)) {
    state.removeStateDir(dir)
  }
  installWindowsNativeBindingsForTest(null)
})

describe.skipIf(process.platform === 'win32')('windows process supervisor lifecycle (mocked native bindings)', () => {
  let workdir: string

  beforeEach(() => {
    workdir = mkdtempSync(join(tmpdir(), 'kk-studio-win-supervisor-'))
  })

  afterEach(() => {
    rmSync(workdir, { recursive: true, force: true })
  })

  test('start establishes the scope, releases on natural exit, and cleans state', async () => {
    const control: Control = { openJob: 10n, activeProcesses: 0, queryJob: 1, closeHandle: 1, terminateJob: 1, getLastError: 5 }
    installWindowsNativeBindingsForTest(bindings(control))
    const supervisor = new WindowsProcessSupervisor()
    const managed = await supervisor.start({ executable: process.execPath, args: ['-e', '0'], cwd: workdir })
    expect(managed.pid).toBeGreaterThan(0)
    const exit = await managed.exit
    expect(exit.exitCode).toBe(0)
    await managed.terminate()
    await supervisor.close()
  }, 30_000)

  test('start after close fails closed', async () => {
    installWindowsNativeBindingsForTest(bindings({ openJob: 10n, activeProcesses: 0, queryJob: 1, closeHandle: 1, terminateJob: 1, getLastError: 5 }))
    const supervisor = new WindowsProcessSupervisor()
    await supervisor.close()
    await expect(supervisor.start({ executable: process.execPath, args: ['-e', '0'], cwd: workdir })).rejects.toThrow(/closed/)
  })

  test('a pre-aborted signal prevents the command from starting', async () => {
    installWindowsNativeBindingsForTest(bindings({ openJob: 10n, activeProcesses: 0, queryJob: 1, closeHandle: 1, terminateJob: 1, getLastError: 5 }))
    const supervisor = new WindowsProcessSupervisor()
    const controller = new AbortController()
    controller.abort()
    await expect(
      supervisor.start({ executable: process.execPath, args: ['-e', '0'], cwd: workdir, signal: controller.signal }),
    ).rejects.toThrow(/cancelled/)
  })

  test('trackDescendant rejects an unknown root pid', async () => {
    installWindowsNativeBindingsForTest(bindings({ openJob: 10n, activeProcesses: 0, queryJob: 1, closeHandle: 1, terminateJob: 1, getLastError: 5 }))
    const supervisor = new WindowsProcessSupervisor()
    await expect(supervisor.trackDescendant(2_000_000_000, process.pid)).rejects.toThrow(/no managed process/)
  })

  test('trackDescendant confirms an in-job descendant', async () => {
    installWindowsNativeBindingsForTest(bindings({ openJob: 10n, activeProcesses: 0, queryJob: 1, closeHandle: 1, terminateJob: 1, getLastError: 5 }))
    const supervisor = new WindowsProcessSupervisor()
    const managed = await supervisor.start({ executable: process.execPath, args: ['-e', '0'], cwd: workdir })
    await expect(supervisor.trackDescendant(managed.pid, 4242)).resolves.toBeUndefined()
    await managed.terminate()
    await supervisor.close()
  }, 30_000)

  test('a helper that dies before publishing the scope fails closed', async () => {
    installWindowsNativeBindingsForTest(bindings({ openJob: 10n, activeProcesses: 0, queryJob: 1, closeHandle: 1, terminateJob: 1, getLastError: 5 }))
    const supervisor = new WindowsProcessSupervisor()
    const managed = await supervisor
      .start({ executable: process.execPath, args: ['-e', '0'], cwd: workdir, env: { NODE_OPTIONS: '--kk-studio-not-a-real-flag' } })
      .catch((error: Error) => error)
    expect(managed).toBeInstanceOf(Error)
    await supervisor.close()
  }, 30_000)
})

describe('windows managed process failure branches (mocked native bindings)', () => {
  function install(control: Control): void {
    installWindowsNativeBindingsForTest(bindings(control))
  }

  const okControl = (): Control => ({ openJob: 10n, activeProcesses: 0, queryJob: 1, closeHandle: 1, terminateJob: 1, getLastError: 5 })

  test('establish rejects an invalid published scope id', async () => {
    install(okControl())
    const dir = await stateDir()
    state.publish(dir, state.SCOPE_FILE, 'not-a-number')
    const managed = new WindowsManagedProcess(dir, fakeHelper(), undefined)
    await expect(managed.establish()).rejects.toThrow(/invalid scope id/)
  })

  test('establish reports a helper that exited without an error file', async () => {
    install(okControl())
    const dir = await stateDir()
    const helper = fakeHelper(50)
    const managed = new WindowsManagedProcess(dir, helper, undefined)
    await new Promise((resolve) => helper.on('close', resolve))
    await expect(managed.establish()).rejects.toThrow(/exited with code 0 before establishing the OS scope/)
  })

  test('establish surfaces the fixed helper failure phase', async () => {
    install(okControl())
    const dir = await stateDir()
    state.publish(dir, state.ERROR_FILE, 'helper-windows-failure')
    const helper = fakeHelper(50)
    const managed = new WindowsManagedProcess(dir, helper, undefined)
    await new Promise((resolve) => helper.on('close', resolve))
    await expect(managed.establish()).rejects.toThrow()
  })

  test('establish cancels before starting the command when the signal aborts after the scope', async () => {
    install(okControl())
    const dir = await stateDir()
    const helper = fakeHelper(400)
    state.publish(dir, state.SCOPE_FILE, String(helper.pid))
    const controller = new AbortController()
    const managed = new WindowsManagedProcess(dir, helper, controller.signal)
    controller.abort()
    await expect(managed.establish()).rejects.toThrow(/cancelled/)
  })

  test('confirmDescendant fails closed when no job is open', () => {
    install(okControl())
    const managed = new WindowsManagedProcess('/nonexistent', fakeHelper(), undefined)
    expect(() => managed.confirmDescendant(1)).toThrow(/job object is not open/)
  })

  test('terminate before the scope is established force-kills the helper', async () => {
    install(okControl())
    const dir = await stateDir()
    const helper = fakeHelper(5_000)
    const managed = new WindowsManagedProcess(dir, helper, undefined)
    await managed.terminate(50)
    expect(helper.killed || helper.exitCode !== null || helper.signalCode !== null).toBe(true)
  })

  test('terminate keeps the state directory when the job cannot be proven converged', async () => {
    const control = okControl()
    control.activeProcesses = 1
    control.terminateJob = 0
    install(control)
    const dir = await stateDir()
    const helper = fakeHelper(400)
    state.publish(dir, state.SCOPE_FILE, String(helper.pid))
    const managed = new WindowsManagedProcess(dir, helper, undefined)
    await managed.establish()
    await expect(managed.terminate(50)).rejects.toThrow(/could not be proven converged/)
    // 收敛失败必须保留状态目录作为唯一可复核的证据。
    expect(existsSync(dir)).toBe(true)
  })

  test('a handle-close failure during natural completion is surfaced on terminate', async () => {
    const control = okControl()
    control.closeHandle = 0
    install(control)
    const dir = await stateDir()
    const helper = fakeHelper(60)
    state.publish(dir, state.SCOPE_FILE, String(helper.pid))
    state.publish(dir, state.CLEANUP_FILE, 'true')
    const managed = new WindowsManagedProcess(dir, helper, undefined)
    await managed.establish()
    // wait for the natural-completion path to run inside the helper 'close' handler.
    await new Promise((resolve) => setTimeout(resolve, 300))
    await expect(managed.terminate()).rejects.toThrow(/close the scope job handle/)
  })

  test('natural completion with cleanup=true settles and releases the state directory', async () => {
    install(okControl())
    const dir = await stateDir()
    const helper = fakeHelper(60)
    state.publish(dir, state.SCOPE_FILE, String(helper.pid))
    state.publish(dir, state.CLEANUP_FILE, 'true')
    const managed = new WindowsManagedProcess(dir, helper, undefined)
    await managed.establish()
    await managed.settled
    expect(existsSync(dir)).toBe(false)
    // 已证明收敛后再次 terminate/close 是幂等的。
    await managed.close()
  })

  test('the exit report is published from the state file before the helper closes', async () => {
    install(okControl())
    const dir = await stateDir()
    const helper = fakeHelper(200)
    state.publish(dir, state.SCOPE_FILE, String(helper.pid))
    state.publish(dir, state.EXIT_FILE, JSON.stringify({ exitCode: 9, signal: null }))
    const managed = new WindowsManagedProcess(dir, helper, undefined)
    await managed.establish()
    await expect(managed.exit).resolves.toEqual({ exitCode: 9, signal: null })
    await managed.terminate(50)
  })
})
