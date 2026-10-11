import { afterEach, describe, expect, it } from 'vitest'
import { chmod, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  OutputSpool,
  TextOutputStore,
  TemporaryResourceStore,
  alignToCharacterBoundary,
  leadingCharacterBoundary,
  writeAll,
  type PrivateFs,
} from '../../src/coding/output.js'
import { fileCapabilities, buildDiff, parseLineEndingDocument } from '../../src/coding/files.js'
import { searchCapabilities } from '../../src/coding/search.js'
import { callCapability, makeConfig, makeContext, openTestStore, textOf } from './helpers.js'
import { ensurePrivateDirectory } from '../../src/native/index.js'

const tempDirs: string[] = []

async function tempDir(): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), 'kk-cov-'))
  tempDirs.push(dir)
  return dir
}

afterEach(async () => {
  await Promise.all(
    tempDirs.splice(0).map(async (dir) => {
      await chmod(dir, 0o755).catch(() => {})
      await rm(dir, { recursive: true, force: true }).catch(() => {})
    }),
  )
})

function utf8(value: string): Uint8Array {
  return new Uint8Array(Buffer.from(value, 'utf8'))
}

describe('OutputSpool 分支', () => {
  it('构造参数必须为正', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    expect(() => new OutputSpool(store, 'c', 0, 1, 1)).toThrowError(/positive/)
  })

  it('空写入无副作用，CRLF 只记一次换行', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const spool = new OutputSpool(store, 'c')
    expect(spool.totalLines()).toBe(0)
    expect(spool.totalBytes()).toBe(0)
    await spool.write(new Uint8Array())
    await spool.write(utf8('a\r\nb\n'))
    expect(spool.totalLines()).toBe(2)
    expect(spool.totalBytes()).toBe(5)
  })

  it('达到捕获预算后的写入提前返回', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const spool = new OutputSpool(store, 'c', 5, 2000, 10)
    await spool.write(utf8('x'.repeat(50)))
    const before = spool.capturedBytes()
    await spool.write(utf8('y'.repeat(50)))
    expect(spool.capturedBytes()).toBe(before)
    expect(spool.isCaptureTruncated()).toBe(true)
  })

  it('落盘失败时只降级为有界预览并明确说明', async () => {
    const dir = await tempDir()
    const failing: PrivateFs = {
      ensurePrivateDirectory,
      writePrivateFile: async () => {
        throw new Error('private fs unavailable')
      },
    }
    const store = await TextOutputStore.open(dir, undefined, undefined, failing)
    const spool = new OutputSpool(store, 'c', 5, 2000, store.captureBudgetBytes())
    await spool.write(utf8('z'.repeat(100)))
    expect(spool.isCaptureFailed()).toBe(true)
    const result = await spool.finish(false)
    expect(textOf(result)).toContain('could not be saved to local storage')
    await spool.close()
  })

  it('已落盘结果可追加终结说明', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const spool = new OutputSpool(store, 'c', 5, 2000, store.captureBudgetBytes())
    await spool.write(utf8('q'.repeat(100)))
    const result = await spool.finish(false, 'exit code 0')
    expect(textOf(result)).toContain('exit code 0')
    await spool.close()
  })

  it('listFilesWithSuffix 忽略根下非目录条目', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    await mkdir(store.root(), { recursive: true })
    await writeFile(join(store.root(), 'stray.txt'), 'x')
    expect(await store.publishedFiles()).toEqual([])
    expect(await store.partialFiles()).toEqual([])
  })

  it('publish 未登记的 .part 也可发布', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    await mkdir(store.root(), { recursive: true })
    const orphan = join(store.root(), 'orphan.part')
    await writeFile(orphan, 'x')
    const published = await store.publish(orphan)
    expect(published.endsWith('.log')).toBe(true)
  })
})

