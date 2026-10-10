import { createHash } from 'node:crypto'
import type { ExitReport, LaunchSpec } from './state.js'
import { joinCommandLine } from './windows-command-line.js'

/**
 * Windows 原生执行范围、内核锁与 owner-only ACL。
 *
 * <p>执行范围是带 `KILL_ON_JOB_CLOSE` 的命名 Job：helper 侧先建立 Job，再用 `CREATE_SUSPENDED` 与
 * `PROC_THREAD_ATTRIBUTE_JOB_LIST` 创建首个进程，此时进程由内核在创建时直接进入 Job，不存在「已经创建但尚未归属」的间隙。
 * 收敛的唯一证据是 `QueryInformationJobObject(JobObjectBasicAccountingInformation).ActiveProcesses == 0`：首个进程退出
 * （哪怕它是唯一进程）都不等于整组已经结束。父进程只有拿到这个证据之后才关闭自己的 Job 句柄。
 *
 * <p>命令的句柄集合显式列举：`STARTUPINFOEX` + `PROC_THREAD_ATTRIBUTE_HANDLE_LIST` 只把命令的 stdin/stdout/stderr
 * 交给它，helper 里其它可继承句柄不会漏进用户命令。
 *
 * <p>权限与锁同样走内核：私有路径用显式 DACL（只有属主一个 allow ACE，目录额外带可继承标志），目录锁用 `LockFileEx`
 * （独占、立即失败），二者都由内核在句柄关闭或进程崩溃时释放。
 *
 * <p>本模块只在 Windows 上被调用；所有原生库与结构体都是惰性加载，非 Windows 平台引入本模块不会触碰 kernel32/advapi32。
 */

/** 目录锁句柄。 */
export interface WindowsDirectoryLock {
  close(): Promise<void>
}

/** helper 与父进程共用的 Job 范围对象。 */
export interface WindowsJobScope {
  readonly processId: number
  resume(): void
  awaitExit(): Promise<ExitReport>
  awaitEmpty(budgetMillis: number): Promise<boolean>
  terminateAndDrain(budgetMillis: number): Promise<boolean>
  assignProcess(childPid: number): void
  close(): void
}

const CREATE_SUSPENDED = 0x0000_0004
const CREATE_UNICODE_ENVIRONMENT = 0x0000_0400
const STARTF_USESTDHANDLES = 0x0000_0100
const EXTENDED_STARTUPINFO_PRESENT = 0x0008_0000
const HANDLE_FLAG_INHERIT = 0x0000_0001
const STD_INPUT_HANDLE = -10
const STD_OUTPUT_HANDLE = -11
const STD_ERROR_HANDLE = -12
const INFINITE = 0xFFFF_FFFF
const WAIT_OBJECT_0 = 0
const JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x0000_2000
const JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION_CLASS = 1
const JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9
const JOB_OBJECT_ALL_ACCESS = 0x001F_001F
const PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x0002_0002
const PROC_THREAD_ATTRIBUTE_JOB_LIST = 0x0002_000D
const SE_FILE_OBJECT = 1
const OWNER_SECURITY_INFORMATION = 0x0000_0001
const DACL_SECURITY_INFORMATION = 0x0000_0004
const ACL_REVISION = 2
const ACCESS_ALLOWED_ACE_TYPE = 0
const FILE_ALL_ACCESS = 0x001F_01FF
const OBJECT_INHERIT_ACE = 0x01
const CONTAINER_INHERIT_ACE = 0x02
const GENERIC_READ = 0x8000_0000
const GENERIC_WRITE = 0x4000_0000
const FILE_SHARE_READ = 0x0000_0001
const FILE_SHARE_WRITE = 0x0000_0002
const OPEN_ALWAYS = 4
const FILE_ATTRIBUTE_NORMAL = 0x0000_0080
const LOCKFILE_FAIL_IMMEDIATELY = 0x0000_0001
const LOCKFILE_EXCLUSIVE_LOCK = 0x0000_0002
const PROCESS_SET_QUOTA = 0x0100
const PROCESS_TERMINATE = 0x0001
const INVALID_HANDLE = 0xFFFF_FFFF_FFFF_FFFFn
const LOCK_FILE_NAME = 'daemon.lock'
const JOB_NAME_PREFIX = 'kk-studio-process-scope-'

type Pointer = bigint
type Koffi = typeof import('koffi')

interface StructType {
  size: number
}

