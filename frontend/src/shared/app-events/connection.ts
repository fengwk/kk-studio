import { apiBaseUrl } from '@/shared/api/client'
import {
  decodeServerMessage,
  encodeClientMessage,
  type ApplicationEventClientMessage,
  type ApplicationEventServerMessage,
} from '@/shared/app-events/protocol'

/**
 * 连接状态机：idle -> connecting -> open -> backoff -> connecting -> closed。
 * - 初始 idle；connect() 后 connecting；socket 失败（含 factory 同步抛错）回 backoff；
 * - 退避到期（或 online/visible 立即重试）后再次 connecting；
 * - manual disconnect 与 terminal 停止都落到 closed。
 */
export type ApplicationEventConnectionStatus = 'idle' | 'connecting' | 'open' | 'backoff' | 'closed'

export type ApplicationEventSocketFactory = (url: string) => WebSocket

export interface ApplicationEventConnectionOptions {
  url: string
  /** 测试注入；生产默认使用全局 WebSocket。 */
  socketFactory?: ApplicationEventSocketFactory
  /** 每次 WebSocket open（首次连接与每次重连）时触发；Manager 借此重发订阅。 */
  onOpen?: () => void
  /** 严格解码后的 server 消息；畸形帧不会到达这里。 */
  onMessage?: (message: ApplicationEventServerMessage) => void
}

/** 退避序列：250ms..10s，之后保持 10s cap。 */
const RECONNECT_BACKOFF_MS = [250, 500, 1000, 2000, 5000, 10000]
/** jitter 上限：当前 base 的 20%。 */
const RECONNECT_JITTER_RATIO = 0.2
/** 服务端主动重启：立即重试。 */
const CLOSE_CODE_RESTART = 1012
/** 协议错误/策略违规：terminal，不无限重试。 */
const CLOSE_CODES_TERMINAL = new Set([1002, 1008])

/**
 * 应用生命周期内的单例 WebSocket 连接：只负责传输、严格 codec、
 * 状态与重连。应用 mount 时 connect()，路由切换不重建；页面 online /
 * visibility visible 时立即重试。
 *
 * 每次 connect() 递增 generation：旧 socket 的回调捕获旧 generation，
 * 一旦落后即失效（no-op），因此过期 socket 的事件永远不会干扰新连接。
 * 重连退避由唯一 reconnect timer 驱动；socket 的 close 是权威清理点，
 * error 只负责关闭 socket（浏览器随后必然派发 close）。
 */
export class ApplicationEventConnection {
  private readonly url: string
  private readonly socketFactory: ApplicationEventSocketFactory
  private readonly onOpen?: () => void
  private readonly onMessage?: (message: ApplicationEventServerMessage) => void

  private socket: WebSocket | null = null
  private generation = 0
  private status: ApplicationEventConnectionStatus = 'idle'
  private reconnectTimer: number | null = null
  private backoffIndex = 0
  private stopped = true
  private terminal = false
  private listenersAttached = false

  constructor(options: ApplicationEventConnectionOptions) {
    this.url = options.url
    this.socketFactory = options.socketFactory ?? ((url) => new WebSocket(url))
    this.onOpen = options.onOpen
    this.onMessage = options.onMessage
  }

  getStatus(): ApplicationEventConnectionStatus {
    return this.status
  }

  /** 建立连接（幂等）；disconnect() 后可再次调用以重启；terminal 后不可重启。 */
  connect(): void {
    if (this.terminal || this.socket != null || this.reconnectTimer != null) {
      return
    }
    this.attachLifecycleListeners()
    this.stopped = false
    this.generation += 1
    const generation = this.generation
    let socket: WebSocket
    try {
      socket = this.socketFactory(this.url)
    } catch {
      // factory 同步抛错：绝不逃逸到调用方（Provider effect），进入 backoff。
      this.setStatus('backoff')
      this.scheduleReconnect()
      return
    }
    this.socket = socket
    this.setStatus('connecting')
    socket.onopen = () => {
      if (!this.isCurrent(generation)) {
        return
      }
      this.backoffIndex = 0
      this.setStatus('open')
      this.onOpen?.()
    }
    socket.onmessage = (event) => {
      if (!this.isCurrent(generation)) {
        return
      }
      const message = decodeServerMessage(String(event.data))
      if (message == null) {
        // 服务端协议违规：本连接 terminal stop，不继续消费、不重连。
        this.stopTerminal()
        return
      }
      this.onMessage?.(message)
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
      this.socket = null
      if (CLOSE_CODES_TERMINAL.has(event.code)) {
        // 协议错误/策略违规：terminal，不无限重试。
        this.stopTerminal()
        return
      }
      this.setStatus('backoff')
      if (event.code === CLOSE_CODE_RESTART) {
        // 服务重启：0-delay timer 立即重试，避免在 close 回调内递归 connect。
        this.scheduleReconnect(0)
        return
      }
      // 网络断线（1006）、1013（try again later）等：正常退避。
      this.scheduleReconnect()
    }
  }

