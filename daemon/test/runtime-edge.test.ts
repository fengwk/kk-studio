import { describe, expect, it } from 'vitest'
import { isAbsolute } from 'node:path'

import type { CapabilityResult, DaemonConfig, IdeService, ResourceUploader } from '../src/contracts.js'
import { InMemoryInvocationJournal } from '../src/journal.js'
import { encodeInvoke, decodeEnvelope } from '../src/protocol.js'
import {
  Runtime,
  defaultNote,
  detectArchitecture,
  detectEnvironmentInfo,
  utf8LengthOf,
  type RuntimeOptions,
  type TemporaryResourceSweeper,
  type UpdatePort,
} from '../src/runtime.js'
import { FakeConnection, FakeTransport, fakeProcessSupervisor, serverMessage, testConfig, tick, waitFor } from './helpers.js'
import { artifactFixture } from './support/update-fixture.js'

const ENV = '11111111-2222-4333-8444-555555555555'
const environment = {
  operatingSystem: 'linux' as const,
  architecture: 'amd64' as const,
  timeZone: 'UTC',
  userName: 'dev',
  homeDirectory: '/home/dev',
  note: 'Linux environment.',
  tempDirectory: '/tmp',
}
const welcomePayload = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
  environmentId: ENV,
  name: 'env',
  maxResourceBytes: 1024 * 1024,
  temporaryResourceTtlSeconds: 60,
  temporaryResourceCleanupIntervalSeconds: 30,
  ...overrides,
})

function baseOptions(overrides: Partial<RuntimeOptions> = {}): RuntimeOptions {
  return {
    config: testConfig(),
    transport: new FakeTransport(),
    capabilities: { 'fs.read': async (call) => ({ callId: call.invocationId, error: false, details: {}, contents: [{ type: 'text', text: 'ok' }] }) },
    processes: fakeProcessSupervisor,
    environment,
    heartbeatIntervalMillis: 10_000,
    initialReconnectDelayMillis: 5,
    maxReconnectDelayMillis: 20,
    ...overrides,
  }
}

function invokeJson(args: Record<string, unknown> = {}): Record<string, unknown> {
  return JSON.parse(encodeInvoke({ capabilityId: 'fs.read', capabilityVersion: '3', arguments: args, timeoutMillis: 0 })) as Record<string, unknown>
}

function sentTypes(transport: FakeTransport): string[] {
  return transport.sent.map((message) => decodeEnvelope(message).messageType)
}

async function ready(options: Partial<RuntimeOptions> = {}): Promise<{ runtime: Runtime; transport: FakeTransport }> {
  const transport = options.transport instanceof FakeTransport ? options.transport : new FakeTransport()
  const runtime = new Runtime(baseOptions({ ...options, transport }))
  runtime.start()
  await tick()
  transport.receive(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload() }))
  await waitFor(() => runtime.getState() === 'READY')
  return { runtime, transport }
}

describe('runtime constructor validation', () => {
  it('rejects invalid numeric and version options', () => {
    expect(() => new Runtime(baseOptions({ daemonVersion: 'bad version' }))).toThrow(/daemonVersion must match/)
    expect(() => new Runtime(baseOptions({ initialReconnectDelayMillis: 100, maxReconnectDelayMillis: 10 }))).toThrow(
      /must not exceed maxReconnectDelayMillis/,
    )
    expect(() => new Runtime(baseOptions({ heartbeatIntervalMillis: 0 }))).toThrow(/heartbeatIntervalMillis must be a positive/)
    expect(() => new Runtime(baseOptions({ initialReconnectDelayMillis: -1 }))).toThrow(/must not be negative/)
    expect(() => new Runtime(baseOptions({ maxReconnectDelayMillis: 0 }))).toThrow(/maxReconnectDelayMillis must be a positive/)
    expect(() => new Runtime(baseOptions({ maxConcurrentInvocations: 0 }))).toThrow(/maxConcurrentInvocations must be a positive/)
    expect(() => new Runtime(baseOptions({ ideCommandTimeoutMillis: 0 }))).toThrow(/ideCommandTimeoutMillis must be a positive/)
  })

  it('accepts an injected resource uploader without creating a transfer client', async () => {
    const resources: ResourceUploader = {
      async upload() {
        throw new Error('unused')
      },
    }
    const transport = new FakeTransport()
    const runtime = new Runtime(baseOptions({ transport, resources }))
    runtime.start()
    await tick()
    transport.receive(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload() }))
    await waitFor(() => runtime.getState() === 'READY')
    // 注入 uploader 后没有内部 transfer client，控制面票据帧是安全的 no-op。
    transport.receive(serverMessage('RESOURCE_UPLOAD_TICKET', { environmentId: ENV, invocationId: 'inv-1', payload: { state: 'READY' } }))
    await tick()
    expect(runtime.getState()).toBe('READY')
    runtime.close()
  })
})

