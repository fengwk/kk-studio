import { describe, expect, it } from 'vitest'
import { create } from '@bufbuild/protobuf'

import {
  Architecture,
  CapabilityResultSchema,
  CompletedSchema,
  EnvironmentInfoSchema,
  EnvelopeSchema,
  IdeResultSchema,
  MessageType,
  OperatingSystem,
  ProgressSchema,
  RuntimeBodySchema,
  RuntimeSnapshotSchema,
  SkillPackageStateSchema,
  StartedSchema,
  UpdatePhase,
  UpdateResultSchema,
} from '../src/generated/control_pb.js'
import type { TerminalMessage } from '../src/journal.js'
import { ConnectionLifecycle, type ActiveConnection } from '../src/runtime-connection.js'
import { OutboundControl } from '../src/runtime-outbound.js'
import { FakeTransport, waitFor } from './helpers.js'

const ENVIRONMENT_ID = '11111111-2222-4333-8444-555555555555'

const ENVIRONMENT = create(EnvironmentInfoSchema, {
  operatingSystem: OperatingSystem.LINUX,
  architecture: Architecture.AMD64,
  timeZone: 'UTC',
  userName: 'dev',
  homeDirectory: '/home/dev',
  note: 'Linux environment.',
  tempDirectory: '/tmp',
})

const SNAPSHOT = create(RuntimeSnapshotSchema, {
  revision: 1n,
  status: create(RuntimeBodySchema, { daemonVersion: '1.2.3' }),
})

interface ConnectedOutbound {
  transport: FakeTransport
  connection: ConnectionLifecycle
  active: ActiveConnection
  outbound: OutboundControl
}

async function connectedOutbound(): Promise<ConnectedOutbound> {
  const transport = new FakeTransport()
  const connection = new ConnectionLifecycle({
    transport,
    initialReconnectDelayMillis: 1,
    maxReconnectDelayMillis: 2,
    callbacks: {
      onEnvelope(): void {},
      onUpgradeFailed(): void {},
      onDisconnected(): void {},
    },
  })
  connection.start()
  await waitFor(() => connection.getActiveConnection() !== null)
  const active = connection.getActiveConnection()
  if (active === null) {
    throw new Error('connection is not active')
  }
  return { transport, connection, active, outbound: new OutboundControl(connection, '1.2.3', ENVIRONMENT) }
}

function completedTerminal(): TerminalMessage {
  return {
    messageType: MessageType.COMPLETED,
    payload: {
      case: 'completed',
      value: create(CompletedSchema, {
        result: create(CapabilityResultSchema, {
          callId: 'inv-1',
          error: false,
          detailsJson: '{}',
          contents: [],
        }),
      }),
    },
  }
}

function preparedResult() {
  return create(UpdateResultSchema, {
    operationId: 'op-1',
    phase: UpdatePhase.UPDATE_PREPARED,
  })
}

function progress() {
  return create(ProgressSchema, {
    result: create(CapabilityResultSchema, {
      callId: 'inv-1',
      error: false,
      detailsJson: '{}',
      contents: [],
    }),
  })
}

