import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, test } from 'vitest'
import { ensurePrivateDirectory, verifyPrivateFile, writePrivateFile } from '../../src/native/index.js'

/**
 * 私有路径探测必须区分「不存在」（ENOENT，交给创建步骤）与「存在但读不到」（其它 errno，必须显式失败）。
 * 把后者当成「不存在」会让创建步骤落到一个错误的路径上。
 */
describe.skipIf(process.platform === 'win32')('native private filesystem io errors', () => {
  const dirs: string[] = []

  afterEach(() => {
    for (const dir of dirs.splice(0)) {
      rmSync(dir, { recursive: true, force: true })
    }
  })

  function workdir(): string {
    const dir = mkdtempSync(join(tmpdir(), 'kk-studio-native-io-'))
    dirs.push(dir)
    return dir
  }

  test('a path whose parent is a regular file fails loudly instead of being treated as missing', async () => {
    const dir = workdir()
    const file = join(dir, 'plain')
    await writePrivateFile(file, 'x')
    // `plain/child` 触发 ENOTDIR：不是 ENOENT，必须原样抛出而不是当成「可以创建」。
    await expect(ensurePrivateDirectory(join(file, 'child'))).rejects.toMatchObject({ code: 'ENOTDIR' })
    await expect(writePrivateFile(join(file, 'child'), 'y')).rejects.toMatchObject({ code: 'ENOTDIR' })
    await expect(verifyPrivateFile(join(file, 'child'))).rejects.toBeInstanceOf(Error)
  })
})