describe('runtime lifecycle guards', () => {
  it('is idempotent for start and close', async () => {
    const { runtime, transport } = await ready()
    const connects = transport.connectCount
    runtime.start()
    await tick()
    expect(transport.connectCount).toBe(connects)
    runtime.close()
    runtime.close()
    expect(runtime.getState()).toBe('STOPPED')
    runtime.start()
    expect(runtime.getState()).toBe('STOPPED')
  })

  it('fails a handshake when the registration token is missing and keeps reconnecting', async () => {
    const config = testConfig({ tokenPath: '/definitely/missing/token' })
    const transport = new FakeTransport()
    const runtime = new Runtime(baseOptions({ config, transport }))
    runtime.start()
    await waitFor(() => transport.connectCount >= 2)
    expect(runtime.getState()).toBe('DISCONNECTED')
    runtime.close()
  })

  it('reconnects when the transport connect attempt rejects', async () => {
    let attempts = 0
    const transport = new FakeTransport()
    transport.connect = async () => {
      attempts++
      throw new Error('connect boom')
    }
    const runtime = new Runtime(baseOptions({ transport }))
    runtime.start()
    await waitFor(() => attempts >= 2)
    expect(runtime.getState()).toBe('DISCONNECTED')
    runtime.close()
  })

  it('drops the connection when a send fails', async () => {
    const transport = new FakeTransport()
    transport.connect = async (listener) => {
      const connection = new FakeConnection()
      connection.sendText = async () => {
        throw new Error('send failed')
      }
      transport.listener = listener
      transport.connection = connection
      transport.connectCount++
      return connection
    }
    const runtime = new Runtime(baseOptions({ transport }))
    runtime.start()
    await waitFor(() => transport.connectCount >= 2)
    expect(runtime.getState()).toBe('DISCONNECTED')
    runtime.close()
  })
})

