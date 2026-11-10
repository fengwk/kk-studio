import {
  decodeServerMessage,
  encodeClientMessage,
  type ApplicationEventClientMessage,
  type ApplicationEventServerMessage,
} from '@/shared/app-events/protocol'

/**
 * 连接状态：
 * - connecting：物理连接建立中，或 transport 已开但 connected 握手未完成（15 秒总预算）；
 * - ready：connected 帧已确认身份，按服务端 heartbeat 间隔监控活性；
 * - backoff：断线（传输丢失/握手超时/失联/服务端重启）后按退避等待自动重连；
 * - stopped：手动断开或 protocol/policy 终止；不自动重连；
 * - idle：尚未 connect() 的初始状态。
 */
export type ApplicationEventConnectionStatus =
  | 'idle'
  | 'connecting'
  | 'ready'
  | 'backoff'
  | 'stopped'

/** backoff 的结构化原因：UI 用它区分恢复路径，不解析文案。 */
export type ApplicationEventConnectionBackoffCause =
  | 'transport-lost'
  | 'handshake-timeout'
  | 'liveness-timeout'
  | 'server-restart'

/** stopped 的结构化原因：manual 是应用主动断开；protocol/policy 终止不能自动恢复。 */
export type ApplicationEventConnectionStopCause = 'manual' | 'protocol' | 'policy'

export type ApplicationEventConnectionReason =
  | { kind: 'connecting'; transportOpen: boolean }
  | { kind: 'backoff'; cause: ApplicationEventConnectionBackoffCause; retryAt: number }
  | { kind: 'stopped'; cause: ApplicationEventConnectionStopCause }

/** 只读连接快照：同一份状态驱动全局提示与 IDE 面板，不把"没有 identity"当唯一诊断。 */
export interface ApplicationEventConnectionState {
  status: ApplicationEventConnectionStatus
  reason: ApplicationEventConnectionReason | null
}

export type ApplicationEventSocketFactory = (url: string) => WebSocket

export interface ApplicationEventConnectionOptions {
  url: string
  /** 测试注入；生产默认使用全局 WebSocket。 */
  socketFactory?: ApplicationEventSocketFactory
  /** transport open（首次连接与每次重连）时触发；Manager 借此重发订阅。 */
  onOpen?: () => void
  /** 严格解码后的 server 消息；畸形帧不会到达这里。 */
  onMessage?: (message: ApplicationEventServerMessage) => void
  /** 当前物理 socket 结束（断线、主动关闭或协议终止）时触发一次；Manager 借此清空连接身份与未确认请求。 */
  onClose?: () => void
}

/** 退避序列：250ms..10s，之后保持 10s cap。 */
const RECONNECT_BACKOFF_MS = [250, 500, 1000, 2000, 5000, 10000]
/** jitter 上限：当前 base 的 20%。 */
const RECONNECT_JITTER_RATIO = 0.2
/** 服务端主动重启：立即重试。 */
const CLOSE_CODE_RESTART = 1012
/** 协议错误/策略违规：terminal，不无限重试；close code 决定停止原因。 */
const CLOSE_CODE_STOP_CAUSE: ReadonlyMap<number, 'protocol' | 'policy'> = new Map([
  [1002, 'protocol'],
  [1008, 'policy'],
])
/** 建连到 connected 帧的总握手预算；服务端不响应时据此 fence 并重连。 */
const HANDSHAKE_BUDGET_MS = 15_000
/** 失联预算：connected 帧声明的 heartbeat 间隔的整数倍。 */
const LIVENESS_MISSED_HEARTBEATS = 3

/**
 * 应用生命周期内的单例 WebSocket 连接：只负责传输、严格 codec、
 * 握手预算、活性监控、状态与重连。应用 mount 时 connect()，路由切换不重建；
 * 页面 online / visibility visible 时：健康连接保留，过期连接立即恢复。
 *
 * 每次 connect() 递增 generation：旧 socket 的回调捕获旧 generation，
 * 一旦落后即失效（no-op），因此过期 socket 的事件永远不会干扰新连接。
 * 握手超时与失联都立即 fence 旧 generation 并 best-effort close 旧 socket，
 * 不等待浏览器不一定及时派发的 close 回调；socket 的 close 仍是传输断开的
 * 权威清理点，error 只负责关闭 socket。
 */
export class ApplicationEventConnection {
  private readonly url: string
  private readonly socketFactory: ApplicationEventSocketFactory
  private readonly onOpen?: () => void
  private readonly onMessage?: (message: ApplicationEventServerMessage) => void
  private readonly onClose?: () => void

