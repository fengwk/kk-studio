import http, { type IncomingHttpHeaders, type IncomingMessage, type ServerResponse } from 'node:http'
import http2, {
  constants as h2constants,
  type ClientHttp2Session,
  type ClientHttp2Stream,
} from 'node:http2'
import type net from 'node:net'
import { Buffer } from 'node:buffer'
import { createWebSocketStream, WebSocket, WebSocketServer } from 'ws'

import {
  decodeFrame,
  encodeFrame,
  PROTOCOL_VERSION,
} from '../../src/protocol/control/codec.js'
import {
  EnvelopeSchema,
  EnvironmentConfigSchema,
  DaemonConfigurationSchema,
  MessageType,
  ResourceBudgetsSchema,
  SettingsSchema,
  type Envelope,
} from '../../src/generated/control_pb.js'
import { create } from '@bufbuild/protobuf'

const {
  HTTP2_HEADER_METHOD,
  HTTP2_HEADER_PATH,
  HTTP2_HEADER_STATUS,
} = h2constants

const HTTP2_HEADER_PROTOCOL = ':protocol'

export interface HandshakeInfo {
  headers: IncomingHttpHeaders
  token?: string
  protocol?: string
  instance?: string
  platform?: string
}

export interface FakeStudioOptions {
  onHandshake?: (req: IncomingMessage, res: ServerResponse) => boolean | void
  autoControl?: boolean
  environmentId?: string
  studioUrl?: string | (() => string)
  /** 自动 ENVIRONMENT_CONFIG 必须与启动缓存内容一致；Windows 会显式配置 Git bash。 */
  bashExecutable?: string
}

export class FakeStudioPeer {
  readonly server: http.Server
  readonly wss: WebSocketServer
  private port = 0
  private latestHandshake: HandshakeInfo | null = null
  private activeWs: WebSocket | null = null
  private clientSession: ClientHttp2Session | null = null
  private controlStream: ClientHttp2Stream | null = null
  private controlBuffer = Buffer.alloc(0)
  private readonly receivedEnvelopes: Envelope[] = []
  private readonly allReceivedEnvelopes: Envelope[] = []
  private readonly envelopeListeners: Array<(envelope: Envelope) => void> = []
  private readonly sockets = new Set<net.Socket>()
  private readonly options: FakeStudioOptions
  private rejectStatus: number | null = null

  constructor(options: FakeStudioOptions = {}) {
    this.options = options
    this.server = http.createServer((req, res) => {
      if (options.onHandshake) {
        const handled = options.onHandshake(req, res)
        if (handled) {
          return
        }
      }
      res.statusCode = 404
      res.end()
    })

    this.server.on('connection', (socket) => {
      this.sockets.add(socket)
      socket.once('close', () => this.sockets.delete(socket))
    })

    this.wss = new WebSocketServer({ noServer: true })

    this.server.on('upgrade', (req, socket, head) => {
      // 测试替身只接受与 Studio 相同的控制连接路径。
      if (req.url !== '/ws/daemon') {
        socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
        socket.destroy()
        return
      }
      if (this.rejectStatus !== null) {
        const status = this.rejectStatus
        socket.write(`HTTP/1.1 ${status} Rejected\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`)
        socket.destroy()
        return
      }

      this.latestHandshake = {
        headers: req.headers,
        token: req.headers['authorization']?.replace(/^Bearer\s+/, ''),
        protocol: req.headers['kk-daemon-protocol'] as string,
        instance: req.headers['kk-daemon-instance'] as string,
        platform: req.headers['kk-daemon-platform'] as string,
      }

      if (options.onHandshake) {
        const res = new http.ServerResponse(req)
        res.assignSocket(socket)
        const handled = options.onHandshake(req, res)
        if (handled) {
          return
        }
      }

      this.wss.handleUpgrade(req, socket, head, (ws) => {
        this.activeWs = ws
        this.setupHttp2(ws)
      })
    })
  }

