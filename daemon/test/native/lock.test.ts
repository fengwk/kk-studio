import { spawn } from 'node:child_process'
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'
import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import { acquireDirectoryLock } from '../../src/native/index.js'
import { awaitGone, fixturePath, isAlive } from './process-test-util.js'

// 目录锁必须是内核锁：同一目录竞争时第二个持有者失败，持有进程崩溃时由内核释放。
describe.skipIf(process.platform === 'win32')('native directory lock', () => {
  let workdir: string

  beforeEach(() => {
    workdir = mkdtempSync(join(tmpdir(), 'kk-studio-native-lock-'))
  })

  afterEach(() => {
    rmSync(workdir, { recursive: true, force: true })
  })

  test('a second acquisition of the same directory fails', async () => {
    const lock = await acquireDirectoryLock(workdir)
    await expect(acquireDirectoryLock(workdir)).rejects.toThrow(/already locked/)
    await lock.close()
    const again = await acquireDirectoryLock(workdir)
    await again.close()
  })

  test('close is idempotent', async () => {
    const lock = await acquireDirectoryLock(workdir)
    await lock.close()
    await lock.close()
  })

  test('the directory and its lock file are owner-only', async () => {
    const lock = await acquireDirectoryLock(workdir)
    expect(statSync(workdir).mode & 0o777).toBe(0o700)
    expect(statSync(join(workdir, 'daemon.lock')).mode & 0o777).toBe(0o600)
    await lock.close()
  })

  test('a symbolic-link lock file fails closed', async () => {
    const target = join(workdir, 'target')
    writeFileSync(target, 'not a lock', { mode: 0o600 })
    symlinkSync(target, join(workdir, 'daemon.lock'))
    await expect(acquireDirectoryLock(workdir)).rejects.toThrow()
  })

  test('a lock file with loose permissions fails closed', async () => {
    const file = join(workdir, 'daemon.lock')
    writeFileSync(file, 'not a lock', { mode: 0o600 })
    chmodSync(file, 0o644)
    await expect(acquireDirectoryLock(workdir)).rejects.toThrow(/owner-only/)
  })

  test('a symbolic-link directory fails closed', async () => {
    const real = mkdtempSync(join(tmpdir(), 'kk-studio-native-lock-real-'))
    const link = join(workdir, 'linked')
    symlinkSync(real, link)
    try {
      await expect(acquireDirectoryLock(link)).rejects.toThrow(/symbolic link/)
    } finally {
      rmSync(real, { recursive: true, force: true })
    }
  })

  test('an external holder is blocked while the lock is held', async () => {
    const ready = join(workdir, 'holder.ready')
    const holder = spawn(process.execPath, [fixturePath('lock-holder.mjs'), workdir, ready], { stdio: 'ignore' })
    try {
      const value = await waitForReady(ready)
      const pid = Number(value)
      expect(isAlive(pid)).toBe(true)
      // 外部内核锁正在被持有：本进程也必须失败。
      await expect(acquireDirectoryLock(workdir)).rejects.toThrow(/already locked/)
    } finally {
      holder.kill('SIGKILL')
      await new Promise((resolve) => holder.on('exit', resolve))
    }
  })

  test('the kernel releases the lock when the holder crashes', async () => {
    const ready = join(workdir, 'holder.ready')
    const holder = spawn(process.execPath, [fixturePath('lock-holder.mjs'), workdir, ready], { stdio: 'ignore' })
    const pid = Number(await waitForReady(ready))
    expect(isAlive(pid)).toBe(true)
    await expect(acquireDirectoryLock(workdir)).rejects.toThrow(/already locked/)

    holder.kill('SIGKILL')
    await promiseFromExit(holder)
    expect(await awaitGone(pid)).toBe(true)

    // 内核在进程崩溃时已经释放 flock：这里必须能重新取得。
    const lock = await acquireDirectoryLock(workdir)
    await lock.close()
  })
})

async function waitForReady(file: string, budgetMillis = 20_000): Promise<string> {
  const deadline = Date.now() + budgetMillis
  while (!existsSync(file)) {
    if (Date.now() >= deadline) {
      throw new Error(`timed out waiting for ${file}`)
    }
    await sleep(5)
  }
  return readFileSync(file, 'utf8').trim()
}

function promiseFromExit(child: ReturnType<typeof spawn>): Promise<void> {
  return new Promise((resolve) => child.on('exit', () => resolve()))
}
