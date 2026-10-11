import { afterEach, describe, expect, test } from 'vitest'
import {
  createSuspendedJob,
  installWindowsNativeBindingsForTest,
  openJob,
  type Kernel32,
  type WindowsNativeBindings,
} from '../../src/native/win32.js'

const WAIT_OBJECT_0 = 0
const WAIT_TIMEOUT = 0x0000_0102
const WAIT_FAILED = 0xffff_ffff
const PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x0002_0002

/**
 * 用可配置的假原生绑定驱动真实 `win32.ts` 的 Job 建立/收敛分支。
 *
 * 这些分支只有在 Windows 上才会自然发生（CreateProcessW 失败、Job 语义句柄缺失、DACL/句柄关闭失败等），
 * 但都属于「失败必须如实上报、绝不假装收敛」的安全性质。假绑定只提供调用序列，不断言任何真实 Windows 行为。
 */
interface Control {
  createJob: bigint | null
  setJobInfo: number
  initAttrSecond: number
  updateHandle: number
  updateJob: number
  createProcess: number
  processFields: boolean
  /** 捕获 CreateProcessW 收到的 Unicode 环境块，用于断言被丢弃的条目确实没有进入。 */
  capturedEnv: Buffer | null
  resumeThread: number
  wait: () => number
  getExit: number
  exitCode: number
  terminateProcess: number
  closeHandle: number
  queryJob: number
  activeProcesses: number
  openJob: bigint | null
  openProcess: bigint | null
  isProcessInJob: number
  isProcessInJobResult: number
  getStdHandle: bigint | null
  setHandleInfo: number
  getFileAttributes: number
  getLastError: number
}