  async start(): Promise<number> {
    return new Promise<number>((resolve) => {
      this.server.listen(0, '127.0.0.1', () => {
        const addr = this.server.address() as { port: number }
        this.port = addr.port
        resolve(this.port)
      })
    })
  }

  get origin(): string {
    return `http://127.0.0.1:${this.port}`
  }

  get gatewayUri(): string {
    return `ws://127.0.0.1:${this.port}/ws/daemon`
  }

  getHandshake(): HandshakeInfo | null {
    return this.latestHandshake
  }

  get received(): Envelope[] {
    return this.allReceivedEnvelopes
  }

  getActiveWs(): WebSocket | null {
    return this.activeWs
  }

  getControlStream(): ClientHttp2Stream | null {
    return this.controlStream
  }

  private setupHttp2(ws: WebSocket): void {
    const wsStream = createWebSocketStream(ws)
    const session = http2.connect('https://daemon', {
      createConnection: () => wsStream,
      settings: { enableConnectProtocol: true },
    })
    this.clientSession = session

    session.on('error', () => {})

    if (this.options.autoControl) {
      void this.openControlStream().then(({ statusCode }) => {
        if (statusCode === 200) {
          const environmentId = this.options.environmentId ?? '4e0d3c2b-1a09-4f8e-9d7c-6b5a49382716'
          this.sendEnvelope(
            create(EnvelopeSchema, {
              messageType: MessageType.SETTINGS,
              environmentId,
              payload: {
                case: 'settings',
                value: create(SettingsSchema, {
                  resourceBudgets: create(ResourceBudgetsSchema, {
                    temporaryResourceTtlSeconds: 60n,
                    temporaryResourceCleanupIntervalSeconds: 30n,
                  }),
                }),
              },
            }),
          )
          this.sendEnvelope(
            create(EnvelopeSchema, {
              messageType: MessageType.ENVIRONMENT_CONFIG,
              environmentId,
              payload: {
                case: 'environmentConfig',
                value: create(EnvironmentConfigSchema, {
                  version: 0n,
                  config: create(DaemonConfigurationSchema, {
                    studioUrl:
                      typeof this.options.studioUrl === 'function'
                        ? this.options.studioUrl()
                        : this.options.studioUrl ?? 'https://studio.example.com',
                    ...(this.options.bashExecutable === undefined
                      ? {}
                      : { bashExecutable: this.options.bashExecutable }),
                  }),
                }),
              },
            }),
          )
        }
      })
    }
  }

