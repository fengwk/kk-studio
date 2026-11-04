import { randomUUID } from 'node:crypto'
import { create } from '@bufbuild/protobuf'

import { parseReleaseArtifactUrl } from './release.js'
import type {
  CapabilityCall,
  CapabilityContext,
  CapabilityExecutor,
  CapabilityResult,
  DaemonConfig,
  IdeCommand,
  IdeLeaseHandler,
  IdeService,
  ProcessSupervisor,
  ResultContent,
} from './contracts.js'
import { DAEMON_VERSION } from './config.js'
import { applyNetworkSettings } from './network.js'
import {
  CancelSchema,
  CancelledSchema,
  CapabilityResultSchema,
  CompletedSchema,
  ContentSchema,
  EnvelopeSchema,
  ErrorSchema,
  ExecutionExitedSchema,
  FailedSchema,
  HeartbeatSchema,
  IdeResultSchema,
  JsonContentSchema,
  MessageType,
  ProgressSchema,
  ReadySchema,
  RecoverNotFoundSchema,
  ResourceContentSchema,
  RuntimeBodySchema,
  RuntimeSnapshotSchema,
  RuntimeStatusSchema,
  SettingsSchema,
  StartedSchema,
  TextContentSchema,
  UpdateProgressSchema,
  UpdateResultSchema,
  IdeAction as ProtoIdeAction,
  IdeOpenMode as ProtoIdeOpenMode,
  type Cancel,
  type Completed,
  type Content,
  type Envelope,
  type Failed,
  type Heartbeat,
  type IdeCommand as ProtoIdeCommand,
  type IdeLease as ProtoIdeLease,
  type IdeResult,
  type Invoke,
  type Progress,
  type Ready,
  type Recover,
  type ResourceUploadTicket,
  type RuntimeSnapshot,
  type Settings,
  type Started,
  type TemporaryResourcePolicy,
  type Update,
  type UpdateResult,
  type UpgradeRequired,
} from './generated/control_pb.js'
import {
  InMemoryInvocationJournal,
  isTerminalState,
  type InvocationJournal,
  type JournalEntry,
  type TerminalMessage,
  type TerminalPayload,
} from './journal.js'
import { validate } from './protocol/control/codec.js'
import { RuntimeStatusTracker, type RuntimeStatus, type RuntimeStatusUpdate, type UpdateByteProgress } from './runtime-status.js'
import { detectEnvironmentInfo } from './runtime-environment.js'
import {
  admitIdeCommand,
  ideFailureReply,
  requireFreshIdeLease,
  toProtoIdeResult,
  type ActiveIdeLeaseRound,
} from './runtime-ide.js'
import type { RuntimeOptions, TemporaryResourceSweeper, UpdatePort } from './runtime-ports.js'
import { createResourceRuntime, type UploadedResource } from './runtime-resources.js'
import { collectDiagnosticFailure, recordShutdownFailure, releaseStep } from './runtime-shutdown.js'
import {
  decideUpdate,
  executeUpdateAttempt,
  executionFailureReceipt,
  preparedReceipt,
  receiptToResend,
  rememberUpdateResult,
  type ActiveUpdate,
  type UpdateReceipt,
} from './runtime-update.js'
import type { ControlConnection, ControlTransport, ControlTransportListener } from './transport/index.js'

/**
 * Environment Daemon 的连接、协议与本地 Capability 执行基座。
 *
 * <p>Invocation journal 是进程内执行事实源；控制流只是消息管道。连接断开后 Daemon 保留 journal 事实并重新握手
 * 与重连；若 Platform 再次发送相同 invocationId，RUNNING 与终态条目分别重放 STARTED 与 terminal 报文，绝不重复执行副作用。
 * 断开连接绝不取消在途 Agent 执行。
 *
 * <p>本进程的 daemonInstanceId 在构造期随机生成一次并在 WebSocket 握手头中声明；同一进程的多次重连复用同一身份。每次连接尝试由
 * 独立的 connectionGeneration 标识，隔离跨连接事件干扰。
 *
 * <p>有界性：并发 invocation 数、update 回执缓存、IDE 在途请求集合与 journal 终态条目都有明确上界；没有无界队列。
 */

export type RuntimeState = 'STOPPED' | 'DISCONNECTED' | 'CONNECTING' | 'READY' | 'FAILED'

export type InvocationTerminationCause = 'TIMED_OUT' | 'CANCELLED' | 'SHUTDOWN'

export type {
  TemporaryResourceSweeper,
  UpdateOutcomeLike,
  UpdatePort,
  UpdateProgressReporter,
  RuntimeOptions,
} from './runtime-ports.js'
export type { RuntimeStatus, UpdateByteProgress } from './runtime-status.js'
export { detectArchitecture, detectEnvironmentInfo, defaultNote } from './runtime-environment.js'
export type { UploadedResource } from './runtime-resources.js'

const FALLBACK_FAILURE_MESSAGE = 'capability execution failed'
const DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 15_000
const DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS = 1_000
const DEFAULT_MAX_RECONNECT_DELAY_MILLIS = 30_000
const DEFAULT_MAX_CONCURRENT_INVOCATIONS = 64
const DEFAULT_IDE_COMMAND_TIMEOUT_MILLIS = 30_000
const DAEMON_VERSION_PATTERN = /^[0-9A-Za-z._+-]{1,64}$/

function toIdeAction(action: ProtoIdeAction): IdeCommand['action'] {
  switch (action) {
    case ProtoIdeAction.OPEN_VIEW:
      return 'OPEN_VIEW'
    case ProtoIdeAction.CHANGE_DIRECTORY:
      return 'CHANGE_DIRECTORY'
    case ProtoIdeAction.FOCUS_TERMINAL:
      return 'FOCUS_TERMINAL'
    case ProtoIdeAction.CLOSE_VIEW:
      return 'CLOSE_VIEW'
    case ProtoIdeAction.LIST_VIEWS:
      return 'LIST_VIEWS'
    case ProtoIdeAction.LIST_DIRECTORIES:
      return 'LIST_DIRECTORIES'
    case ProtoIdeAction.COLLAPSE_MOBILE:
      return 'COLLAPSE_MOBILE'
    default:
      throw new Error(`unsupported IDE action: ${action}`)
  }
}

function toIdeMode(mode: ProtoIdeOpenMode | undefined): IdeCommand['mode'] | undefined {
  if (mode === undefined || mode === ProtoIdeOpenMode.IDE_OPEN_MODE_UNSPECIFIED) {
    return undefined
  }
  switch (mode) {
    case ProtoIdeOpenMode.CREATE:
      return 'create'
    case ProtoIdeOpenMode.RESUME:
      return 'resume'
    default:
      throw new Error(`unsupported IDE open mode: ${mode}`)
  }
}

