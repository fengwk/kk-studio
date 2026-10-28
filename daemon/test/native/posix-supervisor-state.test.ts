import type { ChildProcessWithoutNullStreams } from 'node:child_process'
import { EventEmitter } from 'node:events'
import { join } from 'node:path'
import { PassThrough } from 'node:stream'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ProcessScopeNotConvergedError } from '../../src/contracts.js'
import { PosixManagedProcess, PosixProcessSupervisor } from '../../src/native/posix-supervisor.js'
import type { DirectChild, SessionMember } from '../../src/native/posix-session.js'

// Every OS boundary is a port: no test in this file creates or signals an OS process.
const ports = vi.hoisted(() => ({
  spawn: vi.fn(),
  verify: vi.fn(),
  helperPath: vi.fn(),
  create: vi.fn(),
  writeLaunch: vi.fn(),
  publish: vi.fn(),
  read: vi.fn(),
  readExit: vi.fn(),
  readCleanup: vi.fn(),
  remove: vi.fn(),
  posix: {
    supportsSessionEnumeration: vi.fn(),
    establishSession: vi.fn(),
    currentSession: vi.fn(),
    currentGroup: vi.fn(),
    sessionOf: vi.fn<(pid: number) => number>(),
    processIdentity: vi.fn<(pid: number) => string | null>(),
    membersSnapshot: vi.fn<(session: number, excluded: number) => SessionMember[] | null>(),
    hasLiveMember: vi.fn(),
    signalMembers: vi.fn<(session: number, signal: number, excluded: number) => boolean>(),
    convergeSession: vi.fn(),
    becomeChildSubreaper: vi.fn<() => boolean>(),
    reapOrphans: vi.fn(),
    startOrphanReaper: vi.fn(),
    directChildren: vi.fn<(owner: number, excluded: readonly number[]) => DirectChild[] | null>(),
    convergeAdoptedChildren: vi.fn<(owner: number, grace: number, excluded: readonly number[]) => Promise<boolean>>(),
    reapSpecific: vi.fn(),
    isDescendantOf: vi.fn<(child: number, ancestor: number) => boolean | null>(),
    parentsBeforeChildren: vi.fn(),
  },
}))

vi.mock('node:child_process', () => ({ spawn: ports.spawn }))
vi.mock('../../src/native/posix-session.js', () => ({
  ...ports.posix, SIGTERM: 15, SIGKILL: 9, NO_PROCESS: -1,
}))
vi.mock('../../src/native/helper-path.js', () => ({ resolveHelperPath: ports.helperPath }))
vi.mock('../../src/native/private-fs.js', () => ({ verifyPrivateFile: ports.verify }))
vi.mock('../../src/native/state.js', () => ({
  SCOPE_FILE: 'scope', PERMIT_FILE: 'permit', ERROR_FILE: 'error', LAUNCH_FILE: 'launch',
  createStateDir: ports.create, writeLaunch: ports.writeLaunch, publish: ports.publish,
  read: ports.read, readExit: ports.readExit, readCleanup: ports.readCleanup, removeStateDir: ports.remove,
}))

class FakeChild extends EventEmitter {
  readonly stdin = new PassThrough()
  readonly stdout = new PassThrough()
  readonly stderr = new PassThrough()
  connected = true
  readonly kill = vi.fn((_signal: string) => true)
  readonly disconnect = vi.fn(() => { this.connected = false })
  readonly unref = vi.fn()

  constructor(readonly pid: number) {
    super()
  }

  close(code: number | null = 0, signal: string | null = null): void {
    this.emit('close', code, signal)
  }

  asChild(): ChildProcessWithoutNullStreams {
    return this as unknown as ChildProcessWithoutNullStreams
  }
}

const dirs = new Map<string, Map<string, string>>()
const children: FakeChild[] = []
const events: string[] = []
let nextDir = 0

function child(pid = 501): FakeChild {
  const value = new FakeChild(pid)
  children.push(value)
  return value
}

function directory(): string {
  const dir = join('virtual', `scope-${++nextDir}`)
  dirs.set(dir, new Map())
  return dir
}

function put(dir: string, name: string, value: string): void {
  const files = dirs.get(dir)
  if (files === undefined) {
    throw new Error(`unknown fake directory ${dir}`)
  }
  files.set(name, value)
}

