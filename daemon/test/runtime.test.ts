import { describe, expect, it } from 'vitest'

import type { CapabilityExecutor, CapabilityResult, DaemonConfig, IdeService, ResourceUploader } from '../src/contracts.js'
import { encodeInvoke, decodeEnvelope, type Envelope } from '../src/protocol.js'
import { Runtime, type TemporaryResourceSweeper, type UpdatePort } from '../src/runtime.js'
import {
  FakeTransport,
  fakeProcessSupervisor,
  serverMessage,
  testConfig,
  tick,
  waitFor,
} from './helpers.js'

const ENV = '11111111-2222-4333-8444-555555555555'
const OTHER_ENV = '99999999-2222-4333-8444-555555555555'

const environment = {
  operatingSystem: 'linux' as const,
  timeZone: 'UTC',
  userName: 'dev',
  homeDirectory: '/home/dev',
  note: 'Linux environment.',
  tempDirectory: '/tmp',
}

const welcomePayload = {
  environmentId: ENV,
  name: 'env',
  maxResourceBytes: 1024 * 1024,
  temporaryResourceTtlSeconds: 60,
  temporaryResourceCleanupIntervalSeconds: 30,
}

function invokePayload(capabilityId = 'fs.read', timeoutMillis = 0, args: Record<string, unknown> = {}): Record<string, unknown> {
  return JSON.parse(encodeInvoke({ capabilityId, capabilityVersion: '2', arguments: args, timeoutMillis })) as Record<string, unknown>
}

function sent(transport: FakeTransport): Envelope[] {
  return transport.sent.map((message) => decodeEnvelope(message))
}

function textResult(callId: string, text = 'ok'): CapabilityResult {
  return { callId, error: false, details: {}, contents: [{ type: 'text', text }] }
}

interface Setup {
  transport: FakeTransport
  runtime: Runtime
  config: DaemonConfig
}

function buildRuntime(options: {
  capabilities?: Record<string, CapabilityExecutor>
  resources?: ResourceUploader
  update?: UpdatePort
  ide?: IdeService
  temporaryResources?: TemporaryResourceSweeper
  heartbeatIntervalMillis?: number
  maxConcurrentInvocations?: number
  ideCommandTimeoutMillis?: number
} = {}): Setup {
  const transport = new FakeTransport()
  const config = testConfig()
  const runtime = new Runtime({
    config,
    transport,
    capabilities: options.capabilities ?? { 'fs.read': async (call) => textResult(call.invocationId) },
    processes: fakeProcessSupervisor,
    environment,
    resources: options.resources,
    update: options.update,
    ide: options.ide,
    temporaryResources: options.temporaryResources,
    heartbeatIntervalMillis: options.heartbeatIntervalMillis ?? 10_000,
    initialReconnectDelayMillis: 5,
    maxReconnectDelayMillis: 20,
    maxConcurrentInvocations: options.maxConcurrentInvocations,
    ideCommandTimeoutMillis: options.ideCommandTimeoutMillis,
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

describe('runtime handshake', () => {
  it('sends HELLO with the instance identity and no environment scope', async () => {
    const setup = buildRuntime()
    setup.runtime.start()
    await tick()
    const hello = sent(setup.transport).find((envelope) => envelope.messageType === 'HELLO')
    expect(hello).toBeDefined()
    expect(hello?.environmentId).toBeNull()
    expect(hello?.payload.protocolVersion).toBe(3)
    expect(hello?.payload.capabilityCatalogVersion).toBe('2')
    expect(hello?.payload.daemonVersion).toBe('1.0.12')
    expect(hello?.payload.registrationToken).toBe('test-registration-token')
    expect(typeof hello?.payload.daemonInstanceId).toBe('string')
    setup.runtime.close()
  })

  it('binds the environment and sends READY on WELCOME', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    expect(setup.runtime.getBoundEnvironmentId()).toBe(ENV)
    const ready = sent(setup.transport).find((envelope) => envelope.messageType === 'READY')
    expect(ready?.environmentId).toBe(ENV)
    expect((ready?.payload as { version: number }).version).toBe(4)
    setup.runtime.close()
  })

  it('sends HEARTBEAT only while READY', async () => {
    const setup = buildRuntime({ heartbeatIntervalMillis: 20 })
    await handshake(setup)
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'HEARTBEAT'))
    setup.runtime.close()
  })

  it('rejects WELCOME for a mismatched environment after binding', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    setup.transport.receive(serverMessage('HEARTBEAT', { environmentId: OTHER_ENV, payload: {} }))
    await tick()
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR')).toBe(true)
    setup.runtime.close()
  })
})

