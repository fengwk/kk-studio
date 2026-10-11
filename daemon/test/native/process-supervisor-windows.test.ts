import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import { createProcessSupervisor } from '../../src/native/index.js'
import type { ManagedProcess, ProcessSupervisor } from '../../src/contracts.js'
import { awaitGone, fixtureCommand, fixturePath, isAlive, waitForPid } from './process-test-util.js'

const TEST_TIMEOUT = 30_000

// Windows 执行范围：Job 的创建期归属与 ActiveProcesses==0 收敛。仅在 Windows runner 上执行。
describe.runIf(process.platform === 'win32')('windows process supervisor', () => {
  let workdir: string
  let supervisor: ProcessSupervisor
  const pending: ManagedProcess[] = []

  beforeEach(() => {
    workdir = mkdtempSync(join(tmpdir(), 'kk-studio-native-proc-win-'))
    supervisor = createProcessSupervisor()
  })

  afterEach(async () => {
    for (const managed of pending.splice(0)) {
      try {
        await managed.close()
      } catch {
        // 用例已经断言过收敛。
      }
    }
    await supervisor.close()
    rmSync(workdir, { recursive: true, force: true })
  })

  async function start(name: string, ...args: string[]): Promise<ManagedProcess> {
    const command = fixtureCommand(name, ...args)
    const managed = await supervisor.start({ executable: command.executable, args: command.args, cwd: workdir })
    pending.push(managed)
    return managed
  }

  test('natural exit still converges live descendants', async () => {
    const rootPidFile = join(workdir, 'root.pid')
    const childPidFile = join(workdir, 'child.pid')
    const permit = join(workdir, 'permit')
    const managed = await start('fork-exit.mjs', rootPidFile, childPidFile, permit)

    const child = await waitForPid(childPidFile)
    expect(isAlive(child)).toBe(true)
    writeFileSync(permit, 'go')

    const exit = await managed.exit
    expect(exit.exitCode).toBe(0)
    await managed.terminate()
    expect(await awaitGone(child)).toBe(true)
  }, TEST_TIMEOUT)

  test('terminate converges nested descendants through the job', async () => {
    const rootPidFile = join(workdir, 'root.pid')
    const childPidFile = join(workdir, 'child.pid')
    const grandPidFile = join(workdir, 'grand.pid')
    const managed = await start('nest-hold.mjs', rootPidFile, childPidFile, grandPidFile)

    const root = await waitForPid(rootPidFile)
    const child = await waitForPid(childPidFile)
    const grand = await waitForPid(grandPidFile)
    expect([root, child, grand].every(isAlive)).toBe(true)

    await managed.terminate()
    for (const pid of [root, child, grand]) {
      expect(await awaitGone(pid)).toBe(true)
    }
  }, TEST_TIMEOUT)

  test('stdin EOF lets the fixture exit with its own code', async () => {
    const rootPidFile = join(workdir, 'root.pid')
    const managed = await start('eof.mjs', rootPidFile, '42')
    await waitForPid(rootPidFile)
    managed.stdin.end()
    const exit = await managed.exit
    expect(exit.exitCode).toBe(42)
    await managed.terminate()
  }, TEST_TIMEOUT)

  test('trackDescendant confirms an in-job child and fails closed for a foreign pid', async () => {
    const rootPidFile = join(workdir, 'root.pid')
    const childPidFile = join(workdir, 'child.pid')
    const managed = await start('fork-hold.mjs', rootPidFile, childPidFile)
    const child = await waitForPid(childPidFile)

    // 命令创建的后代在创建期就进入 Job：确认通过，不需要补指派。
    await expect(supervisor.trackDescendant(managed.pid, child)).resolves.toBeUndefined()
    // 不属于本 Job 的 pid 必须失败关闭：绝不调用 AssignProcessToJobObject 劫持别人的进程。
    await expect(supervisor.trackDescendant(managed.pid, process.pid)).rejects.toThrow(/not a member/)

    await managed.terminate()
    expect(await awaitGone(child)).toBe(true)
  }, TEST_TIMEOUT)

  test('the job is drained when the owning daemon is killed', async () => {
    const { resolveHelperPath } = await import('../../src/native/helper-path.js')
    const stateDir = mkdtempSync(join(tmpdir(), 'kk-studio-native-owner-win-'))
    const helperPidFile = join(workdir, 'helper.pid')
    const rootPidFile = join(workdir, 'root.pid')
    const command = fixtureCommand('hold.mjs', rootPidFile)
    const owner = spawn(
      process.execPath,
      [fixturePath('parent-owner.mjs'), stateDir, resolveHelperPath(), helperPidFile, command.executable, ...command.args],
      { stdio: 'ignore' },
    )
    try {
      const helperPid = await waitForPid(helperPidFile)
      const root = await waitForPid(rootPidFile)
      expect([helperPid, root].every(isAlive)).toBe(true)

      // 所有者消失：helper 必须通过 IPC 通道的内核 EOF 察觉并清空整个 Job（等待期间事件循环不被阻塞）。
      owner.kill('SIGKILL')
      await new Promise<void>((resolve) => owner.on('exit', () => resolve()))
      expect(await awaitGone(helperPid, 30_000)).toBe(true)
      expect(await awaitGone(root, 30_000)).toBe(true)
    } finally {
      if (owner.exitCode === null && owner.signalCode === null) {
        owner.kill('SIGKILL')
      }
      rmSync(stateDir, { recursive: true, force: true })
    }
  }, TEST_TIMEOUT)

  test('a later scope is unaffected by a previously released job handle', async () => {
    const firstRoot = join(workdir, 'first.pid')
    const first = await start('hold.mjs', firstRoot)
    await waitForPid(firstRoot)
    await first.terminate()

    const secondRoot = join(workdir, 'second.pid')
    const second = await start('hold.mjs', secondRoot)
    const pid = await waitForPid(secondRoot)
    expect(isAlive(pid)).toBe(true)
    await second.terminate()
    expect(await awaitGone(pid)).toBe(true)
  }, TEST_TIMEOUT)
})
