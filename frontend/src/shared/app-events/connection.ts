import { apiBaseUrl } from '@/shared/api/client'
import { FramedEventLink } from '@/shared/app-events/framed-link.mjs'
import {
  decodeServerMessage,
  encodeClientMessage,
  type ApplicationEventClientMessage,
  type ApplicationEventServerMessage,
} from '@/shared/app-events/protocol'
import { createUuid } from '@/shared/lib/uuid'
import { defaultNotificationLimits } from '@/shared/notification/notification.mjs'

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
  /** 状态实际变更时触发一次（相同状态不重复通知）。 */
  onStatusChange?: (status: ApplicationEventConnectionStatus) => void
}

/** 退避序列：250ms..10s，之后保持 10s cap。 */
const RECONNECT_BACKOFF_MS = [250, 500, 1000, 2000, 5000, 10000]
/** jitter 上限：当前 base 的 20%。 */
const RECONNECT_JITTER_RATIO = 0.2
/** 服务端主动重启：立即重试。 */
const CLOSE_CODE_RESTART = 1012
/** 协议错误/策略违规：terminal，不无限重试。 */
const CLOSE_CODES_TERMINAL = new Set([1002, 1008])
/** 物理发送缓冲上界：达到即推迟下一批 outbox drain，避免同步刷完巨型消息。 */
const MAX_BUFFERED_AMOUNT = 512 * 1024
/** 缓冲/公平让出后的下一次 drain 调度延迟（毫秒）。 */
const DRAIN_RETRY_MS = 16

/**
 * 应用生命周期内的单例 WebSocket 连接：只负责传输、共享 carrier 严格编解码、
 * 状态与重连。应用 mount 时 connect()，路由切换不重建；页面 online /
 * visibility visible 时立即重试。
 *
 * 每个物理连接随机 endpoint publisher，并拥有独立的 FramedEventLink
 * （peer 冻结、target/topic 校验、重组/outbox）。每次 connect() 递增 generation：
 * 旧 socket 的回调与旧 link 捕获旧 generation，一旦落后即失效（no-op）。
 * 重连退避由唯一 reconnect timer 驱动；socket 的 close 是权威清理点。
 */
export class ApplicationEventConnection {
  private readonly url: string
  private readonly socketFactory: ApplicationEventSocketFactory
  private readonly onOpen?: () => void
  private readonly onMessage?: (message: ApplicationEventServerMessage) => void
  private readonly onStatusChange?: (status: ApplicationEventConnectionStatus) => void
  private readonly limits = defaultNotificationLimits()

  private socket: WebSocket | null = null
  private link: FramedEventLink | null = null
  private publisher = ''
  private generation = 0
  private status: ApplicationEventConnectionStatus = 'idle'
  private reconnectTimer: number | null = null
  private expireTimer: number | null = null
  private drainTimer: number | null = null
  private backoffIndex = 0
  private stopped = true
  private terminal = false
  private listenersAttached = false