  async awaitSession(): Promise<ClientHttp2Session> {
    if (this.clientSession && !this.clientSession.closed && !this.clientSession.destroyed) {
      return this.clientSession
    }
    return new Promise<ClientHttp2Session>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('session setup timed out')), 5000)
      const check = setInterval(() => {
        if (this.clientSession && !this.clientSession.closed && !this.clientSession.destroyed) {
          clearInterval(check)
          clearTimeout(timer)
          resolve(this.clientSession)
        }
      }, 50)
    })
  }

  async openControlStream(): Promise<{
    stream: ClientHttp2Stream
    statusCode: number
  }> {
    const session = await this.awaitSession()
    return new Promise((resolve, reject) => {
      const stream = session.request({
        [HTTP2_HEADER_METHOD]: 'POST',
        [HTTP2_HEADER_PATH]: '/control',
      })
      this.controlStream = stream

      stream.on('response', (headers) => {
        const statusCode = Number(headers[HTTP2_HEADER_STATUS])
        resolve({ stream, statusCode })
      })

      stream.on('error', (err) => reject(err))

      stream.on('data', (chunk: Buffer) => {
        this.controlBuffer = Buffer.concat([this.controlBuffer, chunk])
        while (this.controlBuffer.length >= 4) {
          const length = this.controlBuffer.readUInt32BE(0)
          const totalLength = 4 + length
          if (this.controlBuffer.length < totalLength) {
            break
          }
          const frameBytes = this.controlBuffer.subarray(0, totalLength)
          this.controlBuffer = this.controlBuffer.subarray(totalLength)
          try {
            const envelope = decodeFrame(new Uint8Array(frameBytes))
            this.allReceivedEnvelopes.push(envelope)
            if (this.envelopeListeners.length > 0) {
              const listener = this.envelopeListeners.shift()!
              listener(envelope)
            } else {
              this.receivedEnvelopes.push(envelope)
            }
          } catch {}
        }
      })
    })
  }

  sendEnvelope(envelope: Envelope): void {
    if (!this.controlStream || this.controlStream.closed || this.controlStream.destroyed) {
      throw new Error('control stream is not open')
    }
    const framed = encodeFrame(envelope)
    this.controlStream.write(Buffer.from(framed))
  }

  async nextEnvelope(timeoutMillis = 5000): Promise<Envelope> {
    if (this.receivedEnvelopes.length > 0) {
      return this.receivedEnvelopes.shift()!
    }
    return new Promise<Envelope>((resolve, reject) => {
      const timer = setTimeout(() => {
        const idx = this.envelopeListeners.indexOf(listener)
        if (idx >= 0) this.envelopeListeners.splice(idx, 1)
        reject(new Error('timeout waiting for envelope'))
      }, timeoutMillis)

      const listener = (env: Envelope): void => {
        clearTimeout(timer)
        resolve(env)
      }

      this.envelopeListeners.push(listener)
    })
  }

  async requestHttp(
    path: string,
    method = 'GET',
    headers: Record<string, string> = {},
    body?: Buffer | string,
  ): Promise<{ status: number; headers: IncomingHttpHeaders; body: Buffer }> {
    const session = await this.awaitSession()
    return new Promise((resolve, reject) => {
      const req = session.request({
        [HTTP2_HEADER_METHOD]: method,
        [HTTP2_HEADER_PATH]: path,
        ...headers,
      })
      const chunks: Buffer[] = []
      let status = 0
      let resHeaders: IncomingHttpHeaders = {}

      req.on('response', (h) => {
        status = Number(h[HTTP2_HEADER_STATUS])
        resHeaders = h
      })
      req.on('data', (c) => chunks.push(Buffer.from(c)))
      req.on('end', () => {
        resolve({ status, headers: resHeaders, body: Buffer.concat(chunks) })
      })
      req.on('error', reject)

      if (body) {
        req.end(body)
      } else {
        req.end()
      }
    })
  }

  async openConnectStream(
    path: string,
    headers: Record<string, string> = {},
  ): Promise<ClientHttp2Stream> {
    const session = await this.awaitSession()
    const req = session.request({
      [HTTP2_HEADER_METHOD]: 'CONNECT',
      [HTTP2_HEADER_PROTOCOL]: 'websocket',
      [HTTP2_HEADER_PATH]: path,
      'sec-websocket-key': 'dGhlIHNhbXBsZSBub25jZQ==',
      'sec-websocket-version': '13',
      ...headers,
    })
    return req
  }

  sendGoaway(code = h2constants.NGHTTP2_NO_ERROR): void {
    if (this.clientSession && !this.clientSession.closed && !this.clientSession.destroyed) {
      this.clientSession.goaway(code)
    }
  }

  rejectAndClose(status = 401): void {
    this.rejectStatus = status
    if (this.activeWs) {
      try {
        this.activeWs.close()
      } catch {}
    }
  }

  async close(): Promise<void> {
    if (this.clientSession && !this.clientSession.closed && !this.clientSession.destroyed) {
      try {
        this.clientSession.destroy()
      } catch {}
    }
    if (this.activeWs) {
      try {
        this.activeWs.terminate()
      } catch {}
    }
    for (const socket of this.sockets) {
      try {
        socket.destroy()
      } catch {}
    }
    this.sockets.clear()
    await new Promise<void>((resolve) => {
      this.wss.close(() => {
        this.server.close(() => resolve())
      })
    })
  }
}