interface Kernel32 {
  CreateJobObjectW(attributes: Pointer | null, name: string): Pointer | null
  SetInformationJobObject(job: Pointer, infoClass: number, info: unknown, length: number): number
  CreateProcessW(
    applicationName: string | null,
    commandLine: string,
    processAttributes: Pointer | null,
    threadAttributes: Pointer | null,
    inheritHandles: number,
    creationFlags: number,
    environment: Buffer | null,
    currentDirectory: string | null,
    startupInfo: unknown,
    processInformation: Record<string, unknown>,
  ): number
  ResumeThread(thread: Pointer): number
  WaitForSingleObject(handle: Pointer, millis: number): number
  GetExitCodeProcess(process: Pointer, exitCode: number[]): number
  TerminateJobObject(job: Pointer, exitCode: number): number
  QueryInformationJobObject(job: Pointer, infoClass: number, buffer: Record<string, number>, length: number, returned: number[]): number
  OpenJobObjectW(access: number, inherit: number, name: string): Pointer | null
  AssignProcessToJobObject(job: Pointer, process: Pointer): number
  OpenProcess(access: number, inherit: number, pid: number): Pointer | null
  CloseHandle(handle: Pointer): number
  TerminateProcess(process: Pointer, exitCode: number): number
  SetHandleInformation(handle: Pointer, mask: number, flags: number): number
  GetStdHandle(stdHandle: number): Pointer | null
  GetCurrentProcess(): Pointer
  InitializeProcThreadAttributeList(attributeList: Buffer | null, count: number, flags: number, size: SizeBox): number
  UpdateProcThreadAttribute(attributeList: Buffer, flags: number, attribute: number, value: Buffer, size: number, previous: null, returnSize: null): number
  DeleteProcThreadAttributeList(attributeList: Buffer): void
  GetLastError(): number
  CreateFileW(fileName: string, desiredAccess: number, shareMode: number, securityAttributes: Pointer | null, creationDisposition: number, flagsAndAttributes: number, template: Pointer | null): Pointer | null
  LockFileEx(file: Pointer, flags: number, reserved: number, low: number, high: number, overlapped: Record<string, unknown>): number
  UnlockFileEx(file: Pointer, reserved: number, low: number, high: number, overlapped: Record<string, unknown>): number
}

interface Advapi32 {
  GetNamedSecurityInfoW(name: string, objectType: number, securityInfo: number, owner: Pointer[], group: Pointer[], dacl: Pointer[], sacl: Pointer[], descriptor: Pointer[]): number
  SetNamedSecurityInfoW(name: string, objectType: number, securityInfo: number, owner: Pointer | null, group: Pointer | null, dacl: Pointer | null, sacl: Pointer | null): number
  InitializeAcl(acl: Pointer, length: number, revision: number): number
  AddAccessAllowedAceEx(acl: Pointer, revision: number, flags: number, mask: number, sid: Pointer): number
  GetAce(acl: Pointer, index: number, ace: Pointer[]): number
  GetLengthSid(sid: Pointer): number
  EqualSid(first: Pointer, second: Pointer): number
  LocalFree(memory: Pointer): Pointer | null
}

interface WindowsTypes {
  startupInfoEx: StructType
  jobExtendedLimit: StructType
  jobBasicAccounting: StructType
  sizeBox: StructType
  acl: StructType
  aceHeader: StructType
}

/** 单个 `size_t` 的盒子：把 `size_t *` 出参交给 Koffi 的 struct 编组读取。 */
interface SizeBox {
  value: number | bigint
}

let kernelCache: Kernel32 | null = null
let advapiCache: Advapi32 | null = null
let typesCache: WindowsTypes | null = null

async function koffiModule(): Promise<Koffi> {
  return import('koffi')
}

