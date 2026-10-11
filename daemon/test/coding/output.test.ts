import { afterEach, describe, expect, it } from 'vitest'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { textResult } from '../../src/contracts.js'
import { openTestStore } from './helpers.js'
import {
  OutputSpool,
  spoolLargeTextResult,
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
    expect(spool.isSpilled()).toBe(false)
    expect(textOf(result)).toBe('hi\n')
    expect(result.details).toEqual({})
  })

  it('跨阈值落盘并原子发布 durable 全文', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const spool = new OutputSpool(store, 'c2', 10, 5, store.captureBudgetBytes())
    await spool.write(utf8('x'.repeat(100)))
    expect(spool.isSpilled()).toBe(true)
    const result = await spool.finish(false)
    const textOutput = result.details.textOutput as { totalBytes: number; path: string }
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
    expect(spool.capturedBytes()).toBe(50)
    const result = await spool.finish(false)
    expect((result.details.textOutput as { captureTruncated: boolean }).captureTruncated).toBe(true)
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

describe('spoolLargeTextResult', () => {
  it('大文本外化并在 details 携带 textOutput', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const result = await spoolLargeTextResult(store, textResult('c', 'z'.repeat(60000)))
    expect(result.details.textOutput).toBeDefined()
  })

  it('小文本原样返回', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const result = await spoolLargeTextResult(store, textResult('c', 'small'))
    expect(result.details).toEqual({})
  })
})