describe('runtime inbound protocol branches', () => {
  it('rejects a second WELCOME on the same connection', async () => {
    const { runtime, transport } = await ready()
    transport.receive(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload() }))
    await tick()
    expect(sentTypes(transport)).toContain('ERROR')
    runtime.close()
  })

  it('rejects a WELCOME with an invalid resource budget', async () => {
    const missing = new FakeTransport()
    const missingRuntime = new Runtime(baseOptions({ transport: missing }))
    missingRuntime.start()
    await tick()
    missing.receive(serverMessage('WELCOME', { environmentId: ENV, payload: { environmentId: ENV } }))
    await tick()
    expect(sentTypes(missing)).toContain('ERROR')
    missingRuntime.close()

    const zero = new FakeTransport()
    const zeroRuntime = new Runtime(baseOptions({ transport: zero }))
    zeroRuntime.start()
    await tick()
    zero.receive(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload({ maxResourceBytes: 0 }) }))
    await tick()
    expect(sentTypes(zero)).toContain('ERROR')
    zeroRuntime.close()
  })

  it('rejects a resource policy before READY and validates ERROR payloads', async () => {
    const transport = new FakeTransport()
    const runtime = new Runtime(baseOptions({ transport }))
    runtime.start()
    await tick()
    transport.receive(serverMessage('TEMPORARY_RESOURCE_POLICY', { payload: { temporaryResourceTtlSeconds: 10, temporaryResourceCleanupIntervalSeconds: 5 } }))
    await tick()
    expect(sentTypes(transport)).toContain('ERROR')

    transport.receive(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload() }))
    await waitFor(() => runtime.getState() === 'READY')
    transport.receive(serverMessage('ERROR', { environmentId: ENV, payload: { foo: 1 } }))
    await tick()
    transport.receive(serverMessage('ERROR', { environmentId: ENV, payload: {} }))
    await tick()
    transport.receive(serverMessage('ERROR', { environmentId: ENV, payload: { code: 5 } }))
    await tick()
    transport.receive(serverMessage('ERROR', { environmentId: ENV, payload: { code: 'NOPE' } }))
    await tick()
    expect(sentTypes(transport).filter((type) => type === 'ERROR').length).toBeGreaterThanOrEqual(4)
    runtime.close()
  })

  it('reports a blank capability error message with a safe fallback', async () => {
    const { runtime, transport } = await ready({
      capabilities: {
        'fs.read': async () => {
          throw new Error('   ')
        },
      },
    })
    transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokeJson() }))
    await waitFor(() => sentTypes(transport).includes('FAILED'))
    runtime.close()
  })
})

