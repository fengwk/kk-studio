import { describe, expect, it, vi } from 'vitest'
import { mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { PassThrough } from 'node:stream'
import { create } from '@bufbuild/protobuf'

import type {
  CapabilityExecutor,
  CapabilityResult,
  DaemonConfig,
  IdeLeaseHandler,
  IdeService,
  ManagedProcess,
  ProcessExit,
  ProcessSupervisor,
  ResourceUploader,
} from '../src/contracts.js'
import { DAEMON_VERSION } from '../src/config.js'
import {
  CancelSchema,
  CapabilityResultSchema,
  CompletedSchema,
  ContentSchema,
  EnvelopeSchema,
  EnvironmentConfigSchema,
  DaemonConfigurationSchema,
  ErrorSchema,
  HeartbeatSchema,
  IdeAction as ProtoIdeAction,
  IdeCommandSchema,
  IdeLeaseBindingSchema,
  IdeLeaseSchema,
  IdeOpenMode as ProtoIdeOpenMode,
  InvokeSchema,
  JsonContentSchema,
  MessageType,
  ProgressSchema,
  ReadySchema,
  RecoverSchema,
  ResourceBudgetsSchema,
  ResourceContentSchema,
  ResourceUploadTicketSchema,
  SettingsSchema,
  SkillPackageSchema,
  SkillPackageStateSchema,
  SkillSyncSchema,
  TemporaryResourcePolicySchema,
  TextContentSchema,
  UpdatePhase,
  UpdateProgressSchema,
  UpdateSchema,
  UpdateResultSchema,
  type Content,
  type Envelope,
  type SkillPackage,
} from '../src/generated/control_pb.js'
import { Runtime } from '../src/runtime.js'
import type { TemporaryResourceSweeper, UpdatePort, UpdateProgressReporter } from '../src/runtime-ports.js'
import { fileCapabilities } from '../src/coding/files.js'
import {
  FakeTransport,
  fakeProcessSupervisor,
  processCapabilities,
  testConfig,
  tick,
  waitFor,
} from './helpers.js'

const UPDATE_TARGET_VERSION = '1.1.1'

const ENV = '11111111-2222-4333-8444-555555555555'
const OTHER_ENV = '99999999-2222-4333-8444-555555555555'

const environment = {
  operatingSystem: 'linux' as const,
  architecture: 'amd64' as const,
  timeZone: 'UTC',
  userName: 'dev',
  homeDirectory: '/home/dev',
  note: 'Linux environment.',
  tempDirectory: '/tmp',
}

export interface DecodedSentEnvelope {
  messageType: string
  environmentId: string | null
  invocationId: string | null
  payload: any
  raw: Envelope
}


function encodeInvoke(options: { capabilityId: string; arguments?: Record<string, unknown>; timeoutMillis?: number }): string {
  return JSON.stringify({
    capabilityId: options.capabilityId,
    arguments: options.arguments ?? {},
    timeoutMillis: options.timeoutMillis ?? 0,
  })
}

function decodeCapabilities(payloadOrJson: any): { runtimeStatus: { revision: string; status: any } } {
  if (typeof payloadOrJson === 'object' && payloadOrJson !== null) {
    return payloadOrJson
  }
  return JSON.parse(payloadOrJson)
}

function decodeHeartbeat(payloadOrJson: any): { runtimeStatus: any; ideLeaseRound: string | null } {
  const parsed = typeof payloadOrJson === 'object' && payloadOrJson !== null ? payloadOrJson : JSON.parse(payloadOrJson)
  return {
    runtimeStatus: parsed.runtimeStatus,
    ideLeaseRound: parsed.ideLeaseRound !== undefined && parsed.ideLeaseRound !== null && parsed.ideLeaseRound !== 'null' ? String(parsed.ideLeaseRound) : null,
  }
}

function decodeRuntimeStatus(json: string): any {
  return JSON.parse(json)
}

function sent(transport: FakeTransport): DecodedSentEnvelope[] {
  return transport.sent.map((env) => {
    const typeName = MessageType[env.messageType] ?? 'UNKNOWN'
    let payload: any = {}
    if (env.payload.case === 'completed') {
      const res = env.payload.value.result
      payload = {
        result: {
          callId: res?.callId ?? '',
          error: res?.error ?? false,
          details: res?.detailsJson ? JSON.parse(res.detailsJson) : {},
          contents: (res?.contents ?? []).map((cnt) => {
            if (cnt.body.case === 'text') {
              return { type: 'text', text: cnt.body.value.text }
            }
            if (cnt.body.case === 'json') {
              return { type: 'json', json: JSON.parse(cnt.body.value.json) }
            }
            if (cnt.body.case === 'resource') {
              return {
                type: 'resource',
                uploadId: cnt.body.value.uploadId,
                mediaType: cnt.body.value.mediaType,
                name: cnt.body.value.name ?? null,
                size: Number(cnt.body.value.size),
                sha256: cnt.body.value.sha256,
              }
            }
            return {}
          }),
        },
      }
    } else if (env.payload.case === 'progress') {
      const res = env.payload.value.result
      payload = {
        result: {
          callId: res?.callId ?? '',
          error: res?.error ?? false,
          details: res?.detailsJson ? JSON.parse(res.detailsJson) : {},
          contents: (res?.contents ?? []).map((cnt) => {
            if (cnt.body.case === 'text') {
              return { type: 'text', text: cnt.body.value.text }
            }
            if (cnt.body.case === 'json') {
              return { type: 'json', json: JSON.parse(cnt.body.value.json) }
            }
            return {}
          }),
        },
      }
    } else if (env.payload.case === 'failed') {
      payload = { message: env.payload.value.message }
    } else if (env.payload.case === 'error') {
      payload = { message: env.payload.value.message }
    } else if (env.payload.case === 'started') {
      payload = (transport as any)._invStartedCounts?.get(env.invocationId) ? { replayed: true } : {}
      if (!(transport as any)._invStartedCounts) (transport as any)._invStartedCounts = new Map()
      const current = (transport as any)._invStartedCounts.get(env.invocationId) ?? 0
      if (current > 0) payload = { replayed: true }
      ;(transport as any)._invStartedCounts.set(env.invocationId, current + 1)
    } else if (env.payload.case === 'cancelled') {
      payload = {}
    } else if (env.payload.case === 'ready') {
      payload = {
        version: 1,
        runtimeStatus: {
          revision: env.payload.value.runtimeStatus?.revision !== undefined ? String(env.payload.value.runtimeStatus.revision) : '1',
          status: {
            daemonVersion: env.payload.value.runtimeStatus?.status?.daemonVersion ?? DAEMON_VERSION,
            update: env.payload.value.runtimeStatus?.status?.update ? {
              targetVersion: env.payload.value.runtimeStatus.status.update.targetVersion,
              downloadedBytes: Number(env.payload.value.runtimeStatus.status.update.downloadedBytes),
              totalBytes: env.payload.value.runtimeStatus.status.update.totalBytes !== undefined ? Number(env.payload.value.runtimeStatus.status.update.totalBytes) : null,
            } : null,
          },
        },
      }
    } else if (env.payload.case === 'runtimeStatus') {
      const rs = env.payload.value.runtimeStatus ?? {}
      payload = {
        revision: rs.revision !== undefined ? String(rs.revision) : '1',
        status: {
          daemonVersion: rs.status?.daemonVersion,
          update: rs.status?.update ? {
            targetVersion: rs.status.update.targetVersion,
            downloadedBytes: Number(rs.status.update.downloadedBytes),
            totalBytes: rs.status.update.totalBytes !== undefined ? Number(rs.status.update.totalBytes) : null,
          } : null,
        },
      }
    } else if (env.payload.case === 'heartbeat') {
      const rs = env.payload.value.runtimeStatus
      payload = {
        runtimeStatus: {
          revision: rs?.revision !== undefined ? String(rs.revision) : '1',
          status: {
            daemonVersion: rs?.status?.daemonVersion ?? DAEMON_VERSION,
            update: rs?.status?.update ? {
              targetVersion: rs.status.update.targetVersion,
              downloadedBytes: Number(rs.status.update.downloadedBytes),
              totalBytes: rs.status.update.totalBytes !== undefined ? Number(rs.status.update.totalBytes) : null,
            } : null,
          },
        },
        ideLeaseRound: env.payload.value.ideLeaseRound !== undefined ? String(env.payload.value.ideLeaseRound) : null,
      }
    } else if (env.payload.case === 'updateResult') {
      const phases = ['', 'ACCEPTED', 'PREPARED', 'FAILED', 'APPLIED', 'BUSY']
      payload = {
        operationId: env.payload.value.operationId,
        phase: phases[env.payload.value.phase] ?? '',
        message: env.payload.value.message,
      }
    } else if (env.payload.case === 'ideResult') {
      payload = {
        ok: env.payload.value.ok,
        code: env.payload.value.code,
        message: env.payload.value.message,
        views: env.payload.value.views ?? [],
      }
    }
    return {
      messageType: typeName,
      environmentId: env.environmentId ?? null,
      invocationId: env.invocationId ?? null,
      payload,
      raw: env,
    }
  })
}

function lastErrorMessage(transport: FakeTransport): string | undefined {
  const error = sent(transport).filter((envelope) => envelope.messageType === 'ERROR').at(-1)
  return (error?.payload as { message?: string } | undefined)?.message
}

function invokePayload(capabilityId = 'fs.read', timeoutMillis = 0, args: Record<string, unknown> = {}): Record<string, unknown> {
  return { capabilityId, arguments: args, timeoutMillis }
}

const ACTION_ENUM_MAP: Record<string, ProtoIdeAction> = {
  OPEN_VIEW: ProtoIdeAction.OPEN_VIEW,
  CHANGE_DIRECTORY: ProtoIdeAction.CHANGE_DIRECTORY,
  FOCUS_TERMINAL: ProtoIdeAction.FOCUS_TERMINAL,
  CLOSE_VIEW: ProtoIdeAction.CLOSE_VIEW,
  LIST_VIEWS: ProtoIdeAction.LIST_VIEWS,
  LIST_DIRECTORIES: ProtoIdeAction.LIST_DIRECTORIES,
  COLLAPSE_MOBILE: ProtoIdeAction.COLLAPSE_MOBILE,
}

function serverMessage(type: string, options: { environmentId?: string; invocationId?: string; payload?: any } = {}): Envelope {
  const envId = options.environmentId ?? ENV
  const invId = options.invocationId
  const rawPayload = options.payload ?? {}

  if (type === 'INVOKE') {
    return create(EnvelopeSchema, {
      messageType: MessageType.INVOKE,
      environmentId: envId,
      invocationId: invId ?? 'inv-1',
      payload: {
        case: 'invoke',
        value: create(InvokeSchema, {
          capabilityId: rawPayload.capabilityId ?? 'fs.read',
          capabilityVersion: rawPayload.capabilityVersion ?? '3',
          argumentsJson: JSON.stringify(rawPayload.arguments ?? {}),
          timeoutMillis: BigInt(rawPayload.timeoutMillis ?? 0),
        }),
      },
    })
  }
  if (type === 'CANCEL') {
    return create(EnvelopeSchema, {
      messageType: MessageType.CANCEL,
      environmentId: envId,
      invocationId: invId ?? '',
      payload: { case: 'cancel', value: create(CancelSchema, {}) },
    })
  }
  if (type === 'RECOVER') {
    return create(EnvelopeSchema, {
      messageType: MessageType.RECOVER,
      environmentId: envId,
      invocationId: invId ?? '',
      payload: { case: 'recover', value: create(RecoverSchema, {}) },
    })
  }
  if (type === 'SETTINGS') {
    return create(EnvelopeSchema, {
      messageType: MessageType.SETTINGS,
      environmentId: envId,
      payload: {
        case: 'settings',
        value: create(SettingsSchema, {
          resourceBudgets: create(ResourceBudgetsSchema, {
            maxResourceBytes: BigInt(rawPayload.resourceBudgets?.maxResourceBytes ?? 1024 * 1024),
            temporaryResourceTtlSeconds: BigInt(rawPayload.resourceBudgets?.temporaryResourceTtlSeconds ?? 60),
            temporaryResourceCleanupIntervalSeconds: BigInt(rawPayload.resourceBudgets?.temporaryResourceCleanupIntervalSeconds ?? 30),
          }),
        }),
      },
    })
  }
  if (type === 'ENVIRONMENT_CONFIG') {
    return create(EnvelopeSchema, {
      messageType: MessageType.ENVIRONMENT_CONFIG,
      environmentId: envId,
      payload: {
        case: 'environmentConfig',
        value: create(EnvironmentConfigSchema, {
          version: BigInt(rawPayload.version ?? 0),
          config: create(DaemonConfigurationSchema, {
            studioUrl: rawPayload.studioUrl ?? 'https://studio.example.com',
            ...(rawPayload.bashExecutable === undefined ? {} : { bashExecutable: rawPayload.bashExecutable }),
            ...(rawPayload.proxyUrl === undefined ? {} : { proxyUrl: rawPayload.proxyUrl }),
          }),
        }),
      },
    })
  }
  if (type === 'UPDATE') {
    return create(EnvelopeSchema, {
      messageType: MessageType.UPDATE,
      environmentId: envId,
      payload: {
        case: 'update',
        value: create(UpdateSchema, {
          operationId: rawPayload.operationId ?? '',
          targetVersion: rawPayload.targetVersion ?? '',
        }),
      },
    })
  }
  if (type === 'IDE_COMMAND') {
    return create(EnvelopeSchema, {
      messageType: MessageType.IDE_COMMAND,
      environmentId: envId,
      invocationId: invId ?? 'ide-1',
      payload: {
        case: 'ideCommand',
        value: create(IdeCommandSchema, {
          action: ACTION_ENUM_MAP[rawPayload.action] ?? ProtoIdeAction.LIST_VIEWS,
          viewId: rawPayload.viewId,
          documentId: rawPayload.documentId,
          eventNodeId: rawPayload.eventNodeId,
          connectionId: rawPayload.connectionId,
          claimId: rawPayload.claimId,
          bindingEpoch: rawPayload.bindingEpoch !== undefined ? BigInt(rawPayload.bindingEpoch) : undefined,
          mode: rawPayload.mode === 'open' ? ProtoIdeOpenMode.RESUME : (rawPayload.mode === 'create' ? ProtoIdeOpenMode.CREATE : undefined),
          directory: rawPayload.directory,
          discard: rawPayload.discard,
        }),
      },
    })
  }
  if (type === 'IDE_LEASE') {
    return create(EnvelopeSchema, {
      messageType: MessageType.IDE_LEASE,
      environmentId: envId,
      payload: {
        case: 'ideLease',
        value: create(IdeLeaseSchema, {
          round: BigInt(rawPayload.round ?? 1),
          bindings: (rawPayload.bindings ?? []).map((b: any) =>
            create(IdeLeaseBindingSchema, {
              viewId: b.viewId,
              documentId: b.documentId,
              bindingEpoch: BigInt(b.bindingEpoch ?? 1),
            }),
          ),
        }),
      },
    })
  }
  if (type === 'TEMPORARY_RESOURCE_POLICY') {
    return create(EnvelopeSchema, {
      messageType: MessageType.TEMPORARY_RESOURCE_POLICY,
      environmentId: envId,
      payload: {
        case: 'temporaryResourcePolicy',
        value: create(TemporaryResourcePolicySchema, {
          temporaryResourceTtlSeconds: BigInt(rawPayload.temporaryResourceTtlSeconds ?? 60),
          temporaryResourceCleanupIntervalSeconds: BigInt(rawPayload.temporaryResourceCleanupIntervalSeconds ?? 30),
        }),
      },
    })
  }
  if (type === 'RESOURCE_UPLOAD_TICKET') {
    return create(EnvelopeSchema, {
      messageType: MessageType.RESOURCE_UPLOAD_TICKET,
      environmentId: envId,
      invocationId: invId ?? '',
      payload: {
        case: 'resourceUploadTicket',
        value: create(ResourceUploadTicketSchema, {
          transferId: rawPayload.transferId ?? '',
          state: rawPayload.state ?? 1,
          uploadUrl: rawPayload.uploadUrl ?? '',
          uploadHeadersJson: rawPayload.uploadHeadersJson ?? '{}',
          resourceId: rawPayload.resourceId ?? '',
          errorMessage: rawPayload.errorMessage,
        }),
      },
    })
  }
  if (type === 'ERROR') {
    return create(EnvelopeSchema, {
      messageType: MessageType.ERROR,
      environmentId: envId,
      payload: {
        case: 'error',
        value: create(ErrorSchema, {
          message: rawPayload.message ?? (rawPayload.code ?? 'error'),
        }),
      },
    })
  }
  if (type === 'READY') {
    return create(EnvelopeSchema, {
      messageType: MessageType.READY,
      environmentId: envId,
      payload: { case: 'ready', value: create(ReadySchema, {}) },
    })
  }
  if (type === 'HEARTBEAT') {
    return create(EnvelopeSchema, {
      messageType: MessageType.HEARTBEAT,
      environmentId: envId,
      payload: { case: 'heartbeat', value: create(HeartbeatSchema, {}) },
    })
  }
  return create(EnvelopeSchema, {
    messageType: MessageType.UNSPECIFIED,
    environmentId: envId,
    invocationId: invId,
  })
}

function textResult(callId: string, text = 'ok'): CapabilityResult {
  return { callId, error: false, details: {}, contents: [{ type: 'text', text }] }
}

const openViewPayload = {
  action: 'OPEN_VIEW',
  viewId: ENV,
  documentId: '22222222-3333-4444-8555-666666666666',
  eventNodeId: '33333333-4444-4555-8666-777777777777',
  connectionId: '44444444-5555-4666-8777-888888888888',
  claimId: '55555555-6666-4777-8888-999999999999',
  mode: 'create',
}

const lease = {
  round: '1',
  bindings: [
    {
      viewId: ENV,
      documentId: '22222222-3333-4444-8555-666666666666',
      bindingEpoch: '1',
    },
  ],
}

function ideService(overrides: Partial<IdeService> = {}): IdeService {
  const base: IdeService = {
    async handle() {
      return { ok: true }
    },
    canStartUpdate: () => true,
    async stopForUpdate() {},
    getViewState: () => null,
    activeStreamCount: () => 0,
    async resolveView() {
      throw new Error('view resolution is not used by this test')
    },
    async restoreBackup() {
      return { restored: 0, skipped: 0 }
    },
    hasActiveViews: () => false,
    apply() {},
    async close() {},
  }
  return { ...base, ...overrides }
}

interface Setup {
  transport: FakeTransport
  runtime: Runtime
  config: DaemonConfig
}

function buildRuntime(options: {
  capabilities?: Record<string, CapabilityExecutor>
  processes?: ProcessSupervisor
  resources?: ResourceUploader
  update?: UpdatePort
  ide?: IdeService
  ideLease?: IdeLeaseHandler
  temporaryResources?: TemporaryResourceSweeper
  heartbeatIntervalMillis?: number
  maxConcurrentInvocations?: number
  ideCommandTimeoutMillis?: number
  monotonicNow?: () => number
  daemonVersion?: string
} = {}): Setup {
  const transport = new FakeTransport()
  const config = testConfig()
  const runtime = new Runtime({
    config,
    transport,
    capabilities: options.capabilities ?? { 'fs.read': async (call) => textResult(call.invocationId) },
    processes: options.processes ?? fakeProcessSupervisor,
    environment,
    resources: options.resources,
    update: options.update,
    ide: options.ide,
    ideLease: options.ideLease,
    temporaryResources: options.temporaryResources,
    heartbeatIntervalMillis: options.heartbeatIntervalMillis ?? 10_000,
    initialReconnectDelayMillis: 5,
    maxReconnectDelayMillis: 20,
    maxConcurrentInvocations: options.maxConcurrentInvocations,
    ideCommandTimeoutMillis: options.ideCommandTimeoutMillis,
    monotonicNow: options.monotonicNow,
    daemonVersion: options.daemonVersion,
  })
  return { transport, runtime, config }
}

async function handshake(setup: Setup): Promise<void> {
  setup.runtime.start()
  await waitFor(() => setup.transport.connection !== null)
  setup.transport.receive(
    serverMessage('SETTINGS', {
      environmentId: ENV,
      payload: {
        resourceBudgets: {
          maxResourceBytes: 1024 * 1024,
          temporaryResourceTtlSeconds: 60,
          temporaryResourceCleanupIntervalSeconds: 30,
        },
      },
    }),
  )
  setup.transport.receive(serverMessage('ENVIRONMENT_CONFIG', { environmentId: ENV, payload: { version: 0 } }))
  await waitFor(() => setup.runtime.getState() === 'READY')
  expect(setup.runtime.getState()).toBe('READY')
}

describe('runtime handshake', () => {


  it('sends HEARTBEAT only while READY', async () => {
    const setup = buildRuntime({ heartbeatIntervalMillis: 20 })
    await handshake(setup)
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'HEARTBEAT'))
    setup.runtime.close()
  })

  /** 没有环境标识的 SETTINGS 无法定向回 ERROR，只能关闭本次握手并重连重新取设置。 */
  it('retries the handshake when the initial SETTINGS declares no environment', async () => {
    const setup = buildRuntime()
    setup.runtime.start()
    await waitFor(() => setup.transport.connection !== null)
    setup.transport.receive(
      create(EnvelopeSchema, {
        messageType: MessageType.SETTINGS,
        payload: { case: 'settings', value: create(SettingsSchema, { noProxyHosts: '' }) },
      }),
    )
    await waitFor(() => setup.transport.connectCount >= 2)
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR')).toBe(false)
    setup.runtime.close()
  })

  it('fails the initial SETTINGS without noProxyHosts and retries the handshake', async () => {
    const setup = buildRuntime()
    setup.runtime.start()
    await waitFor(() => setup.transport.connection !== null)
    setup.transport.receive(
      create(EnvelopeSchema, {
        messageType: MessageType.SETTINGS,
        environmentId: ENV,
        payload: { case: 'settings', value: create(SettingsSchema, {}) },
      }),
    )
    await waitFor(() => lastErrorMessage(setup.transport)?.includes('settings.resourceBudgets is required') === true)
    await waitFor(() => setup.transport.connectCount >= 2)
    setup.runtime.close()
  })

  it('rejects SETTINGS that change the bound environment', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    setup.transport.receive(serverMessage('SETTINGS', { environmentId: OTHER_ENV }))
    await waitFor(() => lastErrorMessage(setup.transport)?.includes('SETTINGS environmentId does not match daemon') === true)
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

  // RECOVER 是同实例重连后的取回：运行中的调用回放 STARTED 并继续执行，绝不像 CANCEL 那样中止执行体。
  it('recovers a running invocation without aborting it and delivers its later result', async () => {
    let observedSignal: AbortSignal | null = null
    const release = Promise.withResolvers<void>()
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          observedSignal = context.signal
          await release.promise
          return textResult(call.invocationId)
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-recover', payload: invokePayload() }))
    await waitFor(() => observedSignal !== null)
    setup.transport.receive(serverMessage('RECOVER', { environmentId: ENV, invocationId: 'inv-recover', payload: {} }))
    await tick()
    const recovered = sent(setup.transport).filter((envelope) => envelope.invocationId === 'inv-recover')
    expect(recovered.filter((envelope) => envelope.messageType === 'STARTED').map((envelope) => envelope.payload)).toEqual([
      {},
      { replayed: true },
    ])
    expect(observedSignal?.aborted).toBe(false)
    release.resolve()
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'CANCELLED')).toBe(false)
    setup.runtime.close()
  })

  it('replays a terminal invocation with its exit confirmation on RECOVER and ignores an unknown one', async () => {
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          context.confirmExecutionExit()
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('RECOVER', { environmentId: ENV, invocationId: 'missing', payload: {} }))
      await tick()
      expect(sent(setup.transport).some((envelope) => envelope.invocationId === 'missing' && envelope.messageType === 'RECOVER_NOT_FOUND')).toBe(true)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-recovered', payload: invokePayload() }))
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED'))
      setup.transport.receive(serverMessage('RECOVER', { environmentId: ENV, invocationId: 'inv-recovered', payload: {} }))
      await waitFor(() => sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED').length === 2)
      const replayed = sent(setup.transport).filter((envelope) => envelope.invocationId === 'inv-recovered')
      expect(replayed.filter((envelope) => envelope.messageType === 'COMPLETED')).toHaveLength(2)
      expect(replayed.filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toHaveLength(2)
    } finally {
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  it('replays terminal state completed during disconnection on the same fixed environment after RECOVER', async () => {
    const completeGate = Promise.withResolvers<void>()
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          await completeGate.promise
          context.confirmExecutionExit()
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(
        serverMessage('INVOKE', {
          environmentId: ENV,
          invocationId: 'inv-disconnect-recover',
          payload: invokePayload(),
        }),
      )
      await waitFor(() =>
        sent(setup.transport).some(
          (envelope) =>
            envelope.invocationId === 'inv-disconnect-recover' &&
            envelope.messageType === 'STARTED',
        ),
      )

      // 1. 断开连接：断开期间 boundEnvironmentId 清空为 null
      setup.transport.disconnect()
      await waitFor(() => setup.runtime.getState() === 'DISCONNECTED')

      // 2. 在断开期间完成调用
      completeGate.resolve()
      await tick(10)

      // 确保断开期间没有发带空/非法 environmentId 的 COMPLETED 报文
      expect(
        sent(setup.transport).some(
          (envelope) =>
            envelope.invocationId === 'inv-disconnect-recover' &&
            envelope.messageType === 'COMPLETED',
        ),
      ).toBe(false)

      // 3. 重连握手：固定 UUID 不随连接改变。
      await waitFor(() => setup.transport.connection !== null && setup.transport.connection.open)
      setup.transport.receive(
        serverMessage('SETTINGS', {
          environmentId: ENV,
          payload: {
            resourceBudgets: {
              maxResourceBytes: 1024 * 1024,
              temporaryResourceTtlSeconds: 60,
              temporaryResourceCleanupIntervalSeconds: 30,
            },
          },
        }),
      )
      setup.transport.receive(serverMessage('ENVIRONMENT_CONFIG', { environmentId: ENV, payload: { version: 0 } }))
      await waitFor(() => setup.runtime.getState() === 'READY')

      // 4. 发送 RECOVER 取回 invocation
      setup.transport.receive(
        serverMessage('RECOVER', {
          environmentId: ENV,
          invocationId: 'inv-disconnect-recover',
          payload: {},
        }),
      )
      await waitFor(() =>
        sent(setup.transport).some(
          (envelope) =>
            envelope.invocationId === 'inv-disconnect-recover' &&
            envelope.messageType === 'COMPLETED',
        ),
      )

      const completedEnvelope = sent(setup.transport).find(
        (envelope) =>
          envelope.invocationId === 'inv-disconnect-recover' &&
          envelope.messageType === 'COMPLETED',
      )
      expect(completedEnvelope).toBeDefined()
      // 固定卡片身份：重连后仍使用同一 environmentId。
      expect(completedEnvelope?.environmentId).toBe(ENV)

      // 确保没有因 validation 失败而产生 ERROR 报文
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR')).toBe(false)
    } finally {
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  // 验证超时机制：Runtime在timer到期仅发abort信号，cleanup期间维持RUNNING不发终态，release后才完成终态。
  it('times out a long-running invocation with cooperative cleanup and deferred terminal', async () => {
    let observedSignal: AbortSignal | null = null
    let execCount = 0
    const cleanupGate = Promise.withResolvers<void>()
    const startedGate = Promise.withResolvers<void>()

    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          execCount++
          observedSignal = context.signal
          startedGate.resolve()
          await new Promise<void>((resolve) =>
            context.signal.addEventListener('abort', () => resolve(), { once: true }),
          )
          await cleanupGate.promise
          throw new Error('timed out cleanup failure')
        },
      },
    })
    try {
      await handshake(setup)
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
      setup.transport.receive(
        serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload('fs.read', 20) }),
      )
      await startedGate.promise
      await vi.advanceTimersByTimeAsync(20)
      vi.useRealTimers()

      expect(observedSignal?.aborted).toBe(true)
      expect(observedSignal?.reason).toBe('TIMED_OUT')

      // 在 cleanupGate 释放之前：journal仍处于RUNNING且无terminal报文
      expect(setup.runtime.getJournal().find('inv-1')?.state).toBe('RUNNING')
      expect(sent(setup.transport).some((env) => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(env.messageType))).toBe(false)

      // 发送重复的 INVOKE 不会重复触发执行器，只会重放 STARTED
      setup.transport.receive(
        serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload('fs.read', 20) }),
      )
      await tick()
      expect(execCount).toBe(1)
      const replayedStarted = sent(setup.transport).filter((env) => env.messageType === 'STARTED')
      expect(replayedStarted.length).toBe(2)

      // 释放 cleanupGate，执行器抛错退出
      cleanupGate.resolve()

      await waitFor(() => sent(setup.transport).some((env) => env.messageType === 'FAILED'))
      const failedEnvelopes = sent(setup.transport).filter((env) => env.messageType === 'FAILED')
      expect(failedEnvelopes.length).toBe(1)
      expect(setup.runtime.getJournal().find('inv-1')?.state).toBe('FAILED')
      const failedPayload = failedEnvelopes[0]?.payload as { message: string }
      expect(String(failedPayload.message)).toContain('timed out after 20ms')
    } finally {
      cleanupGate.resolve()
      vi.useRealTimers()
      setup.runtime.close()
    }
  })

  // 验证可信成功结果在超时或取消竞态时不被覆盖，文件真实变更保留且发送COMPLETED终态。
  it.each([
    {
      scenario: 'timeout',
      timeoutMillis: 30,
      raceAction: async () => {
        await vi.advanceTimersByTimeAsync(30)
      },
      expectedReason: 'TIMED_OUT',
    },
    {
      scenario: 'cancel',
      timeoutMillis: 10_000,
      raceAction: async (setup: Setup, invocationId: string) => {
        setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId }))
      },
      expectedReason: 'CANCELLED',
    },
    {
      scenario: 'timeout-then-cancel',
      timeoutMillis: 30,
      raceAction: async (setup: Setup, invocationId: string) => {
        await vi.advanceTimersByTimeAsync(30)
        setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId }))
      },
      expectedReason: 'TIMED_OUT',
    },
    {
      scenario: 'cancel-then-timeout',
      timeoutMillis: 30,
      raceAction: async (setup: Setup, invocationId: string) => {
        setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId }))
        await vi.advanceTimersByTimeAsync(30)
      },
      expectedReason: 'CANCELLED',
    },
  ])(
    'preserves credible success result when $scenario races after side effect commit',
    async ({ scenario, timeoutMillis, raceAction, expectedReason }) => {
      const dir = await realpath(await mkdtemp(join(tmpdir(), 'kk-runtime-edit-')))
      const filePath = join(dir, 'test.txt')
      await writeFile(filePath, 'hello world\n', 'utf8')

      const committedGate = Promise.withResolvers<void>()
      const holdGate = Promise.withResolvers<void>()
      let observedSignal: AbortSignal | null = null

      const realEdit = fileCapabilities['fs.edit']!
      const wrappedEdit: CapabilityExecutor = async (call, context) => {
        observedSignal = context.signal
        const realResult = await realEdit(call, context)
        committedGate.resolve()
        await holdGate.promise
        return realResult
      }

      const setup = buildRuntime({
        capabilities: {
          'fs.edit': wrappedEdit,
        },
      })
      const invocationId = `inv-edit-${scenario}`

      try {
        await handshake(setup)
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })

        const editArgs = {
          path: filePath,
          old_string: 'hello world',
          new_string: 'hello kk-studio',
        }
        const payload = JSON.parse(
          encodeInvoke({
            capabilityId: 'fs.edit',
            arguments: editArgs,
            timeoutMillis,
          }),
        ) as Record<string, unknown>

        setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId, payload }))

        // 等待真实 edit 落盘 commit
        await committedGate.promise

        // 此时已 commit，触发超时或取消竞态
        await raceAction(setup, invocationId)

        expect(observedSignal?.aborted).toBe(true)
        expect(observedSignal?.reason).toBe(expectedReason)

        vi.useRealTimers()
        holdGate.resolve()

        // 验证：收到的终态是 COMPLETED 报文，绝不被 TIMED_OUT 或 CANCELLED 覆盖
        await waitFor(() =>
          sent(setup.transport).some((env) => env.invocationId === invocationId && env.messageType === 'COMPLETED'),
        )
        const completed = sent(setup.transport).find(
          (env) => env.invocationId === invocationId && env.messageType === 'COMPLETED',
        )
        expect(completed).toBeDefined()
        expect(
          sent(setup.transport).some((env) => env.invocationId === invocationId && ['FAILED', 'CANCELLED'].includes(env.messageType)),
        ).toBe(false)

        const body = completed?.payload as {
          result: {
            error: boolean
            details: { editReview?: { diff?: string; path?: string } }
          }
        }
        const resultPayload = body.result
        expect(resultPayload.error).toBe(false)
        expect(resultPayload.details.editReview?.path).toBe(filePath)
        expect(resultPayload.details.editReview?.diff).toContain('+ 1|hello kk-studio')

        const updatedContent = await readFile(filePath, 'utf8')
        expect(updatedContent).toBe('hello kk-studio\n')
      } finally {
        holdGate.resolve()
        committedGate.resolve()
        vi.useRealTimers()
        setup.runtime.close()
        await rm(dir, { recursive: true, force: true })
      }
    },
  )

  // 验证Runtime与process真实集成：捕获进度屏障后推进超时，terminate清理期间维持RUNNING，收敛后交付包含输出的TIMED_OUT终态。
  it('integrates Runtime and process capability with truthful output preservation, deferred terminal and final convergence', async () => {
    const dir = await realpath(await mkdtemp(join(tmpdir(), 'kk-runtime-proc-')))

    const cleanupGate = Promise.withResolvers<void>()
    const startedGate = Promise.withResolvers<void>()
    const progressGate = Promise.withResolvers<void>()
    const exitGate = Promise.withResolvers<ProcessExit>()

    const stdoutStream = new PassThrough()
    const stderrStream = new PassThrough()

    let terminateStarted = false
    let terminated = false

    const managedProcess: ManagedProcess = {
      pid: 9999,
      stdin: new PassThrough(),
      stdout: stdoutStream,
      stderr: stderrStream,
      exit: exitGate.promise,
      terminate: async () => {
        terminateStarted = true
        await cleanupGate.promise
        terminated = true
        stdoutStream.end()
        stderrStream.end()
        exitGate.resolve({ exitCode: null, signal: 'SIGTERM' })
      },
    }

    const supervisor: ProcessSupervisor = {
      start: async () => {
        startedGate.resolve()
        return managedProcess
      },
      close: async () => {},
    }

    const realExec = processCapabilities['process.exec']!
    const wrappedExec: CapabilityExecutor = async (call, context) => {
      const wrappedContext = {
        ...context,
        progress: async (result: CapabilityResult) => {
          await context.progress(result)
          progressGate.resolve()
        },
      }
      return realExec(call, wrappedContext)
    }

    const setup = buildRuntime({
      capabilities: {
        'process.exec': wrappedExec,
      },
      processes: supervisor,
    })

    try {
      await handshake(setup)

      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })

      const invoke = JSON.parse(
        encodeInvoke({
          capabilityId: 'process.exec',
          arguments: { command: 'sleep 100', workdir: dir },
          timeoutMillis: 80,
        }),
      ) as Record<string, unknown>

      setup.transport.receive(
        serverMessage('INVOKE', { environmentId: ENV, invocationId: 'proc-inv-1', payload: invoke }),
      )

      // 等待 supervisor.start 事件
      await startedGate.promise

      // 写入足量数据并等待 progress wrapper 屏障
      stdoutStream.write('initial output line before timeout\n' + 'x'.repeat(70_000) + '\n')
      await progressGate.promise

      // 屏障之后再推进显式预算 80ms 超时
      await vi.advanceTimersByTimeAsync(80)

      vi.useRealTimers()

      // 等待 terminate 被触发
      await waitFor(() => terminateStarted)

      // 在 cleanupGate 释放前：无 terminal 报文，journal 仍为 RUNNING
      expect(
        sent(setup.transport).some(
          (env) => env.invocationId === 'proc-inv-1' && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(env.messageType),
        ),
      ).toBe(false)
      expect(setup.runtime.getJournal().find('proc-inv-1')?.state).toBe('RUNNING')

      // 释放清理门禁
      cleanupGate.resolve()

      // 验证终态 COMPLETED
      await waitFor(() =>
        sent(setup.transport).some((env) => env.invocationId === 'proc-inv-1' && env.messageType === 'COMPLETED'),
      )
      const terminalCompleted = sent(setup.transport).find(
        (env) => env.invocationId === 'proc-inv-1' && env.messageType === 'COMPLETED',
      )
      expect(terminalCompleted).toBeDefined()

      const body = terminalCompleted?.payload as {
        result: {
          error: boolean
          details: { process?: { outcome?: string } }
          contents: Array<{ type: string; text?: string }>
        }
      }
      const resultPayload = body.result
      expect(resultPayload.error).toBe(true)
      expect(resultPayload.details.process?.outcome).toBe('TIMED_OUT')
      const combinedText = resultPayload.contents.map((c) => c.text ?? '').join('\n')
      expect(combinedText).toContain('initial output line before timeout')
      expect(combinedText).toContain('timed out after 80 ms')

      expect(terminated).toBe(true)
      expect(setup.runtime.getJournal().find('proc-inv-1')?.state).toBe('COMPLETED')
    } finally {
      cleanupGate.resolve()
      startedGate.resolve()
      progressGate.resolve()
      exitGate.resolve({ exitCode: null, signal: 'SIGTERM' })
      vi.useRealTimers()
      setup.runtime.close()
      await rm(dir, { recursive: true, force: true })
    }
  })

  it('fails unknown capabilities', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload('nope.nope') }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'FAILED'))
    expect(setup.runtime.getJournal().find('inv-1')?.state).toBe('FAILED')
    // 参数/能力确定未进入执行器：可以直接确认未执行，且每个调用至多一次。
    expect(
      sent(setup.transport)
        .filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')
        .map((envelope) => envelope.invocationId),
    ).toEqual(['inv-1'])
    setup.runtime.close()
  })

  it('forwards progress frames', async () => {
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          await context.progress({ callId: call.invocationId, error: false, details: {}, contents: [{ type: 'text', text: 'partial' }] })
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'PROGRESS'))
      expect(sent(setup.transport).find((envelope) => envelope.messageType === 'PROGRESS')?.payload).toEqual({
        result: { callId: 'inv-1', error: false, details: {}, contents: [{ type: 'text', text: 'partial' }] },
      })
    } finally {
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  it('requests cancellation when a progress result cannot be encoded', async () => {
    // 编码失败立即封存 FAILED，但执行体仍被真实 gate 持有；停止意图必须已送达，退出确认只等 body 真正离开。
    const gate = Promise.withResolvers<void>()
    let abortReason: unknown
    let bodyHeld = false
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          context.signal.addEventListener('abort', () => {
            abortReason = context.signal.reason
          }, { once: true })
          await context.progress({ callId: call.invocationId, error: false, details: {}, contents: [{ type: 'binary', bytes: new Uint8Array([1]), mediaType: 'text/plain' }] })
          bodyHeld = true
          await gate.promise
          bodyHeld = false
          context.confirmExecutionExit()
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-binary-progress', payload: invokePayload() }))
      await waitFor(() => bodyHeld)
      expect(bodyHeld).toBe(true)
      const failed = sent(setup.transport).filter((envelope) => envelope.messageType === 'FAILED')
      expect(failed).toHaveLength(1)
      expect(failed[0]?.payload).toEqual({
        message: 'cannot encode partial capability result: progress does not allow binary content',
      })
      // 失败封存本身不确认退出：body 仍在 gate 内，不能有 ACK。
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toBe(false)
      // 原实现缺少这一步，执行体会一直不知道自己应当停止。
      expect(abortReason).toBe('CANCELLED')
      expect(setup.runtime.getJournal().find('inv-binary-progress')?.state).toBe('FAILED')
      gate.resolve()
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED'))
      expect(bodyHeld).toBe(false)
      expect(sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toHaveLength(1)
      expect(sent(setup.transport).filter((envelope) => envelope.messageType === 'FAILED')).toHaveLength(1)
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED')).toBe(false)
      expect(setup.runtime.getJournal().find('inv-binary-progress')?.state).toBe('FAILED')
    } finally {
      gate.resolve()
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
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

  it('fails invocation when binary content is returned but uploader is null, and encodes progress with json and resource', async () => {
    let progressSent = false
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          await context.progress({
            callId: call.invocationId,
            error: false,
            details: {},
            contents: [
              { type: 'text', text: 'step 1' },
              { type: 'json', json: { status: 'running' } },
            ],
          })
          progressSent = true
          return {
            callId: call.invocationId,
            error: false,
            details: {},
            contents: [{ type: 'binary', bytes: new Uint8Array([1, 2, 3]), mediaType: 'application/octet-stream' }],
          }
        },
      },
      resources: null as any,
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-prog', payload: invokePayload() }))

    await waitFor(() => progressSent)
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'PROGRESS'))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'FAILED'))

    const failedEnv = sent(setup.transport).find((envelope) => envelope.messageType === 'FAILED')
    expect((failedEnv?.payload as { message: string }).message).toContain('resource upload is not configured')
    setup.runtime.close()
  })
})

