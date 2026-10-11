import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import {
  SIGKILL,
  SIGTERM,
  becomeChildSubreaper,
  convergeSession,
  directChildren,
  hasLiveMember,
  isDescendantOf,
  membersSnapshot,
  parentsBeforeChildren,
  processIdentity,
  reapOrphans,
  reapSpecific,
  sessionOf,
  signalMembers,
  supportsSessionEnumeration,
  type SessionMember,
} from '../../src/native/posix-session.js'
import { awaitGone, fixturePath, isAlive, waitForPid } from './process-test-util.js'

const TEST_TIMEOUT = 30_000

// POSIX 会话原语：真实内核成员枚举、身份核验与「先温和后强杀」的收敛。
describe.skipIf(process.platform === 'win32')('native posix session primitives', () => {
  let workdir: string
  const children: number[] = []

  beforeEach(() => {
    workdir = mkdtempSync(join(tmpdir(), 'kk-studio-native-session-'))
  })

  afterEach(async () => {
    for (const pid of children.splice(0)) {
      if (isAlive(pid)) {
        try {
          process.kill(pid, 'SIGKILL')
        } catch {
          // 已经消失。
        }
      }
    }
    rmSync(workdir, { recursive: true, force: true })
  })

  test('the platform exposes real session enumeration', () => {
    expect(supportsSessionEnumeration()).toBe(true)
  })

  test('parentsBeforeChildren orders by topology, not by pid', () => {
    const members: SessionMember[] = [
      { pid: 30, identity: 'c', parentPid: 20 },
      { pid: 10, identity: 'a', parentPid: 1 },
      { pid: 20, identity: 'b', parentPid: 10 },
    ]
    const ordered = parentsBeforeChildren(members)
    expect(ordered?.map((member) => member.pid)).toEqual([10, 20, 30])
    expect(parentsBeforeChildren([])).toEqual([])
  })

  test('parentsBeforeChildren refuses ambiguous snapshots', () => {
    // 重复 pid 无法消歧。
    expect(
      parentsBeforeChildren([
        { pid: 1, identity: 'a', parentPid: 1 },
        { pid: 1, identity: 'b', parentPid: 1 },
      ]),
    ).toBeNull()
    // 父指针成环无法消歧。
    expect(
      parentsBeforeChildren([
        { pid: 1, identity: 'a', parentPid: 2 },
        { pid: 2, identity: 'b', parentPid: 1 },
      ]),
    ).toBeNull()
  })

  test('a descendant that establishes its own session is enumerated and converged', async () => {
    const pidFile = join(workdir, 'escaped.pid')
    const child = spawn(process.execPath, [fixturePath('escape-hold.mjs'), pidFile], { stdio: 'ignore' })
    const pid = await waitForPid(pidFile)
    children.push(pid)

    expect(sessionOf(pid)).toBe(pid)
    expect(processIdentity(pid)).not.toBeNull()
    const members = membersSnapshot(pid, -1)
    expect(members?.some((member) => member.pid === pid)).toBe(true)
    expect(signalMembers(pid, 0, -1)).toBe(true)

    expect(await convergeSession(pid, -1, 200)).toBe(true)
    expect(await awaitGone(pid)).toBe(true)
    child.unref()
  }, TEST_TIMEOUT)

  test('a member that ignores SIGTERM is escalated to SIGKILL', async () => {
    const pidFile = join(workdir, 'stubborn.pid')
    const child = spawn(process.execPath, [fixturePath('stubborn-hold.mjs'), pidFile], { stdio: 'ignore' })
    const pid = await waitForPid(pidFile)
    children.push(pid)

    // 先温和：忽略 SIGTERM 的成员在宽限窗口内不会消失。
    signalMembers(pid, SIGTERM, -1)
    expect(isAlive(pid)).toBe(true)
    // 再强杀：convergeSession 必须在宽限之后升级到 SIGKILL 并证明收敛。
    expect(await convergeSession(pid, -1, 300)).toBe(true)
    expect(await awaitGone(pid)).toBe(true)
    child.unref()
  }, TEST_TIMEOUT)

  test('an unknown session is decidable and has no members', () => {
    expect(membersSnapshot(2_000_000_000, -1)).toEqual([])
    expect(hasLiveMember(2_000_000_000, -1)).toBe(false)
    expect(signalMembers(2_000_000_000, SIGKILL, -1)).toBe(false)
  })

  test('child subreaper and orphan reaping are available on Linux', () => {
    expect(becomeChildSubreaper()).toBe(process.platform === 'linux')
    expect(() => reapOrphans()).not.toThrow()
  })

  test('the orphan reaper can be started and stopped', async () => {
    const { startOrphanReaper } = await import('../../src/native/posix-session.js')
    const stop = startOrphanReaper(20)
    expect(typeof stop).toBe('function')
    stop()
  })

  test('session and identity are undefined for a vanished pid', () => {
    expect(sessionOf(2_000_000_000)).toBe(-1)
    expect(processIdentity(2_000_000_000)).toBeNull()
  })

  test('reapSpecific is a no-op for a pid that is not our child', () => {
    // 内核返回 ECHILD：回收自己孩子之外的 pid 绝不能有副作用。
    expect(() => reapSpecific(1)).not.toThrow()
  })

  test('isDescendantOf walks the real parent chain', async () => {
    const pidFile = join(workdir, 'child.pid')
    const child = spawn(process.execPath, [fixturePath('hold.mjs'), pidFile], { stdio: 'ignore' })
    const pid = await waitForPid(pidFile)
    children.push(pid)

    expect(isDescendantOf(pid, process.pid)).toBe(true)
    expect(isDescendantOf(pid, pid)).toBe(true)
    expect(isDescendantOf(process.pid, pid)).toBe(false)
    child.unref()
  }, TEST_TIMEOUT)

  test('directChildren lists real direct children and honours exclusions', async () => {
    // 收养集合只在 Linux 上可核实：macOS 的孤儿被重新挂到 launchd。
    if (process.platform !== 'linux') {
      return
    }
    const pidFile = join(workdir, 'child.pid')
    const child = spawn(process.execPath, [fixturePath('hold.mjs'), pidFile], { stdio: 'ignore' })
    const pid = await waitForPid(pidFile)
    children.push(pid)

    expect(directChildren(process.pid, [])?.some((entry) => entry.pid === pid)).toBe(true)
    expect(directChildren(process.pid, [pid])?.some((entry) => entry.pid === pid)).toBe(false)
    child.unref()
  }, TEST_TIMEOUT)
})
