import type { ChildProcessWithoutNullStreams } from 'node:child_process'
import { EventEmitter } from 'node:events'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { PassThrough } from 'node:stream'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import type { ManagedProcess, ProcessExit, ProcessSpec } from '../../src/contracts.js'

/**
 * 证明探针真的挂在测试进程实际加载的 `native/state` / `WindowsProcessSupervisor` 模块上，并且只根据真实观察分类。
 *
 * <p>这些用例只验证探针与分类逻辑，不断言任何 Windows 内核语义；它们可在 Linux 上运行。
 */
vi.mock('../../src/native/state.js', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/native/state.js')>()
  const { traceStateModule } = await import('./windows-process-diagnostics.js')
  return traceStateModule(actual)
})

vi.mock('../../src/native/process-supervisor-windows.js', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/native/process-supervisor-windows.js')>()
  const { traceWindowsSupervisorModule } = await import('./windows-process-diagnostics.js')
  return traceWindowsSupervisorModule(actual)
})

import * as state from '../../src/native/state.js'
import { WindowsProcessSupervisor } from '../../src/native/process-supervisor-windows.js'
import {
  configureWindowsProcessDiagnostics,
  observeManagedProcess,
  resetWindowsProcessDiagnostics,
  traceStateModule,
  traceWindowsSupervisorModule,
  windowsProcessDiagnosticFacts,
  windowsProcessDiagnosticHealth,
} from './windows-process-diagnostics.js'

interface Deferred<T> {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((accept, decline) => {
    resolve = accept
    reject = decline
  })
  return { promise, resolve, reject }
}

function fakeManaged(stateDirectory: string, exit: Promise<ProcessExit>): ManagedProcess & {
  stateDir: string
  settled: Promise<void>
} {
  return {
    stateDir: stateDirectory,
    pid: 4242,
    stdin: new PassThrough(),
    stdout: new PassThrough(),
    stderr: new PassThrough(),
    exit,
    terminate: async () => undefined,
    settled: Promise.resolve(),
  }
}

async function flushMicrotasks(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
}

function fakeEventHelper(pid = 4242): ChildProcessWithoutNullStreams {
  const helper = Object.assign(new EventEmitter(), {
    pid,
    stdin: new PassThrough(),
    stdout: new PassThrough(),
    stderr: new PassThrough(),
    kill: () => true,
  })
  return helper as unknown as ChildProcessWithoutNullStreams
}

let outputDirectory: string
let records: Array<Record<string, unknown>>

beforeEach(() => {
  outputDirectory = mkdtempSync(join(tmpdir(), 'kk-windows-diagnostic-'))
  records = []
  configureWindowsProcessDiagnostics({
    enabled: true,
    outputDirectory,
    label: 'unit',
    sink: (record) => {
      records.push(record)
    },
  })
})

afterEach(() => {
  resetWindowsProcessDiagnostics()
  rmSync(outputDirectory, { recursive: true, force: true })
})

