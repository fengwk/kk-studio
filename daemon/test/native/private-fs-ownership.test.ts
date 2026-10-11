import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, test, vi } from 'vitest'
import { ensurePrivateDirectory, verifyPrivateFile, writePrivateFile } from '../../src/native/index.js'

/**
 * 属主校验是安全边界：即使权限位恰好是 0600，只要属主不是当前用户，读回验证就必须拒绝，
 * 绝不能在别人的文件上继续完成「属主独占」的流程。
 */
describe.skipIf(process.platform === 'win32')('native private filesystem ownership checks', () => {
  const dirs: string[] = []

  afterEach(() => {
    vi.restoreAllMocks()
    for (const dir of dirs.splice(0)) {
      rmSync(dir, { recursive: true, force: true })
    }
  })

  function workdir(): string {
    const dir = mkdtempSync(join(tmpdir(), 'kk-studio-native-owner-'))
    dirs.push(dir)
    return dir
  }

  test('a file owned by another user is rejected even with owner-only mode', async () => {
    const dir = workdir()
    const file = join(dir, 'secret')
    await writePrivateFile(file, 'x')
    const realUid = process.getuid?.() ?? 0
    const spy = vi.spyOn(process, 'getuid').mockReturnValue(realUid + 1)
    try {
      await expect(verifyPrivateFile(file)).rejects.toThrow(/not owned by the current user/)
    } finally {
      spy.mockRestore()
    }
  })

  test('a directory owned by another user is not chmodded', async () => {
    const dir = workdir()
    const target = join(dir, 'data')
    await ensurePrivateDirectory(target)
    const realUid = process.getuid?.() ?? 0
    const spy = vi.spyOn(process, 'getuid').mockReturnValue(realUid + 1)
    try {
      await expect(writePrivateFile(join(target, 'secret'), 'x')).rejects.toThrow(/not owned by the current user/)
    } finally {
      spy.mockRestore()
    }
  })
})