async function types(): Promise<WindowsTypes> {
  if (typesCache === null) {
    const koffi = await koffiModule()
    const startupInfo = koffi.struct('STARTUPINFOW', {
      cb: 'uint32_t',
      lpReserved: 'void *',
      lpDesktop: 'void *',
      lpTitle: 'void *',
      dwX: 'uint32_t',
      dwY: 'uint32_t',
      dwXSize: 'uint32_t',
      dwYSize: 'uint32_t',
      dwXCountChars: 'uint32_t',
      dwYCountChars: 'uint32_t',
      dwFillAttribute: 'uint32_t',
      dwFlags: 'uint32_t',
      wShowWindow: 'uint16_t',
      cbReserved2: 'uint16_t',
      lpReserved2: 'void *',
      hStdInput: 'void *',
      hStdOutput: 'void *',
      hStdError: 'void *',
    })
    const startupInfoEx = koffi.struct('STARTUPINFOEXW', {
      StartupInfo: startupInfo,
      lpAttributeList: 'void *',
    })
    koffi.struct('PROCESS_INFORMATION', {
      hProcess: 'void *',
      hThread: 'void *',
      dwProcessId: 'uint32_t',
      dwThreadId: 'uint32_t',
    })
    const jobBasicLimit = koffi.struct('JOBOBJECT_BASIC_LIMIT_INFORMATION', {
      PerProcessUserTimeLimit: 'int64_t',
      PerJobUserTimeLimit: 'int64_t',
      LimitFlags: 'uint32_t',
      MinimumWorkingSetSize: 'size_t',
      MaximumWorkingSetSize: 'size_t',
      ActiveProcessLimit: 'uint32_t',
      Affinity: 'size_t',
      PriorityClass: 'uint32_t',
      SchedulingClass: 'uint32_t',
    })
    const ioCounters = koffi.struct('IO_COUNTERS', {
      ReadOperationCount: 'uint64_t',
      WriteOperationCount: 'uint64_t',
      OtherOperationCount: 'uint64_t',
      ReadTransferCount: 'uint64_t',
      WriteTransferCount: 'uint64_t',
      OtherTransferCount: 'uint64_t',
    })
    const jobExtendedLimit = koffi.struct('JOBOBJECT_EXTENDED_LIMIT_INFORMATION', {
      BasicLimitInformation: jobBasicLimit,
      IoInfo: ioCounters,
      ProcessMemoryLimit: 'size_t',
      JobMemoryLimit: 'size_t',
      PeakProcessMemoryUsed: 'size_t',
      PeakJobMemoryUsed: 'size_t',
    })
    const jobBasicAccounting = koffi.struct('JOBOBJECT_BASIC_ACCOUNTING_INFORMATION', {
      TotalUserTime: 'int64_t',
      TotalKernelTime: 'int64_t',
      ThisPeriodTotalUserTime: 'int64_t',
      ThisPeriodTotalKernelTime: 'int64_t',
      TotalPageFaultCount: 'uint32_t',
      TotalProcesses: 'uint32_t',
      ActiveProcesses: 'uint32_t',
      TotalTerminatedProcesses: 'uint32_t',
    })
    koffi.struct('OVERLAPPED', {
      Internal: 'size_t',
      InternalHigh: 'size_t',
      Offset: 'uint32_t',
      OffsetHigh: 'uint32_t',
      hEvent: 'void *',
    })
    const acl = koffi.struct('ACL', {
      AclRevision: 'uint8_t',
      Sbz1: 'uint8_t',
      AclSize: 'uint16_t',
      AceCount: 'uint16_t',
      Sbz2: 'uint16_t',
    })
    const aceHeader = koffi.struct('ACE_HEADER', {
      AceType: 'uint8_t',
      AceFlags: 'uint8_t',
      AceSize: 'uint16_t',
    })
    const sizeBox = koffi.struct('SIZE_T_BOX', { value: 'size_t' })
    typesCache = { startupInfoEx, jobExtendedLimit, jobBasicAccounting, sizeBox, acl, aceHeader }
  }
  return typesCache
}

