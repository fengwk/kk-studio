import type { DaemonConfig, IdeLeaseHandler } from './contracts.js'
import {
  MessageType,
  type Envelope,
  type EnvironmentConfig,
  type IdeCommand as ProtoIdeCommand,
  type IdeLease as ProtoIdeLease,
  type ResourceUploadTicket,
  type Settings,
  type SkillPackageState,
  type SkillSync,
  type TemporaryResourcePolicy,
  type UpgradeRequired,
} from './generated/control_pb.js'
import type { EnvironmentConfigRuntime } from './environment-config.js'
import { parseEnvironmentConfig } from './protocol/control/codec.js'
import { ProtocolError } from './protocol.js'
import type { ActiveConnection, ConnectionLifecycle } from './runtime-connection.js'
import { requireInvocationId, safeMessage } from './runtime-encoding.js'
import {
  requireFreshIdeLease,
  toIdeLeaseProof,
  type ActiveIdeLeaseRound,
  type IdeCommandRuntime,
} from './runtime-ide.js'
import type { InvocationRuntime } from './runtime-invocations.js'
import type { OutboundControl } from './runtime-outbound.js'
import type { TemporaryResourceSweeper } from './runtime-ports.js'
import type { ResourceRuntime } from './runtime-resources.js'
import { describeFailureType } from './runtime-shutdown.js'
import type { UpdateRuntime } from './runtime-update.js'
import type { SkillReconciler } from './skill/reconcile.js'

export interface ControlSessionOptions {
  connection: ConnectionLifecycle
  outbound: OutboundControl
  resources: ResourceRuntime
  invocations: InvocationRuntime
  ideCommands: IdeCommandRuntime
  updates: UpdateRuntime
  configuration: EnvironmentConfigRuntime
  ideLease: IdeLeaseHandler | null
  temporaryResources: TemporaryResourceSweeper | null
  monotonicNow: () => number
  heartbeatIntervalMillis: number
  track(promise: Promise<void>): void
  /** 当前连接代际主动断开重连。 */
  reconnect(generation: number): void
  /** 进入终态失败；由装配层发布终止。 */
  stop(reason: string): void
}

/**
 * 控制会话：环境绑定、握手、心跳与入站信封分发。
 *
 * <p>连接只是消息管道。会话只裁决「这条连接代际与这个环境绑定下，哪个信封归哪个组件处理」：SETTINGS 只建立绑定并装载
 * 平台预算；完整 ENVIRONMENT_CONFIG 应用成功后才发送 READY。心跳把运行状态与 IDE 使用证明轮次带给平台；出入站信封都不
 * 携带其他组件的语义。业务事实（invocation journal、更新回执、IDE 视图）由各自运行时持有，断线只重置绑定与在途请求，
 * 不取消已受理执行。
 */
export class ControlSession {
  private skillReconciler: SkillReconciler | null = null
  private heartbeatTimer: NodeJS.Timeout | null = null
  private sweepTimer: NodeJS.Timeout | null = null
  private maxResourceBytesValue = 0
  private temporaryResourceTtlSeconds = 0
  private scheduledCleanupIntervalSeconds = 0
  private activeIdeLeaseRound: ActiveIdeLeaseRound | null = null
  private ideLeaseRound = 0n
  private admissionSuspended = false
  private reconnectPending = false
  /** 候选准备前的准入状态；仅在控制地址/代理实际变化且候选未发布时用于恢复。 */
  private candidateSuspension: boolean | null = null

  constructor(private readonly options: ControlSessionOptions) {}

  /** 当前连接代际的资源预算；新执行开始时读取。 */
  get maxResourceBytes(): number {
    return this.maxResourceBytesValue
  }

  /** 启动连接生命周期并立即尝试连接；首次真正启动后开始心跳。 */
  start(): void {
    if (this.options.connection.isClosed()) {
      return
    }
    const wasStarted = this.options.connection.isStarted()
    this.options.connection.start()
    if (!wasStarted && this.options.connection.isStarted()) {
      this.heartbeatTimer = setInterval(() => this.sendHeartbeat(), this.options.heartbeatIntervalMillis)
    }
  }

