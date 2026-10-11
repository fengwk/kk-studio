import { hostname, homedir, platform, userInfo } from 'node:os'
import { join, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'

import type {
  CapabilityCall,
  CapabilityContext,
  CapabilityExecutor,
  CapabilityResult,
  DaemonConfig,
  IdeCommand,
  IdeService,
  ProcessSupervisor,
  ResourceUploader,
  ResultContent,
} from './contracts.js'
import { DAEMON_VERSION, readRegistrationToken } from './config.js'
import {
  CAPABILITY_CATALOG_VERSION,
  PROTOCOL_VERSION,
  READY_CAPABILITIES_VERSION,
  ProtocolError,
  decodeEnvelope,
  decodeIdeCommand,
  decodeInvoke,
  decodeUpdateCommand,
  encodeCapabilities,
  encodeCompleted,
  encodeEnvelope,
  encodeIdeReply,
  encodeProgress,
  encodeUpdateResult,
  errorPayload,
  utf8ByteLength,
  type EnvironmentInfo,
  type Envelope,
  type MessageType,
  type UpdateCommand,
  type UpdateResult,
} from './protocol.js'
import {
  InMemoryInvocationJournal,
  isTerminalState,
  type InvocationJournal,
  type JournalEntry,
  type TerminalMessage,
} from './journal.js'
import type { ControlConnection, ControlTransport, ControlTransportListener } from './transport/control.js'
import {
  createHttpPutObject,
  createResourceTransferClient,
  type PresignedPutSender,
  type ResourceTransferClient,
} from './transport/resources.js'

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

/** 受控临时资源清扫端口；具体 workspace 存储由 coding 片提供。 */
export interface TemporaryResourceSweeper {
  sweep(ttlSeconds: number): Promise<void>
}

/** 受管更新端口；由 {@code src/update} 提供。UPDATE 结果只暴露运行时用于编码 UPDATE_RESULT 的窄结构。 */
export interface UpdateOutcomeLike {
  phase: string
  message?: string | null
}

export interface UpdatePort {
  handleUpdate(
    command: Record<string, unknown>,
    config: DaemonConfig,
    signal: AbortSignal,
  ): Promise<UpdateOutcomeLike>
}

export interface RuntimeOptions {
  config: DaemonConfig
  transport: ControlTransport
  /** capability id → 本地执行器；由各 capability 片合并后注入。 */
  capabilities: Record<string, CapabilityExecutor>
  /** 子进程监督端口；由 native 片提供。 */
  processes: ProcessSupervisor
  /** READY 上报的宿主 metadata；缺省时按当前进程检测。 */
  environment?: EnvironmentInfo
  capabilityCatalogVersion?: string
  daemonVersion?: string
  journal?: InvocationJournal
  /** 自定义资源上传端口；缺省时使用内置控制面客户端。 */
  resources?: ResourceUploader
  /** 内置资源客户端的预签名 PUT 实现；缺省使用 fetch。 */
  putObject?: PresignedPutSender
  update?: UpdatePort
  ide?: IdeService
  temporaryResources?: TemporaryResourceSweeper
  heartbeatIntervalMillis?: number
  initialReconnectDelayMillis?: number
  maxReconnectDelayMillis?: number
  maxConcurrentInvocations?: number
  ideCommandTimeoutMillis?: number
}

const FALLBACK_FAILURE_MESSAGE = 'capability execution failed'
const DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 15_000
const DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS = 1_000
const DEFAULT_MAX_RECONNECT_DELAY_MILLIS = 30_000
const DEFAULT_MAX_CONCURRENT_INVOCATIONS = 64
const DEFAULT_IDE_COMMAND_TIMEOUT_MILLIS = 30_000
/** 在途 IDE 请求条数上界：OPEN_STREAM 可无限存活，必须以此硬上限防止无界 map 与资源耗尽。 */
const MAX_IN_FLIGHT_IDE_COMMANDS = 256
const IDE_BUSY_MESSAGE = 'too many in-flight IDE commands'
/** 内存中保留的受管更新回执条数上界：足够覆盖少量重发与重连重放，不无界累积。 */
const MAX_TRACKED_UPDATE_OPERATIONS = 8
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
  private readonly resources: ResourceUploader | null
  private readonly resourceClient: ResourceTransferClient | null
  private readonly update: UpdatePort | null
  private readonly ide: IdeService | null
  private readonly temporaryResources: TemporaryResourceSweeper | null
  private readonly heartbeatIntervalMillis: number
  private readonly initialReconnectDelayMillis: number
  private readonly maxReconnectDelayMillis: number
  private readonly maxConcurrentInvocations: number
  private readonly ideCommandTimeoutMillis: number
  private readonly daemonInstanceId = randomUUID()
  private readonly running = new Map<string, RunningInvocation>()
  private readonly ideInFlight = new Map<string, AbortController>()
  private readonly updateResults = new Map<string, string>()
  /** 所有已登记的在途执行（capability/IDE/update）；停机后必须全部收敛才发布完成信号。 */
  private readonly inFlight = new Set<Promise<void>>()
  private activeUpdateController: AbortController | null = null

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
  private activeUpdateOperationId: string | null = null

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
    this.temporaryResources = options.temporaryResources ?? null
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
    if (options.resources !== undefined) {
      this.resources = options.resources
      this.resourceClient = null
    } else {
      const client = createResourceTransferClient({
        sendControl: (messageType, invocationId, payloadJson) =>
          this.sendTransferControl(messageType, invocationId, payloadJson),
        putObject: options.putObject ?? createHttpPutObject(),
      })
      this.resources = client
      this.resourceClient = client
    }
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
    this.resourceClient?.onConnectionLost()
    this.maxResourceBytes = 0
    this.boundEnvironmentId = null
    this.state = 'DISCONNECTED'
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
    })
    return this.sendOn(connection, 'READY', null, payload)
  }

  private sendHeartbeat(): void {
    if (this.state === 'READY') {
      this.send('HEARTBEAT', null, '{}')
    }
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
      this.resourceClient?.onConnectionReady()
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
    this.resourceClient?.onTicket(invocationId, JSON.stringify(envelope.payload))
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
      }
      void this.track(this.execute(invocation, executor, call))
    } catch (error) {
      this.terminal(invocationId, 'FAILED', JSON.stringify(errorPayload(safeMessage(error))), null)
    }
  }

  private async execute(
    invocation: RunningInvocation,
    executor: CapabilityExecutor,
    call: CapabilityCall,
  ): Promise<void> {
    try {
      const result = await executor(call, this.capabilityContext(invocation))
      if (invocation.terminalClaimed) {
        return
      }
      if (result.callId !== invocation.invocationId) {
        this.terminal(
          invocation.invocationId,
          'FAILED',
          JSON.stringify(errorPayload('capability result callId does not match invocationId')),
          null,
        )
        return
      }
      const payloadJson = await encodeCompleted(result, invocation.maxResourceBytes, (content) => {
        const resources = this.resources
        if (resources === null) {
          throw new ProtocolError('resource upload is not configured')
        }
        return resources.upload(invocation.invocationId, content, invocation.controller.signal)
      })
      this.terminal(invocation.invocationId, 'COMPLETED', payloadJson, null)
    } catch (error) {
      if (invocation.terminalClaimed) {
        return
      }
      this.terminal(invocation.invocationId, 'FAILED', JSON.stringify(errorPayload(safeMessage(error))), null)
    }
  }

  private capabilityContext(invocation: RunningInvocation): CapabilityContext {
    const resources = this.resources
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
    }
  }

  private progress(invocation: RunningInvocation, result: CapabilityResult): void {
    if (invocation.terminalClaimed || !this.isRunning(invocation.invocationId)) {
      return
    }
    try {
      const payloadJson = encodeProgress(result)
      this.send('PROGRESS', invocation.invocationId, payloadJson)
    } catch (error) {
      this.terminal(
        invocation.invocationId,
        'FAILED',
        JSON.stringify(errorPayload(`cannot encode partial capability result: ${safeMessage(error)}`)),
        null,
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
    this.terminal(invocationId, 'CANCELLED', JSON.stringify({ reason: 'cancelled' }), 'CANCELLED')
  }

  private replay(entry: JournalEntry): void {
    if (entry.state === 'RUNNING') {
      this.send('STARTED', entry.invocationId, JSON.stringify({ replayed: true }))
      return
    }
    const terminal = entry.terminalMessage
    if (terminal !== null) {
      this.send(terminal.messageType, entry.invocationId, terminal.payloadJson)
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
      this.terminal(
        invocation.invocationId,
        'FAILED',
        JSON.stringify({ message: `capability execution timed out after ${timeoutMillis}ms` }),
        'TIMED_OUT',
      )
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
    const cached = this.updateResults.get(command.operationId)
    if (cached !== undefined) {
      this.sendUpdateResult(cached)
      return
    }
    if (this.activeUpdateOperationId !== null) {
      if (this.activeUpdateOperationId === command.operationId) {
        return
      }
      throw new ProtocolError('a different update operation is already in progress')
    }
    this.activeUpdateOperationId = command.operationId
    this.sendUpdateResult(
      encodeUpdateResult({ operationId: command.operationId, phase: 'ACCEPTED', message: null }),
    )
    const controller = new AbortController()
    this.activeUpdateController = controller
    void this.track(this.runUpdate(command, controller))
  }

  private async runUpdate(command: UpdateCommand, controller: AbortController): Promise<void> {
    let payloadJson: string
    try {
      if (this.update === null) {
        throw new Error('managed update is not configured')
      }
      const raw = await this.update.handleUpdate(
        command as unknown as Record<string, unknown>,
        this.config,
        controller.signal,
      )
      payloadJson = encodeUpdateResult(normalizeUpdateResult(raw, command.operationId))
    } catch (error) {
      payloadJson = encodeUpdateResult({
        operationId: command.operationId,
        phase: 'FAILED',
        message: safeMessage(error),
      })
    } finally {
      this.activeUpdateOperationId = null
      if (this.activeUpdateController === controller) {
        this.activeUpdateController = null
      }
    }
    this.rememberUpdateResult(command.operationId, payloadJson)
    if (this.state === 'READY') {
      this.send('UPDATE_RESULT', null, payloadJson)
    }
  }

  private rememberUpdateResult(operationId: string, payloadJson: string): void {
    this.updateResults.delete(operationId)
    this.updateResults.set(operationId, payloadJson)
    while (this.updateResults.size > MAX_TRACKED_UPDATE_OPERATIONS) {
      const oldest = this.updateResults.keys().next()
      if (oldest.done === true) {
        break
      }
      this.updateResults.delete(oldest.value)
    }
  }

  private resendUpdateResults(): void {
    const active = this.activeUpdateOperationId
    if (active !== null && !this.updateResults.has(active)) {
      this.sendUpdateResult(
        encodeUpdateResult({ operationId: active, phase: 'ACCEPTED', message: null }),
      )
      return
    }
    let last: string | undefined
    for (const payloadJson of this.updateResults.values()) {
      last = payloadJson
    }
    if (last !== undefined) {
      this.sendUpdateResult(last)
    }
  }

  private sendUpdateResult(payloadJson: string): void {
    if (this.state === 'READY') {
      this.send('UPDATE_RESULT', null, payloadJson)
    }
  }

  // -------------------------------------------------------------------------
  // IDE 请求（元数据动作有独立 deadline；OPEN_STREAM 是活动通道，不进入 Agent journal，
  // 断线/停机中止而不跨代际重放副作用）
  // -------------------------------------------------------------------------

  private handleIdeCommand(envelope: Envelope): void {
    if (this.state !== 'READY' || envelope.environmentId === null) {
      throw new ProtocolError('IDE_COMMAND requires a READY bound connection')
    }
    const invocationId = requireInvocationId(envelope)
    const command = decodeIdeCommand(JSON.stringify(envelope.payload))
    if (this.ideInFlight.has(invocationId)) {
      // 同连接内重复的 IDE 请求不再执行副作用。
      return
    }
    if (this.ide === null) {
      this.send('IDE_RESULT', invocationId, encodeIdeReply({ ok: false, message: 'IDE service is unavailable' }))
      return
    }
    // 硬上限在插入前判定：同 ID 重复已在上面去重、不占名额；不同 ID 洪泛到上限一律固定 busy，绝不无界增长。
    if (this.ideInFlight.size >= MAX_IN_FLIGHT_IDE_COMMANDS) {
      this.send('IDE_RESULT', invocationId, encodeIdeReply({ ok: false, message: IDE_BUSY_MESSAGE }))
      return
    }
    const controller = new AbortController()
    this.ideInFlight.set(invocationId, controller)
    // 只有元数据动作有固定 deadline；OPEN_STREAM 是活动通道，其寿命由 IDE 流传输的 connect 超时与
    // peer/控制代际失效决定，绝不被运行时 30s 上限切断。
    const timer =
      command.action === 'OPEN_STREAM'
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
      this.send('IDE_RESULT', invocationId, encodeIdeReply(reply))
    } catch (error) {
      this.send('IDE_RESULT', invocationId, encodeIdeReply({ ok: false, message: safeMessage(error) }))
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
    this.resourceClient?.close()
    if (connection !== null) {
      try {
        connection.connection.close()
      } catch {
        // 连接关闭失败不影响其余清理。
      }
    }
    for (const invocation of this.running.values()) {
      invocation.controller.abort('SHUTDOWN')
      invocation.clearDeadline()
      this.journal.complete(invocation.invocationId, {
        messageType: 'CANCELLED',
        payloadJson: JSON.stringify({ reason: 'daemon shutdown' }),
      })
    }
    this.running.clear()
    for (const controller of this.ideInFlight.values()) {
      controller.abort('SHUTDOWN')
    }
    this.ideInFlight.clear()
    // 受管更新使用独立 controller；停机必须中止，否则其 OS 作业句柄悬空。
    this.activeUpdateController?.abort('SHUTDOWN')
    this.activeUpdateController = null
    try {
      this.transport.close()
    } catch {
      // transport 关闭失败不影响终止收敛。
    }
    void this.finalize(terminalState)
  }

  /** 登记一个在途执行 Promise，并在其收敛后自动移除。 */
  private track(promise: Promise<void>): Promise<void> {
    this.inFlight.add(promise)
    const remove = (): void => {
      this.inFlight.delete(promise)
    }
    promise.then(remove, remove)
    return promise
  }

  /**
   * 停机后等待所有在途执行（capability/IDE/update）真正收敛，再发布终止与完成信号。
   *
   * <p>运行时调度的 deadline 已在 shutdown 中清除、controller 已中止，因此 well-behaved 执行器必然收敛，
   * 不会因本 runtime 自身的定时器死锁。
   */
  private async finalize(terminalState: RuntimeState): Promise<void> {
    await Promise.allSettled([...this.inFlight])
    this.terminationResolve(terminalState)
  }
}

class ActiveConnection {
  helloSent = false
  ready = false
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
  deadlineTimer: NodeJS.Timeout | null = null

  constructor(
    readonly invocationId: string,
    readonly controller: AbortController,
    /** INVOKE 时刻协商的资源预算快照；连接断开重置运行时预算也不影响在途执行。 */
    readonly maxResourceBytes: number,
  ) {}

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

function normalizeUpdateResult(raw: UpdateOutcomeLike, operationId: string): UpdateResult {
  const phase = raw.phase === 'FAILED' ? 'FAILED' : 'PREPARED'
  const message = typeof raw.message === 'string' && raw.message.trim() !== '' ? raw.message : null
  return { operationId, phase, message }
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

// ---------------------------------------------------------------------------
// 宿主 environment metadata 检测
// ---------------------------------------------------------------------------

/** 采集真实进程宿主事实：OS family、时区、进程用户、canonical HOME、稳定说明与受控临时目录。 */
export function detectEnvironmentInfo(config: DaemonConfig, tempDirectory?: string): EnvironmentInfo {
  const operatingSystem = detectOperatingSystem()
  const home = canonicalHomeDirectory(homedir())
  return {
    operatingSystem,
    architecture: detectArchitecture(),
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
    userName: safeUserName(),
    homeDirectory: home,
    note: config.note ?? defaultNote(operatingSystem),
    tempDirectory: tempDirectory ?? join(resolve(config.dataDir), 'tmp'),
  }
}

/** 把 Node 的 process.arch 归一到发行制品命名；不支持的架构失败关闭，绝不猜测。 */
export function detectArchitecture(): EnvironmentInfo['architecture'] {
  switch (process.arch) {
    case 'x64':
      return 'amd64'
    case 'arm64':
      return 'arm64'
    default:
      throw new Error(`unsupported daemon architecture: ${process.arch}`)
  }
}

function detectOperatingSystem(): EnvironmentInfo['operatingSystem'] {
  const name = platform()
  if (name === 'win32') {
    return 'windows'
  }
  if (name === 'darwin') {
    return 'macos'
  }
  if (name === 'linux') {
    const env = process.env
    if (
      (env.WSL_DISTRO_NAME !== undefined && env.WSL_DISTRO_NAME !== '') ||
      (env.WSL_INTEROP !== undefined && env.WSL_INTEROP !== '') ||
      procContainsMicrosoft('/proc/version') ||
      procContainsMicrosoft('/proc/sys/kernel/osrelease')
    ) {
      return 'wsl'
    }
    return 'linux'
  }
  throw new Error(`unsupported daemon operating system: ${name}`)
}

function procContainsMicrosoft(path: string): boolean {
  try {
    return readFileSync(path, 'utf8').toLowerCase().includes('microsoft')
  } catch {
    return false
  }
}

function canonicalHomeDirectory(home: string): string {
  try {
    return resolve(home)
  } catch {
    return home
  }
}

function safeUserName(): string {
  try {
    return userInfo().username
  } catch {
    return process.env.USER ?? process.env.USERNAME ?? hostname()
  }
}

/** 按实测 OS 生成稳定默认说明；不构成 cwd、默认 workdir 或沙箱。 */
export function defaultNote(operatingSystem: EnvironmentInfo['operatingSystem']): string {
  switch (operatingSystem) {
    case 'windows':
      return 'Windows environment.'
    case 'wsl':
      return 'WSL environment. Windows files may be accessible under /mnt/<drive>, and some Windows commands may be invocable from WSL.'
    case 'linux':
      return 'Linux environment.'
    case 'macos':
      return 'macOS environment.'
  }
}

/** 供测试断言 payload 上限使用的窄导出。 */
export function utf8LengthOf(value: string): number {
  return utf8ByteLength(value, 'value')
}

/** 供类型收窄：把 ResourceUploader 结果转成 wire 资源内容。 */
export type UploadedResource = Extract<ResultContent, { type: 'resource' }>