function managed(helper: FakeChild, dir = directory(), baseline: number[] | null = null, adopts = false): PosixManagedProcess {
  return new PosixManagedProcess(dir, helper.asChild(), undefined, baseline, () => [], adopts)
}

async function establish(scope: PosixManagedProcess): Promise<void> {
  put(scope.stateDir, 'scope', String(scope.pid))
  await scope.establish()
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  dirs.clear()
  events.length = 0
  nextDir = 0
  ports.create.mockImplementation(async () => { events.push('create'); return directory() })
  ports.writeLaunch.mockImplementation((dir: string, spec: { cwd: string; args: string[] }) => {
    events.push('launch')
    put(dir, 'launch', JSON.stringify(spec))
  })
  ports.verify.mockImplementation(async () => { events.push('verify') })
  ports.helperPath.mockReturnValue('fixed-helper.js')
  ports.publish.mockImplementation((dir: string, name: string, value: string) => {
    events.push(name)
    put(dir, name, value)
  })
  ports.read.mockImplementation((dir: string, name: string) => dirs.get(dir)?.get(name) ?? null)
  ports.readExit.mockImplementation((dir: string) => {
    const value = dirs.get(dir)?.get('exit')
    if (value === undefined) {
      return null
    }
    return JSON.parse(value)
  })
  ports.readCleanup.mockImplementation((dir: string) => {
    const value = dirs.get(dir)?.get('cleanup')
    return value === 'true' ? true : value === 'false' ? false : null
  })
  ports.remove.mockImplementation((dir: string) => { events.push('remove'); dirs.delete(dir) })
  ports.posix.becomeChildSubreaper.mockReturnValue(false)
  ports.posix.directChildren.mockReturnValue([])
  ports.posix.membersSnapshot.mockReturnValue([])
  ports.posix.signalMembers.mockReturnValue(true)
  ports.posix.isDescendantOf.mockReturnValue(true)
  ports.posix.sessionOf.mockReturnValue(601)
  ports.posix.processIdentity.mockReturnValue('original')
  ports.posix.convergeAdoptedChildren.mockResolvedValue(true)
})

afterEach(() => {
  try {
    // Output is intentionally preserved by successful close; the fixture owns its final destruction.
    for (const helper of children.splice(0)) {
      helper.stdin.destroy()
      helper.stdout.destroy()
      helper.stderr.destroy()
      expect([helper.stdin.destroyed, helper.stdout.destroyed, helper.stderr.destroyed]).toEqual([true, true, true])
    }
    expect(vi.getTimerCount()).toBe(0)
  } finally {
    vi.useRealTimers()
  }
})

