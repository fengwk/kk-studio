import type {
  ApplicationEventClientMessage,
  ApplicationEventServerMessage,
} from '@/shared/app-events/protocol'

/**
 * WebSocket 测试替身：记录 url/发送帧，测试可驱动 open/fail/close 与
 * 派发 server 帧。语义贴近浏览器：error 之后 close 是权威结束点；
 * 显式 close() 正常结束（code 1000），closeWith(code) 模拟服务端主动关闭。
 */
export class FakeWebSocket {
  readonly url: string
  readonly sent: string[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: unknown }) => void) | null = null
  onclose: ((event: { code: number }) => void) | null = null
  onerror: (() => void) | null = null
  closed = false
  /** 为 true 时 send 同步抛错（模拟 open 与 send 之间的竞态/传输故障）。 */
  sendThrows = false
  /** 为 true 时 close 同步抛错（模拟浏览器对保留 close code/已关闭 socket 的拒绝）。 */
  closeThrows = false
  /** send() 时同步派发的 server 帧（测试「先登记 listener 再发 subscribe」等时序契约）。 */
  onSendResponse: ApplicationEventServerMessage | null = null

  constructor(url: string) {
    this.url = url
  }

  send(data: string): void {
    if (this.sendThrows) {
      throw new Error('WebSocket is not open')
    }
    this.sent.push(data)
    if (this.onSendResponse != null) {
      this.onmessage?.({ data: JSON.stringify({ version: 1, ...this.onSendResponse }) })
    }
  }

  open(): void {
    this.onopen?.()
  }

  /** 触发 error 并关闭（与浏览器一致：error 后必然 close；默认 1006 网络断线）。 */
  fail(): void {
    this.onerror?.()
    this.closeWith()
  }

  /** 以指定 close code 关闭（服务端主动 close，无 error 事件）。 */
  closeWith(code = 1006): void {
    if (this.closed) {
      return
    }
    this.closed = true
    this.onclose?.({ code })
  }

  /** 浏览器显式 close()（无参数）：正常关闭（code 1000）。 */
  close(): void {
    if (this.closeThrows) {
      throw new Error('WebSocket close failed')
    }
    this.closeWith(1000)
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