describe('runtime invocation lifecycle', () => {
  it('executes INVOKE and emits STARTED then COMPLETED', async () => {
    const calls: string[] = []
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call) => {
          calls.push(call.invocationId)
          return textResult(call.invocationId)
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
    const types = sent(setup.transport).map((envelope) => envelope.messageType)
    expect(types).toContain('STARTED')
    expect(types).toContain('COMPLETED')
    expect(calls).toEqual(['inv-1'])
    setup.runtime.close()
  })

  it('dedups duplicate INVOKE while running and replays STARTED', async () => {
    let release: (() => void) | null = null
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    let calls = 0
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call) => {
          calls++
          await gate
          return textResult(call.invocationId)
        },
      },
    })
    await handshake(setup)
    const message = serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() })
    setup.transport.receive(message)
    await tick()
    setup.transport.receive(message)
    await tick()
    expect(calls).toBe(1)
    const replayed = sent(setup.transport).filter((envelope) => envelope.messageType === 'STARTED')
    expect(replayed.length).toBe(2)
    expect(replayed[1]?.payload).toEqual({ replayed: true })
    release?.()
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
    setup.runtime.close()
  })

  it('replays the terminal result for a duplicate INVOKE after completion', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    const message = serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() })
    setup.transport.receive(message)
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
    const completed = sent(setup.transport).find((envelope) => envelope.messageType === 'COMPLETED')
    setup.transport.receive(message)
    await tick()
    const completions = sent(setup.transport).filter((envelope) => envelope.messageType === 'COMPLETED')
    expect(completions.length).toBe(2)
    expect(completions[1]?.payload).toEqual(completed?.payload)
    setup.runtime.close()
  })

  it('cancels a running invocation and aborts the capability signal', async () => {
    let observedSignal: AbortSignal | null = null
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          observedSignal = context.signal
          await new Promise<void>((resolve) => context.signal.addEventListener('abort', () => resolve(), { once: true }))
          throw new Error('cancelled')
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
    await tick()
    setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId: 'inv-1', payload: {} }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'CANCELLED'))
    expect(observedSignal?.aborted).toBe(true)
    setup.runtime.close()
  })

  it('ignores CANCEL for an unknown invocation and replays a terminal one', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId: 'missing', payload: {} }))
    await tick()
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'CANCELLED')).toBe(false)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
    setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId: 'inv-1', payload: {} }))
    await tick()
    // 终态不可变：重放 COMPLETED，绝不改成 CANCELLED。
    expect(sent(setup.transport).filter((envelope) => envelope.messageType === 'COMPLETED').length).toBe(2)
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'CANCELLED')).toBe(false)
    setup.runtime.close()
  })

  it('times out a long-running invocation', async () => {
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async () => new Promise<CapabilityResult>(() => {}),
      },
    })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload('fs.read', 20) }),
    )
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'FAILED'))
    const failed = sent(setup.transport).find((envelope) => envelope.messageType === 'FAILED')
    expect(String((failed?.payload as { message: string }).message)).toContain('timed out')
    setup.runtime.close()
  })

  it('fails unknown capabilities and mismatched capability versions', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload('nope.nope') }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'FAILED'))
    expect(setup.runtime.getJournal().find('inv-1')?.state).toBe('FAILED')

    const mismatched = JSON.parse(
      encodeInvoke({ capabilityId: 'fs.read', capabilityVersion: '9', arguments: {}, timeoutMillis: 0 }),
    ) as Record<string, unknown>
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-2', payload: mismatched }))
    await waitFor(() => setup.runtime.getJournal().find('inv-2')?.state === 'FAILED')
    setup.runtime.close()
  })

  it('forwards progress and rejects binary progress', async () => {
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          await context.progress({ callId: call.invocationId, error: false, details: {}, contents: [{ type: 'text', text: 'partial' }] })
          return textResult(call.invocationId)
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'PROGRESS'))
    expect(sent(setup.transport).find((envelope) => envelope.messageType === 'PROGRESS')?.payload).toBeDefined()
    setup.runtime.close()

    const bad = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          await context.progress({ callId: call.invocationId, error: false, details: {}, contents: [{ type: 'binary', bytes: new Uint8Array([1]), mediaType: 'text/plain' }] })
          return textResult(call.invocationId)
        },
      },
    })
    await handshake(bad)
    bad.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
    await waitFor(() => sent(bad.transport).some((envelope) => envelope.messageType === 'FAILED'))
    bad.runtime.close()
  })

  it('fails when the capability result callId mismatches', async () => {
    const setup = buildRuntime({
      capabilities: { 'fs.read': async () => textResult('other') },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'FAILED'))
    setup.runtime.close()
  })

  it('enforces the concurrency bound', async () => {
    const setup = buildRuntime({
      capabilities: { 'fs.read': async () => new Promise<CapabilityResult>(() => {}) },
      maxConcurrentInvocations: 1,
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
    await tick()
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-2', payload: invokePayload() }))
    await waitFor(() => setup.runtime.getJournal().find('inv-2')?.state === 'FAILED')
    setup.runtime.close()
  })
})

