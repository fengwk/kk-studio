import { Buffer } from 'node:buffer'
import http, { type IncomingHttpHeaders } from 'node:http'
import http2, {
  constants as h2constants,
  type ServerHttp2Session,
  type ServerHttp2Stream,
} from 'node:http2'
import { createWebSocketStream, WebSocket } from 'ws'
import { fromBinary } from '@bufbuild/protobuf'

import type { DaemonConfig, IdeService } from '../contracts.js'
import { readRegistrationToken } from '../config.js'
import { currentPlatformKey } from '../release.js'
import {
  decodeFrame,
  encodeFrame,
  PROTOCOL_VERSION,
  validateUpgrade,
} from '../protocol/control/codec.js'
import {
  UpgradeRequiredSchema,
  type Envelope,
  type UpgradeRequired,
} from '../generated/control_pb.js'
import { handleIdeConnectRequest, handleIdeHttpRequest } from './ide-tunnel.js'

const {
  HTTP2_HEADER_STATUS,
  HTTP2_HEADER_METHOD,
  HTTP2_HEADER_PATH,
  NGHTTP2_REFUSED_STREAM,
} = h2constants

const HTTP2_HEADER_PROTOCOL = ':protocol'

export interface ControlTransportListener {
  onEnvelope(envelope: Envelope): void
  onDisconnected(): void
  onGoaway(): void
  onUpgradeFailed(statusCode: number, upgradeRequired?: UpgradeRequired): void
}

export interface ControlConnection {
  send(envelope: Envelope): boolean
  isOpen(): boolean
  close(): void
}

export interface ControlTransport {
  connect(listener: ControlTransportListener): Promise<ControlConnection>
  close(): Promise<void>
}

export interface DaemonTransportOptions {
  gatewayUri: string
  config: DaemonConfig
  ide: IdeService
  daemonInstanceId: string
  platformKey?: string
  inactivityTimeoutMillis?: number
  controlStreamTimeoutMillis?: number
}

const DEFAULT_INACTIVITY_TIMEOUT_MILLIS = 45_000
const DEFAULT_CONTROL_STREAM_TIMEOUT_MILLIS = 10_000
const MAX_WS_PAYLOAD_BYTES = 1024 * 1024

export class DaemonWebSocketTransport implements ControlTransport {
  private readonly gatewayUri: string
  private readonly config: DaemonConfig
  private readonly ide: IdeService
  private readonly daemonInstanceId: string
  private readonly platformKey: string
  private readonly inactivityTimeoutMillis: number
  private readonly controlStreamTimeoutMillis: number
  private activeWs: WebSocket | null = null
  private activeSession: ServerHttp2Session | null = null
  private closed = false

  constructor(options: DaemonTransportOptions) {
    this.gatewayUri = options.gatewayUri
    this.config = options.config
    this.ide = options.ide
    this.daemonInstanceId = options.daemonInstanceId
    this.platformKey = options.platformKey ?? currentPlatformKey()
    this.inactivityTimeoutMillis = options.inactivityTimeoutMillis ?? DEFAULT_INACTIVITY_TIMEOUT_MILLIS
    this.controlStreamTimeoutMillis = options.controlStreamTimeoutMillis ?? DEFAULT_CONTROL_STREAM_TIMEOUT_MILLIS
  }

