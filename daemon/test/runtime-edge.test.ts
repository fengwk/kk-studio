import { describe, expect, it, vi } from 'vitest'
import { isAbsolute } from 'node:path'
import { create } from '@bufbuild/protobuf'

import type { CapabilityResult, DaemonConfig, IdeService, ResourceUploader } from '../src/contracts.js'
import { InMemoryInvocationJournal } from '../src/journal.js'
import {
  Architecture,
  CancelSchema,
  EnvelopeSchema,
  ErrorSchema,
  IdeAction,
  IdeCommandSchema,
  InvokeSchema,
  MessageType,
  OperatingSystem,
  PresignedPutSchema,
  ResourceBudgetsSchema,
  ResourceUploadTicketSchema,
  SettingsSchema,
  TemporaryResourcePolicySchema,
  TicketState,
  UpdatePhase,
  UpdateResultSchema,
  UpdateSchema,
  type Envelope,
  type Settings,
  type UpdateResult,
} from '../src/generated/control_pb.js'
import {
  Runtime,
  defaultNote,
  detectArchitecture,
  detectEnvironmentInfo,
  type RuntimeOptions,
  type TemporaryResourceSweeper,
  type UpdatePort,
} from '../src/runtime.js'
import {
  collectDiagnosticFailure,
  describeFailureType,
  recordShutdownFailure,
  releaseStep,
} from '../src/runtime-shutdown.js'
import { FakeConnection, FakeTransport, fakeProcessSupervisor, testConfig, tick, waitFor } from './helpers.js'
import { artifactFixture } from './support/update-fixture.js'

const ENV = '11111111-2222-4333-8444-555555555555'
const environment = {
  operatingSystem: OperatingSystem.LINUX,
  architecture: Architecture.AMD64,
  timeZone: 'UTC',
  userName: 'dev',
  homeDirectory: '/home/dev',
  note: 'Linux environment.',
  tempDirectory: '/tmp',
}

function makeSettingsEnvelope(overrides: Partial<Settings> = {}): Envelope {
  return create(EnvelopeSchema, {
    messageType: MessageType.SETTINGS,
    environmentId: ENV,
    payload: {
      case: 'settings',
      value: create(SettingsSchema, {
        resourceBudgets: create(ResourceBudgetsSchema, {
          maxResourceBytes: 1048576n,
          temporaryResourceTtlSeconds: 60n,
          temporaryResourceCleanupIntervalSeconds: 30n,
        }),
        ...overrides,
      }),
    },
  })
}

function baseOptions(overrides: Partial<RuntimeOptions> = {}): RuntimeOptions {
  return {
    config: testConfig(),
    transport: new FakeTransport(),
    capabilities: {
      'fs.read': async (call) => ({
        callId: call.invocationId,
        error: false,
        details: {},
        contents: [{ type: 'text', text: 'ok' }],
      }),
    },
    processes: fakeProcessSupervisor,
    environment,
    heartbeatIntervalMillis: 10_000,
    initialReconnectDelayMillis: 5,
    maxReconnectDelayMillis: 20,
    ...overrides,
  }
}

function invokeEnvelope(invocationId = 'inv-1', args: Record<string, unknown> = {}): Envelope {
  return create(EnvelopeSchema, {
    messageType: MessageType.INVOKE,
    environmentId: ENV,
    invocationId,
    payload: {
      case: 'invoke',
      value: create(InvokeSchema, {
        capabilityId: 'fs.read',
        argumentsJson: JSON.stringify(args),
        timeoutMillis: 60_000n,
      }),
    },
  })
}

function sentTypes(transport: FakeTransport): MessageType[] {
  return transport.sent.map((envelope) => envelope.messageType)
}

