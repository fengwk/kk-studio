import { Buffer } from 'node:buffer'
import http, { type IncomingHttpHeaders } from 'node:http'
import https from 'node:https'
import http2, {
  constants as h2constants,
  type ServerHttp2Session,
  type ServerHttp2Stream,
} from 'node:http2'
import type { Duplex } from 'node:stream'
import { HttpsProxyAgent } from 'https-proxy-agent'
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
import { abortControlSession, closeControlSession, guardControlStream } from './control-session.js'

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
  /** 显式覆盖当前配置推导出的 gateway；生产不使用。 */
  gatewayUri?: string
  /** 每次连接前读取当前已应用配置；配置切换后同进程重连读取新地址/代理。 */
  config: DaemonConfig | (() => DaemonConfig)
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
  private readonly explicitGatewayUri: string | null
  private readonly configProvider: () => DaemonConfig
  private readonly ide: IdeService
  private readonly daemonInstanceId: string
  private readonly platformKey: string
  private readonly inactivityTimeoutMillis: number
  private readonly controlStreamTimeoutMillis: number
  private activeWs: WebSocket | null = null
  private activeWsStream: Duplex | null = null
  private activeSession: ServerHttp2Session | null = null
  private closed = false

  constructor(options: DaemonTransportOptions) {
    this.explicitGatewayUri = options.gatewayUri ?? null
    this.configProvider = typeof options.config === 'function' ? options.config : () => options.config as DaemonConfig
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

    // 每次连接都读取当前已应用配置：代理/地址切换后重连不再复用旧值。
    const config = this.configProvider()
    const gatewayUri = this.explicitGatewayUri ?? config.gatewayUri
    let token: string
    try {
      token = readRegistrationToken(config)
    } catch (err) {
      throw new Error(`cannot read registration token: ${(err as Error).message}`)
    }

    const headers: Record<string, string> = {
      Authorization: `Bearer ${token}`,
      'KK-Daemon-Protocol': String(PROTOCOL_VERSION),
      'KK-Daemon-Instance': this.daemonInstanceId,
      'KK-Daemon-Platform': this.platformKey,
      'KK-Environment-Id': config.environmentId,
    }

    // 显式直连或卡片代理；绝不使用会读取 NODE_USE_ENV_PROXY 的全局 Agent。
    const agent =
      config.proxyUrl === null
        ? gatewayUri.startsWith('wss:')
          ? new https.Agent()
          : new http.Agent()
        : new HttpsProxyAgent(config.proxyUrl)
    const destroyAgent = (): void => {
      try {
        agent.destroy()
      } catch {
        // 清理失败不影响连接已关闭的事实。
      }
    }
    const ws = new WebSocket(gatewayUri, {
      headers,
      agent,
      perMessageDeflate: false,
      maxPayload: MAX_WS_PAYLOAD_BYTES,
    })
    ws.once('close', destroyAgent)
    ws.once('error', destroyAgent)

    this.activeWs = ws

    // 立即建立 WebSocket stream 与 HTTP/2 server 握手，不等 open 事件
    const wsStream = createWebSocketStream(ws)
    this.activeWsStream = wsStream
    guardControlStream(ws, wsStream)

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
        abortControlSession(session, wsStream, ws)
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
          abortControlSession(session, wsStream, ws)
        })
      } else {
        res.resume()
        if (!connectResolved) {
          connectResolved = true
          rejectConnect(new Error(`handshake failed with status ${statusCode}`))
        }
        listener.onUpgradeFailed(statusCode)
        abortControlSession(session, wsStream, ws)
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
        abortControlSession(session, wsStream, ws)
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
            closeControlSession(session, wsStream, ws, controlStream)
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
    const stream = this.activeWsStream
    const ws = this.activeWs
    if (stream !== null && ws !== null) {
      abortControlSession(this.activeSession, stream, ws)
    }
  }
}

export function createDaemonTransport(options: DaemonTransportOptions): ControlTransport {
  return new DaemonWebSocketTransport(options)
}
