import { afterEach, describe, expect, it, vi } from 'vitest'
import { existsSync } from 'node:fs'
import { mkdtemp, open, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { OutputSpool, type TextOutputStore } from '../../src/coding/output.js'

/**
 * 输出捕获失败语义：真实的磁盘写入/落盘失败绝不伪装成“已保存完整全文”。
 *
 * <p>真实 `/dev/full` 覆盖写入失败；fsync 失败在真实文件句柄上注入，避免依赖设备的平台语义。关闭句柄仍走真实调用。
 */

vi.mock('node:fs/promises', async (importOriginal) => {
  const original = await importOriginal<typeof import('node:fs/promises')>()
  return { ...original, open: vi.fn(original.open) }
})

const tempDirs: string[] = []

async function tempDir(): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), 'kk-outfail-'))
  tempDirs.push(dir)
  return dir
}

afterEach(async () => {
  vi.restoreAllMocks()
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

  it('budget 截断后的 fsync 失败绝不发布 full output', async () => {
    const directory = await tempDir()
    const staging = join(directory, 'staging.part')
    await writeFile(staging, '')
    const actual = await vi.importActual<typeof import('node:fs/promises')>('node:fs/promises')
    let handle: Awaited<ReturnType<typeof open>> | undefined
    vi.mocked(open).mockImplementationOnce(async (...args) => {
      handle = await actual.open(...args)
      vi.spyOn(handle, 'sync').mockRejectedValue(new Error('fixture fsync failure'))
      return handle
    })
    const published: string[] = []
    const store = fakeStagingStore(staging, published, 50)
    const spool = new OutputSpool(store, 'c', 5, 2000, 50)
    await spool.write(utf8('y'.repeat(100)))
    const result = await spool.finish(false)
    expect(published).toEqual([])
    expect(textOutputOf(result).captureFailed).toBe(true)
    expect(textOutputOf(result).captureTruncated).toBe(true)
    expect(textOutputOf(result).totalBytes).toBe(100)
    expect(textOf(result)).toContain('could not be saved to local storage')
    expect(textOf(result)).not.toContain('Full output:')
    await spool.close()
    expect(handle).toBeDefined()
    expect(handle!.fd).toBe(-1)
  })
})
