import { afterEach, describe, expect, it } from 'vitest'
import { mkdtemp, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { PassThrough, Readable } from 'node:stream'
import type { CapabilityResult, ManagedProcess, ProcessExit, ProcessSpec, ProcessSupervisor } from '../../src/contracts.js'
import { processCapability } from '../../src/coding/process.js'
import { callCapability, makeContext, textOf } from './helpers.js'

const tempDirs: string[] = []

async function tempDir(): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), 'kk-proc-'))
  // realpath 失败时，afterEach 仍持有已创建目录的清理责任。
  tempDirs.push(dir)
  return await realpath(dir)
}

afterEach(async () => {
  await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })))
})

interface FakeOptions {
  stdout?: string
  stderr?: string
  exitCode?: number | null
  signal?: string | null
  /** 为 true 时进程只有在 terminate 之后才退出（用于超时/取消）。 */
  holdUntilTerminate?: boolean
}

interface FakeHandle {
  process: ManagedProcess
  spec: ProcessSpec
  terminated: () => boolean
}

/** 构造一个行为受控的 ManagedProcess：流式输出 + 可收敛的退出。 */
function fakeManagedProcess(options: FakeOptions): FakeHandle {
  const stdout = options.holdUntilTerminate
    ? new Readable({ read() {} })
    : Readable.from(options.stdout === undefined ? [] : [Buffer.from(options.stdout)])
  const stderr = options.holdUntilTerminate
    ? new Readable({ read() {} })
    : Readable.from(options.stderr === undefined ? [] : [Buffer.from(options.stderr)])

  let resolveExit!: (exit: ProcessExit) => void
  let settled = false
  const exit = new Promise<ProcessExit>((resolve) => {
    resolveExit = (value) => {
      if (!settled) {
        settled = true
        resolve(value)
      }
    }
  })
  let terminated = false
  if (!options.holdUntilTerminate) {
    let remaining = 2
    const onEnd = (): void => {
      remaining--
      if (remaining === 0) {
        resolveExit({
          exitCode: options.exitCode !== undefined ? options.exitCode : 0,
          signal: options.signal ?? null,
        })
      }
    }
    stdout.on('end', onEnd)
    stderr.on('end', onEnd)
  }

  const process: ManagedProcess = {
    pid: 4242,
    stdin: new PassThrough(),
    stdout,
    stderr,
    exit,
    async terminate() {
      terminated = true
      stdout.push(null)
      stderr.push(null)
      resolveExit({
        exitCode: options.exitCode !== undefined ? options.exitCode : null,
        signal: options.signal ?? 'SIGTERM',
      })
    },
  }
  return {
    process,
    spec: { executable: '', args: [], cwd: '' },
    terminated: () => terminated,
  }
}

/** 单进程 fake supervisor：记录 spec，返回预置的进程句柄。 */
function fakeSupervisor(
  handle: FakeHandle | (() => FakeHandle),
  options?: { onStart?: (spec: ProcessSpec) => void },
) {
  const started: ProcessSpec[] = []
  return {
    started,
    start: async (spec: ProcessSpec): Promise<ManagedProcess> => {
      started.push(spec)
      options?.onStart?.(spec)
      const resolved = typeof handle === 'function' ? handle() : handle
      resolved.spec = spec
      return resolved.process
    },
    close: async () => {},
  }
}

