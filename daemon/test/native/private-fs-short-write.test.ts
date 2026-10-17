import { mkdtempSync, readdirSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

// `writeSync` 返回 0（短写为 0）必须失败关闭：绝不改名、绝不留残余，且临时文件要被清理。
// 这里只替换 node:fs 的 writeSync，其余调用仍是真实实现，验证的是 `private-fs` 真实的补写/清理分支。
vi.mock('node:fs', async (importOriginal) => {
  const actual = await importOriginal<typeof import('node:fs')>()
  return { ...actual, writeSync: () => 0 }
})

import { writePrivateFile } from '../../src/native/index.js'

describe.skipIf(process.platform === 'win32')('native private file short-write handling', () => {
  let workdir: string

  beforeEach(() => {
    workdir = mkdtempSync(join(tmpdir(), 'kk-studio-native-shortwrite-'))
  })

  afterEach(() => {
    rmSync(workdir, { recursive: true, force: true })
  })

  test('a zero-length short write fails closed and leaves no temporary file', async () => {
    const file = join(workdir, 'secret')
    await expect(writePrivateFile(file, 'payload')).rejects.toThrow(/could not be written completely/)
    // 目标绝不能出现，临时文件也必须被清理：失败不能改变磁盘状态。
    expect(readdirSync(workdir)).toEqual([])
  })
})