describe('TemporaryResourceStore 分支', () => {
  it('acquire 对非 UUID 段与不存在 workspace 返回 no-op', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const nonUuid = await store.acquire(join(store.root(), 'not-uuid', 'f'))
    expect(() => nonUuid.close()).not.toThrow()
    const missing = await store.acquire(join(store.root(), '00000000-0000-4000-8000-000000000000', 'f'))
    expect(() => missing.close()).not.toThrow()
  })

  it('sweep 拒绝非正 TTL 并跳过非 UUID 条目', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    await mkdir(join(store.root(), 'not-uuid'), { recursive: true })
    await expect(store.sweep(0)).rejects.toThrowError(/positive/)
    expect(await store.sweep(10)).toBe(0)
  })

  it('重复 acquire 共享同一 lease 计数', async () => {
    const dir = await tempDir()
    let now = 1000
    const store = await openTestStore(dir, undefined, () => now)
    const staging = await store.createStagingFile('call')
    const published = await store.publish(staging)
    const first = await store.acquire(published)
    const second = await store.acquire(published)
    now += 100
    first.close()
    expect(await store.sweep(10)).toBe(0)
    second.close()
    expect(await store.sweep(10)).toBe(1)
  })

  it('UTF-8 边界辅助覆盖 2/3/4 字节序列', () => {
    expect(alignToCharacterBoundary(new Uint8Array([0xc3, 0xa9]), 1)).toBe(0)
    expect(alignToCharacterBoundary(new Uint8Array([0xf0, 0x9f, 0x98]), 3)).toBe(0)
    expect(alignToCharacterBoundary(new Uint8Array([0x80]), 1)).toBe(0)
    expect(alignToCharacterBoundary(new Uint8Array([0x41]), 0)).toBe(0)
    expect(leadingCharacterBoundary(new Uint8Array([0x80, 0x80]))).toBe(2)
  })

  it('Workspace.close 幂等：double close 只释放一次租约', async () => {
    const dir = await tempDir()
    let now = 1000
    const resources = await TemporaryResourceStore.open(dir, () => now)
    const workspace = await resources.create()
    expect(resources.hasActiveLease(workspace.id)).toBe(true)
    workspace.close()
    workspace.close()
    expect(resources.hasActiveLease(workspace.id)).toBe(false)
    now += 100
    // 释放后即可被 TTL 清扫，且不会因为第二次 close 而影响其它租约。
    expect(await resources.sweep(10)).toBe(1)
  })

  it('writeAll 补齐短写并拒绝无进展', async () => {
    const bytes = Uint8Array.from([1, 2, 3, 4, 5])
    const received: number[] = []
    const shortWriter = {
      async write(buffer: Uint8Array, offset: number, length: number) {
        const take = Math.min(2, length)
        for (let index = 0; index < take; index++) {
          received.push(buffer[offset + index] as number)
        }
        return { bytesWritten: take }
      },
    }
    await writeAll(shortWriter, bytes)
    expect(received).toEqual([1, 2, 3, 4, 5])
    const stuck = { async write() { return { bytesWritten: 0 } } }
    await expect(writeAll(stuck, bytes)).rejects.toThrowError(/no progress/)
  })

  it.skipIf(process.platform !== 'linux')('发布与关闭后不泄漏文件描述符', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const before = (await readdir('/proc/self/fd')).length
    for (let index = 0; index < 5; index++) {
      const spool = new OutputSpool(store, `c${index}`, 5, 2000, store.captureBudgetBytes())
      await spool.write(utf8('x'.repeat(2000)))
      await spool.finish(false)
      await spool.close()
    }
    const after = (await readdir('/proc/self/fd')).length
    expect(after).toBeLessThanOrEqual(before)
  })
})

describe('files/search 覆盖补充', () => {
  it('无扩展名文件不报告 LSP', async () => {
    const dir = await tempDir()
    const file = join(dir, 'Makefile')
    await writeFile(file, 'all:\n')
    const config = makeConfig(dir)
    config.lsp = {
      typescript: { command: ['ts'], extensions: ['.ts'], rootMarkers: [], firstMatchMarkers: [] },
    }
    const read = fileCapabilities['fs.read']!
    const result = await callCapability(read, 'fs.read', { path: file }, makeContext(dir, { config }))
    expect(textOf(result)).not.toContain('lsp:')
  })

  it('edit 插入多行并保留既有行尾', async () => {
    const dir = await tempDir()
    const file = join(dir, 'ins.txt')
    await writeFile(file, 'a\r\nc\r\n')
    const edit = fileCapabilities['fs.edit']!
    const result = await callCapability(
      edit,
      'fs.edit',
      { path: file, old_string: 'a\r\nc', new_string: 'a\r\nb\r\nc' },
      makeContext(dir),
    )
    expect(result.error).toBe(false)
    expect(await readFile(file, 'utf8')).toBe('a\r\nb\r\nc\r\n')
  })

  it('edit 删除跨行文本', async () => {
    const dir = await tempDir()
    const file = join(dir, 'del.txt')
    await writeFile(file, 'a\nb\nc\n')
    const edit = fileCapabilities['fs.edit']!
    const result = await callCapability(
      edit,
      'fs.edit',
      { path: file, old_string: 'a\nb\n', new_string: '' },
      makeContext(dir),
    )
    expect(result.error).toBe(false)
    expect(await readFile(file, 'utf8')).toBe('c\n')
  })

  it('buildDiff 覆盖首尾变更的前导/尾随上下文', () => {
    const lines = Array.from({ length: 30 }, (_value, index) => `l${index}`)
    const changed = [...lines]
    changed[0] = 'first'
    changed[29] = 'last'
    const diff = buildDiff(
      parseLineEndingDocument(`${lines.join('\n')}\n`),
      parseLineEndingDocument(`${changed.join('\n')}\n`),
    )
    expect(diff).toContain('+ 1|first')
    expect(diff).toContain('+30|last')
  })

  it('grep 无命中但存在不可搜索路径时返回错误', async () => {
    const dir = await tempDir()
    const blocked = join(dir, 'blocked')
    await mkdir(blocked)
    await writeFile(join(blocked, 'x.txt'), 'x\n')
    await chmod(blocked, 0o000)
    try {
      const grep = searchCapabilities['fs.grep']!
      const result = await callCapability(grep, 'fs.grep', { pattern: 'zzz', path: dir }, makeContext(dir))
      expect(result.error).toBe(true)
      expect(textOf(result)).toContain('could not be searched')
    } finally {
      await chmod(blocked, 0o755)
    }
  })

  it('find 摘要对超过 10 条未搜索路径折叠', async () => {
    const dir = await tempDir()
    for (let index = 0; index < 12; index++) {
      const blocked = join(dir, `blocked${index}`)
      await mkdir(blocked)
      await writeFile(join(blocked, 'x.txt'), '')
      await chmod(blocked, 0o000)
    }
    try {
      const find = searchCapabilities['fs.find']!
      const result = await callCapability(
        find,
        'fs.find',
        { pattern: '*.none', path: dir },
        makeContext(dir),
      )
      expect(result.error).toBe(true)
      expect(textOf(result)).toContain('...')
    } finally {
      for (let index = 0; index < 12; index++) {
        await chmod(join(dir, `blocked${index}`), 0o755)
      }
    }
  })
})