describe('process.exec', () => {
  it('成功执行并合并 stdout/stderr', async () => {
    const dir = await tempDir()
    const handle = fakeManagedProcess({ stdout: 'out\n', stderr: 'err\n', exitCode: 0 })
    const supervisor = fakeSupervisor(handle)
    const result = await callCapability(
      processCapability,
      'process.exec',
      { command: 'echo hi', workdir: dir },
      makeContext(dir, { processes: supervisor }),
    )
    expect(result.error).toBe(false)
    expect(textOf(result)).toContain('out')
    expect(textOf(result)).toContain('err')
    expect(result.details).toMatchObject({ process: { outcome: 'EXITED', exitCode: 0 } })
    expect(supervisor.started[0]).toMatchObject({ args: ['-lc', 'echo hi'], cwd: dir })
  })

  it('非零退出保留输出并标注退出码', async () => {
    const dir = await tempDir()
    const handle = fakeManagedProcess({ stdout: 'boom\n', exitCode: 2 })
    const result = await callCapability(
      processCapability,
      'process.exec',
      { command: 'false', workdir: dir },
      makeContext(dir, { processes: fakeSupervisor(handle) }),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('boom')
    expect(textOf(result)).toContain('[Command exited with code 2.]')
    expect(result.details).toMatchObject({ process: { outcome: 'EXITED', exitCode: 2 } })
  })

  it('大输出外化为 durable 全文', async () => {
    const dir = await tempDir()
    const handle = fakeManagedProcess({ stdout: 'x'.repeat(60_000), exitCode: 0 })
    const result = await callCapability(
      processCapability,
      'process.exec',
      { command: 'yes', workdir: dir },
      makeContext(dir, { processes: fakeSupervisor(handle) }),
    )
    const textOutput = (result.details as { textOutput?: { path?: string; totalBytes?: number } })
      .textOutput
    expect(textOutput?.path?.endsWith('.log')).toBe(true)
    expect(textOutput?.totalBytes).toBe(60_000)
  })
  /**
   * 验证直接调用 processCapability 时的超时处理：
   * 执行器本身不自建内部定时器，由调用方通过 signal 发出 TIMED_OUT 终止信号；
   * 测试通过 started 屏障后显式 abort('TIMED_OUT')，验证进程能够收敛并返回 outcome 为 TIMED_OUT 的结果。
   */
  it('超时终止并给出稳定说明', async () => {
    const dir = await tempDir()
    const controller = new AbortController()
    const handle = fakeManagedProcess({ holdUntilTerminate: true })
    const started = Promise.withResolvers<void>()
    const supervisor = fakeSupervisor(handle, { onStart: () => started.resolve() })
    const pending = callCapability(
      processCapability,
      'process.exec',
      { command: 'sleep 100', workdir: dir },
      makeContext(dir, { processes: supervisor, signal: controller.signal }),
      30,
    )
    await started.promise
    controller.abort('TIMED_OUT')
    const result = await pending
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('timed out after 30 ms')
    expect(result.details).toMatchObject({ process: { outcome: 'TIMED_OUT' } })
    expect(handle.terminated()).toBe(true)
  })

  it('direct process 不自行起 timer：未收到 signal 时按正常流程完成并保留结果', async () => {
    const dir = await tempDir()
    const handle = fakeManagedProcess({ stdout: 'done\n', exitCode: 0 })
    const supervisor = fakeSupervisor(handle)
    const result = await callCapability(
      processCapability,
      'process.exec',
      { command: 'echo done', workdir: dir },
      makeContext(dir, { processes: supervisor }),
      30,
    )
    expect(result.error).toBe(false)
    expect(textOf(result)).toContain('done')
    expect(result.details).toMatchObject({ process: { outcome: 'EXITED', exitCode: 0 } })
  })

  /** 验证启动前已被 TIMED_OUT 中止时直接返回 TIMED_OUT 结果且不启动 shell 进程。 */
  it('启动前已超时不启动进程', async () => {
    const dir = await tempDir()
    const controller = new AbortController()
    controller.abort('TIMED_OUT')
    const supervisor = fakeSupervisor(fakeManagedProcess({ exitCode: 0 }))
    const result = await callCapability(
      processCapability,
      'process.exec',
      { command: 'echo hi', workdir: dir },
      makeContext(dir, { processes: supervisor, signal: controller.signal }),
      30,
    )
    expect(result.error).toBe(true)
    expect(supervisor.started).toEqual([])
    expect(result.details).toMatchObject({ process: { outcome: 'TIMED_OUT' } })
    expect(textOf(result)).toContain('timed out after 30 ms')
  })

  it('取消终止并返回 cancelled 结果', async () => {
    const dir = await tempDir()
    const controller = new AbortController()
    const handle = fakeManagedProcess({ holdUntilTerminate: true })
    const started = Promise.withResolvers<void>()
    const supervisor = fakeSupervisor(handle, { onStart: () => started.resolve() })
    const pending = callCapability(
      processCapability,
      'process.exec',
      { command: 'sleep 100', workdir: dir },
      makeContext(dir, { processes: supervisor, signal: controller.signal }),
    )
    await started.promise
    controller.abort()
    const settled = await pending
    expect(settled.error).toBe(true)
    expect(textOf(settled)).toContain('Operation cancelled by the caller')
    expect(settled.details).toMatchObject({ process: { outcome: 'CANCELLED' } })
    expect(handle.terminated()).toBe(true)
  })

  it('启动 await 期间 abort 且 terminate 失败必须返回 convergenceFailure，绝不假称已取消或已收敛', async () => {
    const dir = await tempDir()
    const controller = new AbortController()

    const startEntered = Promise.withResolvers<void>()
    const allowStartReturn = Promise.withResolvers<void>()

    const failedProcess: ManagedProcess = {
      pid: 1234,
      stdin: new PassThrough(),
      stdout: Readable.from([]),
      stderr: Readable.from([]),
      exit: new Promise<ProcessExit>(() => {}),
      terminate: async () => {
        throw new Error('sigkill failed: process immune')
      },
    }

    const supervisor: ProcessSupervisor = {
      start: async () => {
        startEntered.resolve()
        await allowStartReturn.promise
        return failedProcess
      },
      close: async () => {},
    }

    let confirmations = 0
    const pending = callCapability(
      processCapability,
      'process.exec',
      { command: 'sleep 100', workdir: dir },
      {
        ...makeContext(dir, { processes: supervisor, signal: controller.signal }),
        confirmExecutionExit: () => {
          confirmations += 1
        },
      },
      50,
    )

    await startEntered.promise
    controller.abort('TIMED_OUT')
    allowStartReturn.resolve()

    const result = await pending
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('the process scope did not converge; processes may still be running')
    expect(result.details).toMatchObject({
      code: 'FAILED',
    })
    expect(confirmations).toBe(0)
  })

  it('启动 await 期间 abort 且 terminate 成功收敛返回 terminatedBeforeStart', async () => {
    const dir = await tempDir()
    const controller = new AbortController()

    const startEntered = Promise.withResolvers<void>()
    const allowStartReturn = Promise.withResolvers<void>()
    let terminated = false
    let confirmations = 0

    const successfulProcess: ManagedProcess = {
      pid: 1234,
      stdin: new PassThrough(),
      stdout: Readable.from([]),
      stderr: Readable.from([]),
      exit: new Promise<ProcessExit>(() => {}),
      terminate: async () => {
        terminated = true
      },
    }

    const supervisor: ProcessSupervisor = {
      start: async () => {
        startEntered.resolve()
        await allowStartReturn.promise
        return successfulProcess
      },
      close: async () => {},
    }

    const pending = callCapability(
      processCapability,
      'process.exec',
      { command: 'sleep 100', workdir: dir },
      {
        ...makeContext(dir, { processes: supervisor, signal: controller.signal }),
        confirmExecutionExit: () => {
          confirmations += 1
        },
      },
      50,
    )

    await startEntered.promise
    controller.abort('TIMED_OUT')
    allowStartReturn.resolve()

    const result = await pending
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ process: { outcome: 'TIMED_OUT' } })
    expect(textOf(result)).toContain('timed out after 50 ms')
    expect(terminated).toBe(true)
    expect(confirmations).toBe(1)
  })

  it('ACKs after a natural exit has been converged and captured output closed', async () => {
    // 正向对照：进程真实退出且 terminate 与本地捕获收尾都成功后确认一次。
    const dir = await tempDir()
    let confirmations = 0
    const result = await callCapability(
      processCapability,
      'process.exec',
      { command: 'echo hi', workdir: dir },
      {
        ...makeContext(dir, { processes: fakeSupervisor(fakeManagedProcess({ stdout: 'hi\n', exitCode: 0 })) }),
        confirmExecutionExit: () => {
          confirmations += 1
        },
      },
    )
    expect(result.error).toBe(false)
    expect(confirmations).toBe(1)
  })

  it('does not ACK when start throws before a managed process exists', async () => {
    // start 抛错只说明没有返回受管进程，不证明状态目录或 helper 已清理。
    const dir = await tempDir()
    let confirmations = 0
    const supervisor: ProcessSupervisor = {
      start: async () => {
        throw new Error('spawn failed after creating the state directory')
      },
      close: async () => {},
    }
    const result = await callCapability(
      processCapability,
      'process.exec',
      { command: 'echo hi', workdir: dir },
      {
        ...makeContext(dir, { processes: supervisor }),
        confirmExecutionExit: () => {
          confirmations += 1
        },
      },
    )
    expect(result.error).toBe(true)
    expect(confirmations).toBe(0)
  })

  it('ACKs a rejection that happens before start is attempted', async () => {
    // 正向对照：workdir 在调用 start 前就被拒绝，执行范围确定未创建。
    const dir = await tempDir()
    let confirmations = 0
    const supervisor = fakeSupervisor(fakeManagedProcess({ exitCode: 0 }))
    const result = await callCapability(
      processCapability,
      'process.exec',
      { command: 'echo hi', workdir: 'relative' },
      {
        ...makeContext(dir, { processes: supervisor }),
        confirmExecutionExit: () => {
          confirmations += 1
        },
      },
    )
    expect(result.error).toBe(true)
    expect(supervisor.started).toEqual([])
    expect(confirmations).toBe(1)
  })

  it('启动抛出异常且在此期间已 abort 时返回明确的终止说明', async () => {
    const dir = await tempDir()
    const controller = new AbortController()
    let confirmations = 0
    const supervisor: ProcessSupervisor = {
      start: async () => {
        controller.abort()
        throw new Error('spawn failed')
      },
      close: async () => {},
    }
    const result = await callCapability(
      processCapability,
      'process.exec',
      { command: 'echo hi', workdir: dir },
      {
        ...makeContext(dir, { processes: supervisor, signal: controller.signal }),
        confirmExecutionExit: () => {
          confirmations += 1
        },
      },
    )
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ process: { outcome: 'CANCELLED' } })
    // 取消不能把不确定的 start 清理变成退出证据。
    expect(confirmations).toBe(0)
  })

  it('进程被外部信号杀死时保留输出并记录 signal 说明', async () => {
    const dir = await tempDir()
    const handle = fakeManagedProcess({ stdout: 'partial\n', exitCode: null, signal: 'SIGKILL' })
    const result = await callCapability(
      processCapability,
      'process.exec',
      { command: 'echo partial', workdir: dir },
      makeContext(dir, { processes: fakeSupervisor(handle) }),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('partial')
    expect(textOf(result)).toContain('[Command was terminated by signal SIGKILL.]')
    expect(result.details).toMatchObject({ process: { outcome: 'EXITED', signal: 'SIGKILL' } })
  })

  it('启动前已取消不启动进程', async () => {
    const dir = await tempDir()
    const controller = new AbortController()
    controller.abort()
    const supervisor = fakeSupervisor(fakeManagedProcess({ exitCode: 0 }))
    const result = await callCapability(
      processCapability,
      'process.exec',
      { command: 'echo hi', workdir: dir },
      makeContext(dir, { processes: supervisor, signal: controller.signal }),
    )
    expect(result.error).toBe(true)
    expect(supervisor.started).toEqual([])
    expect(result.details).toMatchObject({ process: { outcome: 'CANCELLED' } })
  })

  it('无效 workdir 在任何进程启动前被拒绝', async () => {
    const dir = await tempDir()
    const supervisor = fakeSupervisor(fakeManagedProcess({ exitCode: 0 }))
    const context = makeContext(dir, { processes: supervisor })
    const empty = await callCapability(
      processCapability,
      'process.exec',
      { command: 'echo hi', workdir: '   ' },
      context,
    )
    expect(empty.error).toBe(true)
    expect(textOf(empty)).toContain('workdir is required')

    const relative = await callCapability(
      processCapability,
      'process.exec',
      { command: 'echo hi', workdir: 'relative' },
      context,
    )
    expect(relative.error).toBe(true)
    expect(textOf(relative)).toContain('workdir must be an absolute path')

    const file = join(dir, 'f.txt')
    await writeFile(file, 'x')
    const notDir = await callCapability(
      processCapability,
      'process.exec',
      { command: 'echo hi', workdir: file },
      context,
    )
    expect(textOf(notDir)).toContain('workdir is not a directory')

    const missing = await callCapability(
      processCapability,
      'process.exec',
      { command: 'echo hi', workdir: join(dir, 'nope') },
      context,
    )
    expect(textOf(missing)).toContain('workdir does not exist')
    expect(supervisor.started).toEqual([])
  })

  it('缺少 command 被拒绝', async () => {
    const dir = await tempDir()
    const result = await callCapability(
      processCapability,
      'process.exec',
      { workdir: dir },
      makeContext(dir, { processes: fakeSupervisor(fakeManagedProcess({ exitCode: 0 })) }),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('command is required')
  })

  it('shell 启动失败声明未执行', async () => {
    const dir = await tempDir()
    const supervisor = {
      start: async (): Promise<ManagedProcess> => {
        throw new Error('spawn failed')
      },
      close: async () => {},
    }
    const result = await callCapability(
      processCapability,
      'process.exec',
      { command: 'echo hi', workdir: dir },
      makeContext(dir, { processes: supervisor }),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('the shell could not be started')
    expect(textOf(result)).toContain('was not executed')
  })

  it.each(['CANCELLED', 'TIMED_OUT'] as const)('启动 await 期间的 %s 被围栏并收敛', async (reason) => {
    const dir = await tempDir()
    const controller = new AbortController()
    const handle = fakeManagedProcess({ holdUntilTerminate: true })
    const started = Promise.withResolvers<void>()
    const allowStartReturn = Promise.withResolvers<void>()
    let startedSpec: ProcessSpec | undefined
    const supervisor = {
      start: async (spec: ProcessSpec): Promise<ManagedProcess> => {
        startedSpec = spec
        started.resolve()
        await allowStartReturn.promise
        return handle.process
      },
      close: async () => {},
    }
    const pending = callCapability(
      processCapability,
      'process.exec',
      { command: 'x', workdir: dir },
      makeContext(dir, { processes: supervisor, signal: controller.signal }),
    )
    try {
      await started.promise
      controller.abort(reason)
      allowStartReturn.resolve()
      const result = await pending
      expect(result.error).toBe(true)
      expect(result.details).toMatchObject({ process: { outcome: reason } })
      // start 收到停止信号，且放行后立即收敛，不留下运行中的 shell。
      expect(startedSpec?.signal).toBe(controller.signal)
      expect(handle.terminated()).toBe(true)
    } finally {
      controller.abort(reason)
      allowStartReturn.resolve()
    }
  })

  it('live partial 携带精确区间', async () => {
    const dir = await tempDir()
    const progress: CapabilityResult[] = []
    const handle = fakeManagedProcess({ stdout: 'y'.repeat(200_000), exitCode: 0 })
    await callCapability(
      processCapability,
      'process.exec',
      { command: 'yes', workdir: dir },
      makeContext(dir, {
        processes: fakeSupervisor(handle),
        progress: async (result) => {
          progress.push(result)
        },
      }),
    )
    const appends = progress.filter(
      (item) => (item.details as { mode?: string }).mode === 'APPEND',
    )
    expect(appends.length).toBeGreaterThan(1)
    expect((appends[0]?.details as { startOffset?: number }).startOffset).toBe(0)
    expect((appends[0]?.details as { kind?: string }).kind).toBe('process.output')
  })

  it('progress 投递失败不停止读取流', async () => {
    const dir = await tempDir()
    const handle = fakeManagedProcess({ stdout: 'z'.repeat(200_000), exitCode: 0 })
    const result = await callCapability(
      processCapability,
      'process.exec',
      { command: 'yes', workdir: dir },
      makeContext(dir, {
        processes: fakeSupervisor(handle),
        progress: async () => {
          throw new Error('progress channel down')
        },
      }),
    )
    // live 通知失败只抑制通知：命令仍自然退出并给出完整捕获事实。
    expect(result.error).toBe(false)
    expect(result.details).toMatchObject({ process: { outcome: 'EXITED', exitCode: 0 } })
    expect((result.details as { textOutput?: { totalBytes?: number } }).textOutput?.totalBytes).toBe(
      200_000,
    )
  })

  it('收敛失败返回 FAILED 而非成功', async () => {
    const dir = await tempDir()
    const stdout = Readable.from([Buffer.from('boom\n')])
    const stderr = Readable.from([])
    const exit = Promise.resolve<ProcessExit>({ exitCode: 0, signal: null })
    const proc: ManagedProcess = {
      pid: 7,
      stdin: new PassThrough(),
      stdout,
      stderr,
      exit,
      async terminate() {
        throw new Error('scope did not converge')
      },
    }
    const result = await callCapability(
      processCapability,
      'process.exec',
      { command: 'x', workdir: dir },
      makeContext(dir, { processes: { start: async () => proc, close: async () => {} } }),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('did not converge')
  })

  it('自然读取 EIO 返回 FAILED 并保留已读前缀', async () => {
    const dir = await tempDir()
    const stdout = new Readable({ read() {} })
    stdout.push(Buffer.from('prefix\n' + 'x'.repeat(70_000) + '\n'))
    const stderr = Readable.from([])
    const exit = Promise.withResolvers<ProcessExit>()
    const capturedProgress = Promise.withResolvers<void>()
    const proc: ManagedProcess = {
      pid: 9,
      stdin: new PassThrough(),
      stdout,
      stderr,
      exit: exit.promise,
      async terminate() {
        stdout.destroy()
        exit.resolve({ exitCode: 0, signal: null })
      },
    }
    const pending = callCapability(
      processCapability,
      'process.exec',
      { command: 'x', workdir: dir },
      makeContext(dir, {
        processes: { start: async () => proc, close: async () => {} },
        progress: async () => {
          capturedProgress.resolve()
        },
      }),
    )
    try {
      // 确认前缀已捕获后再触发读取错误，避免与宿主调度及进程退出速度竞态。
      await capturedProgress.promise
      stdout.destroy(new Error('EIO'))
      exit.resolve({ exitCode: 0, signal: null })
      const result = await pending
      expect(result.error).toBe(true)
      expect(textOf(result)).toContain('prefix')
      expect(textOf(result)).toContain('failed while reading')
    } finally {
      stdout.destroy()
      exit.resolve({ exitCode: 0, signal: null })
    }
  })

  it('取消导致的 premature close 仍为 CANCELLED 且保留前缀', async () => {
    const dir = await tempDir()
    const controller = new AbortController()
    const stdout = new Readable({ read() {} })
    const stderr = new Readable({ read() {} })
    stdout.on('error', () => {})
    stderr.on('error', () => {})

    let resolveExit!: (exit: ProcessExit) => void
    const exit = new Promise<ProcessExit>((resolve) => {
      resolveExit = resolve
    })
    const proc: ManagedProcess = {
      pid: 11,
      stdin: new PassThrough(),
      stdout,
      stderr,
      exit,
      async terminate() {
        stdout.destroy(new Error('premature close'))
        stderr.destroy(new Error('premature close'))
        resolveExit({ exitCode: null, signal: 'SIGTERM' })
      },
    }
    const capturedProgress = Promise.withResolvers<void>()
    const pending = callCapability(
      processCapability,
      'process.exec',
      { command: 'x', workdir: dir },
      makeContext(dir, {
        processes: {
          start: async () => proc,
          close: async () => {},
        },
        signal: controller.signal,
        progress: async () => {
          capturedProgress.resolve()
        },
      }),
    )

    // 让 stdout 先推送数据，等待 progress 捕获屏障后再 abort
    stdout.push(Buffer.from('keep\n' + 'x'.repeat(70_000) + '\n'))
    await capturedProgress.promise
    controller.abort()

    const result = await pending
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ process: { outcome: 'CANCELLED' } })
    expect(textOf(result)).toContain('keep')
  })

  it('流式输出在 64KB 边界处正确对齐 2/3/4 字节 UTF-8 字符', async () => {
    const dir = await tempDir()
    for (const char of ['é', '中', '🚀']) {
      const padding = 'a'.repeat(65535)
      const combined = padding + char + '\n'
      const handle = fakeManagedProcess({ stdout: combined, exitCode: 0 })
      const supervisor = fakeSupervisor(handle)
      const result = await callCapability(
        processCapability,
        'process.exec',
        { command: 'echo', workdir: dir },
        makeContext(dir, { processes: supervisor }),
      )
      expect(result.error).toBe(false)
      expect(textOf(result)).toContain(char)
    }
  })
})