describe('runtime update handling', () => {
  const operationId = '33333333-4444-4555-8666-777777777777'
  const command = {
    operationId,
    targetVersion: UPDATE_TARGET_VERSION,
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

  it('releases the accepted handoff permit after the receipt and retries it on replay and reconnect', async () => {
    const stderr = vi.spyOn(process.stderr, 'write').mockReturnValue(true)
    const releases: number[] = []
    let attempts = 0
    const update: UpdatePort = {
      async handleUpdate() {
        return {
          phase: 'PREPARED',
          async releasePermit(): Promise<void> {
            attempts++
            if (attempts === 1) {
              // 第一次释放失败：必须被记录为可诊断的失败，并由重放与重连重发自然重试。
              throw new Error('injected permit write failure')
            }
            releases.push(attempts)
          },
        }
      },
    }
    const setup = buildRuntime({ update })
    await handshake(setup)
    const prepared = (): boolean =>
      sent(setup.transport).some(
        (envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'PREPARED',
      )
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(prepared)
    await waitFor(() => attempts === 1)

    // 同一 operation 的重放：既不第二次准备，也重试许可释放。
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(() => releases.length === 1)

    // 重连重发同样重试许可释放。
    setup.transport.disconnect()
    await waitFor(() => setup.transport.connectCount >= 2)
    setup.transport.receive(serverMessage('SETTINGS', { environmentId: ENV, payload: {} }))
    setup.transport.receive(serverMessage('ENVIRONMENT_CONFIG', { environmentId: ENV, payload: { version: 0 } }))
    await waitFor(() => setup.runtime.getState() === 'READY')
    await waitFor(() => releases.length === 2)

    expect(attempts).toBe(3)
    // 许可释放失败不是停机失败：只留下安全诊断，绝不把一次写入失败升级为 Daemon 退出失败。
    expect(setup.runtime.getShutdownFailures()).toEqual([])
    expect(stderr.mock.calls.map((call) => String(call[0])).join('')).toContain(
      'daemon update permit release failed: Error',
    )
    stderr.mockRestore()
    setup.runtime.close()
  })

  it('reports a post-handoff installer failure and releases update admission', async () => {
    // A failed download before service stop must not leave the daemon permanently busy.
    let reportFailure: ((message: string) => void) | undefined
    let stopped = false
    const update: UpdatePort = {
      async handleUpdate() {
        return {
          phase: 'PREPARED',
          watchFailure(report) {
            reportFailure = report
            return () => { stopped = true }
          },
        }
      },
    }
    const setup = buildRuntime({ update })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(() => reportFailure !== undefined)
    reportFailure!('official installer failed; check local install.log')
    await waitFor(() => sent(setup.transport).some(envelope =>
      envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'FAILED'))
    expect(stopped).toBe(true)
    expect(sent(setup.transport).filter(envelope => envelope.messageType === 'RUNTIME_STATUS').at(-1)?.payload)
      .toMatchObject({ status: { update: null } })
    setup.runtime.close()
  })

  it('reports every independent shutdown release failure and still attempts the remaining releases', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    const stderr = vi.spyOn(process.stderr, 'write').mockReturnValue(true)
    const connection = setup.transport.connection
    expect(connection).not.toBeNull()
    connection!.close = (): void => {
      throw new Error('injected connection close failure')
    }
    setup.transport.close = (): void => {
      throw new Error('injected transport close failure')
    }

    setup.runtime.close()
    await setup.runtime.awaitCompletion()

    expect(setup.runtime.getShutdownFailures()).toHaveLength(2)
    const diagnostics = stderr.mock.calls.map((call) => String(call[0])).join('')
    expect(diagnostics).toContain('daemon connection close failed: Error')
    expect(diagnostics).toContain('daemon transport close failed: Error')
    stderr.mockRestore()
  })

  it('keeps independent releases and the completion latch when the diagnostic output itself fails', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    const stderr = vi.spyOn(process.stderr, 'write').mockImplementation(() => {
      throw new Error('injected diagnostic write failure')
    })
    const connection = setup.transport.connection
    expect(connection).not.toBeNull()
    connection!.close = (): void => {
      throw new Error('injected connection close failure')
    }

    setup.runtime.close()
    stderr.mockRestore()

    // 诊断写不出来也必须落定终止闩，绝不挂起 awaitCompletion，也绝不阻断后续独立释放。
    await setup.runtime.awaitCompletion()

    // connection 的失败与「写诊断失败」都可见，而之后的 transport 释放照常独立执行。
    expect(setup.runtime.getShutdownFailures()).toHaveLength(2)
    expect(setup.transport.closed).toBe(true)
  })

  it('never leaks a writable error name or a non-error value into the shutdown diagnostic', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    const stderr = vi.spyOn(process.stderr, 'write').mockReturnValue(true)
    const connection = setup.transport.connection
    expect(connection).not.toBeNull()
    const named = new Error('boom')
    named.name = 's3cr3t-token=/home/dev/.ssh/id_rsa'
    connection!.close = (): void => {
      throw named
    }
    setup.transport.close = (): void => {
      throw 's3cr3t-thrown-string'
    }

    setup.runtime.close()
    await setup.runtime.awaitCompletion()

    const diagnostics = stderr.mock.calls.map((call) => String(call[0])).join('')
    expect(diagnostics).toContain('daemon connection close failed: Error')
    expect(diagnostics).toContain('daemon transport close failed: UnknownError')
    expect(diagnostics).not.toContain('s3cr3t')
    expect(diagnostics).not.toContain('id_rsa')
    expect(diagnostics).not.toContain('boom')
    expect(setup.runtime.getShutdownFailures()).toEqual([named, 's3cr3t-thrown-string'])
    stderr.mockRestore()
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

  it('rejects a different target while one is in progress and reports the active target', async () => {
    const gate = new Promise<void>(() => {})
    let handled = 0
    const update: UpdatePort = {
      async handleUpdate() {
        handled++
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
        payload: {
          ...command,
          operationId: '44444444-4444-4555-8666-777777777777',
          targetVersion: '9.9.10',
        },
      }),
    )
    await tick()
    const busy = sent(setup.transport).find(
      (envelope) =>
        envelope.messageType === 'UPDATE_RESULT' &&
        (envelope.payload as { operationId: string }).operationId === '44444444-4444-4555-8666-777777777777',
    )
    expect((busy?.payload as { phase: string }).phase).toBe('FAILED')
    expect((busy?.payload as { message: string }).message).toContain(command.targetVersion)
    expect(handled).toBe(1)
    setup.runtime.close()
  })

  it('returns the current snapshot for a same-target new command and rejects the same id with changed arguments', async () => {
    const gate = new Promise<void>(() => {})
    let handled = 0
    const update: UpdatePort = {
      async handleUpdate(_command, _config, _signal, reportProgress) {
        handled++
        reportProgress!({ downloadedBytes: 12, totalBytes: 100 })
        reportProgress!({ downloadedBytes: 5, totalBytes: 100 })
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
    expect(handled).toBe(1)
    const status = sent(setup.transport)
      .filter((envelope) => envelope.messageType === 'RUNTIME_STATUS')
      .at(-1)
    expect((status?.payload as { status: { update: unknown } }).status.update).toMatchObject({
      targetVersion: command.targetVersion,
      downloadedBytes: 12,
      totalBytes: 100,
    })

    setup.transport.receive(
      serverMessage('UPDATE', { environmentId: ENV, payload: { ...command, targetVersion: '9.9.10' } }),
    )
    await tick()
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR')).toBe(true)
    expect(handled).toBe(1)
    setup.runtime.close()
  })

  it('returns the current snapshot for an already installed target without invoking the updater', async () => {
    let handled = 0
    const update: UpdatePort = {
      async handleUpdate() {
        handled++
        return { phase: 'PREPARED' }
      },
    }
    const setup = buildRuntime({ update })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('UPDATE', {
        environmentId: ENV,
        payload: {
          ...command,
          targetVersion: DAEMON_VERSION,
        },
      }),
    )
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'RUNTIME_STATUS'))
    expect(handled).toBe(0)
    expect(
      sent(setup.transport).some(
        (envelope) =>
          envelope.messageType === 'UPDATE_RESULT' &&
          (envelope.payload as { operationId?: string }).operationId === command.operationId,
      ),
    ).toBe(false)
    const status = sent(setup.transport)
      .filter((envelope) => envelope.messageType === 'RUNTIME_STATUS')
      .at(-1)
    expect(decodeRuntimeStatus(JSON.stringify(status?.payload))).toEqual(setup.runtime.getRuntimeStatus())
    expect(setup.runtime.getRuntimeStatus().status.update).toBeNull()
    setup.runtime.close()
  })

  it('clears the update on explicit failure and ignores late progress callbacks', async () => {
    const gate = Promise.withResolvers<void>()
    let updateAttempts = 0
    let reportProgress: UpdateProgressReporter | null = null
    const update: UpdatePort = {
      async handleUpdate(_command, _config, _signal, reporter) {
        updateAttempts++
        reportProgress = reporter ?? null
        reporter!({ downloadedBytes: 12, totalBytes: 100 })
        await gate.promise
        throw new Error('download failed')
      },
    }
    const setup = buildRuntime({ update, heartbeatIntervalMillis: 20 })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(() => reportProgress !== null)
    await waitFor(() => setup.runtime.getRuntimeStatus().status.update?.downloadedBytes === 12)
    const beforeFailure = setup.runtime.getRuntimeStatus()
    gate.resolve()
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'FAILED',
        ),
    )
    const afterFailure = setup.runtime.getRuntimeStatus()
    expect(afterFailure.status.update).toBeNull()
    expect(BigInt(afterFailure.revision)).toBeGreaterThan(BigInt(beforeFailure.revision))
    expect(
      sent(setup.transport).some(
        (envelope) =>
          envelope.messageType === 'RUNTIME_STATUS' &&
          decodeRuntimeStatus(JSON.stringify(envelope.payload)).status.update === null,
      ),
    ).toBe(true)

    const statusCount = sent(setup.transport).filter((envelope) => envelope.messageType === 'RUNTIME_STATUS').length
    reportProgress!({ downloadedBytes: 1, totalBytes: 100 })
    await tick()
    expect(sent(setup.transport).filter((envelope) => envelope.messageType === 'RUNTIME_STATUS')).toHaveLength(statusCount)
    expect(setup.runtime.getRuntimeStatus()).toEqual(afterFailure)
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'HEARTBEAT'))
    const heartbeat = sent(setup.transport)
      .filter((envelope) => envelope.messageType === 'HEARTBEAT')
      .at(-1)
    expect(decodeHeartbeat(JSON.stringify(heartbeat?.payload)).runtimeStatus).toEqual(afterFailure)

    // 明确失败后同一目标的新 operation 可以重试；上一轮 update Promise 不占住准入门闩。
    const retryOperationId = '44444444-4444-4555-8666-777777777777'
    setup.transport.receive(
      serverMessage('UPDATE', {
        environmentId: ENV,
        payload: { ...command, operationId: retryOperationId },
      }),
    )
    await waitFor(() => updateAttempts === 2)
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) =>
            envelope.messageType === 'UPDATE_RESULT' &&
            (envelope.payload as { operationId: string }).operationId === retryOperationId &&
            (envelope.payload as { phase: string }).phase === 'FAILED',
        ),
    )
    setup.runtime.close()
  })

  it('blocks new invocations and IDE creation while an update is being prepared and releases them on failure', async () => {
    const gate = Promise.withResolvers<void>()
    let updateAttempts = 0
    let capabilityCalls = 0
    let ideCalls = 0
    const update: UpdatePort = {
      async handleUpdate() {
        updateAttempts++
        await gate.promise
        throw new Error('download failed')
      },
    }
    const setup = buildRuntime({
      update,
      capabilities: {
        'fs.read': async (call) => {
          capabilityCalls++
          return textResult(call.invocationId)
        },
      },
      ide: ideService({
        async handle() {
          ideCalls++
          return { ok: true }
        },
      }),
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'ACCEPTED',
        ),
    )

    setup.transport.receive(
      serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-blocked', payload: invokePayload() }),
    )
    setup.transport.receive(
      serverMessage('IDE_COMMAND', {
        environmentId: ENV,
        invocationId: 'ide-blocked',
        payload: openViewPayload,
      }),
    )
    await waitFor(() =>
      sent(setup.transport).some(
        (envelope) =>
          envelope.messageType === 'UPDATE_RESULT' ||
          envelope.messageType === 'IDE_RESULT' ||
          envelope.messageType === 'FAILED',
      ),
    )
    await tick()
    const blockedInvocation = sent(setup.transport).find(
      (envelope) => envelope.messageType === 'FAILED' && envelope.invocationId === 'inv-blocked',
    )
    expect((blockedInvocation?.payload as { message: string }).message).toContain('update admission is held')
    expect(
      sent(setup.transport).some(
        (envelope) =>
          envelope.messageType === 'IDE_RESULT' &&
          envelope.invocationId === 'ide-blocked' &&
          (envelope.payload as { ok: boolean }).ok === false,
      ),
    ).toBe(true)
    expect(capabilityCalls).toBe(0)
    expect(ideCalls).toBe(0)

    gate.resolve()
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'FAILED',
        ),
    )
    setup.transport.receive(
      serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-after-failure', payload: invokePayload() }),
    )
    await waitFor(() =>
      sent(setup.transport).some(
        (envelope) => envelope.messageType === 'COMPLETED' && envelope.invocationId === 'inv-after-failure',
      ),
    )
    expect(capabilityCalls).toBe(1)
    expect(updateAttempts).toBe(1)
    setup.runtime.close()
  })

  it('keeps the admission gate held after PREPARED for new calls, views, and leases', async () => {
    let updateCalls = 0
    let ideCalls = 0
    const update: UpdatePort = {
      async handleUpdate() {
        updateCalls++
        return { phase: 'PREPARED' }
      },
    }
    const setup = buildRuntime({
      update,
      ide: ideService({
        async handle() {
          ideCalls++
          return { ok: true }
        },
      }),
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'PREPARED',
        ),
    )

    setup.transport.receive(
      serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-after-prepared', payload: invokePayload() }),
    )
    setup.transport.receive(
      serverMessage('IDE_COMMAND', {
        environmentId: ENV,
        invocationId: 'ide-after-prepared',
        payload: openViewPayload,
      }),
    )
    setup.transport.receive(
      serverMessage('IDE_LEASE', {
        environmentId: ENV,
        payload: {
          round: '1',
          bindings: [
            {
              viewId: ENV,
              documentId: '99999999-2222-4333-8444-555555555555',
              bindingEpoch: '1',
            },
          ],
        },
      }),
    )
    await waitFor(
      () =>
        sent(setup.transport).filter((envelope) => envelope.messageType === 'ERROR').length >= 1,
    )
    await tick()
    expect(
      sent(setup.transport).some(
        (envelope) =>
          envelope.messageType === 'FAILED' &&
          envelope.invocationId === 'inv-after-prepared' &&
          (envelope.payload as { message: string }).message.includes('update admission is held'),
      ),
    ).toBe(true)
    expect(
      sent(setup.transport).some(
        (envelope) =>
          envelope.messageType === 'IDE_RESULT' &&
          envelope.invocationId === 'ide-after-prepared' &&
          (envelope.payload as { message: string }).message.includes('update is in progress'),
      ),
    ).toBe(true)
    const errors = sent(setup.transport).filter((envelope) => envelope.messageType === 'ERROR')
    expect(errors.some((envelope) => (envelope.payload as { message: string }).message.includes('not allowed while environment update'))).toBe(
      true,
    )
    expect(setup.runtime.getRuntimeStatus().status.update?.targetVersion).toBe(command.targetVersion)
    expect(ideCalls).toBe(0)

    setup.transport.receive(
      serverMessage('UPDATE', {
        environmentId: ENV,
        payload: { ...command, operationId: '44444444-4444-4555-8666-777777777777' },
      }),
    )
    await tick()
    expect(updateCalls).toBe(1)
    expect(setup.runtime.getRuntimeStatus().status.update?.targetVersion).toBe(command.targetVersion)
    setup.runtime.close()
  })

  it('uses the IDE admission view to reject UPDATE before taking the gate', async () => {
    let canStart = false
    let updateCalls = 0
    const setup = buildRuntime({
      ide: ideService({
        async handle() {
          return { ok: true }
        },
        canStartUpdate: () => canStart,
      }),
      update: {
        async handleUpdate() {
          updateCalls++
          return { phase: 'PREPARED' }
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) =>
            envelope.messageType === 'UPDATE_RESULT' &&
            (envelope.payload as { phase: string }).phase === 'FAILED' &&
            (envelope.payload as { message: string }).message.includes('IDE views'),
        ),
    )
    expect(updateCalls).toBe(0)

    canStart = true
    setup.transport.receive(
      serverMessage('UPDATE', {
        environmentId: ENV,
        payload: { ...command, operationId: '44444444-4444-4555-8666-777777777777' },
      }),
    )
    await waitFor(() => updateCalls === 1)
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) =>
            envelope.messageType === 'UPDATE_RESULT' &&
            (envelope.payload as { operationId: string }).operationId === '44444444-4444-4555-8666-777777777777' &&
            (envelope.payload as { phase: string }).phase === 'PREPARED',
        ),
    )
    setup.runtime.close()
  })

  it('keeps EXECUTION_EXITED and recovery semantics while protected work blocks UPDATE admission', async () => {
    const release = Promise.withResolvers<void>()
    let capabilityCalls = 0
    let updateCalls = 0
    const setup = buildRuntime({
      update: {
        async handleUpdate() {
          updateCalls++
          return { phase: 'PREPARED' }
        },
      },
      capabilities: {
        'fs.read': async (call, context) => {
          capabilityCalls++
          context.confirmExecutionExit()
          await release.promise
          return textResult(call.invocationId)
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-recover', payload: invokePayload() }))
    await waitFor(() => capabilityCalls === 1)
    setup.transport.receive(serverMessage('RECOVER', { environmentId: ENV, invocationId: 'inv-recover', payload: {} }))
    await tick()
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) =>
            envelope.messageType === 'UPDATE_RESULT' &&
            (envelope.payload as { phase: string }).phase === 'FAILED' &&
            (envelope.payload as { message: string }).message.includes('protected execution'),
        ),
    )
    expect(updateCalls).toBe(0)

    release.resolve()
    await waitFor(() =>
      sent(setup.transport).some(
        (envelope) => envelope.messageType === 'EXECUTION_EXITED' && envelope.invocationId === 'inv-recover',
      ),
    )
    const types = sent(setup.transport)
      .filter((envelope) => envelope.invocationId === 'inv-recover')
      .map((envelope) => envelope.messageType)
    expect(types.indexOf('COMPLETED')).toBeGreaterThanOrEqual(0)
    expect(types.indexOf('COMPLETED')).toBeLessThan(types.indexOf('EXECUTION_EXITED'))
    expect(types.filter((messageType) => messageType === 'EXECUTION_EXITED')).toHaveLength(1)

    await tick()
    setup.transport.receive(
      serverMessage('UPDATE', {
        environmentId: ENV,
        payload: { ...command, operationId: '44444444-4444-4555-8666-777777777777' },
      }),
    )
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) =>
            envelope.messageType === 'UPDATE_RESULT' &&
            (envelope.payload as { operationId: string }).operationId === '44444444-4444-4555-8666-777777777777' &&
            (envelope.payload as { phase: string }).phase === 'PREPARED',
        ),
    )
    expect(updateCalls).toBe(1)
    setup.runtime.close()
  })

  it('delivers heartbeat and reconnect snapshots from the same cached progress state', async () => {
    const gate = Promise.withResolvers<void>()
    let reportProgress: UpdateProgressReporter | null = null
    const update: UpdatePort = {
      async handleUpdate(_command, _config, _signal, reporter) {
        reportProgress = reporter ?? null
        reporter!({ downloadedBytes: 20, totalBytes: 100 })
        reporter!({ downloadedBytes: 10, totalBytes: 100 })
        await gate.promise
        return { phase: 'PREPARED' }
      },
    }
    const setup = buildRuntime({ update, heartbeatIntervalMillis: 20 })
    await handshake(setup)
    const ready = sent(setup.transport).find((envelope) => envelope.messageType === 'READY')
    const initial = decodeCapabilities(ready?.payload)
    expect(initial.runtimeStatus).toEqual({
      revision: '1',
      status: { daemonVersion: DAEMON_VERSION, update: null },
    })

    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(() => reportProgress !== null)
    await waitFor(() => setup.runtime.getRuntimeStatus().status.update?.downloadedBytes === 20)
    const current = setup.runtime.getRuntimeStatus()
    reportProgress!({ downloadedBytes: 10, totalBytes: 100 })
    expect(setup.runtime.getRuntimeStatus()).toEqual(current)

    await waitFor(() =>
      sent(setup.transport).some(
        (envelope) =>
          envelope.messageType === 'HEARTBEAT' &&
          decodeHeartbeat(JSON.stringify(envelope.payload)).runtimeStatus.status.update?.downloadedBytes === 20,
      ),
    )
    const heartbeat = sent(setup.transport)
      .filter((envelope) => envelope.messageType === 'HEARTBEAT')
      .at(-1)
    expect(decodeHeartbeat(JSON.stringify(heartbeat?.payload))).toEqual({
      runtimeStatus: current,
      ideLeaseRound: null,
    })

    setup.transport.disconnect()
    await waitFor(() => setup.transport.connectCount >= 2)
    setup.transport.receive(serverMessage('SETTINGS', { environmentId: ENV, payload: {} }))
    setup.transport.receive(serverMessage('ENVIRONMENT_CONFIG', { environmentId: ENV, payload: { version: 0 } }))
    await waitFor(() => setup.runtime.getState() === 'READY')
    const reconnectReady = sent(setup.transport)
      .filter((envelope) => envelope.messageType === 'READY')
      .at(-1)
    const reconnected = decodeCapabilities(JSON.stringify(reconnectReady?.payload))
    expect(reconnected.runtimeStatus).toEqual(current)
    expect(setup.runtime.getUpdateProgress()).toEqual(current.status.update)
    gate.resolve()
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'PREPARED',
        ),
    )
    setup.runtime.close()
  })

  it('uses the updater progress callback shape and the pull progress hook', async () => {
    let pulled = 0
    const gate = Promise.withResolvers<void>()
    const update: UpdatePort = {
      async handleUpdate(
        _command,
        _config,
        _signal,
        reportProgress?: (progress: { downloadedBytes: number; totalBytes: number | null }) => void,
      ) {
        // 与 r500-updater 的实际第四参数一致：进度对象，而不是两个位置参数。
        reportProgress?.({ downloadedBytes: 33, totalBytes: 99 })
        await gate.promise
        return { phase: 'PREPARED' }
      },
      getProgress() {
        pulled++
        return { downloadedBytes: 33, totalBytes: 99 }
      },
    }
    const setup = buildRuntime({ update })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'ACCEPTED',
        ),
    )
    expect(setup.runtime.getUpdateProgress()).toEqual({
      targetVersion: command.targetVersion,
      downloadedBytes: 33,
      totalBytes: 99,
    })
    expect(pulled).toBeGreaterThan(0)
    gate.resolve()
    await tick()
    setup.runtime.close()
  })

  /** 更新器拉取进度失败只留下诊断，不改变已准入更新的状态快照。 */
  it('keeps the update snapshot when the progress pull fails', async () => {
    const setup = buildRuntime({
      update: {
        async handleUpdate() {
          return { phase: 'PREPARED' }
        },
        getProgress() {
          throw new Error('progress unavailable')
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'PREPARED',
        ),
    )
    expect(setup.runtime.getRuntimeStatus().status.update?.targetVersion).toBe(UPDATE_TARGET_VERSION)
    expect(setup.runtime.getShutdownFailures()).toEqual([])
    setup.runtime.close()
  })

  it('rejects UPDATE while an IDE command is in flight', async () => {
    let updateCalls = 0
    const setup = buildRuntime({
      ideCommandTimeoutMillis: 60_000,
      ide: ideService({
        async handle(_command, signal) {
          await new Promise<void>((resolve) => signal.addEventListener('abort', () => resolve(), { once: true }))
          return { ok: false, code: 'BUSY', message: 'aborted' }
        },
      }),
      update: {
        async handleUpdate() {
          updateCalls++
          return { phase: 'PREPARED' }
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: 'ide-busy', payload: { action: 'LIST_VIEWS' } }),
    )
    await tick()
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) =>
            envelope.messageType === 'UPDATE_RESULT' &&
            String((envelope.payload as { message?: string }).message ?? '').includes('IDE command is in progress'),
        ),
    )
    expect(updateCalls).toBe(0)
    setup.runtime.close()
  })
})