  private socket: WebSocket | null = null
  private socketOpen = false
  private generation = 0
  private state: ApplicationEventConnectionState = { status: 'idle', reason: null }
  private readonly stateListeners = new Set<(state: ApplicationEventConnectionState) => void>()
  private reconnectTimer: number | null = null
  private handshakeTimer: number | null = null
  private livenessTimer: number | null = null
  private backoffIndex = 0
  private stopped = true
  private terminal = false
  private listenersAttached = false
  private closeNotified = false
  /** connected 帧声明的服务端 heartbeat 间隔；ready 之后才有效。 */
  private heartbeatIntervalMillis = 0
  /** 最近一条合法服务端帧的单调接收时间（fake timers 会连同 Date.now 一起推进）。 */
  private lastReceivedAt = 0

  constructor(options: ApplicationEventConnectionOptions) {
    this.url = options.url
    this.socketFactory = options.socketFactory ?? ((url) => new WebSocket(url))
    this.onOpen = options.onOpen
    this.onMessage = options.onMessage
    this.onClose = options.onClose
  }

  getStatus(): ApplicationEventConnectionStatus {
    return this.state.status
  }

  getState(): ApplicationEventConnectionState {
    return this.state
  }

  /** 订阅连接状态快照；立即回调当前值，返回取消函数。 */
  subscribeState(listener: (state: ApplicationEventConnectionState) => void): () => void {
    this.stateListeners.add(listener)
    listener(this.state)
    return () => {
      this.stateListeners.delete(listener)
    }
  }

  /** 建立连接（幂等）；disconnect() 后可再次调用以重启；terminal 后不可重启。 */
  connect(): void {
    if (this.terminal || this.socket != null || this.reconnectTimer != null) {
      return
    }
    this.attachLifecycleListeners()
    this.stopped = false
    this.generation += 1
    this.closeNotified = false
    const generation = this.generation
    let socket: WebSocket
    try {
      socket = this.socketFactory(this.url)
    } catch {
      // factory 同步抛错：绝不逃逸到调用方（Provider effect），进入 backoff。
      this.scheduleReconnect('transport-lost')
      return
    }
    this.socket = socket
    this.socketOpen = false
    this.setState('connecting', { kind: 'connecting', transportOpen: false })
    // 15 秒预算覆盖建连与等待 connected；connected 到达才算握手完成。
    this.armHandshakeTimer()
    socket.onopen = () => {
      if (!this.isCurrent(generation)) {
        return
      }
      this.socketOpen = true
      this.backoffIndex = 0
      this.setState('connecting', { kind: 'connecting', transportOpen: true })
      this.onOpen?.()
    }
    socket.onmessage = (event) => {
      if (!this.isCurrent(generation)) {
        return
      }
      const message = decodeServerMessage(String(event.data))
      if (message == null) {
        // 服务端协议违规：本连接 terminal stop，不继续消费、不重连。
        this.stopTerminal('protocol')
        return
      }
      this.handleServerMessage(message)
    }
    socket.onerror = () => {
      if (!this.isCurrent(generation)) {
        return
      }
      // 规范上 error 之后必然派发 close；这里主动关闭，让 close 成为唯一清理点。
      if (!this.safeClose(socket)) {
        // close 同步失败：手动 fence 并走 backoff 重连，避免卡在非空 socket。
        this.abandonSocket(socket)
      }
    }
    socket.onclose = (event) => {
      if (!this.isCurrent(generation)) {
        return
      }
      this.clearWatchdogs()
      this.socket = null
      this.socketOpen = false
      this.notifyClosed()
      const stopCause = CLOSE_CODE_STOP_CAUSE.get(event.code)
      if (stopCause != null) {
        // 协议错误/策略违规：terminal，不无限重试。
        this.stopTerminal(stopCause)
        return
      }
      if (event.code === CLOSE_CODE_RESTART) {
        // 服务重启：0-delay timer 立即重试，避免在 close 回调内递归 connect。
        this.scheduleReconnect('server-restart', 0)
        return
      }
      // 网络断线（1006）、1013（try again later）等：正常退避。
      this.scheduleReconnect('transport-lost')
    }
  }