async function kernel(): Promise<Kernel32> {
  if (kernelCache === null) {
    await types()
    const koffi = await koffiModule()
    const library = koffi.load('kernel32.dll')
    kernelCache = {
      CreateJobObjectW: library.func('void *CreateJobObjectW(void *attributes, const char16_t *name)'),
      SetInformationJobObject: library.func('int SetInformationJobObject(void *job, int infoClass, const JOBOBJECT_EXTENDED_LIMIT_INFORMATION *info, unsigned int length)'),
      CreateProcessW: library.func(
        'int CreateProcessW(const char16_t *applicationName, char16_t *commandLine, void *processAttributes, void *threadAttributes, int inheritHandles, unsigned int creationFlags, void *environment, const char16_t *currentDirectory, const STARTUPINFOEXW *startupInfo, _Out_ PROCESS_INFORMATION *processInformation)',
      ),
      ResumeThread: library.func('unsigned int ResumeThread(void *thread)'),
      WaitForSingleObject: library.func('unsigned int WaitForSingleObject(void *handle, unsigned int millis)'),
      GetExitCodeProcess: library.func('int GetExitCodeProcess(void *process, _Out_ unsigned int *exitCode)'),
      TerminateJobObject: library.func('int TerminateJobObject(void *job, unsigned int exitCode)'),
      QueryInformationJobObject: library.func('int QueryInformationJobObject(void *job, int infoClass, _Out_ JOBOBJECT_BASIC_ACCOUNTING_INFORMATION *buffer, unsigned int length, _Out_ unsigned int *returned)'),
      OpenJobObjectW: library.func('void *OpenJobObjectW(unsigned int access, int inherit, const char16_t *name)'),
      AssignProcessToJobObject: library.func('int AssignProcessToJobObject(void *job, void *process)'),
      OpenProcess: library.func('void *OpenProcess(unsigned int access, int inherit, unsigned int pid)'),
      CloseHandle: library.func('int CloseHandle(void *handle)'),
      TerminateProcess: library.func('int TerminateProcess(void *process, unsigned int exitCode)'),
      SetHandleInformation: library.func('int SetHandleInformation(void *handle, unsigned int mask, unsigned int flags)'),
      GetStdHandle: library.func('void *GetStdHandle(int stdHandle)'),
      GetCurrentProcess: library.func('void *GetCurrentProcess()'),
      InitializeProcThreadAttributeList: library.func('int InitializeProcThreadAttributeList(void *attributeList, unsigned int count, unsigned int flags, _Inout_ SIZE_T_BOX *size)'),
      UpdateProcThreadAttribute: library.func('int UpdateProcThreadAttribute(void *attributeList, unsigned int flags, size_t attribute, void *value, size_t size, void *previous, void *returnSize)'),
      DeleteProcThreadAttributeList: library.func('void DeleteProcThreadAttributeList(void *attributeList)'),
      GetLastError: library.func('unsigned int GetLastError()'),
      CreateFileW: library.func('void *CreateFileW(const char16_t *fileName, unsigned int desiredAccess, unsigned int shareMode, void *securityAttributes, unsigned int creationDisposition, unsigned int flagsAndAttributes, void *template)'),
      LockFileEx: library.func('int LockFileEx(void *file, unsigned int flags, unsigned int reserved, unsigned int low, unsigned int high, OVERLAPPED *overlapped)'),
      UnlockFileEx: library.func('int UnlockFileEx(void *file, unsigned int reserved, unsigned int low, unsigned int high, OVERLAPPED *overlapped)'),
    }
  }
  return kernelCache
}

async function advapi(): Promise<Advapi32> {
  if (advapiCache === null) {
    const koffi = await koffiModule()
    const library = koffi.load('advapi32.dll')
    advapiCache = {
      GetNamedSecurityInfoW: library.func('unsigned int GetNamedSecurityInfoW(const char16_t *name, int objectType, unsigned int securityInfo, _Out_ void **owner, _Out_ void **group, _Out_ void **dacl, _Out_ void **sacl, _Out_ void **descriptor)'),
      SetNamedSecurityInfoW: library.func('unsigned int SetNamedSecurityInfoW(char16_t *name, int objectType, unsigned int securityInfo, void *owner, void *group, void *dacl, void *sacl)'),
      InitializeAcl: library.func('int InitializeAcl(void *acl, unsigned int length, unsigned int revision)'),
      AddAccessAllowedAceEx: library.func('int AddAccessAllowedAceEx(void *acl, unsigned int revision, unsigned int flags, unsigned int mask, void *sid)'),
      GetAce: library.func('int GetAce(void *acl, unsigned int index, _Out_ void **ace)'),
      GetLengthSid: library.func('unsigned int GetLengthSid(void *sid)'),
      EqualSid: library.func('int EqualSid(void *first, void *second)'),
      LocalFree: library.func('void *LocalFree(void *memory)'),
    }
  }
  return advapiCache
}

/** 从调用私有状态目录派生 Job 名：父进程与 helper 对同一个目录得到同一个名字。 */
export function jobNameFor(stateDir: string): string {
  return JOB_NAME_PREFIX + createHash('sha256').update(stateDir).digest('hex').slice(0, 24)
}

/**
 * helper 侧：建立命名 Job 并以挂起状态创建首个进程，归属成功后才返回。
 *
 * <p>归属必须早于范围发布：父进程一旦读到范围，就可以认定「要么没有任何命令进程，要么它已经在 Job 内」。
 */
export async function createSuspendedJob(jobName: string, spec: LaunchSpec, env: NodeJS.ProcessEnv): Promise<WindowsJobScope> {
  const kernel32 = await kernel()
  const job = kernel32.CreateJobObjectW(null, jobName)
  if (job === null) {
    throw new Error(`cannot create the scope job object: ${kernel32.GetLastError()}`)
  }
  const scope = new JobScope(job, true, -1)
  try {
    await scope.limitJobLifetimeToHandle()
    await scope.createSuspendedProcess(spec, env)
    return scope
  } catch (error) {
    scope.close()
    throw error
  }
}

