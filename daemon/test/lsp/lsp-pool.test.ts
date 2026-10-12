import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { LspClientPool, poolKey } from '../../src/coding/lsp-pool.js'
import { LspDiscovery } from '../../src/coding/lsp-discovery.js'
import type { ManagedProcess, ProcessSpec } from '../../src/contracts.js'
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
    for (const pool of pools) {
      await pool.close()
    }
    await supervisor.close()
    try {
      fs.rmSync(tempDir, { recursive: true, force: true })
    } catch {}
  })

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

  it('cancels a lease awaiting initialize when the caller aborts, then cleans up the late boot', async () => {
    const transcript = path.join(tempDir, 'abort-init-transcript.jsonl')
    const server = fakeServerConfig('java', 'slow-init', transcript)
    const pool = new LspClientPool(LspDiscovery.of([server]), supervisor, {
      initializeTimeout: 60_000,
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

    await expect(pool.close()).rejects.toThrow(/failed to release all resources/)
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
    const failingSupervisor = {
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
    const pool = new LspClientPool(LspDiscovery.of([server]), failingSupervisor, {
      idleTimeout: 50,
      shutdownGrace: 100,
    })

    const javaFile = path.join(tempDir, 'App.java')
    fs.writeFileSync(javaFile, 'class App {}\n')

    const entry = await pool.lease(javaFile)
    pool.release(entry)

    // Wait for idle eviction
    await new Promise((resolve) => setTimeout(resolve, 200))
    expect(pool.activeCount()).toBe(0)

    // close() surfaces the failure that occurred during idle retirement
    await expect(pool.close()).rejects.toThrow(/failed to release all resources/)
  })
})
