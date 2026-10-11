import { createHash } from 'node:crypto'
import type { Stats } from 'node:fs'
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
  confirmDescendant(childPid: number): void
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
const WAIT_OBJECT_0 = 0
const WAIT_TIMEOUT = 0x0000_0102
const WAIT_FAILED = 0xFFFF_FFFF
const JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x0000_2000
const JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION_CLASS = 1
const JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9
const JOB_OBJECT_ALL_ACCESS = 0x001F_001F
const PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x0002_0002
const PROC_THREAD_ATTRIBUTE_JOB_LIST = 0x0002_000D
const SE_FILE_OBJECT = 1
const OWNER_SECURITY_INFORMATION = 0x0000_0001
const DACL_SECURITY_INFORMATION = 0x0000_0004
/** 让新 DACL 取代继承来的 ACE：既切掉外部主体，又不触碰属主 SID、不提升权限。 */
const PROTECTED_DACL_SECURITY_INFORMATION = 0x8000_0000
const TOKEN_QUERY = 0x0000_0008
const TOKEN_USER_INFORMATION_CLASS = 1
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
/** 打开 reparse 点本身而不是跟随它：配合 `GetFileAttributesW` 的 reparse 判定，任何类型的重定向都被拒绝。 */
const FILE_FLAG_OPEN_REPARSE_POINT = 0x0020_0000
const FILE_ATTRIBUTE_REPARSE_POINT = 0x0000_0400
const INVALID_FILE_ATTRIBUTES = 0xFFFF_FFFF
const ERROR_FILE_NOT_FOUND = 2
const ERROR_PATH_NOT_FOUND = 3
const LOCKFILE_FAIL_IMMEDIATELY = 0x0000_0001
const LOCKFILE_EXCLUSIVE_LOCK = 0x0000_0002
const PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
const INVALID_HANDLE = 0xFFFF_FFFF_FFFF_FFFFn
/** 命令退出轮询步长：`WaitForSingleObject(..., 0)` 之间让出事件循环，父进程消失的 IPC 收尾才可能被处理。 */
const EXIT_POLL_INTERVAL_MILLIS = 20
const LOCK_FILE_NAME = 'daemon.lock'
const JOB_NAME_PREFIX = 'kk-studio-process-scope-'

type Pointer = bigint
type Koffi = typeof import('koffi')

export interface StructType {
  size: number
}

export interface Kernel32 {
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
  IsProcessInJob(process: Pointer, job: Pointer, result: number[]): number
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
  GetFileAttributesW(fileName: string): number
  CreateFileW(fileName: string, desiredAccess: number, shareMode: number, securityAttributes: Pointer | null, creationDisposition: number, flagsAndAttributes: number, template: Pointer | null): Pointer | null
  LockFileEx(file: Pointer, flags: number, reserved: number, low: number, high: number, overlapped: Record<string, unknown>): number
  UnlockFileEx(file: Pointer, reserved: number, low: number, high: number, overlapped: Record<string, unknown>): number
}

export interface Advapi32 {
  GetNamedSecurityInfoW(name: string, objectType: number, securityInfo: number, owner: Pointer[], group: Pointer[], dacl: Pointer[], sacl: Pointer[], descriptor: Pointer[]): number
  SetNamedSecurityInfoW(name: string, objectType: number, securityInfo: number, owner: Pointer | null, group: Pointer | null, dacl: Pointer | null, sacl: Pointer | null): number
  OpenProcessToken(process: Pointer, desiredAccess: number, token: Pointer[]): number
  GetTokenInformation(token: Pointer, infoClass: number, info: Buffer | null, length: number, returnLength: number[]): number
  InitializeAcl(acl: Pointer, length: number, revision: number): number
  AddAccessAllowedAceEx(acl: Pointer, revision: number, flags: number, mask: number, sid: Pointer): number
  GetAce(acl: Pointer, index: number, ace: Pointer[]): number
  GetLengthSid(sid: Pointer): number
  EqualSid(first: Pointer, second: Pointer): number
  LocalFree(memory: Pointer): Pointer | null
}

export interface WindowsTypes {
  startupInfoEx: StructType
  jobExtendedLimit: StructType
  jobBasicAccounting: StructType
  sizeBox: StructType
  acl: StructType
  aceHeader: StructType
  tokenUser: StructType
}

/** 单个 `size_t` 的盒子：把 `size_t *` 出参交给 Koffi 的 struct 编组读取。 */
export interface SizeBox {
  value: number | bigint
}