export class Runtime {
  private readonly config: DaemonConfig
  private readonly transport: ControlTransport
  private readonly capabilities: Record<string, CapabilityExecutor>
  private readonly processes: ProcessSupervisor
  private readonly environment: Parameters<typeof detectEnvironmentInfo>[0] extends never ? never : ReturnType<typeof detectEnvironmentInfo>
  private readonly daemonVersion: string
  private readonly journal: InvocationJournal
  private readonly resources: ReturnType<typeof createResourceRuntime>
  private readonly update: UpdatePort | null
  private readonly ide: IdeService | null
  private readonly ideLease: IdeLeaseHandler | null
  private readonly temporaryResources: TemporaryResourceSweeper | null
  private readonly heartbeatIntervalMillis: number
  private readonly initialReconnectDelayMillis: number
  private readonly maxReconnectDelayMillis: number
  private readonly maxConcurrentInvocations: number
  private readonly ideCommandTimeoutMillis: number
  private readonly monotonicNow: () => number
  private readonly runtimeStatus: RuntimeStatusTracker
  private readonly running = new Map<string, RunningInvocation>()
  private readonly ideInFlight = new Map<string, AbortController>()
  private readonly updateResults = new Map<string, UpdateReceipt>()
  /** 停机独立释放步骤与未收敛在途执行的失败：绝不阻断其余清理，由装配层显式聚合报告。 */
  private readonly shutdownFailures: unknown[] = []
  /** 所有已登记的在途执行（capability/IDE/update）；停机后必须全部收敛才发布完成信号。 */
  private readonly inFlight = new Set<Promise<void>>()
  /** 普通/恢复执行及其拥有的上传收尾；更新准入必须等它们全部结束。 */
  private readonly protectedExecutions = new Set<Promise<void>>()
  private activeUpdate: ActiveUpdate | null = null
  private activeIdeLeaseRound: ActiveIdeLeaseRound | null = null
  private ideLeaseRound = 0n

  private boundEnvironmentId: string | null = null
  private maxResourceBytes = 0
  private temporaryResourceTtlSeconds = 0
  private temporaryResourceCleanupIntervalSeconds = 0
  private scheduledCleanupIntervalSeconds = 0

  private activeConnection: ActiveConnection | null = null
  private connectionGeneration = 0
  private started = false
  private closed = false
  private connecting = false
  private reconnectScheduled = false
  private state: RuntimeState = 'STOPPED'
  private failureReason: string | null = null
  private nextReconnectDelay: number

  private heartbeatTimer: NodeJS.Timeout | null = null
  private reconnectTimer: NodeJS.Timeout | null = null
  private sweepTimer: NodeJS.Timeout | null = null
  private terminationResolve: (state: RuntimeState) => void = () => {}
  private readonly termination: Promise<RuntimeState>

  constructor(options: RuntimeOptions) {
    this.config = options.config
    this.transport = options.transport
    this.capabilities = options.capabilities
    this.processes = options.processes
    this.daemonVersion = options.daemonVersion ?? DAEMON_VERSION
    if (!DAEMON_VERSION_PATTERN.test(this.daemonVersion)) {
      throw new Error('daemonVersion must match [0-9A-Za-z._+-]{1,64}')
    }
    this.journal = options.journal ?? new InMemoryInvocationJournal()
    this.environment = options.environment ?? detectEnvironmentInfo(options.config)
    this.update = options.update ?? null
    this.ide = options.ide ?? null
    this.ideLease = options.ideLease ?? null
    this.temporaryResources = options.temporaryResources ?? null
    this.monotonicNow = options.monotonicNow ?? (() => performance.now())
    this.runtimeStatus = new RuntimeStatusTracker(this.daemonVersion)
    this.heartbeatIntervalMillis = requirePositive(
      options.heartbeatIntervalMillis ?? DEFAULT_HEARTBEAT_INTERVAL_MILLIS,
      'heartbeatIntervalMillis',
    )
    this.initialReconnectDelayMillis = requireNonNegative(
      options.initialReconnectDelayMillis ?? DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS,
      'initialReconnectDelayMillis',
    )
    this.maxReconnectDelayMillis = requirePositive(
      options.maxReconnectDelayMillis ?? DEFAULT_MAX_RECONNECT_DELAY_MILLIS,
      'maxReconnectDelayMillis',
    )
    if (this.initialReconnectDelayMillis > this.maxReconnectDelayMillis) {
      throw new Error('initialReconnectDelayMillis must not exceed maxReconnectDelayMillis')
    }
    this.maxConcurrentInvocations = requirePositive(
      options.maxConcurrentInvocations ?? DEFAULT_MAX_CONCURRENT_INVOCATIONS,
      'maxConcurrentInvocations',
    )
    this.ideCommandTimeoutMillis = requirePositive(
      options.ideCommandTimeoutMillis ?? DEFAULT_IDE_COMMAND_TIMEOUT_MILLIS,
      'ideCommandTimeoutMillis',
    )
    this.nextReconnectDelay = this.initialReconnectDelayMillis
    this.resources = createResourceRuntime({
      ...(options.resources === undefined ? {} : { resources: options.resources }),
      ...(options.putObject === undefined ? {} : { putObject: options.putObject }),
      sendControl: (envelope: Envelope) => this.sendTransferControl(envelope),
    })
    this.termination = new Promise<RuntimeState>((resolveTermination) => {
      this.terminationResolve = resolveTermination
    })
  }

  /** 启动连接生命周期并立即尝试建立连接；重复调用无副作用。 */
  start(): void {
    if (this.closed || this.started) {
      return
    }
    this.started = true
    this.state = 'DISCONNECTED'
    this.heartbeatTimer = setInterval(() => this.sendHeartbeat(), this.heartbeatIntervalMillis)
    this.scheduleReconnect(0)
  }

  /** 阻塞等待终止（由 close 或终态冲突触发）；返回终态。 */
  awaitTermination(): Promise<RuntimeState> {
    return this.termination
  }

  /** 停机后所有在途执行真正收敛的完成信号；与 awaitTermination 同时发布。 */
  async awaitCompletion(): Promise<void> {
    await this.termination
  }

  getState(): RuntimeState {
    return this.state
  }

  getFailureReason(): string | null {
    return this.failureReason
  }

  getBoundEnvironmentId(): string | null {
    return this.boundEnvironmentId
  }

  getJournal(): InvocationJournal {
    return this.journal
  }

  /** 当前完整运行状态快照；与 READY/HEARTBEAT/RUNTIME_STATUS 的 wire 结构一致。 */
  getRuntimeStatus(): RuntimeStatus {
    this.refreshUpdateProgress()
    return this.runtimeStatus.plainSnapshot()
  }

  /** 当前更新字节进度；无更新时 null。供更新器/运维适配器读取同一份事实，不产生历史。 */
  getUpdateProgress(): RuntimeStatusUpdate | null {
    this.refreshUpdateProgress()
    return this.runtimeStatus.getUpdate()
  }

  /** 主动停机；幂等。 */
  close(): void {
    this.shutdown('STOPPED', null)
  }

  // -------------------------------------------------------------------------
  // 连接生命周期
  // -------------------------------------------------------------------------

  private scheduleReconnect(delayMillis: number): boolean {
    if (!this.started || this.reconnectScheduled) {
      return this.started
    }
    this.reconnectScheduled = true
    this.reconnectTimer = setTimeout(() => {
      this.reconnectScheduled = false
      this.connect()
    }, Math.max(0, delayMillis))
    return true
  }

  private connect(): void {
    if (!this.started || this.connecting) {
      return
    }
    this.connecting = true
    this.state = 'CONNECTING'
    const generation = ++this.connectionGeneration
    const listener: ControlTransportListener = {
      onEnvelope: (envelope) => this.onEnvelope(generation, envelope),
      onUpgradeFailed: (statusCode, upgradeRequired) =>
        this.handleUpgradeFailed(generation, statusCode, upgradeRequired),
      onGoaway: () => this.handleGoaway(generation),
      onDisconnected: () => this.handleDisconnected(generation),
    }
    this.transport.connect(listener).then(
      (connection) => {
        this.connecting = false
        if (!this.started || generation !== this.connectionGeneration) {
          connection.close()
          return
        }
        const active = new ActiveConnection(generation, connection)
        this.activeConnection = active
        this.nextReconnectDelay = this.initialReconnectDelayMillis
        // 握手头已在 transport 建立时发送；等待服务端 POST /control 推送 SETTINGS 帧
      },
      () => {
        this.connecting = false
        this.handleDisconnected(generation)
      },
    )
  }