describe('runtime journal capacity recovery evidence', () => {
  it('recovery CANCEL never starts an evicted invocation while INVOKE re-executes it', async () => {
    const invocationA = 'inv-journal-a'
    const invocationB = 'inv-journal-b'
    const journal = new InMemoryInvocationJournal(1)
    const aGate = Promise.withResolvers<void>()
    let aExecutions = 0
    let bExecutions = 0

    const transport = new FakeTransport()
    const runtime = new Runtime(
      baseOptions({
        transport,
        journal,
        capabilities: {
          'fs.read': async (call, context) => {
            if (call.invocationId === invocationA) {
              aExecutions += 1
              if (aExecutions === 1) {
                await aGate.promise
              }
            } else {
              bExecutions += 1
            }
            // 两个 body 都留下真实退出证据；A 的确认发生在显式 gate 之后。
            context.confirmExecutionExit()
            return { callId: call.invocationId, error: false, details: {}, contents: [{ type: 'text', text: 'ok' }] }
          },
        },
      }),
    )

    const framesFor = (invocationId: string) =>
      transport.sent.map((message) => decodeEnvelope(message)).filter((envelope) => envelope.invocationId === invocationId)
    const typesFor = (invocationId: string) => framesFor(invocationId).map((envelope) => envelope.messageType)

    try {
      runtime.start()
      // 以实际 HELLO / READY 观察握手完成，不靠 tick 猜测连接已就绪。
      await waitFor(() => sentTypes(transport).includes('HELLO'))
      transport.receive(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload() }))
      await waitFor(() => runtime.getState() === 'READY')

      // A 先真正进入执行体并停在显式 gate；此时还没有任何终态可供容量淘汰。
      transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: invocationA, payload: invokeJson() }))
      await waitFor(() => aExecutions === 1)
      expect(aExecutions).toBe(1)

      // B 后进入并完成：真实 COMPLETED 与独立 EXECUTION_EXITED 都已发出。
      transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: invocationB, payload: invokeJson() }))
      await waitFor(
        () =>
          typesFor(invocationB).filter((type) => type === 'COMPLETED').length === 1 &&
          typesFor(invocationB).filter((type) => type === 'EXECUTION_EXITED').length === 1,
      )
      expect(bExecutions).toBe(1)
      expect(journal.find(invocationB)).toMatchObject({ state: 'COMPLETED', exitConfirmed: true })

      // 释放 A 并等它真实的 COMPLETED。A 的 RUNNING 条目早于 B 插入，转为终态后是最旧终态，先被自身容量淘汰。
      aGate.resolve()
      await waitFor(
        () =>
          typesFor(invocationA).filter((type) => type === 'COMPLETED').length === 1 &&
          journal.find(invocationA) === undefined,
      )
      expect(journal.find(invocationA)).toBeUndefined()
      expect(journal.find(invocationB)).toMatchObject({ state: 'COMPLETED', exitConfirmed: true })
      // A 的 body 已提供退出证据，但终态先写入就淘汰了自己的行，因此初次执行没有 EXECUTION_EXITED。
      expect(typesFor(invocationA).filter((type) => type === 'EXECUTION_EXITED')).toHaveLength(0)
      const aFramesBeforeCancel = typesFor(invocationA)

      // 恢复只发送 CANCEL：被淘汰的 A 未命中 journal，既不再执行，也不造终态、退出确认或 ACK。
      transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId: invocationA, payload: {} }))
      // CANCEL B 是 FIFO 正控制：必须重放 B 的具体终态与独立退出事实。
      transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId: invocationB, payload: {} }))
      await waitFor(
        () =>
          typesFor(invocationB).filter((type) => type === 'COMPLETED').length === 2 &&
          typesFor(invocationB).filter((type) => type === 'EXECUTION_EXITED').length === 2,
      )
      expect(aExecutions).toBe(1)
      expect(bExecutions).toBe(1)
      expect(typesFor(invocationA)).toEqual(aFramesBeforeCancel)
      expect(journal.find(invocationA)).toBeUndefined()
      expect(journal.find(invocationB)).toMatchObject({ state: 'COMPLETED', exitConfirmed: true })

      // 对照：若 Server 错把 INVOKE 当恢复查询，容量淘汰后已无终态可重放，A 会真实二次执行。
      // 这不是安全恢复，而是被禁止的恢复策略的可观测后果；第二次 body 完成后的独立退出帧证明正控制能产生 ACK。
      transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: invocationA, payload: invokeJson() }))
      await waitFor(
        () =>
          aExecutions === 2 &&
          typesFor(invocationA).filter((type) => type === 'COMPLETED').length === 2 &&
          typesFor(invocationA).filter((type) => type === 'EXECUTION_EXITED').length === 1,
      )
      expect(aExecutions).toBe(2)
      expect(typesFor(invocationA).filter((type) => type === 'STARTED').length).toBe(2)
      expect(typesFor(invocationA).filter((type) => type === 'EXECUTION_EXITED')).toHaveLength(1)
      expect(journal.find(invocationA)).toMatchObject({ state: 'COMPLETED', exitConfirmed: true })
    } finally {
      // 失败或正常结束都先释放 body gate，再关闭 Runtime，最后实际等待在途收尾完成。
      aGate.resolve()
      try {
        runtime.close()
      } finally {
        await runtime.awaitCompletion()
      }
    }
  })
})

describe('runtime temporary resource policy', () => {
  it('hot-updates the sweep interval and sweeps with the active TTL', async () => {
    const sweeps: number[] = []
    const temporaryResources: TemporaryResourceSweeper = {
      async sweep(ttlSeconds) {
        sweeps.push(ttlSeconds)
        throw new Error('sweep failed')
      },
    }
    const transport = new FakeTransport()
    const runtime = new Runtime(baseOptions({ transport, temporaryResources }))
    runtime.start()
    await tick()
    transport.receive(
      serverMessage('WELCOME', {
        environmentId: ENV,
        payload: welcomePayload({ temporaryResourceTtlSeconds: 7, temporaryResourceCleanupIntervalSeconds: 1 }),
      }),
    )
    await waitFor(() => runtime.getState() === 'READY')
    // 相同间隔的重复策略不重建定时器。
    transport.receive(
      serverMessage('TEMPORARY_RESOURCE_POLICY', {
        environmentId: ENV,
        payload: { temporaryResourceTtlSeconds: 9, temporaryResourceCleanupIntervalSeconds: 1 },
      }),
    )
    await waitFor(() => sweeps.length >= 1, 3000)
    expect(sweeps[0]).toBe(9)
    runtime.close()
  })
})

