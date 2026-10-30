import { randomUUID } from 'node:crypto'

import type {
  CapabilityCall,
  CapabilityContext,
  CapabilityExecutor,
  CapabilityResult,
  DaemonConfig,
  DataBindHandler,
  IdeCommand,
  IdeLeaseHandler,
  IdeService,
  ProcessSupervisor,
} from './contracts.js'
import { DAEMON_VERSION, readRegistrationToken } from './config.js'
import {
  CAPABILITY_CATALOG_VERSION,
  PROTOCOL_VERSION,
  READY_CAPABILITIES_VERSION,
  ProtocolError,
  decodeEnvelope,
  decodeDataBind,
  decodeIdeCommand,
  decodeIdeLease,
  decodeInvoke,
  decodeUpdateCommand,
  encodeCapabilities,
  encodeCompleted,
  encodeEnvelope,
  encodeHeartbeat,
  encodeIdeReply,
  encodeProgress,
  encodeRuntimeStatus,
  errorPayload,
  utf8ByteLength,
  validateDataBindOrigin,
  type EnvironmentInfo,
  type Envelope,
  type MessageType,
  type RuntimeStatus,
  type RuntimeStatusUpdate,
  type UpdateCommand,
} from './protocol.js'
import {
  InMemoryInvocationJournal,
  isTerminalState,
  type InvocationJournal,
  type JournalEntry,
  type TerminalMessage,
} from './journal.js'
import { RuntimeStatusTracker, type UpdateByteProgress } from './runtime-status.js'
import { detectEnvironmentInfo } from './runtime-environment.js'
import {
  admitIdeCommand,
  ideFailureReply,
  requireFreshIdeLease,
  type ActiveIdeLeaseRound,
} from './runtime-ide.js'
import type { RuntimeOptions, TemporaryResourceSweeper, UpdatePort } from './runtime-ports.js'
import { createResourceRuntime } from './runtime-resources.js'
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
import type { ControlConnection, ControlTransport, ControlTransportListener } from './transport/control.js'