/** 父进程侧：打开 helper 建立的命名 Job，从而在 helper 之外独立拥有「终止并查询整组」的能力。 */
export async function openJob(jobName: string): Promise<WindowsJobScope> {
  const kernel32 = await kernel()
  const deadline = Date.now() + 500
  while (true) {
    const job = kernel32.OpenJobObjectW(JOB_OBJECT_ALL_ACCESS, 0, jobName)
    if (job !== null) {
      return new JobScope(job, false, -1)
    }
    if (Date.now() >= deadline) {
      throw new Error(`cannot open the scope job object ${jobName}: ${kernel32.GetLastError()}`)
    }
    await delay(10)
  }
}

class JobScope implements WindowsJobScope {
  private processIdValue: number
  private thread: Pointer | null = null
  private process: Pointer | null = null
  private resumed = false
  private closed = false
  private readonly job: Pointer

  constructor(job: Pointer, private readonly owner: boolean, processId: number) {
    this.job = job
    this.processIdValue = processId
  }

  get processId(): number {
    return this.processIdValue
  }

  async limitJobLifetimeToHandle(): Promise<void> {
    const kernel32 = await kernel()
    const windowsTypes = await types()
    const limits = {
      BasicLimitInformation: {
        PerProcessUserTimeLimit: 0n,
        PerJobUserTimeLimit: 0n,
        LimitFlags: JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
        MinimumWorkingSetSize: 0n,
        MaximumWorkingSetSize: 0n,
        ActiveProcessLimit: 0,
        Affinity: 0n,
        PriorityClass: 0,
        SchedulingClass: 0,
      },
      IoInfo: {
        ReadOperationCount: 0n,
        WriteOperationCount: 0n,
        OtherOperationCount: 0n,
        ReadTransferCount: 0n,
        WriteTransferCount: 0n,
        OtherTransferCount: 0n,
      },
      ProcessMemoryLimit: 0n,
      JobMemoryLimit: 0n,
      PeakProcessMemoryUsed: 0n,
      PeakJobMemoryUsed: 0n,
    }
    if (kernel32.SetInformationJobObject(this.job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS, limits, windowsTypes.jobExtendedLimit.size) === 0) {
      throw new Error(`cannot limit the scope job object lifetime: ${kernel32.GetLastError()}`)
    }
  }

  async createSuspendedProcess(spec: LaunchSpec, env: NodeJS.ProcessEnv): Promise<void> {
    if (spec.args.length === 0) {
      throw new Error('command must not be empty')
    }
    const kernel32 = await kernel()
    const koffi = await koffiModule()
    const windowsTypes = await types()
    const standardInput = inheritStandardHandle(kernel32, STD_INPUT_HANDLE)
    const standardOutput = inheritStandardHandle(kernel32, STD_OUTPUT_HANDLE)
    const standardError = inheritStandardHandle(kernel32, STD_ERROR_HANDLE)
    const inherited = distinctHandles([standardInput, standardOutput, standardError])

    // 先问所需字节数，再按该尺寸分配属性列表；出参用单 size_t 盒子编组读取。
    const sizeBox: SizeBox = { value: 0 }
    kernel32.InitializeProcThreadAttributeList(null, 2, 0, sizeBox)
    const attributeList = Buffer.alloc(Number(sizeBox.value))
    if (kernel32.InitializeProcThreadAttributeList(attributeList, 2, 0, sizeBox) === 0) {
      throw new Error(`cannot initialize the command process attribute list: ${kernel32.GetLastError()}`)
    }
    try {
      // 只把命令的三条标准句柄交给它；句柄列表是显式列举，helper 里其它可继承句柄不会漏进用户命令。
      const handleList = Buffer.alloc(inherited.length * 8)
      inherited.forEach((handle, index) => handleList.writeBigUInt64LE(handle, index * 8))
      if (kernel32.UpdateProcThreadAttribute(attributeList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, handleList, handleList.length, null, null) === 0) {
        throw new Error(`cannot restrict the command process handle list: ${kernel32.GetLastError()}`)
      }
      // 进程由内核在创建时直接进入 Job：不存在「创建完成但尚未归属」的窗口。
      const jobList = Buffer.alloc(8)
      jobList.writeBigUInt64LE(this.job, 0)
      if (kernel32.UpdateProcThreadAttribute(attributeList, 0, PROC_THREAD_ATTRIBUTE_JOB_LIST, jobList, jobList.length, null, null) === 0) {
        throw new Error(`cannot attach the command process to the scope job: ${kernel32.GetLastError()}`)
      }

      const startupInfo = {
        StartupInfo: {
          cb: windowsTypes.startupInfoEx.size,
          lpReserved: null,
          lpDesktop: null,
          lpTitle: null,
          dwX: 0,
          dwY: 0,
          dwXSize: 0,
          dwYSize: 0,
          dwXCountChars: 0,
          dwYCountChars: 0,
          dwFillAttribute: 0,
          dwFlags: STARTF_USESTDHANDLES,
          wShowWindow: 0,
          cbReserved2: 0,
          lpReserved2: null,
          hStdInput: standardInput,
          hStdOutput: standardOutput,
          hStdError: standardError,
        },
        lpAttributeList: koffi.as(attributeList, 'void *'),
      }
      const information: Record<string, unknown> = {}
      const created = kernel32.CreateProcessW(
        spec.args[0] ?? null,
        joinCommandLine(spec.args),
        null,
        null,
        1,
        CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT | EXTENDED_STARTUPINFO_PRESENT,
        createEnvironmentBlock(env),
        spec.cwd,
        startupInfo,
        information,
      )
      if (created === 0) {
        throw new Error(`cannot create the command process: ${kernel32.GetLastError()}`)
      }
      const process = information.hProcess as Pointer | null | undefined
      const thread = information.hThread as Pointer | null | undefined
      const processId = information.dwProcessId as number | undefined
      if (process == null || thread == null || processId === undefined) {
        throw new Error('the command process was created without a process handle')
      }
      this.process = process
      this.thread = thread
      this.processIdValue = processId
    } finally {
      kernel32.DeleteProcThreadAttributeList(attributeList)
    }
  }