describe('runtime managed update branches', () => {
  const operationId = '33333333-4444-4555-8666-777777777777'
  const command = {
    operationId,
    targetVersion: artifactFixture.targetVersion,
    artifactUrl: artifactFixture.accepted[0]!.artifactUrl,
    artifactSha256: artifactFixture.artifactSha256,
  }

  it('rejects an UPDATE before READY', async () => {
    const transport = new FakeTransport()
    const runtime = new Runtime(baseOptions({ transport, update: { async handleUpdate() { return { phase: 'PREPARED' } } } }))
    runtime.start()
    await tick()
    transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await tick()
    expect(sentTypes(transport)).toContain('ERROR')
    runtime.close()
  })

  it('fails an UPDATE when no updater is configured', async () => {
    const { runtime, transport } = await ready()
    transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(() =>
      transport.sent
        .map((message) => decodeEnvelope(message))
        .some((envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'FAILED'),
    )
    runtime.close()
  })

  it('ignores a repeated UPDATE while the same operation is in progress', async () => {
    let release: (() => void) | null = null
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    const update: UpdatePort = {
      async handleUpdate() {
        await gate
        return { phase: 'PREPARED' }
      },
    }
    const { runtime, transport } = await ready({ update })
    transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await tick()
    transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await tick()
    const accepted = transport.sent
      .map((message) => decodeEnvelope(message))
      .filter((envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'ACCEPTED')
    expect(accepted.length).toBe(1)
    release?.()
    await waitFor(() => runtime.getState() === 'READY')
    runtime.close()
  })

  it('resends the last update result after reconnect', async () => {
    const update: UpdatePort = {
      async handleUpdate() {
        return { phase: 'PREPARED' }
      },
    }
    const { runtime, transport } = await ready({ update })
    transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(() =>
      transport.sent.map((message) => decodeEnvelope(message)).some((envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'PREPARED'),
    )
    transport.disconnect()
    await waitFor(() => transport.connectCount >= 2)
    transport.receive(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload() }))
    await waitFor(() => runtime.getState() === 'READY')
    await waitFor(() =>
      transport.sent.map((message) => decodeEnvelope(message)).some((envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'PREPARED'),
    )
    runtime.close()
  })
})

describe('runtime IDE branches', () => {
  it('rejects an IDE_COMMAND before READY', async () => {
    const transport = new FakeTransport()
    const runtime = new Runtime(baseOptions({ transport }))
    runtime.start()
    await tick()
    transport.receive(serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: 'ide-1', payload: { action: 'OPEN_VIEW' } }))
    await tick()
    expect(sentTypes(transport)).toContain('ERROR')
    runtime.close()
  })

  it('aborts an in-flight IDE command on shutdown', async () => {
    let aborted = false
    const ide: IdeService = {
      async handle(_command, signal) {
        await new Promise<void>((resolve) => signal.addEventListener('abort', () => resolve(), { once: true }))
        aborted = true
        throw new Error('aborted')
      },
      async close() {},
    }
    const { runtime, transport } = await ready({ ide, ideCommandTimeoutMillis: 60_000 })
    transport.receive(serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: 'ide-1', payload: { action: 'OPEN_VIEW' } }))
    await tick()
    runtime.close()
    await waitFor(() => aborted)
  })
})

