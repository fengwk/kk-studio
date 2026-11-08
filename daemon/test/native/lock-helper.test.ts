import { spawn, type ChildProcess } from 'node:child_process'
import { mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, beforeEach, describe, expect, test } from 'vitest'

import {
  LOCK_HELPER_EXIT,
  HOLD_READY_LINE,
  runLockHelper,
} from '../../src/native/lock-helper.js'
import { acquireDirectoryLock, INSTALL_LOCK_FILE_NAME } from '../../src/native/index.js'
import { isAlive } from './process-test-util.js'

const HELPER_ENTRY = fileURLToPath(new URL('../../dist/native/lock-helper.js', import.meta.url))

interface HelperRun {
  child: ChildProcess
  stdout(): string
  stderr(): string
  exited: Promise<{ code: number | null; signal: NodeJS.Signals | null }>
}

/** 启动真实编译产物 helper，并保持 stdin 可写；stdin 关闭即释放锁。 */
function startHelper(args: string[], { keepStdin = true } = {}): HelperRun {
  const child = spawn(process.execPath, [HELPER_ENTRY, ...args], {
    stdio: [keepStdin ? 'pipe' : 'ignore', 'pipe', 'pipe'],
  })
  let stdout = ''
  let stderr = ''
  child.stdout?.on('data', (chunk: Buffer) => {
    stdout += chunk.toString('utf8')
  })
  child.stderr?.on('data', (chunk: Buffer) => {
    stderr += chunk.toString('utf8')
  })
  const exited = new Promise<{ code: number | null; signal: NodeJS.Signals | null }>((resolve) => {
    child.on('exit', (code, signal) => resolve({ code, signal }))
  })
  return { child, stdout: () => stdout, stderr: () => stderr, exited }
}

/** 等到 helper 明确输出 ready 行或提前退出，避免依赖固定 sleep。 */
async function waitForReady(run: HelperRun): Promise<void> {
  const deadline = Date.now() + 20_000
  for (;;) {
    if (run.stdout().includes(HOLD_READY_LINE)) {
      return
    }
    if (run.child.exitCode !== null || run.child.signalCode !== null) {
      throw new Error(`the helper exited before taking the lock: ${run.stderr()}`)
    }
    if (Date.now() >= deadline) {
      throw new Error(`timed out waiting for the helper: ${run.stderr()}`)
    }
    await new Promise((resolve) => setTimeout(resolve, 5))
  }
}

describe.skipIf(process.platform === 'win32')('install operation lock helper', () => {
  let workdir: string

  beforeEach(() => {
    workdir = mkdtempSync(join(tmpdir(), 'kk-studio-lock-helper-'))
  })

  afterEach(() => {
    rmSync(workdir, { recursive: true, force: true })
  })

  test('hold owns install.lock until stdin closes', async () => {
    const run = startHelper(['hold', workdir])
    await waitForReady(run)
    // 守护期间另一个持有者必须竞争失败：证明锁是真实内核锁而不是标记文件。
    await expect(acquireDirectoryLock(workdir, INSTALL_LOCK_FILE_NAME)).rejects.toThrow(/already locked/)
    run.child.stdin?.end()
    await expect(run.exited).resolves.toEqual({ code: 0, signal: null })
    const lock = await acquireDirectoryLock(workdir, INSTALL_LOCK_FILE_NAME)
    await lock.close()
  })

  test('hold reports busy when the installation lock is already held', async () => {
    const lock = await acquireDirectoryLock(workdir, INSTALL_LOCK_FILE_NAME)
    try {
      const run = startHelper(['hold', workdir], { keepStdin: false })
      await expect(run.exited).resolves.toMatchObject({ code: LOCK_HELPER_EXIT.busy })
      expect(run.stderr()).toContain('install operation lock is busy')
    } finally {
      await lock.close()
    }
  })

  test('the kernel releases the installation lock when the helper is killed', async () => {
    const run = startHelper(['hold', workdir])
    await waitForReady(run)
    const pid = run.child.pid
    expect(pid).toBeTypeOf('number')
    run.child.kill('SIGKILL')
    await run.exited
    expect(isAlive(pid!)).toBe(false)
    const lock = await acquireDirectoryLock(workdir, INSTALL_LOCK_FILE_NAME)
    await lock.close()
  })

  test('wait returns as soon as the daemon lock is free', async () => {
    const run = startHelper(['wait', workdir, '0'], { keepStdin: false })
    await expect(run.exited).resolves.toMatchObject({ code: 0 })
  })

  test('wait times out while the daemon lock is held', async () => {
    const run = startHelper(['wait', workdir, '0'], { keepStdin: false })
    await expect(run.exited).resolves.toMatchObject({ code: 0 })
    const lock = await acquireDirectoryLock(workdir)
    try {
      const blocked = startHelper(['wait', workdir, '1'], { keepStdin: false })
      await expect(blocked.exited).resolves.toMatchObject({ code: LOCK_HELPER_EXIT.timeout })
      expect(blocked.stderr()).toContain('daemon runtime lock wait timed out')
    } finally {
      await lock.close()
    }
  })

  test('wait reports an unreadable lock as a failure, not a timeout', async () => {
    writeFileSync(join(workdir, 'target'), 'not a lock', { mode: 0o600 })
    symlinkSync(join(workdir, 'target'), join(workdir, 'daemon.lock'))
    const run = startHelper(['wait', workdir, '1'], { keepStdin: false })
    await expect(run.exited).resolves.toMatchObject({ code: LOCK_HELPER_EXIT.failed })
    expect(run.stderr()).toContain('cannot inspect the daemon runtime lock')
  })

  test('usage errors are fixed and non-zero', async () => {
    for (const args of [[], ['bogus', workdir], ['hold', 'relative'], ['hold', workdir, 'extra'], ['wait', workdir], ['wait', workdir, '1', '2'], ['wait', workdir, '3601'], ['wait', workdir, '-1']]) {
      const run = startHelper(args, { keepStdin: false })
      await expect(run.exited).resolves.toMatchObject({ code: LOCK_HELPER_EXIT.usage })
      expect(run.stderr()).toContain('usage: lock-helper hold <directory>')
    }
  })

  test('the module entry stays inert when imported', async () => {
    // 模块被导入时不得启动 helper 主流程；可测试入口仍然导出。
    await expect(runLockHelper([])).resolves.toBe(LOCK_HELPER_EXIT.usage)
  })
})
