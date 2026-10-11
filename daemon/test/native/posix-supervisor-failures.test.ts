import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import { PosixManagedProcess, PosixProcessSupervisor } from '../../src/native/posix-supervisor.js'
import * as state from '../../src/native/state.js'

/**
 * POSIX 父进程侧状态机的失败关闭路径：范围 id 不可信、helper 未发布范围就退出、以及启动前
 * 写入启动规格失败，都必须显式失败并保留/清理正确的证据。
 */
const children: ChildProcessWithoutNullStreams[] = []
const stateDirs: string[] = []

function fakeHelper(lifetimeMillis = 400): ChildProcessWithoutNullStreams {
  const child = spawn(process.execPath, ['-e', `setTimeout(() => process.exit(0), ${lifetimeMillis})`], {
    stdio: ['pipe', 'pipe', 'pipe', 'ipc'],
  }) as ChildProcessWithoutNullStreams
  children.push(child)
  return child
}

async function dir(): Promise<string> {
  const value = await state.createStateDir()
  stateDirs.push(value)
  return value
}

function managed(stateDir: string, helper: ChildProcessWithoutNullStreams): PosixManagedProcess {
  // baseline=null 与 adoptsOrphans=false：本文件只驱动父进程侧状态机，不参与真实孤儿收养。
  return new PosixManagedProcess(stateDir, helper, undefined, null, () => [], false)
}

afterEach(async () => {
  for (const child of children.splice(0)) {
    if (child.exitCode === null && child.signalCode === null) {
      child.kill('SIGKILL')
    }
  }
  for (const value of stateDirs.splice(0)) {
    state.removeStateDir(value)
  }
})

describe.skipIf(process.platform === 'win32')('posix supervisor failure branches', () => {
  let workdir: string

  beforeEach(() => {
    workdir = mkdtempSync(join(tmpdir(), 'kk-studio-posix-sup-'))
  })

  afterEach(() => {
    rmSync(workdir, { recursive: true, force: true })
  })

  test('an invalid published scope id fails closed', async () => {
    const stateDir = await dir()
    state.publish(stateDir, state.SCOPE_FILE, 'not-a-number')
    const scope = managed(stateDir, fakeHelper())
    await expect(scope.establish()).rejects.toThrow(/invalid scope id/)
    await scope.close()
  })

  test('a scope id that is not the helper session fails closed', async () => {
    const stateDir = await dir()
    const helper = fakeHelper()
    state.publish(stateDir, state.SCOPE_FILE, String((helper.pid ?? 0) + 1))
    const scope = managed(stateDir, helper)
    await expect(scope.establish()).rejects.toThrow(/not its own session/)
    await scope.close()
  })

  test('a helper that died before the scope reports its fixed failure phase', async () => {
    const stateDir = await dir()
    state.publish(stateDir, state.ERROR_FILE, 'helper-posix-failure')
    const helper = fakeHelper(50)
    const scope = managed(stateDir, helper)
    await new Promise((resolve) => helper.on('close', resolve))
    await expect(scope.establish()).rejects.toThrow(/failed during its posix phase/)
  })

  test('a launch spec that cannot be written fails closed before spawning the helper', async () => {
    const supervisor = new PosixProcessSupervisor()
    await expect(
      supervisor.start({ executable: process.execPath, args: ['a\0b'], cwd: tmpdir() }),
    ).rejects.toThrow(/NUL/)
    await supervisor.close()
  })
})