  constructor(options: ApplicationEventConnectionOptions) {
    this.url = options.url
    this.socketFactory = options.socketFactory ?? ((url) => new WebSocket(url))
    this.onOpen = options.onOpen
    this.onMessage = options.onMessage
    this.onStatusChange = options.onStatusChange
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
    this.publisher = createUuid()
    this.socket = socket
    this.link = this.createLink(generation)
    this.setStatus('connecting')
    socket.onopen = () => {
      if (!this.isCurrent(generation)) {
        return
      }
      this.backoffIndex = 0
      this.startExpireTimer(generation)
      this.setStatus('open')
      this.onOpen?.()
    }
    socket.onmessage = (event) => {
      if (!this.isCurrent(generation)) {
        return
      }
      const link = this.link
      if (link == null) {
        return
      }
      if (typeof event.data !== 'string') {
        // binary 帧：协议违规，terminal stop。
        this.stopTerminal()
        return
      }
      link.accept(event.data)
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
      this.closeLink()
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

  /** 永久停止：清除定时器、关闭 socket 与 link、解绑生命周期监听；之后可 connect() 重启。 */
  disconnect(): void {
    this.stopped = true
    this.generation += 1
    this.detachLifecycleListeners()
    if (this.reconnectTimer != null) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.closeLink()
    this.safeClose(this.socket)
    this.socket = null
    this.setStatus('closed')
  }

  /**
   * 仅 open 时发送；返回是否被本地 outbox 接受并成功同步发送首个批次（未连接时调用方
   * 按 pending 处理）。逻辑 JSON 经共享 carrier 分片发送，绝不 raw JSON。编码/入队
   * 异常绝不逃逸到调用方（React effect），而是返回 false 或走统一重连路径。
   */
  send(message: ApplicationEventClientMessage): boolean {
    const socket = this.socket
    const link = this.link
    if (this.status !== 'open' || socket == null || link == null) {
      return false
    }
    let body: string
    try {
      body = encodeClientMessage(message)
    } catch {
      // 非法 shell.command 等编码失败：不逃逸、不发送。
      return false
    }
    if (!link.offer(createUuid(), body)) {
      return false
    }
    return this.drain()
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

  private createLink(generation: number): FramedEventLink {
    return new FramedEventLink({
      self: this.publisher,
      limits: this.limits,
      deliver: (body) => this.handleBody(generation, body),
      onFailure: (recover) => this.handleLinkFailure(generation, recover),
    })
  }

  /** 共享 link 交付完整 UTF-8 逻辑体：严格解码后分发给 onMessage。 */
  private handleBody(generation: number, body: string): void {
    if (!this.isCurrent(generation)) {
      return
    }
    const message = decodeServerMessage(body)
    if (message == null) {
      // 服务端逻辑协议违规：本连接 terminal stop，不继续消费、不重连。
      this.stopTerminal()
      return
    }
    this.onMessage?.(message)
  }

  /**
   * 共享 link 的失败收敛：reassembly 缺片/超时/超限走统一恢复 close（触发退避重连）；
   * carrier/peer/topic/target/UTF-8 违规是 terminal，不再重连。
   */
  private handleLinkFailure(generation: number, recover: boolean): void {
    if (!this.isCurrent(generation)) {
      return
    }
    if (recover) {
      const socket = this.socket
      if (socket == null || !this.safeClose(socket)) {
        this.abandonSocket(socket)
      }
      return
    }
    this.stopTerminal()
  }

  /**
   * 有界公平 drain：每轮最多发送一个 outbox batch（<= sendBatchFrames 片），
   * bufferedAmount 达到上界时推迟到下一次调度；单个巨型消息绝不同步刷完。
   * 返回 false 仅表示本批次同步发送失败（已走统一恢复路径），否则 true。
   */
  private drain(): boolean {
    const generation = this.generation
    const link = this.link
    const socket = this.socket
    if (link == null || socket == null || !this.isCurrent(generation) || this.status !== 'open') {
      return false
    }
    if (socket.bufferedAmount >= MAX_BUFFERED_AMOUNT) {
      this.scheduleDrain()
      return true
    }
    const batch = link.pollBatch()
    if (batch == null) {
      return true
    }
    try {
      for (const frame of batch.frames()) {
        socket.send(frame)
      }
      link.complete(batch, true)
    } catch {
      link.complete(batch, false)
      this.handleLinkFailure(generation, true)
      return false
    }
    if (link.hasPending()) {
      this.scheduleDrain()
    }
    return true
  }

  private scheduleDrain(): void {
    if (this.drainTimer != null || this.stopped) {
      return
    }
    const generation = this.generation
    this.drainTimer = window.setTimeout(() => {
      this.drainTimer = null
      if (!this.isCurrent(generation)) {
        return
      }
      this.drain()
    }, DRAIN_RETRY_MS)
  }

  /** 每个 open generation 的定期重组过期清扫；断开时随 link 一起清除。 */
  private startExpireTimer(generation: number): void {
    if (this.expireTimer != null) {
      return
    }
    const period = this.limits.reassemblyTimeoutMs
    this.expireTimer = window.setInterval(() => {
      if (!this.isCurrent(generation)) {
        return
      }
      this.link?.expire()
    }, period)
  }

  /** 释放当前 link 及其全部 timer/bytes；幂等。 */
  private closeLink(): void {
    if (this.expireTimer != null) {
      window.clearInterval(this.expireTimer)
      this.expireTimer = null
    }
    if (this.drainTimer != null) {
      window.clearTimeout(this.drainTimer)
      this.drainTimer = null
    }
    this.link?.close()
    this.link = null
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
  private abandonSocket(socket: WebSocket | null): void {
    if (socket == null || this.socket !== socket) {
      return // 已不是当前 socket：disconnect/terminal/新连接已接管。
    }
    this.generation += 1
    this.socket = null
    this.closeLink()
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
    this.closeLink()
    this.safeClose(this.socket)
    this.socket = null
    this.setStatus('closed')
  }

  private isCurrent(generation: number): boolean {
    return !this.stopped && generation === this.generation
  }

  private setStatus(status: ApplicationEventConnectionStatus): void {
    if (this.status === status) {
      return
    }
    this.status = status
    this.onStatusChange?.(status)
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