  resume(): void {
    if (this.thread === null) {
      throw new Error('the scope job object has no suspended command process')
    }
    if (loadedKernel().ResumeThread(this.thread) === 0xFFFF_FFFF) {
      throw new Error(`cannot resume the command process: ${loadedKernel().GetLastError()}`)
    }
    this.resumed = true
  }

  async awaitExit(): Promise<ExitReport> {
    if (this.process === null) {
      throw new Error('the scope job object has no command process to wait for')
    }
    const kernel32 = await kernel()
    if (kernel32.WaitForSingleObject(this.process, INFINITE) !== WAIT_OBJECT_0) {
      throw new Error(`cannot wait for the command process: ${kernel32.GetLastError()}`)
    }
    const exitCode = [0]
    if (kernel32.GetExitCodeProcess(this.process, exitCode) === 0) {
      throw new Error(`cannot read the command exit code: ${kernel32.GetLastError()}`)
    }
    return { exitCode: exitCode[0] ?? null, signal: null }
  }

  async terminateAndDrain(budgetMillis: number): Promise<boolean> {
    loadedKernel().TerminateJobObject(this.job, 1)
    return this.awaitEmpty(budgetMillis)
  }

  async awaitEmpty(budgetMillis: number): Promise<boolean> {
    const deadline = Date.now() + budgetMillis
    while (true) {
      const active = await this.activeProcesses()
      if (active === 0) {
        return true
      }
      if (Date.now() >= deadline) {
        return false
      }
      await delay(5)
    }
  }

  assignProcess(childPid: number): void {
    const kernel32 = loadedKernel()
    const process = kernel32.OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, 0, childPid)
    if (process === null) {
      throw new Error(`cannot open the descendant process ${childPid}: ${kernel32.GetLastError()}`)
    }
    try {
      if (kernel32.AssignProcessToJobObject(this.job, process) === 0) {
        throw new Error(`cannot assign the descendant process ${childPid} to the scope job: ${kernel32.GetLastError()}`)
      }
    } finally {
      kernel32.CloseHandle(process)
    }
  }

  close(): void {
    if (this.closed) {
      return
    }
    this.closed = true
    const kernel32 = loadedKernel()
    if (this.owner && this.process !== null) {
      if (!this.resumed) {
        // 从未运行过的挂起进程：helper 失败关闭时必须自己结束它，而不是把它留给句柄语义。
        kernel32.TerminateProcess(this.process, 1)
      }
      kernel32.CloseHandle(this.process)
    }
    if (this.owner && this.thread !== null) {
      kernel32.CloseHandle(this.thread)
    }
    kernel32.CloseHandle(this.job)
  }

  private async activeProcesses(): Promise<number | null> {
    const kernel32 = await kernel()
    const accounting: Record<string, number> = {}
    const returned = [0]
    const length = (await types()).jobBasicAccounting.size
    if (kernel32.QueryInformationJobObject(this.job, JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION_CLASS, accounting, length, returned) === 0) {
      return null
    }
    const value = accounting.ActiveProcesses
    return typeof value === 'number' ? value : null
  }
}