  /** 停机时停止心跳与临时资源清扫；已在途的执行由各自运行时中止。 */
  stopTimers(): void {
    if (this.heartbeatTimer !== null) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
    if (this.sweepTimer !== null) {
      clearInterval(this.sweepTimer)
      this.sweepTimer = null
    }
  }

  clearLeaseRound(): void {
    this.activeIdeLeaseRound = null
  }

  setSkillReconciler(reconciler: SkillReconciler): void {
    this.skillReconciler = reconciler
  }

  sendSkillState(states: SkillPackageState[]): boolean {
    return this.options.outbound.sendSkillState(states)
  }

  onEnvelope(generation: number, envelope: Envelope): void {
    const connection = this.options.connection.getActiveConnection()
    if (connection === null || connection.generation !== generation) {
      return
    }
    try {
      if (envelope.messageType === MessageType.SETTINGS) {
        this.handleSettings(envelope)
        return
      }
      this.verifyScope(envelope)
      this.dispatch(connection, envelope)
    } catch (error) {
      const errorEnvironmentId = this.options.outbound.environmentId ?? envelope.environmentId ?? undefined
      this.options.outbound.sendErrorOn(connection, errorEnvironmentId, safeMessage(error))
      // scope/版本冲突/非法帧是连接级协议违规，READY 后也不能只回 ERROR 继续复用；
      // 初始 SETTINGS 失败时服务端尚未 READY，同样关闭握手让下一次重新取设置。
      if (
        this.isFatalProtocolError(error) ||
        (envelope.messageType === MessageType.SETTINGS && !connection.ready)
      ) {
        if (this.options.connection.getActiveConnection() === connection) {
          this.options.connection.closeFailedHandshake(connection)
        }
      }
    }
  }

  /** 连接失效不影响 invocation journal，也绝不取消在途 Agent 执行；仅重置绑定与资源预算。 */
  onConnectionLost(): void {
    this.options.resources.onConnectionLost()
    this.maxResourceBytesValue = 0
    this.options.outbound.unbind()
    this.activeIdeLeaseRound = null
    this.admissionSuspended = false
    this.reconnectPending = false
    this.candidateSuspension = null
    // IDE 请求绑定于控制代际：连接失效即中止在途 IDE 请求，绝不跨代际重放副作用。
    this.options.ideCommands.abortAll('CONTROL_DISCONNECTED')
  }

  handleUpgradeFailed(generation: number, statusCode: number, upgradeRequired?: UpgradeRequired): void {
    if (!this.options.connection.isCurrent(generation)) {
      return
    }
    if (statusCode === 401) {
      this.options.stop('environment registration is rejected')
      return
    }
    if (statusCode === 426) {
      const targetVersion = upgradeRequired?.targetVersion
      if (targetVersion !== undefined && this.options.updates.configured) {
        this.options.updates.beginUpgrade(targetVersion, () =>
          this.options.stop('daemon requires protocol upgrade'),
        )
        return
      }
      this.options.stop('daemon requires protocol upgrade')
      return
    }
    this.options.connection.handleDisconnected(generation)
  }

  private dispatch(connection: ActiveConnection, envelope: Envelope): void {
    if (this.admissionSuspended && this.isAdmissionMessage(envelope.messageType)) {
      throw new Error('environment configuration is switching; new work is not admitted')
    }
    switch (envelope.messageType) {
      case MessageType.INVOKE:
        if (!this.options.outbound.isReady || !envelope.environmentId) {
          throw new Error('INVOKE requires a READY bound connection')
        }
        this.options.invocations.handleInvoke(connection, envelope)
        return
      case MessageType.CANCEL:
        this.options.invocations.handleCancel(envelope)
        return
      case MessageType.RECOVER:
        this.options.invocations.handleRecover(envelope)
        return
      case MessageType.TEMPORARY_RESOURCE_POLICY:
        this.handleTemporaryResourcePolicy(envelope)
        return
      case MessageType.ERROR:
        // 服务端发送的 ERROR 帧触发控制连接断开重连
        this.options.connection.handleDisconnected(connection.generation)
        return
      case MessageType.UPDATE:
        this.requireReady(envelope, 'UPDATE')
        this.options.updates.handle(envelope)
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
      case MessageType.ENVIRONMENT_CONFIG:
        this.options.track(
          this.handleEnvironmentConfig(connection, envelope).catch((error: unknown) => {
            this.failEnvironmentConfig(connection, error)
          }),
        )
        return
      default:
        throw new ProtocolError(
          `daemon must not receive ${MessageType[envelope.messageType] ?? envelope.messageType} from server`,
        )
    }
  }

