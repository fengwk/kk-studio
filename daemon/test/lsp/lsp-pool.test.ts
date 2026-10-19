import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LspClientPool, poolKey } from '../../src/coding/lsp-pool.js'
import { LspDiscovery } from '../../src/coding/lsp-discovery.js'
import { LspTimeoutError } from '../../src/coding/lsp-error.js'
import type { ManagedProcess, ProcessSpec, ProcessSupervisor } from '../../src/contracts.js'
import {
  awaitEvent,
  awaitProcessGone,
  fakeServerConfig,
  readEvents,
} from './fake-lsp-helper.js'
import { TestProcessSupervisor } from './test-process-supervisor.js'

describe('LspClientPool Concurrency & Lifecycle', () => {
  let tempDir: string
  let supervisor: TestProcessSupervisor
  const pools: LspClientPool[] = []

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lsp-pool-test-'))
    supervisor = new TestProcessSupervisor()
  })

  afterEach(async () => {
    const cleanupErrors: unknown[] = []
    // splice：池绝不残留到下一个测试。二次关闭的失败既不当成功，也不掩盖原测试结论。
    const closing = pools.splice(0)
    const poolResults = await Promise.allSettled(closing.map((pool) => pool.close()))
    for (const result of poolResults) {
      if (result.status === 'rejected') {
        cleanupErrors.push(result.reason)
      }
    }
    // supervisor 与临时目录独立收尾：池关闭失败绝不能跳过它们。
    const supervisorResults = await Promise.allSettled([supervisor.close()])
    for (const result of supervisorResults) {
      if (result.status === 'rejected') {
        cleanupErrors.push(result.reason)
      }
    }
    try {
      fs.rmSync(tempDir, { recursive: true, force: true })
    } catch (error) {
      cleanupErrors.push(error)
    }
    if (cleanupErrors.length > 0) {
      throw new AggregateError(cleanupErrors, 'lsp-pool fixture cleanup failed')
    }
  })

  /**
   * 池的 close 已被本测试有意毒化：拒绝由测试自己断言，afterEach 不再二次关闭它。
   * 这不是吞掉失败——其余 cleanup 仍独立执行，且池的子进程仍由 supervisor 收尾。
   */
  async function expectPoolCloseFailure(pool: LspClientPool): Promise<void> {
    const index = pools.indexOf(pool)
    if (index >= 0) {
      pools.splice(index, 1)
    }
    await expect(pool.close()).rejects.toThrow(/failed to release all resources/)
  }

  /**
   * 终止永远失败的 supervisor：真实启动仍由 TestProcessSupervisor 管理（afterEach 能收尾），
   * 只把 terminate 换成抛错，用来验证「退休/清理没能证明收敛」时池的失败关闭语义。
   */
  function failingTerminateSupervisor(): ProcessSupervisor {
    return {
      start: async (spec: ProcessSpec): Promise<ManagedProcess> => {
        const managed = await supervisor.start(spec)
        return {
          ...managed,
          terminate: async (): Promise<void> => {
            throw new Error('retirement terminate boom')
          },
        }
      },
      close: async (): Promise<void> => {},
    }
  }

  /**
   * 在失败终止 supervisor 外再加一层真实启动屏障：等假服务器写下 'start' 后才把 managed 交回池。
   * 这样 50ms 的 initialize 预算从「进程已就绪」起算，macOS 冷启动不会与初始化超时抢时间；
   * 池仍然真实地经历 no-init 超时，不使用 sleep 猜测。
   */
  function coldStartBarrierSupervisor(transcriptPath: string): ProcessSupervisor {
    const failing = failingTerminateSupervisor()
    return {
      start: async (spec: ProcessSpec): Promise<ManagedProcess> => {
        const managed = await failing.start(spec)
        await awaitEvent(transcriptPath, (event) => event.event === 'start')
        return managed
      },
      close: (): Promise<void> => failing.close(),
    }
  }

  it('reuses client for same key and only starts one process', async () => {
    const transcript = path.join(tempDir, 'reuse-transcript.jsonl')
    const server = fakeServerConfig('java', 'normal', transcript)
    const discovery = LspDiscovery.of([server])
    const pool = new LspClientPool(discovery, supervisor)
    pools.push(pool)

    const file1 = path.join(tempDir, 'App.java')
    fs.writeFileSync(file1, 'class App {}\n')
    const file2 = path.join(tempDir, 'Other.java')
    fs.writeFileSync(file2, 'class Other {}\n')

    const entry1 = await pool.lease(file1)
    expect(entry1.client.isAlive()).toBe(true)
    pool.release(entry1)

    const entry2 = await pool.lease(file2)
    expect(entry2.client).toBe(entry1.client)
    pool.release(entry2)

    const starts = readEvents(transcript).filter((e) => e.event === 'start')
    expect(starts).toHaveLength(1)
  })

  it('coalesces concurrent lease calls into a single boot', async () => {
    const transcript = path.join(tempDir, 'coalesce-transcript.jsonl')
    const server = fakeServerConfig('java', 'slow-init', transcript)
    const discovery = LspDiscovery.of([server])
    const pool = new LspClientPool(discovery, supervisor)
    pools.push(pool)

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    // 4 concurrent lease calls
    const [e1, e2, e3, e4] = await Promise.all([
      pool.lease(file),
      pool.lease(file),
      pool.lease(file),
      pool.lease(file),
    ])

    expect(e1.client).toBe(e2.client)
    expect(e2.client).toBe(e3.client)
    expect(e3.client).toBe(e4.client)

    const starts = readEvents(transcript).filter((e) => e.event === 'start')
    expect(starts).toHaveLength(1)

    pool.release(e1)
    pool.release(e2)
    pool.release(e3)
    pool.release(e4)
  })

  it('evicts idle client after idleTimeout and terminates process', async () => {
    const transcript = path.join(tempDir, 'idle-transcript.jsonl')
    const server = fakeServerConfig('java', 'normal', transcript)
    const discovery = LspDiscovery.of([server])
    const pool = new LspClientPool(discovery, supervisor, {
      idleTimeout: 100, // 100ms
      shutdownGrace: 200,
    })
    pools.push(pool)

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const entry = await pool.lease(file)
    const startEvent = await awaitEvent(transcript, (e) => e.event === 'start')
    const pid = startEvent.pid!
    pool.release(entry)

    expect(pool.activeCount()).toBe(1)

    // Wait for idle timer to evict
    await new Promise((resolve) => setTimeout(resolve, 250))
    expect(pool.activeCount()).toBe(0)
    await awaitProcessGone(pid)
  })

  it('close during boot cancels startup and stops launched process (late-boot cleanup)', async () => {
    const transcript = path.join(tempDir, 'late-boot-transcript.jsonl')
    const server = fakeServerConfig('java', 'slow-init', transcript)
    const discovery = LspDiscovery.of([server])
    const pool = new LspClientPool(discovery, supervisor)
    pools.push(pool)

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const pendingLease = pool.lease(file)
    // 立即挂上拒绝观察：close 取消 boot 时（进程启动后）不能留下未处理的 rejection。
    const leaseRejected = expect(pendingLease).rejects.toThrow(/closed|cancelled/)

    // Wait until process starts
    const startEvent = await awaitEvent(transcript, (e) => e.event === 'start')
    const pid = startEvent.pid!

    await pool.close()

    await leaseRejected
    expect(pool.activeCount()).toBe(0)
    await awaitProcessGone(pid)
  })

  it('concurrent failed boots share single launch and can retry afterwards', async () => {
    const transcript = path.join(tempDir, 'dead-boot-transcript.jsonl')
    const server = fakeServerConfig('java', 'dead', transcript)
    const discovery = LspDiscovery.of([server])
    const pool = new LspClientPool(discovery, supervisor)
    pools.push(pool)

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const [r1, r2] = await Promise.allSettled([pool.lease(file), pool.lease(file)])
    expect(r1.status).toBe('rejected')
    expect(r2.status).toBe('rejected')

    const starts = readEvents(transcript).filter((e) => e.event === 'start')
    expect(starts).toHaveLength(1)
  })

  it('uses a structured fingerprint so array values containing separators never collide', () => {
    const merged = poolKey('/root', {
      id: 's',
      command: ['a,b'],
      extensions: [],
      rootMarkers: [],
      firstMatchMarkers: [],
    })
    const split = poolKey('/root', {
      id: 's',
      command: ['a', 'b'],
      extensions: [],
      rootMarkers: [],
      firstMatchMarkers: [],
    })
    expect(merged).not.toBe(split)

    const rootWithNewline = poolKey('/ro\n', {
      id: 's',
      command: ['a'],
      extensions: [],
      rootMarkers: [],
      firstMatchMarkers: [],
    })
    const rootThenId = poolKey('/ro', {
      id: '\ns',
      command: ['a'],
      extensions: [],
      rootMarkers: [],
      firstMatchMarkers: [],
    })
    expect(rootWithNewline).not.toBe(rootThenId)

    const same = poolKey('/root', {
      id: 's',
      command: ['a', 'b'],
      extensions: ['.java'],
      rootMarkers: ['pom.xml'],
      firstMatchMarkers: [],
    })
    expect(
      same,
    ).toBe(
      poolKey('/root', {
        id: 's',
        command: ['a', 'b'],
        extensions: ['.java'],
        rootMarkers: ['pom.xml'],
        firstMatchMarkers: [],
      }),
    )
  })

  it('defaults initializeTimeout to 180000ms', () => {
    const pool = new LspClientPool(LspDiscovery.of([]), supervisor)
    expect((pool as any).initializeTimeout).toBe(180_000)
  })

  it('cancels a lease awaiting initialize when the caller aborts, then cleans up the late boot', async () => {
    const transcript = path.join(tempDir, 'abort-init-transcript.jsonl')
    const server = fakeServerConfig('java', 'slow-init', transcript)
    const pool = new LspClientPool(LspDiscovery.of([server]), supervisor, {
      initializeTimeout: 180_000,
      shutdownGrace: 300,
    })
    pools.push(pool)

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const controller = new AbortController()
    const pending = pool.lease(file, controller.signal)
    const startEvent = await awaitEvent(transcript, (e) => e.event === 'start')
    const pid = startEvent.pid!

    const startedAt = Date.now()
    controller.abort()
    await expect(pending).rejects.toThrow(/Operation cancelled|aborted/i)
    expect(Date.now() - startedAt).toBeLessThan(2000)
    expect(pool.activeCount()).toBe(0)
    await awaitProcessGone(pid)
  })

  it('preserves other waiters when one waiter aborts or times out during boot without waiting for boot', async () => {
    const transcript = path.join(tempDir, 'shared-waiters-transcript.jsonl')
    const server = fakeServerConfig('java', 'slow-init', transcript)
    const pool = new LspClientPool(LspDiscovery.of([server]), supervisor, {
      shutdownGrace: 300,
    })
    pools.push(pool)

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const ac1 = new AbortController()
    const ac2 = new AbortController()

    const pending1 = pool.lease(file, ac1.signal)
    const pending2 = pool.lease(file, ac2.signal)

    const startEvent = await awaitEvent(transcript, (e) => e.event === 'start')
    const pid = startEvent.pid!

    // waiter1 times out; waiter1 must exit promptly without waiting for slow-init (3000ms)
    const abortTime = Date.now()
    ac1.abort('TIMED_OUT')
    const err1 = await pending1.catch((e: unknown) => e)
    expect(Date.now() - abortTime).toBeLessThan(1000)
    expect(err1).toBeInstanceOf(LspTimeoutError)
    expect((err1 as Error).message).toBe('Operation timed out')

    // waiter2 was not affected, waits for boot to finish normally and succeeds
    const entry2 = await pending2
    expect(entry2.client.isAlive()).toBe(true)
    expect(pool.activeCount()).toBe(1)
    pool.release(entry2)

    await pool.close()
    await awaitProcessGone(pid)
  })

  it('aborts boot only after the last remaining waiter exits and awaits cleanup convergence', async () => {
    const transcript = path.join(tempDir, 'all-waiters-abort-transcript.jsonl')
    const server = fakeServerConfig('java', 'slow-init', transcript)
    const pool = new LspClientPool(LspDiscovery.of([server]), supervisor, {
      shutdownGrace: 300,
    })
    pools.push(pool)

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const ac1 = new AbortController()
    const ac2 = new AbortController()

    const pending1 = pool.lease(file, ac1.signal)
    const pending2 = pool.lease(file, ac2.signal)

    const startEvent = await awaitEvent(transcript, (e) => e.event === 'start')
    const pid = startEvent.pid!

    ac1.abort('TIMED_OUT')
    await expect(pending1).rejects.toThrow(/Operation timed out/)

    ac2.abort()
    await expect(pending2).rejects.toThrow(/Operation cancelled/)

    // Last waiter awaiting cleanup means process is already gone
    expect(pool.activeCount()).toBe(0)
    await awaitProcessGone(pid)
  })

  it('does not lease warm client when caller signal is already aborted', async () => {
    const transcript = path.join(tempDir, 'warm-abort-transcript.jsonl')
    const server = fakeServerConfig('java', 'normal', transcript)
    const pool = new LspClientPool(LspDiscovery.of([server]), supervisor)
    pools.push(pool)

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const first = await pool.lease(file)
    expect(first.inFlight).toBe(1)
    pool.release(first)
    expect(first.inFlight).toBe(0)

    const cancelledAc = new AbortController()
    cancelledAc.abort()
    await expect(pool.lease(file, cancelledAc.signal)).rejects.toThrow(/Operation cancelled/)
    expect(first.inFlight).toBe(0)

    const timeoutAc = new AbortController()
    timeoutAc.abort('TIMED_OUT')
    const timeoutErr = await pool.lease(file, timeoutAc.signal).catch((e: unknown) => e)
    expect(timeoutErr).toBeInstanceOf(LspTimeoutError)
    expect((timeoutErr as Error).message).toBe('Operation timed out')
    expect(first.inFlight).toBe(0)
  })

  it('aborts promptly when waiting for cancelled boot convergence if caller aborts', async () => {
    const transcript = path.join(tempDir, 'cancelled-boot-wait-transcript.jsonl')
    const server = fakeServerConfig('java', 'slow-init', transcript)
    const pool = new LspClientPool(LspDiscovery.of([server]), supervisor, {
      shutdownGrace: 2000,
    })
    pools.push(pool)

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const ac1 = new AbortController()
    const p1 = pool.lease(file, ac1.signal)
    await awaitEvent(transcript, (e) => e.event === 'start')

    ac1.abort()
    await expect(p1).rejects.toThrow(/Operation cancelled/)

    // Next waiter with already aborted signal must not hang waiting for old convergence
    const ac2 = new AbortController()
    ac2.abort('TIMED_OUT')
    const started = Date.now()
    const err = await pool.lease(file, ac2.signal).catch((e: unknown) => e)
    expect(Date.now() - started).toBeLessThan(500)
    expect(err).toBeInstanceOf(LspTimeoutError)
  })

  it('surfaces boot initialization failure to all concurrent waiters without treating it as cancellation', async () => {
    const transcript = path.join(tempDir, 'boot-fail-waiters-transcript.jsonl')
    const server = fakeServerConfig('java', 'no-init', transcript)
    const pool = new LspClientPool(LspDiscovery.of([server]), supervisor, {
      initializeTimeout: 100,
      shutdownGrace: 100,
    })
    pools.push(pool)

    const javaFile = path.join(tempDir, 'App.java')
    fs.writeFileSync(javaFile, 'class App {}\n')

    const p1 = pool.lease(javaFile)
    const p2 = pool.lease(javaFile)

    const [r1, r2] = await Promise.allSettled([p1, p2])
    expect(r1.status).toBe('rejected')
    expect(r2.status).toBe('rejected')
    const err1 = (r1 as PromiseRejectedResult).reason as Error
    const err2 = (r2 as PromiseRejectedResult).reason as Error
    expect(err1.message).toContain('LSP request timed out')
    expect(err2.message).toContain('LSP request timed out')
    expect(err1.name).not.toBe('AbortError')
    expect(err2.name).not.toBe('AbortError')
    expect(err1.name).not.toBe('TimeoutError')
    expect(err2.name).not.toBe('TimeoutError')
    expect(pool.activeCount()).toBe(0)
  })

  it('close() surfaces release failures instead of silently claiming cleanup', async () => {
    const transcript = path.join(tempDir, 'close-fail-transcript.jsonl')
    const server = fakeServerConfig('java', 'normal', transcript)
    const failingSupervisor = {
      start: async (spec: ProcessSpec): Promise<ManagedProcess> => {
        const managed = await supervisor.start(spec)
        return {
          ...managed,
          terminate: async (): Promise<void> => {
            throw new Error('terminate boom')
          },
        }
      },
      close: async (): Promise<void> => {},
    }
    const pool = new LspClientPool(LspDiscovery.of([server]), failingSupervisor)

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const entry = await pool.lease(file)
    pool.release(entry)

    await expectPoolCloseFailure(pool)
  })

  it('client(file) returns undefined when unsupported, unstarted, or client has died', async () => {
    const transcript = path.join(tempDir, 'client-lookup-transcript.jsonl')
    const server = fakeServerConfig('java', 'normal', transcript)
    const discovery = LspDiscovery.of([server])
    const pool = new LspClientPool(discovery, supervisor)
    pools.push(pool)

    const javaFile = path.join(tempDir, 'App.java')
    fs.writeFileSync(javaFile, 'class App {}\n')
    const pyFile = path.join(tempDir, 'App.py')
    fs.writeFileSync(pyFile, 'print(1)\n')

    // Unsupported extension
    expect(pool.client(pyFile)).toBeUndefined()

    // Supported but unleased
    expect(pool.client(javaFile)).toBeUndefined()

    // Leased and alive
    const entry = await pool.lease(javaFile)
    expect(pool.client(javaFile)).toBe(entry.client)
    pool.release(entry)

    // Stop client externally so isAlive is false
    await entry.client.stop()
    expect(pool.client(javaFile)).toBeUndefined()
  })

  it('restarts a new process when leasing after existing process has died externally', async () => {
    const transcript = path.join(tempDir, 'restart-dead-transcript.jsonl')
    const server = fakeServerConfig('java', 'normal', transcript)
    const discovery = LspDiscovery.of([server])
    const pool = new LspClientPool(discovery, supervisor)
    pools.push(pool)

    const javaFile = path.join(tempDir, 'App.java')
    fs.writeFileSync(javaFile, 'class App {}\n')

    const firstEntry = await pool.lease(javaFile)
    pool.release(firstEntry)
    expect(readEvents(transcript).filter((e) => e.event === 'start')).toHaveLength(1)

    // Stop the first process externally
    await firstEntry.client.stop()
    expect(firstEntry.client.isAlive()).toBe(false)

    // Lease again: pool should detect that the existing client died, delete it and start a new process
    const secondEntry = await pool.lease(javaFile)
    expect(secondEntry.client.isAlive()).toBe(true)
    expect(secondEntry.client).not.toBe(firstEntry.client)
    pool.release(secondEntry)

    expect(readEvents(transcript).filter((e) => e.event === 'start')).toHaveLength(2)
  })

  it('rejects lease immediately if pool is already closed', async () => {
    const transcript = path.join(tempDir, 'closed-pool-transcript.jsonl')
    const server = fakeServerConfig('java', 'normal', transcript)
    const pool = new LspClientPool(LspDiscovery.of([server]), supervisor)
    await pool.close()

    const javaFile = path.join(tempDir, 'App.java')
    fs.writeFileSync(javaFile, 'class App {}\n')

    await expect(pool.lease(javaFile)).rejects.toThrow('LSP service is closed')
  })

  it('handles client stop failure during idle retirement and records cleanup failure', async () => {
    const transcript = path.join(tempDir, 'idle-fail-transcript.jsonl')
    const server = fakeServerConfig('java', 'normal', transcript)
    const pool = new LspClientPool(LspDiscovery.of([server]), failingTerminateSupervisor(), {
      idleTimeout: 50,
      shutdownGrace: 100,
    })

    const javaFile = path.join(tempDir, 'App.java')
    fs.writeFileSync(javaFile, 'class App {}\n')

    const entry = await pool.lease(javaFile)
    const started = Promise.withResolvers<void>()
    const releaseStop = Promise.withResolvers<void>()
    const stopped = Promise.withResolvers<void>()
    const originalStop = entry.client.stop.bind(entry.client)
    const stop = vi.spyOn(entry.client, 'stop').mockImplementation(async (grace) => {
      started.resolve()
      try {
        await releaseStop.promise
        await originalStop(grace)
      } finally {
        stopped.resolve()
      }
    })
    let closing: Promise<void> | undefined
    let settled = false
    try {
      pool.release(entry)
      // 仍由真实 idle timer 退休；只卡住已开始的 stop，不用固定 sleep 猜完成时间。
      await started.promise
      expect(pool.activeCount()).toBe(0)
      closing = pool.close()
      const observedClose = closing.then(
        () => {
          settled = true
        },
        () => {
          settled = true
        },
      )
      // 跨一个事件循环轮次排空微任务：stop gate 尚未放行，close 必须仍然等待。
      await new Promise<void>((resolve) => setImmediate(resolve))
      expect(settled).toBe(false)
      expect(stop).toHaveBeenCalledTimes(1)
      releaseStop.resolve()
      await expect(closing).rejects.toThrow(/failed to release all resources/)
      await observedClose
      expect(stop).toHaveBeenCalledTimes(1)
    } finally {
      releaseStop.resolve()
      // 成功/失败都等物理退休完成；子进程仍由 afterEach 的真实 supervisor 收尾。
      await stopped.promise
      await Promise.allSettled(closing ? [closing] : [])
      stop.mockRestore()
    }
  })

  it('fails closed for the retired key when the retirement could not be proven converged', async () => {
    const transcript = path.join(tempDir, 'idle-fail-lease-transcript.jsonl')
    const server = fakeServerConfig('java', 'normal', transcript)
    const pool = new LspClientPool(LspDiscovery.of([server]), failingTerminateSupervisor(), {
      idleTimeout: 50,
      shutdownGrace: 100,
    })
    pools.push(pool)

    const javaFile = path.join(tempDir, 'App.java')
    fs.writeFileSync(javaFile, 'class App {}\n')

    const entry = await pool.lease(javaFile)
    const started = Promise.withResolvers<void>()
    const originalStop = entry.client.stop.bind(entry.client)
    let stopFailure: unknown
    const stop = vi.spyOn(entry.client, 'stop').mockImplementation(async (grace) => {
      started.resolve()
      try {
        await originalStop(grace)
      } catch (error) {
        stopFailure = error
        throw error
      }
    })
    pool.release(entry)

    // 空闲回收的终止失败：退休登记必须保留，等待者与后续租约共享同一条失败。
    await started.promise
    expect(pool.activeCount()).toBe(0)
    const first = await pool.lease(javaFile).then(
      () => null,
      (error: unknown) => error,
    )
    expect(first).toBeInstanceOf(Error)
    expect(first).toBe(stopFailure)
    const second = await pool.lease(javaFile).then(
      () => null,
      (error: unknown) => error,
    )
    // 同一条失败对象：既没有替换登记，也绝没有为同一个 key 启动第二个服务器。
    expect(second).toBe(first)
    expect(readEvents(transcript).filter((e) => e.event === 'start')).toHaveLength(1)
    expect(stop).toHaveBeenCalledTimes(1)
    await expectPoolCloseFailure(pool)
    stop.mockRestore()
  })

  it('keeps a key closed when a failed boot could not be proven cleaned up', async () => {
    const transcript = path.join(tempDir, 'boot-cleanup-fail-transcript.jsonl')
    const server = fakeServerConfig('java', 'no-init', transcript)
    // 只有这个用例把 50ms 初始化预算压在冷启动上：先等真实的 'start' 屏障再交给池。
    const pool = new LspClientPool(
      LspDiscovery.of([server]),
      coldStartBarrierSupervisor(transcript),
      {
        initializeTimeout: 50,
        shutdownGrace: 50,
      },
    )
    pools.push(pool)

    const javaFile = path.join(tempDir, 'App.java')
    fs.writeFileSync(javaFile, 'class App {}\n')

    const first = await pool.lease(javaFile).then(
      () => null,
      (error: unknown) => error,
    )
    expect(first).toBeInstanceOf(AggregateError)
    expect((first as Error).message).toContain('startup failed and cleanup failed')
    // 清理没能证明收敛：旧实例可能仍然存活，同一个 key 的后续租约必须共享同一条失败。
    const second = await pool.lease(javaFile).then(
      () => null,
      (error: unknown) => error,
    )
    expect(second).toBe(first)
    expect(readEvents(transcript).filter((e) => e.event === 'start')).toHaveLength(1)
    await expectPoolCloseFailure(pool)
  })
})

