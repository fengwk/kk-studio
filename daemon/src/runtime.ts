import { randomUUID } from 'node:crypto'
import { create } from '@bufbuild/protobuf'

import { DAEMON_VERSION_PATTERN } from './release.js'
import type {
  CapabilityCall,
  CapabilityContext,
  CapabilityExecutor,
  CapabilityResult,
  DaemonConfig,
  IdeLeaseHandler,
  IdeService,
  ProcessSupervisor,
} from './contracts.js'
import { DAEMON_VERSION } from './config.js'
import { applyNetworkSettings } from './network.js'
import {
  CancelledSchema,
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
  type Envelope,
  type IdeCommand as ProtoIdeCommand,
  type IdeLease as ProtoIdeLease,
  type IdeResult,
  type Invoke,
  type ResourceUploadTicket,
  type RuntimeSnapshot,
  type Settings,
  type SkillPackageState,
  type SkillSync,
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
import {
  ActiveConnection,
  ConnectionLifecycle,
  type RuntimeState,
} from './runtime-connection.js'
import { RuntimeStatusTracker, type RuntimeStatus, type RuntimeStatusUpdate, type UpdateByteProgress } from './runtime-status.js'
import { detectEnvironmentInfo } from './runtime-environment.js'
import {
  encodeCompletedProto,
  encodeProgressProto,
  failedPayload,
  requireInvocationId,
  safeMessage,
} from './runtime-encoding.js'
import {
  IdeCommandRuntime,
  requireFreshIdeLease,
  toIdeLeaseProof,
  type ActiveIdeLeaseRound,
} from './runtime-ide.js'
import type { RuntimeOptions, TemporaryResourceSweeper, UpdatePort } from './runtime-ports.js'
import { createResourceRuntime } from './runtime-resources.js'
import { collectDiagnosticFailure, recordShutdownFailure, releaseStep } from './runtime-shutdown.js'
import {
  acceptedReceipt,
  decideUpdate,
  executeUpdateAttempt,
  executionFailureReceipt,
  preparedReceipt,
  receiptToResend,
  rememberUpdateResult,
  type ActiveUpdate,
  type UpdateReceipt,
} from './runtime-update.js'
import type { ControlTransport } from './transport/index.js'
import type { SkillReconciler } from './skill/reconcile.js'

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

export type { RuntimeState } from './runtime-connection.js'

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

const DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 15_000
const DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS = 1_000
const DEFAULT_MAX_RECONNECT_DELAY_MILLIS = 30_000
const DEFAULT_MAX_CONCURRENT_INVOCATIONS = 64
const DEFAULT_IDE_COMMAND_TIMEOUT_MILLIS = 30_000

export class Runtime {
  private readonly config: DaemonConfig
  private readonly transport: ControlTransport
  private readonly connection: ConnectionLifecycle
  private readonly capabilities: Record<string, CapabilityExecutor>
  private readonly processes: ProcessSupervisor
  private readonly environment: ReturnType<typeof detectEnvironmentInfo>
  private readonly daemonVersion: string
  private readonly journal: InvocationJournal
  private readonly resources: ReturnType<typeof createResourceRuntime>
  private readonly update: UpdatePort | null
  private readonly ide: IdeService | null
  private readonly ideLease: IdeLeaseHandler | null
  private readonly temporaryResources: TemporaryResourceSweeper | null
  private skillReconciler: SkillReconciler | null
  private readonly heartbeatIntervalMillis: number
  private readonly maxConcurrentInvocations: number
  private readonly monotonicNow: () => number
  private readonly runtimeStatus: RuntimeStatusTracker
  private readonly running = new Map<string, RunningInvocation>()
  private readonly ideCommands: IdeCommandRuntime
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

  private heartbeatTimer: NodeJS.Timeout | null = null
  private sweepTimer: NodeJS.Timeout | null = null

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
    this.skillReconciler = options.skillReconciler ?? null
    this.monotonicNow = options.monotonicNow ?? (() => performance.now())
    this.runtimeStatus = new RuntimeStatusTracker(this.daemonVersion)
    this.heartbeatIntervalMillis = requirePositive(
      options.heartbeatIntervalMillis ?? DEFAULT_HEARTBEAT_INTERVAL_MILLIS,
      'heartbeatIntervalMillis',
    )
    const initialReconnectDelayMillis = requireNonNegative(
      options.initialReconnectDelayMillis ?? DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS,
      'initialReconnectDelayMillis',
    )
    const maxReconnectDelayMillis = requirePositive(
      options.maxReconnectDelayMillis ?? DEFAULT_MAX_RECONNECT_DELAY_MILLIS,
      'maxReconnectDelayMillis',
    )
    if (initialReconnectDelayMillis > maxReconnectDelayMillis) {
      throw new Error('initialReconnectDelayMillis must not exceed maxReconnectDelayMillis')
    }
    this.maxConcurrentInvocations = requirePositive(
      options.maxConcurrentInvocations ?? DEFAULT_MAX_CONCURRENT_INVOCATIONS,
      'maxConcurrentInvocations',
    )
    const ideCommandTimeoutMillis = requirePositive(
      options.ideCommandTimeoutMillis ?? DEFAULT_IDE_COMMAND_TIMEOUT_MILLIS,
      'ideCommandTimeoutMillis',
    )
    this.resources = createResourceRuntime({
      ...(options.resources === undefined ? {} : { resources: options.resources }),
      ...(options.putObject === undefined ? {} : { putObject: options.putObject }),
      sendControl: (envelope: Envelope) => this.sendTransferControl(envelope),
    })
    this.connection = new ConnectionLifecycle({
      transport: this.transport,
      initialReconnectDelayMillis,
      maxReconnectDelayMillis,
      callbacks: {
        onEnvelope: (generation, envelope) => this.onEnvelope(generation, envelope),
        onUpgradeFailed: (generation, statusCode, upgradeRequired) =>
          this.handleUpgradeFailed(generation, statusCode, upgradeRequired),
        onDisconnected: (connection) => this.onConnectionLost(connection),
      },
    })
    this.ideCommands = new IdeCommandRuntime({
      ide: this.ide,
      timeoutMillis: ideCommandTimeoutMillis,
      isUpdateHeld: () => this.updateAdmissionHeld(),
      onResult: (invocationId, reply) => this.sendIdeResult(invocationId, reply),
      track: (promise) => {
        void this.track(promise)
      },
    })
  }

  /** 启动连接生命周期并立即尝试建立连接；重复调用无副作用。 */
  start(): void {
    if (this.connection.isClosed()) {
      return
    }
    const wasStarted = this.connection.isStarted()
    this.connection.start()
    if (!wasStarted && this.connection.isStarted()) {
      this.heartbeatTimer = setInterval(() => this.sendHeartbeat(), this.heartbeatIntervalMillis)
    }
  }

  /** 阻塞等待终止（由 close 或终态冲突触发）；返回终态。 */
  awaitTermination(): Promise<RuntimeState> {
    return this.connection.awaitTermination()
  }

  /** 停机后所有在途执行真正收敛的完成信号；与 awaitTermination 同时发布。 */
  async awaitCompletion(): Promise<void> {
    await this.connection.awaitTermination()
  }

  getState(): RuntimeState {
    return this.connection.getState()
  }

  getFailureReason(): string | null {
    return this.connection.getFailureReason()
  }

  getBoundEnvironmentId(): string | null {
    return this.boundEnvironmentId
  }

  setSkillReconciler(reconciler: SkillReconciler): void {
    this.skillReconciler = reconciler
  }

  sendSkillState(states: SkillPackageState[]): boolean {
    if (this.connection.getState() !== 'READY' || this.boundEnvironmentId === null) {
      return false
    }
    const envelope = create(EnvelopeSchema, {
      messageType: MessageType.SKILL_STATE,
      environmentId: this.boundEnvironmentId,
      payload: {
        case: 'skillState',
        value: create(SkillStateSchema, {
          packages: states,
        }),
      },
    })
    return this.send(envelope)
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

  /** 连接失效不影响 Invocation journal，也绝不取消在途 Agent 执行；仅重置绑定与资源预算。 */
  private onConnectionLost(connection: ActiveConnection | null): void {
    this.resources.onConnectionLost()
    this.maxResourceBytes = 0
    this.boundEnvironmentId = null
    this.activeIdeLeaseRound = null
    connection?.abort.abort('CONTROL_DISCONNECTED')
    // IDE 请求绑定于控制代际：连接失效即中止在途 IDE 请求，绝不跨代际重放副作用。
    this.ideCommands.abortAll('CONTROL_DISCONNECTED')
  }

  private handleUpgradeFailed(
    generation: number,
    statusCode: number,
    upgradeRequired?: UpgradeRequired,
  ): void {
    if (!this.connection.isCurrent(generation)) {
      return
    }
    if (statusCode === 401) {
      this.failTerminal('environment registration is rejected')
      return
    }
    if (statusCode === 426) {
      const targetVersion = upgradeRequired?.targetVersion
      if (targetVersion !== undefined && this.update !== null) {
        const operationId = randomUUID()
        const updateCommand = create(EnvelopeSchema, {
          messageType: MessageType.UPDATE,
          environmentId: this.boundEnvironmentId ?? undefined,
          payload: {
            case: 'update',
            value: {
              $typeName: 'kkstudio.environment.control.Update',
              operationId,
              targetVersion,
            },
          },
        })
        const command = updateCommand.payload.value as Update
        const receipt = acceptedReceipt(command)
        const active: ActiveUpdate = {
          operationId,
          commandKey: receipt.commandKey,
          targetVersion,
          controller: new AbortController(),
          receipt,
          prepared: false,
        }
        this.activeUpdate = active
        this.runtimeStatus.beginUpdate(targetVersion)
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
    this.connection.handleDisconnected(generation)
  }

  private closeFailedHandshake(connection: ActiveConnection): void {
    this.connection.closeFailedHandshake(connection)
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
    if (this.connection.getState() !== 'READY') {
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
    return this.connection.send(envelope)
  }

  private sendOn(connection: ActiveConnection, envelope: Envelope): boolean {
    return this.connection.sendOn(connection, envelope)
  }

  // -------------------------------------------------------------------------
  // 入站消息
  // -------------------------------------------------------------------------

  private onEnvelope(generation: number, envelope: Envelope): void {
    const connection = this.connection.getActiveConnection()
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
        case MessageType.SKILL_SYNC:
          this.handleSkillSync(envelope)
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
      if (envelope.messageType === MessageType.SETTINGS && !connection.ready && this.connection.getActiveConnection() === connection) {
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
      if (this.connection.getActiveConnection() === connection) {
        this.closeFailedHandshake(connection)
      }
      return
    }
    if (this.connection.markReady(connection)) {
      this.resources.onConnectionReady()
      this.resendUpdateResults()
    }
  }

  private handleTemporaryResourcePolicy(envelope: Envelope): void {
    if (this.connection.getState() !== 'READY' || !envelope.environmentId) {
      throw new Error('TEMPORARY_RESOURCE_POLICY requires a READY bound connection')
    }
    const policy = envelope.payload.value as TemporaryResourcePolicy
    const ttl = Number(policy.temporaryResourceTtlSeconds)
    const cleanup = Number(policy.temporaryResourceCleanupIntervalSeconds)
    this.applyTemporaryResourcePolicy(ttl, cleanup)
  }

  private handleSkillSync(envelope: Envelope): void {
    if (
      this.connection.getState() !== 'READY' ||
      !envelope.environmentId ||
      this.boundEnvironmentId === null
    ) {
      throw new Error('SKILL_SYNC requires a READY bound connection')
    }
    const sync = envelope.payload.value as SkillSync
    if (this.skillReconciler !== null) {
      this.skillReconciler.onDesiredSet(sync.packages ?? [])
    }
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
    this.connection.handleDisconnected(connection.generation)
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
    if (!this.connection.isStarted()) {
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
        failedPayload(safeMessage(error)),
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
          failedPayload('capability result callId does not match invocationId'),
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
          failedPayload(`capability execution timed out after ${call.timeoutMillis}ms`),
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
        failedPayload(safeMessage(error)),
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
        failedPayload(`cannot encode partial capability result: ${safeMessage(error)}`),
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
    if (this.connection.getState() !== 'READY' || this.boundEnvironmentId === null) {
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
    if (this.connection.getState() !== 'READY' || !envelope.environmentId) {
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
    if (this.connection.getState() === 'READY' && this.boundEnvironmentId !== null) {
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
    if (this.ideCommands.inFlightCount > 0) {
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
    if (this.connection.getState() === 'READY' && this.boundEnvironmentId !== null) {
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
    if (this.connection.getState() !== 'READY' || !envelope.environmentId) {
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
    const proof = toIdeLeaseProof(lease)
    this.runExtensionHandler('IDE_LEASE', () =>
      this.ideLease?.apply(proof, active.sentAt),
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
    if (this.connection.getState() === 'READY' && this.boundEnvironmentId !== null) {
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
    if (this.connection.getState() !== 'READY' || !envelope.environmentId) {
      throw new Error('IDE_COMMAND requires a READY bound connection')
    }
    const invocationId = requireInvocationId(envelope)
    const protoCmd = envelope.payload.value as ProtoIdeCommand
    this.ideCommands.handle(invocationId, protoCmd)
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
    if (this.connection.isClosed()) {
      return
    }
    const connection = this.connection.beginShutdown(terminalState, reason)
    if (this.heartbeatTimer !== null) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
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
    this.ideCommands.abortAll('SHUTDOWN')
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
    this.connection.resolveTermination(terminalState)
  }

  getShutdownFailures(): readonly unknown[] {
    return this.shutdownFailures
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
