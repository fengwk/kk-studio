import WebSocket from 'ws'

import { getOutboundNetwork } from '../network.js'
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
  const pending = new Set<AbortController>()
  let closed = false
  return {
    connect(listener: ControlTransportListener): Promise<ControlConnection> {
      if (closed) {
        return Promise.reject(new Error('control transport is closed'))
      }
      // handshake 期间由 transport 持有取消信号；close 立即中止仍在 lookup/握手中的连接。
      const cancel = new AbortController()
      pending.add(cancel)
      return new WebSocketHandshake(gatewayUri, listener, maxInboundTextBytes, connectTimeoutMillis, cancel)
        .start()
        .finally(() => {
          pending.delete(cancel)
        })
    },
    close(): void {
      if (closed) {
        return
      }
      closed = true
      for (const controller of pending) {
        controller.abort()
      }
      pending.clear()
    },
  }
}

/** 取消/超时统一的 AbortError；调用方据此判定操作被中止。 */
function abortError(): Error {
  const error = new Error('Operation cancelled')
  error.name = 'AbortError'
  return error
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
    private readonly cancel: AbortController,
  ) {}

  async start(): Promise<ControlConnection> {
    // lookup 与握手共享同一绝对 deadline；transport.close 通过 cancel 立即中止。
    const startedAt = Date.now()
    const deadline = AbortSignal.any([this.cancel.signal, AbortSignal.timeout(this.connectTimeoutMillis)])
    const plan = await getOutboundNetwork().createWebSocketAgent(this.gatewayUri, deadline)
    if (deadline.aborted) {
      plan.destroy()
      throw abortError()
    }
    // 握手沿用剩余时间，绝不重新计时为满额，避免总超时翻倍。
    const handshakeTimeout = this.remainingMillis(startedAt)
    return new Promise<ControlConnection>((resolve, reject) => {
      let socket: WebSocket
      try {
        socket = new WebSocket(this.gatewayUri, {
          perMessageDeflate: true,
          handshakeTimeout,
          maxPayload: this.maxInboundTextBytes,
          agent: plan.agent,
        })
      } catch (error) {
        plan.destroy()
        reject(error)
        return
      }
      if (deadline.aborted) {
        socket.terminate()
        plan.destroy()
        reject(abortError())
        return
      }
      // 握手期间 transport.close/deadline 到点必须立即终止 socket，而不是只依赖 handshakeTimeout。
      const onAbort = (): void => {
        deadline.removeEventListener('abort', onAbort)
        plan.destroy()
        socket.terminate()
        this.notifyDisconnected(abortError())
        if (!this.settled) {
          this.settled = true
          reject(abortError())
        }
      }
      deadline.addEventListener('abort', onAbort, { once: true })
      const releaseAgent = (): void => {
        deadline.removeEventListener('abort', onAbort)
        plan.destroy()
      }
      socket.once('close', releaseAgent)
      socket.once('error', releaseAgent)
      this.connection.attach(socket)
      socket.on('open', () => {
        // 握手成功即摘除 deadline abort 监听：已建立的连接绝不能被连接超时终止。
        deadline.removeEventListener('abort', onAbort)
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

  /** 与 lookup 共享同一绝对 deadline 的剩余毫秒数；至少 1ms，绝不重新计时。 */
  private remainingMillis(startedAt: number): number {
    return Math.max(1, this.connectTimeoutMillis - (Date.now() - startedAt))
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