async function ready(
  options: Partial<RuntimeOptions> = {},
): Promise<{ runtime: Runtime; transport: FakeTransport }> {
  const transport = options.transport instanceof FakeTransport ? options.transport : new FakeTransport()
  const runtime = new Runtime(baseOptions({ ...options, transport }))
  runtime.start()
  await waitFor(() => transport.connection !== null)
  transport.receive(makeSettingsEnvelope())
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
    await waitFor(() => transport.connection !== null)
    await tick()
    transport.receive(makeSettingsEnvelope())
    await waitFor(() => runtime.getState() === 'READY')
    // 注入 uploader 后没有内部 transfer client，控制面票据帧是安全的 no-op。
    transport.receive(
      create(EnvelopeSchema, {
        messageType: MessageType.RESOURCE_UPLOAD_TICKET,
        environmentId: ENV,
        invocationId: 'inv-1',
        payload: {
          case: 'resourceUploadTicket',
          value: create(ResourceUploadTicketSchema, {
            transferId: 'transfer-1',
            state: TicketState.TICKET_READY,
            uploadId: 'upload-1',
          }),
        },
      }),
    )
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
      connection.send = () => false
      transport.listener = listener
      transport.connection = connection
      transport.connectCount++
      return connection
    }
    const runtime = new Runtime(baseOptions({ transport }))
    runtime.start()
    await waitFor(() => transport.connection !== null)
    await tick()
    transport.receive(makeSettingsEnvelope())
    await waitFor(() => transport.connectCount >= 2)
    runtime.close()
  })
})

describe('runtime inbound protocol branches', () => {
  it('rejects a second SETTINGS on the same connection', async () => {
    const { runtime, transport } = await ready()
    transport.receive(makeSettingsEnvelope())
    await tick()
    expect(sentTypes(transport)).toContain(MessageType.ERROR)
    runtime.close()
  })

  it('rejects a resource policy before READY and validates ERROR payloads', async () => {
    const transport = new FakeTransport()
    const runtime = new Runtime(baseOptions({ transport }))
    runtime.start()
    await waitFor(() => transport.connection !== null)
    await tick()
    transport.receive(
      create(EnvelopeSchema, {
        messageType: MessageType.TEMPORARY_RESOURCE_POLICY,
        environmentId: ENV,
        payload: {
          case: 'temporaryResourcePolicy',
          value: create(TemporaryResourcePolicySchema, {
            temporaryResourceTtlSeconds: 10n,
            temporaryResourceCleanupIntervalSeconds: 5n,
          }),
        },
      }),
    )
    await tick()
    expect(sentTypes(transport)).toContain(MessageType.ERROR)
    transport.receive(makeSettingsEnvelope())
    await waitFor(() => runtime.getState() === 'READY')
    transport.receive(
      create(EnvelopeSchema, {
        messageType: MessageType.ERROR,
        environmentId: ENV,
        payload: {
          case: 'error',
          value: create(ErrorSchema, {
            message: 'something failed',
          }),
        },
      }),
    )
    // 服务端 ERROR 导致断开并重新连接
    await waitFor(() => transport.connectCount >= 2)
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
    transport.receive(invokeEnvelope('inv-1'))
    await waitFor(() => sentTypes(transport).includes(MessageType.FAILED))
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
            context.confirmExecutionExit()
            return { callId: call.invocationId, error: false, details: {}, contents: [{ type: 'text', text: 'ok' }] }
          },
        },
      }),
    )

    const framesFor = (invocationId: string) =>
      transport.sent.filter((envelope) => envelope.invocationId === invocationId)
    const typesFor = (invocationId: string) => framesFor(invocationId).map((envelope) => envelope.messageType)

    try {
      runtime.start()
      await waitFor(() => transport.connection !== null)
      transport.receive(makeSettingsEnvelope())
      await waitFor(() => runtime.getState() === 'READY')

      // A 先真正进入执行体并停在显式 gate
      transport.receive(invokeEnvelope(invocationA))
      await waitFor(() => aExecutions === 1)
      expect(aExecutions).toBe(1)

      // B 后进入并完成
      transport.receive(invokeEnvelope(invocationB))
      await waitFor(
        () => typesFor(invocationB).filter((type) => type === MessageType.COMPLETED).length === 1,
      )
      expect(bExecutions).toBe(1)
      expect(journal.find(invocationB)).toMatchObject({ state: 'COMPLETED', exitConfirmed: true })

      // 释放 A 并等它真实的 COMPLETED
      aGate.resolve()
      await waitFor(
        () =>
          typesFor(invocationA).filter((type) => type === MessageType.COMPLETED).length === 1 &&
          journal.find(invocationA) === undefined,
      )
      expect(journal.find(invocationA)).toBeUndefined()
      expect(journal.find(invocationB)).toMatchObject({ state: 'COMPLETED', exitConfirmed: true })
      expect(typesFor(invocationA).filter((type) => type === MessageType.EXECUTION_EXITED)).toHaveLength(0)
      const aFramesBeforeCancel = typesFor(invocationA)

      // 恢复只发送 CANCEL
      transport.receive(
        create(EnvelopeSchema, {
          messageType: MessageType.CANCEL,
          environmentId: ENV,
          invocationId: invocationA,
          payload: { case: 'cancel', value: create(CancelSchema, {}) },
        }),
      )
      transport.receive(
        create(EnvelopeSchema, {
          messageType: MessageType.CANCEL,
          environmentId: ENV,
          invocationId: invocationB,
          payload: { case: 'cancel', value: create(CancelSchema, {}) },
        }),
      )
      await waitFor(
        () =>
          typesFor(invocationB).filter((type) => type === MessageType.COMPLETED).length === 2 &&
          typesFor(invocationB).filter((type) => type === MessageType.EXECUTION_EXITED).length === 2,
      )
      expect(aExecutions).toBe(1)
      expect(bExecutions).toBe(1)
      expect(typesFor(invocationA)).toEqual(aFramesBeforeCancel)
      expect(journal.find(invocationA)).toBeUndefined()
      expect(journal.find(invocationB)).toMatchObject({ state: 'COMPLETED', exitConfirmed: true })

      // 对照：INVOKE 重复执行
      transport.receive(invokeEnvelope(invocationA))
      await waitFor(
        () =>
          aExecutions === 2 &&
          typesFor(invocationA).filter((type) => type === MessageType.COMPLETED).length === 2,
      )
      expect(aExecutions).toBe(2)
      expect(typesFor(invocationA).filter((type) => type === MessageType.EXECUTION_EXITED)).toHaveLength(1)
      expect(journal.find(invocationA)).toMatchObject({ state: 'COMPLETED', exitConfirmed: true })
    } finally {
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
    await waitFor(() => transport.connection !== null)
    await tick()
    transport.receive(
      create(EnvelopeSchema, {
        messageType: MessageType.SETTINGS,
        environmentId: ENV,
        payload: {
          case: 'settings',
          value: create(SettingsSchema, {
            resourceBudgets: create(ResourceBudgetsSchema, {
              maxResourceBytes: 1048576n,
              temporaryResourceTtlSeconds: 7n,
              temporaryResourceCleanupIntervalSeconds: 1n,
            }),
          }),
        },
      }),
    )
    await waitFor(() => runtime.getState() === 'READY')
    // 相同间隔的重复策略不重建定时器。
    transport.receive(
      create(EnvelopeSchema, {
        messageType: MessageType.TEMPORARY_RESOURCE_POLICY,
        environmentId: ENV,
        payload: {
          case: 'temporaryResourcePolicy',
          value: create(TemporaryResourcePolicySchema, {
            temporaryResourceTtlSeconds: 9n,
            temporaryResourceCleanupIntervalSeconds: 1n,
          }),
        },
      }),
    )
    await waitFor(() => sweeps.length >= 1, 3000)
    expect(sweeps[0]).toBe(9)
    runtime.close()
  })
})

