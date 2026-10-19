import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { existsSync } from 'node:fs'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { Worker } from 'node:worker_threads'
import { resolveSearchWorkerEntry, searchCapabilities } from '../../src/coding/search.js'
import { callCapability, makeContext, textOf } from './helpers.js'

/**
 * Worker 只跑编译产物：定位规则必须落在真实的 `dist/coding/search-worker.js`，且缺少编译产物时 fail-closed。
 *
 * <p>测试前置为构建（`npm run build`）；本测试自身也是对“未构建即失败”的显式断言。
 */

const tempDirs: string[] = []

async function tempDir(): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), 'kk-wloc-'))
  tempDirs.push(dir)
  return dir
}

/** 若失败路径请求强制清理，teardown 等待真实 Worker 退出后再删除临时资源。 */
const pendingTerminations: Array<Promise<number>> = []

beforeEach(() => {
  const realTerminate = Worker.prototype.terminate
  vi.spyOn(Worker.prototype, 'terminate').mockImplementation(function (this: Worker) {
    const termination = realTerminate.call(this)
    pendingTerminations.push(termination)
    return termination
  })
})

afterEach(async () => {
  vi.restoreAllMocks()
  await Promise.all(pendingTerminations.splice(0))
  await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })))
})

describe('搜索 Worker 定位', () => {
  it('禁止将 Worker 入口作为主线程模块启动', async () => {
    await expect(import('../../src/coding/search-worker.js')).rejects.toThrow(
      'search worker must be started with a parent port',
    )
  })

  it('默认定位到本项目编译产物 dist/coding/search-worker.js', () => {
    const entry = resolveSearchWorkerEntry()
    expect(entry, '缺少编译产物，请先构建 daemon（dist/coding/search-worker.js）').not.toBeNull()
    const path = fileURLToPath(entry as URL)
    expect(path.endsWith(join('dist', 'coding', 'search-worker.js'))).toBe(true)
    expect(existsSync(path)).toBe(true)
  })

  it('只按模块入口位置解析：同目录优先、源码态回落 dist、缺失即 fail-closed', async () => {
    const root = await tempDir()
    const codingDir = join(root, 'a', 'b', 'coding')
    await mkdir(codingDir, { recursive: true })
    const base = pathToFileURL(join(codingDir, 'search.js'))

    // 同目录与 ../../dist 都不存在 → fail-closed。
    expect(resolveSearchWorkerEntry(base)).toBeNull()

    const sibling = join(codingDir, 'search-worker.js')
    await writeFile(sibling, '')
    expect(fileURLToPath(resolveSearchWorkerEntry(base) as URL)).toBe(sibling)

    await rm(sibling)
    const compiled = join(root, 'a', 'dist', 'coding')
    await mkdir(compiled, { recursive: true })
    const compiledEntry = join(compiled, 'search-worker.js')
    await writeFile(compiledEntry, '')
    expect(fileURLToPath(resolveSearchWorkerEntry(base) as URL)).toBe(compiledEntry)
  })
})

describe('搜索 Worker 执行', () => {
  it('搜索在真实 Worker 中执行，主线程事件循环保持响应', async () => {
    const dir = await tempDir()
    for (let index = 0; index < 3000; index++) {
      await writeFile(join(dir, `f${index}.txt`), 'x\n')
    }
    const grep = searchCapabilities['fs.grep']!
    let ticks = 0
    const interval = setInterval(() => {
      ticks += 1
    }, 1)
    try {
      const result = await callCapability(
        grep,
        'fs.grep',
        { pattern: 'zzz', path: dir },
        makeContext(dir),
      )
      expect(result.error).toBe(false)
      expect(textOf(result)).toBe('No matches found')
    } finally {
      clearInterval(interval)
    }
    // 同步扫描会独占主线程：若退回同步实现，搜索期间该定时器不可能推进。
    expect(ticks).toBeGreaterThan(0)
  })

  it('真实 Worker 在收到超时信号后协作停止，自然退出并明确标记结果不完整', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'a.txt'), 'needle\n')
    const controller = new AbortController()
    const realPostMessage = Worker.prototype.postMessage
    let exited = false
    // 在真实任务下发边界请求停止，不依赖 Worker 启动速度或短墙钟预算。
    const posted = vi.spyOn(Worker.prototype, 'postMessage').mockImplementation(function (this: Worker, ...args) {
      this.once('exit', () => {
        exited = true
      })
      controller.abort('TIMED_OUT')
      return realPostMessage.apply(this, args)
    })

    const result = await callCapability(
      searchCapabilities['fs.grep']!,
      'fs.grep',
      { pattern: 'needle', path: dir },
      makeContext(dir, { signal: controller.signal }),
    )

    expect(posted).toHaveBeenCalledOnce()
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({
      code: 'TIMEOUT',
      search: { outcome: 'TIMED_OUT', incomplete: true },
    })
    expect(textOf(result)).toContain('[Search timed out; results are incomplete.]')
    expect(textOf(result)).not.toContain('No matches found')
    expect(exited).toBe(true)
    expect(Worker.prototype.terminate).not.toHaveBeenCalled()
  })
})
