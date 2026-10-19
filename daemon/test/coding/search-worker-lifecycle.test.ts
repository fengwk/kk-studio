import { afterEach, describe, expect, it, vi } from 'vitest'
import { mkdtemp, realpath, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { searchCapabilities } from '../../src/coding/search.js'
import type { SearchReply } from '../../src/coding/search-core.js'
import { FakeWorker } from './fake-search-worker.js'
import { callCapability, makeContext } from './helpers.js'

/**
 * 直接锁定 Worker 生命周期契约：正常回传和协作停止都只在 Worker 真正退出后交付，
 * 收到 stop 后启动单个 5s 清理宽限，5s 仍无响应才 await terminate()；异常也 await terminate()。
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

const noFind: SearchReply = { ok: true, kind: 'find', matches: [], limitReached: false, skipped: [], stopReason: null }
const noGrep: SearchReply = { ok: true, kind: 'grep', lines: [], moreMatches: false, skipped: [], stopReason: null }

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

describe('Worker 协作停止与宽限超时生命周期', () => {
  it('signal abort 协作停止：Worker 回传 partial 并在 exit 后交付', async () => {
    const dir = await tempDir()
    const controller = new AbortController()
    const tracked = track(
      callCapability(grep, 'fs.grep', { pattern: 'zzz', path: dir }, makeContext(dir, { signal: controller.signal })),
    )
    const worker = await waitForWorker()
    // 触发取消
    controller.abort()

    // 检查 job 中下发的 stopBuffer 是否已经被 store 设为 2 (CANCELLED)
    const job = worker.posted[0] as { stopBuffer: SharedArrayBuffer }
    expect(job.stopBuffer).toBeDefined()
    expect(Atomics.load(new Int32Array(job.stopBuffer), 0)).toBe(2)

    // 模拟 worker 协作停止，回传 partial 结果带 stopReason='CANCELLED'
    worker.emit('message', {
      ok: true,
      kind: 'grep',
      lines: [`${dir}/a.txt:1:match`],
      moreMatches: false,
      skipped: [],
      stopReason: 'CANCELLED',
    })

    await new Promise((resolve) => setImmediate(resolve))
    // 关键：worker 尚未 exit 时，保持 pending
    expect(tracked.settled()).toBe(false)
    expect(worker.terminated).toBe(false)

    // worker 自然退出
    worker.emit('exit', 0)
    const result = await tracked.promise
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({
      code: 'CANCELLED',
      search: { outcome: 'CANCELLED', incomplete: true },
    })
    expect(result.contents[0]).toMatchObject({
      type: 'text',
      text: `${dir}/a.txt:1:match\n\n[Search cancelled; results are incomplete.]`,
    })
    // 协作停止未触发 terminate()
    expect(worker.terminated).toBe(false)
  })

  it('signal TIMED_OUT 协作停止：保留 partial 结果并追加超时提示', async () => {
    const dir = await tempDir()
    const controller = new AbortController()
    const tracked = track(
      callCapability(find, 'fs.find', { pattern: '*.ts', path: dir }, makeContext(dir, { signal: controller.signal })),
    )
    const worker = await waitForWorker()
    controller.abort('TIMED_OUT')

    const job = worker.posted[0] as { stopBuffer: SharedArrayBuffer }
    expect(Atomics.load(new Int32Array(job.stopBuffer), 0)).toBe(1)

    worker.emit('message', {
      ok: true,
      kind: 'find',
      matches: [`${dir}/a.ts`],
      limitReached: false,
      skipped: [],
      stopReason: 'TIMED_OUT',
    })
    worker.emit('exit', 0)

    const result = await tracked.promise
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({
      code: 'TIMEOUT',
      search: { outcome: 'TIMED_OUT', incomplete: true },
    })
    expect(result.contents[0]).toMatchObject({
      type: 'text',
      text: `${dir}/a.ts\n\n[Search timed out; results are incomplete.]`,
    })
  })

  it('5s 清理宽限超时仍无响应时 await terminate() 并以错误交付', async () => {
    const dir = await tempDir()
    const controller = new AbortController()
    const tracked = track(
      callCapability(grep, 'fs.grep', { pattern: 'zzz', path: dir }, makeContext(dir, { signal: controller.signal })),
    )
    const worker = await waitForWorker()

    vi.useFakeTimers()
    try {
      controller.abort('TIMED_OUT')

      // 在 5s 宽限期前未 terminate
      await vi.advanceTimersByTimeAsync(4000)
      expect(worker.terminated).toBe(false)
      expect(tracked.settled()).toBe(false)

      // 宽限期满 5s
      await vi.advanceTimersByTimeAsync(1000)
      expect(worker.terminated).toBe(true)
      expect(worker.terminateSettled).toBe(true)

      const result = await tracked.promise
      expect(result.error).toBe(true)
      expect(result.details).toMatchObject({ code: 'TIMEOUT' })
    } finally {
      vi.useRealTimers()
    }
  })

  it('Worker error 事件直接以该错误失败并 await terminate()', async () => {
    const dir = await tempDir()
    const tracked = track(callCapability(find, 'fs.find', { pattern: '*', path: dir }, makeContext(dir)))
    const worker = await waitForWorker()
    worker.emit('error', new Error('worker exploded'))
    // 能力层把未知错误映射为不泄露细节的 INTERNAL 失败，但仍是稳定的错误结果。
    const result = await tracked.promise
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ code: 'INTERNAL' })
    expect(worker.terminated).toBe(true)
    expect(worker.terminateSettled).toBe(true)
  })

  it('Worker 未回传即退出报告 INTERNAL 异常退出', async () => {
    const dir = await tempDir()
    const tracked = track(callCapability(find, 'fs.find', { pattern: '*', path: dir }, makeContext(dir)))
    const worker = await waitForWorker()
    worker.emit('exit', 3)
    const result = await tracked.promise
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ code: 'INTERNAL' })
    expect(JSON.stringify(result.contents)).toContain('exited unexpectedly (code 3); results incomplete')
  })

  it('Worker 回传数据后异常退出（code 非 0）必须失败且标记 results incomplete', async () => {
    const dir = await tempDir()
    const tracked = track(callCapability(find, 'fs.find', { pattern: '*', path: dir }, makeContext(dir)))
    const worker = await waitForWorker()
    worker.emit('message', {
      ok: true,
      kind: 'find',
      matches: [`${dir}/a.ts`],
      limitReached: false,
      skipped: [],
      stopReason: null,
    })
    worker.emit('exit', 1)
    const result = await tracked.promise
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ code: 'INTERNAL' })
    expect(JSON.stringify(result.contents)).toContain('the search worker exited unexpectedly (code 1); results incomplete')
  })

  it('postMessage 同步抛出时 await terminate 并以 INTERNAL 失败', async () => {
    const dir = await tempDir()
    FakeWorker.nextPostMessageError = new Error('structured clone failure')
    const result = await callCapability(find, 'fs.find', { pattern: '*', path: dir }, makeContext(dir))
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ code: 'INTERNAL' })
    const worker = FakeWorker.instances.at(-1)!
    expect(worker.terminated).toBe(true)
  })

  it('worker.terminate 失败时明确交付 cleanup failed; results incomplete', async () => {
    const dir = await tempDir()
    FakeWorker.nextTerminateError = new Error('kill permission denied')
    const tracked = track(callCapability(find, 'fs.find', { pattern: '*', path: dir }, makeContext(dir)))
    const worker = await waitForWorker()
    worker.emit('error', new Error('worker crashed'))
    const result = await tracked.promise
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ code: 'INTERNAL' })
    expect(JSON.stringify(result.contents)).toContain('cleanup failed; results incomplete')
  })

  it('context abort 遇到 worker 内部错误时保留原 INTERNAL 错误码并补充 search incomplete', async () => {
    const dir = await tempDir()
    const controller = new AbortController()
    controller.abort('TIMED_OUT')
    const tracked = track(
      callCapability(find, 'fs.find', { pattern: '*', path: dir }, makeContext(dir, { signal: controller.signal })),
    )
    const worker = await waitForWorker()
    worker.emit('error', new Error('disk read failed'))
    const result = await tracked.promise
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({
      code: 'INTERNAL',
      search: { outcome: 'TIMED_OUT', incomplete: true },
    })
  })
  it('Worker 回传 kind 不匹配时以 INTERNAL 报错', async () => {
    const dir = await tempDir()
    const tracked = track(callCapability(find, 'fs.find', { pattern: '*', path: dir }, makeContext(dir)))
    const worker = await waitForWorker()
    worker.emit('message', noGrep)
    worker.emit('exit', 0)
    const result = await tracked.promise
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ code: 'INTERNAL' })
    expect(JSON.stringify(result.contents)).toContain('mismatched result')
  })

  it('无匹配但存在 skipped 路径时返回警告型错误', async () => {
    const dir = await tempDir()
    const tracked = track(callCapability(find, 'fs.find', { pattern: '*', path: dir }, makeContext(dir)))
    const worker = await waitForWorker()
    worker.emit('message', {
      ok: true,
      kind: 'find',
      matches: [],
      limitReached: false,
      skipped: [{ path: '/blocked', reason: 'denied' }],
      stopReason: null,
    })
    worker.emit('exit', 0)
    const result = await tracked.promise
    expect(result.error).toBe(true)
    expect(JSON.stringify(result.contents)).toContain('path(s) could not be searched')
  })
})

describe('参数校验与 Worker 创建隔离', () => {
  it('非法参数在能力入口直接被拒绝且不创建 Worker', async () => {
    const dir = await tempDir()
    // 缺少必填参数 pattern
    const result1 = await callCapability(find, 'fs.find', { path: dir }, makeContext(dir))
    expect(result1.error).toBe(true)
    expect(result1.details).toMatchObject({ code: 'INVALID_ARGUMENT' })
    expect(FakeWorker.instances).toHaveLength(0)

    // 非法 limit (<= 0)
    const result2 = await callCapability(grep, 'fs.grep', { pattern: 'x', path: dir, limit: 0 }, makeContext(dir))
    expect(result2.error).toBe(true)
    expect(result2.details).toMatchObject({ code: 'INVALID_ARGUMENT' })
    expect(FakeWorker.instances).toHaveLength(0)

    // 不存在的 path
    const result3 = await callCapability(find, 'fs.find', { pattern: '*.ts', path: join(dir, 'missing') }, makeContext(dir))
    expect(result3.error).toBe(true)
    expect(result3.details).toMatchObject({ code: 'NOT_FOUND' })
    expect(FakeWorker.instances).toHaveLength(0)
  })
})