/**
 * Environment Daemon 的连接、协议与本地 Capability 执行基座。
 *
 * <p>Invocation journal 是进程内执行事实源；WebSocket 连接只是消息管道。连接断开后 Daemon 保留 journal 事实并重新握手
 * 与重连；若 Platform 再次发送相同 invocationId，RUNNING 与终态条目分别重放 STARTED 与 terminal 报文，绝不重复执行副作用。
 * 断开连接绝不取消在途 Agent 执行。
 *
 * <p>本进程的 daemonInstanceId 在构造期随机生成一次并在每个 HELLO 中声明；同一进程的多次重连复用同一身份。每次连接尝试由
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
export type { UpdateByteProgress } from './runtime-status.js'
export { detectArchitecture, detectEnvironmentInfo, defaultNote } from './runtime-environment.js'
export type { UploadedResource } from './runtime-resources.js'

const FALLBACK_FAILURE_MESSAGE = 'capability execution failed'
const DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 15_000
const DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS = 1_000
const DEFAULT_MAX_RECONNECT_DELAY_MILLIS = 30_000
const DEFAULT_MAX_CONCURRENT_INVOCATIONS = 64
const DEFAULT_IDE_COMMAND_TIMEOUT_MILLIS = 30_000
const DAEMON_VERSION_PATTERN = /^[0-9A-Za-z._+-]{1,64}$/

export class Runtime {
  private readonly config: DaemonConfig
  private readonly transport: ControlTransport
  private readonly capabilities: Record<string, CapabilityExecutor>
  private readonly processes: ProcessSupervisor
  private readonly environment: EnvironmentInfo
  private readonly capabilityCatalogVersion: string
  private readonly daemonVersion: string
  private readonly journal: InvocationJournal
  private readonly resources: ReturnType<typeof createResourceRuntime>
  private readonly update: UpdatePort | null
  private readonly ide: IdeService | null
  private readonly dataBind: DataBindHandler | null
  private readonly ideLease: IdeLeaseHandler | null
  private readonly temporaryResources: TemporaryResourceSweeper | null
  private readonly heartbeatIntervalMillis: number
  private readonly initialReconnectDelayMillis: number
  private readonly maxReconnectDelayMillis: number
  private readonly maxConcurrentInvocations: number
  private readonly ideCommandTimeoutMillis: number
  private readonly monotonicNow: () => number
  private readonly runtimeStatus: RuntimeStatusTracker
  private readonly daemonInstanceId = randomUUID()
  private readonly running = new Map<string, RunningInvocation>()
  private readonly ideInFlight = new Map<string, AbortController>()
  private readonly updateResults = new Map<string, UpdateReceipt>()
  /** 停机独立释放步骤与未收敛在途执行的失败：绝不阻断其余清理，由装配层显式聚合报告。 */
  private readonly shutdownFailures: unknown[] = []
  /** 所有已登记的在途执行（capability/IDE/update/data bind）；停机后必须全部收敛才发布完成信号。 */
  private readonly inFlight = new Set<Promise<void>>()
  /** 普通/恢复执行及其拥有的上传收尾；更新准入必须等它们全部结束。 */
  private readonly protectedExecutions = new Set<Promise<void>>()
  /** 数据连接建立/存续 Promise；活动数据流阻止更新准入。 */
  private readonly dataBindInFlight = new Set<Promise<void>>()
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
    this.capabilityCatalogVersion = options.capabilityCatalogVersion ?? CAPABILITY_CATALOG_VERSION
    this.daemonVersion = options.daemonVersion ?? DAEMON_VERSION
    if (!DAEMON_VERSION_PATTERN.test(this.daemonVersion)) {
      throw new Error('daemonVersion must match [0-9A-Za-z._+-]{1,64}')
    }
    this.journal = options.journal ?? new InMemoryInvocationJournal()
    this.environment = options.environment ?? detectEnvironmentInfo(options.config)
    this.update = options.update ?? null
    this.ide = options.ide ?? null
    this.dataBind = options.dataBind ?? null
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
      sendControl: (messageType, invocationId, payloadJson) =>
        this.sendTransferControl(messageType, invocationId, payloadJson),
    })
    this.termination = new Promise<RuntimeState>((resolveTermination) => {
      this.terminationResolve = resolveTermination
    })
  }

  /** 启动连接生命周期并立即尝试建立 WebSocket；重复调用无副作用。 */
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
    return this.currentRuntimeStatus()
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
      onMessage: (message) => this.onMessage(generation, message),
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
        if (this.sendHello(active)) {
          active.helloSent = true
        } else {
          this.closeFailedHandshake(active)
        }
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

  private closeFailedHandshake(connection: ActiveConnection): void {
    try {
      connection.connection.close()
    } catch {
      // 关闭失败仍要走代际断开，避免停在 CONNECTING。
    }
    this.handleDisconnected(connection.generation)
  }

  private sendHello(connection: ActiveConnection): boolean {
    let token: string
    try {
      token = readRegistrationToken(this.config)
    } catch {
      return false
    }
    const payload = JSON.stringify({
      protocolVersion: PROTOCOL_VERSION,
      registrationToken: token,
      capabilityCatalogVersion: this.capabilityCatalogVersion,
      daemonVersion: this.daemonVersion,
      daemonInstanceId: this.daemonInstanceId,
    })
    return this.sendOn(connection, 'HELLO', null, payload)
  }

  private sendReady(connection: ActiveConnection): boolean {
    const payload = encodeCapabilities({
      version: READY_CAPABILITIES_VERSION,
      daemonVersion: this.daemonVersion,
      environment: this.environment,
      runtimeStatus: this.currentRuntimeStatus(),
    })
    return this.sendOn(connection, 'READY', null, payload)
  }

  private sendHeartbeat(): void {
    if (this.state !== 'READY') {
      return
    }
    const runtimeStatus = this.currentRuntimeStatus()
    let ideLeaseRound: string | null = null
    if (this.ideLease !== null && this.ideLease.hasActiveViews()) {
      this.ideLeaseRound++
      ideLeaseRound = this.ideLeaseRound.toString()
    }
    const sent = this.send('HEARTBEAT', null, encodeHeartbeat({ runtimeStatus, ideLeaseRound }))
    this.activeIdeLeaseRound =
      sent && ideLeaseRound !== null
        ? { round: ideLeaseRound, sentAt: this.monotonicNow(), consumed: false }
        : null
  }

  private send(messageType: MessageType, invocationId: string | null, payloadJson: string): boolean {
    const connection = this.activeConnection
    return connection !== null && this.sendOn(connection, messageType, invocationId, payloadJson)
  }

  private sendOn(
    connection: ActiveConnection,
    messageType: MessageType,
    invocationId: string | null,
    payloadJson: string,
  ): boolean {
    if (this.activeConnection !== connection || !connection.connection.isOpen()) {
      return false
    }
    const envelope: Envelope = {
      protocolVersion: PROTOCOL_VERSION,
      messageType,
      environmentId: messageType === 'HELLO' ? null : this.boundEnvironmentId,
      invocationId,
      payload: JSON.parse(payloadJson) as Record<string, unknown>,
    }
    connection.connection.sendText(encodeEnvelope(envelope)).catch(() => {
      this.handleDisconnected(connection.generation)
    })
    return true
  }

  // -------------------------------------------------------------------------
  // 入站消息
  // -------------------------------------------------------------------------

  private onMessage(generation: number, rawMessage: string): void {
    const connection = this.activeConnection
    if (connection === null || connection.generation !== generation) {
      return
    }
    try {
      const envelope = decodeEnvelope(rawMessage)
      this.verifyScope(envelope)
      switch (envelope.messageType) {
        case 'INVOKE':
          this.handleInvoke(connection, envelope)
          return
        case 'CANCEL':
          this.handleCancel(envelope)
          return
        case 'RECOVER':
          this.handleRecover(envelope)
          return
        case 'WELCOME':
          this.handleWelcome(connection, envelope)
          return
        case 'TEMPORARY_RESOURCE_POLICY':
          this.handleTemporaryResourcePolicy(envelope)
          return
        case 'ERROR':
          this.handleError(connection, envelope)
          return
        case 'UPDATE':
          this.handleUpdate(envelope)
          return
        case 'DATA_BIND':
          this.handleDataBind(connection, envelope)
          return
        case 'IDE_LEASE':
          this.handleIdeLease(envelope)
          return
        case 'RESOURCE_UPLOAD_TICKET':
          this.handleTicket(envelope)
          return
        case 'IDE_COMMAND':
          this.handleIdeCommand(envelope)
          return
        default:
          throw new ProtocolError(`daemon must not receive ${envelope.messageType} from server`)
      }
    } catch (error) {
      this.sendOn(connection, 'ERROR', null, JSON.stringify(errorPayload(safeMessage(error))))
    }
  }

  private verifyScope(envelope: Envelope): void {
    if (envelope.messageType === 'HELLO') {
      if (envelope.environmentId !== null) {
        throw new ProtocolError('HELLO must not declare environmentId')
      }
      return
    }
    if (envelope.messageType === 'WELCOME') {
      if (envelope.environmentId === null) {
        throw new ProtocolError('WELCOME must declare non-null environmentId')
      }
      return
    }
    const bound = this.boundEnvironmentId
    if (bound !== null && envelope.environmentId !== null && bound !== envelope.environmentId) {
      throw new ProtocolError(`envelope environmentId does not match daemon: ${envelope.environmentId}`)
    }
  }

  private handleWelcome(connection: ActiveConnection, envelope: Envelope): void {
    if (!connection.helloSent) {
      throw new ProtocolError('WELCOME requires a preceding HELLO')
    }
    if (!connection.markWelcomed()) {
      throw new ProtocolError('WELCOME may only be received once per connection')
    }
    if (envelope.environmentId === null) {
      throw new ProtocolError('WELCOME must declare non-null environmentId')
    }
    this.boundEnvironmentId = envelope.environmentId
    const payload = envelope.payload
    this.maxResourceBytes = requiredPositiveInt(payload, 'WELCOME', 'maxResourceBytes')
    const ttl = requiredPositiveInt(payload, 'WELCOME', 'temporaryResourceTtlSeconds')
    const cleanup = requiredPositiveInt(payload, 'WELCOME', 'temporaryResourceCleanupIntervalSeconds')
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
    if (this.state !== 'READY' || envelope.environmentId === null) {
      throw new ProtocolError('TEMPORARY_RESOURCE_POLICY requires a READY bound connection')
    }
    const payload = envelope.payload
    const ttl = requiredPositiveInt(payload, 'TEMPORARY_RESOURCE_POLICY', 'temporaryResourceTtlSeconds')
    const cleanup = requiredPositiveInt(
      payload,
      'TEMPORARY_RESOURCE_POLICY',
      'temporaryResourceCleanupIntervalSeconds',
    )
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

  private handleError(connection: ActiveConnection, envelope: Envelope): void {
    const payload = envelope.payload
    for (const field of Object.keys(payload)) {
      if (field !== 'message' && field !== 'code') {
        throw new ProtocolError(`ERROR payload has unexpected fields: ${field}`)
      }
    }
    const code = payload.code
    if (code === undefined || code === null) {
      return
    }
    if (typeof code !== 'string') {
      throw new ProtocolError('ERROR payload.code is unknown')
    }
    if (code === 'REGISTRATION_REJECTED') {
      const message = typeof payload.message === 'string' ? payload.message : ''
      this.failTerminal(
        `environment registration is rejected${message.trim() === '' ? '' : `: ${message}`}`,
      )
      return
    }
    if (code === 'RETRY_LATER') {
      this.handleDisconnected(connection.generation)
      return
    }
    throw new ProtocolError(`ERROR payload.code is unknown: ${code}`)
  }

  private handleTicket(envelope: Envelope): void {
    const invocationId = requireInvocationId(envelope)
    this.resources.onTicket(invocationId, JSON.stringify(envelope.payload))
  }

  // -------------------------------------------------------------------------
  // Agent invocation
  // -------------------------------------------------------------------------

  private handleInvoke(connection: ActiveConnection, envelope: Envelope): void {
    if (!this.started) {
      return
    }
    const invocationId = requireInvocationId(envelope)
    const invoke = decodeInvoke(JSON.stringify(envelope.payload))
    const start = this.journal.start(invocationId)
    if (!start.created) {
      this.replay(start.entry)
      return
    }
    try {
      if (this.updateAdmissionHeld()) {
        throw new Error('environment update admission is held')
      }
      const executor = this.capabilities[invoke.capabilityId]
      if (executor === undefined) {
        throw new Error(`unknown Environment capability: ${invoke.capabilityId}`)
      }
      if (invoke.capabilityVersion !== this.capabilityCatalogVersion) {
        throw new Error(
          `capabilityVersion does not match environment descriptor: ${invoke.capabilityVersion}`,
        )
      }
      if (this.running.size >= this.maxConcurrentInvocations) {
        throw new Error('too many concurrent capability invocations')
      }
      const controller = new AbortController()
      const invocation = new RunningInvocation(invocationId, controller, this.maxResourceBytes)
      this.running.set(invocationId, invocation)
      this.sendOn(connection, 'STARTED', invocationId, '{}')
      this.scheduleTimeout(invocation, invoke.timeoutMillis)
      const call: CapabilityCall = {
        invocationId,
        capabilityId: invoke.capabilityId,
        arguments: invoke.arguments,
        timeoutMillis: invoke.timeoutMillis,
        ...(invoke.expectedFile === undefined ? {} : { expectedFile: invoke.expectedFile }),
      }
      void this.trackProtected(this.execute(invocation, executor, call))
    } catch (error) {
      this.confirmRejectedBeforeExecution(invocationId)
      this.terminal(invocationId, 'FAILED', JSON.stringify(errorPayload(safeMessage(error))), null)
    }
  }

  /** 执行器确定未启动时记录并发送退出确认；重复确认由 journal 吸收。 */
  private confirmRejectedBeforeExecution(invocationId: string): void {
    if (this.journal.confirmExit(invocationId)) {
      this.send('EXECUTION_EXITED', invocationId, '{}')
    }
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
          'FAILED',
          JSON.stringify(errorPayload('capability result callId does not match invocationId')),
          null,
        )
        invocation.finishOwnedCleanup()
        return
      }
      const payloadJson = await encodeCompleted(result, invocation.maxResourceBytes, (content) => {
        const resources = this.resources.uploader
        if (resources === null) {
          throw new ProtocolError('resource upload is not configured')
        }
        return resources.upload(invocation.invocationId, content, invocation.controller.signal)
      })
      this.terminal(invocation.invocationId, 'COMPLETED', payloadJson, null)
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
        const payload = reason === 'SHUTDOWN' ? { reason: 'daemon shutdown' } : { reason: 'cancelled' }
        this.terminal(invocation.invocationId, 'CANCELLED', JSON.stringify(payload), reason)
        if (executorFailed) {
          invocation.finishOwnedCleanup()
        }
        return
      }
      if (reason === 'TIMED_OUT') {
        this.terminal(
          invocation.invocationId,
          'FAILED',
          JSON.stringify(errorPayload(`capability execution timed out after ${call.timeoutMillis}ms`)),
          'TIMED_OUT',
        )
        if (executorFailed) {
          invocation.finishOwnedCleanup()
        }
        return
      }
      this.terminal(invocation.invocationId, 'FAILED', JSON.stringify(errorPayload(safeMessage(error))), null)
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
    this.send('EXECUTION_EXITED', invocation.invocationId, '{}')
  }

  private progress(invocation: RunningInvocation, result: CapabilityResult): void {
    if (invocation.terminalClaimed || !this.isRunning(invocation.invocationId)) {
      return
    }
    try {
      const payloadJson = encodeProgress(result)
      this.send('PROGRESS', invocation.invocationId, payloadJson)
    } catch (error) {
      // 对外保留 FAILED 诊断，但封存失败的同时必须把停止意图交给仍持有执行体的调用。
      this.terminal(
        invocation.invocationId,
        'FAILED',
        JSON.stringify(errorPayload(`cannot encode partial capability result: ${safeMessage(error)}`)),
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
    const entry = this.journal.find(requireInvocationId(envelope))
    if (entry !== undefined) {
      this.replay(entry)
    }
  }

  private replay(entry: JournalEntry): void {
    if (entry.state === 'RUNNING') {
      this.send('STARTED', entry.invocationId, JSON.stringify({ replayed: true }))
    } else {
      const terminal = entry.terminalMessage
      if (terminal !== null) {
        this.send(terminal.messageType, entry.invocationId, terminal.payloadJson)
      }
    }
    if (entry.exitConfirmed) {
      this.send('EXECUTION_EXITED', entry.invocationId, '{}')
    }
  }

  /**
   * 原子终态仲裁与结果派发：journal 的 RUNNING→终态跃迁是唯一提交点，只有跃迁成功者发送终态报文。
   */
  private terminal(
    invocationId: string,
    messageType: TerminalMessage['messageType'],
    payloadJson: string,
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
    const terminal: TerminalMessage = { messageType, payloadJson }
    if (!this.journal.complete(invocationId, terminal)) {
      return
    }
    if (invocation !== undefined) {
      this.running.delete(invocationId)
    }
    this.send(messageType, invocationId, payloadJson)
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

  private sendTransferControl(
    messageType: 'RESOURCE_UPLOAD_REQUEST' | 'RESOURCE_UPLOAD_COMMIT',
    invocationId: string,
    payloadJson: string,
  ): boolean {
    if (this.state !== 'READY' || this.boundEnvironmentId === null) {
      return false
    }
    const invocation = this.running.get(invocationId)
    if (invocation === undefined) {
      return false
    }
    return invocation.trySendControl(
      () => this.isRunning(invocationId) && this.send(messageType, invocationId, payloadJson),
    )
  }

  // -------------------------------------------------------------------------
  // 受管更新
  // -------------------------------------------------------------------------

  private handleUpdate(envelope: Envelope): void {
    if (this.state !== 'READY' || envelope.environmentId === null) {
      throw new ProtocolError('UPDATE requires a READY bound connection')
    }
    const command = decodeUpdateCommand(JSON.stringify(envelope.payload))
    const decision = decideUpdate({
      command,
      active: this.activeUpdate,
      cached: this.updateResults.get(command.operationId),
      currentVersion: this.daemonVersion,
      busyReason: this.updateBusyReason(),
      configured: this.update !== null,
    })
    if (decision.kind === 'status') {
      this.publishRuntimeStatus()
      return
    }
    if (decision.kind === 'replay-active') {
      // PREPARED 后重放冻结回执；准备中的重复请求只刷新当前快照，不重复 ACK 或执行。
      if (decision.active.prepared) {
        this.deliverUpdateResult(decision.active.receipt)
      } else {
        this.publishRuntimeStatus()
      }
      return
    }
    if (decision.kind === 'replay-cached') {
      // 已完成 operation 只重放回执并幂等重试许可释放。
      this.deliverUpdateResult(decision.receipt)
      this.publishRuntimeStatus()
      return
    }
    if (decision.kind === 'reject') {
      this.rememberUpdateResult(command.operationId, decision.receipt)
      this.deliverUpdateResult(decision.receipt)
      return
    }
    // 先建立准入门闩与当前快照，再发送 ACCEPTED；新调用/IDE 创建从此刻起同步被拒绝。
    this.activeUpdate = decision.active
    this.runtimeStatus.beginUpdate(command.targetVersion)
    this.publishRuntimeStatus()
    this.sendUpdateResult(decision.active.receipt.payloadJson)
    void this.track(this.executeUpdate(decision.active, decision.command))
  }

  private async executeUpdate(active: ActiveUpdate, command: UpdateCommand): Promise<void> {
    const update = this.update
    const attempt =
      update === null
        ? { failure: new Error('managed update is not configured'), prepared: null }
        : await executeUpdateAttempt(
            active,
            command,
            update,
            this.dataBind,
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
      this.rememberUpdateResult(command.operationId, receipt)
      this.deliverUpdateResult(receipt)
      return
    }
    // 已接受交接：更新门闩与当前目标快照保持到进程确定退出；下载字节在心跳中继续上报。
    this.refreshUpdateProgress()
    const receipt = preparedReceipt(active, command, attempt.prepared)
    active.receipt = receipt
    active.prepared = true
    this.runtimeStatus.beginUpdate(command.targetVersion)
    this.publishRuntimeStatus()
    this.rememberUpdateResult(command.operationId, receipt)
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

  private currentRuntimeStatus(): RuntimeStatus {
    this.refreshUpdateProgress()
    return this.runtimeStatus.snapshot()
  }

  private publishRuntimeStatus(): void {
    if (this.state === 'READY' && this.boundEnvironmentId !== null) {
      this.send('RUNTIME_STATUS', null, encodeRuntimeStatus(this.currentRuntimeStatus()))
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
    if (this.dataBind !== null && this.dataBind.activeStreamCount() > 0) {
      return 'IDE data streams are active'
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

  /**
   * 交付一条更新回执：先尝试出站，再幂等释放已接受交接的许可。
   *
   * <p>显式重放、随重连的重发与完成路径都经由此处，因此它们既是回执重发，也是许可释放的天然重试：一次失败的释放
   * 不会让 ACCEPTED 交接永久停在「已接受但无许可」的状态，而更新器始终 fail closed。释放失败只记录固定阶段与异常
   * 类型，绝不掩盖出站结果，也绝不阻断其余清理。
   */
  private deliverUpdateResult(receipt: UpdateReceipt): void {
    this.sendUpdateResult(receipt.payloadJson)
    const releasePermit = receipt.releasePermit
    if (releasePermit === undefined) {
      return
    }
    void this.track(
      releasePermit().catch((error: unknown) => {
        // 许可释放失败只留下安全诊断（固定阶段与固定异常类型），既不掩盖出站结果，也不伪装成停机失败；
        // 诊断输出本身失败也照样可见地并入停机失败聚合。
        this.collectDiagnosticFailure('update permit release', error)
      }),
    )
  }

  private sendUpdateResult(payloadJson: string): void {
    if (this.state === 'READY') {
      this.send('UPDATE_RESULT', null, payloadJson)
    }
  }

  // -------------------------------------------------------------------------
  // 数据绑定与 IDE 使用证明扩展点
  // -------------------------------------------------------------------------

  private handleDataBind(connection: ActiveConnection, envelope: Envelope): void {
    if (this.state !== 'READY' || envelope.environmentId === null) {
      throw new ProtocolError('DATA_BIND requires a READY bound connection')
    }
    if (this.updateAdmissionHeld()) {
      throw new ProtocolError('DATA_BIND is not allowed while environment update admission is held')
    }
    const binding = decodeDataBind(JSON.stringify(envelope.payload))
    validateDataBindOrigin(binding, this.config.studioUrl)
    if (this.dataBind === null) {
      throw new ProtocolError('DATA_BIND handler is not configured')
    }
    this.runDataBindHandler('DATA_BIND', () => this.dataBind?.bind(binding, connection.abort.signal))
  }

  private handleIdeLease(envelope: Envelope): void {
    if (this.state !== 'READY' || envelope.environmentId === null) {
      throw new ProtocolError('IDE_LEASE requires a READY bound connection')
    }
    if (this.updateAdmissionHeld()) {
      throw new ProtocolError('IDE_LEASE is not allowed while environment update admission is held')
    }
    const lease = decodeIdeLease(JSON.stringify(envelope.payload))
    if (this.ideLease === null) {
      throw new ProtocolError('IDE_LEASE handler is not configured')
    }
    const active = requireFreshIdeLease(this.activeIdeLeaseRound, lease.round, this.monotonicNow())
    // 一轮只接受一次证明；处理器失败也不重放旧轮次，等待下一次正常心跳。
    // 以本轮发出时的本机单调时间作为使用证明，而不是以延迟到达时间重新获得完整 600 秒。
    active.consumed = true
    this.runExtensionHandler('IDE_LEASE', () => this.ideLease?.apply(lease, active.sentAt))
  }

  /** 数据连接的生命周期代表绑定存续；未收敛前不得开始更新。 */
  private runDataBindHandler(phase: string, action: () => Promise<void> | void): void {
    const result = action()
    if (result === undefined) {
      return
    }
    const promise = Promise.resolve(result).catch((error: unknown) => {
      this.sendExtensionError(phase, error)
    })
    this.trackIn(this.dataBindInFlight, promise)
  }

  /** 扩展处理器的失败必须显式回到控制连接；异步失败不阻断当前入站帧处理。 */
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
    if (this.state === 'READY') {
      this.send('ERROR', null, JSON.stringify(errorPayload(`${phase} handler failed: ${safeMessage(error)}`)))
    }
  }

  // -------------------------------------------------------------------------
  // IDE 请求（管理动作有独立 deadline；不进入 Agent journal，
  // 断线/停机中止而不跨代际重放副作用）
  // -------------------------------------------------------------------------

  private handleIdeCommand(envelope: Envelope): void {
    if (this.state !== 'READY' || envelope.environmentId === null) {
      throw new ProtocolError('IDE_COMMAND requires a READY bound connection')
    }
    const invocationId = requireInvocationId(envelope)
    const command = decodeIdeCommand(JSON.stringify(envelope.payload))
    const admission = admitIdeCommand({
      invocationId,
      command,
      inFlight: this.ideInFlight,
      updateHeld: this.updateAdmissionHeld(),
      ide: this.ide,
    })
    if (admission.kind === 'ignore') {
      // 同连接内重复的 IDE 请求不再执行副作用。
      return
    }
    if (admission.kind === 'reply') {
      this.send('IDE_RESULT', invocationId, admission.replyJson)
      return
    }
    const controller = admission.controller
    this.ideInFlight.set(invocationId, controller)
    // 所有 IDE 管理命令都有固定 deadline；数据面逻辑流不占用 IDE_COMMAND 调用。
    const timer = setTimeout(() => controller.abort('IDE_COMMAND_TIMEOUT'), this.ideCommandTimeoutMillis)
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
      this.send('IDE_RESULT', invocationId, encodeIdeReply(reply))
    } catch (error) {
      this.send('IDE_RESULT', invocationId, ideFailureReply(error, safeMessage(error)))
    } finally {
      if (timer !== null) {
        clearTimeout(timer)
      }
      this.ideInFlight.delete(invocationId)
    }
  }

  // -------------------------------------------------------------------------
  // 停机
  // -------------------------------------------------------------------------

  private failTerminal(reason: string): void {
    this.shutdown('FAILED', reason)
  }

  /**
   * 停机：顺序固定为资源客户端 → 连接 → 在途调用的 journal 终态 → 在途 IDE 与更新 → transport，每一项独立释放。
   *
   * <p>任一步无论抛出什么失败都继续后续动作，并按固定阶段记入聚合（由装配层显式报告，绝不静默报告成功）；终止闩始终在
   * {@link finalize} 中释放，因此同步抛出的释放失败不会让 {@link awaitCompletion()} 悬挂。
   */
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
      this.release('invocation journal complete', () =>
        this.journal.complete(invocation.invocationId, {
          messageType: 'CANCELLED',
          payloadJson: JSON.stringify({ reason: 'daemon shutdown' }),
        }),
      )
    }
    this.running.clear()
    for (const controller of this.ideInFlight.values()) {
      controller.abort('SHUTDOWN')
    }
    this.ideInFlight.clear()
    // 受管更新使用独立 controller；停机必须中止，否则其 OS 作业句柄悬空。
    this.activeUpdate?.controller?.abort('SHUTDOWN')
    if (this.activeUpdate !== null) {
      this.activeUpdate.controller = null
    }
    this.release('transport close', () => this.transport.close())
    void this.finalize(terminalState)
  }

  /** 独立释放一个停机步骤：失败按固定阶段记入聚合并继续，绝不跳过其余释放。 */
  private release(phase: string, action: () => void): void {
    releaseStep(this.shutdownFailures, phase, action)
  }

  /** 记录一次停机失败；诊断输出失败同样并入聚合且不递归。 */
  private recordShutdownFailure(phase: string, error: unknown): void {
    recordShutdownFailure(this.shutdownFailures, phase, error)
  }

  /** 只留下安全诊断，不把该失败升级为停机失败。 */
  private collectDiagnosticFailure(phase: string, error: unknown): void {
    collectDiagnosticFailure(this.shutdownFailures, phase, error)
  }

  /** 登记一个在途执行 Promise，并在其收敛后自动移除。 */
  private track(promise: Promise<void>): Promise<void> {
    return this.trackIn(null, promise)
  }

  /** 普通/恢复执行额外进入更新准入占用集合；更新自身的 Promise 不进入。 */
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

  /**
   * 停机后等待所有在途执行（capability/IDE/update）真正收敛，再发布终止与完成信号。
   *
   * <p>运行时调度的 deadline 已在 shutdown 中清除、controller 已中止，因此 well-behaved 执行器必然收敛，
   * 不会因本 runtime 自身的定时器死锁。未收敛的在途执行按固定阶段记入停机失败聚合，绝不静默成成功。
   */
  private async finalize(terminalState: RuntimeState): Promise<void> {
    const settled = await Promise.allSettled([...this.inFlight])
    for (const outcome of settled) {
      if (outcome.status === 'rejected') {
        this.recordShutdownFailure('in-flight execution did not converge', outcome.reason)
      }
    }
    this.terminationResolve(terminalState)
  }

  /**
   * 停机期间独立释放步骤与未收敛在途执行的失败；调用方必须在 {@link awaitCompletion()} 之后读取，
   * 并据此报告失败退出，绝不把「释放没有证明完成」当成成功。
   */
  getShutdownFailures(): readonly unknown[] {
    return this.shutdownFailures
  }
}

class ActiveConnection {
  helloSent = false
  ready = false
  readonly abort = new AbortController()
  private welcomed = false

  constructor(
    readonly generation: number,
    readonly connection: ControlConnection,
  ) {}

  markWelcomed(): boolean {
    if (this.welcomed) {
      return false
    }
    this.welcomed = true
    return true
  }
}

class RunningInvocation {
  terminalClaimed = false
  /** executor 已返回或抛出，但上传与终态收尾可能尚未完成。 */
  executorReturned = false
  /** executor 及其拥有的收尾都已结束。收尾抛错时保持 false。 */
  executorFinished = false
  /** capability 留下的正向退出证据。与 executorFinished 都成立后才发送。 */
  exitConfirmed = false
  /** 本次已尝试发布退出确认，避免迟到证据与收尾完成重复发送。 */
  exitPublished = false
  /** 终态之后仍保留，供迟到证据发布；不依赖 running map。 */
  private exitPublisher: ((invocation: RunningInvocation) => void) | null = null
  deadlineTimer: NodeJS.Timeout | null = null

  constructor(
    readonly invocationId: string,
    readonly controller: AbortController,
    /** INVOKE 时刻协商的资源预算快照；连接断开重置运行时预算也不影响在途执行。 */
    readonly maxResourceBytes: number,
  ) {}

  /** executor 已离开。此前缓存的证据这时才允许进入发布判断。 */
  markExecutorReturned(): void {
    this.executorReturned = true
    this.exitPublisher?.(this)
  }

  /** 上传与终态收尾已完成。收尾抛错时不得调用。 */
  finishOwnedCleanup(): void {
    this.executorReturned = true
    this.executorFinished = true
    this.exitPublisher?.(this)
  }

  /** 记录正向证据。executor 尚未返回时只缓存，返回后才尝试发布。 */
  noteExitEvidence(publisher: (invocation: RunningInvocation) => void): void {
    this.exitConfirmed = true
    this.exitPublisher = publisher
    if (this.executorReturned) {
      publisher(this)
    }
  }

  /** 原子抢占终态仲裁权（terminal-once 互斥点）。 */
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

  /** 终态抢占与资源上传控制帧出站互斥：终态已抢占时控制帧确定不发送。 */
  trySendControl(sender: () => boolean): boolean {
    if (this.terminalClaimed) {
      return false
    }
    return sender()
  }
}

function requireInvocationId(envelope: Envelope): string {
  const invocationId = envelope.invocationId
  if (invocationId === null || invocationId.trim() === '') {
    throw new ProtocolError(`${envelope.messageType} requires non-null invocationId`)
  }
  return invocationId
}

function requiredPositiveInt(payload: Record<string, unknown>, frame: string, field: string): number {
  const value = payload[field]
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) {
    throw new ProtocolError(`${frame} must declare integer '${field}'`)
  }
  if (value <= 0) {
    throw new ProtocolError(`${frame} '${field}' must be positive`)
  }
  return value
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

/** 供测试断言 payload 上限使用的窄导出。 */
export function utf8LengthOf(value: string): number {
  return utf8ByteLength(value, 'value')
}
