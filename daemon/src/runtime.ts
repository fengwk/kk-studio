import { randomUUID } from 'node:crypto'
import { create } from '@bufbuild/protobuf'

import type {
  CapabilityCall,
  CapabilityContext,
  CapabilityExecutor,
  CapabilityResult,
  DaemonConfig,
  IdeCommand,
  IdeLeaseHandler,
  IdeReply,
  IdeService,
  ProcessSupervisor,
} from './contracts.js'
import { DAEMON_VERSION } from './config.js'
import {
  PROTOCOL_VERSION,
} from './protocol/control/codec.js'
import {
  CancelledSchema,
  CapabilityResultSchema,
  CompletedSchema,
  ContentSchema,
  EnvelopeSchema,
  ExecutionExitedSchema,
  FailedSchema,
  HeartbeatSchema,
  IdeAction,
  IdeOpenMode,
  IdeResultSchema,
  IdeViewState,
  IdeViewSummarySchema,
  JsonContentSchema,
  MessageType,
  ProgressSchema,
  ReadySchema,
  RecoverNotFoundSchema,
  ResourceContentSchema,
  RuntimeStatusSchema,
  StartedSchema,
  TextContentSchema,
  UpdatePhase,
  UpdateResultSchema,
  UpdateSchema,
  type Content,
  type Envelope,
  type EnvironmentInfo,
  type IdeCommand as ProtoIdeCommand,
  type IdeLease,
  type IdeResult,
  type Invoke,
  type ResourceUploadTicket,
  type RuntimeSnapshot,
  type Settings,
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
} from './journal.js'
import { RuntimeStatusTracker, type RuntimeStatusUpdate, type UpdateByteProgress } from './runtime-status.js'
import { detectEnvironmentInfo } from './runtime-environment.js'
import {
  admitIdeCommand,
  ideFailureReply,
  requireFreshIdeLease,
  toProtoIdeResult,
  type ActiveIdeLeaseRound,
} from './runtime-ide.js'
import type { RuntimeOptions, TemporaryResourceSweeper, UpdatePort } from './runtime-ports.js'
import { createResourceRuntime } from './runtime-resources.js'
import { recordShutdownFailure, releaseStep } from './runtime-shutdown.js'
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
  private readonly daemonInstanceId = randomUUID()
  private readonly running = new Map<string, RunningInvocation>()
  private readonly ideInFlight = new Map<string, AbortController>()
  private readonly updateResults = new Map<string, UpdateReceipt>()
  private readonly shutdownFailures: unknown[] = []
  private readonly inFlight = new Set<Promise<void>>()
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
      sendControl: (envelope) => this.sendEnvelope(this.activeConnection, envelope),
    })
    this.termination = new Promise<RuntimeState>((resolveTermination) => {
      this.terminationResolve = resolveTermination
    })
  }

  start(): void {
    if (this.closed || this.started) {
      return
    }
    this.started = true
    this.state = 'DISCONNECTED'
    this.heartbeatTimer = setInterval(() => this.sendHeartbeat(), this.heartbeatIntervalMillis)
    this.scheduleReconnect(0)
  }

  awaitTermination(): Promise<RuntimeState> {
    return this.termination
  }

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

  getRuntimeStatus(): RuntimeSnapshot {
    return this.currentRuntimeStatus()
  }

  getShutdownFailures(): readonly unknown[] {
    return this.shutdownFailures
  }

  getUpdateProgress(): RuntimeStatusUpdate | null {
    this.refreshUpdateProgress()
    return this.runtimeStatus.getUpdate()
  }

  close(): void {
    this.shutdown('STOPPED', null)
  }

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
      onDisconnected: () => this.handleDisconnected(generation),
      onGoaway: () => this.handleGoaway(generation),
      onUpgradeFailed: (statusCode, upgrade) => this.handleUpgradeFailed(generation, statusCode, upgrade),
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
    this.resources.onConnectionLost()
    this.maxResourceBytes = 0
    this.boundEnvironmentId = null
    this.state = 'DISCONNECTED'
    this.activeIdeLeaseRound = null
    connection?.abort.abort('CONTROL_DISCONNECTED')
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
      if (upgradeRequired?.artifactUrl && upgradeRequired?.artifactSha256) {
        const updateCommand = create(UpdateSchema, {
          operationId: randomUUID(),
          targetVersion: String(upgradeRequired.targetProtocolVersion),
          artifactUrl: upgradeRequired.artifactUrl,
          artifactSha256: upgradeRequired.artifactSha256,
        })
        const active: ActiveUpdate = {
          operationId: updateCommand.operationId ?? '',
          commandKey: JSON.stringify([
            updateCommand.targetVersion ?? '',
            updateCommand.artifactUrl ?? '',
            updateCommand.artifactSha256 ?? '',
          ]),
          targetVersion: updateCommand.targetVersion ?? '',
          controller: new AbortController(),
          receipt: {
            commandKey: '',
            result: create(UpdateResultSchema, {
              operationId: updateCommand.operationId ?? '',
              phase: UpdatePhase.UPDATE_ACCEPTED,
            }),
          },
          prepared: false,
        }
        this.activeUpdate = active
        this.runtimeStatus.beginUpdate(updateCommand.targetVersion ?? '')
        void this.track(this.executeUpdate(active, updateCommand))
        return
      }
      process.stderr.write('daemon update required: manual reinstallation needed\n')
      this.nextReconnectDelay = this.maxReconnectDelayMillis
      this.handleDisconnected(generation)
      return
    }
    this.handleDisconnected(generation)
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
    return this.sendEnvelope(connection, readyEnvelope)
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
    const sent = this.sendEnvelope(this.activeConnection, heartbeatEnvelope)
    this.activeIdeLeaseRound =
      sent && ideLeaseRound !== undefined
        ? { round: ideLeaseRound, sentAt: this.monotonicNow(), consumed: false }
        : null
  }

  private sendEnvelope(connection: ActiveConnection | null, envelope: Envelope): boolean {
    if (connection === null || this.activeConnection !== connection || !connection.connection.isOpen()) {
      return false
    }
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
        case MessageType.SETTINGS:
          this.handleSettings(connection, envelope)
          return
        case MessageType.INVOKE:
          this.handleInvoke(connection, envelope)
          return
        case MessageType.CANCEL:
          this.handleCancel(envelope)
          return
        case MessageType.RECOVER:
          this.handleRecover(envelope)
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
    } catch {
      this.handleDisconnected(connection.generation)
    }
  }

  private verifyScope(envelope: Envelope): void {
    if (envelope.messageType === MessageType.SETTINGS) {
      if (!envelope.environmentId) {
        throw new Error('SETTINGS must declare non-empty environmentId')
      }
      return
    }
    const bound = this.boundEnvironmentId
    if (bound !== null && envelope.environmentId && bound !== envelope.environmentId) {
      throw new Error(`envelope environmentId does not match daemon: ${envelope.environmentId}`)
    }
  }

  private handleSettings(connection: ActiveConnection, envelope: Envelope): void {
    if (connection.settingsReceived) {
      throw new Error('SETTINGS may only be received once per connection')
    }
    connection.settingsReceived = true
    if (!envelope.environmentId) {
      throw new Error('SETTINGS must declare non-empty environmentId')
    }
    this.boundEnvironmentId = envelope.environmentId
    const settings = envelope.payload.value as Settings
    const budgets = settings.resourceBudgets
    this.maxResourceBytes = Number(budgets?.maxResourceBytes ?? 0n)
    const ttl = Number(budgets?.temporaryResourceTtlSeconds ?? 0n)
    const cleanup = Number(budgets?.temporaryResourceCleanupIntervalSeconds ?? 0n)
    this.applyTemporaryResourcePolicy(ttl, cleanup)
    if (!this.sendReady(connection)) {
      if (this.activeConnection === connection) {
        this.handleDisconnected(connection.generation)
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
    if (this.scheduledCleanupIntervalSeconds === cleanupIntervalSeconds && this.sweepTimer !== null) {
      return
    }
    if (this.sweepTimer !== null) {
      clearInterval(this.sweepTimer)
      this.sweepTimer = null
    }
    this.scheduledCleanupIntervalSeconds = cleanupIntervalSeconds
    if (cleanupIntervalSeconds <= 0) {
      return
    }
    this.sweepTimer = setInterval(() => {
      this.temporaryResources?.sweep(this.temporaryResourceTtlSeconds).catch((error) => {
        this.shutdownFailures.push(error)
      })
    }, cleanupIntervalSeconds * 1000)
  }

  private handleError(connection: ActiveConnection, _envelope: Envelope): void {
    this.handleDisconnected(connection.generation)
  }

  private handleInvoke(connection: ActiveConnection, envelope: Envelope): void {
    if (this.state !== 'READY') {
      return
    }
    const invocationId = requireInvocationId(envelope)
    const invoke = envelope.payload.value as Invoke
    let callArguments: Record<string, unknown> = {}
    try {
      callArguments = JSON.parse(invoke.argumentsJson ?? '{}') as Record<string, unknown>
    } catch {}

    const expectedFile =
      invoke.expectedFile?.path !== undefined && invoke.expectedFile?.sha256 !== undefined
        ? { path: invoke.expectedFile.path, sha256: invoke.expectedFile.sha256 }
        : undefined

    const call: CapabilityCall = {
      invocationId,
      capabilityId: invoke.capabilityId ?? '',
      arguments: callArguments,
      timeoutMillis: Number(invoke.timeoutMillis),
      expectedFile,
    }

    const start = this.journal.start(call.invocationId)
    if (!start.created) {
      this.replay(start.entry)
      return
    }

    if (this.activeUpdate !== null) {
      this.terminal(
        call.invocationId,
        MessageType.FAILED,
        this.buildFailedEnvelope(call.invocationId, 'environment update is in progress'),
        null,
      )
      return
    }
    if (this.running.size >= this.maxConcurrentInvocations) {
      this.terminal(
        call.invocationId,
        MessageType.FAILED,
        this.buildFailedEnvelope(call.invocationId, 'too many concurrent capability invocations'),
        null,
      )
      return
    }
    const executor = this.capabilities[call.capabilityId]
    if (executor === undefined) {
      this.terminal(
        call.invocationId,
        MessageType.FAILED,
        this.buildFailedEnvelope(call.invocationId, `unknown capability: ${call.capabilityId}`),
        null,
      )
      return
    }

    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort('TIMED_OUT'), call.timeoutMillis)
    const running = new RunningInvocation(call.invocationId, controller, timer)
    this.running.set(call.invocationId, running)
    const execution = this.executeCapability(connection, running, call, executor)
    void this.track(execution)
    void this.trackProtected(execution)
  }

  private async executeCapability(
    _connection: ActiveConnection,
    invocation: RunningInvocation,
    call: CapabilityCall,
    executor: CapabilityExecutor,
  ): Promise<void> {
    try {
      const context = this.capabilityContext(invocation)
      const result = await executor(call, context)
      invocation.executorReturned = true
      clearTimeout(invocation.timer)
      if (invocation.terminalClaimed) {
        return
      }
      const envelope = create(EnvelopeSchema, {
        messageType: MessageType.COMPLETED,
        environmentId: this.boundEnvironmentId ?? undefined,
        invocationId: invocation.invocationId,
        payload: {
          case: 'completed',
          value: create(CompletedSchema, {
            result: toProtoCapabilityResult(result),
          }),
        },
      })
      this.terminal(invocation.invocationId, MessageType.COMPLETED, envelope, null)
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
        const payloadReason = reason === 'SHUTDOWN' ? 'daemon shutdown' : 'cancelled'
        const envelope = create(EnvelopeSchema, {
            messageType: MessageType.CANCELLED,
          environmentId: this.boundEnvironmentId ?? undefined,
          invocationId: invocation.invocationId,
          payload: {
            case: 'cancelled',
            value: create(CancelledSchema, { reason: payloadReason }),
          },
        })
        this.terminal(invocation.invocationId, MessageType.CANCELLED, envelope, reason)
        if (executorFailed) {
          invocation.finishOwnedCleanup()
        }
        return
      }
      if (reason === 'TIMED_OUT') {
        const envelope = this.buildFailedEnvelope(
          invocation.invocationId,
          `capability execution timed out after ${call.timeoutMillis}ms`,
        )
        this.terminal(invocation.invocationId, MessageType.FAILED, envelope, 'TIMED_OUT')
        if (executorFailed) {
          invocation.finishOwnedCleanup()
        }
        return
      }
      const envelope = this.buildFailedEnvelope(invocation.invocationId, safeMessage(error))
      this.terminal(invocation.invocationId, MessageType.FAILED, envelope, null)
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
      progress: async (result) => {
        if (invocation.terminalClaimed) {
          return
        }
        const envelope = create(EnvelopeSchema, {
            messageType: MessageType.PROGRESS,
          environmentId: this.boundEnvironmentId ?? undefined,
          invocationId: invocation.invocationId,
          payload: {
            case: 'progress',
            value: create(ProgressSchema, {
              result: toProtoCapabilityResult(result),
            }),
          },
        })
        this.sendEnvelope(this.activeConnection, envelope)
      },
      confirmExecutionExit: () => {
        invocation.noteExitEvidence((current) => this.publishExecutionExit(current))
      },
    }
  }

  private publishExecutionExit(invocation: RunningInvocation): void {
    if (!invocation.executorReturned || !invocation.exitConfirmed || invocation.exitPublished) {
      return
    }
    invocation.exitPublished = true
    if (!this.journal.confirmExit(invocation.invocationId)) {
      return
    }
    const exited = create(EnvelopeSchema, {
      messageType: MessageType.EXECUTION_EXITED,
      environmentId: this.boundEnvironmentId ?? undefined,
      invocationId: invocation.invocationId,
      payload: {
        case: 'executionExited',
        value: create(ExecutionExitedSchema, {}),
      },
    })
    this.sendEnvelope(this.activeConnection, exited)
  }

  private handleCancel(envelope: Envelope): void {
    const invocationId = requireInvocationId(envelope)
    const entry = this.journal.find(invocationId)
    if (entry !== undefined && isTerminalState(entry.state)) {
      this.replay(entry)
      return
    }
    const invocation = this.running.get(invocationId)
    if (invocation !== undefined) {
      invocation.controller.abort('CANCELLED')
    }
  }

  private handleRecover(envelope: Envelope): void {
    const invocationId = requireInvocationId(envelope)
    const entry = this.journal.find(invocationId)
    if (entry !== undefined) {
      this.replay(entry)
    } else {
      const notFound = create(EnvelopeSchema, {
        messageType: MessageType.RECOVER_NOT_FOUND,
        environmentId: this.boundEnvironmentId ?? undefined,
        invocationId,
        payload: {
          case: 'recoverNotFound',
          value: create(RecoverNotFoundSchema, {}),
        },
      })
      this.sendEnvelope(this.activeConnection, notFound)
    }
  }

  private replay(entry: JournalEntry): void {
    if (entry.state === 'RUNNING') {
      const started = create(EnvelopeSchema, {
        messageType: MessageType.STARTED,
        environmentId: this.boundEnvironmentId ?? undefined,
        invocationId: entry.invocationId,
        payload: {
          case: 'started',
          value: create(StartedSchema, { replayed: true }),
        },
      })
      this.sendEnvelope(this.activeConnection, started)
    } else {
      const terminal = entry.terminalMessage
      if (terminal !== null) {
        this.sendEnvelope(this.activeConnection, terminal.envelope)
      }
    }
    if (entry.exitConfirmed) {
      const exited = create(EnvelopeSchema, {
        messageType: MessageType.EXECUTION_EXITED,
        environmentId: this.boundEnvironmentId ?? undefined,
        invocationId: entry.invocationId,
        payload: {
          case: 'executionExited',
          value: create(ExecutionExitedSchema, {}),
        },
      })
      this.sendEnvelope(this.activeConnection, exited)
    }
  }

  private terminal(
    invocationId: string,
    messageType: TerminalMessage['messageType'],
    envelope: Envelope,
    _cause: InvocationTerminationCause | null,
  ): void {
    const invocation = this.running.get(invocationId)
    if (invocation !== undefined) {
      invocation.terminalClaimed = true
      clearTimeout(invocation.timer)
      this.running.delete(invocationId)
    }
    const transition = this.journal.complete(invocationId, { messageType, envelope })
    if (transition) {
      this.sendEnvelope(this.activeConnection, envelope)
    }
  }

  private handleTicket(envelope: Envelope): void {
    const invocationId = requireInvocationId(envelope)
    const ticket = envelope.payload.value as ResourceUploadTicket
    this.resources.onTicket(invocationId, ticket)
  }

  private handleIdeCommand(envelope: Envelope): void {
    if (this.state !== 'READY' || !envelope.environmentId) {
      throw new Error('IDE_COMMAND requires a READY bound connection')
    }
    const invocationId = requireInvocationId(envelope)
    const protoCommand = envelope.payload.value as ProtoIdeCommand
    const command = toContractsCommand(protoCommand)

    const admission = admitIdeCommand({
      invocationId,
      command,
      inFlight: this.ideInFlight,
      updateHeld: this.activeUpdate !== null,
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
    const timer = setTimeout(() => controller.abort('TIMED_OUT'), this.ideCommandTimeoutMillis)
    const execution = this.executeIdeCommand(invocationId, command, controller, timer)
    void this.track(execution)
  }

  private async executeIdeCommand(
    invocationId: string,
    command: IdeCommand,
    controller: AbortController,
    timer: NodeJS.Timeout,
  ): Promise<void> {
    try {
      const ide = this.ide!
      const reply = await ide.handle(command, controller.signal)
      clearTimeout(timer)
      this.sendIdeResult(invocationId, toProtoIdeResult(reply))
    } catch (error) {
      clearTimeout(timer)
      this.sendIdeResult(invocationId, ideFailureReply(error, safeMessage(error)))
    } finally {
      this.ideInFlight.delete(invocationId)
    }
  }

  private sendIdeResult(invocationId: string, result: IdeResult): void {
    const envelope = create(EnvelopeSchema, {
      messageType: MessageType.IDE_RESULT,
      environmentId: this.boundEnvironmentId ?? undefined,
      invocationId,
      payload: {
        case: 'ideResult',
        value: result,
      },
    })
    this.sendEnvelope(this.activeConnection, envelope)
  }

  private handleIdeLease(envelope: Envelope): void {
    if (this.state !== 'READY' || !envelope.environmentId) {
      throw new Error('IDE_LEASE requires a READY bound connection')
    }
    if (this.ideLease === null) {
      throw new Error('IDE lease handler is not configured')
    }
    const lease = envelope.payload.value as IdeLease
    if (lease.round === undefined) {
      throw new Error('IDE_LEASE round is required')
    }
    const activeRound = requireFreshIdeLease(this.activeIdeLeaseRound, lease.round, this.monotonicNow())
    activeRound.consumed = true
    this.activeIdeLeaseRound = activeRound
    const roundStartedAt = activeRound.sentAt
    const execution = Promise.resolve(this.ideLease.apply(lease, roundStartedAt)).catch((error) => {
      this.shutdownFailures.push(error)
    })
    void this.track(execution)
  }

  // -------------------------------------------------------------------------
  // 受管更新
  // -------------------------------------------------------------------------

  private handleUpdate(envelope: Envelope): void {
    if (this.state !== 'READY' || !envelope.environmentId) {
      throw new Error('UPDATE requires a READY bound connection')
    }
    const command = envelope.payload.value as Update
    const operationId = command.operationId ?? ''
    const decision = decideUpdate({
      command,
      active: this.activeUpdate,
      cached: this.updateResults.get(operationId),
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
      rememberUpdateResult(this.updateResults, operationId, decision.receipt)
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
    const operationId = command.operationId ?? active.operationId
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
      rememberUpdateResult(this.updateResults, operationId, receipt)
      this.deliverUpdateResult(receipt)
      return
    }
    this.refreshUpdateProgress()
    const receipt = preparedReceipt(active, command, attempt.prepared)
    active.receipt = receipt
    active.prepared = true
    this.runtimeStatus.beginUpdate(command.targetVersion ?? '')
    this.publishRuntimeStatus()
    rememberUpdateResult(this.updateResults, operationId, receipt)
    this.deliverUpdateResult(receipt)
  }

  private reportUpdateProgress(active: ActiveUpdate, progress: UpdateByteProgress): void {
    if (this.activeUpdate !== active) {
      return
    }
    if (this.runtimeStatus.reportProgress(progress)) {
      this.publishRuntimeStatus()
    }
  }

  private refreshUpdateProgress(): void {
    if (this.activeUpdate === null || this.update?.getProgress === undefined) {
      return
    }
    const current = this.update.getProgress()
    if (current !== null) {
      this.reportUpdateProgress(this.activeUpdate, current)
    }
  }

  private deliverUpdateResult(receipt: UpdateReceipt): void {
    this.sendUpdateResult(receipt.result)
    if (receipt.releasePermit !== undefined) {
      receipt.releasePermit().catch((error) => {
        this.shutdownFailures.push(error)
      })
    }
  }

  private sendUpdateResult(result: UpdateResult): void {
    const envelope = create(EnvelopeSchema, {
      messageType: MessageType.UPDATE_RESULT,
      environmentId: this.boundEnvironmentId ?? undefined,
      payload: {
        case: 'updateResult',
        value: result,
      },
    })
    this.sendEnvelope(this.activeConnection, envelope)
  }

  private resendUpdateResults(): void {
    const receipt = receiptToResend(this.activeUpdate, this.updateResults)
    if (receipt !== null) {
      this.deliverUpdateResult(receipt)
    }
  }

  private updateBusyReason(): string | null {
    if (this.running.size > 0 || this.protectedExecutions.size > 0) {
      return 'capability executions are active'
    }
    if (this.ide !== null && !this.ide.canStartUpdate()) {
      return 'IDE service is busy'
    }
    return null
  }

  private publishRuntimeStatus(): void {
    if (this.state !== 'READY') {
      return
    }
    const envelope = create(EnvelopeSchema, {
      messageType: MessageType.RUNTIME_STATUS,
      environmentId: this.boundEnvironmentId ?? undefined,
      payload: {
        case: 'runtimeStatus',
        value: create(RuntimeStatusSchema, {
          runtimeStatus: this.currentRuntimeStatus(),
        }),
      },
    })
    this.sendEnvelope(this.activeConnection, envelope)
  }

  private currentRuntimeStatus(): RuntimeSnapshot {
    this.refreshUpdateProgress()
    return this.runtimeStatus.snapshot()
  }

  private buildFailedEnvelope(invocationId: string, message: string): Envelope {
    return create(EnvelopeSchema, {
      messageType: MessageType.FAILED,
      environmentId: this.boundEnvironmentId ?? undefined,
      invocationId,
      payload: {
        case: 'failed',
        value: create(FailedSchema, { message }),
      },
    })
  }

  // -------------------------------------------------------------------------
  // 停机与生命周期收敛
  // -------------------------------------------------------------------------

  private failTerminal(reason: string): void {
    this.shutdown('FAILED', reason)
  }

  private shutdown(targetState: RuntimeState, reason: string | null): void {
    if (this.closed) {
      return
    }
    this.closed = true
    this.state = targetState
    this.failureReason = reason
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

    const connection = this.activeConnection
    this.activeConnection = null
    connection?.abort.abort('SHUTDOWN')
    connection?.connection.close()
    for (const controller of this.ideInFlight.values()) {
      controller.abort('SHUTDOWN')
    }
    this.ideInFlight.clear()
    this.resources.close()

    void (async () => {
      try {
        await this.transport.close()
      } catch (error) {
        recordShutdownFailure(this.shutdownFailures, 'transport close', error)
      }
      await Promise.allSettled([...this.inFlight])
      this.terminationResolve(this.state)
    })()
  }

  private async track(promise: Promise<void>): Promise<void> {
    this.inFlight.add(promise)
    try {
      await promise
    } catch (error) {
      recordShutdownFailure(this.shutdownFailures, 'execution', error)
    } finally {
      this.inFlight.delete(promise)
    }
  }

  private async trackProtected(promise: Promise<void>): Promise<void> {
    this.protectedExecutions.add(promise)
    try {
      await promise
    } finally {
      this.protectedExecutions.delete(promise)
    }
  }
}

class ActiveConnection {
  readonly abort = new AbortController()
  settingsReceived = false
  ready = false

  constructor(
    readonly generation: number,
    readonly connection: ControlConnection,
  ) {}
}

class RunningInvocation {
  terminalClaimed = false
  exitConfirmed = false
  exitPublished = false
  executorReturned = false
  private exitPublisher: ((invocation: RunningInvocation) => void) | null = null
  private cleanupResolve: () => void = () => {}
  readonly ownedCleanup: Promise<void>

  constructor(
    readonly invocationId: string,
    readonly controller: AbortController,
    readonly timer: NodeJS.Timeout,
  ) {
    this.ownedCleanup = new Promise<void>((resolve) => {
      this.cleanupResolve = resolve
    })
  }

  finishOwnedCleanup(): void {
    this.cleanupResolve()
    this.exitPublisher?.(this)
  }

  noteExitEvidence(publisher: (invocation: RunningInvocation) => void): void {
    this.exitConfirmed = true
    this.exitPublisher = publisher
    if (this.executorReturned) {
      publisher(this)
    }
  }
}

function requireInvocationId(envelope: Envelope): string {
  const id = envelope.invocationId
  if (!id || id.trim() === '') {
    throw new Error('envelope invocationId is required')
  }
  return id
}

function requirePositive(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer`)
  }
  return value
}

function requireNonNegative(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error(`${name} must be a non-negative integer`)
  }
  return value
}

function safeMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim() !== '') {
    return error.message
  }
  return FALLBACK_FAILURE_MESSAGE
}

function toProtoCapabilityResult(result: CapabilityResult) {
  const contents: Content[] = result.contents.map((content) => {
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
    if (content.type === 'resource') {
      return create(ContentSchema, {
        body: {
          case: 'resource',
          value: create(ResourceContentSchema, {
            uploadId: content.uploadId,
            mediaType: content.mediaType,
            name: content.name ?? undefined,
            size: BigInt(content.size),
            sha256: content.sha256,
            preview: content.preview ?? undefined,
          }),
        },
      })
    }
    throw new Error(`unsupported content type: ${(content as unknown as { type: string }).type}`)
  })

  return create(CapabilityResultSchema, {
    callId: result.callId,
    error: result.error,
    detailsJson: JSON.stringify(result.details ?? {}),
    contents,
  })
}

function toContractsCommand(proto: ProtoIdeCommand): IdeCommand {
  let action: IdeCommand['action']
  switch (proto.action) {
    case IdeAction.OPEN_VIEW:
      action = 'OPEN_VIEW'
      break
    case IdeAction.CHANGE_DIRECTORY:
      action = 'CHANGE_DIRECTORY'
      break
    case IdeAction.FOCUS_TERMINAL:
      action = 'FOCUS_TERMINAL'
      break
    case IdeAction.CLOSE_VIEW:
      action = 'CLOSE_VIEW'
      break
    case IdeAction.DETACH_VIEW:
      action = 'DETACH_VIEW'
      break
    case IdeAction.LIST_VIEWS:
      action = 'LIST_VIEWS'
      break
    default:
      throw new Error(`unknown IdeAction: ${proto.action}`)
  }

  let mode: IdeCommand['mode']
  if (proto.mode === IdeOpenMode.CREATE) {
    mode = 'create'
  } else if (proto.mode === IdeOpenMode.RESUME) {
    mode = 'resume'
  }

  return {
    action,
    viewId: proto.viewId,
    documentId: proto.documentId,
    eventNodeId: proto.eventNodeId,
    connectionId: proto.connectionId,
    claimId: proto.claimId,
    bindingEpoch: proto.bindingEpoch !== undefined ? proto.bindingEpoch.toString() : undefined,
    mode,
    directory: proto.directory,
    discard: proto.discard,
  }
}
