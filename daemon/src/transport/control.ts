import WebSocket from 'ws'

import { MAX_PAYLOAD_UTF8_BYTES } from '../protocol.js'

/**
 * Daemon Runtime 的可替换 WebSocket transport 边界（control 通道）。
 *
 * <p>生产实现基于 ws 8.22，并强制协商 {@code permessage-deflate}：服务端未接受该扩展时立即以 RFC 6455 close
 * code 1010 关闭且不交付连接，绝不退化为未压缩会话。只接受 UTF-8 文本帧；单条入站文本超过上限或收到 binary 帧时以
 * close code 1008 确定性拒绝。
 *
 * <p>测试可注入内存 transport；运行时只依赖这里的最小接口。
 */

export interface ControlConnection {
  /** 异步发送一条完整文本帧。 */
  sendText(message: string): Promise<void>
  /** 主动关闭连接。 */
  close(): void
  /** 连接是否仍可发送。 */
  isOpen(): boolean
}

export interface ControlTransportListener {
  /** 接收一条完整的文本消息。 */
  onMessage(message: string): void
  /** 连接被远端关闭、被本地关闭或因 I/O 失败而失效。 */
  onDisconnected(cause: unknown): void
}

export interface ControlTransport {
  connect(listener: ControlTransportListener): Promise<ControlConnection>
  close(): void
}

export interface WebSocketTransportOptions {
  /** 单条入站文本消息的 UTF-8 字节上限；默认 16 MiB。 */
  maxInboundTextBytes?: number
  /** 连接超时（毫秒）。 */
  connectTimeoutMillis?: number
}

/** RFC 6455 policy violation close code。 */
export const POLICY_VIOLATION = 1008
/** RFC 6455 mandatory extension close code。 */
export const MANDATORY_EXTENSION = 1010
/** RFC 6455 normal closure。 */
export const NORMAL_CLOSURE = 1000

const PERMESSAGE_DEFLATE = 'permessage-deflate'

/** 生产 transport：基于 ws，强制 permessage-deflate 与文本帧约束。 */
export function createWebSocketTransport(
  gatewayUri: string,
  options: WebSocketTransportOptions = {},
): ControlTransport {
  const maxInboundTextBytes = options.maxInboundTextBytes ?? MAX_PAYLOAD_UTF8_BYTES
  const connectTimeoutMillis = options.connectTimeoutMillis ?? 2000
  if (!Number.isSafeInteger(maxInboundTextBytes) || maxInboundTextBytes <= 0) {
    throw new Error('maxInboundTextBytes must be positive')
  }
  return {
    connect(listener: ControlTransportListener): Promise<ControlConnection> {
      return new WebSocketHandshake(gatewayUri, listener, maxInboundTextBytes, connectTimeoutMillis).start()
    },
    close(): void {
      // 单连接由调用方持有；transport 本身无共享资源。
    },
  }
}

/** 单次握手的仲裁点：最多交付一次连接、最多通知一次断开。 */
class WebSocketHandshake {
  private readonly connection = new WebSocketConnection()
  private settled = false
  private disconnected = false
  private rejected = false

  constructor(
    private readonly gatewayUri: string,
    private readonly delegate: ControlTransportListener,
    private readonly maxInboundTextBytes: number,
    private readonly connectTimeoutMillis: number,
  ) {}

  start(): Promise<ControlConnection> {
    return new Promise<ControlConnection>((resolve, reject) => {
      const socket = new WebSocket(this.gatewayUri, {
        perMessageDeflate: true,
        handshakeTimeout: this.connectTimeoutMillis,
        maxPayload: this.maxInboundTextBytes,
      })
      this.connection.attach(socket)
      socket.on('open', () => {
        if (!socket.extensions.includes(PERMESSAGE_DEFLATE)) {
          this.reject(socket, MANDATORY_EXTENSION, 'server did not negotiate permessage-deflate')
          reject(new Error('server did not negotiate permessage-deflate'))
          return
        }
        this.connection.markOpen()
        if (!this.settled) {
          this.settled = true
          resolve(this.connection)
        }
      })
      socket.on('message', (data: WebSocket.RawData, isBinary: boolean) => {
        if (this.rejected) {
          return
        }
        if (isBinary) {
          this.reject(socket, POLICY_VIOLATION, 'binary frames are not supported')
          return
        }
        const text = Buffer.isBuffer(data) ? data.toString('utf8') : String(data)
        if (Buffer.byteLength(text, 'utf8') > this.maxInboundTextBytes) {
          this.reject(socket, POLICY_VIOLATION, 'inbound text message exceeds the maximum size')
          return
        }
        this.delegate.onMessage(text)
      })
      socket.on('close', () => {
        this.notifyDisconnected(null)
      })
      socket.on('error', (error: Error) => {
        this.notifyDisconnected(error)
        if (!this.settled) {
          this.settled = true
          reject(error)
        }
      })
    })
  }

  /** 确定性拒绝：恰好一次 close 帧、恰好一次断开通知，且不再消费后续帧。 */
  private reject(socket: WebSocket, closeCode: number, reason: string): void {
    if (this.rejected) {
      return
    }
    this.rejected = true
    try {
      socket.close(closeCode, reason)
    } catch {
      // 关闭失败仍要走断开路径。
    }
    this.notifyDisconnected(new Error(reason))
  }

  private notifyDisconnected(cause: unknown): void {
    if (this.disconnected) {
      return
    }
    this.disconnected = true
    this.connection.markClosed()
    this.delegate.onDisconnected(cause)
  }
}

class WebSocketConnection implements ControlConnection {
  private socket: WebSocket | null = null
  private open = false
  private closeRequested = false

  attach(socket: WebSocket): void {
    this.socket = socket
  }

  markOpen(): void {
    this.open = true
  }

  markClosed(): void {
    this.open = false
  }

  sendText(message: string): Promise<void> {
    const socket = this.socket
    if (socket === null || !this.open || socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error('websocket is closed'))
    }
    return new Promise<void>((resolve, reject) => {
      socket.send(message, (error?: Error) => {
        if (error) {
          reject(error)
        } else {
          resolve()
        }
      })
    })
  }

  close(): void {
    this.open = false
    if (!this.closeRequested) {
      this.closeRequested = true
      const socket = this.socket
      if (socket !== null) {
        socket.close(NORMAL_CLOSURE, 'daemon stopped')
      }
    }
  }

  isOpen(): boolean {
    return this.open
  }
}