  private verifyScope(envelope: Envelope): void {
    const bound = this.options.outbound.environmentId
    if (bound !== null && envelope.environmentId && bound !== envelope.environmentId) {
      throw new ProtocolError(`envelope environmentId does not match daemon: ${envelope.environmentId}`)
    }
  }

  /**
   * SETTINGS 只建立固定 UUID 的 scope 绑定并装载平台资源预算；READY 由完整配置应用完成。
   *
   * <p>SETTINGS 是本连接唯一能建立绑定的帧，因此环境标识校验就在这里完成一次，不再由通用 scope 校验重复。
   */
  private handleSettings(envelope: Envelope): void {
    const environmentId = envelope.environmentId
    if (!environmentId) {
      throw new ProtocolError('SETTINGS must declare non-null environmentId')
    }
    if (environmentId !== this.options.configuration.config.environmentId) {
      throw new ProtocolError('SETTINGS environmentId does not match daemon')
    }
    const bound = this.options.outbound.environmentId
    if (bound !== null && bound !== environmentId) {
      throw new ProtocolError('SETTINGS environmentId does not match daemon')
    }
    const settings = envelope.payload.value as Settings
    this.options.outbound.bind(environmentId)
    const budgets = settings.resourceBudgets
    if (budgets === undefined) {
      throw new Error('settings.resourceBudgets is required')
    }
    this.maxResourceBytesValue = Number(budgets.maxResourceBytes)
    const ttl = Number(budgets.temporaryResourceTtlSeconds)
    const cleanup = Number(budgets.temporaryResourceCleanupIntervalSeconds)
    this.applyTemporaryResourcePolicy(ttl, cleanup)
  }

  private async handleEnvironmentConfig(
    connection: ActiveConnection,
    envelope: Envelope,
  ): Promise<void> {
    const bound = this.options.outbound.environmentId
    if (bound === null || !envelope.environmentId) {
      throw new ProtocolError('ENVIRONMENT_CONFIG requires a SETTINGS-bound connection')
    }
    if (bound !== envelope.environmentId) {
      throw new ProtocolError('ENVIRONMENT_CONFIG environmentId does not match daemon')
    }
    const { version, configuration } = parseEnvironmentConfig(
      envelope.payload.value as EnvironmentConfig,
    )
    const result = await this.options.configuration.submit(version, configuration)
    if (this.options.connection.getActiveConnection() !== connection) {
      return
    }
    switch (result.kind) {
      case 'applied':
        if (this.reconnectPending) {
          this.requestReconnect(connection)
          return
        }
        if (!connection.ready) {
          this.completeReady(connection)
        } else {
          this.options.outbound.sendRuntimeStatus(this.options.updates.snapshot())
        }
        return
      case 'confirmed':
      case 'stale':
        // 只有已存在可用快照时 stale/confirmed 才能放行 READY；未初始化必须等待真正应用。
        if (!this.options.configuration.hasAppliedConfiguration) {
          this.options.outbound.sendRuntimeStatus(this.options.updates.snapshot())
          return
        }
        if (this.reconnectPending) {
          this.requestReconnect(connection)
          return
        }
        if (!connection.ready) {
          this.completeReady(connection)
        } else {
          this.options.outbound.sendRuntimeStatus(this.options.updates.snapshot())
        }
        return
      case 'failed':
        // 已有可用配置继续使用；尚未 READY 时保持 CONNECTING 并上报错误，等待服务端修正。
        this.options.outbound.sendRuntimeStatus(this.options.updates.snapshot())
        return
      case 'fatal':
        this.options.outbound.sendErrorOn(
          connection,
          this.options.outbound.environmentId ?? undefined,
          safeMessage(new Error(result.message)),
        )
        this.options.connection.closeFailedHandshake(connection)
        return
    }
  }

