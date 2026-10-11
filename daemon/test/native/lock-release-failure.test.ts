import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

// 释放目录锁时 `flock(LOCK_UN)` 失败必须显式抛出：绝不能对外宣称「已经释放」。
// 只把 koffi 的 flock 绑定替换为「加锁成功/解锁失败」，其余调用仍是真实实现。
vi.mock('koffi', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  const library = {
    func: (signature: string) =>
      signature.includes('flock') ? (_descriptor: number, operation: number) => (operation & 8 ? -1 : 0) : () => 0,
  }
  const stubbed = { ...actual, load: () => library, errno: () => 1 }
  return { ...stubbed, default: stubbed }
})

import { acquireDirectoryLock } from '../../src/native/index.js'

describe.skipIf(process.platform === 'win32')('native directory lock release failure', () => {
  let workdir: string

  beforeEach(() => {
    workdir = mkdtempSync(join(tmpdir(), 'kk-studio-native-lock-unlock-'))
  })

  afterEach(() => {
    rmSync(workdir, { recursive: true, force: true })
  })

  test('a failed unlock surfaces instead of pretending the lock was released', async () => {
    const lock = await acquireDirectoryLock(workdir)
    await expect(lock.close()).rejects.toThrow(/cannot release the directory lock/)
  })
})