function loadedKernel(): Kernel32 {
  if (kernelCache === null) {
    throw new Error('kernel32 bindings are not loaded yet')
  }
  return kernelCache
}

function inheritStandardHandle(kernel32: Kernel32, stdHandle: number): Pointer {
  const handle = kernel32.GetStdHandle(stdHandle)
  if (handle === null) {
    throw new Error(`the helper has no standard handle ${stdHandle}`)
  }
  if (kernel32.SetHandleInformation(handle, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT) === 0) {
    throw new Error(`cannot make the standard handle ${stdHandle} inheritable: ${kernel32.GetLastError()}`)
  }
  return handle
}

function distinctHandles(handles: Pointer[]): Pointer[] {
  const seen = new Set<bigint>()
  return handles.filter((handle) => {
    if (seen.has(handle)) {
      return false
    }
    seen.add(handle)
    return true
  })
}

/** 构造 CreateProcessW 需要的 Unicode 环境块：`NAME=value\0` 序列再补一个 `\0`。 */
function createEnvironmentBlock(env: NodeJS.ProcessEnv): Buffer {
  const entries: string[] = []
  for (const key of Object.keys(env).sort()) {
    const value = env[key]
    if (value === undefined || key.includes('=') || key.includes('\0') || value.includes('\0')) {
      continue
    }
    entries.push(`${key}=${value}`)
  }
  const block = entries.length === 0 ? '\0\0' : `${entries.join('\0')}\0\0`
  return Buffer.from(block, 'utf16le')
}

/** 目录锁：创建锁文件并用 `LockFileEx` 取得独占锁；崩溃时由内核释放。 */
export async function lockDirectory(path: string): Promise<WindowsDirectoryLock> {
  const kernel32 = await kernel()
  const { mkdirSync } = await import('node:fs')
  mkdirSync(path, { recursive: true })
  const handle = kernel32.CreateFileW(joinPath(path, LOCK_FILE_NAME), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, null, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, null)
  if (handle === null || isInvalidHandle(handle)) {
    throw new Error(`cannot open the directory lock file: ${kernel32.GetLastError()}`)
  }
  const overlapped = { Internal: 0n, InternalHigh: 0n, Offset: 0, OffsetHigh: 0, hEvent: null }
  if (kernel32.LockFileEx(handle, LOCKFILE_EXCLUSIVE_LOCK | LOCKFILE_FAIL_IMMEDIATELY, 0, 1, 0, overlapped) === 0) {
    kernel32.CloseHandle(handle)
    throw new Error(`the directory is already locked by another process: ${path}`)
  }
  let closed = false
  return {
    async close(): Promise<void> {
      if (closed) {
        return
      }
      closed = true
      kernel32.UnlockFileEx(handle, 0, 1, 0, overlapped)
      kernel32.CloseHandle(handle)
    },
  }
}

/** 独占创建或确认一个只有属主可访问的私有目录，并读回验证 DACL。 */
export async function ensurePrivateDirectory(path: string): Promise<string> {
  const { mkdirSync } = await import('node:fs')
  await rejectReparsePoint(path)
  mkdirSync(path, { recursive: true })
  await applyOwnerOnlyDacl(path, true)
  await verifyPrivatePath(path)
  return path
}

/** 以 owner-only DACL 写入敏感文件并读回验证。 */
export async function writePrivateFile(path: string, data: string | Uint8Array): Promise<void> {
  const { writeFileSync } = await import('node:fs')
  await rejectReparsePoint(path)
  writeFileSync(path, data)
  await applyOwnerOnlyDacl(path, false)
  await verifyPrivatePath(path)
}

/** 读回 DACL：必须存在、至少一个 ACE，且全部 allow ACE 都只授予属主。 */
export async function verifyPrivateFile(path: string): Promise<void> {
  await verifyPrivatePath(path)
}

