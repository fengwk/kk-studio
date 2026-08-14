import type {
  ApplicationEventClientMessage,
  ApplicationEventServerMessage,
} from '@/shared/app-events/protocol'

/**
 * WebSocket 测试替身：记录 url/发送帧，测试可驱动 open/fail/close 与
 * 派发 server 帧。语义贴近浏览器：error 之后 close 是权威结束点。
 */
export class FakeWebSocket {
  readonly url: string
  readonly sent: string[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: unknown }) => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  closed = false
  /** 为 true 时 send 同步抛错（模拟 open 与 send 之间的竞态/传输故障）。 */
  sendThrows = false

  constructor(url: string) {
    this.url = url
  }

  send(data: string): void {
    if (this.sendThrows) {
      throw new Error('WebSocket is not open')
    }
    this.sent.push(data)
  }

  open(): void {
    this.onopen?.()
  }

  /** 触发 error 并关闭（与浏览器一致：error 后必然 close）。 */
  fail(): void {
    this.onerror?.()
    this.close()
  }

  close(): void {
    if (this.closed) {
      return
    }
    this.closed = true
    this.onclose?.()
  }

  /**
   * 以真实 wire 帧派发 server 消息：自动补齐协议 version=1，
   * 连接端 codec 会做完整严格校验。
   */
  emitServer(message: ApplicationEventServerMessage): void {
    this.onmessage?.({ data: JSON.stringify({ version: 1, ...message }) })
  }

  sentMessages(): ApplicationEventClientMessage[] {
    return this.sent.map((raw) => JSON.parse(raw) as ApplicationEventClientMessage)
  }
}

/** 收集连接创建的所有 socket 并向连接注入 factory。 */
export class FakeWebSocketHarness {
  readonly sockets: FakeWebSocket[] = []
  readonly factory = (url: string): WebSocket => {
    const socket = new FakeWebSocket(url)
    this.sockets.push(socket)
    return socket as unknown as WebSocket
  }

  get latest(): FakeWebSocket | null {
    return this.sockets.at(-1) ?? null
  }

  /** 打开最新 socket（通常在挂载后立刻调用）。 */
  openLatest(): FakeWebSocket {
    const socket = this.latest
    if (socket == null) {
      throw new Error('no WebSocket created yet')
    }
    socket.open()
    return socket
  }
}
