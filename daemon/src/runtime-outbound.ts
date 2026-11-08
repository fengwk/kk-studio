import { create } from '@bufbuild/protobuf'

import type {
  Envelope,
  HostInfo,
  IdeResult,
  Progress,
  RuntimeSnapshot,
  SkillPackageState,
  UpdateResult,
} from './generated/control_pb.js'
import {
  EnvelopeSchema,
  ErrorSchema,
  ExecutionExitedSchema,
  HeartbeatSchema,
  MessageType,
  ReadySchema,
  RecoverNotFoundSchema,
  RuntimeStatusSchema,
  SkillStateSchema,
  StartedSchema,
} from './generated/control_pb.js'
import type { TerminalMessage } from './journal.js'
import type { ActiveConnection, ConnectionLifecycle } from './runtime-connection.js'

/**
 * 控制面出站。
 *
 * <p>这里持有当前绑定的 environmentId，并集中构造必须携带它的信封。绑定由控制会话在握手成功或断线时改变；
 * 需要绑定的帧在未绑定时返回 false，部分帧还要求连接处于 READY；READY 握手帧不要求已有绑定。
 */
export class OutboundControl {
  private boundEnvironmentId: string | null = null

  constructor(
    private readonly connection: ConnectionLifecycle,
    private readonly daemonVersion: string,
    private readonly environment: HostInfo,
  ) {}

  get environmentId(): string | null {
    return this.boundEnvironmentId
  }

  /** 连接处于 READY；与 READY 握手一起成立的绑定另见 {@link canSend}。 */
  get isReady(): boolean {
    return this.connection.getState() === 'READY'
  }

  /** READY 且已绑定：需要就绪状态的出站帧使用的门禁。 */
  get canSend(): boolean {
    return this.isReady && this.boundEnvironmentId !== null
  }

  /** 已完成 SETTINGS scope 绑定；CONNECTING 阶段允许的帧使用。 */
  get isBound(): boolean {
    return this.boundEnvironmentId !== null
  }

  bind(environmentId: string): void {
    this.boundEnvironmentId = environmentId
  }

  unbind(): void {
    this.boundEnvironmentId = null
  }

  /** READY 回执发往发起握手的连接；绑定在调用前已成立。 */
  sendReady(connection: ActiveConnection, runtimeStatus: RuntimeSnapshot): boolean {
    return this.sendOn(
      connection,
      create(EnvelopeSchema, {
        messageType: MessageType.READY,
        environmentId: this.boundEnvironmentId ?? undefined,
        payload: {
          case: 'ready',
          value: create(ReadySchema, {
            daemonVersion: this.daemonVersion,
            hostInfo: this.environment,
            runtimeStatus,
          }),
        },
      }),
    )
  }

  sendHeartbeat(runtimeStatus: RuntimeSnapshot, ideLeaseRound: bigint | undefined): boolean {
    if (!this.isBound) {
      return false
    }
    return this.send(
      create(EnvelopeSchema, {
        messageType: MessageType.HEARTBEAT,
        environmentId: this.boundEnvironmentId ?? undefined,
        payload: {
          case: 'heartbeat',
          value: create(HeartbeatSchema, {
            runtimeStatus,
            ideLeaseRound,
          }),
        },
      }),
    )
  }

  /** STARTED 只发往指定连接；connection 为空时发往当前连接（journal 重放）。 */
  sendStarted(connection: ActiveConnection | null, invocationId: string, replayed: boolean): boolean {
    if (this.boundEnvironmentId === null) {
      return false
    }
    const envelope = create(EnvelopeSchema, {
      messageType: MessageType.STARTED,
      environmentId: this.boundEnvironmentId,
      invocationId,
      payload: {
        case: 'started',
        value: create(StartedSchema, replayed ? { replayed: true } : {}),
      },
    })
    return connection === null ? this.send(envelope) : this.sendOn(connection, envelope)
  }

  sendTerminal(invocationId: string, terminal: TerminalMessage): boolean {
    if (this.boundEnvironmentId === null) {
      return false
    }
    return this.send(
      create(EnvelopeSchema, {
        messageType: terminal.messageType,
        environmentId: this.boundEnvironmentId,
        invocationId,
        payload: terminal.payload,
      }),
    )
  }

