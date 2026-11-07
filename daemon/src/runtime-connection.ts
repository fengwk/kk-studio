import type { Envelope, UpgradeRequired } from './generated/control_pb.js'
import { validate } from './protocol/control/codec.js'
import type { ControlConnection, ControlTransport, ControlTransportListener } from './transport/index.js'

export type RuntimeState = 'STOPPED' | 'DISCONNECTED' | 'CONNECTING' | 'READY' | 'FAILED'

/** 一条已建立的控制连接及其连接代际；握手就绪只由生命周期推进。 */
export class ActiveConnection {
  ready = false

  constructor(
    readonly generation: number,
    readonly connection: ControlConnection,
  ) {}
}

export interface ConnectionLifecycleCallbacks {
  onEnvelope(generation: number, envelope: Envelope): void
  onUpgradeFailed(generation: number, statusCode: number, upgradeRequired?: UpgradeRequired): void
  /** active 已清空且状态已切到 DISCONNECTED 后调用；连接与 IDE 在途请求由会话侧中止。 */
  onDisconnected(connection: ActiveConnection | null): void
}

export interface ConnectionLifecycleOptions {
  transport: ControlTransport
  initialReconnectDelayMillis: number
  maxReconnectDelayMillis: number
  callbacks: ConnectionLifecycleCallbacks
}

/**
 * 控制连接生命周期：连接代际、重连退避、READY 与终止。
 *
 * <p>这里不承载会话、资源或 IDE 语义；断开时只通知会话侧清理，再由本类安排下一次重连。
 */
export class ConnectionLifecycle {
  private state: RuntimeState = 'STOPPED'
  private failureReason: string | null = null
  private started = false
  private closed = false
  private connecting = false
  private reconnectScheduled = false
  private generation = 0
  private active: ActiveConnection | null = null
  private nextReconnectDelay: number
  private reconnectTimer: NodeJS.Timeout | null = null
  private terminationResolve: (state: RuntimeState) => void = () => {}
  private readonly termination: Promise<RuntimeState>

  constructor(private readonly options: ConnectionLifecycleOptions) {
    this.nextReconnectDelay = options.initialReconnectDelayMillis
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
    this.scheduleReconnect(0)
  }

  awaitTermination(): Promise<RuntimeState> {
    return this.termination
  }

  getState(): RuntimeState {
    return this.state
  }

  getFailureReason(): string | null {
    return this.failureReason
  }

  getActiveConnection(): ActiveConnection | null {
    return this.active
  }

  isStarted(): boolean {
    return this.started
  }

  isClosed(): boolean {
    return this.closed
  }

  isCurrent(generation: number): boolean {
    return this.started && generation === this.generation
  }

  markReady(connection: ActiveConnection): boolean {
    if (this.closed || this.active !== connection) {
      return false
    }
    connection.ready = true
    this.state = 'READY'
    return true
  }

  /** 进入终止态并摘除连接；返回仍需由会话侧关闭的连接。 */
  beginShutdown(state: RuntimeState, reason: string | null): ActiveConnection | null {
    this.closed = true
    this.started = false
    this.state = state
    this.failureReason = reason
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    const connection = this.active
    this.active = null
    return connection
  }

  resolveTermination(state: RuntimeState): void {
    this.terminationResolve(state)
  }

  send(envelope: Envelope): boolean {
    const connection = this.active
    return connection !== null && this.sendOn(connection, envelope)
  }

  sendOn(connection: ActiveConnection, envelope: Envelope): boolean {
    if (this.active !== connection || !connection.connection.isOpen()) {
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

  handleDisconnected(generation: number): void {
    const connection = this.active
    if (connection !== null) {
      if (connection.generation !== generation || this.active !== connection) {
        return
      }
    } else if (this.state !== 'CONNECTING') {
      return
    }
    this.disconnect(generation, connection, false)
  }

  /** 优雅 GOAWAY：先摘除并关闭旧代际，再立即重连；既有退避状态不会被清零。 */
  handleGoaway(generation: number): void {
    const connection = this.active
    if (connection === null || connection.generation !== generation || !this.isCurrent(generation)) {
      return
    }
    this.disconnect(generation, connection, true)
  }

  /**
   * 统一断开路径：先摘除 active 并切到 DISCONNECTED，GOAWAY 时关闭旧连接，最后恰好通知会话一次。
   *
   * <p>关闭可能同步回调 onDisconnected；此时 active 已为空且状态不是 CONNECTING，迟到回调会被忽略。
   */
  private disconnect(generation: number, connection: ActiveConnection | null, goaway: boolean): void {
    if (!this.isCurrent(generation)) {
      return
    }
    this.active = null
    this.state = 'DISCONNECTED'
    if (goaway && connection !== null) {
      try {
        connection.connection.close()
      } catch {
        // 关闭失败仍继续断开清理与重连。
      }
    }
    this.options.callbacks.onDisconnected(connection)
    if (goaway) {
      this.scheduleReconnect(0)
      return
    }
    const delay = this.nextReconnectDelay
    this.nextReconnectDelay = Math.min(this.nextReconnectDelay * 2, this.options.maxReconnectDelayMillis)
    this.scheduleReconnect(delay)
  }

  closeFailedHandshake(connection: ActiveConnection): void {
    try {
      connection.connection.close()
    } catch {
      // 关闭失败仍要走代际断开，避免停在 CONNECTING。
    }
    this.handleDisconnected(connection.generation)
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
    const generation = ++this.generation
    const listener: ControlTransportListener = {
      onEnvelope: (envelope) => this.options.callbacks.onEnvelope(generation, envelope),
      onUpgradeFailed: (statusCode, upgradeRequired) =>
        this.options.callbacks.onUpgradeFailed(generation, statusCode, upgradeRequired),
      onGoaway: () => this.handleGoaway(generation),
      onDisconnected: () => this.handleDisconnected(generation),
    }
    this.options.transport.connect(listener).then(
      (connection) => {
        this.connecting = false
        if (!this.started || generation !== this.generation) {
          connection.close()
          return
        }
        this.active = new ActiveConnection(generation, connection)
        this.nextReconnectDelay = this.options.initialReconnectDelayMillis
        // 握手头已在 transport 建立时发送；等待服务端 POST /control 推送 SETTINGS 帧
      },
      () => {
        this.connecting = false
        this.handleDisconnected(generation)
      },
    )
  }
}