describe('POSIX parent state machine with controlled ports', () => {
  test('start verifies the private launch before spawn and permits only the helper-owned SID', async () => {
    const helper = child()
    const supervisor = new PosixProcessSupervisor()
    const spec = { executable: '/private/tool', args: ['secret argument'], cwd: '/private/work', env: { KEY: 'value' } }
    ports.spawn.mockImplementation(() => { events.push('spawn'); return helper.asChild() })
    const starting = supervisor.start(spec)
    await vi.advanceTimersByTimeAsync(0)

    expect(events).toEqual(['create', 'launch', 'verify', 'spawn'])
    expect(ports.posix.directChildren).toHaveBeenCalledWith(process.pid, [])
    expect(ports.writeLaunch).toHaveBeenCalledWith(join('virtual', 'scope-1'), {
      cwd: spec.cwd, args: [spec.executable, ...spec.args],
    })
    expect(ports.verify).toHaveBeenCalledWith(join('virtual', 'scope-1', 'launch'))
    expect(ports.spawn).toHaveBeenCalledWith(process.execPath, [
      '--no-warnings', 'fixed-helper.js', join('virtual', 'scope-1'),
    ], { env: spec.env, stdio: ['pipe', 'pipe', 'pipe', 'ipc'] })
    expect(ports.publish).not.toHaveBeenCalled()

    put(join('virtual', 'scope-1'), 'scope', '501')
    await vi.advanceTimersByTimeAsync(5)
    const scope = await starting as PosixManagedProcess
    expect(events).toEqual(['create', 'launch', 'verify', 'spawn', 'permit'])
    expect(ports.publish).toHaveBeenCalledWith(scope.stateDir, 'permit', '501')
    expect([scope.pid, scope.stdin, scope.stdout, scope.stderr]).toEqual([501, helper.stdin, helper.stdout, helper.stderr])
    put(scope.stateDir, 'cleanup', 'true')
    helper.close()
    await scope.settled
    await supervisor.close()
    expect(ports.posix.signalMembers).not.toHaveBeenCalled()
  })

  test('closed and pre-cancelled starts have no launch side effects', async () => {
    const supervisor = new PosixProcessSupervisor()
    const controller = new AbortController()
    controller.abort()
    const spec = { executable: 'tool', args: [], cwd: 'work' }
    await expect(supervisor.start({ ...spec, signal: controller.signal })).rejects.toThrow(/cancelled/)
    await supervisor.close()
    await expect(supervisor.start(spec)).rejects.toThrow(/closed/)
    expect(ports.posix.directChildren).not.toHaveBeenCalled()
    expect(ports.create).not.toHaveBeenCalled()
    expect(ports.writeLaunch).not.toHaveBeenCalled()
    expect(ports.verify).not.toHaveBeenCalled()
    expect(ports.spawn).not.toHaveBeenCalled()
    expect(ports.publish).not.toHaveBeenCalled()
  })

  test.each(['launch', 'verify', 'spawn'])('%s failure removes private state and preserves the original error', async (stage) => {
    const failure = new Error(`${stage} failure`)
    if (stage === 'launch') {
      ports.writeLaunch.mockImplementation(() => { throw failure })
    } else if (stage === 'verify') {
      ports.verify.mockRejectedValue(failure)
    } else {
      ports.spawn.mockImplementation(() => { throw failure })
    }
    const supervisor = new PosixProcessSupervisor()
    await expect(supervisor.start({ executable: 'tool', args: [], cwd: 'work' })).rejects.toBe(failure)
    expect(ports.remove).toHaveBeenCalledOnce()
    expect(dirs.size).toBe(0)
    expect(ports.publish).not.toHaveBeenCalled()
    if (stage !== 'spawn') {
      expect(ports.spawn).not.toHaveBeenCalled()
    }
    await supervisor.close()
  })

  test('an exit report alone does not release ownership; a later explicit close proves cleanup', async () => {
    const helper = child()
    const scope = managed(helper)
    await establish(scope)
    put(scope.stateDir, 'exit', JSON.stringify({ exitCode: 17, signal: null }))
    await vi.advanceTimersByTimeAsync(5)
    await expect(scope.exit).resolves.toEqual({ exitCode: 17, signal: null })
    expect(dirs.has(scope.stateDir)).toBe(true)
    helper.close(0)
    expect(dirs.has(scope.stateDir)).toBe(true)
    await scope.close()
    await scope.settled
    expect(ports.posix.signalMembers).toHaveBeenCalledWith(501, 15, -1)
    expect(ports.posix.membersSnapshot).toHaveBeenCalledWith(501, -1)
    expect(dirs.has(scope.stateDir)).toBe(false)
  })

  test.each(['missing', 'false', 'corrupt'])('unknown exit and %s cleanup do not fake natural convergence', async (cleanup) => {
    const helper = child()
    const scope = managed(helper)
    await establish(scope)
    put(scope.stateDir, 'exit', '{bad json')
    if (cleanup !== 'missing') {
      put(scope.stateDir, 'cleanup', cleanup === 'false' ? 'false' : 'unreadable')
    }
    await vi.advanceTimersByTimeAsync(5)
    helper.close(7, 'SIGTERM')
    await expect(scope.exit).resolves.toEqual({ exitCode: 7, signal: 'SIGTERM' })
    expect(dirs.has(scope.stateDir)).toBe(true)
    ports.posix.membersSnapshot.mockReturnValue(null)
    await expect(scope.close()).rejects.toThrow(/could not be proven converged/)
    expect(dirs.has(scope.stateDir)).toBe(true)
    expect(ports.remove).not.toHaveBeenCalled()
  })

  test('a corrupt cleanup port cannot authorize natural completion', async () => {
    const helper = child()
    const scope = managed(helper)
    await establish(scope)
    ports.readCleanup.mockImplementation(() => { throw new Error('corrupt cleanup') })
    helper.close()
    expect(dirs.has(scope.stateDir)).toBe(true)
    await scope.close()
    expect(ports.posix.membersSnapshot).toHaveBeenCalledWith(501, -1)
    expect(dirs.has(scope.stateDir)).toBe(false)
  })

  test('natural cleanup true releases once and subsequent close never signals a reused SID', async () => {
    const helper = child()
    const scope = managed(helper)
    await establish(scope)
    put(scope.stateDir, 'exit', JSON.stringify({ exitCode: 0, signal: null }))
    put(scope.stateDir, 'cleanup', 'true')
    helper.stdout.write('buffered')
    helper.close()
    await expect(scope.exit).resolves.toEqual({ exitCode: 0, signal: null })
    await scope.settled
    expect(helper.stdin.destroyed).toBe(true)
    expect(helper.stdout.read().toString()).toBe('buffered')
    expect(helper.disconnect).toHaveBeenCalledOnce()
    expect(helper.unref).toHaveBeenCalledOnce()
    await scope.close()
    await scope.terminate()
    expect(ports.remove).toHaveBeenCalledOnce()
    expect(ports.posix.signalMembers).not.toHaveBeenCalled()
  })

  test('concurrent close is single-flight: TERM, bounded KILL, controlled close event, then empty session', async () => {
    const helper = child()
    const scope = managed(helper)
    await establish(scope)
    const first = scope.close()
    const second = scope.close()
    let completed = false
    void first.then(() => { completed = true })
    expect(ports.posix.signalMembers.mock.calls).toEqual([[501, 15, -1]])
    expect(dirs.has(scope.stateDir)).toBe(true)
    await vi.advanceTimersByTimeAsync(999)
    expect(helper.kill).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(ports.posix.signalMembers.mock.calls).toEqual([[501, 15, -1], [501, 9, -1]])
    expect(helper.kill).toHaveBeenCalledExactlyOnceWith('SIGKILL')
    expect(completed).toBe(false)
    expect(ports.posix.membersSnapshot).not.toHaveBeenCalled()
    expect(dirs.has(scope.stateDir)).toBe(true)

    ports.posix.membersSnapshot.mockReturnValueOnce([{ pid: 510, parentPid: 501, identity: 'child' }]).mockReturnValue([])
    helper.close(null, 'SIGKILL')
    await vi.advanceTimersByTimeAsync(0)
    expect(completed).toBe(false)
    expect(dirs.has(scope.stateDir)).toBe(true)
    expect(ports.posix.signalMembers.mock.calls).toEqual([[501, 15, -1], [501, 9, -1], [501, 9, -1]])
    await vi.advanceTimersByTimeAsync(5)
    await Promise.all([first, second])
    expect(ports.remove).toHaveBeenCalledOnce()
    expect(helper.unref).toHaveBeenCalledOnce()
    expect(dirs.has(scope.stateDir)).toBe(false)
  })

  test('before scope establishment only the fake helper is killed, never an unknown SID', async () => {
    const helper = child()
    const scope = managed(helper)
    helper.kill.mockImplementation(() => { helper.close(null, 'SIGKILL'); return true })
    await scope.close()
    expect(helper.kill).toHaveBeenCalledExactlyOnceWith('SIGKILL')
    expect(ports.posix.signalMembers).not.toHaveBeenCalled()
    expect(ports.posix.membersSnapshot).not.toHaveBeenCalled()
    expect(dirs.has(scope.stateDir)).toBe(false)
  })

  test('unestablished helper without a close event times out at 1s and caches the failure', async () => {
    const helper = child()
    const scope = managed(helper)
    const failure = expect(scope.close()).rejects.toThrow(/helper could not be terminated/)
    await vi.advanceTimersByTimeAsync(999)
    expect(dirs.has(scope.stateDir)).toBe(true)
    await vi.advanceTimersByTimeAsync(1)
    await failure
    await expect(scope.close()).rejects.toThrow(/helper could not be terminated/)
    expect(helper.kill).toHaveBeenCalledOnce()
    expect(ports.posix.signalMembers).not.toHaveBeenCalled()
    expect(dirs.has(scope.stateDir)).toBe(true)
  })

  test('an empty OS session proves exit even when stdio has not emitted close', async () => {
    // Node close waits for pipe EOF; a snapshot can prove OS exit before that event.
    const helper = child()
    const scope = managed(helper)
    await establish(scope)
    helper.stdout.write('buffered output')
    let exitedWithoutClose = false
    helper.kill.mockImplementation(() => { exitedWithoutClose = true; return true })
    ports.posix.membersSnapshot.mockImplementation(() => exitedWithoutClose ? [] : [
      { pid: 501, parentPid: process.pid, identity: 'helper' },
    ])
    const closing = scope.close()
    await vi.advanceTimersByTimeAsync(2000)
    await closing
    expect(ports.posix.membersSnapshot.mock.calls).toEqual([[501, -1]])
    expect(ports.posix.signalMembers.mock.calls).toEqual([[501, 15, -1], [501, 9, -1]])
    expect(helper.kill).toHaveBeenCalledExactlyOnceWith('SIGKILL')
    expect(dirs.has(scope.stateDir)).toBe(false)
    expect(helper.stdin.destroyed).toBe(true)
    expect(helper.disconnect).toHaveBeenCalledOnce()
    expect(helper.unref).toHaveBeenCalledOnce()
    expect(helper.stdout.read().toString()).toBe('buffered output')
  })

  test('a live helper without close remains unproven after both grace windows and the 4s budget', async () => {
    // Unlike delayed pipe EOF, an explicitly live session member is not exit evidence.
    const helper = child()
    const scope = managed(helper)
    await establish(scope)
    ports.posix.membersSnapshot.mockReturnValue([{ pid: 501, parentPid: process.pid, identity: 'helper' }])
    const outcome = scope.close().then(() => null, (error: unknown) => error)
    await vi.advanceTimersByTimeAsync(5999)
    expect(dirs.has(scope.stateDir)).toBe(true)
    await vi.advanceTimersByTimeAsync(1)
    expect((await outcome as Error).message).toMatch(/could not be proven converged/)
    expect(helper.kill).toHaveBeenCalledExactlyOnceWith('SIGKILL')
    expect(ports.posix.membersSnapshot.mock.calls.every(([sid, excluded]) => sid === 501 && excluded === -1)).toBe(true)
    expect(ports.posix.signalMembers.mock.calls[0]).toEqual([501, 15, -1])
    expect(ports.posix.signalMembers.mock.calls.slice(1).every(([sid, signal, excluded]) =>
      sid === 501 && signal === 9 && excluded === -1)).toBe(true)
    expect(ports.posix.processIdentity).not.toHaveBeenCalled()
    expect(ports.posix.sessionOf).not.toHaveBeenCalled()
    expect(ports.posix.directChildren).not.toHaveBeenCalled()
    expect(ports.posix.convergeAdoptedChildren).not.toHaveBeenCalled()
    expect(ports.remove).not.toHaveBeenCalled()
    expect(dirs.has(scope.stateDir)).toBe(true)
  })

  test('a persistent session is bounded by 4s and a repeated close preserves the same failure', async () => {
    const helper = child()
    const scope = managed(helper)
    await establish(scope)
    helper.close()
    ports.posix.membersSnapshot.mockReturnValue([{ pid: 510, parentPid: 501, identity: 'child' }])
    const pending = scope.close()
    const failure = pending.then(() => null, (error: unknown) => error)
    await vi.advanceTimersByTimeAsync(3999)
    expect(dirs.has(scope.stateDir)).toBe(true)
    await vi.advanceTimersByTimeAsync(1)
    const firstError = await failure
    expect(firstError).toBeInstanceOf(Error)
    expect((firstError as Error).message).toMatch(/could not be proven converged/)
    const signals = ports.posix.signalMembers.mock.calls.length
    await expect(scope.close()).rejects.toBe(firstError)
    expect(ports.posix.signalMembers).toHaveBeenCalledTimes(signals)
    expect(dirs.has(scope.stateDir)).toBe(true)
    expect(helper.stdout.destroyed).toBe(true)
    expect(helper.stderr.destroyed).toBe(true)
  })

  test('scope publication timeout is 20s and failed start closes only its helper', async () => {
    const helper = child()
    helper.kill.mockImplementation(() => { helper.close(null, 'SIGKILL'); return true })
    ports.spawn.mockReturnValue(helper.asChild())
    const supervisor = new PosixProcessSupervisor()
    const starting = supervisor.start({ executable: 'tool', args: [], cwd: 'work' })
    const failure = expect(starting).rejects.toThrow(/within 20000 ms/)
    await vi.advanceTimersByTimeAsync(19_999)
    expect(helper.kill).not.toHaveBeenCalled()
    expect(ports.publish).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    await failure
    expect(helper.kill).toHaveBeenCalledExactlyOnceWith('SIGKILL')
    expect(ports.posix.signalMembers).not.toHaveBeenCalled()
    expect(dirs.size).toBe(0)
    await supervisor.close()
  })

  test.each(['bad', '999'])('untrusted scope %s cannot grant or signal a foreign session', async (published) => {
    const helper = child()
    helper.kill.mockImplementation(() => { helper.close(null, 'SIGKILL'); return true })
    ports.spawn.mockImplementation(() => {
      put(join('virtual', 'scope-1'), 'scope', published)
      return helper.asChild()
    })
    const supervisor = new PosixProcessSupervisor()
    await expect(supervisor.start({ executable: 'tool', args: [], cwd: 'work' })).rejects.toThrow(
      published === 'bad' ? /invalid scope id/ : /not its own session/,
    )
    expect(ports.publish).not.toHaveBeenCalled()
    expect(ports.posix.signalMembers).not.toHaveBeenCalled()
    expect(dirs.size).toBe(0)
    await supervisor.close()
  })

  test('failed startup reports an unprovable helper termination without hiding the original failure', async () => {
    const helper = child()
    ports.spawn.mockImplementation(() => {
      put(join('virtual', 'scope-1'), 'scope', 'invalid')
      return helper.asChild()
    })
    const supervisor = new PosixProcessSupervisor()
    const starting = supervisor.start({ executable: 'tool', args: [], cwd: 'work' })
    const failure = starting.then(() => null, (error: unknown) => error)
    await vi.advanceTimersByTimeAsync(1000)
    const error = await failure
    expect(error).toBeInstanceOf(ProcessScopeNotConvergedError)
    expect((error as Error).message).toMatch(/invalid scope id: invalid; additionally .*could not be terminated/)
    expect(dirs.has(join('virtual', 'scope-1'))).toBe(true)
    expect(ports.posix.signalMembers).not.toHaveBeenCalled()
    await supervisor.close()
  })

  test.each([
    { report: 'helper-posix-failure', expected: /during its posix phase/ },
    { report: 'secret path or malformed error', expected: /exited with code 42/ },
  ])('startup exit reports only a safe fixed phase ($report)', async ({ report, expected }) => {
    const helper = child()
    const scope = managed(helper)
    put(scope.stateDir, 'error', report)
    helper.close(42)
    await expect(scope.establish()).rejects.toThrow(expected)
    await expect(scope.exit).resolves.toEqual({ exitCode: 42, signal: null })
    await scope.close()
    expect(helper.kill).not.toHaveBeenCalled()
  })

  test('a helper error before scope uses fallback exit and can release without broadcasting', async () => {
    const helper = child()
    const scope = managed(helper)
    helper.emit('error', new Error('opaque'))
    await expect(scope.establish()).rejects.toThrow(/before establishing/)
    await expect(scope.exit).resolves.toEqual({ exitCode: null, signal: null })
    await scope.close()
    expect(helper.kill).not.toHaveBeenCalled()
    expect(ports.posix.signalMembers).not.toHaveBeenCalled()
  })

  test('cancellation between spawn and permit first converges the confirmed SID', async () => {
    const helper = child()
    const controller = new AbortController()
    ports.spawn.mockReturnValue(helper.asChild())
    ports.posix.signalMembers.mockImplementation((sid, signal) => {
      if (sid === 501 && signal === 15) {
        helper.close()
      }
      return true
    })
    const supervisor = new PosixProcessSupervisor()
    const starting = supervisor.start({ executable: 'tool', args: [], cwd: 'work', signal: controller.signal })
    const failure = expect(starting).rejects.toThrow(/cancelled/)
    await vi.advanceTimersByTimeAsync(0)
    controller.abort()
    put(join('virtual', 'scope-1'), 'scope', '501')
    await vi.advanceTimersByTimeAsync(5)
    await failure
    expect(ports.publish).not.toHaveBeenCalled()
    expect(ports.posix.signalMembers.mock.calls).toEqual([[501, 15, -1]])
    expect(ports.posix.membersSnapshot).toHaveBeenCalledWith(501, -1)
    expect(dirs.size).toBe(0)
    await supervisor.close()
  })

  test('tracking requires an owned root, lineage, session, and frozen identity; only that session is drained', async () => {
    const helper = child()
    ports.spawn.mockImplementation(() => {
      put(join('virtual', 'scope-1'), 'scope', '501')
      return helper.asChild()
    })
    const supervisor = new PosixProcessSupervisor()
    const scope = await supervisor.start({ executable: 'tool', args: [], cwd: 'work' }) as PosixManagedProcess
    await expect(supervisor.trackDescendant(999, 600)).rejects.toThrow(/no managed process/)
    for (const lineage of [false, null]) {
      ports.posix.isDescendantOf.mockReturnValueOnce(lineage)
      await expect(supervisor.trackDescendant(501, 600)).rejects.toThrow(/not a descendant/)
    }
    ports.posix.sessionOf.mockReturnValueOnce(-1)
    await expect(supervisor.trackDescendant(501, 600)).rejects.toThrow(/cannot resolve/)
    ports.posix.processIdentity.mockReturnValueOnce(null)
    await expect(supervisor.trackDescendant(501, 600)).rejects.toThrow(/cannot freeze/)
    ports.posix.processIdentity.mockClear()
    ports.posix.sessionOf.mockReturnValueOnce(501)
    await supervisor.trackDescendant(501, 600)
    expect(ports.posix.processIdentity).not.toHaveBeenCalled()
    await supervisor.trackDescendant(501, 600)
    expect(ports.posix.isDescendantOf).toHaveBeenLastCalledWith(600, 501)
    expect(ports.posix.processIdentity).toHaveBeenCalledExactlyOnceWith(600)

    let trackedReads = 0
    ports.posix.membersSnapshot.mockImplementation((session) => {
      if (session === 601 && trackedReads++ === 0) {
        return [{ pid: 600, parentPid: 501, identity: 'original' }]
      }
      return []
    })
    helper.close()
    const closing = scope.close()
    await vi.advanceTimersByTimeAsync(0)
    expect(ports.posix.processIdentity).toHaveBeenCalledTimes(2)
    expect(ports.posix.sessionOf).toHaveBeenLastCalledWith(600)
    expect(ports.posix.signalMembers.mock.calls).toEqual([[501, 15, -1], [601, 9, -1]])
    expect(dirs.has(scope.stateDir)).toBe(true)
    await vi.advanceTimersByTimeAsync(5)
    await closing
    expect(ports.posix.membersSnapshot.mock.calls).toEqual([[501, -1], [601, -1], [601, -1]])
    await supervisor.close()
  })

  test('a changed descendant session is fenced and its old nonempty session is not signalled', async () => {
    const helper = child()
    const scope = managed(helper)
    await establish(scope)
    scope.trackSession(600)
    ports.posix.sessionOf.mockReturnValue(777)
    ports.posix.membersSnapshot.mockImplementation((session) => session === 601
      ? [{ pid: 602, parentPid: 1, identity: 'other' }] : [])
    helper.close()
    await expect(scope.close()).rejects.toThrow(/could not be proven converged/)
    expect(ports.posix.membersSnapshot).toHaveBeenCalledWith(601, -1)
    expect(ports.posix.signalMembers.mock.calls).toEqual([[501, 15, -1]])
    expect(dirs.has(scope.stateDir)).toBe(true)
  })

  test('adoption runs once before fencing proof and excludes baseline plus all active helpers', async () => {
    ports.posix.becomeChildSubreaper.mockReturnValue(true)
    ports.posix.directChildren
      .mockReturnValueOnce([{ pid: 71, session: 71, identity: 'baseline' }])
      .mockReturnValueOnce([{ pid: 71, session: 71, identity: 'baseline' }, { pid: 501, session: 501, identity: 'helper' }])
    const firstHelper = child(501)
    const secondHelper = child(502)
    ports.spawn.mockImplementationOnce(() => {
      put(join('virtual', 'scope-1'), 'scope', '501')
      return firstHelper.asChild()
    }).mockImplementationOnce(() => {
      put(join('virtual', 'scope-2'), 'scope', '502')
      return secondHelper.asChild()
    })
    const supervisor = new PosixProcessSupervisor()
    const first = await supervisor.start({ executable: 'tool', args: [], cwd: 'work' }) as PosixManagedProcess
    const second = await supervisor.start({ executable: 'tool', args: [], cwd: 'work' }) as PosixManagedProcess
    await supervisor.trackDescendant(501, 600)
    ports.posix.processIdentity.mockReturnValue(null)
    let adopted = false
    const order: string[] = []
    ports.posix.membersSnapshot.mockImplementation((session) => {
      order.push(`snapshot:${session}`)
      return session === 601 && !adopted ? [{ pid: 600, parentPid: process.pid, identity: 'original' }] : []
    })
    ports.posix.convergeAdoptedChildren.mockImplementation(async () => {
      order.push('adoption')
      adopted = true
      return true
    })
    firstHelper.close()
    await first.close()
    expect(ports.posix.convergeAdoptedChildren).toHaveBeenCalledExactlyOnceWith(process.pid, 1000, [501, 502, 71])
    expect(order).toEqual(['snapshot:501', 'adoption', 'snapshot:601'])
    expect(ports.posix.signalMembers.mock.calls).toEqual([[501, 15, -1]])
    expect(dirs.has(first.stateDir)).toBe(false)
    put(second.stateDir, 'cleanup', 'true')
    secondHelper.close()
    await second.settled
    await supervisor.close()
    expect(ports.posix.convergeAdoptedChildren).toHaveBeenCalledOnce()
  })

  test('unknown baseline never adopts unfamiliar children; a failed adoption retains evidence', async () => {
    ports.posix.becomeChildSubreaper.mockReturnValue(true)
    ports.posix.directChildren.mockReturnValueOnce(null).mockReturnValueOnce([])
    const firstHelper = child(501)
    const secondHelper = child(502)
    ports.spawn.mockImplementationOnce(() => {
      put(join('virtual', 'scope-1'), 'scope', '501')
      return firstHelper.asChild()
    }).mockImplementationOnce(() => {
      put(join('virtual', 'scope-2'), 'scope', '502')
      return secondHelper.asChild()
    })
    const supervisor = new PosixProcessSupervisor()
    const first = await supervisor.start({ executable: 'tool', args: [], cwd: 'work' }) as PosixManagedProcess
    const second = await supervisor.start({ executable: 'tool', args: [], cwd: 'work' }) as PosixManagedProcess
    firstHelper.close()
    await first.close()
    expect(ports.posix.convergeAdoptedChildren).not.toHaveBeenCalled()
    expect(ports.posix.signalMembers.mock.calls).toEqual([[501, 15, -1]])
    ports.posix.convergeAdoptedChildren.mockResolvedValue(false)
    secondHelper.close()
    await expect(second.close()).rejects.toThrow(/could not be proven converged/)
    expect(ports.posix.convergeAdoptedChildren).toHaveBeenCalledExactlyOnceWith(process.pid, 1000, [502])
    expect(dirs.has(second.stateDir)).toBe(true)
    await supervisor.close()
  })

  test.each([
    { identity: 'reused', members: [{ pid: 602, identity: 'other', parentPid: 1 }] },
    { identity: null, members: null },
  ])('identity fencing cannot prove a nonempty or unknown old session ($identity)', async ({ identity, members }) => {
    // A reused/disappeared leader must not authorize signalling its old SID or erase evidence.
    const helper = child()
    const scope = managed(helper)
    await establish(scope)
    scope.trackSession(600)
    ports.posix.processIdentity.mockReturnValue(identity)
    ports.posix.membersSnapshot.mockImplementation((session) => session === 601 ? members : [])
    helper.close()

    await expect(scope.close()).rejects.toThrow(/could not be proven converged/)
    expect(ports.posix.membersSnapshot).toHaveBeenCalledWith(601, -1)
    expect(ports.posix.signalMembers).not.toHaveBeenCalledWith(601, expect.anything(), -1)
    expect(dirs.has(scope.stateDir)).toBe(true)
  })

  test('identity fencing accepts a read-only proof that the old session is empty', async () => {
    const helper = child()
    const scope = managed(helper)
    await establish(scope)
    scope.trackSession(600)
    ports.posix.processIdentity.mockReturnValue(null)
    helper.close()
    await scope.close()
    expect(ports.posix.membersSnapshot).toHaveBeenCalledWith(601, -1)
    expect(ports.posix.signalMembers).not.toHaveBeenCalledWith(601, expect.anything(), -1)
    expect(dirs.has(scope.stateDir)).toBe(false)
  })
})
