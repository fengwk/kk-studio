import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mkdtemp, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import type { DaemonConfig } from '../src/contracts.js'
import { CAPABILITY_CATALOG_VERSION, decodeEnvelope, encodeInvoke, type Envelope } from '../src/protocol.js'
import { Runtime } from '../src/runtime.js'
import { searchCapabilities } from '../src/coding/search.js'
import { FakeWorker } from './coding/fake-search-worker.js'
import { FakeTransport, fakeProcessSupervisor, serverMessage, testConfig, tick, waitFor } from './helpers.js'

vi.mock('node:worker_threads', async () => {
  const { FakeWorker } = await import('./coding/fake-search-worker.js')
  return { Worker: FakeWorker }
})

const ENV = '11111111-2222-4333-8444-555555555555'

const welcomePayload = {
  environmentId: ENV,
  name: 'env',
  maxResourceBytes: 1024 * 1024,
  temporaryResourceTtlSeconds: 60,
  temporaryResourceCleanupIntervalSeconds: 30,
}

const environment = {
  operatingSystem: 'linux' as const,
  architecture: 'amd64' as const,
  timeZone: 'UTC',
  userName: 'dev',
  homeDirectory: '/home/dev',
  note: 'Linux environment.',
  tempDirectory: '/tmp',
}

function sent(transport: FakeTransport): Envelope[] {
  return transport.sent.map((message) => decodeEnvelope(message))
}

interface Setup {
  transport: FakeTransport
  runtime: Runtime
  config: DaemonConfig
}

function buildSearchRuntime(): Setup {
  const transport = new FakeTransport()
  const config = testConfig()
  const runtime = new Runtime({
    config,
    transport,
    capabilities: searchCapabilities,
    processes: fakeProcessSupervisor,
    environment,
    heartbeatIntervalMillis: 10_000,
    initialReconnectDelayMillis: 5,
    maxReconnectDelayMillis: 20,
  })
  return { transport, runtime, config }
}

async function handshake(setup: Setup): Promise<void> {
  setup.runtime.start()
  await tick()
  setup.transport.receive(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload }))
  await tick()
  expect(setup.runtime.getState()).toBe('READY')
}