  /**
   * 候选开始准备：控制地址/代理实际变化时立即暂停旧连接的新 INVOKE/IDE 写。
   *
   * <p>必须早于资源准备，不能等 publish 之后；候选失败由
   * {@link onConfigurationCandidateAborted} 恢复准备前的准入状态。
   */
  onConfigurationCandidate(previous: DaemonConfig, candidate: DaemonConfig): void {
    if (!this.controlEndpointChanged(previous, candidate)) {
      return
    }
    if (this.candidateSuspension === null) {
      this.candidateSuspension = this.admissionSuspended
    }
    this.admissionSuspended = true
  }

  /** 候选失败/放弃：恢复候选准备前的准入；成功路径保持暂停到新连接 READY。 */
  onConfigurationCandidateAborted(previous: DaemonConfig, candidate: DaemonConfig): void {
    if (!this.controlEndpointChanged(previous, candidate)) {
      return
    }
    if (this.candidateSuspension !== null) {
      this.admissionSuspended = this.candidateSuspension
      this.candidateSuspension = null
    }
  }

  /** 配置热切换：地址/代理变化时保持暂停新入站写并主动用新配置重连。 */
  onConfigurationApplied(previous: DaemonConfig, next: DaemonConfig): void {
    if (this.controlEndpointChanged(previous, next)) {
      this.candidateSuspension = null
      this.admissionSuspended = true
      this.reconnectPending = true
      return
    }
    this.candidateSuspension = null
    this.admissionSuspended = false
    this.reconnectPending = false
  }

  private controlEndpointChanged(previous: DaemonConfig, next: DaemonConfig): boolean {
    return previous.gatewayUri !== next.gatewayUri || previous.proxyUrl !== next.proxyUrl
  }

  private completeReady(connection: ActiveConnection): void {
    if (!this.options.outbound.sendReady(connection, this.options.updates.snapshot())) {
      if (this.options.connection.getActiveConnection() === connection) {
        this.options.connection.closeFailedHandshake(connection)
      }
      return
    }
    if (this.options.connection.markReady(connection)) {
      this.admissionSuspended = false
      this.options.resources.onConnectionReady()
      this.options.updates.resendResults()
    }
  }

  private requestReconnect(connection: ActiveConnection): void {
    this.reconnectPending = false
    this.options.reconnect(connection.generation)
  }

  private failEnvironmentConfig(connection: ActiveConnection, error: unknown): void {
    this.options.outbound.sendErrorOn(
      connection,
      this.options.outbound.environmentId ?? undefined,
      safeMessage(error),
    )
    // 未 READY 时任何配置失败都关闭握手等待修正；READY 后的连接级协议违规同样必须关闭。
    if (
      (!connection.ready || this.isFatalProtocolError(error)) &&
      this.options.connection.getActiveConnection() === connection
    ) {
      this.options.connection.closeFailedHandshake(connection)
    }
  }

  private isFatalProtocolError(error: unknown): boolean {
    return error instanceof ProtocolError
  }

  private requireReady(envelope: Envelope, messageType: string): void {
    if (!this.options.outbound.isReady || !envelope.environmentId) {
      throw new Error(`${messageType} requires a READY bound connection`)
    }
  }

  private isAdmissionMessage(messageType: MessageType): boolean {
    return (
      messageType === MessageType.INVOKE ||
      messageType === MessageType.IDE_COMMAND ||
      messageType === MessageType.IDE_LEASE ||
      messageType === MessageType.SKILL_SYNC
    )
  }

