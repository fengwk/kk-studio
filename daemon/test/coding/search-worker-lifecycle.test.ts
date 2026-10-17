import { afterEach, describe, expect, it, vi } from 'vitest'
import { mkdtemp, realpath, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { searchCapabilities } from '../../src/coding/search.js'
import type { SearchReply } from '../../src/coding/search-core.js'
import { FakeWorker } from './fake-search-worker.js'
import { callCapability, makeContext } from './helpers.js'

/**
 * 直接锁定 Worker 生命周期契约：正常回传只在 Worker 真正退出后交付（避免其覆盖率刷写与主进程收集竞争），
 * 取消/超时/异常则立即交付稳定结果，绝不等待可能被同步 FS 阻塞的 terminate()。
 */

vi.mock('node:worker_threads', async () => {
  const { FakeWorker } = await import('./fake-search-worker.js')
  return { Worker: FakeWorker }
})

const tempDirs: string[] = []

async function tempDir(): Promise<string> {
  const dir = await realpath(await mkdtemp(join(tmpdir(), 'kk-swlife-')))
  tempDirs.push(dir)
  return dir
}

afterEach(async () => {
  for (const worker of FakeWorker.instances) {
    worker.release()
  }
  FakeWorker.instances.length = 0
  await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })))
})

const find = searchCapabilities['fs.find']!
const grep = searchCapabilities['fs.grep']!

/** 等待执行器创建出受控 Worker 并挂好 message 监听（能力层先做异步路径解析）。 */
async function waitForWorker(): Promise<FakeWorker> {
  await vi.waitFor(() => {
    const worker = FakeWorker.instances.at(-1)
    expect(worker).toBeDefined()
    expect(worker!.listenerCount('message')).toBeGreaterThan(0)
  })
  return FakeWorker.instances.at(-1)!
}

/** 记录 Promise 是否已 settle，用于断言交付时机。 */
function track<T>(promise: Promise<T>): { promise: Promise<T>; settled: () => boolean } {
  let settled = false
  const tracked = promise.then(
    (value) => {
      settled = true
      return value
    },
    (error) => {
      settled = true
      throw error
    },
  )
  return { promise: tracked, settled: () => settled }
}

const noFind: SearchReply = { ok: true, kind: 'find', matches: [], limitReached: false, skipped: [] }
const noGrep: SearchReply = { ok: true, kind: 'grep', lines: [], moreMatches: false, skipped: [] }

describe('Worker 正常回传生命周期', () => {
  it('find 回传后保持 pending，直到 Worker 退出才交付结果', async () => {
    const dir = await tempDir()
    const tracked = track(callCapability(find, 'fs.find', { pattern: '*.zzz', path: dir }, makeContext(dir)))
    const worker = await waitForWorker()
    worker.emit('message', noFind)
    await new Promise((resolve) => setImmediate(resolve))
    // 关键：消息已到达但 Worker 尚未退出，能力调用不得 settle（旧实现会在此过早 resolve）。
    expect(tracked.settled()).toBe(false)
    expect(worker.terminated).toBe(false)
    expect(worker.unrefCount).toBe(0)

    worker.emit('exit', 0)
    const result = await tracked.promise
    expect(result.error).toBe(false)
    expect(result.contents[0]).toMatchObject({ type: 'text', text: 'No files found matching pattern' })
  })

  it('grep 回传后同样等待 Worker 退出', async () => {
    const dir = await tempDir()
    const tracked = track(callCapability(grep, 'fs.grep', { pattern: 'zzz', path: dir }, makeContext(dir)))
    const worker = await waitForWorker()
    worker.emit('message', noGrep)
    await new Promise((resolve) => setImmediate(resolve))
    expect(tracked.settled()).toBe(false)

    worker.emit('exit', 0)
    const result = await tracked.promise
    expect(result.error).toBe(false)
    expect(result.contents[0]).toMatchObject({ type: 'text', text: 'No matches found' })
  })
})

describe('Worker 取消/超时/异常生命周期', () => {
  it('取消立即 reject，不等待未完成的 terminate()', async () => {
    const dir = await tempDir()
    const controller = new AbortController()
    const tracked = track(
      callCapability(grep, 'fs.grep', { pattern: 'zzz', path: dir }, makeContext(dir, { signal: controller.signal })),
    )
    const worker = await waitForWorker()
    controller.abort()
    const result = await tracked.promise
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ code: 'CANCELLED' })
    // terminate() 被调用但其 Promise 尚未完成，调用却已返回稳定结果。
    expect(worker.terminated).toBe(true)
    expect(worker.terminateSettled).toBe(false)
  })

  it('超时立即 reject，不等待未完成的 terminate()', async () => {
    const dir = await tempDir()
    const tracked = track(callCapability(grep, 'fs.grep', { pattern: 'zzz', path: dir }, makeContext(dir), 5))
    const worker = await waitForWorker()
    const result = await tracked.promise
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ code: 'TIMEOUT' })
    expect(worker.terminated).toBe(true)
    expect(worker.terminateSettled).toBe(false)
  })

  it('Worker error 事件直接以该错误失败', async () => {
    const dir = await tempDir()
    const tracked = track(callCapability(find, 'fs.find', { pattern: '*', path: dir }, makeContext(dir)))
    const worker = await waitForWorker()
    worker.emit('error', new Error('worker exploded'))
    // 能力层把未知错误映射为不泄露细节的 INTERNAL 失败，但仍是稳定的错误结果。
    const result = await tracked.promise
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ code: 'INTERNAL' })
    expect(worker.terminated).toBe(true)
  })

  it('Worker 未回传即退出报告 INTERNAL 异常退出', async () => {
    const dir = await tempDir()
    const tracked = track(callCapability(find, 'fs.find', { pattern: '*', path: dir }, makeContext(dir)))
    const worker = await waitForWorker()
    worker.emit('exit', 3)
    const result = await tracked.promise
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ code: 'INTERNAL' })
    expect(JSON.stringify(result.contents)).toContain('exited unexpectedly (code 3)')
  })
})