describe('runtime extension handlers', () => {
  const operationId = '33333333-4444-4555-8666-777777777777'
  const command = {
    operationId,
    targetVersion: UPDATE_TARGET_VERSION,
  }
  const binding = {
    generation: '1',
    ticket: 'A'.repeat(43),
    streamUrl: 'https://studio.example.com/data?generation=1',
  }
  const lease = {
    round: '1',
    bindings: [
      {
        viewId: ENV,
        documentId: '99999999-2222-4333-8444-555555555555',
        bindingEpoch: '1',
      },
    ],
  }







  it('reports IDE_LEASE explicitly when no handler is configured', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    setup.transport.receive(serverMessage('IDE_LEASE', { environmentId: ENV, payload: lease }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR'))
    expect(
      (sent(setup.transport).find((envelope) => envelope.messageType === 'ERROR')?.payload as { message: string }).message,
    ).toContain('IDE_LEASE handler is not configured')
    setup.runtime.close()
  })

  it('reports an asynchronous IDE_LEASE handler failure as an extension error', async () => {
    const setup = buildRuntime({
      ideLease: {
        hasActiveViews: () => true,
        async apply() {
          throw new Error('lease apply failed')
        },
        getViewState: () => null,
      },
    })
    await handshake(setup)
    const internals = setup.runtime as unknown as { session: { sendHeartbeat(): void } }
    internals.session.sendHeartbeat()

    setup.transport.receive(serverMessage('IDE_LEASE', { environmentId: ENV, payload: lease }))
    await waitFor(
      () => lastErrorMessage(setup.transport)?.includes('IDE_LEASE handler failed: lease apply failed') === true,
    )
    setup.runtime.close()
  })

  it('rejects IDE_LEASE before the session is READY', async () => {
    const setup = buildRuntime()
    setup.runtime.start()
    await waitFor(() => setup.transport.connection !== null)
    setup.transport.receive(serverMessage('IDE_LEASE', { environmentId: ENV, payload: lease }))
    await waitFor(
      () => lastErrorMessage(setup.transport)?.includes('IDE_LEASE requires a READY bound connection') === true,
    )
    setup.runtime.close()
  })

  it('issues one-use, expiring IDE lease rounds only while views are active', async () => {
    let activeViews = false
    let now = 0
    const applied: unknown[] = []
    const setup = buildRuntime({
      ideLease: {
        hasActiveViews: () => activeViews,
        apply(value, roundStartedAt) {
          applied.push({ value, roundStartedAt })
        },
        getViewState: () => null,
      },
      monotonicNow: () => now,
    })
    await handshake(setup)

    const heartbeat = (): ReturnType<typeof decodeHeartbeat> => {
      const internals = setup.runtime as unknown as { session: { sendHeartbeat(): void } }
      internals.session.sendHeartbeat()
      const latest = sent(setup.transport)
        .filter((envelope) => envelope.messageType === 'HEARTBEAT')
        .at(-1)
      return decodeHeartbeat(latest?.payload)
    }

    expect(heartbeat().ideLeaseRound).toBeNull()
    setup.transport.receive(serverMessage('IDE_LEASE', { environmentId: ENV, payload: lease }))
    await tick()
    expect(applied).toEqual([])

    activeViews = true
    expect(heartbeat().ideLeaseRound).toBe('1')
    setup.transport.receive(serverMessage('IDE_LEASE', { environmentId: ENV, payload: lease }))
    await waitFor(() => applied.length === 1)
    expect(applied[0]).toEqual({ value: lease, roundStartedAt: 0 })

    // 同一轮只接受一次证明。
    setup.transport.receive(serverMessage('IDE_LEASE', { environmentId: ENV, payload: lease }))
    await tick()
    expect(applied).toHaveLength(1)

    now = 15_000
    expect(heartbeat().ideLeaseRound).toBe('2')
    setup.transport.receive(serverMessage('IDE_LEASE', { environmentId: ENV, payload: { ...lease, round: '2' } }))
    await waitFor(() => applied.length === 2)
    // 使用证明使用轮次发出时的本机单调时间，而不是证明到达时间。
    expect(applied[1]).toEqual({ value: { ...lease, round: '2' }, roundStartedAt: 15_000 })

    now = 30_000
    expect(heartbeat().ideLeaseRound).toBe('3')
    now = 45_000
    setup.transport.receive(serverMessage('IDE_LEASE', { environmentId: ENV, payload: { ...lease, round: '3' } }))
    await tick()
    expect(applied).toHaveLength(2)
    expect(
      (sent(setup.transport).filter((envelope) => envelope.messageType === 'ERROR').at(-1)?.payload as {
        message: string
      }).message,
    ).toContain('current unexpired round')
    setup.runtime.close()
  })
})

describe('runtime IDE commands', () => {
  it('maps directory listing and mobile collapse commands and returns the listing proto', async () => {
    const commands: Array<Parameters<IdeService['handle']>[0]> = []
    const listing = {
      path: '/workspace', parentPath: '/', truncated: true,
      children: [{ name: 'project', path: '/workspace/project' }],
    }
    const setup = buildRuntime({
      ide: ideService({
        async handle(command) {
          commands.push(command)
          return command.action === 'LIST_DIRECTORIES' ? { ok: true, directoryListing: listing } : { ok: true }
        },
      }),
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('IDE_COMMAND', {
        invocationId: 'ide-directories', payload: { action: 'LIST_DIRECTORIES', directory: '/workspace' },
      }))
      await waitFor(() => setup.transport.sent.some((env) => env.invocationId === 'ide-directories'))
      const result = setup.transport.sent.find((env) => env.invocationId === 'ide-directories')
      expect(result?.payload.case).toBe('ideResult')
      if (result?.payload.case !== 'ideResult') throw new Error('missing IDE result')
      expect(result.payload.value.ok).toBe(true)
      expect(result.payload.value.directoryListing).toMatchObject(listing)
      setup.transport.receive(serverMessage('IDE_COMMAND', {
        invocationId: 'ide-collapse', payload: { action: 'COLLAPSE_MOBILE', viewId: ENV },
      }))
      await waitFor(() => setup.transport.sent.some((env) => env.invocationId === 'ide-collapse'))
      expect(commands).toHaveLength(2)
      expect(commands[0]).toMatchObject({ action: 'LIST_DIRECTORIES', directory: '/workspace' })
      expect(commands[1]).toMatchObject({ action: 'COLLAPSE_MOBILE', viewId: ENV })
      expect(sent(setup.transport).find((env) => env.invocationId === 'ide-collapse')?.payload.ok).toBe(true)
    } finally {
      setup.runtime.close()
      await setup.runtime.awaitCompletion()
    }
  })

  // 人工确认的普通关闭不受短默认超时限制；释放后仍需在原连接交付结果。
  it('keeps normal close pending past the default timeout and sends its eventual result', async () => {
    const gate = Promise.withResolvers<void>()
    const started = Promise.withResolvers<AbortSignal>()
    const setup = buildRuntime({
      ideCommandTimeoutMillis: 20,
      ide: ideService({
        async handle(command, signal) {
          expect(command).toMatchObject({ action: 'CLOSE_VIEW', discard: false })
          started.resolve(signal)
          await gate.promise
          return { ok: true, viewId: ENV }
        },
      }),
    })
    try {
      await handshake(setup)
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
      setup.transport.receive(serverMessage('IDE_COMMAND', {
        invocationId: 'ide-close-pending', payload: { action: 'CLOSE_VIEW', viewId: ENV, discard: false },
      }))
      const signal = await started.promise
      await vi.advanceTimersByTimeAsync(100)
      expect(signal.aborted).toBe(false)
      expect(setup.transport.sent.some((env) => env.invocationId === 'ide-close-pending')).toBe(false)
      gate.resolve()
      await vi.advanceTimersByTimeAsync(0)
      const result = setup.transport.sent.filter((env) => env.invocationId === 'ide-close-pending')
      expect(result).toHaveLength(1)
      expect(result[0]?.payload.case).toBe('ideResult')
      if (result[0]?.payload.case !== 'ideResult') throw new Error('missing IDE result')
      expect(result[0].payload.value).toMatchObject({ ok: true, viewId: ENV })
    } finally {
      gate.resolve()
      vi.useRealTimers()
      setup.runtime.close()
      await setup.runtime.awaitCompletion()
    }
  })

  // 即使 IDE 延迟返回成功，断线中止后的旧请求也不能向新连接发送结果。
  it('aborts a pending normal close on disconnect and never sends its stale success', async () => {
    const gate = Promise.withResolvers<void>()
    const started = Promise.withResolvers<AbortSignal>()
    const setup = buildRuntime({
      ideCommandTimeoutMillis: 20,
      ide: ideService({
        async handle(_command, signal) {
          started.resolve(signal)
          await gate.promise
          return { ok: true }
        },
      }),
    })
    try {
      await handshake(setup)
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
      setup.transport.receive(serverMessage('IDE_COMMAND', {
        invocationId: 'ide-close-disconnect', payload: { action: 'CLOSE_VIEW', viewId: ENV, discard: false },
      }))
      const signal = await started.promise
      await vi.advanceTimersByTimeAsync(100)
      expect(signal.aborted).toBe(false)
      const oldConnection = setup.transport.connection!
      setup.transport.disconnect()
      expect(signal.aborted).toBe(true)
      expect(signal.reason).toBe('CONTROL_DISCONNECTED')
      await vi.advanceTimersByTimeAsync(5)
      setup.transport.receive(serverMessage('SETTINGS', { environmentId: ENV }))
      setup.transport.receive(serverMessage('ENVIRONMENT_CONFIG', { environmentId: ENV, payload: { version: 0 } }))
      expect(setup.runtime.getState()).toBe('READY')
      gate.resolve()
      await vi.advanceTimersByTimeAsync(0)
      expect(oldConnection.sent.some((env) => env.invocationId === 'ide-close-disconnect')).toBe(false)
      expect(setup.transport.sent.some((env) => env.invocationId === 'ide-close-disconnect')).toBe(false)
    } finally {
      gate.resolve()
      vi.useRealTimers()
      setup.runtime.close()
      await setup.runtime.awaitCompletion()
    }
  })

  it('dispatches IDE_COMMAND and replies with IDE_RESULT', async () => {
    let handled = 0
    const setup = buildRuntime({
      ide: ideService({
        async handle() {
          handled++
          return { ok: true, views: [] }
        },
      }),
    })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: 'ide-1', payload: { action: 'LIST_VIEWS' } }),
    )
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'IDE_RESULT'))
    const result = sent(setup.transport).find((envelope) => envelope.messageType === 'IDE_RESULT')
    expect(result?.invocationId).toBe('ide-1')
    expect(result?.payload).toEqual({ ok: true, views: [] })
    expect(handled).toBe(1)
    setup.runtime.close()
  })

  it('maps all protocol fields including discard, bindingEpoch, directory to IdeCommand', async () => {
    let captured: any = null
    const setup = buildRuntime({
      ide: ideService({
        async handle(command) {
          captured = command
          return { ok: true }
        },
      }),
    })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('IDE_COMMAND', {
        environmentId: ENV,
        invocationId: 'ide-close-1',
        payload: {
          action: 'CLOSE_VIEW',
          viewId: '11111111-1111-1111-1111-111111111111',
          documentId: '22222222-2222-2222-2222-222222222222',
          bindingEpoch: 42,
          discard: true,
          directory: '/workspace/dir',
        },
      }),
    )
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'IDE_RESULT'))
    expect(captured).toMatchObject({
      action: 'CLOSE_VIEW',
      viewId: '11111111-1111-1111-1111-111111111111',
      documentId: '22222222-2222-2222-2222-222222222222',
      bindingEpoch: '42',
      discard: true,
      directory: '/workspace/dir',
    })
    setup.runtime.close()
  })

  it('dedups an in-flight IDE request and reports unavailable service', async () => {
    let handled = 0
    let release: (() => void) | null = null
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    const setup = buildRuntime({
      ide: ideService({
        async handle() {
          handled++
          await gate
          return { ok: true }
        },
      }),
    })
    await handshake(setup)
    const message = serverMessage('IDE_COMMAND', {
      environmentId: ENV,
      invocationId: 'ide-1',
      payload: { action: 'LIST_VIEWS' },
    })
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
      serverMessage('IDE_COMMAND', {
        environmentId: ENV,
        invocationId: 'ide-2',
        payload: {
          action: 'CLOSE_VIEW',
          viewId: ENV,
          documentId: '22222222-3333-4444-8555-666666666666',
          bindingEpoch: '1',
          discard: true,
        },
      }),
    )
    await waitFor(() => sent(withoutService.transport).some((envelope) => envelope.messageType === 'IDE_RESULT'))
    const result = sent(withoutService.transport).find((envelope) => envelope.messageType === 'IDE_RESULT')
    expect(result?.payload).toMatchObject({ ok: false, code: 'UNAVAILABLE' })
    withoutService.runtime.close()
  })

  it('aborts an IDE request that exceeds its deadline', async () => {
    let aborted = false
    const setup = buildRuntime({
      ideCommandTimeoutMillis: 20,
      ide: ideService({
        async handle(_command, signal) {
          await new Promise<void>((resolve) => signal.addEventListener('abort', () => resolve(), { once: true }))
          aborted = true
          throw new Error('aborted')
        },
      }),
    })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: 'ide-1', payload: { action: 'LIST_VIEWS' } }),
    )
    await waitFor(() => aborted)
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'IDE_RESULT'))
    const result = sent(setup.transport).find((envelope) => envelope.messageType === 'IDE_RESULT')
    expect(result?.payload).toMatchObject({ ok: false })
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
    setup.transport.receive(serverMessage('SETTINGS', { environmentId: ENV, payload: {} }))
    setup.transport.receive(serverMessage('ENVIRONMENT_CONFIG', { environmentId: ENV, payload: { version: 0 } }))
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

  /**
   * GOAWAY 是旧代际的断开：先关闭旧连接并清理其会话，再立即重连。
   * 即使关闭同步回调 onDisconnected、旧代际稍后又迟到断开，会话清理也恰好一次。
   */
  it('closes the old generation on GOAWAY, cleans up once and cancels its IDE work', async () => {
    let aborted = false
    let agentStarted = false
    const agentGate = Promise.withResolvers<void>()
    const started = Promise.withResolvers<AbortSignal>()
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call) => {
          agentStarted = true
          await agentGate.promise
          return textResult(call.invocationId)
        },
      },
      ideCommandTimeoutMillis: 60_000,
      ide: ideService({
        async handle(_command, signal) {
          started.resolve(signal)
          await new Promise<void>((resolve) => signal.addEventListener('abort', () => resolve(), { once: true }))
          aborted = true
          return { ok: true, views: [] }
        },
      }),
    })
    try {
      await handshake(setup)
      setup.transport.receive(
        serverMessage('INVOKE', {
          environmentId: ENV,
          invocationId: 'inv-goaway',
          payload: invokePayload(),
        }),
      )
      await waitFor(() => agentStarted)
      const oldConnection = setup.transport.connection!
      const oldListener = setup.transport.listener!
      setup.transport.receive(
        serverMessage('IDE_COMMAND', {
          environmentId: ENV,
          invocationId: 'ide-goaway',
          payload: { action: 'LIST_VIEWS' },
        }),
      )
      const signal = await started.promise
      expect(signal.aborted).toBe(false)
      oldConnection.closeNotifier = () => oldListener.onDisconnected()

      setup.transport.goaway()
      expect(signal.aborted).toBe(true)
      expect(signal.reason).toBe('CONTROL_DISCONNECTED')
      expect(oldConnection.isOpen()).toBe(false)
      expect(setup.runtime.getBoundEnvironmentId()).toBeNull()
      // 断连只中止 IDE 请求，不取消在途 Agent 执行。
      expect(setup.runtime.getJournal().find('inv-goaway')?.state).toBe('RUNNING')
      await waitFor(() => aborted)

      await waitFor(() => setup.transport.connectCount >= 2)
      expect(setup.transport.connection).not.toBe(oldConnection)
      // 迟到的旧代际断开不能清掉新代际的绑定。
      oldListener.onDisconnected()
      setup.transport.receive(serverMessage('SETTINGS', { environmentId: ENV, payload: {} }))
      setup.transport.receive(serverMessage('ENVIRONMENT_CONFIG', { environmentId: ENV, payload: { version: 0 } }))
      await waitFor(() => setup.runtime.getState() === 'READY')
      expect(setup.runtime.getBoundEnvironmentId()).toBe(ENV)
      expect(
        setup.runtime.sendSkillState([create(SkillPackageStateSchema, { packageName: 'demo' })]),
      ).toBe(true)
      expect(sent(setup.transport).some((envelope) => envelope.invocationId === 'ide-goaway')).toBe(false)

      agentGate.resolve()
      await waitFor(() =>
        sent(setup.transport).some(
          (envelope) => envelope.invocationId === 'inv-goaway' && envelope.messageType === 'COMPLETED',
        ),
      )
      expect(setup.runtime.getJournal().find('inv-goaway')?.state).toBe('COMPLETED')
    } finally {
      agentGate.resolve()
      setup.runtime.close()
      await setup.runtime.awaitCompletion()
    }
  })

  it('fails terminally on 401 registration rejection and reconnects on disconnect', async () => {
    const setup = buildRuntime()
    setup.runtime.start()
    await waitFor(() => setup.transport.connection !== null)
    setup.transport.listener?.onUpgradeFailed(401, false)
    await tick()
    expect(await setup.runtime.awaitTermination()).toBe('FAILED')
    expect(setup.runtime.getFailureReason()).toContain('registration is rejected')

    const retry = buildRuntime()
    retry.runtime.start()
    await waitFor(() => retry.transport.connection !== null)
    retry.transport.listener?.onDisconnected()
    await waitFor(() => retry.transport.connectCount >= 2)
    retry.runtime.close()
  })

  it('runs managed update on 426 with a target version without being aborted by shutdown', async () => {
    const updateGate = Promise.withResolvers<void>()
    let observedSignal: AbortSignal | null = null
    const update: UpdatePort = {
      async handleUpdate(cmd, _cfg, signal) {
        expect(cmd).toMatchObject({ targetVersion: '1.0.0' })
        observedSignal = signal
        expect(signal.aborted).toBe(false)
        await updateGate.promise
        return { phase: 'PREPARED' }
      },
    }
    const setup = buildRuntime({ update })
    setup.runtime.start()
    await waitFor(() => setup.transport.connection !== null)
    setup.transport.listener?.onUpgradeFailed(426, { targetVersion: '1.0.0' })
    await waitFor(() => setup.runtime.getRuntimeStatus().status.update !== null)
    expect(observedSignal).not.toBeNull()
    expect((observedSignal as unknown as AbortSignal).aborted).toBe(false)
    expect(setup.runtime.getState()).not.toBe('FAILED')

    updateGate.resolve()
    await waitFor(() => setup.runtime.getRuntimeStatus().status.update?.targetVersion === '1.0.0')
    expect(setup.runtime.getState()).not.toBe('FAILED')
    expect((observedSignal as unknown as AbortSignal).aborted).toBe(false)

    setup.runtime.close()
    await setup.runtime.awaitCompletion()
  })

  it('records transport.close failure into shutdownFailures before finalize finishes', async () => {
    const setup = buildRuntime()
    setup.runtime.start()
    await waitFor(() => setup.transport.connection !== null)
    const closeError = new Error('transport close crashed')
    setup.transport.close = async () => {
      throw closeError
    }
    setup.runtime.close()
    await setup.runtime.awaitCompletion()
    expect(setup.runtime.getShutdownFailures()).toContain(closeError)
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
    firstListener?.onEnvelope(serverMessage('HEARTBEAT', { environmentId: ENV, payload: {} }))
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

  it('exposes bound environment and journal accessors', async () => {
    const setup = buildRuntime()
    expect(setup.runtime.getBoundEnvironmentId()).toBeNull()
    expect(setup.runtime.getState()).toBe('STOPPED')
    await handshake(setup)
    expect(setup.runtime.getJournal()).toBeDefined()
    setup.runtime.close()
    expect(setup.runtime.getState()).toBe('STOPPED')
  })

  it('rejects envelopes bound to another environment', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    setup.transport.receive(serverMessage('CANCEL', { environmentId: OTHER_ENV, invocationId: 'inv-scope' }))
    await waitFor(() => lastErrorMessage(setup.transport)?.includes('does not match daemon') === true)
    setup.runtime.close()
  })

  /** SKILL_STATE 是在线状态帧：只有 READY 且已绑定时才有明确的接收环境。 */
  it('sends SKILL_STATE only while a READY bound connection exists', async () => {
    const setup = buildRuntime()
    const packages = [create(SkillPackageStateSchema, { packageName: 'demo' })]
    expect(setup.runtime.sendSkillState(packages)).toBe(false)
    await handshake(setup)
    expect(setup.runtime.sendSkillState(packages)).toBe(true)
    const state = setup.transport.sent.filter((envelope) => envelope.messageType === MessageType.SKILL_STATE).at(-1)
    expect(state?.environmentId).toBe(ENV)
    expect(state?.payload.case === 'skillState' ? state.payload.value.packages[0]?.packageName : undefined).toBe('demo')

    setup.transport.disconnect()
    await waitFor(() => setup.runtime.getState() === 'DISCONNECTED')
    expect(setup.runtime.sendSkillState(packages)).toBe(false)
    setup.runtime.close()
  })

  it('routes SKILL_SYNC to the reconciler only on a READY bound connection', async () => {
    const setup = buildRuntime()
    const desired: SkillPackage[][] = []
    setup.runtime.setSkillReconciler({
      onDesiredSet(packages) {
        desired.push(packages)
      },
    })
    const syncEnvelope = (packages: SkillPackage[]): Envelope =>
      create(EnvelopeSchema, {
        messageType: MessageType.SKILL_SYNC,
        environmentId: ENV,
        payload: { case: 'skillSync', value: create(SkillSyncSchema, { packages }) },
      })

    setup.runtime.start()
    await waitFor(() => setup.transport.connection !== null)
    setup.transport.receive(syncEnvelope([]))
    await waitFor(
      () => lastErrorMessage(setup.transport)?.includes('SKILL_SYNC requires a READY bound connection') === true,
    )

    setup.transport.receive(serverMessage('SETTINGS', {}))
    setup.transport.receive(serverMessage('ENVIRONMENT_CONFIG', { environmentId: ENV, payload: { version: 0 } }))
    await waitFor(() => setup.runtime.getState() === 'READY')
    const pkg = create(SkillPackageSchema, { packageName: 'demo', sha256: 'a'.repeat(64) })
    setup.transport.receive(syncEnvelope([pkg]))
    await waitFor(() => desired.length === 1)
    expect(desired[0]).toEqual([pkg])
    setup.runtime.close()
  })
})