async function verifyPrivatePath(path: string): Promise<void> {
  const { lstatSync } = await import('node:fs')
  if (lstatSync(path).isSymbolicLink()) {
    throw new Error(`the private path must not be a symbolic link: ${path}`)
  }
  const advapi32 = await advapi()
  const windowsTypes = await types()
  const owner: Pointer[] = [null as unknown as Pointer]
  const group: Pointer[] = [null as unknown as Pointer]
  const dacl: Pointer[] = [null as unknown as Pointer]
  const sacl: Pointer[] = [null as unknown as Pointer]
  const descriptor: Pointer[] = [null as unknown as Pointer]
  const status = advapi32.GetNamedSecurityInfoW(path, SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION, owner, group, dacl, sacl, descriptor)
  if (status !== 0) {
    throw new Error(`cannot read the private path security descriptor: ${status}`)
  }
  try {
    const ownerSid = owner[0]
    const acl = dacl[0]
    if (ownerSid == null) {
      throw new Error(`the private path has no owner: ${path}`)
    }
    if (acl == null) {
      throw new Error(`the private path has a null DACL (everyone has access): ${path}`)
    }
    const koffi = await koffiModule()
    const header = koffi.decode(acl, windowsTypes.acl as never) as unknown as { AceCount: number }
    if (header.AceCount === 0) {
      throw new Error(`the private path has an empty DACL: ${path}`)
    }
    for (let index = 0; index < header.AceCount; index += 1) {
      const ace: Pointer[] = [null as unknown as Pointer]
      if (advapi32.GetAce(acl, index, ace) === 0 || ace[0] == null) {
        throw new Error(`cannot read the private path DACL entry ${index}: ${path}`)
      }
      const entry = ace[0]
      const aceHeader = koffi.decode(entry, windowsTypes.aceHeader as never) as unknown as { AceType: number }
      if (aceHeader.AceType !== ACCESS_ALLOWED_ACE_TYPE) {
        // 只接受写入方自己建立的简单 allow ACE；其它类型由 fail-closed 处理。
        throw new Error(`the private path DACL contains an unsupported entry: ${path}`)
      }
      if (advapi32.EqualSid(entry + 8n, ownerSid) === 0) {
        throw new Error(`the private path DACL grants access to a foreign principal: ${path}`)
      }
    }
  } finally {
    if (descriptor[0] != null) {
      advapi32.LocalFree(descriptor[0])
    }
  }
}

/** 用路径当前属主 SID 重建 DACL：目录额外带可继承标志，使新建子项默认同样只有属主可访问。 */
async function applyOwnerOnlyDacl(path: string, directory: boolean): Promise<void> {
  const advapi32 = await advapi()
  const koffi = await koffiModule()
  const owner: Pointer[] = [null as unknown as Pointer]
  const group: Pointer[] = [null as unknown as Pointer]
  const dacl: Pointer[] = [null as unknown as Pointer]
  const sacl: Pointer[] = [null as unknown as Pointer]
  const descriptor: Pointer[] = [null as unknown as Pointer]
  const status = advapi32.GetNamedSecurityInfoW(path, SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION, owner, group, dacl, sacl, descriptor)
  if (status !== 0) {
    throw new Error(`cannot read the private path owner: ${status}`)
  }
  try {
    const ownerSid = owner[0]
    if (ownerSid == null) {
      throw new Error(`the private path has no owner: ${path}`)
    }
    const sidLength = advapi32.GetLengthSid(ownerSid)
    if (sidLength === 0) {
      throw new Error(`cannot read the owner SID length: ${path}`)
    }
    const aclLength = 8 + 8 + sidLength
    const acl = koffi.alloc('char', aclLength)
    if (advapi32.InitializeAcl(acl, aclLength, ACL_REVISION) === 0) {
      throw new Error(`cannot initialize the private path ACL: ${path}`)
    }
    const aceFlags = directory ? OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE : 0
    if (advapi32.AddAccessAllowedAceEx(acl, ACL_REVISION, aceFlags, FILE_ALL_ACCESS, ownerSid) === 0) {
      throw new Error(`cannot add the private path owner ACE: ${path}`)
    }
    if (advapi32.SetNamedSecurityInfoW(path, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, null, null, acl, null) !== 0) {
      throw new Error(`cannot set the private path owner-only DACL: ${path}`)
    }
  } finally {
    if (descriptor[0] != null) {
      advapi32.LocalFree(descriptor[0])
    }
  }
}

/** Windows 路径拼接：只补一个分隔符，不改变已有语义。 */
function joinPath(directory: string, name: string): string {
  const separator = directory.endsWith('\\') || directory.endsWith('/') ? '' : '\\'
  return `${directory}${separator}${name}`
}

function isInvalidHandle(handle: Pointer): boolean {
  return handle === INVALID_HANDLE || handle === -1n
}

/** 拒绝最终组件是符号链接或 reparse 点。 */
async function rejectReparsePoint(path: string): Promise<void> {
  const { lstatSync } = await import('node:fs')
  try {
    if (lstatSync(path).isSymbolicLink()) {
      throw new Error(`the private path must not be a symbolic link: ${path}`)
    }
  } catch (error) {
    if (error instanceof Error && 'code' in error && (error as NodeJS.ErrnoException).code === 'ENOENT') {
      return
    }
    throw error
  }
}

function delay(millis: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, millis))
}