describe('Runtime + Search 真实能力整合生命周期', () => {
  const tempDirs: string[] = []

  beforeEach(() => {
    FakeWorker.onCreated = null
    FakeWorker.instances.length = 0
  })

  afterEach(async () => {
    FakeWorker.onCreated = null
    for (const worker of FakeWorker.instances) {
      worker.release()
    }
    FakeWorker.instances.length = 0
    await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })))
  })

  async function createTestDir(): Promise<string> {
    const dir = await realpath(await mkdtemp(join(tmpdir(), 'kk-runtime-search-')))
    tempDirs.push(dir)
    await writeFile(join(dir, 'a.ts'), 'export const needle = 42\n', 'utf8')
    return dir
  }

  it.each(['fs.find', 'fs.grep'] as const)(
    '推进 fake timer 超时：%s 协作写入 stopBuffer(TIMED_OUT)，exit 门禁前维持 RUNNING，退出后仅一次 COMPLETED(error=true) 终态',
    async (capabilityId) => {
      const dir = await createTestDir()
      const workerCreated = Promise.withResolvers<FakeWorker>()
      FakeWorker.onCreated = (w) => workerCreated.resolve(w)

      const setup = buildSearchRuntime()
      const invocationId = `inv-search-timeout-${capabilityId.replace('.', '-')}`

      try {
        await handshake(setup)
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })

        const args =
          capabilityId === 'fs.find'
            ? { pattern: '*.ts', path: dir }
            : { pattern: 'needle', path: dir }

        const payload = JSON.parse(
          encodeInvoke({
            capabilityId,
            capabilityVersion: CAPABILITY_CATALOG_VERSION,
            arguments: args,
            timeoutMillis: 50,
          }),
        ) as Record<string, unknown>

        setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId, payload }))

        // 等待 worker 实例创建并收到 search job
        const worker = await workerCreated.promise
        const job = worker.posted[0] as { stopBuffer: SharedArrayBuffer }
        expect(job.stopBuffer).toBeDefined()
        expect(Atomics.load(new Int32Array(job.stopBuffer), 0)).toBe(0)

        // 推进 fake clock 到期，触发 Runtime 唯一的 invoke deadline timer
        await vi.advanceTimersByTimeAsync(50)

        // 验证 search capability 监听到 abort 后协作更新了 stopBuffer 为 1 (STOP_TIMED_OUT)
        expect(Atomics.load(new Int32Array(job.stopBuffer), 0)).toBe(1)

        // 模拟 worker 响应协作停止，回传 partial 结果带 stopReason='TIMED_OUT'
        worker.emit('message', {
          ok: true,
          kind: capabilityId === 'fs.find' ? 'find' : 'grep',
          matches: capabilityId === 'fs.find' ? [`${dir}/a.ts`] : undefined,
          lines: capabilityId === 'fs.grep' ? [`${dir}/a.ts:1:export const needle = 42`] : undefined,
          limitReached: false,
          skipped: [],
          stopReason: 'TIMED_OUT',
        })

        // 在 exit 门禁放行前：journal 维持 RUNNING 且绝不发送 terminal 报文
        expect(setup.runtime.getJournal().find(invocationId)?.state).toBe('RUNNING')
        expect(
          sent(setup.transport).some(
            (env) => env.invocationId === invocationId && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(env.messageType),
          ),
        ).toBe(false)

        // worker 进程自然退出
        worker.emit('exit', 0)

        vi.useRealTimers()

        // 验证终态到达：按契约，CapabilityResult 无论 error 与否统一由 COMPLETED 报文承载
        await waitFor(() =>
          sent(setup.transport).some((env) => env.invocationId === invocationId && env.messageType === 'COMPLETED'),
        )

        const completedEnvelopes = sent(setup.transport).filter(
          (env) => env.invocationId === invocationId && env.messageType === 'COMPLETED',
        )
        expect(completedEnvelopes).toHaveLength(1)
        expect(
          sent(setup.transport).some(
            (env) => env.invocationId === invocationId && ['FAILED', 'CANCELLED'].includes(env.messageType),
          ),
        ).toBe(false)

        const completed = completedEnvelopes[0]!
        const body = completed.payload as {
          result: {
            error: boolean
            details: { search?: { outcome?: string; incomplete?: boolean } }
            contents: Array<{ type: string; text?: string }>
          }
        }
        const resultPayload = body.result
        expect(resultPayload.error).toBe(true)
        expect(resultPayload.details.search?.outcome).toBe('TIMED_OUT')
        expect(resultPayload.details.search?.incomplete).toBe(true)

        const text = resultPayload.contents.map((c) => c.text ?? '').join('\n')
        expect(text).toContain(capabilityId === 'fs.find' ? `${dir}/a.ts` : 'export const needle = 42')
        expect(text).toContain('[Search timed out; results are incomplete.]')

        expect(setup.runtime.getJournal().find(invocationId)?.state).toBe('COMPLETED')
      } finally {
        vi.useRealTimers()
        setup.runtime.close()
      }
    },
  )

  it.each(['fs.find', 'fs.grep'] as const)(
    '收到 CANCEL 报文：%s 协作写入 stopBuffer(CANCELLED)，exit 门禁前维持 RUNNING，退出后仅一次 COMPLETED(error=true) 终态',
    async (capabilityId) => {
      const dir = await createTestDir()
      const workerCreated = Promise.withResolvers<FakeWorker>()
      FakeWorker.onCreated = (w) => workerCreated.resolve(w)

      const setup = buildSearchRuntime()
      const invocationId = `inv-search-cancel-${capabilityId.replace('.', '-')}`

      try {
        await handshake(setup)

        const args =
          capabilityId === 'fs.find'
            ? { pattern: '*.ts', path: dir }
            : { pattern: 'needle', path: dir }

        const payload = JSON.parse(
          encodeInvoke({
            capabilityId,
            capabilityVersion: CAPABILITY_CATALOG_VERSION,
            arguments: args,
            timeoutMillis: 10_000,
          }),
        ) as Record<string, unknown>

        setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId, payload }))

        const worker = await workerCreated.promise
        const job = worker.posted[0] as { stopBuffer: SharedArrayBuffer }
        expect(job.stopBuffer).toBeDefined()
        expect(Atomics.load(new Int32Array(job.stopBuffer), 0)).toBe(0)

        // 发送 CANCEL 报文
        setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId }))

        // 验证 stopBuffer 被协作更新为 2 (STOP_CANCELLED)
        await waitFor(() => Atomics.load(new Int32Array(job.stopBuffer), 0) === 2)

        // 模拟 worker 协作停止并回传带 CANCELLED 的 partial
        worker.emit('message', {
          ok: true,
          kind: capabilityId === 'fs.find' ? 'find' : 'grep',
          matches: capabilityId === 'fs.find' ? [`${dir}/a.ts`] : undefined,
          lines: capabilityId === 'fs.grep' ? [`${dir}/a.ts:1:export const needle = 42`] : undefined,
          limitReached: false,
          skipped: [],
          stopReason: 'CANCELLED',
        })

        // 在 exit 门禁前：journal 维持 RUNNING 且无 terminal 报文
        expect(setup.runtime.getJournal().find(invocationId)?.state).toBe('RUNNING')
        expect(
          sent(setup.transport).some(
            (env) => env.invocationId === invocationId && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(env.messageType),
          ),
        ).toBe(false)

        worker.emit('exit', 0)

        await waitFor(() =>
          sent(setup.transport).some((env) => env.invocationId === invocationId && env.messageType === 'COMPLETED'),
        )

        const completedEnvelopes = sent(setup.transport).filter(
          (env) => env.invocationId === invocationId && env.messageType === 'COMPLETED',
        )
        expect(completedEnvelopes).toHaveLength(1)
        expect(
          sent(setup.transport).some(
            (env) => env.invocationId === invocationId && ['FAILED', 'CANCELLED'].includes(env.messageType),
          ),
        ).toBe(false)

        const completed = completedEnvelopes[0]!
        const body = completed.payload as {
          result: {
            error: boolean
            details: { search?: { outcome?: string; incomplete?: boolean } }
            contents: Array<{ type: string; text?: string }>
          }
        }
        const resultPayload = body.result
        expect(resultPayload.error).toBe(true)
        expect(resultPayload.details.search?.outcome).toBe('CANCELLED')
        expect(resultPayload.details.search?.incomplete).toBe(true)

        const text = resultPayload.contents.map((c) => c.text ?? '').join('\n')
        expect(text).toContain(capabilityId === 'fs.find' ? `${dir}/a.ts` : 'export const needle = 42')
        expect(text).toContain('[Search cancelled; results are incomplete.]')

        expect(setup.runtime.getJournal().find(invocationId)?.state).toBe('COMPLETED')
      } finally {
        setup.runtime.close()
      }
    },
  )
})
