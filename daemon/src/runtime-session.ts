import type { IdeLeaseHandler } from './contracts.js'
import {
  MessageType,
  type Envelope,
  type IdeCommand as ProtoIdeCommand,
  type IdeLease as ProtoIdeLease,
  type ResourceUploadTicket,
  type Settings,
  type SkillPackageState,
  type SkillSync,
  type TemporaryResourcePolicy,
  type UpgradeRequired,
} from './generated/control_pb.js'
import { applyNetworkSettings } from './network.js'
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
import type { UpdateRuntime } from './runtime-update.js'
import type { SkillReconciler } from './skill/reconcile.js'

export interface ControlSessionOptions {
  connection: ConnectionLifecycle
  outbound: OutboundControl
  resources: ResourceRuntime
  invocations: InvocationRuntime
  ideCommands: IdeCommandRuntime
  updates: UpdateRuntime
  ideLease: IdeLeaseHandler | null
  temporaryResources: TemporaryResourceSweeper | null
  monotonicNow: () => number
  heartbeatIntervalMillis: number
  track(promise: Promise<void>): void
  /** 进入终态失败；由装配层发布终止。 */
  stop(reason: string): void
}

/**
 * 控制会话：环境绑定、握手、心跳与入站信封分发。
 *
 * <p>连接只是消息管道。会话只裁决「这条连接代际与这个环境绑定下，哪个信封归哪个组件处理」：SETTINGS 建立绑定并回
 * READY，后续 SETTINGS 只刷新网络设置；心跳把运行状态与 IDE 使用证明轮次带给平台；出入站信封都不携带其他组件的语义。
 * 业务事实（invocation journal、更新回执、IDE 视图）由各自运行时持有，断线只重置绑定与在途请求，不取消已受理执行。
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
        this.handleSettings(connection, envelope)
        return
      }
      this.verifyScope(envelope)
      this.dispatch(connection, envelope)
    } catch (error) {
      const errorEnvironmentId = this.options.outbound.environmentId ?? envelope.environmentId ?? undefined
      this.options.outbound.sendErrorOn(connection, errorEnvironmentId, safeMessage(error))
      // 初始 SETTINGS 失败时服务端尚未将会话标为 READY，不能依赖在线变更通知修复。
      // 关闭握手并重连，下一次从平台重新读取设置；已 READY 的连接仍可接收后续修正。
      if (
        envelope.messageType === MessageType.SETTINGS &&
        !connection.ready &&
        this.options.connection.getActiveConnection() === connection
      ) {
        this.options.connection.closeFailedHandshake(connection)
      }
    }
  }

  /** 连接失效不影响 invocation journal，也绝不取消在途 Agent 执行；仅重置绑定与资源预算。 */
  onConnectionLost(connection: ActiveConnection | null): void {
    this.options.resources.onConnectionLost()
    this.maxResourceBytesValue = 0
    this.options.outbound.unbind()
    this.activeIdeLeaseRound = null
    connection?.abort.abort('CONTROL_DISCONNECTED')
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
    switch (envelope.messageType) {
      case MessageType.INVOKE:
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
      default:
        throw new Error(`daemon must not receive ${envelope.messageType} from server`)
    }
  }

  private verifyScope(envelope: Envelope): void {
    const bound = this.options.outbound.environmentId
    if (bound !== null && envelope.environmentId && bound !== envelope.environmentId) {
      throw new Error(`envelope environmentId does not match daemon: ${envelope.environmentId}`)
    }
  }

  /**
   * 首次 SETTINGS 建立环境绑定并回 READY；已 READY 的后续 SETTINGS 只刷新网络设置。
   *
   * <p>SETTINGS 是本连接唯一能建立绑定的帧，因此环境标识校验就在这里完成一次，不再由通用 scope 校验重复。
   */
  private handleSettings(connection: ActiveConnection, envelope: Envelope): void {
    const environmentId = envelope.environmentId
    if (!environmentId) {
      throw new Error('SETTINGS must declare non-null environmentId')
    }
    const bound = this.options.outbound.environmentId
    if (bound !== null && bound !== environmentId) {
      throw new Error('SETTINGS environmentId does not match daemon')
    }
    const settings = envelope.payload.value as Settings
    if (settings.noProxyHosts === undefined) {
      throw new Error('settings.noProxyHosts is required')
    }
    applyNetworkSettings(settings.proxyUrl ?? null, settings.noProxyHosts)
    if (connection.ready) {
      return
    }
    this.options.outbound.bind(environmentId)
    const budgets = settings.resourceBudgets!
    this.maxResourceBytesValue = Number(budgets.maxResourceBytes)
    const ttl = Number(budgets.temporaryResourceTtlSeconds)
    const cleanup = Number(budgets.temporaryResourceCleanupIntervalSeconds)
    this.applyTemporaryResourcePolicy(ttl, cleanup)
    if (!this.options.outbound.sendReady(connection, this.options.updates.snapshot())) {
      if (this.options.connection.getActiveConnection() === connection) {
        this.options.connection.closeFailedHandshake(connection)
      }
      return
    }
    if (this.options.connection.markReady(connection)) {
      this.options.resources.onConnectionReady()
      this.options.updates.resendResults()
    }
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
      void temporaryResources.sweep(ttl).catch(() => {
        // 清扫失败不影响连接状态。
      })
    }, cleanupIntervalSeconds * 1000)
  }

  private sendHeartbeat(): void {
    if (!this.options.outbound.isReady) {
      return
    }
    const runtimeStatus = this.options.updates.snapshot()
    let ideLeaseRound: bigint | undefined = undefined
    if (this.options.ideLease !== null && this.options.ideLease.hasActiveViews()) {
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