  /** 永久停止：清除定时器、关闭 socket、解绑生命周期监听；之后可 connect() 重启。 */
  disconnect(): void {
    this.stopped = true
    this.generation += 1
    this.detachLifecycleListeners()
    this.clearWatchdogs()
    if (this.reconnectTimer != null) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.safeClose(this.socket)
    this.socket = null
    this.socketOpen = false
    this.notifyClosed()
    this.setState('stopped', { kind: 'stopped', cause: 'manual' })
  }

  /**
   * transport open 后即可发送（订阅重发发生在 connected 之前）；返回是否真正写入
   * wire。open 检查与 send 之间 socket 可能刚关闭，或 send 本身同步抛错：异常
   * 绝不逃逸到调用方（React effect），而是关闭 socket 走统一重连路径。
   */
  send(message: ApplicationEventClientMessage): boolean {
    const socket = this.socket
    if (!this.socketOpen || socket == null) {
      return false
    }
    try {
      socket.send(encodeClientMessage(message))
      return true
    } catch {
      // 竞态（send 时已关闭）或同步异常：close 是权威清理点，触发重连。
      if (!this.safeClose(socket)) {
        this.abandonSocket(socket)
      }
      return false
    }
  }

  private handleServerMessage(message: ApplicationEventServerMessage): void {
    if (message.type === 'connected' && this.state.status === 'connecting') {
      this.completeHandshake(Number(message.heartbeatIntervalMillis))
    } else if (this.state.status === 'ready') {
      // 完成握手后，每条合法服务端帧都刷新活性基线。
      this.lastReceivedAt = Date.now()
      this.armLivenessTimer()
    }
    this.onMessage?.(message)
  }

  private completeHandshake(heartbeatIntervalMillis: number): void {
    this.clearHandshakeTimer()
    this.heartbeatIntervalMillis = heartbeatIntervalMillis
    this.lastReceivedAt = Date.now()
    this.setState('ready', null)
    this.armLivenessTimer()
  }

  private armHandshakeTimer(): void {
    this.clearHandshakeTimer()
    this.handshakeTimer = window.setTimeout(() => {
      this.handshakeTimer = null
      if (this.stopped || this.state.status !== 'connecting') {
        return
      }
      this.fenceAndBackoff('handshake-timeout')
    }, HANDSHAKE_BUDGET_MS)
  }

  private clearHandshakeTimer(): void {
    if (this.handshakeTimer != null) {
      window.clearTimeout(this.handshakeTimer)
      this.handshakeTimer = null
    }
  }

  /** 活性定时器只保留一个：合法帧刷新基线时重排，不做多轮堆积。 */
  private armLivenessTimer(): void {
    this.clearLivenessTimer()
    const budget = LIVENESS_MISSED_HEARTBEATS * this.heartbeatIntervalMillis
    const delay = Math.max(this.lastReceivedAt + budget - Date.now(), 0)
    this.livenessTimer = window.setTimeout(() => {
      this.livenessTimer = null
      this.checkLiveness()
    }, delay)
  }

  private clearLivenessTimer(): void {
    if (this.livenessTimer != null) {
      window.clearTimeout(this.livenessTimer)
      this.livenessTimer = null
    }
  }

  private checkLiveness(): void {
    if (this.stopped || this.state.status !== 'ready' || this.socket == null) {
      return
    }
    const budget = LIVENESS_MISSED_HEARTBEATS * this.heartbeatIntervalMillis
    if (Date.now() - this.lastReceivedAt < budget) {
      // 提前触发（时钟竞争/浏览器节流恢复）：按剩余时间重排。
      this.armLivenessTimer()
      return
    }
    // 连续 3 个 heartbeat 周期没有任何合法帧：立即 fence 旧连接并重连。
    this.fenceAndBackoff('liveness-timeout')
  }

  /**
   * 握手/失联超时：立即 fence 旧 generation、清空身份、拒绝旧 pending、通知订阅者，
   * 再 best-effort close 旧 socket 并调度退避；不等待浏览器不一定及时到来的 close 回调。
   */
  private fenceAndBackoff(cause: ApplicationEventConnectionBackoffCause): void {
    this.generation += 1
    this.clearWatchdogs()
    const socket = this.socket
    this.socket = null
    this.socketOpen = false
    this.notifyClosed()
    this.safeClose(socket)
    this.scheduleReconnect(cause)
  }