describe('runtime IDE command lifetime and shutdown convergence', () => {
  /**
   * IDE 管理请求不进入 Agent journal；连接失效即中止在途请求，且不跨代际重放。
   */
  it('aborts an in-flight IDE command on disconnect without journaling or replay', async () => {
    let aborted = false
    const setup = buildRuntime({
      ideCommandTimeoutMillis: 60_000,
      ide: ideService({
        async handle(_command, signal) {
          await new Promise<void>((resolve) => signal.addEventListener('abort', () => resolve(), { once: true }))
          aborted = true
          return { ok: true, views: [] }
        },
      }),
    })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: 'ide-1', payload: { action: 'LIST_VIEWS' } }),
    )
    await tick()
    expect(setup.runtime.getJournal().find('ide-1')).toBeUndefined()
    setup.transport.disconnect()
    await waitFor(() => aborted)

    await waitFor(() => setup.transport.connectCount >= 2)
    setup.transport.receive(serverMessage('SETTINGS', { environmentId: ENV, payload: {} }))
    setup.transport.receive(serverMessage('ENVIRONMENT_CONFIG', { environmentId: ENV, payload: { version: 0 } }))
    await waitFor(() => setup.runtime.getState() === 'READY')
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'IDE_RESULT')).toBe(false)
    expect(setup.runtime.getJournal().find('ide-1')).toBeUndefined()
    setup.runtime.close()
  })

  /** 停机必须中止在途 controller，并且只有所有在途执行真正收敛后才发布完成信号。 */
  it('aborts in-flight executions on shutdown and only completes after they settle', async () => {
    let streamAborted = false
    let capabilityAborted = false
    let releaseCapability: (() => void) | null = null
    const capabilityGate = new Promise<void>((resolve) => {
      releaseCapability = resolve
    })
    const setup = buildRuntime({
      ideCommandTimeoutMillis: 60_000,
      ide: ideService({
        async handle(_command, signal) {
          await new Promise<void>((resolve) => signal.addEventListener('abort', () => resolve(), { once: true }))
          streamAborted = true
          return { ok: false, code: 'BUSY', message: 'aborted' }
        },
      }),
      capabilities: {
        'fs.read': async (call, context) => {
          await new Promise<void>((resolve) => {
            context.signal.addEventListener(
              'abort',
              () => {
                capabilityAborted = true
                resolve()
              },
              { once: true },
            )
          })
          // 收敛延迟：验证 termination 只在 Promise.allSettled 之后发布。
          await capabilityGate
          return textResult(call.invocationId)
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('IDE_COMMAND', {
        environmentId: ENV,
        invocationId: 'ide-1',
        payload: { action: 'LIST_VIEWS' },
      }),
    )
    setup.transport.receive(
      serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload('fs.read', 60_000) }),
    )
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'STARTED'))

    let completed = false
    const completion = setup.runtime.awaitCompletion().then(() => {
      completed = true
    })
    setup.runtime.close()
    await waitFor(() => streamAborted)
    await waitFor(() => capabilityAborted)
    await tick(3)
    expect(completed).toBe(false)

    releaseCapability?.()
    await waitFor(() => completed)
    await completion
  })

  /** 受管更新也有独立 controller；停机必须中止它，并在其收敛后才完成终止。 */
  it('aborts an in-flight managed update on shutdown and awaits its outcome', async () => {
    let updateAborted = false
    let releaseUpdate: (() => void) | null = null
    const updateGate = new Promise<void>((resolve) => {
      releaseUpdate = resolve
    })
    const update: UpdatePort = {
      async handleUpdate(_command, _config, signal) {
        await new Promise<void>((resolve) =>
          signal.addEventListener(
            'abort',
            () => {
              updateAborted = true
              resolve()
            },
            { once: true },
          ),
        )
        await updateGate
        return { phase: 'FAILED', message: 'aborted' }
      },
    }
    const setup = buildRuntime({ update })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('UPDATE', {
        environmentId: ENV,
        payload: {
          operationId: '55555555-4444-4555-8666-777777777777',
          targetVersion: UPDATE_TARGET_VERSION,
        },
      }),
    )
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'UPDATE_RESULT'))

    let completed = false
    const completion = setup.runtime.awaitCompletion().then(() => {
      completed = true
    })
    setup.runtime.close()
    await waitFor(() => updateAborted)
    await tick(3)
    expect(completed).toBe(false)

    releaseUpdate?.()
    await waitFor(() => completed)
    await completion
  })

  /**
   * 在途 IDE 请求必须有硬上限。同 ID 重复不占名额，不同 ID 洪泛到上限一律固定 busy，
   * 释放名额后可再接新请求。
   */
  it('bounds in-flight IDE commands, reports busy on overflow and accepts again after release', async () => {
    const releases: Array<() => void> = []
    const ide = ideService({
      async handle(_command, signal) {
        await new Promise<void>((resolve) => {
          releases.push(resolve)
          signal.addEventListener('abort', () => resolve(), { once: true })
        })
        return { ok: true, views: [] }
      },
    })
    const setup = buildRuntime({ ideCommandTimeoutMillis: 60_000, ide })
    await handshake(setup)
    const uuid = (n: number): string => `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`
    const cap = 256
    const commandPayload = { action: 'LIST_VIEWS' }
    for (let index = 0; index < cap; index++) {
      setup.transport.receive(
        serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: uuid(index), payload: commandPayload }),
      )
    }
    expect(releases.length).toBe(cap)

    // 同 ID 重复不占新名额。
    setup.transport.receive(
      serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: uuid(0), payload: commandPayload }),
    )
    await tick()
    expect(releases.length).toBe(cap)

    // 不同 ID 超过上限：固定 busy，绝不执行副作用。
    setup.transport.receive(
      serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: uuid(cap), payload: commandPayload }),
    )
    await waitFor(() =>
      sent(setup.transport).some(
        (envelope) => envelope.messageType === 'IDE_RESULT' && envelope.invocationId === uuid(cap),
      ),
    )
    const busy = sent(setup.transport).find((envelope) => envelope.invocationId === uuid(cap))
    expect(busy?.payload).toMatchObject({ ok: false, message: 'too many in-flight IDE commands' })
    expect(releases.length).toBe(cap)

    // 释放一个名额后可再接新请求。
    releases[0]?.()
    await waitFor(() =>
      sent(setup.transport).some(
        (envelope) => envelope.messageType === 'IDE_RESULT' && envelope.invocationId === uuid(0),
      ),
    )
    setup.transport.receive(
      serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: uuid(cap + 1), payload: commandPayload }),
    )
    await waitFor(() => releases.length === cap + 1)
    setup.runtime.close()
  })
})