describe('runtime resource upload control plane', () => {
  const uploadId = '99999999-8888-4777-8666-555555555555'

  function envelopes(transport: FakeTransport): ReturnType<typeof decodeEnvelope>[] {
    return transport.sent.map((message) => decodeEnvelope(message))
  }

  it('uploads binary results through REQUEST → PUT → COMMIT → READY', async () => {
    let putBytes = 0
    const transport = new FakeTransport()
    const runtime = new Runtime(
      baseOptions({
        transport,
        capabilities: {
          'fs.read': async (call) => ({
            callId: call.invocationId,
            error: false,
            details: {},
            contents: [{ type: 'binary', bytes: new Uint8Array([1, 2, 3]), mediaType: 'application/octet-stream' }],
          }),
        },
        putObject: async (_presigned, bytes) => {
          putBytes += bytes.byteLength
        },
      }),
    )
    runtime.start()
    await tick()
    transport.receive(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload() }))
    await waitFor(() => runtime.getState() === 'READY')
    transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokeJson() }))
    await waitFor(() => envelopes(transport).some((envelope) => envelope.messageType === 'RESOURCE_UPLOAD_REQUEST'))
    const request = envelopes(transport).find((envelope) => envelope.messageType === 'RESOURCE_UPLOAD_REQUEST')
    const transferId = (request?.payload as { transferId: string }).transferId
    transport.receive(
      serverMessage('RESOURCE_UPLOAD_TICKET', {
        environmentId: ENV,
        invocationId: 'inv-1',
        payload: {
          transferId,
          state: 'PENDING',
          uploadId,
          presignedPut: { method: 'PUT', url: 'https://s3.example.com/x', headers: {}, expiresAt: '2030-01-01T00:00:00.000Z' },
        },
      }),
    )
    await waitFor(() => envelopes(transport).some((envelope) => envelope.messageType === 'RESOURCE_UPLOAD_COMMIT'))
    transport.receive(
      serverMessage('RESOURCE_UPLOAD_TICKET', {
        environmentId: ENV,
        invocationId: 'inv-1',
        payload: { transferId, state: 'READY', uploadId },
      }),
    )
    await waitFor(() => envelopes(transport).some((envelope) => envelope.messageType === 'COMPLETED'))
    expect(putBytes).toBe(3)
    const completed = envelopes(transport).find((envelope) => envelope.messageType === 'COMPLETED')
    expect(JSON.stringify(completed?.payload)).toContain(uploadId)
    runtime.close()
  })
})

describe('environment detection helpers', () => {
  it('collects host facts and stable notes', () => {
    const info = detectEnvironmentInfo(testConfig())
    expect(isAbsolute(info.homeDirectory)).toBe(true)
    expect(info.tempDirectory).toContain('tmp')
    expect(['windows', 'wsl', 'linux', 'macos']).toContain(info.operatingSystem)
    expect(defaultNote('windows')).toContain('Windows')
    expect(defaultNote('wsl')).toContain('WSL')
    expect(defaultNote('linux')).toContain('Linux')
    expect(defaultNote('macos')).toContain('macOS')
    expect(utf8LengthOf('中文')).toBe(6)
  })

  it('detects WSL from the environment marker', () => {
    const previous = process.env.WSL_DISTRO_NAME
    const previousPlatform = Object.getOwnPropertyDescriptor(process, 'platform')!
    Object.defineProperty(process, 'platform', { value: 'linux', configurable: true })
    process.env.WSL_DISTRO_NAME = 'Ubuntu'
    try {
      expect(detectEnvironmentInfo(testConfig()).operatingSystem).toBe('wsl')
    } finally {
      Object.defineProperty(process, 'platform', previousPlatform)
      if (previous === undefined) {
        delete process.env.WSL_DISTRO_NAME
      } else {
        process.env.WSL_DISTRO_NAME = previous
      }
    }
  })

  it('maps process.arch to the release architecture and fails closed otherwise', () => {
    expect(['amd64', 'arm64']).toContain(detectArchitecture())
    const original = Object.getOwnPropertyDescriptor(process, 'arch')
    Object.defineProperty(process, 'arch', { value: 'ia32', configurable: true })
    try {
      expect(() => detectArchitecture()).toThrow(/unsupported daemon architecture/)
    } finally {
      if (original !== undefined) {
        Object.defineProperty(process, 'arch', original)
      }
    }
  })
})
