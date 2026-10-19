import { afterEach, describe, expect, it } from 'vitest'
import { mkdtemp, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { PassThrough, Readable } from 'node:stream'
import type { CapabilityResult, ManagedProcess, ProcessExit, ProcessSpec } from '../../src/contracts.js'
import { processCapability } from '../../src/coding/process.js'
import { callCapability, makeContext, textOf } from './helpers.js'

const tempDirs: string[] = []

async function tempDir(): Promise<string> {
  const dir = await realpath(await mkdtemp(join(tmpdir(), 'kk-proc-')))
  tempDirs.push(dir)
  return dir
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
        resolveExit({ exitCode: options.exitCode ?? 0, signal: options.signal ?? null })
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
      resolveExit({ exitCode: options.exitCode ?? null, signal: options.signal ?? 'SIGTERM' })
    },
  }
  return {
    process,
    spec: { executable: '', args: [], cwd: '' },
    terminated: () => terminated,
  }
}

/** 单进程 fake supervisor：记录 spec，返回预置的进程句柄。 */
function fakeSupervisor(handle: FakeHandle | (() => FakeHandle)) {
  const started: ProcessSpec[] = []
  return {
    started,
    start: async (spec: ProcessSpec): Promise<ManagedProcess> => {
      started.push(spec)
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
   * 执行器本身不再自建内部定时器，由调用方通过 signal 发出 TIMED_OUT 终止信号；
   * 测试通过局部 AbortController 显式 abort('TIMED_OUT')，验证进程能够收敛并返回 outcome 为 TIMED_OUT 的结果。
   */
  it('超时终止并给出稳定说明', async () => {
    const dir = await tempDir()
    const controller = new AbortController()
    const handle = fakeManagedProcess({ holdUntilTerminate: true })
    const supervisor = fakeSupervisor(handle)
    const pending = callCapability(
      processCapability,
      'process.exec',
      { command: 'sleep 100', workdir: dir },
      makeContext(dir, { processes: supervisor, signal: controller.signal }),
      30,
    )
    setTimeout(() => controller.abort('TIMED_OUT'), 10)
    const result = await pending
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('timed out after 30 ms')
    expect(result.details).toMatchObject({ process: { outcome: 'TIMED_OUT' } })
    expect(handle.terminated()).toBe(true)
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
    const pending = callCapability(
      processCapability,
      'process.exec',
      { command: 'sleep 100', workdir: dir },
      makeContext(dir, { processes: fakeSupervisor(handle), signal: controller.signal }),
    )
    // 让进程进入排空阶段后取消。
    setTimeout(() => controller.abort(), 10)
    const settled = await pending
    expect(settled.error).toBe(true)
    expect(textOf(settled)).toContain('Operation cancelled by the caller')
    expect(settled.details).toMatchObject({ process: { outcome: 'CANCELLED' } })
    expect(handle.terminated()).toBe(true)
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

  it('启动 await 期间的取消被围栏并收敛', async () => {
    const dir = await tempDir()
    const controller = new AbortController()
    const handle = fakeManagedProcess({ holdUntilTerminate: true })
    let startedSpec: ProcessSpec | undefined
    const supervisor = {
      start: async (spec: ProcessSpec): Promise<ManagedProcess> => {
        startedSpec = spec
        await new Promise((resolve) => setTimeout(resolve, 20))
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
    setTimeout(() => controller.abort(), 5)
    const result = await pending
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ process: { outcome: 'CANCELLED' } })
    // start 收到取消信号，且放行后立即收敛，不留下运行中的 shell。
    expect(startedSpec?.signal).toBe(controller.signal)
    expect(handle.terminated()).toBe(true)
  })

  /** 验证在 processes.start 的 await 期间发生超时，进程同样被围栏截断并收敛。 */
  it('启动 await 期间的超时被围栏并收敛', async () => {
    const dir = await tempDir()
    const controller = new AbortController()
    const handle = fakeManagedProcess({ holdUntilTerminate: true })
    let startedSpec: ProcessSpec | undefined
    const supervisor = {
      start: async (spec: ProcessSpec): Promise<ManagedProcess> => {
        startedSpec = spec
        await new Promise((resolve) => setTimeout(resolve, 20))
        return handle.process
      },
      close: async () => {},
    }
    const pending = callCapability(
      processCapability,
      'process.exec',
      { command: 'x', workdir: dir },
      makeContext(dir, { processes: supervisor, signal: controller.signal }),
      30,
    )
    setTimeout(() => controller.abort('TIMED_OUT'), 5)
    const result = await pending
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ process: { outcome: 'TIMED_OUT' } })
    expect(startedSpec?.signal).toBe(controller.signal)
    expect(handle.terminated()).toBe(true)
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
    const exit = new Promise<ProcessExit>((resolve) => setTimeout(() => resolve({ exitCode: 0, signal: null }), 10))
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
    stdout.push(Buffer.from('prefix\n'))
    const stderr = Readable.from([])
    const exit = new Promise<ProcessExit>((resolve) =>
      setTimeout(() => resolve({ exitCode: 0, signal: null }), 30),
    )
    const proc: ManagedProcess = {
      pid: 9,
      stdin: new PassThrough(),
      stdout,
      stderr,
      exit,
      async terminate() {
        stdout.destroy()
      },
    }
    setTimeout(() => stdout.destroy(new Error('EIO')), 10)
    const result = await callCapability(
      processCapability,
      'process.exec',
      { command: 'x', workdir: dir },
      makeContext(dir, { processes: { start: async () => proc, close: async () => {} } }),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('prefix')
    expect(textOf(result)).toContain('failed while reading')
  })

  it('取消导致的 premature close 仍为 CANCELLED 且保留前缀', async () => {
    const dir = await tempDir()
    const controller = new AbortController()
    const stdout = new Readable({ read() {} })
    stdout.push(Buffer.from('keep\n'))
    const stderr = new Readable({ read() {} })
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
    const pending = callCapability(
      processCapability,
      'process.exec',
      { command: 'x', workdir: dir },
      makeContext(dir, {
        processes: { start: async () => proc, close: async () => {} },
        signal: controller.signal,
      }),
    )
    setTimeout(() => controller.abort(), 10)
    const result = await pending
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ process: { outcome: 'CANCELLED' } })
    expect(textOf(result)).toContain('keep')
  })
})
