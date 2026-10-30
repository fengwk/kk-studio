import { mkdtempSync, rmSync, writeFileSync, mkdirSync, lstatSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, test, vi } from 'vitest'
import {
  commitPrivateFile,
  createSuspendedJob,
  ensurePrivateDirectory,
  installWindowsNativeBindingsForTest,
  lockDirectory,
  releaseDirectoryLock,
  verifyPrivateFile,
  writePrivateFile,
  type WindowsNativeBindings,
} from '../../src/native/win32.js'
import { writeAllSync } from '../../src/native/write-all.js'

const actualFs = await vi.importActual<typeof import('node:fs')>('node:fs')

vi.mock('node:fs', async (importOriginal) => {
  const original = await importOriginal<typeof import('node:fs')>()
  return { ...original, mkdirSync: vi.fn(original.mkdirSync) }
})

/**
 * 用假原生绑定在**非 Windows 主机**上驱动真实 `win32.ts` 代码路径。
 *
 * <p>覆盖三类只有在 Linux 上才能廉价回归、却属于 Windows 真实安全/正确性的性质：
 * (1) `awaitExit` 绝不把 helper 的 Node 事件循环同步阻塞住（否则父进程消失的 IPC 收尾永远收不到）；
 * (2) Job 成员只核验不劫持、收敛失败如实抛出、句柄关闭失败如实聚合；
 * (3) DACL 读回与 `LocalFree`（**由 kernel32.dll 导出**）失败聚合、短写为 0 时绝不改名。
 */
const WAIT_OBJECT_0 = 0
const WAIT_TIMEOUT = 0x102
const WAIT_FAILED = 0xffff_ffff
const INFINITE = 0xffff_ffff
const SID_POINTER = 0x1000n
const FOREIGN_SID = 0x1001n
const DESCRIPTOR_POINTER = 0x2000n
const OWNER_SECURITY_INFORMATION = 0x0000_0001
const ERROR_ALREADY_EXISTS = 183

interface Harness {
  bindings: WindowsNativeBindings
  waits: number[]
  inJob: { value: number }
  closed: number[]
  ticks: { value: number }
  activeProcesses: { value: number }
  terminateResult: { value: number }
  closeResult: { value: number }
  equalSid: { value: number }
  localFreeResult: { value: bigint | null }
}

