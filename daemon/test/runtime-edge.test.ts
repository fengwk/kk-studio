import { describe, expect, it, vi } from 'vitest'
import { tmpdir } from 'node:os'
import { isAbsolute, resolve } from 'node:path'
import { create } from '@bufbuild/protobuf'

import type { CapabilityResult, DaemonConfig, IdeService, ResourceUploader } from '../src/contracts.js'
import { InMemoryInvocationJournal } from '../src/journal.js'
import { getOutboundNetwork, NetworkRuntime } from '../src/network.js'
import {
  Architecture,
  CancelSchema,
  EnvelopeSchema,
  EnvironmentConfigSchema,
  DaemonConfigurationSchema,
  ErrorSchema,
  IdeAction,
  IdeCommandSchema,
  InvokeSchema,
  MessageType,
  OperatingSystem,
  PresignedPutSchema,
  ReadySchema,
  RecoverSchema,
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
import { Runtime } from '../src/runtime.js'
import {
  detectEnvironmentInfo,
} from '../src/runtime-environment.js'
import type { RuntimeOptions, TemporaryResourceSweeper, UpdatePort } from '../src/runtime-ports.js'
import { ShutdownRuntime } from '../src/runtime-shutdown.js'
import { FakeConnection, FakeTransport, fakeProcessSupervisor, testConfig, tick, waitFor } from './helpers.js'

const UPDATE_TARGET_VERSION = '1.1.1'

const ENV = '11111111-2222-4333-8444-555555555555'
const environment = {
  operatingSystem: OperatingSystem.LINUX,
  architecture: Architecture.AMD64,
  timeZone: 'UTC',
  userName: 'dev',
  homeDirectory: '/home/dev',
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
          temporaryResourceTtlSeconds: 60n,
          temporaryResourceCleanupIntervalSeconds: 30n,
        }),
        ...overrides,
      }),
    },
  })
}

function makeEnvironmentConfigEnvelope(
  version = 0n,
  config: { studioUrl?: string; bashExecutable?: string; proxyUrl?: string } = {},
): Envelope {
  return create(EnvelopeSchema, {
    messageType: MessageType.ENVIRONMENT_CONFIG,
    environmentId: ENV,
    payload: {
      case: 'environmentConfig',
      value: create(EnvironmentConfigSchema, {
        version,
        config: create(DaemonConfigurationSchema, {
          studioUrl: config.studioUrl ?? 'https://studio.example.com',
          ...(config.bashExecutable === undefined ? {} : { bashExecutable: config.bashExecutable }),
          ...(config.proxyUrl === undefined ? {} : { proxyUrl: config.proxyUrl }),
        }),
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
        maxOutputBytes: 16n * 1024n * 1024n,
      }),
    },
  })
}

function sentTypes(transport: FakeTransport): MessageType[] {
  return transport.sent.map((envelope) => envelope.messageType)
}

function errorMessages(transport: FakeTransport): string[] {
  return transport.sent
    .filter((envelope) => envelope.messageType === MessageType.ERROR)
    .map((envelope) => (envelope.payload.case === 'error' ? envelope.payload.value.message : ''))
}

function cancelEnvelope(invocationId: string): Envelope {
  return create(EnvelopeSchema, {
    messageType: MessageType.CANCEL,
    environmentId: ENV,
    invocationId,
    payload: { case: 'cancel', value: create(CancelSchema, {}) },
  })
}

function recoverEnvelope(invocationId: string): Envelope {
  return create(EnvelopeSchema, {
    messageType: MessageType.RECOVER,
    environmentId: ENV,
    invocationId,
    payload: { case: 'recover', value: create(RecoverSchema, {}) },
  })
}

function serverReadyEnvelope(): Envelope {
  return create(EnvelopeSchema, {
    messageType: MessageType.READY,
    environmentId: ENV,
    payload: { case: 'ready', value: create(ReadySchema, {}) },
  })
}

async function ready(
  options: Partial<RuntimeOptions> = {},
): Promise<{ runtime: Runtime; transport: FakeTransport }> {
  const transport = options.transport instanceof FakeTransport ? options.transport : new FakeTransport()
  const runtime = new Runtime(baseOptions({ ...options, transport }))
  runtime.start()
  await waitFor(() => transport.connection !== null)
  transport.receive(makeSettingsEnvelope())
  transport.receive(makeEnvironmentConfigEnvelope())
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
    transport.receive(makeEnvironmentConfigEnvelope())
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
    transport.receive(makeEnvironmentConfigEnvelope())
    await waitFor(() => transport.connectCount >= 2)
    runtime.close()
  })
})

