import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import { createProcessSupervisor } from '../../src/native/index.js'
import type { ManagedProcess, ProcessSupervisor } from '../../src/contracts.js'
import { awaitGone, fixtureCommand, isAlive, waitForPid } from './process-test-util.js'

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
})