describe('runtime resource upload', () => {
  it('externalizes binary results through the resource uploader', async () => {
    const uploaded: { invocationId: string; size: number }[] = []
    const resources: ResourceUploader = {
      async upload(invocationId, content) {
        uploaded.push({ invocationId, size: content.bytes.byteLength })
        return {
          type: 'resource',
          uploadId: '77777777-7777-4777-8777-777777777777',
          mediaType: content.mediaType,
          name: content.name ?? null,
          size: content.bytes.byteLength,
          sha256: 'a'.repeat(64),
        }
      },
    }
    const setup = buildRuntime({
      resources,
      capabilities: {
        'fs.read': async (call) => ({
          callId: call.invocationId,
          error: false,
          details: {},
          contents: [{ type: 'binary', bytes: new Uint8Array([1, 2, 3]), mediaType: 'application/octet-stream' }],
        }),
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
    expect(uploaded).toEqual([{ invocationId: 'inv-1', size: 3 }])
    const completed = sent(setup.transport).find((envelope) => envelope.messageType === 'COMPLETED')
    expect(JSON.stringify(completed?.payload)).toContain('77777777-7777-4777-8777-777777777777')
    setup.runtime.close()
  })

  it('fails deterministically when binary content exceeds the announced budget', async () => {
    let uploads = 0
    const resources: ResourceUploader = {
      async upload() {
        uploads++
        throw new Error('must not be called')
      },
    }
    const setup = buildRuntime({
      resources,
      capabilities: {
        'fs.read': async (call) => ({
          callId: call.invocationId,
          error: false,
          details: {},
          contents: [{ type: 'binary', bytes: new Uint8Array(2 * 1024 * 1024), mediaType: 'application/octet-stream' }],
        }),
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'FAILED'))
    expect(uploads).toBe(0)
    setup.runtime.close()
  })
})

describe('runtime update handling', () => {
  const operationId = '33333333-4444-4555-8666-777777777777'
  const command = {
    operationId,
    targetVersion: '1.0.13',
    artifactUrl: 'https://github.com/fengwk/kk-studio/releases/download/v1.0.13/kk-studio-daemon-v1.0.13.jar',
    artifactSha256: 'a'.repeat(64),
  }

  it('acknowledges ACCEPTED then reports PREPARED and dedups by operationId', async () => {
    let handled = 0
    const update: UpdatePort = {
      async handleUpdate() {
        handled++
        return { phase: 'PREPARED' }
      },
    }
    const setup = buildRuntime({ update })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'PREPARED'))
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await tick()
    expect(handled).toBe(1)
    setup.runtime.close()
  })

  it('reports FAILED when the updater throws', async () => {
    const update: UpdatePort = {
      async handleUpdate() {
        throw new Error('download failed')
      },
    }
    const setup = buildRuntime({ update })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(() =>
      sent(setup.transport).some((envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'FAILED'),
    )
    setup.runtime.close()
  })

  it('rejects a different update while one is in progress', async () => {
    const gate = new Promise<void>(() => {})
    const update: UpdatePort = {
      async handleUpdate() {
        await gate
        return { phase: 'PREPARED' }
      },
    }
    const setup = buildRuntime({ update })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await tick()
    setup.transport.receive(
      serverMessage('UPDATE', {
        environmentId: ENV,
        payload: { ...command, operationId: '44444444-4444-4555-8666-777777777777' },
      }),
    )
    await tick()
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR')).toBe(true)
    setup.runtime.close()
  })
})

describe('runtime IDE commands', () => {
  it('dispatches IDE_COMMAND and replies with IDE_RESULT', async () => {
    let handled = 0
    const setup = buildRuntime({
      ide: {
        async handle() {
          handled++
          return { ok: true, viewId: ENV }
        },
        async close() {},
      },
    })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: 'ide-1', payload: { action: 'OPEN_VIEW', viewId: ENV } }),
    )
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'IDE_RESULT'))
    const result = sent(setup.transport).find((envelope) => envelope.messageType === 'IDE_RESULT')
    expect(result?.invocationId).toBe('ide-1')
    expect(result?.payload).toMatchObject({ ok: true })
    expect(handled).toBe(1)
    setup.runtime.close()
  })

  it('dedups an in-flight IDE request and reports unavailable service', async () => {
    let handled = 0
    let release: (() => void) | null = null
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    const setup = buildRuntime({
      ide: {
        async handle() {
          handled++
          await gate
          return { ok: true }
        },
        async close() {},
      },
    })
    await handshake(setup)
    const message = serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: 'ide-1', payload: { action: 'FOCUS_TERMINAL', viewId: ENV } })
    setup.transport.receive(message)
    await tick()
    setup.transport.receive(message)
    await tick()
    expect(handled).toBe(1)
    release?.()
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'IDE_RESULT'))
    setup.runtime.close()

    const withoutService = buildRuntime()
    await handshake(withoutService)
    withoutService.transport.receive(
      serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: 'ide-2', payload: { action: 'CLOSE_VIEW', viewId: ENV } }),
    )
    await waitFor(() => sent(withoutService.transport).some((envelope) => envelope.messageType === 'IDE_RESULT'))
    const result = sent(withoutService.transport).find((envelope) => envelope.messageType === 'IDE_RESULT')
    expect(result?.payload).toMatchObject({ ok: false })
    withoutService.runtime.close()
  })

  it('aborts an IDE request that exceeds its deadline', async () => {
    let aborted = false
    const setup = buildRuntime({
      ideCommandTimeoutMillis: 20,
      ide: {
        async handle(_command, signal) {
          await new Promise<void>((resolve) => signal.addEventListener('abort', () => resolve(), { once: true }))
          aborted = true
          throw new Error('aborted')
        },
        async close() {},
      },
    })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: 'ide-1', payload: { action: 'OPEN_VIEW', viewId: ENV } }),
    )
    await waitFor(() => aborted)
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'IDE_RESULT'))
    setup.runtime.close()
  })
})