  private handleTemporaryResourcePolicy(envelope: Envelope): void {
    if (!this.options.outbound.isReady || !envelope.environmentId) {
      throw new Error('TEMPORARY_RESOURCE_POLICY requires a READY bound connection')
    }
    const policy = envelope.payload.value as TemporaryResourcePolicy
    const ttl = Number(policy.temporaryResourceTtlSeconds)
    const cleanup = Number(policy.temporaryResourceCleanupIntervalSeconds)
    this.applyTemporaryResourcePolicy(ttl, cleanup)
  }

  private handleSkillSync(envelope: Envelope): void {
    if (!this.options.outbound.isReady || !envelope.environmentId || this.options.outbound.environmentId === null) {
      throw new Error('SKILL_SYNC requires a READY bound connection')
    }
    const sync = envelope.payload.value as SkillSync
    if (this.skillReconciler !== null) {
      this.skillReconciler.onDesiredSet(sync.packages ?? [])
    }
  }

  private applyTemporaryResourcePolicy(ttlSeconds: number, cleanupIntervalSeconds: number): void {
    this.temporaryResourceTtlSeconds = ttlSeconds
    const temporaryResources = this.options.temporaryResources
    if (temporaryResources === null) {
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
      if (ttl <= 0) {
        return
      }
      void temporaryResources.sweep(ttl).catch((error: unknown) => {
        try {
          process.stderr.write(`daemon temporary resource sweep failed: ${describeFailureType(error)}\n`)
        } catch {
          // 诊断输出失败不影响连接与后续清扫。
        }
      })
    }, cleanupIntervalSeconds * 1000)
  }

  private sendHeartbeat(): void {
    if (!this.options.outbound.isBound) {
      return
    }
    const runtimeStatus = this.options.updates.snapshot()
    let ideLeaseRound: bigint | undefined = undefined
    if (
      this.options.outbound.isReady &&
      this.options.ideLease !== null &&
      this.options.ideLease.hasActiveViews()
    ) {
      this.ideLeaseRound++
      ideLeaseRound = this.ideLeaseRound
    }
    const sent = this.options.outbound.sendHeartbeat(runtimeStatus, ideLeaseRound)
    this.activeIdeLeaseRound =
      sent && ideLeaseRound !== undefined
        ? { round: ideLeaseRound, sentAt: this.options.monotonicNow(), consumed: false }
        : null
  }

  private handleIdeLease(envelope: Envelope): void {
    if (!this.options.outbound.isReady || !envelope.environmentId) {
      throw new Error('IDE_LEASE requires a READY bound connection')
    }
    if (this.options.updates.isAdmissionHeld) {
      throw new Error('IDE_LEASE is not allowed while environment update admission is held')
    }
    const lease = envelope.payload.value as ProtoIdeLease
    if (this.options.ideLease === null) {
      throw new Error('IDE_LEASE handler is not configured')
    }
    const active = requireFreshIdeLease(this.activeIdeLeaseRound, lease.round ?? 0n, this.options.monotonicNow())
    active.consumed = true
    const proof = toIdeLeaseProof(lease)
    this.runExtensionHandler('IDE_LEASE', () => this.options.ideLease?.apply(proof, active.sentAt))
  }

  private runExtensionHandler(phase: string, action: () => Promise<void> | void): void {
    const result = action()
    if (result === undefined) {
      return
    }
    this.options.track(
      Promise.resolve(result).catch((error: unknown) => {
        this.sendExtensionError(phase, error)
      }),
    )
  }

  private sendExtensionError(phase: string, error: unknown): void {
    this.options.outbound.sendError(`${phase} handler failed: ${safeMessage(error)}`)
  }

  private handleTicket(envelope: Envelope): void {
    const invocationId = requireInvocationId(envelope)
    const ticket = envelope.payload.value as ResourceUploadTicket
    this.options.resources.onTicket(invocationId, ticket)
  }

  private handleIdeCommand(envelope: Envelope): void {
    if (!this.options.outbound.isReady || !envelope.environmentId) {
      throw new Error('IDE_COMMAND requires a READY bound connection')
    }
    const invocationId = requireInvocationId(envelope)
    this.options.ideCommands.handle(invocationId, envelope.payload.value as ProtoIdeCommand)
  }
}