describe('runtime managed update branches', () => {
  const operationId = '33333333-4444-4555-8666-777777777777'
  const command = create(UpdateSchema, {
    operationId,
    targetVersion: artifactFixture.targetVersion,
    artifactUrl: artifactFixture.accepted[0]!.artifactUrl,
    artifactSha256: artifactFixture.artifactSha256,
  })

  function makeUpdateEnvelope(): Envelope {
    return create(EnvelopeSchema, {
      messageType: MessageType.UPDATE,
      environmentId: ENV,
      payload: {
        case: 'update',
        value: command,
      },
    })
  }

  it('rejects an UPDATE before READY', async () => {
    const transport = new FakeTransport()
    const runtime = new Runtime(baseOptions({ transport, update: { async handleUpdate() { return { phase: 'PREPARED' } } } }))
    runtime.start()
    await waitFor(() => transport.connection !== null)
    await tick()
    transport.receive(makeUpdateEnvelope())
    await tick()
    expect(sentTypes(transport)).toContain(MessageType.ERROR)
    runtime.close()
  })

  it('fails an UPDATE when no updater is configured', async () => {
    const { runtime, transport } = await ready()
    transport.receive(makeUpdateEnvelope())
    await waitFor(() =>
      transport.sent.some(
        (envelope) =>
          envelope.messageType === MessageType.UPDATE_RESULT &&
          (envelope.payload.value as UpdateResult).phase === UpdatePhase.UPDATE_FAILED,
      ),
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
    transport.receive(makeUpdateEnvelope())
    await tick()
    transport.receive(makeUpdateEnvelope())
    await tick()
    const accepted = transport.sent.filter(
      (envelope) =>
        envelope.messageType === MessageType.UPDATE_RESULT &&
        (envelope.payload.value as UpdateResult).phase === UpdatePhase.UPDATE_ACCEPTED,
    )
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
    transport.receive(makeUpdateEnvelope())
    await waitFor(() =>
      transport.sent.some(
        (envelope) =>
          envelope.messageType === MessageType.UPDATE_RESULT &&
          (envelope.payload.value as UpdateResult).phase === UpdatePhase.UPDATE_PREPARED,
      ),
    )
    transport.disconnect()
    await waitFor(() => transport.connectCount >= 2)
    transport.receive(makeSettingsEnvelope())
    await waitFor(() => runtime.getState() === 'READY')
    await waitFor(() =>
      transport.sent.some(
        (envelope) =>
          envelope.messageType === MessageType.UPDATE_RESULT &&
          (envelope.payload.value as UpdateResult).phase === UpdatePhase.UPDATE_PREPARED,
      ),
    )
    runtime.close()
  })
})

describe('runtime IDE branches', () => {
  it('rejects an IDE_COMMAND before READY', async () => {
    const transport = new FakeTransport()
    const runtime = new Runtime(baseOptions({ transport }))
    runtime.start()
    await waitFor(() => transport.connection !== null)
    await tick()
    transport.receive(
      create(EnvelopeSchema, {
        messageType: MessageType.IDE_COMMAND,
        environmentId: ENV,
        invocationId: 'ide-1',
        payload: {
          case: 'ideCommand',
          value: create(IdeCommandSchema, {
            action: IdeAction.LIST_VIEWS,
          }),
        },
      }),
    )
    await tick()
    expect(sentTypes(transport)).toContain(MessageType.ERROR)
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
    transport.receive(
      create(EnvelopeSchema, {
        messageType: MessageType.IDE_COMMAND,
        environmentId: ENV,
        invocationId: 'ide-1',
        payload: {
          case: 'ideCommand',
          value: create(IdeCommandSchema, {
            action: IdeAction.LIST_VIEWS,
          }),
        },
      }),
    )
    await tick()
    runtime.close()
    await waitFor(() => aborted)
  })
})

describe('runtime resource upload control plane', () => {
  const uploadId = '99999999-8888-4777-8666-555555555555'

  it('uploads binary results through REQUEST → PUT → COMMIT → READY', async () => {
    let putBytes = 0
    const transport = new FakeTransport()
    const runtime = new Runtime(
      baseOptions({
        transport,
        capabilities: {
          'fs.read': async (call, context) => {
            const uploaded = await context.resources.upload(
              call.invocationId,
              { type: 'binary', bytes: new Uint8Array([1, 2, 3]), mediaType: 'application/octet-stream' },
              context.signal,
            )
            return {
              callId: call.invocationId,
              error: false,
              details: {},
              contents: [uploaded],
            }
          },
        },
        putObject: async (_presigned, bytes) => {
          putBytes += bytes.byteLength
        },
      }),
    )
    runtime.start()
    await waitFor(() => transport.connection !== null)
    await tick()
    transport.receive(makeSettingsEnvelope())
    await waitFor(() => runtime.getState() === 'READY')
    transport.receive(invokeEnvelope('inv-1'))
    await waitFor(() => transport.sent.some((envelope) => envelope.messageType === MessageType.RESOURCE_UPLOAD_REQUEST))
    const request = transport.sent.find((envelope) => envelope.messageType === MessageType.RESOURCE_UPLOAD_REQUEST)
    const transferId = (request?.payload.value as { transferId: string }).transferId
    transport.receive(
      create(EnvelopeSchema, {
        messageType: MessageType.RESOURCE_UPLOAD_TICKET,
        environmentId: ENV,
        invocationId: 'inv-1',
        payload: {
          case: 'resourceUploadTicket',
          value: create(ResourceUploadTicketSchema, {
            transferId,
            state: TicketState.TICKET_PENDING,
            uploadId,
            presignedPut: create(PresignedPutSchema, {
              method: 'PUT',
              url: 'https://s3.example.com/x',
              expiresAt: '2030-01-01T00:00:00.000Z',
            }),
          }),
        },
      }),
    )
    await waitFor(() => transport.sent.some((envelope) => envelope.messageType === MessageType.RESOURCE_UPLOAD_COMMIT))
    transport.receive(
      create(EnvelopeSchema, {
        messageType: MessageType.RESOURCE_UPLOAD_TICKET,
        environmentId: ENV,
        invocationId: 'inv-1',
        payload: {
          case: 'resourceUploadTicket',
          value: create(ResourceUploadTicketSchema, {
            transferId,
            state: TicketState.TICKET_READY,
            uploadId,
          }),
        },
      }),
    )
    await waitFor(() => transport.sent.some((envelope) => envelope.messageType === MessageType.COMPLETED))
    expect(putBytes).toBe(3)
    const completed = transport.sent.find((envelope) => envelope.messageType === MessageType.COMPLETED)
    expect(JSON.stringify(completed?.payload, (_k, v) => typeof v === 'bigint' ? v.toString() : v)).toContain(uploadId)
    runtime.close()
  })
})

describe('environment detection helpers', () => {
  it('collects host facts and stable notes', () => {
    const info = detectEnvironmentInfo(testConfig())
    expect(isAbsolute(info.homeDirectory)).toBe(true)
    expect(info.tempDirectory).toContain('tmp')
    expect([
      OperatingSystem.WINDOWS,
      OperatingSystem.WSL,
      OperatingSystem.LINUX,
      OperatingSystem.MACOS,
    ]).toContain(info.operatingSystem)
    expect(defaultNote(OperatingSystem.WINDOWS)).toContain('Windows')
    expect(defaultNote(OperatingSystem.WSL)).toContain('WSL')
    expect(defaultNote(OperatingSystem.LINUX)).toContain('Linux')
    expect(defaultNote(OperatingSystem.MACOS)).toContain('macOS')
    expect(Buffer.byteLength('中文', 'utf8')).toBe(6)
  })

  it('detects WSL from the environment marker', () => {
    const previous = process.env.WSL_DISTRO_NAME
    const previousPlatform = Object.getOwnPropertyDescriptor(process, 'platform')!
    Object.defineProperty(process, 'platform', { value: 'linux', configurable: true })
    process.env.WSL_DISTRO_NAME = 'Ubuntu'
    try {
      expect(detectEnvironmentInfo(testConfig()).operatingSystem).toBe(OperatingSystem.WSL)
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
    expect([Architecture.AMD64, Architecture.ARM64]).toContain(detectArchitecture())
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

  it('validates environment detection edge cases and default note constraints', () => {
    expect(() => defaultNote(OperatingSystem.OPERATING_SYSTEM_UNSPECIFIED)).toThrow(/unspecified/)

    const origPlatform = Object.getOwnPropertyDescriptor(process, 'platform')!
    Object.defineProperty(process, 'platform', { value: 'sunos', configurable: true })
    try {
      expect(() => detectEnvironmentInfo(testConfig())).toThrow(/unsupported daemon operating system/)
    } finally {
      Object.defineProperty(process, 'platform', origPlatform)
    }
  })

  it('validates runtime shutdown helper failure types and release step diagnostics', async () => {
    expect(describeFailureType(new TypeError('type err'))).toBe('TypeError')
    expect(describeFailureType(new RangeError('range err'))).toBe('RangeError')
    expect(describeFailureType('string err')).toBe('UnknownError')
    expect(describeFailureType(new Error('err'))).toBe('Error')

    const failures: unknown[] = []
    releaseStep(failures, 'async-phase', () => Promise.reject(new Error('async fail')))
    await vi.waitFor(() => {
      expect(failures.length).toBe(1)
    })

    releaseStep(failures, 'sync-phase', () => {
      throw new Error('sync fail')
    })
    expect(failures.length).toBe(2)

    collectDiagnosticFailure(failures, 'diag', new Error('diagnostic error'))

    const origWrite = process.stderr.write
    try {
      process.stderr.write = (() => {
        throw new Error('stderr error')
      }) as any
      const diagFailures: unknown[] = []
      recordShutdownFailure(diagFailures, 'phase', new Error('err'))
      expect(diagFailures.length).toBe(2)

      const collectFailures: unknown[] = []
      collectDiagnosticFailure(collectFailures, 'phase', new Error('err'))
      expect(collectFailures.length).toBe(1)
    } finally {
      process.stderr.write = origWrite
    }
  })
})