describe('runtime execution exit confirmation', () => {
  it('does not ACK until the executor body returns, even after confirmExecutionExit', async () => {
    let entered = false
    const gate = Promise.withResolvers<void>()
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          context.confirmExecutionExit()
          entered = true
          await gate.promise
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-exit', payload: invokePayload() }))
      // 有界等待 capability body 真正进入，不用固定 tick 数或 STARTED 猜测执行位置。
      await waitFor(() => entered)
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toBe(false)
      gate.resolve()
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED'))
      const exited = sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')
      expect(exited).toHaveLength(1)
      expect(exited[0]?.payload).toEqual({})
      const types = sent(setup.transport).map((envelope) => envelope.messageType)
      expect(types.indexOf('COMPLETED')).toBeLessThan(types.indexOf('EXECUTION_EXITED'))
    } finally {
      gate.resolve()
      // close 抛错也必须真正 join，避免执行器仍停留在本次 scope 内。
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  it('does not ACK a terminal result that never confirmed its own execution scope', async () => {
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call) => textResult(call.invocationId),
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-plain', payload: invokePayload() }))
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toBe(false)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-plain', payload: invokePayload() }))
      // 等重放终态这一具体动作，不用固定 tick 数猜测重复 INVOKE 已处理。
      await waitFor(() => sent(setup.transport).filter((envelope) => envelope.messageType === 'COMPLETED').length === 2)
      expect(sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toHaveLength(0)
    } finally {
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
    expect(setup.runtime.getJournal().find('inv-plain')?.exitConfirmed).toBe(false)
  })

  it('replays a stored EXECUTION_EXITED independently of the terminal on duplicate INVOKE', async () => {
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          context.confirmExecutionExit()
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-replay', payload: invokePayload() }))
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED'))
      const before = sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED').length
      expect(before).toBe(1)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-replay', payload: invokePayload() }))
      await waitFor(() => sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED').length === 2)
      const replayed = sent(setup.transport).filter((envelope) => envelope.invocationId === 'inv-replay')
      expect(replayed.filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toHaveLength(2)
      expect(replayed.filter((envelope) => envelope.messageType === 'COMPLETED')).toHaveLength(2)
    } finally {
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  it('replays a stored EXECUTION_EXITED once on a repeated CANCEL after the terminal', async () => {
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          context.confirmExecutionExit()
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-cancel-replay', payload: invokePayload() }))
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED'))
      setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId: 'inv-cancel-replay', payload: {} }))
      setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId: 'inv-cancel-replay', payload: {} }))
      await waitFor(() => sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED').length === 3)
      const replayed = sent(setup.transport).filter((envelope) => envelope.invocationId === 'inv-cancel-replay')
      expect(replayed.filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toHaveLength(3)
      expect(replayed.filter((envelope) => envelope.messageType === 'COMPLETED')).toHaveLength(3)
    } finally {
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  it('ACKs only after a cancelled execution has actually left its scope', async () => {
    let entered = false
    let cancelled = false
    const gate = Promise.withResolvers<void>()
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          context.signal.addEventListener('abort', () => {
            cancelled = true
          }, { once: true })
          entered = true
          await gate.promise
          if (context.signal.aborted) {
            context.confirmExecutionExit()
            return { callId: call.invocationId, error: true, details: {}, contents: [{ type: 'text', text: 'cancelled' }] }
          }
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-cancel', payload: invokePayload() }))
      // 有界等待真实 capability body 与 abort 事件，不用固定 tick 数猜测。
      await waitFor(() => entered)
      setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId: 'inv-cancel', payload: {} }))
      await waitFor(() => cancelled)
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toBe(false)
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'CANCELLED')).toBe(false)
      gate.resolve()
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED'))
      const terminal = sent(setup.transport).find(
        (envelope) =>
          envelope.invocationId === 'inv-cancel' &&
          (envelope.messageType === 'CANCELLED' || envelope.messageType === 'FAILED' || envelope.messageType === 'COMPLETED'),
      )
      expect(terminal?.messageType).toBe('COMPLETED')
      expect(JSON.stringify(terminal?.payload)).toContain('cancelled')
    } finally {
      gate.resolve()
      // close 抛错也必须真正 join，避免执行器仍停留在本次 scope 内。
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  it('ACKs a late confirmation after the terminal removed the running entry, and only once', async () => {
    // 终态会移出 running。迟到的正向证据必须仍绑定这次调用并发送一次，不能因为 map 已空而丢失。
    let confirm: (() => void) | null = null
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          confirm = () => context.confirmExecutionExit()
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-late', payload: invokePayload() }))
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toBe(false)
      confirm?.()
      confirm?.()
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED'))
      expect(sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toHaveLength(1)
    } finally {
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  it('does not ACK when owned upload cleanup throws after the executor confirmed', async () => {
    // 正向证据不能抢在本次资源上传之前发出。上传抛错说明收尾未完成，终态之后也不补 ACK。
    const setup = buildRuntime({
      resources: {
        async upload() {
          throw new Error('upload failed')
        },
      },
      capabilities: {
        'fs.read': async (call, context) => {
          context.confirmExecutionExit()
          return {
            callId: call.invocationId,
            error: false,
            details: {},
            contents: [{ type: 'binary', bytes: Uint8Array.of(1), mediaType: 'application/octet-stream' }],
          }
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-upload', payload: invokePayload() }))
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'FAILED'))
      expect(sent(setup.transport).find((envelope) => envelope.messageType === 'FAILED')?.payload).toEqual({ message: 'upload failed' })
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toBe(false)
    } finally {
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
    // Runtime 已真正收尾；失败的上传不能留下可在重连后重放的退出事实。
    expect(setup.runtime.getJournal().find('inv-upload')?.exitConfirmed).toBe(false)
  })

  it('ACKs only after a successful resource upload, not when confirmExecutionExit returns', async () => {
    // 同一调用的正向对照：上传成功且终态发送后，已留下的证据才变成一次 ACK。
    let uploadEntered = false
    const uploadGate = Promise.withResolvers<void>()
    const setup = buildRuntime({
      resources: {
        async upload() {
          uploadEntered = true
          await uploadGate.promise
          return {
            type: 'resource',
            uploadId: '77777777-7777-4777-8777-777777777777',
            mediaType: 'application/octet-stream',
            name: null,
            size: 1,
            sha256: 'a'.repeat(64),
          }
        },
      },
      capabilities: {
        'fs.read': async (call, context) => {
          context.confirmExecutionExit()
          return {
            callId: call.invocationId,
            error: false,
            details: {},
            contents: [{ type: 'binary', bytes: Uint8Array.of(1), mediaType: 'application/octet-stream' }],
          }
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-upload-ok', payload: invokePayload() }))
      // 有界等待真实 uploader 进入，不用 tick 数或终态猜测上传已开始。
      await waitFor(() => uploadEntered)
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toBe(false)
      uploadGate.resolve()
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED'))
      const types = sent(setup.transport).map((envelope) => envelope.messageType)
      expect(types.indexOf('COMPLETED')).toBeLessThan(types.indexOf('EXECUTION_EXITED'))
      expect(sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toHaveLength(1)
    } finally {
      uploadGate.resolve()
      // close 抛错也必须真正 join，避免执行器仍停留在本次 scope 内。
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  it('does not ACK process.exec when the managed scope fails to converge', async () => {
    const setup = buildRuntime({
      capabilities: processCapabilities,
      processes: {
        async start() {
          const stdout = new PassThrough()
          const stderr = new PassThrough()
          stdout.end()
          stderr.end()
          return {
            stdin: new PassThrough(),
            stdout,
            stderr,
            exit: Promise.resolve({ exitCode: 0, signal: null }),
            terminate: async () => {
              throw new Error('scope still running')
            },
          } satisfies ManagedProcess
        },
        async close() {},
      },
    })
    let dir: string | null = null
    try {
      await handshake(setup)
      dir = await mkdtemp(join(tmpdir(), 'kk-exit-process-'))
      dir = await realpath(dir)
      setup.transport.receive(
        serverMessage('INVOKE', {
          environmentId: ENV,
          invocationId: 'inv-process',
          payload: invokePayload('process.exec', 0, { command: 'true', workdir: dir }),
        }),
      )
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED' || envelope.messageType === 'FAILED'))
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toBe(false)
      const terminal = sent(setup.transport).find((envelope) => envelope.messageType === 'FAILED' || envelope.messageType === 'COMPLETED')
      expect(JSON.stringify(terminal?.payload)).toContain('did not converge')
    } finally {
      // close 抛错也必须真正 join；清理目录放在最内层，join 失败也尽量释放。
      try {
        setup.runtime.close()
      } finally {
        try {
          await setup.runtime.awaitCompletion()
        } finally {
          if (dir !== null) {
            await rm(dir, { recursive: true, force: true })
          }
        }
      }
    }
  })

  describe('outbound validation and message conformance', () => {
    it('sets cancelled.reason on user cancellation and shutdown, and marks replayed on replayed STARTED', async () => {
      let hangGate = Promise.withResolvers<void>()
      const setup = buildRuntime({
        initialReconnectDelayMillis: 10,
        capabilities: {
          'fs.read': async (_call, context) => {
            await new Promise<void>((resolve) => {
              context.signal.addEventListener('abort', () => resolve(), { once: true })
              void hangGate.promise.then(resolve)
            })
            throw new Error('cancelled')
          },
        },
      })

      try {
        await handshake(setup)
        // 1. 发起调用，首次收到 STARTED，replayed 为 undefined
        setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
        await waitFor(() => sent(setup.transport).some((e) => e.messageType === 'STARTED'))
        const firstStarted = setup.transport.sent.find((e) => e.messageType === MessageType.STARTED)
        expect(firstStarted?.payload.case).toBe('started')
        if (firstStarted?.payload.case === 'started') {
          expect(firstStarted.payload.value.replayed).toBeFalsy()
        }

        // 2. 发起 CANCEL，收到 CANCELLED，reason 必须为 'cancelled'
        setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId: 'inv-1', payload: {} }))
        await waitFor(() => sent(setup.transport).some((e) => e.messageType === 'CANCELLED'))
        const userCancelled = setup.transport.sent.find((e) => e.messageType === MessageType.CANCELLED)
        expect(userCancelled?.payload.case).toBe('cancelled')
        if (userCancelled?.payload.case === 'cancelled') {
          expect(userCancelled.payload.value.reason).toBe('cancelled')
        }

        // 3. 再次发起一个悬挂调用 inv-2，重放时验证 replayed: true
        hangGate = Promise.withResolvers<void>()
        const invoke2 = serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-2', payload: invokePayload() })
        setup.transport.receive(invoke2)
        await waitFor(() => setup.transport.sent.filter((e) => e.messageType === MessageType.STARTED).length >= 2)

        // 重连，触发 replay
        setup.transport.disconnect()
        await waitFor(() => setup.transport.connectCount >= 2)
        setup.transport.receive(serverMessage('SETTINGS', { environmentId: ENV, payload: {} }))
    setup.transport.receive(serverMessage('ENVIRONMENT_CONFIG', { environmentId: ENV, payload: { version: 0 } }))
        await waitFor(() => setup.runtime.getState() === 'READY')
        setup.transport.receive(invoke2)
        // 等待重连后的 STARTED
        await waitFor(() => setup.transport.sent.filter((e) => e.messageType === MessageType.STARTED).length >= 1)
        const replayedStarted = setup.transport.sent[setup.transport.sent.length - 1]
        expect(replayedStarted.payload.case).toBe('started')
        if (replayedStarted.payload.case === 'started') {
          expect(replayedStarted.payload.value.replayed).toBe(true)
        }
      } finally {
        hangGate.resolve()
        setup.runtime.close()
        await setup.runtime.awaitCompletion()
      }
    })
  })
})