describe('windows process diagnostic trace', () => {
  test('an observed exit report is classified as command-exit-report and snapshotted before deletion', async () => {
    const directory = await state.createStateDir()
    const exit = deferred<ProcessExit>()
    observeManagedProcess(fakeManaged(directory, exit.promise), {
      executable: 'C:\\tools\\node.exe',
      args: ['-e', 'ignored'],
      cwd: 'ignored',
    } satisfies ProcessSpec)

    // 真实 readExit（经探针）看到 helper 发布的报告后，exit promise 才落定。
    expect(state.readExit(directory)).toBeNull()
    state.publish(directory, state.SCOPE_FILE, '9876')
    state.publish(directory, state.EXIT_FILE, JSON.stringify({ exitCode: 8, signal: null }))
    const report = state.readExit(directory)
    expect(report).toEqual({ exitCode: 8, signal: null })
    exit.resolve(report)
    await flushMicrotasks()
    state.removeStateDir(directory)
    await flushMicrotasks()

    const facts = windowsProcessDiagnosticFacts(directory)
    expect(facts?.source).toBe('command-exit-report')
    expect(facts?.exitResult).toEqual({ exitCode: 8, signal: null })
    expect(facts?.executable).toBe('node.exe')
    expect(facts?.helperPid).toBe(4242)
    const beforeRemove = records.find((record) => record.kind === 'before-remove')
    expect(beforeRemove).toMatchObject({
      state: expect.any(String),
      sourceAtRemoval: 'command-exit-report',
      cleanupFacts: {
        scope: '9876',
        commandPid: 9876,
        exitReport: { exitCode: 8, signal: null },
        cleanup: null,
      },
    })
    const resolved = records.find((record) => record.kind === 'exit-resolved')
    expect(resolved).toMatchObject({ exitCode: 8, exitCodeHex: '0x8', source: 'command-exit-report' })
    // 敏感输入没有进入记录：ProcessSpec 的 args/cwd 从未被写入。
    expect(JSON.stringify(records)).not.toContain('ignored')
    expect(JSON.stringify(records)).not.toContain(directory)

    const jsonl = readFileSync(join(outputDirectory, `windows-process-main-${process.pid}.jsonl`), 'utf8')
      .trim()
      .split('\n')
      .map((line) => JSON.parse(line) as Record<string, unknown>)
    expect(jsonl.some((record) => record.kind === 'before-remove')).toBe(true)
  })

  test('no exit report but a real readExit call supports helper-close-fallback', async () => {
    const directory = await state.createStateDir()
    const exit = deferred<ProcessExit>()
    observeManagedProcess(fakeManaged(directory, exit.promise))
    expect(state.readExit(directory)).toBeNull()
    exit.resolve({ exitCode: 1, signal: null })
    await flushMicrotasks()
    state.removeStateDir(directory)
    await flushMicrotasks()

    expect(windowsProcessDiagnosticFacts(directory)?.source).toBe('helper-close-fallback')
    expect(records.find((record) => record.kind === 'before-remove')).toMatchObject({
      sourceAtRemoval: 'helper-close-fallback',
      cleanupFacts: { exitReport: null },
    })
  })

  test('an unread state directory stays unknown instead of inventing provenance', async () => {
    const directory = await state.createStateDir()
    const exit = deferred<ProcessExit>()
    observeManagedProcess(fakeManaged(directory, exit.promise))
    exit.resolve({ exitCode: 7, signal: null })
    await flushMicrotasks()
    state.removeStateDir(directory)
    await flushMicrotasks()

    expect(windowsProcessDiagnosticFacts(directory)?.source).toBe('unknown')
    expect(records.find((record) => record.kind === 'before-remove')).toMatchObject({
      sourceAtRemoval: 'unknown',
    })
  })

  test('a null readExit before the exit promise resolves stays unknown', async () => {
    const directory = await state.createStateDir()
    const exit = deferred<ProcessExit>()
    observeManagedProcess(fakeManaged(directory, exit.promise))
    expect(state.readExit(directory)).toBeNull()
    // 只有读取动作、没有落定的 exit 结果，不能证明实际走的是 helper close 回退。
    expect(windowsProcessDiagnosticFacts(directory)?.source).toBe('unknown')
    exit.resolve({ exitCode: 1, signal: null })
    await flushMicrotasks()
    state.removeStateDir(directory)
    await flushMicrotasks()
    expect(windowsProcessDiagnosticFacts(directory)?.source).toBe('helper-close-fallback')
  })

  test('a late incompatible report cannot mislabel the actual fallback result', async () => {
    const directory = await state.createStateDir()
    const exit = deferred<ProcessExit>()
    observeManagedProcess(fakeManaged(directory, exit.promise))
    expect(state.readExit(directory)).toBeNull()
    exit.resolve({ exitCode: 1, signal: null })
    await flushMicrotasks()
    // exit 已落定后出现的报告与真实结果不一致：不得改写成 command-exit-report。
    state.publish(directory, state.EXIT_FILE, JSON.stringify({ exitCode: 9, signal: null }))
    expect(state.readExit(directory)).toEqual({ exitCode: 9, signal: null })
    state.removeStateDir(directory)
    await flushMicrotasks()
    expect(windowsProcessDiagnosticFacts(directory)?.source).toBe('helper-close-fallback')
  })

  test('a malformed report is recorded as a read error and never overwrites the fallback status', async () => {
    const directory = await state.createStateDir()
    const exit = deferred<ProcessExit>()
    observeManagedProcess(fakeManaged(directory, exit.promise))
    state.publish(directory, state.EXIT_FILE, '{not-json')
    expect(() => state.readExit(directory)).toThrow(/unreadable/)
    exit.resolve({ exitCode: 0, signal: null })
    await flushMicrotasks()
    state.removeStateDir(directory)
    await flushMicrotasks()

    const facts = windowsProcessDiagnosticFacts(directory)
    expect(facts?.source).toBe('helper-close-fallback')
    expect(facts?.exitResult).toEqual({ exitCode: 0, signal: null })
    expect(records.find((record) => record.kind === 'read-exit-error')).toMatchObject({
      error: { errorType: 'Error' },
    })
  })

  test('a late matching report cannot rewrite already observed helper-close provenance', async () => {
    const directory = await state.createStateDir()
    const exit = deferred<ProcessExit>()
    observeManagedProcess(fakeManaged(directory, exit.promise))
    expect(state.readExit(directory)).toBeNull()
    exit.resolve({ exitCode: 0x80000004, signal: null })
    await flushMicrotasks()
    state.publish(directory, state.EXIT_FILE, JSON.stringify({ exitCode: 0x80000004, signal: null }))
    expect(state.readExit(directory)?.exitCode).toBe(0x80000004)
    state.removeStateDir(directory)
    expect(windowsProcessDiagnosticFacts(directory)?.source).toBe('helper-close-fallback')
  })

  test('the supervisor wrapper delegates start and observes the real returned managed process', async () => {
    class DelegatingSupervisor {
      readonly starts: ProcessSpec[] = []

      async start(spec: ProcessSpec): Promise<ManagedProcess> {
        this.starts.push(spec)
        const directory = await state.createStateDir()
        state.publish(directory, state.SCOPE_FILE, '77')
        const exit = deferred<ProcessExit>()
        const managed = fakeManaged(directory, exit.promise)
        state.publish(directory, state.EXIT_FILE, JSON.stringify({ exitCode: 0, signal: null }))
        expect(state.readExit(directory)).toEqual({ exitCode: 0, signal: null })
        exit.resolve({ exitCode: 0, signal: null })
        await flushMicrotasks()
        state.removeStateDir(directory)
        return managed
      }
    }

    const wrapped = traceWindowsSupervisorModule({
      WindowsProcessSupervisor: DelegatingSupervisor,
      marker: 1,
    } as unknown as typeof import('../../src/native/process-supervisor-windows.js'))
    expect(wrapped.marker).toBe(1)
    expect(wrapped.WindowsProcessSupervisor).not.toBe(DelegatingSupervisor)
    const supervisor = new wrapped.WindowsProcessSupervisor()
    const managed = await supervisor.start({ executable: 'C:\\tools\\node.exe', args: [], cwd: 'ignored' })
    await flushMicrotasks()

    expect((supervisor as unknown as DelegatingSupervisor).starts).toHaveLength(1)
    const facts = windowsProcessDiagnosticFacts((managed as unknown as { stateDir: string }).stateDir)
    expect(facts?.source).toBe('command-exit-report')
    expect(facts?.exitResult).toEqual({ exitCode: 0, signal: null })
  })

  test('the real WindowsManagedProcess consumes the mocked state module for a command exit report', async () => {
    const actual = await vi.importActual<typeof import('../../src/native/process-supervisor-windows.js')>(
      '../../src/native/process-supervisor-windows.js',
    )
    const directory = await state.createStateDir()
    state.publish(directory, state.SCOPE_FILE, '3210')
    state.publish(directory, state.EXIT_FILE, JSON.stringify({ exitCode: 7, signal: null }))
    state.publish(directory, state.CLEANUP_FILE, 'true')
    const helper = fakeEventHelper()
    const managed = new actual.WindowsManagedProcess(directory, helper, undefined)
    observeManagedProcess(managed)
    try {
      helper.emit('close', 0)
      await expect(managed.exit).resolves.toEqual({ exitCode: 7, signal: null })
      await managed.settled
      const facts = windowsProcessDiagnosticFacts(directory)
      expect(facts?.source).toBe('command-exit-report')
      expect(facts?.lastSnapshot).toMatchObject({
        commandPid: 3210,
        exitReport: { exitCode: 7, signal: null },
        cleanup: true,
      })
      expect(facts?.removed).toBe(true)
    } finally {
      await managed.terminate(50).catch(() => undefined)
    }
  })

  test('the real WindowsManagedProcess reports helper-close-fallback when no report exists', async () => {
    const actual = await vi.importActual<typeof import('../../src/native/process-supervisor-windows.js')>(
      '../../src/native/process-supervisor-windows.js',
    )
    const directory = await state.createStateDir()
    state.publish(directory, state.SCOPE_FILE, '3211')
    const helper = fakeEventHelper(4243)
    const managed = new actual.WindowsManagedProcess(directory, helper, undefined)
    observeManagedProcess(managed)
    try {
      helper.emit('close', 3)
      await expect(managed.exit).resolves.toEqual({ exitCode: 3, signal: null })
      expect(windowsProcessDiagnosticFacts(directory)?.source).toBe('helper-close-fallback')
      // 不建立真实 Job 也能走完公开 terminate 的收尾：helper 已经退出，直接释放状态目录。
      await managed.terminate(50)
      expect(windowsProcessDiagnosticFacts(directory)?.lastSnapshot).toMatchObject({
        commandPid: 3211,
        exitReport: null,
      })
    } finally {
      await managed.terminate(50).catch(() => undefined)
    }
  })

  test('a state directory removal throw is recorded as safe evidence and rethrown', async () => {
    const actual = await vi.importActual<typeof import('../../src/native/state.js')>('../../src/native/state.js')
    const wrapped = traceStateModule({
      ...actual,
      removeStateDir: () => {
        throw new Error('remove blocked')
      },
    })
    const directory = await wrapped.createStateDir()
    try {
      expect(() => wrapped.removeStateDir(directory)).toThrow(/remove blocked/)
      expect(records.find((record) => record.kind === 'state-remove-error')).toMatchObject({
        error: { errorType: 'Error', errorMessage: 'remove blocked' },
      })
    } finally {
      actual.removeStateDir(directory)
    }
  })

  test('diagnostic output write failures are retained as health, not thrown into native calls', async () => {
    const blocked = join(outputDirectory, 'not-a-directory')
    writeFileSync(blocked, 'x')
    configureWindowsProcessDiagnostics({ enabled: true, outputDirectory: blocked })
    const directory = await state.createStateDir()
    state.publish(directory, state.EXIT_FILE, JSON.stringify({ exitCode: 0, signal: null }))
    expect(() => state.readExit(directory)).not.toThrow()
    const health = windowsProcessDiagnosticHealth()
    expect(health.healthy).toBe(false)
    expect(health.writeFailures).toBeGreaterThan(0)
    expect(health.lastFailure?.errorType).toBeTruthy()
    state.removeStateDir(directory)
  })

  test('the mocked supervisor module is the real export wrapped by a transparent subclass', async () => {
    // 证明顶部 vi.mock 真的命中测试进程加载的 dist?vitest 模块，而不是只创建了一个未被使用的假对象。
    const actual = await vi.importActual<typeof import('../../src/native/process-supervisor-windows.js')>(
      '../../src/native/process-supervisor-windows.js',
    )
    expect(WindowsProcessSupervisor).not.toBe(actual.WindowsProcessSupervisor)
    expect(Object.getPrototypeOf(WindowsProcessSupervisor.prototype)).toBe(actual.WindowsProcessSupervisor.prototype)
  })
})