  sendExecutionExited(invocationId: string): boolean {
    if (this.boundEnvironmentId === null) {
      return false
    }
    return this.send(
      create(EnvelopeSchema, {
        messageType: MessageType.EXECUTION_EXITED,
        environmentId: this.boundEnvironmentId,
        invocationId,
        payload: { case: 'executionExited', value: create(ExecutionExitedSchema, {}) },
      }),
    )
  }

  sendProgress(invocationId: string, progress: Progress): boolean {
    if (this.boundEnvironmentId === null) {
      return false
    }
    return this.send(
      create(EnvelopeSchema, {
        messageType: MessageType.PROGRESS,
        environmentId: this.boundEnvironmentId,
        invocationId,
        payload: { case: 'progress', value: progress },
      }),
    )
  }

  sendRecoverNotFound(invocationId: string): boolean {
    if (this.boundEnvironmentId === null) {
      return false
    }
    return this.send(
      create(EnvelopeSchema, {
        messageType: MessageType.RECOVER_NOT_FOUND,
        environmentId: this.boundEnvironmentId,
        invocationId,
        payload: { case: 'recoverNotFound', value: create(RecoverNotFoundSchema, {}) },
      }),
    )
  }

  sendSkillState(packages: SkillPackageState[]): boolean {
    if (!this.canSend) {
      return false
    }
    return this.send(
      create(EnvelopeSchema, {
        messageType: MessageType.SKILL_STATE,
        environmentId: this.boundEnvironmentId ?? undefined,
        payload: {
          case: 'skillState',
          value: create(SkillStateSchema, {
            packages,
          }),
        },
      }),
    )
  }

  sendRuntimeStatus(runtimeStatus: RuntimeSnapshot): boolean {
    if (!this.isBound) {
      return false
    }
    return this.send(
      create(EnvelopeSchema, {
        messageType: MessageType.RUNTIME_STATUS,
        environmentId: this.boundEnvironmentId ?? undefined,
        payload: {
          case: 'runtimeStatus',
          value: create(RuntimeStatusSchema, {
            runtimeStatus,
          }),
        },
      }),
    )
  }

  sendUpdateResult(result: UpdateResult): boolean {
    if (!this.canSend) {
      return false
    }
    return this.send(
      create(EnvelopeSchema, {
        messageType: MessageType.UPDATE_RESULT,
        environmentId: this.boundEnvironmentId ?? undefined,
        payload: { case: 'updateResult', value: result },
      }),
    )
  }

  sendIdeResult(invocationId: string, reply: IdeResult): boolean {
    if (this.boundEnvironmentId === null) {
      return false
    }
    return this.send(
      create(EnvelopeSchema, {
        messageType: MessageType.IDE_RESULT,
        environmentId: this.boundEnvironmentId,
        invocationId,
        payload: { case: 'ideResult', value: reply },
      }),
    )
  }

  /** 入站处理失败时回复 ERROR；environmentId 由调用方裁决，未给出时不发送。 */
  sendErrorOn(connection: ActiveConnection, environmentId: string | undefined, message: string): boolean {
    if (environmentId === undefined) {
      return false
    }
    return this.sendOn(connection, createErrorEnvelope(environmentId, message))
  }

  /** 已 READY 且绑定时的扩展处理器失败回执。 */
  sendError(message: string): boolean {
    if (!this.canSend || this.boundEnvironmentId === null) {
      return false
    }
    return this.send(createErrorEnvelope(this.boundEnvironmentId, message))
  }

  /** 发送一条已构造的信封并补上当前绑定；用于资源上传控制面。 */
  sendBound(envelope: Envelope): boolean {
    if (!this.canSend) {
      return false
    }
    return this.send(create(EnvelopeSchema, { ...envelope, environmentId: this.boundEnvironmentId ?? undefined }))
  }

  private send(envelope: Envelope): boolean {
    return this.connection.send(envelope)
  }

  private sendOn(connection: ActiveConnection, envelope: Envelope): boolean {
    return this.connection.sendOn(connection, envelope)
  }
}

function createErrorEnvelope(environmentId: string, message: string): Envelope {
  return create(EnvelopeSchema, {
    messageType: MessageType.ERROR,
    environmentId,
    payload: {
      case: 'error',
      value: create(ErrorSchema, { message }),
    },
  })
}