  /**
   * 调度重连：delayMs 省略时按退避序列 + 当前 base 最多 20% 的 jitter 计算；
   * 显式传入 0 表示立即重试（1012），不退避、不推进序列。
   * backoff 状态与结构化原因（含重试时刻）在此一并发布。
   */
  private scheduleReconnect(cause: ApplicationEventConnectionBackoffCause, delayMs?: number): void {
    if (this.stopped || this.reconnectTimer != null) {
      return
    }
    let delay = delayMs
    if (delay == null) {
      const base = RECONNECT_BACKOFF_MS[this.backoffIndex] ?? RECONNECT_BACKOFF_MS.at(-1) ?? 10000
      this.backoffIndex = Math.min(this.backoffIndex + 1, RECONNECT_BACKOFF_MS.length - 1)
      delay = base + Math.floor(Math.random() * base * RECONNECT_JITTER_RATIO)
    }
    this.setState('backoff', { kind: 'backoff', cause, retryAt: Date.now() + delay })
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, delay)
  }

  /** online / visibility visible：健康连接保留，过期连接立即恢复；退避中的立即重试。 */
  private retryNow(): void {
    if (this.stopped) {
      return
    }
    if (this.state.status === 'ready') {
      // OPEN 连接先检查是否已过期：过期则按失联 fence 恢复，健康则保留。
      this.checkLiveness()
      return
    }
    if (this.socket != null) {
      return // connecting 中：不重复建连。
    }
    if (this.reconnectTimer != null) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.backoffIndex = 0
    this.connect()
  }

  /** socket.close() 可能同步抛错（已关闭/浏览器拒绝参数）：吞掉但返回是否成功。 */
  private safeClose(socket: WebSocket | null): boolean {
    if (socket == null) {
      return true
    }
    try {
      socket.close()
      return true
    } catch {
      return false
    }
  }

  /**
   * 运行期 close 同步失败时的兜底：generation fence 手动清掉当前 socket，
   * 进入 backoff 并调度重连。仅用于运行期 onerror/send 失败；
   * disconnect/terminal 仍直接收敛 stopped，不走这里。
   */
  private abandonSocket(socket: WebSocket): void {
    if (this.socket !== socket) {
      return // 已不是当前 socket：disconnect/terminal/新连接已接管。
    }
    this.generation += 1
    this.clearWatchdogs()
    this.socket = null
    this.socketOpen = false
    this.notifyClosed()
    this.scheduleReconnect('transport-lost')
  }

  /**
   * Terminal 停止：协议违规或不可恢复 close code。不再消费消息、不再重连，
   * 解绑生命周期监听；关闭 socket 时不带 code（浏览器禁止发送保留 close code）。
   */
  private stopTerminal(cause: 'protocol' | 'policy'): void {
    this.terminal = true
    this.stopped = true
    this.generation += 1
    this.detachLifecycleListeners()
    this.clearWatchdogs()
    if (this.reconnectTimer != null) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.safeClose(this.socket)
    this.socket = null
    this.socketOpen = false
    this.notifyClosed()
    this.setState('stopped', { kind: 'stopped', cause })
  }

  private clearWatchdogs(): void {
    this.clearHandshakeTimer()
    this.clearLivenessTimer()
  }

  private notifyClosed(): void {
    if (this.closeNotified) {
      return
    }
    this.closeNotified = true
    this.onClose?.()
  }

  private isCurrent(generation: number): boolean {
    return !this.stopped && generation === this.generation
  }

  private setState(
    status: ApplicationEventConnectionStatus,
    reason: ApplicationEventConnectionReason | null,
  ): void {
    this.state = { status, reason }
    for (const listener of [...this.stateListeners]) {
      try {
        listener(this.state)
      } catch (error) {
        console.error('application event state listener failed', error)
      }
    }
  }

  private attachLifecycleListeners(): void {
    if (this.listenersAttached) {
      return
    }
    this.listenersAttached = true
    window.addEventListener('online', this.handleOnline)
    document.addEventListener('visibilitychange', this.handleVisibilityChange)
  }

  private detachLifecycleListeners(): void {
    if (!this.listenersAttached) {
      return
    }
    this.listenersAttached = false
    window.removeEventListener('online', this.handleOnline)
    document.removeEventListener('visibilitychange', this.handleVisibilityChange)
  }

  private readonly handleOnline = (): void => {
    this.retryNow()
  }

  private readonly handleVisibilityChange = (): void => {
    if (document.visibilityState === 'visible') {
      this.retryNow()
    }
  }
}

/** 由当前 origin 构造 ws(s)://<host>/ws/events；清除 search/hash。 */
export function createApplicationEventUrl(): string {
  const url = new URL('/ws/events', window.location.href)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.search = ''
  url.hash = ''
  return url.toString()
}