describe('runtime connection management', () => {
  it('keeps in-flight Agent execution across a disconnect and replays after reconnect', async () => {
    let calls = 0
    let release: (() => void) | null = null
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call) => {
          calls++
          await gate
          return textResult(call.invocationId)
        },
      },
    })
    await handshake(setup)
    const invoke = serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() })
    setup.transport.receive(invoke)
    await tick()
    setup.transport.disconnect()
    await tick()
    // 断开不取消在途执行：journal 仍为 RUNNING。
    expect(setup.runtime.getJournal().find('inv-1')?.state).toBe('RUNNING')
    const firstConnection = setup.transport.connection

    // 显式等待重连并完成握手状态同步，再验证同 instance 的行为。
    await waitFor(() => setup.transport.connectCount >= 2)
    setup.transport.receive(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload }))
    await waitFor(() => setup.runtime.getState() === 'READY')
    expect(setup.transport.connection).not.toBe(firstConnection)

    // 执行仍在途：重放的 INVOKE 只得到 STARTED{replayed:true}，副作用恰好一次。
    setup.transport.receive(invoke)
    await waitFor(() => sent(setup.transport).filter((envelope) => envelope.messageType === 'STARTED').length >= 1)
    const replayed = sent(setup.transport).filter((envelope) => envelope.messageType === 'STARTED')
    expect(replayed.length).toBe(1)
    expect(replayed[0]?.payload).toEqual({ replayed: true })
    expect(calls).toBe(1)

    // 在途执行完成后，终态在新连接上送达。
    release?.()
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
    expect(calls).toBe(1)

    // 终态已落库：再次重放提供完全相同的 COMPLETED，绝不重复执行。
    setup.transport.receive(invoke)
    await waitFor(() => sent(setup.transport).filter((envelope) => envelope.messageType === 'COMPLETED').length >= 2)
    expect(calls).toBe(1)
    setup.runtime.close()
  })

  it('fails terminally on REGISTRATION_REJECTED and reconnects on RETRY_LATER', async () => {
    const setup = buildRuntime()
    setup.runtime.start()
    await tick()
    setup.transport.receive(serverMessage('ERROR', { payload: { code: 'REGISTRATION_REJECTED', message: 'no' } }))
    await tick()
    expect(await setup.runtime.awaitTermination()).toBe('FAILED')
    expect(setup.runtime.getFailureReason()).toContain('registration is rejected')

    const retry = buildRuntime()
    retry.runtime.start()
    await tick()
    retry.transport.receive(serverMessage('ERROR', { payload: { code: 'RETRY_LATER' } }))
    await waitFor(() => retry.transport.connectCount >= 2)
    retry.runtime.close()
  })

  it('responds with ERROR to unknown inbound message types', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    setup.transport.receive(serverMessage('READY', { environmentId: ENV, payload: {} }))
    await tick()
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR')).toBe(true)
    setup.runtime.close()
  })

  it('isolates stale connection generations', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    const firstListener = setup.transport.listener
    setup.transport.disconnect()
    await waitFor(() => setup.transport.connectCount >= 2)
    await tick()
    firstListener?.onMessage(serverMessage('HEARTBEAT', { environmentId: ENV, payload: {} }))
    await tick()
    // 旧代际的消息不应产生任何 ERROR 帧。
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR')).toBe(false)
    setup.runtime.close()
  })

  it('applies temporary resource policy updates', async () => {
    const swept: number[] = []
    const temporaryResources: TemporaryResourceSweeper = {
      async sweep(ttlSeconds) {
        swept.push(ttlSeconds)
      },
    }
    const setup = buildRuntime({ temporaryResources })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('TEMPORARY_RESOURCE_POLICY', {
        environmentId: ENV,
        payload: { temporaryResourceTtlSeconds: 120, temporaryResourceCleanupIntervalSeconds: 10 },
      }),
    )
    await tick()
    expect(setup.runtime.getState()).toBe('READY')
    setup.runtime.close()
  })
})

describe('runtime protocol version and scope', () => {
  it('rejects an unsupported protocol version', async () => {
    const setup = buildRuntime()
    setup.runtime.start()
    await tick()
    setup.transport.receive(
      JSON.stringify({ protocolVersion: 2, messageType: 'WELCOME', environmentId: ENV, payload: welcomePayload }),
    )
    await tick()
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR')).toBe(true)
    setup.runtime.close()
  })

  it('exposes bound environment and journal accessors', async () => {
    const setup = buildRuntime()
    expect(setup.runtime.getBoundEnvironmentId()).toBeNull()
    expect(setup.runtime.getState()).toBe('STOPPED')
    await handshake(setup)
    expect(setup.runtime.getJournal()).toBeDefined()
    setup.runtime.close()
    expect(setup.runtime.getState()).toBe('STOPPED')
  })
})