/** 原生绑定集合：生产路径由 `kernel()`/`advapi()`/`types()` 惰性加载，测试可整体注入以驱动真实代码路径。 */
export interface WindowsNativeBindings {
  kernel: Kernel32
  advapi: Advapi32
  types: WindowsTypes
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
    // TOKEN_USER：`SID_AND_ATTRIBUTES.User.Sid` 是缓冲区内的 SID 指针；Koffi 按类型解码，不做字节猜测。
    const sidAndAttributes = koffi.struct('SID_AND_ATTRIBUTES', { Sid: 'void *', Attributes: 'uint32_t' })
    const tokenUser = koffi.struct('TOKEN_USER', { User: sidAndAttributes })
    typesCache = { startupInfoEx, jobExtendedLimit, jobBasicAccounting, sizeBox, acl, aceHeader, tokenUser }
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
      IsProcessInJob: library.func('int IsProcessInJob(void *process, void *job, _Out_ int *result)'),
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
      GetFileAttributesW: library.func('unsigned int GetFileAttributesW(const char16_t *fileName)'),
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
      OpenProcessToken: library.func('int OpenProcessToken(void *process, unsigned int desiredAccess, _Out_ void **token)'),
      GetTokenInformation: library.func('int GetTokenInformation(void *token, int infoClass, void *info, unsigned int length, _Out_ unsigned int *returnLength)'),
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

