import { afterEach, describe, expect, it } from 'vitest'
import { existsSync } from 'node:fs'
import { mkdtemp, readdir, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { OutputSpool, type TextOutputStore } from '../../src/coding/output.js'

/**
 * 输出捕获失败语义：真实的磁盘写入/落盘失败绝不伪装成“已保存完整全文”。
 *
 * <p>用真实的 `/dev/full`（写入 ENOSPC）与 `/dev/null`（fsync EINVAL）触发确定性 IO 失败，文件句柄与 close 都是真实调用，
 * 只把 store 的中转路径替换为这些设备。
 */

const tempDirs: string[] = []

async function tempDir(): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), 'kk-outfail-'))
  tempDirs.push(dir)
  return dir
}

afterEach(async () => {
  await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })))
})

function utf8(value: string): Uint8Array {
  return new Uint8Array(Buffer.from(value, 'utf8'))
}

function fakeStagingStore(
  stagingPath: string,
  published: string[],
  budget = 1_000_000,
): TextOutputStore {
  return {
    captureBudgetBytes: () => budget,
    createStagingFile: async () => stagingPath,
    publish: async (file: string) => {
      published.push(file)
      return file.replace(/\.part$/, '.log')
    },
    deleteStagingQuietly: async () => {},
  } as unknown as TextOutputStore
}

function textOutputOf(result: { details: Record<string, unknown> }): {
  captureFailed?: boolean
  captureTruncated?: boolean
  totalBytes?: number
  path?: string
} {
  return (result.details as { textOutput?: never }).textOutput ?? {}
}

function textOf(result: { contents: unknown[] }): string {
  const first = result.contents[0] as { type?: string; text?: string } | undefined
  return first !== undefined && first.type === 'text' ? (first.text ?? '') : ''
}

describe('输出落盘失败', () => {
  it.skipIf(!existsSync('/dev/full'))('写入失败绝不发布 full output', async () => {
    await tempDir()
    const published: string[] = []
    const store = fakeStagingStore('/dev/full', published)
    const spool = new OutputSpool(store, 'c', 5, 2000, 1_000_000)
    await spool.write(utf8('x'.repeat(100)))
    expect(spool.isCaptureFailed()).toBe(true)
    const result = await spool.finish(false)
    expect(published).toEqual([])
    expect(textOutputOf(result).captureFailed).toBe(true)
    expect(textOutputOf(result).totalBytes).toBe(100)
    expect(textOf(result)).toContain('could not be saved to local storage')
    expect(textOf(result)).not.toContain('Full output:')
    await spool.close()
  })

  it.skipIf(!existsSync('/dev/null'))('budget 截断后的 fsync 失败绝不发布 full output', async () => {
    await tempDir()
    const published: string[] = []
    const store = fakeStagingStore('/dev/null', published, 50)
    const spool = new OutputSpool(store, 'c', 5, 2000, 50)
    const before = existsSync('/proc/self/fd') ? (await readdir('/proc/self/fd')).length : 0
    await spool.write(utf8('y'.repeat(100)))
    const result = await spool.finish(false)
    expect(published).toEqual([])
    expect(textOutputOf(result).captureFailed).toBe(true)
    expect(textOutputOf(result).captureTruncated).toBe(true)
    expect(textOutputOf(result).totalBytes).toBe(100)
    expect(textOf(result)).toContain('could not be saved to local storage')
    expect(textOf(result)).not.toContain('Full output:')
    await spool.close()
    if (before > 0) {
      // fsync 失败后 FD 仍必须真正关闭：句柄计数不得增长。
      expect((await readdir('/proc/self/fd')).length).toBeLessThanOrEqual(before)
    }
  })
})