describe('runtime inbound protocol branches', () => {
  // 未初始化时同版本并发配置共享一次应用；重复帧不得提前放行 READY。
  it('does not complete READY from a duplicate config before the first application succeeds', async () => {
    const transport = new FakeTransport()
    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    let started!: () => void
    const preparing = new Promise<void>((resolve) => {
      started = resolve
    })
    let calls = 0
    const runtime = new Runtime(
      baseOptions({
        transport,
        prepareConfiguration: async (config) => {
          calls++
          started()
          await gate
          return { config, publish() {}, retire: async () => {} }
        },
      }),
    )
    runtime.start()
    try {
      await waitFor(() => transport.connection !== null)
      await tick()
      transport.receive(makeSettingsEnvelope())
      transport.receive(makeEnvironmentConfigEnvelope(1n))
      await preparing
      transport.receive(makeEnvironmentConfigEnvelope(1n))
      await tick()
      expect(runtime.getState()).toBe('CONNECTING')
      expect(sentTypes(transport).filter((type) => type === MessageType.READY)).toHaveLength(0)

      release()
      await waitFor(() => runtime.getState() === 'READY')
      expect(calls).toBe(1)
      expect(sentTypes(transport).filter((type) => type === MessageType.READY)).toHaveLength(1)
    } finally {
      runtime.close()
    }
  })

  // 初始候选校验失败不能制造「连上但永远收不到修正」的死角。
  it('keeps CONNECTING with a config error and accepts a corrected environment config', async () => {
    const transport = new FakeTransport()
    const networks = new NetworkRuntime(null)
    const runtime = new Runtime(
      baseOptions({
        transport,
        prepareConfiguration: async (config) => {
          const network = networks.prepare(config.proxyUrl)
          return { config, publish: () => network.publish(), retire: network.retire }
        },
      }),
    )
    runtime.start()
    try {
      await waitFor(() => transport.connection !== null)
      await tick()
      const first = transport.connection!
      transport.receive(makeSettingsEnvelope())
      transport.receive(makeEnvironmentConfigEnvelope(1n, { bashExecutable: '/definitely/missing/bash' }))
      await waitFor(() => runtime.getRuntimeStatus().status.configuration.error?.version === '1')
      expect(first.isOpen()).toBe(true)
      expect(runtime.getState()).toBe('CONNECTING')
      expect(runtime.getRuntimeStatus().status.configuration).toMatchObject({
        appliedVersion: null,
        error: { version: '1', code: 'VALIDATION_FAILED', field: 'bashExecutable' },
      })
      transport.receive(makeEnvironmentConfigEnvelope(2n, { proxyUrl: 'http://proxy.invalid:8080' }))
      await waitFor(() => transport.connectCount >= 2)
      transport.receive(makeSettingsEnvelope())
      transport.receive(makeEnvironmentConfigEnvelope(2n, { proxyUrl: 'http://proxy.invalid:8080' }))
      await waitFor(() => runtime.getState() === 'READY')
      expect(await getOutboundNetwork().selectProxy('https://outside.invalid', new AbortController().signal))
        .toEqual({ kind: 'proxy', url: 'http://proxy.invalid:8080' })
    } finally {
      runtime.close()
      await networks.close()
    }
  })

  // INVOKE 接受时冻结当前配置：热应用只影响后续调用，不改变在途执行。
  it('freezes the applied configuration at invocation admission', async () => {
    const transport = new FakeTransport()
    let releaseFirst!: () => void
    const firstGate = new Promise<void>((resolve) => {
      releaseFirst = resolve
    })
    let firstStarted!: () => void
    const started = new Promise<void>((resolve) => {
      firstStarted = resolve
    })
    const configs: DaemonConfig[] = []
    const runtime = new Runtime(
      baseOptions({
        transport,
        capabilities: {
          'fs.read': async (call, context) => {
            configs.push(context.config)
            if (configs.length === 1) {
              firstStarted()
              await firstGate
            }
            return {
              callId: call.invocationId,
              error: false,
              details: {},
              contents: [{ type: 'text', text: 'ok' }],
            }
          },
        },
      }),
    )
    runtime.start()
    try {
      await waitFor(() => transport.connection !== null)
      await tick()
      transport.receive(makeSettingsEnvelope())
      transport.receive(makeEnvironmentConfigEnvelope())
      await waitFor(() => runtime.getState() === 'READY')

      transport.receive(invokeEnvelope('frozen-1'))
      await started
      transport.receive(makeEnvironmentConfigEnvelope(1n, { bashExecutable: process.execPath }))
      await waitFor(() => runtime.getRuntimeStatus().status.configuration.appliedVersion === '1')
      releaseFirst()
      transport.receive(invokeEnvelope('frozen-2'))
      await waitFor(() => configs.length === 2)

      expect(configs[0]?.bashExecutableSetting).toBeNull()
      expect(configs[1]?.bashExecutableSetting).toBe(process.execPath)
    } finally {
      runtime.close()
    }
  })

  it('applies and clears config updates without another READY and keeps the previous snapshot on failure', async () => {
    const networks = new NetworkRuntime(null)
    const { runtime, transport } = await ready({
      prepareConfiguration: async (config) => {
        const network = networks.prepare(config.proxyUrl)
        return { config, publish: () => network.publish(), retire: network.retire }
      },
    })
    try {
      transport.receive(makeEnvironmentConfigEnvelope(1n, { bashExecutable: process.execPath }))
      await waitFor(() => runtime.getRuntimeStatus().status.configuration.appliedVersion === '1')
      transport.receive(makeEnvironmentConfigEnvelope(2n, { bashExecutable: '/definitely/missing/bash' }))
      await waitFor(() => runtime.getRuntimeStatus().status.configuration.error?.version === '2')
      expect(runtime.getRuntimeStatus().status.configuration).toMatchObject({
        appliedVersion: '1',
        proxyUrl: null,
        error: { version: '2', code: 'VALIDATION_FAILED', field: 'bashExecutable' },
      })
      transport.receive(makeEnvironmentConfigEnvelope(3n))
      await waitFor(() => runtime.getRuntimeStatus().status.configuration.appliedVersion === '3')
      expect(runtime.getRuntimeStatus().status.configuration.error).toBeNull()
      expect(sentTypes(transport).filter((type) => type === MessageType.READY)).toHaveLength(1)
    } finally {
      runtime.close()
      await networks.close()
    }
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
    transport.receive(makeEnvironmentConfigEnvelope())
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

  // 控制地址变化必须在候选准备开始就暂停新执行；候选失败后恢复旧准入。
  it('pauses admission from candidate preparation and restores it when the candidate fails', async () => {
    const gate = Promise.withResolvers<void>()
    const preparing = Promise.withResolvers<void>()
    const executions: string[] = []
    const { runtime, transport } = await ready({
      capabilities: {
        'fs.read': async (call) => {
          executions.push(call.invocationId)
          return {
            callId: call.invocationId,
            error: false,
            details: {},
            contents: [{ type: 'text', text: 'ok' }],
          }
        },
      },
      prepareConfiguration: async (config) => {
        if (config.configVersion === 1n) {
          preparing.resolve()
          await gate.promise
          throw new Error('candidate preparation failed')
        }
        return { config, publish() {}, retire: async () => {} }
      },
    })
    try {
      transport.receive(makeEnvironmentConfigEnvelope(1n, { studioUrl: 'https://next.example.com' }))
      await preparing.promise
      transport.receive(invokeEnvelope('blocked-during-preparation'))
      await waitFor(() => errorMessages(transport).some((message) => message.includes('switching')))
      expect(executions).toEqual([])
      expect(runtime.getRuntimeStatus().status.configuration.appliedVersion).toBe('0')

      gate.resolve()
      await waitFor(() => runtime.getRuntimeStatus().status.configuration.error?.version === '1')
      transport.receive(invokeEnvelope('admitted-after-failure'))
      await waitFor(() => executions.length === 1)
      expect(executions).toEqual(['admitted-after-failure'])
      expect(transport.connectCount).toBe(1)
    } finally {
      runtime.close()
      await runtime.awaitCompletion()
    }
  })

  // publish 期间仍在暂停；成功后保持暂停并主动重连，新连接 READY 前不得接新执行。
  it('keeps admission paused through publish and until the reconnected environment is READY', async () => {
    const gate = Promise.withResolvers<void>()
    const preparing = Promise.withResolvers<void>()
    const executions: string[] = []
    let publishAdmissionError: string | undefined
    const transport = new FakeTransport()
    const { runtime } = await ready({
      transport,
      capabilities: {
        'fs.read': async (call) => {
          executions.push(call.invocationId)
          return {
            callId: call.invocationId,
            error: false,
            details: {},
            contents: [{ type: 'text', text: 'ok' }],
          }
        },
      },
      prepareConfiguration: async (config) => {
        if (config.configVersion === 1n) {
          preparing.resolve()
          await gate.promise
          return {
            config,
            publish: () => {
              transport.receive(invokeEnvelope('blocked-during-publish'))
              publishAdmissionError = errorMessages(transport).find((message) => message.includes('switching'))
            },
            retire: async () => {},
          }
        }
        return { config, publish() {}, retire: async () => {} }
      },
    })
    try {
      transport.receive(makeEnvironmentConfigEnvelope(1n, { studioUrl: 'https://next.example.com' }))
      await preparing.promise
      transport.receive(invokeEnvelope('blocked-during-preparation'))
      await waitFor(() => errorMessages(transport).some((message) => message.includes('switching')))
      expect(executions).toEqual([])

      gate.resolve()
      await waitFor(() => transport.connectCount >= 2)
      expect(publishAdmissionError).toContain('switching')
      expect(executions).toEqual([])

      // 新连接尚未 SETTINGS/READY：任何 INVOKE 继续被拒绝。
      transport.receive(invokeEnvelope('blocked-while-connecting'))
      await waitFor(() => errorMessages(transport).some((message) => message.includes('INVOKE requires a READY')))
      expect(executions).toEqual([])

      transport.receive(makeSettingsEnvelope())
      transport.receive(makeEnvironmentConfigEnvelope(1n, { studioUrl: 'https://next.example.com' }))
      await waitFor(() => runtime.getState() === 'READY')
      transport.receive(invokeEnvelope('admitted-after-reconnect'))
      await waitFor(() => executions.length === 1)
      expect(executions).toEqual(['admitted-after-reconnect'])
    } finally {
      runtime.close()
      await runtime.awaitCompletion()
    }
  })

  // 连接级协议违规在 READY 后也必须关闭连接，而不是只回 ERROR 继续复用。
  it('closes a READY connection on scope mismatch', async () => {
    const { runtime, transport } = await ready()
    try {
      const first = transport.connection!
      transport.receive(
        create(EnvelopeSchema, {
          messageType: MessageType.CANCEL,
          environmentId: '99999999-2222-4333-8444-555555555555',
          invocationId: 'scope-mismatch',
          payload: { case: 'cancel', value: create(CancelSchema, {}) },
        }),
      )
      await waitFor(() => errorMessages(transport).some((message) => message.includes('does not match daemon')))
      await waitFor(() => !first.isOpen())
      await waitFor(() => transport.connectCount >= 2)
    } finally {
      runtime.close()
      await runtime.awaitCompletion()
    }
  })

  it('closes a READY connection when the same version changes content', async () => {
    const { runtime, transport } = await ready()
    try {
      const first = transport.connection!
      transport.receive(makeEnvironmentConfigEnvelope(0n, { bashExecutable: process.execPath }))
      await waitFor(() =>
        errorMessages(transport).some((message) => message.includes('same environment config version')),
      )
      await waitFor(() => !first.isOpen())
      expect(runtime.getRuntimeStatus().status.configuration).toMatchObject({
        appliedVersion: '0',
        error: null,
      })
    } finally {
      runtime.close()
      await runtime.awaitCompletion()
    }
  })

  it('closes a READY connection on a frame the daemon must never receive', async () => {
    const { runtime, transport } = await ready()
    try {
      const first = transport.connection!
      transport.receive(serverReadyEnvelope())
      await waitFor(() => errorMessages(transport).some((message) => message.includes('must not receive READY')))
      await waitFor(() => !first.isOpen())
    } finally {
      runtime.close()
      await runtime.awaitCompletion()
    }
  })

  // 发布不变量破坏时不能自动重连继续使用混合资源状态，必须进入 FAILED 终止。
  it('enters FAILED instead of reconnecting when configuration publication is inconsistent', async () => {
    const transport = new FakeTransport()
    const runtime = new Runtime(
      baseOptions({
        transport,
        prepareConfiguration: async (config) => ({
          config,
          publish: () => {
            throw new Error('publish invariants broken')
          },
          retire: async () => {},
        }),
      }),
    )
    runtime.start()
    await waitFor(() => transport.connection !== null)
    transport.receive(makeSettingsEnvelope())
    transport.receive(makeEnvironmentConfigEnvelope())
    await waitFor(() => runtime.getState() === 'FAILED')
    expect(runtime.getFailureReason()).toBe('environment configuration is inconsistent')
    await runtime.awaitCompletion()
    expect(transport.connectCount).toBe(1)
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
      transport.receive(makeEnvironmentConfigEnvelope())
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
              temporaryResourceTtlSeconds: 7n,
              temporaryResourceCleanupIntervalSeconds: 1n,
            }),
          }),
        },
      }),
    )
    transport.receive(makeEnvironmentConfigEnvelope())
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

  /** 清扫失败只留下阶段与固定错误类型的诊断，不泄露原始 message，也不影响连接。 */
  it('reports a safe sweep diagnostic and keeps the connection alive', async () => {
    const stderr: string[] = []
    const originalStderrWrite = process.stderr.write
    process.stderr.write = ((chunk: string | Uint8Array) => {
      stderr.push(String(chunk))
      return true
    }) as any
    const temporaryResources: TemporaryResourceSweeper = {
      async sweep() {
        throw new TypeError('sweep failed at /tmp/private')
      },
    }
    const transport = new FakeTransport()
    const runtime = new Runtime(baseOptions({ transport, temporaryResources }))
    try {
      runtime.start()
      await waitFor(() => transport.connection !== null)
      transport.receive(
        create(EnvelopeSchema, {
          messageType: MessageType.SETTINGS,
          environmentId: ENV,
          payload: {
            case: 'settings',
            value: create(SettingsSchema, {
              resourceBudgets: create(ResourceBudgetsSchema, {
                temporaryResourceTtlSeconds: 7n,
                temporaryResourceCleanupIntervalSeconds: 1n,
              }),
            }),
          },
        }),
      )
      transport.receive(makeEnvironmentConfigEnvelope())
      await waitFor(() => runtime.getState() === 'READY')
      const connections = transport.connectCount
      await waitFor(() => stderr.includes('daemon temporary resource sweep failed: TypeError\n'), 3000)
      expect(stderr.join('')).not.toContain('/tmp/private')
      expect(runtime.getState()).toBe('READY')
      expect(runtime.getBoundEnvironmentId()).toBe(ENV)
      expect(transport.connectCount).toBe(connections)
    } finally {
      process.stderr.write = originalStderrWrite
      runtime.close()
      await runtime.awaitCompletion()
    }
  })
})