  async connect(listener: ControlTransportListener): Promise<ControlConnection> {
    if (this.closed) {
      throw new Error('transport is closed')
    }

    let token: string
    try {
      token = readRegistrationToken(this.config)
    } catch (err) {
      throw new Error(`cannot read registration token: ${(err as Error).message}`)
    }

    const headers: Record<string, string> = {
      Authorization: `Bearer ${token}`,
      'KK-Daemon-Protocol': String(PROTOCOL_VERSION),
      'KK-Daemon-Instance': this.daemonInstanceId,
      'KK-Daemon-Platform': this.platformKey,
    }

    const ws = new WebSocket(this.gatewayUri, {
      headers,
      perMessageDeflate: false,
      maxPayload: MAX_WS_PAYLOAD_BYTES,
    })

    this.activeWs = ws

    // 立即建立 WebSocket stream 与 HTTP/2 server 握手，不等 open 事件
    const wsStream = createWebSocketStream(ws)
    const session = http2.performServerHandshake(wsStream, {
      settings: {
        enableConnectProtocol: true,
        maxConcurrentStreams: 512,
        initialWindowSize: 1024 * 1024,
        maxFrameSize: 16384,
      },
    })
    this.activeSession = session

    let connectResolved = false
    let resolveConnect: (conn: ControlConnection) => void
    let rejectConnect: (err: Error) => void
    const connectPromise = new Promise<ControlConnection>((resolve, reject) => {
      resolveConnect = resolve
      rejectConnect = reject
    })

    let lastInboundAt = Date.now()
    const recordInbound = (): void => {
      lastInboundAt = Date.now()
    }

    wsStream.on('data', recordInbound)
    ws.on('message', (_data, isBinary) => {
      recordInbound()
      if (!isBinary) {
        // 收到文本消息以 1003 关闭
        try {
          ws.close(1003, 'text frames not supported')
        } catch {}
      }
    })

    // 存活检测定时器：45 秒内未收到入站字节判定会话失败
    const inactivityInterval = Math.min(1000, Math.max(100, Math.floor(this.inactivityTimeoutMillis / 4)))
    const timer = setInterval(() => {
      if (Date.now() - lastInboundAt >= this.inactivityTimeoutMillis) {
        clearInterval(timer)
        session.destroy()
        try {
          ws.terminate()
        } catch {}
      }
    }, inactivityInterval)

    let controlStream: ServerHttp2Stream | null = null
    let activeControlConnection: ControlConnection | null = null
    let disconnectedFired = false

    const fireDisconnected = (): void => {
      clearInterval(timer)
      if (controlTimer) {
        clearTimeout(controlTimer)
      }
      if (!disconnectedFired) {
        disconnectedFired = true
        listener.onDisconnected()
      }
      if (!connectResolved) {
        connectResolved = true
        rejectConnect(new Error('connection failed before control stream established'))
      }
    }

    ws.on('unexpected-response', (_req, res) => {
      clearInterval(timer)
      if (controlTimer) {
        clearTimeout(controlTimer)
      }
      const statusCode = res.statusCode ?? 500
      if (statusCode === 426) {
        const chunks: Buffer[] = []
        res.on('data', (chunk) => chunks.push(Buffer.from(chunk)))
        res.on('end', () => {
          let upgradeRequired: UpgradeRequired | undefined
          try {
            const body = Buffer.concat(chunks)
            if (body.length > 0) {
              upgradeRequired = validateUpgrade(fromBinary(UpgradeRequiredSchema, body))
            }
          } catch {}
          if (!connectResolved) {
            connectResolved = true
            rejectConnect(new Error(`handshake failed with 426`))
          }
          listener.onUpgradeFailed(426, upgradeRequired)
          try {
            session.destroy()
          } catch {}
          try {
            ws.terminate()
          } catch {}
        })
      } else {
        res.resume()
        if (!connectResolved) {
          connectResolved = true
          rejectConnect(new Error(`handshake failed with status ${statusCode}`))
        }
        listener.onUpgradeFailed(statusCode)
        try {
          session.destroy()
        } catch {}
        try {
          ws.terminate()
        } catch {}
      }
    })

    session.on('goaway', (errorCode) => {
      if (errorCode === h2constants.NGHTTP2_NO_ERROR) {
        listener.onGoaway()
      }
    })

    session.on('error', () => {
      fireDisconnected()
    })
    session.on('close', () => {
      fireDisconnected()
    })
    ws.on('error', () => {
      fireDisconnected()
    })
    ws.on('close', () => {
      fireDisconnected()
    })

    // 控制流超时保护
    const controlTimer = setTimeout(() => {
      if (!connectResolved) {
        session.destroy()
        try {
          ws.terminate()
        } catch {}
        fireDisconnected()
      }
    }, this.controlStreamTimeoutMillis)

    session.on('stream', (stream, streamHeaders) => {
      stream.on('error', () => {})
      const method = (streamHeaders[HTTP2_HEADER_METHOD] as string) || ''
      const path = (streamHeaders[HTTP2_HEADER_PATH] as string) || ''
      const protocol = (streamHeaders[HTTP2_HEADER_PROTOCOL] as string) || ''

      // 1. 控制流：POST /control
      if (method === 'POST' && path === '/control') {
        if (controlStream !== null) {
          // 重复打开以 REFUSED_STREAM 拒绝
          try {
            stream.close(NGHTTP2_REFUSED_STREAM)
          } catch {}
          return
        }

        controlStream = stream
        try {
          stream.respond({ [HTTP2_HEADER_STATUS]: 200 })
        } catch {
          fireDisconnected()
          return
        }

        let buffer = Buffer.alloc(0)
        stream.on('data', (chunk: Buffer) => {
          recordInbound()
          buffer = Buffer.concat([buffer, chunk])
          while (buffer.length >= 4) {
            const messageLength = buffer.readUInt32BE(0)
            const frameLength = 4 + messageLength
            if (buffer.length < frameLength) {
              break
            }
            const frameBytes = buffer.subarray(0, frameLength)
            buffer = buffer.subarray(frameLength)
            try {
              const envelope = decodeFrame(new Uint8Array(frameBytes))
              listener.onEnvelope(envelope)
            } catch {
              try {
                stream.close(h2constants.NGHTTP2_PROTOCOL_ERROR)
              } catch {}
              fireDisconnected()
              return
            }
          }
        })

        stream.on('error', () => {
          controlStream = null
          fireDisconnected()
        })
        stream.on('close', () => {
          controlStream = null
          fireDisconnected()
        })

        activeControlConnection = {
          send: (envelope: Envelope): boolean => {
            if (
              controlStream === null ||
              controlStream.closed ||
              controlStream.destroyed ||
              session.closed ||
              session.destroyed
            ) {
              return false
            }
            try {
              const framed = encodeFrame(envelope)
              controlStream.write(Buffer.from(framed))
              return true
            } catch {
              return false
            }
          },
          isOpen: (): boolean => {
            return (
              controlStream !== null &&
              !controlStream.closed &&
              !controlStream.destroyed &&
              !session.closed &&
              !session.destroyed
            )
          },
          close: (): void => {
            try {
              controlStream?.end()
            } catch {}
            try {
              session.close()
            } catch {}
            try {
              ws.close()
            } catch {}
          },
        }

        if (!connectResolved) {
          connectResolved = true
          clearTimeout(controlTimer)
          resolveConnect(activeControlConnection)
        }
        return
      }

      // 2. extended CONNECT (IDE WebSocket)
      if (method === 'CONNECT' && protocol === 'websocket') {
        void handleIdeConnectRequest(stream, streamHeaders, this.ide)
        return
      }

      // 3. IDE HTTP 流
      if (path.startsWith('/ide/')) {
        void handleIdeHttpRequest(stream, streamHeaders, this.ide)
        return
      }

      // 其它未知流
      if (!stream.closed && !stream.destroyed) {
        try {
          stream.respond({ [HTTP2_HEADER_STATUS]: 404 }, { endStream: true })
        } catch {}
      }
    })

    return connectPromise
  }

  async close(): Promise<void> {
    this.closed = true
    if (this.activeSession && !this.activeSession.destroyed) {
      try {
        this.activeSession.destroy()
      } catch {}
    }
    if (this.activeWs) {
      try {
        this.activeWs.terminate()
      } catch {}
    }
  }
}

export function createDaemonTransport(options: DaemonTransportOptions): ControlTransport {
  return new DaemonWebSocketTransport(options)
}
