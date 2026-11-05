import { afterEach, describe, expect, it } from 'vitest'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { openTestStore } from './helpers.js'
import {
  OutputSpool,
  releaseTextOutputStore,
  sweepTextOutputStores,
  textOutputStoreFor,
} from '../../src/coding/output.js'

const tempDirs: string[] = []

async function tempDir(): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), 'kk-output-'))
  tempDirs.push(dir)
  return dir
}

afterEach(async () => {
  await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })))
})

function utf8(value: string): Uint8Array {
  return new Uint8Array(Buffer.from(value, 'utf8'))
}

function textOf(result: { contents: Array<{ type: string; text?: string }> }): string {
  const first = result.contents[0]
  return first !== undefined && first.type === 'text' ? (first.text ?? '') : ''
}

describe('OutputSpool', () => {
  it('内联小输出不落盘', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const spool = new OutputSpool(store, 'c1')
    await spool.write(utf8('hi\n'))
    const result = await spool.finish(false)
    expect(result.details.textOutput).toBeUndefined()
    expect(result.details).toEqual({})
    expect(textOf(result)).toBe('hi\n')
    expect(await store.publishedFiles()).toEqual([])
    expect(await store.partialFiles()).toEqual([])
  })

  it('跨阈值落盘并原子发布 durable 全文', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const spool = new OutputSpool(store, 'c2', 10, 5, store.captureBudgetBytes())
    await spool.write(utf8('x'.repeat(100)))
    const result = await spool.finish(false)
    const textOutput = result.details.textOutput as {
      totalBytes: number
      path: string
      captureTruncated: boolean
      captureFailed: boolean
    }
    expect(textOutput).toBeDefined()
    expect(textOutput.captureFailed).toBe(false)
    expect(textOutput.totalBytes).toBe(100)
    expect(await readFile(textOutput.path, 'utf8')).toBe('x'.repeat(100))
    expect(await store.partialFiles()).toEqual([])
    expect(await store.publishedFiles()).toEqual([textOutput.path])
  })

  it('达到捕获预算后停止文件捕获但保留有界预览', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const spool = new OutputSpool(store, 'c3', 10, 5, 50)
    await spool.write(utf8('y'.repeat(200)))
    expect(spool.isCaptureTruncated()).toBe(true)
    const result = await spool.finish(false)
    const textOutput = result.details.textOutput as {
      captureTruncated: boolean
      capturedBytes: number
      totalBytes: number
      path: string
    }
    expect(textOutput.captureTruncated).toBe(true)
    expect(textOutput.capturedBytes).toBe(50)
    expect(textOutput.totalBytes).toBe(200)
    expect(await readFile(textOutput.path, 'utf8')).toBe('y'.repeat(50))
    expect(textOf(result)).toContain('Capture stopped at the local 50-byte daemon budget')
  })

  it('in-use lease 保护已发布全文不被清扫删除', async () => {
    const dir = await tempDir()
    let now = 1000
    const store = await openTestStore(dir, undefined, () => now)
    const staging = await store.createStagingFile('call')
    const published = await store.publish(staging)
    const lease = await store.acquire(published)
    now += 100
    expect(await store.sweep(10)).toBe(0)
    expect(await store.publishedFiles()).toContain(published)
    lease.close()
    expect(await store.sweep(10)).toBe(1)
    expect(await store.publishedFiles()).toEqual([])
  })

  it('未超期的 workspace 不被清扫', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir, undefined, () => 1000)
    const staging = await store.createStagingFile('call')
    await store.publish(staging)
    expect(await store.sweep(10)).toBe(0)
  })
})

describe('text output store lifecycle', () => {
  it('按数据目录复用的写入根就是 TTL 清扫根', async () => {
    const dataDir = await tempDir()
    const store = await textOutputStoreFor(dataDir)
    expect(store.root()).toBe(join(dataDir, 'tmp', 'workspaces'))
    const staging = await store.createStagingFile('c')
    await store.publish(staging)
    // 把登记时间改为过期，证明 sweepTextOutputStores 清扫的是同一个受控实例。
    await writeFile(join(dirname(staging), 'created-at'), '0')
    expect(await sweepTextOutputStores(10)).toContain(1)
    expect(await releaseTextOutputStore(dataDir)).toBe(true)
  })
})