describe('runtime managed update branches', () => {
  const operationId = '33333333-4444-4555-8666-777777777777'
  const command = create(UpdateSchema, {
    operationId,
    targetVersion: UPDATE_TARGET_VERSION,
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
    transport.receive(makeEnvironmentConfigEnvelope())
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

describe('runtime CONNECTING recovery admission', () => {
  // 未 READY 只允许处理本进程确实在途的执行收尾；未知/终态事实必须等 READY 后再取回。
  it('does not recover unknown or terminal work before READY', async () => {
    const { runtime, transport } = await ready()
    try {
      transport.receive(invokeEnvelope('terminal-before-reconnect'))
      await waitFor(() => sentTypes(transport).includes(MessageType.COMPLETED))

      transport.disconnect()
      await waitFor(() => runtime.getState() === 'DISCONNECTED')
      await waitFor(() => transport.connectCount >= 2)
      transport.receive(makeSettingsEnvelope())
      expect(runtime.getState()).toBe('CONNECTING')

      transport.receive(recoverEnvelope('missing'))
      transport.receive(recoverEnvelope('terminal-before-reconnect'))
      transport.receive(cancelEnvelope('terminal-before-reconnect'))
      await tick()
      expect(sentTypes(transport)).not.toContain(MessageType.RECOVER_NOT_FOUND)
      expect(transport.sent.filter((envelope) => envelope.messageType === MessageType.COMPLETED)).toHaveLength(0)

      transport.receive(makeEnvironmentConfigEnvelope())
      await waitFor(() => runtime.getState() === 'READY')
      transport.receive(recoverEnvelope('missing'))
      await waitFor(() => sentTypes(transport).includes(MessageType.RECOVER_NOT_FOUND))
      expect(transport.sent.filter((envelope) => envelope.messageType === MessageType.COMPLETED)).toHaveLength(0)
      transport.receive(cancelEnvelope('terminal-before-reconnect'))
      await waitFor(
        () => transport.sent.filter((envelope) => envelope.messageType === MessageType.COMPLETED).length === 1,
      )
    } finally {
      runtime.close()
      await runtime.awaitCompletion()
    }
  })

  // READY 前仍可取消确属本进程在途的执行，收尾由执行器自身完成。
  it('cancels work that is actually in flight before READY', async () => {
    let aborted = false
    const { runtime, transport } = await ready({
      capabilities: {
        'fs.read': async (_call, context) => {
          await new Promise<void>((resolve) => {
            context.signal.addEventListener(
              'abort',
              () => {
                aborted = true
                resolve()
              },
              { once: true },
            )
          })
          throw new Error('cancelled')
        },
      },
    })
    try {
      transport.receive(invokeEnvelope('running-across-reconnect'))
      await waitFor(() => sentTypes(transport).includes(MessageType.STARTED))

      transport.disconnect()
      await waitFor(() => runtime.getState() === 'DISCONNECTED')
      await waitFor(() => transport.connectCount >= 2)
      transport.receive(makeSettingsEnvelope())
      transport.receive(cancelEnvelope('running-across-reconnect'))
      await waitFor(() => aborted)
      await waitFor(() => sentTypes(transport).includes(MessageType.CANCELLED))
    } finally {
      runtime.close()
      await runtime.awaitCompletion()
    }
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
    transport.receive(makeEnvironmentConfigEnvelope())
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
              expiresAtEpochMs: 1893456000000n,
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

describe('runtime shutdown convergence', () => {
  // 异步释放必须纳入 in-flight：终止只能在其真正结束后发布。
  it('waits for tracked asynchronous releases before finalizing', async () => {
    const shutdown = new ShutdownRuntime()
    const gate = Promise.withResolvers<void>()
    shutdown.releaseTracked('environment config close', () => gate.promise)
    let terminated = false
    const finalizing = shutdown.finalize(
      async () => {},
      () => {
        terminated = true
      },
    )
    await tick()
    expect(terminated).toBe(false)

    gate.resolve()
    await finalizing
    expect(terminated).toBe(true)
    expect(shutdown.failures).toEqual([])
  })

  // 释放失败仍要可靠记录并按阶段诊断，不能被静默 catch 吞掉。
  it('records a tracked release failure once', async () => {
    const failure = new Error('configuration close failed')
    const shutdown = new ShutdownRuntime()
    shutdown.releaseTracked('environment config close', () => Promise.reject(failure))
    let terminated = false
    await shutdown.finalize(
      async () => {},
      () => {
        terminated = true
      },
    )
    expect(terminated).toBe(true)
    expect(shutdown.failures).toEqual([failure])
  })

  // 配置 close 等待正在准备的候选与 retire；Runtime 终止不能早于缓存清理完成。
  it('waits for configuration close before publishing runtime termination', async () => {
    const gate = Promise.withResolvers<void>()
    const preparing = Promise.withResolvers<void>()
    let retired = false
    const { runtime, transport } = await ready({
      prepareConfiguration: async (config) => {
        if (config.configVersion === 1n) {
          preparing.resolve()
          await gate.promise
        }
        return {
          config,
          publish() {},
          retire: async () => {
            retired = true
          },
        }
      },
    })
    try {
      transport.receive(makeEnvironmentConfigEnvelope(1n, { bashExecutable: process.execPath }))
      await preparing.promise

      let completed = false
      const completion = runtime.awaitCompletion().then(() => {
        completed = true
      })
      runtime.close()
      await tick(2)
      expect(completed).toBe(false)
      expect(retired).toBe(false)

      gate.resolve()
      await completion
      expect(completed).toBe(true)
      expect(retired).toBe(true)
    } finally {
      runtime.close()
      await runtime.awaitCompletion()
    }
  })
})

describe('environment detection helpers', () => {
  it('collects host facts', () => {
    const config = testConfig()
    const info = detectEnvironmentInfo()
    expect(isAbsolute(info.homeDirectory)).toBe(true)
    expect(info.tempDirectory).toBe(tmpdir())
    expect(resolve(info.tempDirectory).startsWith(resolve(config.dataDir))).toBe(false)
    expect([
      OperatingSystem.WINDOWS,
      OperatingSystem.WSL,
      OperatingSystem.LINUX,
      OperatingSystem.MACOS,
    ]).toContain(info.operatingSystem)
  })

  it('detects WSL from the environment marker', () => {
    const previous = process.env.WSL_DISTRO_NAME
    const previousPlatform = Object.getOwnPropertyDescriptor(process, 'platform')!
    Object.defineProperty(process, 'platform', { value: 'linux', configurable: true })
    process.env.WSL_DISTRO_NAME = 'Ubuntu'
    try {
      expect(detectEnvironmentInfo().operatingSystem).toBe(OperatingSystem.WSL)
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
    expect([Architecture.AMD64, Architecture.ARM64]).toContain(
      detectEnvironmentInfo().architecture,
    )
    const original = Object.getOwnPropertyDescriptor(process, 'arch')
    Object.defineProperty(process, 'arch', { value: 'ia32', configurable: true })
    try {
      expect(() => detectEnvironmentInfo()).toThrow(/unsupported daemon architecture/)
    } finally {
      if (original !== undefined) {
        Object.defineProperty(process, 'arch', original)
      }
    }
  })

  it('validates environment detection edge cases', () => {
    const origPlatform = Object.getOwnPropertyDescriptor(process, 'platform')!
    Object.defineProperty(process, 'platform', { value: 'sunos', configurable: true })
    try {
      expect(() => detectEnvironmentInfo()).toThrow(/unsupported daemon operating system/)
    } finally {
      Object.defineProperty(process, 'platform', origPlatform)
    }
  })

  it('validates runtime shutdown helper failure types and release step diagnostics', async () => {
    const stderrLines: string[] = []
    const origStderrWrite = process.stderr.write
    process.stderr.write = ((chunk: string | Uint8Array) => {
      stderrLines.push(String(chunk))
      return true
    }) as any
    try {
      const shutdown = new ShutdownRuntime()
      shutdown.record('type-phase', new TypeError('type err'))
      shutdown.record('range-phase', new RangeError('range err'))
      shutdown.record('unknown-phase', 'string err')
      shutdown.record('error-phase', new Error('err'))

      expect(stderrLines).toContain('daemon type-phase failed: TypeError\n')
      expect(stderrLines).toContain('daemon range-phase failed: RangeError\n')
      expect(stderrLines).toContain('daemon unknown-phase failed: UnknownError\n')
      expect(stderrLines).toContain('daemon error-phase failed: Error\n')
      expect(stderrLines.join('')).not.toContain('type err')
      expect(stderrLines.join('')).not.toContain('range err')
      expect(stderrLines.join('')).not.toContain('string err')
    } finally {
      process.stderr.write = origStderrWrite
    }

    const shutdown = new ShutdownRuntime()
    shutdown.release('async-phase', () => Promise.reject(new Error('async fail')))
    await vi.waitFor(() => {
      expect(shutdown.failures.length).toBe(1)
    })

    shutdown.release('sync-phase', () => {
      throw new Error('sync fail')
    })
    expect(shutdown.failures.length).toBe(2)

    shutdown.collect('diag', new Error('diagnostic error'))
    expect(shutdown.failures.length).toBe(2)

    const origWrite = process.stderr.write
    try {
      process.stderr.write = (() => {
        throw new Error('stderr error')
      }) as any
      const diagShutdown = new ShutdownRuntime()
      diagShutdown.record('phase', new Error('err'))
      expect(diagShutdown.failures.length).toBe(2)

      const collectShutdown = new ShutdownRuntime()
      collectShutdown.collect('phase', new Error('err'))
      expect(collectShutdown.failures.length).toBe(1)
    } finally {
      process.stderr.write = origWrite
    }
  })
})