function harness(): Harness {
  const waits: number[] = []
  const inJob = { value: 1 }
  const closed: number[] = []
  const ticks = { value: 0 }
  const activeProcesses = { value: 0 }
  const terminateResult = { value: 1 }
  const closeResult = { value: 1 }
  const equalSid = { value: 1 }
  const localFreeResult = { value: null as bigint | null }
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
    TerminateJobObject: () => terminateResult.value,
    QueryInformationJobObject: (_job: unknown, _c: unknown, buffer: Record<string, number>) => {
      buffer.ActiveProcesses = activeProcesses.value
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
      return closeResult.value
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
    GetLastError: () => 5,
    GetFileAttributesW: () => 0x80,
    LocalFree: () => localFreeResult.value,
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
    EqualSid: () => equalSid.value,
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
  return {
    bindings: { kernel, advapi, types } as unknown as WindowsNativeBindings,
    waits,
    inJob,
    closed,
    ticks,
    activeProcesses,
    terminateResult,
    closeResult,
    equalSid,
    localFreeResult,
  }
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

  test('terminateAndDrain throws with the call status when the job never empties', async () => {
    const fake = harness()
    fake.activeProcesses.value = 1
    fake.terminateResult.value = 0
    installWindowsNativeBindingsForTest(fake.bindings)
    const scope = await createSuspendedJob('kk-studio-test-job', { cwd: process.cwd(), args: ['fake-command'] }, {})
    try {
      await expect(scope.terminateAndDrain(30)).rejects.toThrow(/could not be proven converged.*terminate the scope job failed with 5.*active process/)
    } finally {
      scope.close()
    }
  })

  test('terminateAndDrain succeeds purely on ActiveProcesses == 0', async () => {
    const fake = harness()
    fake.activeProcesses.value = 0
    fake.terminateResult.value = 0 // 即使 TerminateJobObject 报错，只要整组确实空了就算收敛。
    installWindowsNativeBindingsForTest(fake.bindings)
    const scope = await createSuspendedJob('kk-studio-test-job', { cwd: process.cwd(), args: ['fake-command'] }, {})
    try {
      await expect(scope.terminateAndDrain(30)).resolves.toBe(true)
    } finally {
      scope.close()
    }
  })

  test('close attempts every handle and aggregates failures', async () => {
    const fake = harness()
    fake.closeResult.value = 0
    installWindowsNativeBindingsForTest(fake.bindings)
    const scope = await createSuspendedJob('kk-studio-test-job', { cwd: process.cwd(), args: ['fake-command'] }, {})
    expect(() => scope.close()).toThrow(/close the command process handle/)
    // 一个句柄失败后仍然尝试了其余句柄（进程、线程、Job）。
    expect(fake.closed.length).toBeGreaterThanOrEqual(3)
    expect(() => scope.close()).not.toThrow() // 幂等：第二次直接返回。
  })

  test('writeAllSync fails closed on a zero-length short write', () => {
    const written: number[] = []
    const zero = {
      writeSync: () => {
        written.push(0)
        return 0
      },
    }
    expect(() => writeAllSync(zero, 1, Buffer.from('abc'), 'the private file could not be written completely')).toThrow(
      /could not be written completely/,
    )

    // 部分写入后补满：必须逐次推进 offset，而不是重写开头。
    const offsets: number[] = []
    const partial = {
      writeSync: (_handle: number, _data: Buffer, offset: number, length: number) => {
        offsets.push(offset)
        return Math.min(2, length)
      },
    }
    writeAllSync(partial, 1, Buffer.from('abcde'), 'the private file could not be written completely')
    expect(offsets).toEqual([0, 2, 4])
  })

  test('commitPrivateFile never renames when the write makes no progress', () => {
    const events: string[] = []
    const fakeFs = {
      constants: { O_WRONLY: 1, O_NOFOLLOW: 0 },
      openSync: () => {
        events.push('open')
        return 9
      },
      writeSync: () => {
        events.push('write')
        return 0
      },
      fsyncSync: () => {
        events.push('fsync')
      },
      closeSync: () => {
        events.push('close')
      },
      renameSync: () => {
        events.push('rename')
      },
    }
    expect(() => commitPrivateFile(fakeFs, 'temp', 'target', Buffer.from('secret'))).toThrow(/could not be written completely/)
    // 目标保持不变：从未发生 rename。
    expect(events).not.toContain('rename')
    expect(events).toContain('close')
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

describe('windows compiled entry (build output)', () => {
  test('the compiled win32.js imports on this platform without touching the lazy kernel bindings', async () => {
    // 编译产物的语法与路径必须在非 Windows 上也可加载：源码态 .ts 含 parameter property，Node 类型剥离无法直接执行，
    // 这正是「helper 只运行编译产物」的动机。这里直接加载 dist 产物，证明编译后的 ESM 在本平台成立。
    const module = await import(new URL('../../dist/native/win32.js', import.meta.url).href)
    expect(typeof module.createSuspendedJob).toBe('function')
    expect(typeof module.verifyPrivateFile).toBe('function')
    expect(typeof module.lockDirectory).toBe('function')
  })
})

describe('windows private path DACL read-back (mocked native bindings)', () => {
  test('LocalFree is bound from kernel32 and its failure is aggregated without masking the original error', async () => {
    const koffi = await import('koffi')
    // 真实的 Koffi 结构布局：`koffi.decode` 需要按类型读取 ACL / ACE / TOKEN_USER。
    const aclType = koffi.struct('KK_TEST_ACL', { AclRevision: 'uint8_t', Sbz1: 'uint8_t', AclSize: 'uint16_t', AceCount: 'uint16_t', Sbz2: 'uint16_t' })
    const aceHeaderType = koffi.struct('KK_TEST_ACE_HEADER', { AceType: 'uint8_t', AceFlags: 'uint8_t', AceSize: 'uint16_t' })
    const sidAndAttributesType = koffi.struct('KK_TEST_SID_AND_ATTRIBUTES', { Sid: 'void *', Attributes: 'uint32_t' })
    const tokenUserType = koffi.struct('KK_TEST_TOKEN_USER', { User: sidAndAttributesType })

    // 真实原生内存（Koffi 分配）承载 ACL 与 ACE：`GetAce` 返回的必须是真正的指针（bigint），
    // 生产代码会对它做指针运算（`entry + 8n`），不能用 Buffer 视图冒充。
    const aclPointer = koffi.alloc('char', 8) as bigint
    koffi.encode(aclPointer, aclType, { AclRevision: 2, Sbz1: 0, AclSize: 8, AceCount: 1, Sbz2: 0 })
    const acePointer = koffi.alloc('char', 16) as bigint // 零初始化：AceType = ACCESS_ALLOWED_ACE_TYPE(0)

    const equalSid = { value: 1 }
    const localFreeResult = { value: null as bigint | null }
    const kernel = {
      GetFileAttributesW: () => 0x80,
      LocalFree: () => localFreeResult.value,
      GetLastError: () => 5,
      GetCurrentProcess: () => 9n,
      CloseHandle: () => 1,
    }
    const advapi = {
      GetNamedSecurityInfoW: (_name: string, _type: number, _info: number, owner: bigint[], _g: unknown, dacl: bigint[], _s: unknown, descriptor: bigint[]) => {
        owner[0] = SID_POINTER
        dacl[0] = aclPointer
        descriptor[0] = DESCRIPTOR_POINTER
        return 0
      },
      OpenProcessToken: (_process: unknown, _access: number, token: bigint[]) => {
        token[0] = 0x4000n
        return 1
      },
      GetTokenInformation: (_token: unknown, _cls: number, info: Buffer | null, _len: number, required: number[]) => {
        required[0] = 16
        if (info === null) {
          return 0
        }
        info.writeBigUInt64LE(SID_POINTER, 0)
        return 1
      },
      GetLengthSid: () => 12,
      EqualSid: () => equalSid.value,
      GetAce: (_acl: unknown, _index: number, ace: bigint[]) => {
        ace[0] = acePointer
        return 1
      },
    }
    const types = {
      startupInfoEx: { size: 112 },
      jobExtendedLimit: { size: 144 },
      jobBasicAccounting: { size: 48 },
      sizeBox: { size: 8 },
      acl: aclType,
      aceHeader: aceHeaderType,
      tokenUser: tokenUserType,
    }
    installWindowsNativeBindingsForTest({ kernel, advapi, types } as unknown as WindowsNativeBindings)

    const workdir = mkdtempSync(join(tmpdir(), 'kk-studio-native-dacl-'))
    const file = join(workdir, 'secret')
    writeFileSync(file, 'x')
    try {
      // 全部 ACE 都是当前用户、LocalFree 成功 → 读回通过。
      await expect(verifyPrivateFile(file)).resolves.toBeUndefined()

      // LocalFree 返回非 NULL（释放失败）：必须报错，绝不当作成功。
      localFreeResult.value = 0x1n
      await expect(verifyPrivateFile(file)).rejects.toThrow(/cannot free the private path security descriptor/)

      // 原始失败（非当前用户）与释放失败必须一起上报，原始错误不被遮蔽。
      equalSid.value = 0
      await expect(verifyPrivateFile(file)).rejects.toThrow(/not owned by the current user.*additionally cannot free/)
    } finally {
      rmSync(workdir, { recursive: true, force: true })
      koffi.free(aclPointer)
      koffi.free(acePointer)
    }
  })

  test('a newly created directory and its empty temporary file set the current user before sensitive bytes', async () => {
    // 默认 TokenOwner 可能不是 TokenUser：只有本次确证新建才带 OWNER 位，且必须早于敏感内容。
    const world = await ownerWorld()
    const workdir = mkdtempSync(join(tmpdir(), 'kk-studio-native-owner-'))
    try {
      const created = join(workdir, 'created')
      await expect(ensurePrivateDirectory(created)).resolves.toBe(created)
      expect(ownerCall(world.calls, created)?.owner).toBe(world.observedUserSid)
      expect((ownerCall(world.calls, created)?.securityInfo ?? 0) & OWNER_SECURITY_INFORMATION).toBe(OWNER_SECURITY_INFORMATION)

      world.calls.length = 0
      let securedWhileEmpty = false
      world.onSecurity = (call) => {
        if (!call.path.endsWith('.tmp') || call.owner !== world.observedUserSid || (call.securityInfo & OWNER_SECURITY_INFORMATION) === 0) {
          return
        }
        // 属主位生效时临时文件必须仍是空文件：敏感 fixture 还不能落盘。
        expect(readFileSync(call.path).length).toBe(0)
        securedWhileEmpty = true
      }
      await writePrivateFile(join(created, 'secret'), 'owner-order-fixture')
      expect(securedWhileEmpty).toBe(true)
      expect(readFileSync(join(created, 'secret'), 'utf8')).toBe('owner-order-fixture')
    } finally {
      rmSync(workdir, { recursive: true, force: true })
      world.release()
    }
  })

  test('an existing current-user directory keeps its owner while a foreign directory is rejected unchanged', async () => {
    const world = await ownerWorld()
    const workdir = mkdtempSync(join(tmpdir(), 'kk-studio-native-owner-'))
    try {
      const existing = join(workdir, 'existing')
      mkdirSync(existing)
      await expect(ensurePrivateDirectory(existing)).resolves.toBe(existing)
      expect(world.calls).toEqual([{ path: existing, securityInfo: expect.any(Number), owner: null }])
      expect(world.calls[0]?.securityInfo & OWNER_SECURITY_INFORMATION).toBe(0)

      world.calls.length = 0
      world.foreignPaths.add(join(workdir, 'foreign'))
      const foreign = join(workdir, 'foreign')
      mkdirSync(foreign)
      await expect(ensurePrivateDirectory(foreign)).rejects.toThrow(/not owned by the current user/)
      expect(world.calls).toEqual([])
    } finally {
      rmSync(workdir, { recursive: true, force: true })
      world.release()
    }
  })

  test('an EEXIST race accepts a real current-user directory but rejects a foreign owner or non-directory', async () => {
    // 只让指定叶子的 mkdir 报 EEXIST：父级仍真实创建，竞态对象由测试在失败前建好。
    const world = await ownerWorld()
    const workdir = mkdtempSync(join(tmpdir(), 'kk-studio-native-owner-'))
    const raced = new Set<string>()
    const realMkdir = actualFs.mkdirSync
    vi.mocked(mkdirSync).mockImplementation(((target: unknown, options: unknown) => {
      if (typeof target === 'string' && raced.has(target) && options === undefined) {
        if (target.endsWith('raced-file')) {
          writeFileSync(target, 'x')
        } else {
          realMkdir(target)
        }
        const error = new Error('exists') as NodeJS.ErrnoException
        error.code = 'EEXIST'
        throw error
      }
      return realMkdir(target as never, options as never)
    }) as typeof mkdirSync)
    try {
      const directory = join(workdir, 'raced-directory')
      raced.add(directory)
      await expect(ensurePrivateDirectory(directory)).resolves.toBe(directory)
      expect(ownerCall(world.calls, directory)?.owner).toBeNull()
      expect((ownerCall(world.calls, directory)?.securityInfo ?? 0) & OWNER_SECURITY_INFORMATION).toBe(0)

      world.calls.length = 0
      const foreign = join(workdir, 'raced-foreign')
      raced.add(foreign)
      world.foreignPaths.add(foreign)
      await expect(ensurePrivateDirectory(foreign)).rejects.toThrow(/not owned by the current user/)
      expect(world.calls).toEqual([])

      world.calls.length = 0
      const file = join(workdir, 'raced-file')
      raced.add(file)
      await expect(ensurePrivateDirectory(file)).rejects.toThrow(/must be a directory/)
      expect(world.calls).toEqual([])
      expect(lstatSync(file).isFile()).toBe(true)
    } finally {
      vi.mocked(mkdirSync).mockImplementation(actualFs.mkdirSync)
      rmSync(workdir, { recursive: true, force: true })
      world.release()
    }
  })

  test('a new lock file records the immediate creation status and an existing foreign lock is not rewritten', async () => {
    const world = await ownerWorld()
    const workdir = mkdtempSync(join(tmpdir(), 'kk-studio-native-owner-'))
    try {
      const created = join(workdir, 'created')
      await ensurePrivateDirectory(created)
      world.calls.length = 0
      world.lockOpenStatus = 0
      const lockFile = `${created}\\daemon.lock`
      writeFileSync(lockFile, '')
      const createdLock = await lockDirectory(created)
      await createdLock.close()
      const createdLockCall = ownerCall(world.calls, lockFile)
      expect(createdLockCall?.owner).toBe(world.observedUserSid)
      expect((createdLockCall?.securityInfo ?? 0) & OWNER_SECURITY_INFORMATION).toBe(OWNER_SECURITY_INFORMATION)

      world.calls.length = 0
      world.lockOpenStatus = ERROR_ALREADY_EXISTS
      await expect(lockDirectory(created)).rejects.toThrow(/not owned by the current user/)
      expect(world.calls.filter((call) => call.path === lockFile)).toEqual([])
    } finally {
      rmSync(workdir, { recursive: true, force: true })
      world.release()
    }
  })
})

interface OwnerCall {
  path: string
  securityInfo: number
  owner: bigint | null
}

interface OwnerWorld {
  calls: OwnerCall[]
  foreignPaths: Set<string>
  lockOpenStatus: number
  observedUserSid: bigint | null
  onSecurity: ((call: OwnerCall) => void) | null
  release(): void
}

/** 用真实 Koffi 内存驱动属主/DACL 读回；属主是否外部由路径集合控制，不放宽生产比较。 */
async function ownerWorld(): Promise<OwnerWorld> {
  const koffi = await import('koffi')
  const aclType = koffi.struct({ AclRevision: 'uint8_t', Sbz1: 'uint8_t', AclSize: 'uint16_t', AceCount: 'uint16_t', Sbz2: 'uint16_t' })
  const aceHeaderType = koffi.struct({ AceType: 'uint8_t', AceFlags: 'uint8_t', AceSize: 'uint16_t' })
  const tokenUserType = koffi.struct({ User: koffi.struct({ Sid: 'void *', Attributes: 'uint32_t' }) })
  const aclPointer = koffi.alloc('char', 8) as bigint
  koffi.encode(aclPointer, aclType, { AclRevision: 2, Sbz1: 0, AclSize: 8, AceCount: 1, Sbz2: 0 })
  const acePointer = koffi.alloc('char', 16) as bigint
  const currentSid = acePointer + 8n
  const world: OwnerWorld = {
    calls: [],
    foreignPaths: new Set<string>(),
    lockOpenStatus: 0,
    observedUserSid: null,
    onSecurity: null,
    release: () => {
      koffi.free(aclPointer)
      koffi.free(acePointer)
    },
  }
  installWindowsNativeBindingsForTest({
    kernel: {
      GetFileAttributesW: () => 0x80,
      LocalFree: () => null,
      GetLastError: () => world.lockOpenStatus,
      GetCurrentProcess: () => 9n,
      CloseHandle: () => 1,
      CreateFileW: () => 6n,
      LockFileEx: () => 1,
      UnlockFileEx: () => 1,
    },
    advapi: {
      GetNamedSecurityInfoW: (name: string, _type: number, _info: number, owner: bigint[], _g: unknown, dacl: bigint[], _s: unknown, descriptor: bigint[]) => {
        const foreign = world.foreignPaths.has(name) || (name.endsWith('daemon.lock') && world.lockOpenStatus === ERROR_ALREADY_EXISTS)
        owner[0] = foreign ? FOREIGN_SID : currentSid
        dacl[0] = aclPointer
        descriptor[0] = DESCRIPTOR_POINTER
        return 0
      },
      SetNamedSecurityInfoW: (name: string, _type: number, securityInfo: number, owner: bigint | null) => {
        const call = { path: name, securityInfo, owner }
        world.calls.push(call)
        if (owner !== null) {
          world.observedUserSid = owner
        }
        world.onSecurity?.(call)
        return 0
      },
      OpenProcessToken: (_process: unknown, _access: number, token: bigint[]) => {
        token[0] = 0x4000n
        return 1
      },
      GetTokenInformation: (_token: unknown, _cls: number, info: Buffer | null, _len: number, required: number[]) => {
        required[0] = 16
        if (info === null) {
          return 0
        }
        info.writeBigUInt64LE(currentSid, 0)
        return 1
      },
      InitializeAcl: () => 1,
      AddAccessAllowedAceEx: () => 1,
      GetLengthSid: () => 12,
      EqualSid: (first: bigint, second: bigint) => (first === currentSid && second === currentSid ? 1 : 0),
      GetAce: (_acl: unknown, _index: number, ace: bigint[]) => {
        ace[0] = acePointer
        return 1
      },
    },
    types: {
      startupInfoEx: { size: 112 },
      jobExtendedLimit: { size: 144 },
      jobBasicAccounting: { size: 48 },
      sizeBox: { size: 8 },
      acl: aclType,
      aceHeader: aceHeaderType,
      tokenUser: tokenUserType,
    },
  } as unknown as WindowsNativeBindings)
  return world
}

function ownerCall(calls: OwnerCall[], path: string): OwnerCall | undefined {
  return calls.find((call) => call.path === path)
}