  private handleDisconnected(generation: number): void {
    const connection = this.activeConnection
    if (connection !== null) {
      if (connection.generation !== generation || this.activeConnection !== connection) {
        return
      }
      this.activeConnection = null
    } else if (this.state !== 'CONNECTING') {
      return
    }
    if (!this.started || generation !== this.connectionGeneration) {
      return
    }
    // 连接失效不影响 Invocation journal，也绝不取消在途 Agent 执行；仅重置绑定与资源预算。
    this.resources.onConnectionLost()
    this.maxResourceBytes = 0
    this.boundEnvironmentId = null
    this.state = 'DISCONNECTED'
    this.activeIdeLeaseRound = null
    connection?.abort.abort('CONTROL_DISCONNECTED')
    // IDE 请求绑定于控制代际：连接失效即中止在途 IDE 请求，绝不跨代际重放副作用。
    for (const controller of this.ideInFlight.values()) {
      controller.abort('CONTROL_DISCONNECTED')
    }
    this.ideInFlight.clear()
    const delay = this.nextReconnectDelay
    this.nextReconnectDelay = Math.min(this.nextReconnectDelay * 2, this.maxReconnectDelayMillis)
    this.scheduleReconnect(delay)
  }

  private handleGoaway(generation: number): void {
    if (!this.started || generation !== this.connectionGeneration) {
      return
    }
    this.nextReconnectDelay = 0
    this.scheduleReconnect(0)
  }

  private handleUpgradeFailed(
    generation: number,
    statusCode: number,
    upgradeRequired?: UpgradeRequired,
  ): void {
    if (!this.started || generation !== this.connectionGeneration) {
      return
    }
    if (statusCode === 401) {
      this.failTerminal('environment registration is rejected')
      return
    }
    if (statusCode === 426) {
      const target = upgradeRequired?.artifactUrl ? parseReleaseArtifactUrl(upgradeRequired.artifactUrl) : null
      if (target !== null && upgradeRequired?.artifactSha256 && this.update !== null) {
        const operationId = randomUUID()
        const commandKey = JSON.stringify([target.targetVersion])
        const updateCommand = create(EnvelopeSchema, {
          messageType: MessageType.UPDATE,
          environmentId: this.boundEnvironmentId ?? undefined,
          payload: {
            case: 'update',
            value: {
              $typeName: 'kkstudio.environment.control.Update',
              operationId,
              targetVersion: target.targetVersion,
            },
          },
        })
        const active: ActiveUpdate = {
          operationId,
          commandKey,
          targetVersion: target.targetVersion,
          controller: new AbortController(),
          receipt: {
            commandKey,
            result: create(UpdateResultSchema, {
              operationId,
              phase: 1, // UPDATE_ACCEPTED
            }),
          },
          prepared: false,
        }
        this.activeUpdate = active
        this.runtimeStatus.beginUpdate(target.targetVersion)
        void this.track(
          (async () => {
            await this.executeUpdate(active, updateCommand.payload.value as Update)
            if (!active.prepared) {
              this.failTerminal('daemon requires protocol upgrade')
            }
          })(),
        )
        return
      }
      this.failTerminal('daemon requires protocol upgrade')
      return
    }
    this.handleDisconnected(generation)
  }

  private closeFailedHandshake(connection: ActiveConnection): void {
    try {
      connection.connection.close()
    } catch {
      // 关闭失败仍要走代际断开，避免停在 CONNECTING。
    }
    this.handleDisconnected(connection.generation)
  }

  private sendReady(connection: ActiveConnection): boolean {
    const readyEnvelope = create(EnvelopeSchema, {
      messageType: MessageType.READY,
      environmentId: this.boundEnvironmentId ?? undefined,
      payload: {
        case: 'ready',
        value: create(ReadySchema, {
          daemonVersion: this.daemonVersion,
          environment: this.environment,
          runtimeStatus: this.currentRuntimeStatus(),
        }),
      },
    })
    return this.sendOn(connection, readyEnvelope)
  }

  private sendHeartbeat(): void {
    if (this.state !== 'READY') {
      return
    }
    const runtimeStatus = this.currentRuntimeStatus()
    let ideLeaseRound: bigint | undefined = undefined
    if (this.ideLease !== null && this.ideLease.hasActiveViews()) {
      this.ideLeaseRound++
      ideLeaseRound = this.ideLeaseRound
    }
    const heartbeatEnvelope = create(EnvelopeSchema, {
      messageType: MessageType.HEARTBEAT,
      environmentId: this.boundEnvironmentId ?? undefined,
      payload: {
        case: 'heartbeat',
        value: create(HeartbeatSchema, {
          runtimeStatus,
          ideLeaseRound,
        }),
      },
    })
    const sent = this.send(heartbeatEnvelope)
    this.activeIdeLeaseRound =
      sent && ideLeaseRound !== undefined
        ? { round: ideLeaseRound, sentAt: this.monotonicNow(), consumed: false }
        : null
  }

  private send(envelope: Envelope): boolean {
    const connection = this.activeConnection
    return connection !== null && this.sendOn(connection, envelope)
  }

  private sendOn(connection: ActiveConnection, envelope: Envelope): boolean {
    if (this.activeConnection !== connection || !connection.connection.isOpen()) {
      return false
    }
    validate(envelope)
    try {
      return connection.connection.send(envelope)
    } catch {
      this.handleDisconnected(connection.generation)
      return false
    }
  }

  // -------------------------------------------------------------------------
  // 入站消息
  // -------------------------------------------------------------------------

  private onEnvelope(generation: number, envelope: Envelope): void {
    const connection = this.activeConnection
    if (connection === null || connection.generation !== generation) {
      return
    }
    try {
      this.verifyScope(envelope)
      switch (envelope.messageType) {
        case MessageType.INVOKE:
          this.handleInvoke(connection, envelope)
          return
        case MessageType.CANCEL:
          this.handleCancel(envelope)
          return
        case MessageType.RECOVER:
          this.handleRecover(envelope)
          return
        case MessageType.SETTINGS:
          this.handleSettings(connection, envelope)
          return
        case MessageType.TEMPORARY_RESOURCE_POLICY:
          this.handleTemporaryResourcePolicy(envelope)
          return
        case MessageType.ERROR:
          this.handleError(connection, envelope)
          return
        case MessageType.UPDATE:
          this.handleUpdate(envelope)
          return
        case MessageType.IDE_LEASE:
          this.handleIdeLease(envelope)
          return
        case MessageType.RESOURCE_UPLOAD_TICKET:
          this.handleTicket(envelope)
          return
        case MessageType.IDE_COMMAND:
          this.handleIdeCommand(envelope)
          return
        default:
          throw new Error(`daemon must not receive ${envelope.messageType} from server`)
      }
    } catch (error) {
      const errorEnvironmentId = this.boundEnvironmentId ?? envelope.environmentId ?? undefined
      if (errorEnvironmentId !== undefined) {
        this.sendOn(
          connection,
          create(EnvelopeSchema, {
            messageType: MessageType.ERROR,
            environmentId: errorEnvironmentId,
            payload: {
              case: 'error',
              value: create(ErrorSchema, { message: safeMessage(error) }),
            },
          }),
        )
      }
      // 初始 SETTINGS 失败时服务端尚未将会话标为 READY，不能依赖在线变更通知修复。
      // 关闭握手并重连，下一次从平台重新读取设置；已 READY 的连接仍可接收后续修正。
      if (envelope.messageType === MessageType.SETTINGS && !connection.ready && this.activeConnection === connection) {
        this.closeFailedHandshake(connection)
      }
    }
  }

