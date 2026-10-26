import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LspClientPool, poolKey } from '../../src/coding/lsp-pool.js'
import { LspDiscovery } from '../../src/coding/lsp-discovery.js'
import { LspBootstrapError, LspCleanupError, LspError, LspTimeoutError } from '../../src/coding/lsp-error.js'
import {
  ProcessScopeNotConvergedError,
  type ManagedProcess,
  type ProcessSpec,
  type ProcessSupervisor,
} from '../../src/contracts.js'
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
  async function expectPoolCloseFailure(pool: LspClientPool): Promise<LspCleanupError> {
    const index = pools.indexOf(pool)
    if (index >= 0) {
      pools.splice(index, 1)
    }
    const error = await pool.close().then(
      () => null,
      (failure: unknown) => failure,
    )
    expect(error).toBeInstanceOf(LspCleanupError)
    const cleanup = error as LspCleanupError
    expect(cleanup.failures.length).toBeGreaterThan(0)
    expect(cleanup.cause).toBeInstanceOf(AggregateError)
    expect((cleanup.cause as AggregateError).errors).toEqual([...cleanup.failures])
    return cleanup
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

  it('close waits for a launch that has not returned and confirms the waiter after cleanup', async () => {
    const transcript = path.join(tempDir, 'close-before-launch-return.jsonl')
    const server = fakeServerConfig('java', 'normal', transcript)
    const launchStarted = Promise.withResolvers<number>()
    const releaseLaunch = Promise.withResolvers<void>()
    const gated: ProcessSupervisor = {
      start: async (spec) => {
        const managed = await supervisor.start(spec)
        launchStarted.resolve(managed.pid)
        await releaseLaunch.promise
        return managed
      },
      close: () => supervisor.close(),
    }
    const pool = new LspClientPool(LspDiscovery.of([server]), gated)
    pools.push(pool)
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    let exits = 0
    const leaseOutcome = pool.lease(file, undefined, () => {
      exits += 1
    }).then(
      () => null,
      (error: unknown) => error,
    )
    const pid = await launchStarted.promise
    const firstClose = pool.close()
    expect(pool.close()).toBe(firstClose)
    const closeOutcome = firstClose.then(
      () => null,
      (error: unknown) => error,
    )
    let closeSettled = false
    void closeOutcome.then(() => {
      closeSettled = true
    })

    try {
      await new Promise<void>((resolve) => setImmediate(resolve))
      // launch 尚未返回：此时 close 既不能完成，也不能确认等待者。
      expect(closeSettled).toBe(false)
      expect(exits).toBe(0)
    } finally {
      releaseLaunch.resolve()
    }

    expect(await closeOutcome).toBeNull()
    expect(await leaseOutcome).toBeInstanceOf(Error)
    await vi.waitFor(() => expect(exits).toBe(1))
    await awaitProcessGone(pid)
  })

  it('close waits for an in-flight boot cleanup failure and never confirms the waiter', async () => {
    const transcript = path.join(tempDir, 'close-before-launch-cleanup-fail.jsonl')
    const server = fakeServerConfig('java', 'normal', transcript)
    const failing = failingTerminateSupervisor()
    const launchStarted = Promise.withResolvers<number>()
    const releaseLaunch = Promise.withResolvers<void>()
    const gated: ProcessSupervisor = {
      start: async (spec) => {
        const managed = await failing.start(spec)
        launchStarted.resolve(managed.pid)
        await releaseLaunch.promise
        return managed
      },
      close: () => failing.close(),
    }
    const pool = new LspClientPool(LspDiscovery.of([server]), gated)
    pools.push(pool)
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    let exits = 0
    const leaseOutcome = pool.lease(file, undefined, () => {
      exits += 1
    }).then(
      () => null,
      (error: unknown) => error,
    )
    await launchStarted.promise
    const firstClose = pool.close()
    let closeSettled = false
    void firstClose.then(
      () => {
        closeSettled = true
      },
      () => {
        closeSettled = true
      },
    )

    try {
      await new Promise<void>((resolve) => setImmediate(resolve))
      expect(closeSettled).toBe(false)
      expect(exits).toBe(0)
    } finally {
      releaseLaunch.resolve()
    }

    const cleanup = await expectPoolCloseFailure(pool)
    expect(cleanup.failures.length).toBeGreaterThan(0)
    expect(await leaseOutcome).toBeInstanceOf(Error)
    // 清理未证明收敛：close 保留 typed 失败，且绝不把等待者确认成退出。
    expect(exits).toBe(0)
  })

  it('confirms a lease superseded by close only after the shared client stop succeeds', async () => {
    const transcript = path.join(tempDir, 'lease-close-shared-stop.jsonl')
    const server = fakeServerConfig('java', 'normal', transcript)
    const launchStarted = Promise.withResolvers<number>()
    const launchGate = Promise.withResolvers<void>()
    const stopStarted = Promise.withResolvers<void>()
    const stopGate = Promise.withResolvers<void>()
    const gated: ProcessSupervisor = {
      start: async (spec) => {
        const managed = await supervisor.start(spec)
        // 先持有真实进程、暂停 bootstrap：测试必须在 initialize/publish 之前安装 resolve hook。
        launchStarted.resolve(managed.pid)
        await launchGate.promise
        return {
          ...managed,
          terminate: async (graceMillis?: number) => {
            stopStarted.resolve()
            await stopGate.promise
            await managed.terminate(graceMillis)
          },
        }
      },
      close: () => supervisor.close(),
    }
    const pool = new LspClientPool(LspDiscovery.of([server]), gated, {
      shutdownGrace: 300,
    })
    pools.push(pool)
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    try {
      let exits = 0
      const leaseOutcome = pool.lease(file, undefined, () => {
        exits += 1
      }).then(
        () => null,
        (error: unknown) => error,
      )
      const pid = await launchStarted.promise
      const boots = Array.from((pool as any).boots.values()) as Array<{
        resolve: (client: unknown) => void
      }>
      expect(boots).toHaveLength(1)
      const originalResolve = boots[0].resolve
      let closeOutcome: Promise<Error | null> | null = null
      boots[0].resolve = (client: unknown): void => {
        originalResolve(client)
        // 成功发布后、租约恢复前关闭：租约必须等同一个 cached stop 成功后才能确认。
        closeOutcome = pool.close().then(
          () => null,
          (error: unknown) => error as Error,
        )
      }

      launchGate.resolve()
      await stopStarted.promise
      expect(closeOutcome).not.toBeNull()
      let closeSettled = false
      void closeOutcome!.then(() => {
        closeSettled = true
      })
      await new Promise<void>((resolve) => setImmediate(resolve))
      expect(closeSettled).toBe(false)
      expect(exits).toBe(0)

      stopGate.resolve()
      expect(await closeOutcome!).toBeNull()
      expect(await leaseOutcome).toBeInstanceOf(Error)
      await vi.waitFor(() => expect(exits).toBe(1))
      await awaitProcessGone(pid)
    } finally {
      // 任何断言失败都要放行 bootstrap 与物理停止，之后由池和 supervisor 完成真实收尾。
      launchGate.resolve()
      stopGate.resolve()
    }
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

  it('reports a converged start rejection and allows a later retry', async () => {
    const server = fakeServerConfig('java', 'normal', path.join(tempDir, 'converged-start.jsonl'))
    let starts = 0
    const failing: ProcessSupervisor = {
      start: async () => {
        starts += 1
        throw new Error('spawn failed')
      },
      close: async () => {},
    }
    const pool = new LspClientPool(LspDiscovery.of([server]), failing)
    pools.push(pool)
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    let exits = 0
    const first = await pool.lease(file, undefined, () => {
      exits += 1
    }).catch((error: unknown) => error)
    expect(first).toBeInstanceOf(LspBootstrapError)
    expect((first as LspBootstrapError).converged).toBe(true)
    expect(exits).toBe(1)

    // 普通拒绝已证明没有遗留进程，因此同一个 key 可以重新启动。
    const second = await pool.lease(file, undefined, () => {
      exits += 1
    }).catch((error: unknown) => error)
    expect(second).toBeInstanceOf(LspBootstrapError)
    expect((second as LspBootstrapError).converged).toBe(true)
    expect(exits).toBe(2)
    expect(starts).toBe(2)
    await pool.close()
  })

  it('keeps the key closed and never confirms a start rejection that was not converged', async () => {
    const server = fakeServerConfig('java', 'normal', path.join(tempDir, 'unconverged-start.jsonl'))
    const startFailure = new ProcessScopeNotConvergedError('spawn cleanup could not be proven')
    let starts = 0
    const failing: ProcessSupervisor = {
      start: async () => {
        starts += 1
        throw startFailure
      },
      close: async () => {},
    }
    const pool = new LspClientPool(LspDiscovery.of([server]), failing)
    pools.push(pool)
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    let exits = 0
    const first = await pool.lease(file, undefined, () => {
      exits += 1
    }).catch((error: unknown) => error)
    expect(first).toBeInstanceOf(LspBootstrapError)
    expect((first as LspBootstrapError).converged).toBe(false)
    expect((first as LspBootstrapError).cause).toBe(startFailure)
    const second = await pool.lease(file, undefined, () => {
      exits += 1
    }).catch((error: unknown) => error)
    expect(second).toBe(first)
    expect(starts).toBe(1)
    expect(exits).toBe(0)
    const cleanup = await expectPoolCloseFailure(pool)
    // 关闭失败保留的仍是未收敛的 typed bootstrap 失败本身。
    expect(cleanup.failures[0]).toBe(first)
    expect((cleanup.failures[0] as LspBootstrapError).converged).toBe(false)
  })

  it('confirms aborted waiters only after the shared boot cleanup succeeds', async () => {
    const transcript = path.join(tempDir, 'shared-boot-cleanup.jsonl')
    const server = fakeServerConfig('java', 'slow-init', transcript)
    const cleanupGate = Promise.withResolvers<void>()
    const terminateStarted = Promise.withResolvers<void>()
    const gated: ProcessSupervisor = {
      start: async (spec) => {
        const managed = await supervisor.start(spec)
        return {
          ...managed,
          terminate: async (graceMillis?: number) => {
            terminateStarted.resolve()
            await cleanupGate.promise
            await managed.terminate(graceMillis)
          },
        }
      },
      close: () => supervisor.close(),
    }
    const pool = new LspClientPool(LspDiscovery.of([server]), gated, {
      shutdownGrace: 300,
    })
    pools.push(pool)
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')
    const ac1 = new AbortController()
    const ac2 = new AbortController()
    let exits1 = 0
    let exits2 = 0
    try {
      const p1 = pool.lease(file, ac1.signal, () => {
        exits1 += 1
      }).catch((error: unknown) => error)
      const p2 = pool.lease(file, ac2.signal, () => {
        exits2 += 1
      }).catch((error: unknown) => error)
      await awaitEvent(transcript, (event) => event.event === 'start')

      // 首个等待者取消时 boot 仍由另一个等待者共享：不能确认退出。
      ac1.abort('TIMED_OUT')
      expect(await p1).toBeInstanceOf(LspTimeoutError)
      expect(exits1).toBe(0)
      expect(exits2).toBe(0)

      // 最后一个等待者也取消后，boot 开始清理但门禁尚未放行：仍不能确认退出。
      ac2.abort()
      await terminateStarted.promise
      let settled = false
      void p2.then(() => {
        settled = true
      })
      await new Promise<void>((resolve) => setImmediate(resolve))
      expect(settled).toBe(false)
      expect(exits1).toBe(0)
      expect(exits2).toBe(0)

      cleanupGate.resolve()
      const error2 = await p2
      expect(error2).toBeInstanceOf(Error)
      expect((error2 as Error).name).toBe('AbortError')
      await vi.waitFor(() => {
        expect(exits1).toBe(1)
        expect(exits2).toBe(1)
      })
      // 每个目标只确认一次；重复唤醒不得再次回调。
      await new Promise<void>((resolve) => setImmediate(resolve))
      expect(exits1).toBe(1)
      expect(exits2).toBe(1)
      expect(readEvents(transcript).filter((event) => event.event === 'start')).toHaveLength(1)
    } finally {
      cleanupGate.resolve()
    }
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
    let exits1 = 0

    const pending1 = pool.lease(file, ac1.signal, () => {
      exits1 += 1
    })
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
    // 共享 boot 仍在运行，不能确认退出。
    expect(exits1).toBe(0)

    // waiter2 was not affected, waits for boot to finish normally and succeeds
    const entry2 = await pending2
    expect(entry2.client.isAlive()).toBe(true)
    expect(pool.activeCount()).toBe(1)
    // 成功发布证明引导已交付给其他 owner，且 waiter1 的目标从未发出；此时可以确认 waiter1。
    await vi.waitFor(() => expect(exits1).toBe(1))
    pool.release(entry2)

    await pool.close()
    // 成功发布是完成证据，但每个目标仍只确认一次。
    expect(exits1).toBe(1)
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

    let exits = 0
    await expect(
      pool.lease(javaFile, undefined, () => {
        exits += 1
      }),
    ).rejects.toThrow('LSP service is closed')
    expect(exits).toBe(1)
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
    const cleanup = await expectPoolCloseFailure(pool)
    // 退休失败作为 typed cause 保留，而不是只剩一个计数。
    expect(cleanup.failures).toContain(stopFailure)
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

    let exits = 0
    const first = await pool.lease(javaFile, undefined, () => {
      exits += 1
    }).then(
      () => null,
      (error: unknown) => error,
    )
    expect(first).toBeInstanceOf(LspBootstrapError)
    expect((first as Error).message).toContain('startup failed and cleanup failed')
    expect((first as LspBootstrapError).cause).toBeInstanceOf(AggregateError)
    // 清理没能证明收敛：旧实例可能仍然存活，同一个 key 的后续租约必须共享同一条失败。
    const second = await pool.lease(javaFile, undefined, () => {
      exits += 1
    }).then(
      () => null,
      (error: unknown) => error,
    )
    expect(second).toBe(first)
    expect(exits).toBe(0)
    expect(readEvents(transcript).filter((e) => e.event === 'start')).toHaveLength(1)
    const cleanup = await expectPoolCloseFailure(pool)
    expect(cleanup.failures[0]).toBeInstanceOf(LspError)
    expect((cleanup.failures[0] as Error).message).toContain('termination failed')
    expect(((cleanup.failures[0] as Error).cause as Error).message).toContain('retirement terminate boom')
  })

  it('surfaces last-waiter cleanup failure and never reopens the cancelled key', async () => {
    const transcript = path.join(tempDir, 'cancelled-boot-cleanup-fail.jsonl')
    const server = fakeServerConfig('java', 'no-init', transcript)
    const pool = new LspClientPool(LspDiscovery.of([server]), failingTerminateSupervisor(), {
      shutdownGrace: 0,
    })
    pools.push(pool)
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')
    const controller = new AbortController()
    const first = pool.lease(file, controller.signal).catch((error: unknown) => error)
    await awaitEvent(transcript, (event) => event.event === 'recv' && event.message?.method === 'initialize')
    controller.abort('TIMED_OUT')

    // 取消不能掩盖物理清理失败，也不能在已拒绝的 boot 上忙循环或启动第二个实例。
    const failure = await first
    expect(failure).toBeInstanceOf(LspBootstrapError)
    expect((failure as Error).message).toContain('cleanup failed')
    await expect(pool.lease(file)).rejects.toBe(failure)
    expect(readEvents(transcript).filter((event) => event.event === 'start')).toHaveLength(1)
    await expectPoolCloseFailure(pool)
  })

  it('waits for cancelled boot to finish converging before fresh lease can proceed with non-overlapping instance', async () => {
    const transcript = path.join(tempDir, 'cancelled-boot-wait-transcript.jsonl')
    const server = fakeServerConfig('java', 'slow-init', transcript)

    const cleanupGate = Promise.withResolvers<void>()
    const terminateStartedGate = Promise.withResolvers<void>()
    let terminateStarted = false
    let launches = 0
    let oldPid: number | null = null

    const gatedSupervisor: ProcessSupervisor = {
      start: async (spec: ProcessSpec): Promise<ManagedProcess> => {
        launches++
        const managed = await supervisor.start(spec)
        if (launches === 1) {
          oldPid = managed.pid
          const originalTerminate = managed.terminate.bind(managed)
          return {
            ...managed,
            terminate: async (): Promise<void> => {
              terminateStarted = true
              terminateStartedGate.resolve()
              await cleanupGate.promise
              return originalTerminate()
            },
          }
        }
        return managed
      },
      close: async (): Promise<void> => supervisor.close(),
    }

    const pool = new LspClientPool(LspDiscovery.of([server]), gatedSupervisor, {
      shutdownGrace: 300,
    })
    pools.push(pool)

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const ac1 = new AbortController()
    const ac2 = new AbortController()

    try {
      // 首个 waiter 取消后，旧进程的清理门禁阻止 boot 提前收敛。
      const p1 = pool.lease(file, ac1.signal).catch((err: unknown) => err)
      await awaitEvent(transcript, (event) => event.event === 'recv' && event.message?.method === 'initialize')
      ac1.abort()
      await terminateStartedGate.promise
      expect(terminateStarted).toBe(true)

      // 新 waiter 等待旧 boot 的清理，但它自身取消不必等共享清理门禁。
      let p2Settled = false
      const p2 = pool.lease(file, ac2.signal).catch((err: unknown) => {
        p2Settled = true
        return err
      })
      await new Promise<void>((resolve) => setImmediate(resolve))
      expect(p2Settled).toBe(false)
      ac2.abort()
      const err2 = await p2
      expect(err2).toBeInstanceOf(Error)
      expect((err2 as Error).name).toBe('AbortError')
      expect(p2Settled).toBe(true)

      let p1Settled = false
      void p1.then(() => {
        p1Settled = true
      })
      await new Promise<void>((resolve) => setImmediate(resolve))
      expect(p1Settled).toBe(false)

      // 5. 释放 cleanupGate，让 p1 的底层 terminate 和 boot 清理彻底完成
      cleanupGate.resolve()
      const err1 = await p1
      expect(err1).toBeInstanceOf(Error)
      expect((err1 as Error).name).toBe('AbortError')

      // 旧进程必须退出
      if (oldPid !== null) {
        await awaitProcessGone(oldPid)
      }

      // 6. 后续重建新实例不能和旧实例重叠：新实例启动并完成 boot，进程 PID 不与旧实例重叠
      const entry = await pool.lease(file)
      expect(entry.client.isAlive()).toBe(true)

      const startEvents = readEvents(transcript).filter((e) => e.event === 'start')
      expect(startEvents.length).toBeGreaterThanOrEqual(2)
      const newPid = startEvents.at(-1)?.pid
      expect(newPid).toBeDefined()
      expect(newPid).not.toBe(oldPid)

      pool.release(entry)
    } finally {
      ac1.abort()
      ac2.abort()
      cleanupGate.resolve()
    }
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

  it.each([false, true])('isolates fixture coverage with explicit override=%s', async (explicit) => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lsp-coverage-env-'))
    const supervisor = new TestProcessSupervisor()
    const keys = ['NODE_V8_COVERAGE', 'KK_LSP_INHERITED', 'KK_LSP_OVERRIDE'] as const
    const previous = keys.map((key) => process.env[key])
    const inheritedCoverage = path.join(tempDir, 'inherited')
    const explicitCoverage = path.join(tempDir, 'explicit')
    const output = path.join(tempDir, 'env.json')
    try {
      process.env.NODE_V8_COVERAGE = inheritedCoverage
      process.env.KK_LSP_INHERITED = 'inherited'
      process.env.KK_LSP_OVERRIDE = 'parent'
      const specEnv = {
        KK_LSP_OVERRIDE: 'child',
        ...(explicit ? { NODE_V8_COVERAGE: explicitCoverage } : {}),
      }
      const managed = await supervisor.start({
        executable: process.execPath,
        args: ['-e', `require('node:fs').writeFileSync(${JSON.stringify(output)}, JSON.stringify({
          coverage: process.env.NODE_V8_COVERAGE ?? null,
          inherited: process.env.KK_LSP_INHERITED,
          override: process.env.KK_LSP_OVERRIDE,
        }))`],
        cwd: tempDir,
        env: specEnv,
      })
      const child = supervisor.children[0]!
      const closed = new Promise<void>((resolve) => child.once('close', () => resolve()))
      await expect(managed.exit).resolves.toEqual({ exitCode: 0, signal: null })
      await closed
      await supervisor.close()
      expect(JSON.parse(fs.readFileSync(output, 'utf8'))).toEqual({
        coverage: null,
        inherited: 'inherited',
        override: 'child',
      })
      expect(keys.map((key) => process.env[key])).toEqual([inheritedCoverage, 'inherited', 'parent'])
      expect(specEnv).toEqual({
        KK_LSP_OVERRIDE: 'child',
        ...(explicit ? { NODE_V8_COVERAGE: explicitCoverage } : {}),
      })
      expect(fs.existsSync(inheritedCoverage)).toBe(false)
      expect(fs.existsSync(explicitCoverage)).toBe(false)
    } finally {
      try {
        await supervisor.close()
      } finally {
        keys.forEach((key, index) => {
          const value = previous[index]
          if (value === undefined) {
            delete process.env[key]
          } else {
            process.env[key] = value
          }
        })
        fs.rmSync(tempDir, { recursive: true, force: true })
      }
    }
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
