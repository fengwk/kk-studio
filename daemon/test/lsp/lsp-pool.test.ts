import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { LspClientPool } from '../../src/coding/lsp-pool.js'
import { LspDiscovery } from '../../src/coding/lsp-discovery.js'
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

    // Wait until process starts
    const startEvent = await awaitEvent(transcript, (e) => e.event === 'start')
    const pid = startEvent.pid!

    await pool.close()

    await expect(pendingLease).rejects.toThrow(/closed|cancelled/)
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
})