  private verifyScope(envelope: Envelope): void {
    if (envelope.messageType === MessageType.SETTINGS) {
      if (!envelope.environmentId) {
        throw new Error('SETTINGS must declare non-null environmentId')
      }
      if (this.boundEnvironmentId !== null && this.boundEnvironmentId !== envelope.environmentId) {
        throw new Error('SETTINGS environmentId does not match daemon')
      }
      return
    }
    const bound = this.boundEnvironmentId
    if (bound !== null && envelope.environmentId && bound !== envelope.environmentId) {
      throw new Error(`envelope environmentId does not match daemon: ${envelope.environmentId}`)
    }
  }

  private handleSettings(connection: ActiveConnection, envelope: Envelope): void {
    if (!envelope.environmentId) {
      throw new Error('SETTINGS must declare non-null environmentId')
    }
    const settings = envelope.payload.value as Settings
    if (settings.noProxyHosts === undefined) {
      throw new Error('settings.noProxyHosts is required')
    }
    applyNetworkSettings(settings.proxyUrl ?? null, settings.noProxyHosts)
    if (connection.ready) {
      return
    }
    if (!connection.markSettingsReceived()) {
      throw new Error('SETTINGS handshake already started')
    }
    this.boundEnvironmentId = envelope.environmentId
    const budgets = settings.resourceBudgets!
    this.maxResourceBytes = Number(budgets.maxResourceBytes)
    const ttl = Number(budgets.temporaryResourceTtlSeconds)
    const cleanup = Number(budgets.temporaryResourceCleanupIntervalSeconds)
    this.applyTemporaryResourcePolicy(ttl, cleanup)
    if (!this.sendReady(connection)) {
      if (this.activeConnection === connection) {
        this.closeFailedHandshake(connection)
      }
      return
    }
    if (this.activeConnection === connection) {
      connection.ready = true
      this.state = 'READY'
      this.resources.onConnectionReady()
      this.resendUpdateResults()
    }
  }

  private handleTemporaryResourcePolicy(envelope: Envelope): void {
    if (this.state !== 'READY' || !envelope.environmentId) {
      throw new Error('TEMPORARY_RESOURCE_POLICY requires a READY bound connection')
    }
    const policy = envelope.payload.value as TemporaryResourcePolicy
    const ttl = Number(policy.temporaryResourceTtlSeconds)
    const cleanup = Number(policy.temporaryResourceCleanupIntervalSeconds)
    this.applyTemporaryResourcePolicy(ttl, cleanup)
  }

  private applyTemporaryResourcePolicy(ttlSeconds: number, cleanupIntervalSeconds: number): void {
    this.temporaryResourceTtlSeconds = ttlSeconds
    this.temporaryResourceCleanupIntervalSeconds = cleanupIntervalSeconds
    if (this.temporaryResources === null) {
      return
    }
    if (this.sweepTimer !== null && this.scheduledCleanupIntervalSeconds === cleanupIntervalSeconds) {
      return
    }
    this.scheduledCleanupIntervalSeconds = cleanupIntervalSeconds
    if (this.sweepTimer !== null) {
      clearInterval(this.sweepTimer)
      this.sweepTimer = null
    }
    this.sweepTimer = setInterval(() => {
      const ttl = this.temporaryResourceTtlSeconds
      if (ttl <= 0 || this.temporaryResources === null) {
        return
      }
      void this.temporaryResources.sweep(ttl).catch(() => {
        // 清扫失败不影响连接状态。
      })
    }, cleanupIntervalSeconds * 1000)
  }

  private handleError(connection: ActiveConnection, _envelope: Envelope): void {
    // 服务端发送的 ERROR 帧触发控制连接断开重连
    this.handleDisconnected(connection.generation)
  }

  private handleTicket(envelope: Envelope): void {
    const invocationId = requireInvocationId(envelope)
    const ticket = envelope.payload.value as ResourceUploadTicket
    this.resources.onTicket(invocationId, ticket)
  }

  // -------------------------------------------------------------------------
  // Agent invocation
  // -------------------------------------------------------------------------

  private handleInvoke(connection: ActiveConnection, envelope: Envelope): void {
    if (!this.started) {
      return
    }
    const invocationId = requireInvocationId(envelope)
    const invoke = envelope.payload.value as Invoke
    const start = this.journal.start(invocationId)
    if (!start.created) {
      this.replay(start.entry)
      return
    }
    try {
      if (this.updateAdmissionHeld()) {
        throw new Error('environment update admission is held')
      }
      const capabilityId = invoke.capabilityId!
      if (invoke.expectedFile !== undefined && capabilityId !== 'fs.edit') {
        throw new Error('expectedFile is only valid for fs.edit')
      }
      const executor = this.capabilities[capabilityId]
      if (executor === undefined) {
        throw new Error(`unknown Environment capability: ${capabilityId}`)
      }
      if (this.running.size >= this.maxConcurrentInvocations) {
        throw new Error('too many concurrent capability invocations')
      }
      const parsedArgs = JSON.parse(invoke.argumentsJson!)
      if (typeof parsedArgs !== 'object' || parsedArgs === null || Array.isArray(parsedArgs)) {
        throw new Error('arguments must be a JSON object')
      }
      const callArguments = parsedArgs as Record<string, unknown>

      const controller = new AbortController()
      const invocation = new RunningInvocation(invocationId, controller, this.maxResourceBytes)
      this.running.set(invocationId, invocation)
      this.sendStarted(connection, invocationId)
      const timeoutMillis = Number(invoke.timeoutMillis)
      this.scheduleTimeout(invocation, timeoutMillis)

      const expectedFile =
        invoke.expectedFile !== undefined
          ? { path: invoke.expectedFile.path!, sha256: invoke.expectedFile.sha256! }
          : undefined

      const call: CapabilityCall = {
        invocationId,
        capabilityId,
        arguments: callArguments,
        timeoutMillis,
        expectedFile,
      }
      void this.trackProtected(this.execute(invocation, executor, call))
    } catch (error) {
      this.confirmRejectedBeforeExecution(invocationId)
      this.terminal(
        invocationId,
        MessageType.FAILED,
        this.buildFailedPayload(safeMessage(error)),
        null,
      )
    }
  }

  private sendStarted(connection: ActiveConnection, invocationId: string): void {
    if (this.boundEnvironmentId === null) {
      return
    }
    this.sendOn(
      connection,
      create(EnvelopeSchema, {
        messageType: MessageType.STARTED,
        environmentId: this.boundEnvironmentId,
        invocationId,
        payload: { case: 'started', value: create(StartedSchema, {}) },
      }),
    )
  }

  /** 执行器确定未启动时记录并发送退出确认；重复确认由 journal 吸收。 */
  private confirmRejectedBeforeExecution(invocationId: string): void {
    if (this.journal.confirmExit(invocationId)) {
      this.sendExecutionExited(invocationId)
    }
  }

