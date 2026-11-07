import { DAEMON_VERSION } from './config.js'
import { DAEMON_VERSION_PATTERN } from './release.js'
import type { SkillPackageState } from './generated/control_pb.js'
import { InMemoryInvocationJournal, type InvocationJournal } from './journal.js'
import { ConnectionLifecycle, type RuntimeState } from './runtime-connection.js'
import { detectEnvironmentInfo } from './runtime-environment.js'
import { IdeCommandRuntime } from './runtime-ide.js'
import { InvocationRuntime } from './runtime-invocations.js'
import { OutboundControl } from './runtime-outbound.js'
import type { RuntimeOptions } from './runtime-ports.js'
import { createResourceRuntime, type ResourceRuntime } from './runtime-resources.js'
import { ControlSession } from './runtime-session.js'
import { ShutdownRuntime } from './runtime-shutdown.js'
import type { RuntimeStatus, RuntimeStatusUpdate } from './runtime-status.js'
import { UpdateRuntime } from './runtime-update.js'
import type { SkillReconciler } from './skill/reconcile.js'
import type { ControlTransport } from './transport/index.js'

/**
 * Environment Daemon 的装配与调用编排入口。
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
export { detectEnvironmentInfo } from './runtime-environment.js'

const DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 15_000
const DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS = 1_000
const DEFAULT_MAX_RECONNECT_DELAY_MILLIS = 30_000
const DEFAULT_MAX_CONCURRENT_INVOCATIONS = 64
const DEFAULT_IDE_COMMAND_TIMEOUT_MILLIS = 30_000

export class Runtime {
  private readonly transport: ControlTransport
  private readonly connection: ConnectionLifecycle
  private readonly shutdownRuntime: ShutdownRuntime
  private readonly outbound: OutboundControl
  private readonly resources: ResourceRuntime
  private readonly invocations: InvocationRuntime
  private readonly ideCommands: IdeCommandRuntime
  private readonly updates: UpdateRuntime
  private readonly session: ControlSession

  constructor(options: RuntimeOptions) {
    this.transport = options.transport
    const daemonVersion = options.daemonVersion ?? DAEMON_VERSION
    if (!DAEMON_VERSION_PATTERN.test(daemonVersion)) {
      throw new Error('daemonVersion must match [0-9A-Za-z._+-]{1,64}')
    }
    const environment = options.environment ?? detectEnvironmentInfo(options.config)
    this.shutdownRuntime = new ShutdownRuntime()
    const heartbeatIntervalMillis = requirePositive(
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
    const maxConcurrentInvocations = requirePositive(
      options.maxConcurrentInvocations ?? DEFAULT_MAX_CONCURRENT_INVOCATIONS,
      'maxConcurrentInvocations',
    )
    const ideCommandTimeoutMillis = requirePositive(
      options.ideCommandTimeoutMillis ?? DEFAULT_IDE_COMMAND_TIMEOUT_MILLIS,
      'ideCommandTimeoutMillis',
    )
    const ide = options.ide ?? null

    this.connection = new ConnectionLifecycle({
      transport: this.transport,
      initialReconnectDelayMillis,
      maxReconnectDelayMillis,
      callbacks: {
        onEnvelope: (generation, envelope) => this.session.onEnvelope(generation, envelope),
        onUpgradeFailed: (generation, statusCode, upgradeRequired) =>
          this.session.handleUpgradeFailed(generation, statusCode, upgradeRequired),
        onDisconnected: () => this.session.onConnectionLost(),
      },
    })
    this.outbound = new OutboundControl(this.connection, daemonVersion, environment)
    this.resources = createResourceRuntime({
      ...(options.resources === undefined ? {} : { resources: options.resources }),
      ...(options.putObject === undefined ? {} : { putObject: options.putObject }),
      sendControl: (envelope) => this.invocations.sendTransferControl(envelope),
    })
    this.invocations = new InvocationRuntime({
      journal: options.journal ?? new InMemoryInvocationJournal(),
      capabilities: options.capabilities,
      config: options.config,
      processes: options.processes,
      outbound: this.outbound,
      maxConcurrentInvocations,
      maxResourceBytes: () => this.session.maxResourceBytes,
      uploader: () => this.resources.uploader,
      isStarted: () => this.connection.isStarted(),
      isUpdateHeld: () => this.updates.isAdmissionHeld,
      trackProtected: (promise) => {
        this.shutdownRuntime.trackProtected(promise)
      },
      release: (phase, action) => {
        this.shutdownRuntime.release(phase, action)
      },
    })
    this.ideCommands = new IdeCommandRuntime({
      ide,
      timeoutMillis: ideCommandTimeoutMillis,
      isUpdateHeld: () => this.updates.isAdmissionHeld,
      onResult: (invocationId, reply) => {
        this.outbound.sendIdeResult(invocationId, reply)
      },
      track: (promise) => {
        this.shutdownRuntime.track(promise)
      },
    })
    this.updates = new UpdateRuntime({
      update: options.update ?? null,
      ide,
      config: options.config,
      daemonVersion,
      outbound: this.outbound,
      hasProtectedExecutions: () =>
        this.invocations.runningCount > 0 || this.shutdownRuntime.hasProtectedExecutions,
      ideCommandCount: () => this.ideCommands.inFlightCount,
      track: (promise) => {
        this.shutdownRuntime.track(promise)
      },
      collectDiagnostic: (phase, error) => {
        this.shutdownRuntime.collect(phase, error)
      },
    })
    this.session = new ControlSession({
      connection: this.connection,
      outbound: this.outbound,
      resources: this.resources,
      invocations: this.invocations,
      ideCommands: this.ideCommands,
      updates: this.updates,
      ideLease: options.ideLease ?? null,
      temporaryResources: options.temporaryResources ?? null,
      monotonicNow: options.monotonicNow ?? (() => performance.now()),
      heartbeatIntervalMillis,
      track: (promise) => {
        this.shutdownRuntime.track(promise)
      },
      stop: (reason) => this.shutdown('FAILED', reason),
    })
  }

  /** 启动连接生命周期并立即尝试建立连接；重复调用无副作用。 */
  start(): void {
    this.session.start()
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
    return this.outbound.environmentId
  }

  setSkillReconciler(reconciler: SkillReconciler): void {
    this.session.setSkillReconciler(reconciler)
  }

  sendSkillState(states: SkillPackageState[]): boolean {
    return this.session.sendSkillState(states)
  }

  getJournal(): InvocationJournal {
    return this.invocations.journal
  }

  /** 当前完整运行状态快照；与 READY/HEARTBEAT/RUNTIME_STATUS 的 wire 结构一致。 */
  getRuntimeStatus(): RuntimeStatus {
    return this.updates.plainSnapshot()
  }

  /** 当前更新字节进度；无更新时 null。供更新器/运维适配器读取同一份事实，不产生历史。 */
  getUpdateProgress(): RuntimeStatusUpdate | null {
    return this.updates.updateProgress()
  }

  /** 主动停机；幂等。 */
  close(): void {
    this.shutdown('STOPPED', null)
  }

  getShutdownFailures(): readonly unknown[] {
    return this.shutdownRuntime.failures
  }

  /**
   * 停机顺序：先停止连接与定时器，再逆序释放资源与在途执行，最后等全部在途执行真正收敛才发布终止。
   *
   * <p>每个独立释放步骤的失败只记录不阻断其余步骤；未收敛在途执行的失败同样进入聚合。
   */
  private shutdown(terminalState: RuntimeState, reason: string | null): void {
    if (this.connection.isClosed()) {
      return
    }
    const connection = this.connection.beginShutdown(terminalState, reason)
    this.session.stopTimers()
    this.shutdownRuntime.release('resource transfer client close', () => this.resources.close())
    if (connection !== null) {
      this.shutdownRuntime.release('connection close', () => connection.connection.close())
    }
    this.session.clearLeaseRound()
    this.invocations.cancelForShutdown()
    this.ideCommands.abortAll('SHUTDOWN')
    this.updates.abortForShutdown()
    void this.shutdownRuntime.finalize(
      () => this.transport.close(),
      () => this.connection.resolveTermination(terminalState),
    )
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
