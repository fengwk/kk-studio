import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import { acquireDirectoryLock, ensurePrivateDirectory, verifyPrivateFile, writePrivateFile } from '../../src/native/index.js'

// Windows 原生基座：owner-only DACL、内核 LockFileEx 以及读回验证。仅在 Windows runner 上执行（本机为 Linux 时跳过）。
describe.runIf(process.platform === 'win32')('windows native primitives', () => {
  let workdir: string

  beforeEach(() => {
    workdir = mkdtempSync(join(tmpdir(), 'kk-studio-native-win-'))
  })

  afterEach(() => {
    rmSync(workdir, { recursive: true, force: true })
  })

  test('ensurePrivateDirectory creates a verified owner-only directory', async () => {
    const target = join(workdir, 'data')
    expect(await ensurePrivateDirectory(target)).toBe(target)
    await expect(verifyPrivateFile(target)).resolves.toBeUndefined()
  })

  test('writePrivateFile round-trips content and passes DACL read-back', async () => {
    const file = join(workdir, 'secret')
    await writePrivateFile(file, 'token-value')
    expect(readFileSync(file, 'utf8')).toBe('token-value')
    await expect(verifyPrivateFile(file)).resolves.toBeUndefined()
  })

  test('verifyPrivateFile rejects a world-readable file', async () => {
    const file = join(workdir, 'loose')
    writeFileSync(file, 'x')
    // 未设置 owner-only DACL 的新文件继承默认（包含其它主体的 allow ACE），读回必须失败。
    await expect(verifyPrivateFile(file)).rejects.toThrow()
  })

  test('writePrivateFile fails closed instead of overwriting a loosely permissioned file', async () => {
    const file = join(workdir, 'loose')
    writeFileSync(file, 'original')
    // 既有目标 DACL 宽松：必须在写入前失败关闭，绝不先截断改写再收紧。
    await expect(writePrivateFile(file, 'attacker')).rejects.toThrow()
    expect(readFileSync(file, 'utf8')).toBe('original')
  })

  test('writePrivateFile atomically replaces an existing private file and leaves no temp file', async () => {
    const file = join(workdir, 'secret')
    await writePrivateFile(file, 'first')
    await writePrivateFile(file, 'second')
    expect(readFileSync(file, 'utf8')).toBe('second')
    await expect(verifyPrivateFile(file)).resolves.toBeUndefined()
    expect(readdirSync(workdir).filter((name) => name.endsWith('.tmp'))).toEqual([])
  })

  test('a reparse-point lock path fails closed', async () => {
    const data = join(workdir, 'data')
    const elsewhere = join(workdir, 'elsewhere')
    mkdirSync(data, { recursive: true })
    mkdirSync(elsewhere, { recursive: true })
    // junction 也是 reparse 点：只查 isSymbolicLink 会漏掉，必须由 GetFileAttributesW 一并拒绝。
    symlinkSync(elsewhere, join(data, 'daemon.lock'), 'junction')
    await expect(acquireDirectoryLock(data)).rejects.toThrow(/must not be a (symbolic link|reparse point)/)
  })

  test('a second acquisition of the same directory fails until released', async () => {
    const lock = await acquireDirectoryLock(workdir)
    await expect(acquireDirectoryLock(workdir)).rejects.toThrow(/already locked/)
    await lock.close()
    const again = await acquireDirectoryLock(workdir)
    await again.close()
  })

  test('the kernel releases the lock when an external holder crashes', async () => {
    const { spawn } = await import('node:child_process')
    const { fixturePath, awaitGone, isAlive } = await import('./process-test-util.js')
    const ready = join(workdir, 'holder.ready')
    const holder = spawn(process.execPath, [fixturePath('lock-holder-win.mjs'), workdir, ready], { stdio: 'ignore' })
    const pid = Number(await waitForReady(ready))
    expect(isAlive(pid)).toBe(true)
    await expect(acquireDirectoryLock(workdir)).rejects.toThrow(/already locked/)

    holder.kill()
    await new Promise((resolve) => holder.on('exit', resolve))
    expect(await awaitGone(pid)).toBe(true)
    const lock = await acquireDirectoryLock(workdir)
    await lock.close()
  }, 30_000)

  test('a lock file left behind does not block a later acquisition', async () => {
    writeFileSync(join(workdir, 'daemon.lock'), '')
    expect(existsSync(join(workdir, 'daemon.lock'))).toBe(true)
    const lock = await acquireDirectoryLock(workdir)
    await lock.close()
  })
})

async function waitForReady(file: string, budgetMillis = 20_000): Promise<string> {
  const { existsSync: exists, readFileSync: read } = await import('node:fs')
  const { setTimeout: sleep } = await import('node:timers/promises')
  const deadline = Date.now() + budgetMillis
  while (!exists(file)) {
    if (Date.now() >= deadline) {
      throw new Error(`timed out waiting for ${file}`)
    }
    await sleep(5)
  }
  return read(file, 'utf8').trim()
}