  private sendExecutionExited(invocationId: string): void {
    if (this.boundEnvironmentId === null) {
      return
    }
    this.send(
      create(EnvelopeSchema, {
        messageType: MessageType.EXECUTION_EXITED,
        environmentId: this.boundEnvironmentId,
        invocationId,
        payload: { case: 'executionExited', value: create(ExecutionExitedSchema, {}) },
      }),
    )
  }

  private async execute(
    invocation: RunningInvocation,
    executor: CapabilityExecutor,
    call: CapabilityCall,
  ): Promise<void> {
    try {
      const result = await executor(call, this.capabilityContext(invocation))
      invocation.markExecutorReturned()
      // 上传和终态仍是本次拥有的收尾，完成前不能发送退出确认。
      if (invocation.terminalClaimed) {
        invocation.finishOwnedCleanup()
        return
      }
      if (result.callId !== invocation.invocationId) {
        this.terminal(
          invocation.invocationId,
          MessageType.FAILED,
          this.buildFailedPayload('capability result callId does not match invocationId'),
          null,
        )
        invocation.finishOwnedCleanup()
        return
      }
      const completed = await encodeCompletedProto(result, invocation.maxResourceBytes, (content) => {
        const resources = this.resources.uploader
        if (resources === null) {
          throw new Error('resource upload is not configured')
        }
        return resources.upload(invocation.invocationId, content, invocation.controller.signal)
      })
      this.terminal(
        invocation.invocationId,
        MessageType.COMPLETED,
        { case: 'completed', value: completed },
        null,
      )
      invocation.finishOwnedCleanup()
    } catch (error) {
      const executorFailed = !invocation.executorReturned
      if (invocation.terminalClaimed) {
        if (executorFailed) {
          invocation.finishOwnedCleanup()
        }
        return
      }
      const reason = invocation.controller.signal.reason
      if (reason === 'CANCELLED' || reason === 'SHUTDOWN') {
        const cancelledReason = reason === 'SHUTDOWN' ? 'daemon shutdown' : 'cancelled'
        this.terminal(
          invocation.invocationId,
          MessageType.CANCELLED,
          {
            case: 'cancelled',
            value: create(CancelledSchema, { reason: cancelledReason }),
          },
          reason,
        )
        if (executorFailed) {
          invocation.finishOwnedCleanup()
        }
        return
      }
      if (reason === 'TIMED_OUT') {
        this.terminal(
          invocation.invocationId,
          MessageType.FAILED,
          this.buildFailedPayload(`capability execution timed out after ${call.timeoutMillis}ms`),
          'TIMED_OUT',
        )
        if (executorFailed) {
          invocation.finishOwnedCleanup()
        }
        return
      }
      this.terminal(
        invocation.invocationId,
        MessageType.FAILED,
        this.buildFailedPayload(safeMessage(error)),
        null,
      )
      // executor 抛错仍可能已有正向范围证据；后续上传/收尾抛错则不能确认清理完成。
      if (executorFailed) {
        invocation.finishOwnedCleanup()
      }
    }
  }

  private capabilityContext(invocation: RunningInvocation): CapabilityContext {
    const resources = this.resources.uploader
    return {
      config: this.config,
      signal: invocation.controller.signal,
      processes: this.processes,
      resources: {
        upload: (invocationId, content, signal) => {
          if (resources === null) {
            return Promise.reject(new Error('resource upload is not configured'))
          }
          return resources.upload(invocationId, content, signal)
        },
      },
      progress: async (result: CapabilityResult) => {
        this.progress(invocation, result)
      },
      confirmExecutionExit: () => {
        // 只记录证据。发送必须等 executor 与本次拥有的收尾都结束，不能在 body 内提前 ACK。
        invocation.noteExitEvidence((current) => this.publishExecutionExit(current))
      },
    }
  }

  /**
   * executor 与本次拥有的收尾都结束后，才把已记录的正向证据写入 journal 并发送一次。
   *
   * <p>终态会把 invocation 移出 running，因此这里持有对象本身，不回查 map。
   * 尚未开始执行器的确定拒绝由调用方直接确认；已经开始但没有正向证据时这里不补发。
   * 结果终态、超时和取消本身都不是退出证据。
   */
  private publishExecutionExit(invocation: RunningInvocation): void {
    if (!invocation.executorFinished || !invocation.exitConfirmed || invocation.exitPublished) {
      return
    }
    invocation.exitPublished = true
    if (!this.journal.confirmExit(invocation.invocationId)) {
      return
    }
    this.sendExecutionExited(invocation.invocationId)
  }

  private progress(invocation: RunningInvocation, result: CapabilityResult): void {
    if (invocation.terminalClaimed || !this.isRunning(invocation.invocationId) || this.boundEnvironmentId === null) {
      return
    }
    try {
      const progress = encodeProgressProto(result)
      this.send(
        create(EnvelopeSchema, {
          messageType: MessageType.PROGRESS,
          environmentId: this.boundEnvironmentId,
          invocationId: invocation.invocationId,
          payload: { case: 'progress', value: progress },
        }),
      )
    } catch (error) {
      // 对外保留 FAILED 诊断，但封存失败的同时必须把停止意图交给仍持有执行体的调用。
      this.terminal(
        invocation.invocationId,
        MessageType.FAILED,
        this.buildFailedPayload(`cannot encode partial capability result: ${safeMessage(error)}`),
        'CANCELLED',
      )
    }
  }

  private handleCancel(envelope: Envelope): void {
    const invocationId = requireInvocationId(envelope)
    const entry = this.journal.find(invocationId)
    if (entry === undefined) {
      return
    }
    if (isTerminalState(entry.state)) {
      this.replay(entry)
      return
    }
    const invocation = this.running.get(invocationId)
    if (invocation !== undefined) {
      invocation.controller.abort('CANCELLED')
    }
  }

  /**
   * 同实例重连后的取回：只重放 journal 已有的事实，绝不终止执行。运行中的调用回放 STARTED 并继续执行，结果经当前连接交付；
   * 已终结的调用重放终态与独立退出确认；从未收到的调用保持静默。
   */
  private handleRecover(envelope: Envelope): void {
    const invocationId = requireInvocationId(envelope)
    const entry = this.journal.find(invocationId)
    if (entry !== undefined) {
      this.replay(entry)
    } else {
      if (this.boundEnvironmentId !== null) {
        this.send(
          create(EnvelopeSchema, {
            messageType: MessageType.RECOVER_NOT_FOUND,
            environmentId: this.boundEnvironmentId,
            invocationId,
            payload: { case: 'recoverNotFound', value: create(RecoverNotFoundSchema, {}) },
          }),
        )
      }
    }
  }

  private replay(entry: JournalEntry): void {
    if (entry.state === 'RUNNING') {
      if (this.boundEnvironmentId !== null) {
        this.send(
          create(EnvelopeSchema, {
            messageType: MessageType.STARTED,
            environmentId: this.boundEnvironmentId,
            invocationId: entry.invocationId,
            payload: { case: 'started', value: create(StartedSchema, { replayed: true }) },
          }),
        )
      }
    } else {
      const terminal = entry.terminalMessage
      if (terminal !== null) {
        this.sendTerminal(entry.invocationId, terminal)
      }
    }
    if (entry.exitConfirmed) {
      this.sendExecutionExited(entry.invocationId)
    }
  }

