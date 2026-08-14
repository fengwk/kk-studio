import { apiBaseUrl } from '@/shared/api/client'
import {
  decodeServerMessage,
  encodeClientMessage,
  type ApplicationEventClientMessage,
  type ApplicationEventServerMessage,
} from '@/shared/app-events/protocol'

export type ApplicationEventConnectionStatus = 'connecting' | 'open' | 'closed'

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

/** 退避序列：250ms, 500ms, 1s, 2s, 5s，之后保持 5s cap。 */
const RECONNECT_BACKOFF_MS = [250, 500, 1000, 2000, 5000]
/** 叠加的小 jitter，避免多客户端同时重连。 */
const RECONNECT_JITTER_MAX_MS = 250

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
  private status: ApplicationEventConnectionStatus = 'closed'
  private reconnectTimer: number | null = null
  private backoffIndex = 0
  private stopped = true
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

  /** 建立连接（幂等）；disconnect() 后可再次调用以重启。 */
  connect(): void {
    if (this.socket != null || this.reconnectTimer != null) {
      return
    }
    this.attachLifecycleListeners()
    this.stopped = false
    this.generation += 1
    const generation = this.generation
    const socket = this.socketFactory(this.url)
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
      if (message != null) {
        this.onMessage?.(message)
      }
    }
    socket.onerror = () => {
      if (!this.isCurrent(generation)) {
        return
      }
      // 规范上 error 之后必然派发 close；这里主动关闭，让 close 成为唯一清理点。
      socket.close()
    }
    socket.onclose = () => {
      if (!this.isCurrent(generation) || this.stopped) {
        return
      }
      this.socket = null
      this.setStatus('connecting')
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
    this.socket?.close()
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
      socket.close()
      return false
    }
  }

  private scheduleReconnect(): void {
    if (this.stopped || this.reconnectTimer != null) {
      return
    }
    const backoff = RECONNECT_BACKOFF_MS[this.backoffIndex] ?? RECONNECT_BACKOFF_MS.at(-1) ?? 5000
    this.backoffIndex = Math.min(this.backoffIndex + 1, RECONNECT_BACKOFF_MS.length - 1)
    const jitter = Math.floor(Math.random() * (RECONNECT_JITTER_MAX_MS + 1))
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, backoff + jitter)
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

/** 由当前 api base（/api）构造 ws(s)://<host>/api/events/v1。 */
export function createApplicationEventUrl(): string {
  const base = new URL(apiBaseUrl, window.location.href)
  base.pathname = `${base.pathname.replace(/\/+$/, '')}/events/v1`
  base.protocol = base.protocol === 'https:' ? 'wss:' : 'ws:'
  return base.toString()
}