// Fixture contract: a test supervisor must prove real stdio teardown, not just 'exit'. A child
// whose pipes are still open can keep a deleted distribution busy and delay coverage collection.
describe('TestProcessSupervisor resource lifecycle', () => {
  const spawnSpec = (): ProcessSpec => ({
    executable: process.execPath,
    args: ['-e', 'setInterval(() => {}, 1000)'],
    cwd: os.tmpdir(),
  })

  it('resolves terminate and close only after the real stdio close', async () => {
    const supervisor = new TestProcessSupervisor()
    try {
      const managed = await supervisor.start(spawnSpec())
      const terminatedChild = supervisor.children[0]!
      let terminatedClosed = false
      terminatedChild.on('close', () => {
        terminatedClosed = true
      })
      await managed.terminate()
      expect(terminatedChild.exitCode !== null || terminatedChild.signalCode !== null).toBe(true)
      expect(terminatedClosed).toBe(true)

      await supervisor.start(spawnSpec())
      const closedChild = supervisor.children[1]!
      let closed = false
      closedChild.on('close', () => {
        closed = true
      })
      await supervisor.close()
      expect(closedChild.exitCode !== null || closedChild.signalCode !== null).toBe(true)
      expect(closed).toBe(true)
    } finally {
      // An early assertion failure must still reap the child; close() is idempotent.
      await supervisor.close()
    }
  })

  it('settles a failed spawn so close never hangs', async () => {
    const supervisor = new TestProcessSupervisor()
    try {
      const missing = path.join(os.tmpdir(), `kk-missing-lsp-${process.pid}-${Date.now()}`)
      const managed = await supervisor.start({ ...spawnSpec(), executable: missing })

      await expect(managed.exit).resolves.toEqual({ exitCode: null, signal: null })
      await expect(supervisor.close()).resolves.toBeUndefined()
    } finally {
      await supervisor.close()
    }
  })
})