  /**
   * 原子终态仲裁与结果派发：journal 的 RUNNING→终态跃迁是唯一提交点，只有跃迁成功者发送终态报文。
   */
  private terminal(
    invocationId: string,
    messageType: TerminalMessage['messageType'],
    payload: TerminalPayload,
    cause: InvocationTerminationCause | null,
  ): void {
    const invocation = this.running.get(invocationId)
    if (invocation !== undefined) {
      if (!invocation.claimTerminal()) {
        return
      }
      invocation.clearDeadline()
      if (cause !== null) {
        invocation.controller.abort(cause)
      }
    }
    const terminal: TerminalMessage = { messageType, payload }
    if (!this.journal.complete(invocationId, terminal)) {
      return
    }
    if (invocation !== undefined) {
      this.running.delete(invocationId)
    }
    this.sendTerminal(invocationId, terminal)
  }

  private sendTerminal(invocationId: string, terminal: TerminalMessage): boolean {
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

  private scheduleTimeout(invocation: RunningInvocation, timeoutMillis: number): void {
    if (!this.isRunning(invocation.invocationId) || invocation.terminalClaimed) {
      return
    }
    // timeout 为 0 表示「没有 deadline」：绝不调度一个立即触发的定时终态。
    if (timeoutMillis === 0) {
      return
    }
    invocation.deadlineTimer = setTimeout(() => {
      if (!this.isRunning(invocation.invocationId) || invocation.terminalClaimed) {
        return
      }
      invocation.controller.abort('TIMED_OUT')
    }, timeoutMillis)
  }

  private isRunning(invocationId: string): boolean {
    const entry = this.journal.find(invocationId)
    return entry !== undefined && entry.state === 'RUNNING'
  }

  // -------------------------------------------------------------------------
  // 资源上传控制面
  // -------------------------------------------------------------------------

  private sendTransferControl(envelope: Envelope): boolean {
    if (this.state !== 'READY' || this.boundEnvironmentId === null) {
      return false
    }
    const invocationId = envelope.invocationId ?? ''
    const invocation = this.running.get(invocationId)
    if (invocation === undefined) {
      return false
    }
    const outbound = create(EnvelopeSchema, {
      ...envelope,
      environmentId: this.boundEnvironmentId,
    })
    return invocation.trySendControl(
      () => this.isRunning(invocationId) && this.send(outbound),
    )
  }

  // -------------------------------------------------------------------------
  // 受管更新
  // -------------------------------------------------------------------------

  private handleUpdate(envelope: Envelope): void {
    if (this.state !== 'READY' || !envelope.environmentId) {
      throw new Error('UPDATE requires a READY bound connection')
    }
    const command = envelope.payload.value as Update
    const decision = decideUpdate({
      command,
      active: this.activeUpdate,
      cached: this.updateResults.get(command.operationId ?? ''),
      currentVersion: this.daemonVersion,
      busyReason: this.updateBusyReason(),
      configured: this.update !== null,
    })
    if (decision.kind === 'status') {
      this.publishRuntimeStatus()
      return
    }
    if (decision.kind === 'replay-active') {
      if (decision.active.prepared) {
        this.deliverUpdateResult(decision.active.receipt)
      } else {
        this.publishRuntimeStatus()
      }
      return
    }
    if (decision.kind === 'replay-cached') {
      this.deliverUpdateResult(decision.receipt)
      this.publishRuntimeStatus()
      return
    }
    if (decision.kind === 'reject') {
      this.rememberUpdateResult(command.operationId ?? '', decision.receipt)
      this.deliverUpdateResult(decision.receipt)
      return
    }
    this.activeUpdate = decision.active
    this.runtimeStatus.beginUpdate(command.targetVersion ?? '')
    this.publishRuntimeStatus()
    this.sendUpdateResult(decision.active.receipt.result)
    void this.track(this.executeUpdate(decision.active, decision.command))
  }

  private async executeUpdate(active: ActiveUpdate, command: Update): Promise<void> {
    const update = this.update
    const attempt =
      update === null
        ? { failure: new Error('managed update is not configured'), prepared: null }
        : await executeUpdateAttempt(
            active,
            command,
            update,
            this.ide,
            this.config,
            (progress) => this.reportUpdateProgress(active, progress),
          )
    active.controller = null
    if (attempt.failure !== null || attempt.prepared === null) {
      if (this.activeUpdate === active) {
        this.activeUpdate = null
      }
      this.runtimeStatus.clearUpdate()
      this.publishRuntimeStatus()
      const receipt = executionFailureReceipt(active, command, attempt.failure)
      this.rememberUpdateResult(command.operationId ?? '', receipt)
      this.deliverUpdateResult(receipt)
      return
    }
    this.refreshUpdateProgress()
    const receipt = preparedReceipt(active, command, attempt.prepared)
    active.receipt = receipt
    active.prepared = true
    this.runtimeStatus.beginUpdate(command.targetVersion ?? '')
    this.publishRuntimeStatus()
    this.rememberUpdateResult(command.operationId ?? '', receipt)
    const stopWatching = attempt.prepared.watchFailure?.((message) => {
      if (this.activeUpdate !== active) {
        return
      }
      active.stopWatching?.()
      this.activeUpdate = null
      this.runtimeStatus.clearUpdate()
      this.publishRuntimeStatus()
      const failed = executionFailureReceipt(active, command, new Error(message))
      this.rememberUpdateResult(command.operationId ?? '', failed)
      this.deliverUpdateResult(failed)
    })
    if (stopWatching !== undefined) {
      active.stopWatching = stopWatching
    }
    if (this.activeUpdate !== active) {
      stopWatching?.()
      return
    }
    this.deliverUpdateResult(receipt)
  }

  private reportUpdateProgress(active: ActiveUpdate, progress: UpdateByteProgress): void {
    if (this.activeUpdate !== active) {
      return
    }
    this.runtimeStatus.reportProgress(progress)
  }

  private refreshUpdateProgress(): void {
    const active = this.activeUpdate
    const getProgress = this.update?.getProgress
    if (active === null || getProgress === undefined) {
      return
    }
    try {
      const progress = getProgress.call(this.update)
      if (progress !== null) {
        this.reportUpdateProgress(active, progress)
      }
    } catch (error) {
      this.collectDiagnosticFailure('update progress', error)
    }
  }

  private currentRuntimeStatus(): RuntimeSnapshot {
    this.refreshUpdateProgress()
    return this.runtimeStatus.snapshot()
  }

  private publishRuntimeStatus(): void {
    if (this.state === 'READY' && this.boundEnvironmentId !== null) {
      this.send(
        create(EnvelopeSchema, {
          messageType: MessageType.RUNTIME_STATUS,
          environmentId: this.boundEnvironmentId,
          payload: {
            case: 'runtimeStatus',
            value: create(RuntimeStatusSchema, {
              runtimeStatus: this.currentRuntimeStatus(),
            }),
          },
        }),
      )
    }
  }

  private updateAdmissionHeld(): boolean {
    return this.activeUpdate !== null
  }

  private updateBusyReason(): string | null {
    if (this.running.size > 0 || this.protectedExecutions.size > 0) {
      return 'protected execution is still running'
    }
    if (this.ideInFlight.size > 0) {
      return 'IDE command is in progress'
    }
    if (this.ide !== null && !this.ide.canStartUpdate()) {
      return 'IDE views, cleanup or data streams are active'
    }
    return null
  }

  private rememberUpdateResult(operationId: string, receipt: UpdateReceipt): void {
    rememberUpdateResult(this.updateResults, operationId, receipt)
  }

  private resendUpdateResults(): void {
    const receipt = receiptToResend(this.activeUpdate, this.updateResults)
    if (receipt !== null) {
      this.deliverUpdateResult(receipt)
    }
  }

  private deliverUpdateResult(receipt: UpdateReceipt): void {
    this.sendUpdateResult(receipt.result)
    const releasePermit = receipt.releasePermit
    if (releasePermit === undefined) {
      return
    }
    void this.track(
      releasePermit().catch((error: unknown) => {
        this.collectDiagnosticFailure('update permit release', error)
      }),
    )
  }

  private sendUpdateResult(result: UpdateResult): void {
    if (this.state === 'READY' && this.boundEnvironmentId !== null) {
      this.send(
        create(EnvelopeSchema, {
          messageType: MessageType.UPDATE_RESULT,
          environmentId: this.boundEnvironmentId,
          payload: { case: 'updateResult', value: result },
        }),
      )
    }
  }

  // -------------------------------------------------------------------------
  // IDE 使用证明扩展点
  // -------------------------------------------------------------------------

  private handleIdeLease(envelope: Envelope): void {
    if (this.state !== 'READY' || !envelope.environmentId) {
      throw new Error('IDE_LEASE requires a READY bound connection')
    }
    if (this.updateAdmissionHeld()) {
      throw new Error('IDE_LEASE is not allowed while environment update admission is held')
    }
    const lease = envelope.payload.value as ProtoIdeLease
    if (this.ideLease === null) {
      throw new Error('IDE_LEASE handler is not configured')
    }
    const active = requireFreshIdeLease(this.activeIdeLeaseRound, lease.round ?? 0n, this.monotonicNow())
    active.consumed = true
    const plainLease = {
      round: String(lease.round ?? 0n),
      bindings: (lease.bindings ?? []).map((b) => ({
        viewId: b.viewId,
        documentId: b.documentId,
        bindingEpoch: String(b.bindingEpoch ?? 0n),
      })),
    }
    this.runExtensionHandler('IDE_LEASE', () =>
      this.ideLease?.apply(
        plainLease as any,
        active.sentAt,
      ),
    )
  }

  private runExtensionHandler(phase: string, action: () => Promise<void> | void): void {
    const result = action()
    if (result === undefined) {
      return
    }
    void this.track(
      Promise.resolve(result).catch((error: unknown) => {
        this.sendExtensionError(phase, error)
      }),
    )
  }

  private sendExtensionError(phase: string, error: unknown): void {
    if (this.state === 'READY' && this.boundEnvironmentId !== null) {
      this.send(
        create(EnvelopeSchema, {
          messageType: MessageType.ERROR,
          environmentId: this.boundEnvironmentId,
          payload: {
            case: 'error',
            value: create(ErrorSchema, { message: `${phase} handler failed: ${safeMessage(error)}` }),
          },
        }),
      )
    }
  }

  // -------------------------------------------------------------------------
  // IDE 请求（管理动作有独立 deadline；不进入 Agent journal，
  // 断线/停机中止而不跨代际重放副作用）
  // -------------------------------------------------------------------------

  private handleIdeCommand(envelope: Envelope): void {
    if (this.state !== 'READY' || !envelope.environmentId) {
      throw new Error('IDE_COMMAND requires a READY bound connection')
    }
    const invocationId = requireInvocationId(envelope)
    const protoCmd = envelope.payload.value as ProtoIdeCommand
    const command: IdeCommand = {
      action: toIdeAction(protoCmd.action),
      viewId: protoCmd.viewId ?? undefined,
      documentId: protoCmd.documentId ?? undefined,
      eventNodeId: protoCmd.eventNodeId ?? undefined,
      connectionId: protoCmd.connectionId ?? undefined,
      claimId: protoCmd.claimId ?? undefined,
      bindingEpoch: protoCmd.bindingEpoch !== undefined ? protoCmd.bindingEpoch.toString() : undefined,
      mode: toIdeMode(protoCmd.mode),
      directory: protoCmd.directory ?? undefined,
      discard: protoCmd.discard ?? undefined,
    }
    const admission = admitIdeCommand({
      invocationId,
      command,
      inFlight: this.ideInFlight,
      updateHeld: this.updateAdmissionHeld(),
      ide: this.ide,
    })
    if (admission.kind === 'ignore') {
      return
    }
    if (admission.kind === 'reply') {
      this.sendIdeResult(invocationId, admission.reply)
      return
    }
    const controller = admission.controller
    this.ideInFlight.set(invocationId, controller)
    // Native close waits for a person; disconnect and shutdown still abort the command.
    const timer = command.action === 'CLOSE_VIEW' && command.discard === false
      ? null
      : setTimeout(() => controller.abort('IDE_COMMAND_TIMEOUT'), this.ideCommandTimeoutMillis)
    void this.track(this.runIdeCommand(invocationId, command, controller, timer))
  }

  private async runIdeCommand(
    invocationId: string,
    command: IdeCommand,
    controller: AbortController,
    timer: NodeJS.Timeout | null,
  ): Promise<void> {
    try {
      const reply = await (this.ide as IdeService).handle(command, controller.signal)
      if (this.ideInFlight.get(invocationId) === controller) {
        this.sendIdeResult(invocationId, toProtoIdeResult(reply))
      }
    } catch (error) {
      if (this.ideInFlight.get(invocationId) === controller) {
        this.sendIdeResult(invocationId, ideFailureReply(error, safeMessage(error)))
      }
    } finally {
      if (timer !== null) {
        clearTimeout(timer)
      }
      if (this.ideInFlight.get(invocationId) === controller) {
        this.ideInFlight.delete(invocationId)
      }
    }
  }

  private sendIdeResult(invocationId: string, reply: IdeResult): void {
    if (this.boundEnvironmentId === null) {
      return
    }
    this.send(
      create(EnvelopeSchema, {
        messageType: MessageType.IDE_RESULT,
        environmentId: this.boundEnvironmentId,
        invocationId,
        payload: { case: 'ideResult', value: reply },
      }),
    )
  }

  // -------------------------------------------------------------------------
  // 停机
  // -------------------------------------------------------------------------

  private failTerminal(reason: string): void {
    this.shutdown('FAILED', reason)
  }

  private shutdown(terminalState: RuntimeState, reason: string | null): void {
    if (this.closed) {
      return
    }
    this.closed = true
    this.started = false
    this.state = terminalState
    this.failureReason = reason
    const connection = this.activeConnection
    this.activeConnection = null
    if (this.heartbeatTimer !== null) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.sweepTimer !== null) {
      clearInterval(this.sweepTimer)
      this.sweepTimer = null
    }
    this.release('resource transfer client close', () => this.resources.close())
    if (connection !== null) {
      connection.abort.abort('SHUTDOWN')
      this.release('connection close', () => connection.connection.close())
    }
    this.activeIdeLeaseRound = null
    for (const invocation of this.running.values()) {
      invocation.claimTerminal()
      invocation.controller.abort('SHUTDOWN')
      invocation.clearDeadline()
      this.release('invocation journal complete', () => {
        this.journal.complete(invocation.invocationId, {
          messageType: MessageType.CANCELLED,
          payload: {
            case: 'cancelled',
            value: create(CancelledSchema, { reason: 'daemon shutdown' }),
          },
        })
      })
    }
    this.running.clear()
    for (const controller of this.ideInFlight.values()) {
      controller.abort('SHUTDOWN')
    }
    this.ideInFlight.clear()
    this.activeUpdate?.controller?.abort('SHUTDOWN')
    if (this.activeUpdate !== null) {
      this.activeUpdate.stopWatching?.()
      this.activeUpdate.controller = null
    }
    void this.finalize(terminalState)
  }