  /** 永久停止：清除定时器、关闭 socket、解绑生命周期监听；之后可 connect() 重启。 */
  disconnect(): void {
    this.stopped = true
    this.generation += 1
    this.detachLifecycleListeners()
    if (this.reconnectTimer != null) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.safeClose(this.socket)
    this.socket = null
    this.setStatus('closed')
  }

  /**
   * 仅 open 时发送；返回是否真正写入 wire（未连接时调用方按 pending 处理）。
   * open 检查与 send 之间 socket 可能刚关闭，或 send 本身同步抛错：异常
   * 绝不逃逸到调用方（React effect），而是关闭 socket 走统一重连路径。
   */
  send(message: ApplicationEventClientMessage): boolean {
    const socket = this.socket
    if (this.status !== 'open' || socket == null) {
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

  /**
   * 调度重连：delayMs 省略时按退避序列 + 当前 base 最多 20% 的 jitter 计算；
   * 显式传入 0 表示立即重试（1012），不退避、不推进序列。
   */
  private scheduleReconnect(delayMs?: number): void {
    if (this.stopped || this.reconnectTimer != null) {
      return
    }
    let delay = delayMs
    if (delay == null) {
      const backoff = RECONNECT_BACKOFF_MS[this.backoffIndex] ?? RECONNECT_BACKOFF_MS.at(-1) ?? 10000
      this.backoffIndex = Math.min(this.backoffIndex + 1, RECONNECT_BACKOFF_MS.length - 1)
      delay = backoff + Math.floor(Math.random() * backoff * RECONNECT_JITTER_RATIO)
    }
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, delay)
  }

  /** online / visibility visible：立即重试（清掉 pending 退避定时器）。 */
  private retryNow(): void {
    if (this.stopped || this.status === 'open' || this.socket != null) {
      return
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
   * disconnect/terminal 仍直接收敛 closed，不走这里。
   */
  private abandonSocket(socket: WebSocket): void {
    if (this.socket !== socket) {
      return // 已不是当前 socket：disconnect/terminal/新连接已接管。
    }
    this.generation += 1
    this.socket = null
    this.setStatus('backoff')
    this.scheduleReconnect()
  }

  /**
   * Terminal 停止：协议违规或不可恢复 close code。不再消费消息、不再重连，
   * 解绑生命周期监听；关闭 socket 时不带 code（浏览器禁止发送保留 close code）。
   */
  private stopTerminal(): void {
    this.terminal = true
    this.stopped = true
    this.generation += 1
    this.detachLifecycleListeners()
    if (this.reconnectTimer != null) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.safeClose(this.socket)
    this.socket = null
    this.setStatus('closed')
  }

  private isCurrent(generation: number): boolean {
    return !this.stopped && generation === this.generation
  }

  private setStatus(status: ApplicationEventConnectionStatus): void {
    this.status = status
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

/** 由当前 api base（/api）构造 ws(s)://<host>/api/events/v1；清除 search/hash。 */
export function createApplicationEventUrl(): string {
  const base = new URL(apiBaseUrl, window.location.href)
  base.pathname = `${base.pathname.replace(/\/+$/, '')}/events/v1`
  base.protocol = base.protocol === 'https:' ? 'wss:' : 'ws:'
  base.search = ''
  base.hash = ''
  return base.toString()
}