  /**
   * 等待命令进程退出。
   *
   * <p>绝不用 `WaitForSingleObject(handle, INFINITE)`：那会同步阻塞 helper 的 Node 事件循环，父进程被强杀时内核关闭 IPC 通道
   * 产生的 `disconnect` 永远不会被派发，收尾也就永远不会发生。这里改为 `Wait(..., 0)` + 小步异步让出，既拿到同样的退出事实，
   * 又让 IPC 收尾始终可被处理。
   */
  async awaitExit(): Promise<ExitReport> {
    if (this.process === null) {
      throw new Error('the scope job object has no command process to wait for')
    }
    const kernel32 = await kernel()
    while (true) {
      const status = kernel32.WaitForSingleObject(this.process, 0)
      if (status === WAIT_OBJECT_0) {
        break
      }
      if (status !== WAIT_TIMEOUT) {
        throw new Error(`cannot wait for the command process: ${status === WAIT_FAILED ? kernel32.GetLastError() : status}`)
      }
      await delay(EXIT_POLL_INTERVAL_MILLIS)
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

  /**
   * 确认某个 pid 确实已经属于本 Job。
   *
   * <p>Windows 的 Job 后代在**创建期**就自动归属（子进程默认继承父进程的 Job），因此这里只做 `IsProcessInJob` 查询：属于才确认，
   * 不属于直接拒绝。绝不调用 `AssignProcessToJobObject` 去「补指派」一个任意 pid——那会把别人的进程劫持进本 Job，随收敛一起被杀。
   */
  confirmDescendant(childPid: number): void {
    const kernel32 = loadedKernel()
    const process = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, childPid)
    if (process === null) {
      throw new Error(`cannot open the descendant process ${childPid}: ${kernel32.GetLastError()}`)
    }
    try {
      const result = [0]
      if (kernel32.IsProcessInJob(process, this.job, result) === 0) {
        throw new Error(`cannot query the scope job membership of the descendant process ${childPid}: ${kernel32.GetLastError()}`)
      }
      if (result[0] === 0) {
        throw new Error(`the descendant process ${childPid} is not a member of the scope job`)
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

/**
 * 仅测试用：整体注入原生绑定，使非 Windows 主机也能驱动真实 `JobScope`/DACL 代码路径。
 *
 * <p>生产路径不会调用它：绑定仍由 `kernel()`/`advapi()`/`types()` 惰性加载；传入 `null` 复位缓存。
 */
export function installWindowsNativeBindingsForTest(bindings: WindowsNativeBindings | null): void {
  kernelCache = bindings?.kernel ?? null
  advapiCache = bindings?.advapi ?? null
  typesCache = bindings?.types ?? null
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

/**
 * 目录锁：先把目录收紧为属主独占，再创建/打开锁文件并读回核验其 DACL，最后用 `LockFileEx` 取得独占锁。
 *
 * <p>锁文件与目录都走同一套真实私有原语：不跟随 reparse 点（`GetFileAttributesW` 判定 + `FILE_FLAG_OPEN_REPARSE_POINT`），
 * 打开后按 owner-only DACL 收紧并读回。释放时无论解锁是否成功都会继续关闭句柄，并把两者的失败一起上报。
 */
export async function lockDirectory(path: string): Promise<WindowsDirectoryLock> {
  const kernel32 = await kernel()
  await ensurePrivateDirectory(path)
  const lockPath = joinPath(path, LOCK_FILE_NAME)
  await rejectReparsePoint(lockPath)
  const handle = kernel32.CreateFileW(
    lockPath,
    GENERIC_READ | GENERIC_WRITE,
    FILE_SHARE_READ | FILE_SHARE_WRITE,
    null,
    OPEN_ALWAYS,
    FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT,
    null,
  )
  if (handle === null || isInvalidHandle(handle)) {
    throw new Error(`cannot open the directory lock file: ${kernel32.GetLastError()}`)
  }
  try {
    // 收紧锁文件 DACL 之前先确认它属于当前用户：既有锁文件若是外部属主，绝不改写。
    await assertOwnedByCurrentUser(lockPath)
    await applyOwnerOnlyDacl(lockPath, false)
    await verifyPrivatePath(lockPath)
  } catch (error) {
    kernel32.CloseHandle(handle)
    throw error
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
      releaseDirectoryLock(kernel32, handle, overlapped)
    },
  }
}

/**
 * 释放目录锁：**一定**尝试解锁与关闭句柄，并把两者的失败一起上报。
 *
 * <p>句柄泄漏比解锁失败更严重，因此即使解锁失败也继续关闭；导出以便在非 Windows 主机上用假绑定验证这一聚合行为。
 */
export function releaseDirectoryLock(
  kernel32: Pick<Kernel32, 'UnlockFileEx' | 'CloseHandle' | 'GetLastError'>,
  handle: Pointer,
  overlapped: Record<string, unknown>,
): void {
  const failures: string[] = []
  if (kernel32.UnlockFileEx(handle, 0, 1, 0, overlapped) === 0) {
    failures.push(`unlock the directory lock file (${kernel32.GetLastError()})`)
  }
  if (kernel32.CloseHandle(handle) === 0) {
    failures.push(`close the directory lock handle (${kernel32.GetLastError()})`)
  }
  if (failures.length > 0) {
    throw new Error(`cannot ${failures.join('; cannot ')}`)
  }
}

/** 独占创建或确认一个只有属主可访问的私有目录，并读回验证 DACL。 */
export async function ensurePrivateDirectory(path: string): Promise<string> {
  const fs = await import('node:fs')
  await rejectReparsePoint(path)
  const existing = lstatOrNull(fs, path)
  if (existing !== null) {
    if (!existing.isDirectory()) {
      throw new Error(`the private path must be a directory: ${path}`)
    }
    // 已存在的目录在改动其 DACL 之前先确认属主：绝不对别人的目录收紧权限。
    await assertOwnedByCurrentUser(path)
  }
  fs.mkdirSync(path, { recursive: true })
  await applyOwnerOnlyDacl(path, true)
  await verifyPrivatePath(path)
  return path
}

/**
 * 以 owner-only DACL **原子**写入敏感文件。
 *
 * <p>绝不对目标做「先写内容再收紧 DACL」：那会先把明文落进继承来的宽松权限文件里。这里改为在同一个私有父目录里独占创建
 * **空**临时文件，先把它的 DACL 收紧成 owner-only 并读回，再写满、`fsync`，最后原子 `rename` 覆盖目标并读回验证。
 * 覆盖既有目标之前先核验它是属主自己的、已经收紧的普通文件；任何外部属主/宽松 ACL 都在写入前失败关闭，绝不截断改写。
 */
export async function writePrivateFile(path: string, data: string | Uint8Array): Promise<void> {
  const fs = await import('node:fs')
  const pathModule = await import('node:path')
  await rejectReparsePoint(path)
  const existing = lstatOrNull(fs, path)
  if (existing !== null) {
    if (!existing.isFile()) {
      throw new Error(`the private path must be a regular file: ${path}`)
    }
    await verifyPrivatePath(path)
  }
  const temporary = pathModule.join(pathModule.dirname(path), `.${pathModule.basename(path)}.${process.pid}.${Date.now().toString(36)}.tmp`)
  try {
    // 空文件：此刻即使权限还没收紧也没有任何敏感内容可泄露。
    fs.writeFileSync(temporary, new Uint8Array(0), { flag: 'wx', mode: 0o600 })
    await applyOwnerOnlyDacl(temporary, false)
    await verifyPrivatePath(temporary)
    // `O_NOFOLLOW` 在 Windows 上未定义：只有 POSIX 需要它，缺省按 0 处理（临时文件刚由本进程独占创建并核验过）。
    const noFollow = fs.constants.O_NOFOLLOW ?? 0
    const handle = fs.openSync(temporary, fs.constants.O_WRONLY | noFollow)
    try {
      writeAllSync(fs, handle, typeof data === 'string' ? Buffer.from(data, 'utf8') : Buffer.from(data))
      fs.fsyncSync(handle)
    } finally {
      fs.closeSync(handle)
    }
    fs.renameSync(temporary, path)
  } catch (error) {
    fs.rmSync(temporary, { force: true })
    throw error
  }
  await verifyPrivatePath(path)
}

/** 读回 DACL：必须存在、至少一个 ACE，且属主与全部 allow ACE 都是当前登录用户。 */
export async function verifyPrivateFile(path: string): Promise<void> {
  await verifyPrivatePath(path)
}

/**
 * 读回核验一个私有路径。
 *
 * <p>判定基准是**当前进程令牌的用户 SID**，不是「文件当前的属主」：否则一个外部用户拥有的文件只要 DACL 全部等于它自己的属主，
 * 就会被误判为「正确」。这里同时要求：属主就是当前用户，且 DACL 里每个 allow ACE 的 SID 也都是当前用户。
 */
async function verifyPrivatePath(path: string): Promise<void> {
  const fs = await import('node:fs')
  if (fs.lstatSync(path).isSymbolicLink()) {
    throw new Error(`the private path must not be a symbolic link: ${path}`)
  }
  const advapi32 = await advapi()
  const windowsTypes = await types()
  const currentSid = await currentUserSid()
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
    if (advapi32.EqualSid(ownerSid, currentSid) === 0) {
      throw new Error(`the private path is not owned by the current user: ${path}`)
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
      if (advapi32.EqualSid(entry + 8n, currentSid) === 0) {
        throw new Error(`the private path DACL grants access to a foreign principal: ${path}`)
      }
    }
  } finally {
    if (descriptor[0] != null) {
      advapi32.LocalFree(descriptor[0])
    }
  }
}

/** 确认路径属主就是当前登录用户：覆盖/收紧任何既有路径之前的第一道门。 */
async function assertOwnedByCurrentUser(path: string): Promise<void> {
  const advapi32 = await advapi()
  const currentSid = await currentUserSid()
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
    if (ownerSid == null || advapi32.EqualSid(ownerSid, currentSid) === 0) {
      throw new Error(`the private path is not owned by the current user: ${path}`)
    }
  } finally {
    if (descriptor[0] != null) {
      advapi32.LocalFree(descriptor[0])
    }
  }
}

let currentUserSidState: { sid: Pointer; buffer: Buffer } | null = null

/**
 * 当前进程令牌的用户 SID。
 *
 * <p>真实 FFI：`OpenProcessToken` + `GetTokenInformation(TokenUser)`，SID 指针按 `TOKEN_USER`/`SID_AND_ATTRIBUTES` 布局解码
 * （不做字节猜测）。返回的指针指向缓存的令牌缓冲区，必须在进程生命周期内保持有效。
 */
async function currentUserSid(): Promise<Pointer> {
  if (currentUserSidState !== null) {
    return currentUserSidState.sid
  }
  const kernel32 = await kernel()
  const advapi32 = await advapi()
  const koffi = await koffiModule()
  const windowsTypes = await types()
  const token: Pointer[] = [null as unknown as Pointer]
  if (advapi32.OpenProcessToken(kernel32.GetCurrentProcess(), TOKEN_QUERY, token) === 0) {
    throw new Error(`cannot open the process token: ${kernel32.GetLastError()}`)
  }
  const tokenHandle = token[0]
  if (tokenHandle == null) {
    throw new Error('the process token handle is missing')
  }
  try {
    const required = [0]
    advapi32.GetTokenInformation(tokenHandle, TOKEN_USER_INFORMATION_CLASS, null, 0, required)
    const length = required[0] ?? 0
    if (length === 0) {
      throw new Error('cannot size the process token user information')
    }
    const buffer = Buffer.alloc(length)
    if (advapi32.GetTokenInformation(tokenHandle, TOKEN_USER_INFORMATION_CLASS, buffer, length, required) === 0) {
      throw new Error(`cannot read the process token user: ${kernel32.GetLastError()}`)
    }
    const decoded = koffi.decode(buffer, windowsTypes.tokenUser as never) as unknown as { User: { Sid: Pointer | null } }
    const sid = decoded.User?.Sid ?? null
    if (sid == null) {
      throw new Error('the process token has no user SID')
    }
    // 缓冲区必须与 SID 指针一起存活：`Sid` 指向缓冲区内部，随缓冲区一起被 GC 回收。
    currentUserSidState = { sid, buffer }
    return sid
  } finally {
    kernel32.CloseHandle(tokenHandle)
  }
}

/**
 * 用**当前登录用户 SID** 重建 DACL：目录额外带可继承标志，使新建子项默认同样只有属主可访问。
 *
 * <p>`PROTECTED_DACL_SECURITY_INFORMATION` 让新 DACL 取代继承来的 ACE，切掉外部主体；只传 DACL，不改属主 SID、不提升权限。
 */
async function applyOwnerOnlyDacl(path: string, directory: boolean): Promise<void> {
  const advapi32 = await advapi()
  const koffi = await koffiModule()
  const currentSid = await currentUserSid()
  const sidLength = advapi32.GetLengthSid(currentSid)
  if (sidLength === 0) {
    throw new Error(`cannot read the current user SID length: ${path}`)
  }
  const aclLength = 8 + 8 + sidLength
  const acl = koffi.alloc('char', aclLength)
  if (advapi32.InitializeAcl(acl, aclLength, ACL_REVISION) === 0) {
    throw new Error(`cannot initialize the private path ACL: ${path}`)
  }
  const aceFlags = directory ? OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE : 0
  if (advapi32.AddAccessAllowedAceEx(acl, ACL_REVISION, aceFlags, FILE_ALL_ACCESS, currentSid) === 0) {
    throw new Error(`cannot add the private path owner ACE: ${path}`)
  }
  if (advapi32.SetNamedSecurityInfoW(path, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION, null, null, acl, null) !== 0) {
    throw new Error(`cannot set the private path owner-only DACL: ${path}`)
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

/** 读取路径的 lstat；不存在返回 `null`，其它错误照常抛出。 */
function lstatOrNull(fs: typeof import('node:fs'), path: string): Stats | null {
  try {
    return fs.lstatSync(path)
  } catch (error) {
    if (error instanceof Error && 'code' in error && (error as NodeJS.ErrnoException).code === 'ENOENT') {
      return null
    }
    throw error
  }
}

/** 写满整个缓冲区：`writeSync` 允许短写，循环直到全部写完。 */
function writeAllSync(fs: typeof import('node:fs'), handle: number, data: Buffer): void {
  let offset = 0
  while (offset < data.length) {
    offset += fs.writeSync(handle, data, offset, data.length - offset)
  }
}

/**
 * 拒绝最终组件是符号链接或**任何** reparse 点。
 *
 * <p>只查 `lstat.isSymbolicLink()` 不够：Windows 上 junction、mount point、AppExecLink 等 reparse 变体同样会把路径重定向到
 * 别处。这里额外用 `GetFileAttributesW` 判定 `FILE_ATTRIBUTE_REPARSE_POINT`，把所有 reparse 变体一并拒绝。
 */
async function rejectReparsePoint(path: string): Promise<void> {
  const fs = await import('node:fs')
  const stats = lstatOrNull(fs, path)
  if (stats !== null && stats.isSymbolicLink()) {
    throw new Error(`the private path must not be a symbolic link: ${path}`)
  }
  const kernel32 = await kernel()
  const attributes = kernel32.GetFileAttributesW(path)
  if (attributes === INVALID_FILE_ATTRIBUTES) {
    const code = kernel32.GetLastError()
    if (code === ERROR_FILE_NOT_FOUND || code === ERROR_PATH_NOT_FOUND) {
      return
    }
    throw new Error(`cannot read the private path attributes: ${path}`)
  }
  if ((attributes & FILE_ATTRIBUTE_REPARSE_POINT) !== 0) {
    throw new Error(`the private path must not be a reparse point: ${path}`)
  }
}

function delay(millis: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, millis))
}