  private release(phase: string, action: () => void): void {
    releaseStep(this.shutdownFailures, phase, action)
  }

  private recordShutdownFailure(phase: string, error: unknown): void {
    recordShutdownFailure(this.shutdownFailures, phase, error)
  }

  private collectDiagnosticFailure(phase: string, error: unknown): void {
    collectDiagnosticFailure(this.shutdownFailures, phase, error)
  }

  private track(promise: Promise<void>): Promise<void> {
    return this.trackIn(null, promise)
  }

  private trackProtected(promise: Promise<void>): Promise<void> {
    return this.trackIn(this.protectedExecutions, promise)
  }

  private trackIn(group: Set<Promise<void>> | null, promise: Promise<void>): Promise<void> {
    this.inFlight.add(promise)
    group?.add(promise)
    const remove = (): void => {
      this.inFlight.delete(promise)
      group?.delete(promise)
    }
    promise.then(remove, remove)
    return promise
  }

  private async finalize(terminalState: RuntimeState): Promise<void> {
    try {
      await this.transport.close()
    } catch (error) {
      this.recordShutdownFailure('transport close', error)
    }
    const settled = await Promise.allSettled([...this.inFlight])
    for (const outcome of settled) {
      if (outcome.status === 'rejected') {
        this.recordShutdownFailure('in-flight execution did not converge', outcome.reason)
      }
    }
    this.terminationResolve(terminalState)
  }