describe('outbound control', () => {
  /** 未绑定环境时任何帧都不能发出，避免平台收到无主或串环境的报文。 */
  it('refuses every envelope before an environment is bound', async () => {
    const { transport, active, outbound } = await connectedOutbound()

    expect(outbound.environmentId).toBeNull()
    expect(outbound.isReady).toBe(false)
    expect(outbound.canSend).toBe(false)

    expect(outbound.sendStarted(active, 'inv-1', true)).toBe(false)
    expect(outbound.sendTerminal('inv-1', completedTerminal())).toBe(false)
    expect(outbound.sendExecutionExited('inv-1')).toBe(false)
    expect(outbound.sendProgress('inv-1', progress())).toBe(false)
    expect(outbound.sendRecoverNotFound('inv-1')).toBe(false)
    expect(outbound.sendSkillState([])).toBe(false)
    expect(outbound.sendRuntimeStatus(SNAPSHOT)).toBe(false)
    expect(outbound.sendUpdateResult(preparedResult())).toBe(false)
    expect(outbound.sendIdeResult('inv-1', create(IdeResultSchema, { ok: true }))).toBe(false)
    expect(outbound.sendErrorOn(active, undefined, 'scope failed')).toBe(false)
    expect(outbound.sendError('IDE_LEASE handler failed')).toBe(false)
    expect(outbound.sendBound(create(EnvelopeSchema, { messageType: MessageType.HEARTBEAT }))).toBe(false)
    expect(transport.sent).toHaveLength(0)
  })

  /**
   * 绑定先于 READY 成立：invocation 终态与恢复重放必须能在 READY 握手完成的同一时刻发出，
   * 而 SKILL_STATE/RUNTIME_STATUS/UPDATE_RESULT 这类在线状态帧只在 READY 后发送。
   */
  it('sends bound invocation frames before READY and READY-gated frames only after markReady', async () => {
    const { transport, connection, active, outbound } = await connectedOutbound()
    outbound.bind(ENVIRONMENT_ID)

    expect(outbound.environmentId).toBe(ENVIRONMENT_ID)
    expect(outbound.isReady).toBe(false)
    expect(outbound.canSend).toBe(false)

    expect(outbound.sendStarted(active, 'inv-1', true)).toBe(true)
    const started = transport.sent.at(-1)
    expect(started?.messageType).toBe(MessageType.STARTED)
    expect(started?.environmentId).toBe(ENVIRONMENT_ID)
    expect(started?.payload.case === 'started' ? started.payload.value.replayed : undefined).toBe(true)

    expect(outbound.sendTerminal('inv-1', completedTerminal())).toBe(true)
    expect(outbound.sendExecutionExited('inv-1')).toBe(true)
    expect(outbound.sendProgress('inv-1', progress())).toBe(true)
    expect(outbound.sendRecoverNotFound('inv-1')).toBe(true)
    expect(outbound.sendIdeResult('inv-1', create(IdeResultSchema, { ok: false, code: 'BUSY', message: 'busy' }))).toBe(true)

    expect(outbound.sendSkillState([])).toBe(false)
    expect(outbound.sendRuntimeStatus(SNAPSHOT)).toBe(false)
    expect(outbound.sendUpdateResult(preparedResult())).toBe(false)
    expect(outbound.sendError('IDE_LEASE handler failed')).toBe(false)
    expect(outbound.sendBound(create(EnvelopeSchema, { messageType: MessageType.HEARTBEAT }))).toBe(false)

    expect(connection.markReady(active)).toBe(true)
    expect(outbound.canSend).toBe(true)

    expect(outbound.sendSkillState([create(SkillPackageStateSchema, { packageName: 'demo' })])).toBe(true)
    const skillState = transport.sent.at(-1)
    expect(skillState?.messageType).toBe(MessageType.SKILL_STATE)
    expect(skillState?.environmentId).toBe(ENVIRONMENT_ID)
    expect(skillState?.payload.case).toBe('skillState')

    expect(outbound.sendRuntimeStatus(SNAPSHOT)).toBe(true)
    expect(transport.sent.at(-1)?.messageType).toBe(MessageType.RUNTIME_STATUS)

    expect(outbound.sendUpdateResult(preparedResult())).toBe(true)
    expect(transport.sent.at(-1)?.messageType).toBe(MessageType.UPDATE_RESULT)

    expect(outbound.sendErrorOn(active, ENVIRONMENT_ID, 'scope failed')).toBe(true)
    const scopedError = transport.sent.at(-1)
    expect(scopedError?.messageType).toBe(MessageType.ERROR)
    expect(scopedError?.payload.case === 'error' ? scopedError.payload.value.message : undefined).toBe('scope failed')

    expect(outbound.sendError('IDE_LEASE handler failed: boom')).toBe(true)
    const extensionError = transport.sent.at(-1)
    expect(extensionError?.messageType).toBe(MessageType.ERROR)
    expect(extensionError?.payload.case === 'error' ? extensionError.payload.value.message : undefined).toBe(
      'IDE_LEASE handler failed: boom',
    )

    expect(
      outbound.sendBound(
        create(EnvelopeSchema, {
          messageType: MessageType.STARTED,
          invocationId: 'inv-1',
          payload: { case: 'started', value: create(StartedSchema, {}) },
        }),
      ),
    ).toBe(true)
    expect(transport.sent.at(-1)?.environmentId).toBe(ENVIRONMENT_ID)

    expect(outbound.sendReady(active, SNAPSHOT)).toBe(true)
    const ready = transport.sent.at(-1)
    expect(ready?.messageType).toBe(MessageType.READY)
    expect(ready?.environmentId).toBe(ENVIRONMENT_ID)
    expect(ready?.payload.case === 'ready' ? ready.payload.value.daemonVersion : undefined).toBe('1.2.3')
  })

  /** 断线清空绑定后，原本已 READY 的通道也不再发出任何帧，直到下一次握手重新绑定。 */
  it('stops sending once the binding is cleared', async () => {
    const { transport, connection, active, outbound } = await connectedOutbound()
    outbound.bind(ENVIRONMENT_ID)
    expect(connection.markReady(active)).toBe(true)
    expect(outbound.sendSkillState([])).toBe(true)

    outbound.unbind()
    expect(outbound.environmentId).toBeNull()
    expect(outbound.canSend).toBe(false)
    expect(outbound.sendSkillState([])).toBe(false)
    expect(outbound.sendRuntimeStatus(SNAPSHOT)).toBe(false)
    expect(outbound.sendStarted(active, 'inv-1', false)).toBe(false)
    expect(transport.sent).toHaveLength(1)
  })
})