function harness(): { control: Control; bindings: WindowsNativeBindings } {
  const control: Control = {
    createJob: 10n,
    setJobInfo: 1,
    initAttrSecond: 1,
    updateHandle: 1,
    updateJob: 1,
    createProcess: 1,
    processFields: true,
    capturedEnv: null,
    resumeThread: 1,
    wait: () => WAIT_OBJECT_0,
    getExit: 1,
    exitCode: 7,
    terminateProcess: 1,
    closeHandle: 1,
    queryJob: 1,
    activeProcesses: 0,
    openJob: 10n,
    openProcess: 3n,
    isProcessInJob: 1,
    isProcessInJobResult: 1,
    getStdHandle: 5n,
    setHandleInfo: 1,
    getFileAttributes: 0x80,
    getLastError: 5,
  }
  let initAttrCalls = 0
  const kernel = {
    CreateJobObjectW: () => control.createJob,
    SetInformationJobObject: () => control.setJobInfo,
    CreateProcessW: (_a: unknown, _c: unknown, _p: unknown, _t: unknown, _i: unknown, _f: unknown, environment: unknown, _d: unknown, _s: unknown, info: Record<string, unknown>) => {
      control.capturedEnv = (environment as Buffer | null) ?? null
      if (control.processFields) {
        info.hProcess = 1n
        info.hThread = 2n
        info.dwProcessId = 4242
      }
      return control.createProcess
    },
    ResumeThread: () => control.resumeThread,
    WaitForSingleObject: () => control.wait(),
    GetExitCodeProcess: (_p: unknown, exitCode: number[]) => {
      exitCode[0] = control.exitCode
      return control.getExit
    },
    TerminateJobObject: () => 1,
    QueryInformationJobObject: (_j: unknown, _c: unknown, buffer: Record<string, number>) => {
      buffer.ActiveProcesses = control.activeProcesses
      return control.queryJob
    },
    OpenJobObjectW: () => control.openJob,
    IsProcessInJob: (_p: unknown, _j: unknown, result: number[]) => {
      result[0] = control.isProcessInJobResult
      return control.isProcessInJob
    },
    OpenProcess: () => control.openProcess,
    CloseHandle: () => control.closeHandle,
    TerminateProcess: () => control.terminateProcess,
    SetHandleInformation: () => control.setHandleInfo,
    GetStdHandle: () => control.getStdHandle,
    GetCurrentProcess: () => 9n,
    InitializeProcThreadAttributeList: (_list: unknown, _count: unknown, _flags: unknown, size: { value: number }) => {
      size.value = 112
      initAttrCalls += 1
      return initAttrCalls === 1 ? 0 : control.initAttrSecond
    },
    UpdateProcThreadAttribute: (_list: unknown, _flags: unknown, attribute: number) =>
      attribute === PROC_THREAD_ATTRIBUTE_HANDLE_LIST ? control.updateHandle : control.updateJob,
    DeleteProcThreadAttributeList: () => undefined,
    GetLastError: () => control.getLastError,
    GetFileAttributesW: () => control.getFileAttributes,
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
  return { control, bindings: { kernel, advapi, types } as unknown as WindowsNativeBindings }
}

const LAUNCH = { cwd: '/tmp', args: ['fake-command'] } as const

afterEach(() => {
  installWindowsNativeBindingsForTest(null)
})

describe('windows job establishment fail-closed branches (mocked native bindings)', () => {
  test('a missing job object is reported with the call status', async () => {
    const { control, bindings } = harness()
    control.createJob = null
    installWindowsNativeBindingsForTest(bindings)
    await expect(createSuspendedJob('job', LAUNCH, {})).rejects.toThrow(/cannot create the scope job object: 5/)
  })

  test('a lifetime-limit failure closes the job and surfaces the status', async () => {
    const { control, bindings } = harness()
    control.setJobInfo = 0
    installWindowsNativeBindingsForTest(bindings)
    await expect(createSuspendedJob('job', LAUNCH, {})).rejects.toThrow(/cannot limit the scope job object lifetime: 5/)
  })

  test('an empty command is rejected before any process is created', async () => {
    const { bindings } = harness()
    installWindowsNativeBindingsForTest(bindings)
    await expect(createSuspendedJob('job', { cwd: '/tmp', args: [] }, {})).rejects.toThrow(/must not be empty/)
  })

  test('a failed attribute-list sizing is reported', async () => {
    const { control, bindings } = harness()
    control.initAttrSecond = 0
    installWindowsNativeBindingsForTest(bindings)
    await expect(createSuspendedJob('job', LAUNCH, {})).rejects.toThrow(/cannot initialize the command process attribute list: 5/)
  })

  test('a failed handle-list restriction is reported', async () => {
    const { control, bindings } = harness()
    control.updateHandle = 0
    installWindowsNativeBindingsForTest(bindings)
    await expect(createSuspendedJob('job', LAUNCH, {})).rejects.toThrow(/cannot restrict the command process handle list: 5/)
  })

  test('a failed job-list attachment is reported', async () => {
    const { control, bindings } = harness()
    control.updateJob = 0
    installWindowsNativeBindingsForTest(bindings)
    await expect(createSuspendedJob('job', LAUNCH, {})).rejects.toThrow(/cannot attach the command process to the scope job: 5/)
  })

  test('a failed CreateProcessW is reported', async () => {
    const { control, bindings } = harness()
    control.createProcess = 0
    installWindowsNativeBindingsForTest(bindings)
    await expect(createSuspendedJob('job', LAUNCH, {})).rejects.toThrow(/cannot create the command process: 5/)
  })

  test('a process created without handles is rejected', async () => {
    const { control, bindings } = harness()
    control.processFields = false
    installWindowsNativeBindingsForTest(bindings)
    await expect(createSuspendedJob('job', LAUNCH, {})).rejects.toThrow(/without a process handle/)
  })

  test('a missing standard handle is reported', async () => {
    const { control, bindings } = harness()
    control.getStdHandle = null
    installWindowsNativeBindingsForTest(bindings)
    await expect(createSuspendedJob('job', LAUNCH, {})).rejects.toThrow(/has no standard handle/)
  })

  test('a standard handle that cannot be made inheritable is reported', async () => {
    const { control, bindings } = harness()
    control.setHandleInfo = 0
    installWindowsNativeBindingsForTest(bindings)
    await expect(createSuspendedJob('job', LAUNCH, {})).rejects.toThrow(/cannot make the standard handle -10 inheritable: 5/)
  })

  test('the environment block drops undefined, NUL and malformed entries', async () => {
    const { control, bindings } = harness()
    installWindowsNativeBindingsForTest(bindings)
    const scope = await createSuspendedJob('job', LAUNCH, { GOOD: 'ok', 'A=B': 'x', BAD: 'a\0b', EMPTY: undefined })
    const environment = control.capturedEnv?.toString('utf16le') ?? ''
    // 非法条目（含 '=' 的键、含 NUL 的值、undefined 值）绝不进入环境块。
    expect(environment).not.toContain('A=B')
    expect(environment).not.toContain('BAD')
    expect(environment).not.toContain('EMPTY')
    // 合法条目必须保留。
    expect(environment).toContain('GOOD=ok')
    scope.close()
  })
})

describe('windows job runtime fail-closed branches (mocked native bindings)', () => {
  test('closing a never-resumed scope terminates the suspended process and aggregates failures', async () => {
    const { control, bindings } = harness()
    control.terminateProcess = 0
    installWindowsNativeBindingsForTest(bindings)
    const scope = await createSuspendedJob('job', LAUNCH, {})
    expect(() => scope.close()).toThrow(/terminate the suspended command process/)
  })

  test('resuming without a suspended thread is rejected', async () => {
    const { bindings } = harness()
    installWindowsNativeBindingsForTest(bindings)
    const scope = await openJob('job')
    expect(scope.processId).toBe(-1)
    expect(() => scope.resume()).toThrow(/no suspended command process/)
    scope.close()
  })

  test('a resume failure is reported with the call status', async () => {
    const { control, bindings } = harness()
    installWindowsNativeBindingsForTest(bindings)
    const scope = await createSuspendedJob('job', LAUNCH, {})
    control.resumeThread = 0xffff_ffff
    expect(() => scope.resume()).toThrow(/cannot resume the command process: 5/)
    scope.close()
  })

  test('awaitExit without a process is rejected', async () => {
    const { bindings } = harness()
    installWindowsNativeBindingsForTest(bindings)
    const scope = await openJob('job')
    await expect(scope.awaitExit()).rejects.toThrow(/no command process to wait for/)
    scope.close()
  })

  test('a WaitForSingleObject failure is surfaced with errno', async () => {
    const { control, bindings } = harness()
    installWindowsNativeBindingsForTest(bindings)
    const scope = await createSuspendedJob('job', LAUNCH, {})
    control.wait = () => WAIT_FAILED
    await expect(scope.awaitExit()).rejects.toThrow(/cannot wait for the command process: 5/)
    scope.close()
  })

  test('a non-timeout, non-ready wait status is surfaced verbatim', async () => {
    const { control, bindings } = harness()
    installWindowsNativeBindingsForTest(bindings)
    const scope = await createSuspendedJob('job', LAUNCH, {})
    control.wait = () => 0x1234
    await expect(scope.awaitExit()).rejects.toThrow(/cannot wait for the command process: 4660/)
    scope.close()
  })

  test('awaitExit polls until the process signals readiness', async () => {
    const { control, bindings } = harness()
    installWindowsNativeBindingsForTest(bindings)
    const scope = await createSuspendedJob('job', LAUNCH, {})
    control.exitCode = 3
    let calls = 0
    // 前两次超时、第三次就绪：验证轮询而非阻塞等待。
    control.wait = () => (calls++ < 2 ? WAIT_TIMEOUT : WAIT_OBJECT_0)
    await expect(scope.awaitExit()).resolves.toEqual({ exitCode: 3, signal: null })
    scope.close()
  })

  test('a failed exit-code read is reported', async () => {
    const { control, bindings } = harness()
    installWindowsNativeBindingsForTest(bindings)
    const scope = await createSuspendedJob('job', LAUNCH, {})
    control.getExit = 0
    await expect(scope.awaitExit()).rejects.toThrow(/cannot read the command exit code: 5/)
    scope.close()
  })

  test('awaitEmpty returns false when the accounting cannot be read', async () => {
    const { control, bindings } = harness()
    installWindowsNativeBindingsForTest(bindings)
    const scope = await createSuspendedJob('job', LAUNCH, {})
    control.queryJob = 0
    await expect(scope.awaitEmpty(10)).resolves.toBe(false)
    scope.close()
  })

  test('confirmDescendant rejects a pid whose process handle cannot be opened', async () => {
    const { control, bindings } = harness()
    installWindowsNativeBindingsForTest(bindings)
    const scope = await createSuspendedJob('job', LAUNCH, {})
    control.openProcess = null
    expect(() => scope.confirmDescendant(4242)).toThrow(/cannot open the descendant process 4242: 5/)
    scope.close()
  })

  test('confirmDescendant reports a failed membership query', async () => {
    const { control, bindings } = harness()
    installWindowsNativeBindingsForTest(bindings)
    const scope = await createSuspendedJob('job', LAUNCH, {})
    control.isProcessInJob = 0
    expect(() => scope.confirmDescendant(4242)).toThrow(/cannot query the scope job membership/)
    scope.close()
  })

  test('closing without loaded bindings fails closed instead of silently skipping', async () => {
    const { bindings } = harness()
    installWindowsNativeBindingsForTest(bindings)
    const scope = await createSuspendedJob('job', LAUNCH, {})
    installWindowsNativeBindingsForTest(null)
    expect(() => scope.close()).toThrow(/kernel32 bindings are not loaded yet/)
  })
})

describe('windows job open retry (mocked native bindings)', () => {
  test('openJob retries then fails closed with the call status', async () => {
    const { control, bindings } = harness()
    control.openJob = null
    installWindowsNativeBindingsForTest(bindings)
    await expect(openJob('missing-job')).rejects.toThrow(/cannot open the scope job object missing-job: 5/)
  })
})

describe('windows types lazy loading (non-windows host)', () => {
  test('types() builds the real Koffi structs before kernel32 becomes unavailable', async () => {
    if (process.platform === 'win32') {
      return
    }
    installWindowsNativeBindingsForTest(null)
    // 非 Windows 主机没有 kernel32.dll：必须在加载原生库时失败关闭，而不是静默继续。
    await expect(createSuspendedJob('kk-studio-types-probe', LAUNCH, {})).rejects.toThrow()
  })
})