  getShutdownFailures(): readonly unknown[] {
    return this.shutdownFailures
  }

  private buildFailedPayload(message: string): { case: 'failed'; value: Failed } {
    return {
      case: 'failed',
      value: create(FailedSchema, { message }),
    }
  }
}

class ActiveConnection {
  ready = false
  readonly abort = new AbortController()
  private settingsReceived = false

  constructor(
    readonly generation: number,
    readonly connection: ControlConnection,
  ) {}

  markSettingsReceived(): boolean {
    if (this.settingsReceived) {
      return false
    }
    this.settingsReceived = true
    return true
  }
}

class RunningInvocation {
  terminalClaimed = false
  executorReturned = false
  executorFinished = false
  exitConfirmed = false
  exitPublished = false
  private exitPublisher: ((invocation: RunningInvocation) => void) | null = null
  deadlineTimer: NodeJS.Timeout | null = null

  constructor(
    readonly invocationId: string,
    readonly controller: AbortController,
    readonly maxResourceBytes: number,
  ) {}

  markExecutorReturned(): void {
    this.executorReturned = true
    this.exitPublisher?.(this)
  }

  finishOwnedCleanup(): void {
    this.executorReturned = true
    this.executorFinished = true
    this.exitPublisher?.(this)
  }

  noteExitEvidence(publisher: (invocation: RunningInvocation) => void): void {
    this.exitConfirmed = true
    this.exitPublisher = publisher
    if (this.executorReturned) {
      publisher(this)
    }
  }

  claimTerminal(): boolean {
    if (this.terminalClaimed) {
      return false
    }
    this.terminalClaimed = true
    return true
  }

  clearDeadline(): void {
    if (this.deadlineTimer !== null) {
      clearTimeout(this.deadlineTimer)
      this.deadlineTimer = null
    }
  }

  trySendControl(sender: () => boolean): boolean {
    if (this.terminalClaimed) {
      return false
    }
    return sender()
  }
}

function requireInvocationId(envelope: Envelope): string {
  const invocationId = envelope.invocationId
  if (invocationId === undefined || invocationId === null || invocationId.trim() === '') {
    throw new Error(`${envelope.messageType} requires non-null invocationId`)
  }
  return invocationId
}

function safeMessage(error: unknown): string {
  if (error instanceof Error) {
    const message = error.message
    if (typeof message === 'string' && message.trim() !== '') {
      return message
    }
  }
  return FALLBACK_FAILURE_MESSAGE
}

function requirePositive(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer`)
  }
  return value
}

function requireNonNegative(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error(`${name} must not be negative`)
  }
  return value
}

async function encodeCompletedProto(
  result: CapabilityResult,
  maximumResourceBytes: number,
  uploader: ((content: Extract<ResultContent, { type: 'binary' }>) => Promise<UploadedResource>) | null,
): Promise<Completed> {
  if (!Number.isSafeInteger(maximumResourceBytes) || maximumResourceBytes <= 0) {
    throw new Error('maximumResourceBytes must be positive')
  }
  let aggregate = 0
  for (const content of result.contents) {
    let size = 0
    if (content.type === 'resource') {
      size = content.size
    } else if (content.type === 'binary') {
      size = content.bytes.byteLength
    }
    if (size > maximumResourceBytes - aggregate) {
      throw new Error(`aggregate resource bytes exceed maximumResourceBytes: maximum=${maximumResourceBytes}`)
    }
    aggregate += size
  }

  const protoContents: Content[] = []
  for (const content of result.contents) {
    if (content.type === 'text') {
      protoContents.push(
        create(ContentSchema, {
          body: {
            case: 'text',
            value: create(TextContentSchema, { text: content.text }),
          },
        }),
      )
    } else if (content.type === 'json') {
      protoContents.push(
        create(ContentSchema, {
          body: {
            case: 'json',
            value: create(JsonContentSchema, { json: JSON.stringify(content.json) }),
          },
        }),
      )
    } else if (content.type === 'resource') {
      protoContents.push(
        create(ContentSchema, {
          body: {
            case: 'resource',
            value: create(ResourceContentSchema, {
              uploadId: content.uploadId,
              mediaType: content.mediaType,
              name: content.name ?? undefined,
              size: BigInt(content.size),
              sha256: content.sha256,
            }),
          },
        }),
      )
    } else if (content.type === 'binary') {
      if (uploader === null) {
        throw new Error('resource upload is not configured')
      }
      const uploaded = await uploader(content)
      protoContents.push(
        create(ContentSchema, {
          body: {
            case: 'resource',
            value: create(ResourceContentSchema, {
              uploadId: uploaded.uploadId,
              mediaType: uploaded.mediaType,
              name: uploaded.name ?? undefined,
              size: BigInt(uploaded.size),
              sha256: uploaded.sha256,
            }),
          },
        }),
      )
    }
  }

  return create(CompletedSchema, {
    result: create(CapabilityResultSchema, {
      callId: result.callId,
      error: result.error,
      detailsJson: JSON.stringify(result.details ?? {}),
      contents: protoContents,
    }),
  })
}

function encodeProgressProto(result: CapabilityResult): Progress {
  return create(ProgressSchema, {
    result: create(CapabilityResultSchema, {
      callId: result.callId,
      error: result.error,
      detailsJson: JSON.stringify(result.details ?? {}),
      contents: result.contents.map((content) => {
        if (content.type === 'text') {
          return create(ContentSchema, {
            body: { case: 'text', value: create(TextContentSchema, { text: content.text }) },
          })
        }
        if (content.type === 'json') {
          return create(ContentSchema, {
            body: { case: 'json', value: create(JsonContentSchema, { json: JSON.stringify(content.json) }) },
          })
        }
        throw new Error('progress does not allow binary content')
      }),
    }),
  })
}
