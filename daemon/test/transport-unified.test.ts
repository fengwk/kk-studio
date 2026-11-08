import http from 'node:http'
import https from 'node:https'
import { execFileSync } from 'node:child_process'
import net from 'node:net'
import { readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { randomUUID } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { create, toBinary } from '@bufbuild/protobuf'
import { WebSocketServer, WebSocket } from 'ws'
import { constants as h2constants } from 'node:http2'

import {
  createDaemonTransport,
} from '../src/transport/index.js'
import { Runtime } from '../src/runtime.js'
import type { DaemonConfig, IdeService, IdeStreamUpstream } from '../src/contracts.js'
import {
  EnvelopeSchema,
  EnvironmentConfigSchema,
  DaemonConfigurationSchema,
  HeartbeatSchema,
  IdeAction,
  IdeCommandSchema,
  IdeOpenMode,
  IdeResultSchema,
  IdeViewState,
  IdeViewSummarySchema,
  InvokeSchema,
  MessageType,
  ReadySchema,
  RecoverNotFoundSchema,
  RecoverSchema,
  ResourceBudgetsSchema,
  SettingsSchema,
  UpgradeRequiredSchema,
  type Envelope,
} from '../src/generated/control_pb.js'
import { PROTOCOL_VERSION } from '../src/protocol/control/codec.js'
import { ensurePrivateDirectory, writePrivateFile } from '../src/native/private-fs.js'
import { FakeStudioPeer } from './support/fake-studio.js'
import { IdeError } from '../src/ide/errors.js'

interface ConnectProxy {
  url: string
  targets: string[]
  close(): Promise<void>
}

/** 真实 HTTP CONNECT 代理：记录隧道目标并双向转发，验证 WS 控制连接实际经过卡片代理。 */
async function startConnectProxy(): Promise<ConnectProxy> {
  const targets: string[] = []
  const sockets = new Set<net.Socket>()
  const server = http.createServer((_request, response) => {
    response.writeHead(405)
    response.end()
  })
  server.on('connection', (socket) => {
    sockets.add(socket)
    socket.once('close', () => sockets.delete(socket))
  })
  server.on('connect', (request, clientSocket, head) => {
    targets.push(request.url ?? '')
    const [host, portText] = (request.url ?? '').split(':')
    const upstream = net.connect(Number(portText), host, () => {
      clientSocket.write('HTTP/1.1 200 Connection Established\r\n\r\n')
      if (head.length > 0) {
        upstream.write(head)
      }
      upstream.pipe(clientSocket)
      clientSocket.pipe(upstream)
    })
    sockets.add(upstream)
    upstream.once('close', () => sockets.delete(upstream))
    upstream.on('error', () => clientSocket.destroy())
    clientSocket.on('error', () => upstream.destroy())
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()))
  const port = (server.address() as net.AddressInfo).port
  return {
    url: `http://127.0.0.1:${port}`,
    targets,
    close: () =>
      new Promise<void>((resolve) => {
        for (const socket of sockets) {
          socket.destroy()
        }
        server.close(() => resolve())
      }),
  }
}

describe('Unified Transport & Control Protocol', () => {
  let tempDir: string
  let tokenPath: string
  let config: DaemonConfig
  let peer: FakeStudioPeer | null = null

  beforeEach(async () => {
    // Windows 的 %TEMP% 继承宽松 DACL；配置热写会严格核验父目录私有，因此 fixture 必须真实收紧。
    tempDir = await ensurePrivateDirectory(join(tmpdir(), `daemon-transport-test-${randomUUID()}`))
    tokenPath = join(tempDir, 'daemon.token')
    await writePrivateFile(tokenPath, 'test-secret-token\n')

    config = {
      environmentId: '11111111-1111-4111-8111-111111111111',
      configVersion: 0n,
      configPath: join(tempDir, 'daemon.json'),
      dataDir: tempDir,
      tokenPath,
      studioUrl: 'http://127.0.0.1:8080',
      gatewayUri: 'ws://127.0.0.1:8080/ws/daemon',
      bashExecutable: '/bin/bash',
      bashExecutableSetting: null,
      lsp: {},
      proxyUrl: null,
      distributionDir: tempDir,
    }
  })

  afterEach(async () => {
    if (peer) {
      await peer.close()
      peer = null
    }
    rmSync(tempDir, { recursive: true, force: true })
  })

  it('connects with the daemon request headers and completes handshake', async () => {
    peer = new FakeStudioPeer()
    await peer.start()

    const transport = createDaemonTransport({
      gatewayUri: peer.gatewayUri,
      config,
      ide: {
        resolveView: async () => ({} as unknown as IdeStreamUpstream),
      } as unknown as IdeService,
      daemonInstanceId: '11111111-1111-4111-8111-111111111111',
      platformKey: 'linux-amd64',
    })

    const connectPromise = transport.connect({
      onEnvelope: () => {},
      onDisconnected: () => {},
      onGoaway: () => {},
      onUpgradeFailed: () => {},
    })

    // 等待 peer 收到连接并打开 control 流
    const { statusCode } = await peer.openControlStream()
    expect(statusCode).toBe(200)

    const conn = await connectPromise
    expect(conn.isOpen()).toBe(true)

    const handshake = peer.getHandshake()
    expect(handshake).not.toBeNull()
    expect(handshake?.token).toBe('test-secret-token')
    expect(handshake?.protocol).toBe(String(PROTOCOL_VERSION))
    expect(handshake?.instance).toBe('11111111-1111-4111-8111-111111111111')
    expect(handshake?.platform).toBe('linux-amd64')

    await transport.close()
  })

  // 每次 connect 读取当前配置：地址/代理热切换后重连仍使用同一 daemon instance，并携带固定环境标识。
  it('reconnects with the current gateway and proxy while preserving the daemon instance', async () => {
    const first = new FakeStudioPeer()
    const second = new FakeStudioPeer()
    const proxy = await startConnectProxy()
    await first.start()
    await second.start()
    const current: DaemonConfig = {
      ...config,
      studioUrl: first.origin,
      gatewayUri: first.gatewayUri,
      proxyUrl: proxy.url,
    }
    const instanceId = randomUUID()
    const transport = createDaemonTransport({
      config: () => current,
      ide: {} as unknown as IdeService,
      daemonInstanceId: instanceId,
      platformKey: 'linux-amd64',
    })
    const listener = {
      onEnvelope: () => {},
      onDisconnected: () => {},
      onGoaway: () => {},
      onUpgradeFailed: () => {},
    }
    try {
      const firstConnect = transport.connect(listener)
      expect((await first.openControlStream()).statusCode).toBe(200)
      const firstConnection = await firstConnect
      expect(first.getHandshake()).toMatchObject({ instance: instanceId })
      expect(first.getHandshake()?.headers['kk-environment-id']).toBe(config.environmentId)
      const firstPort = (first.server.address() as net.AddressInfo).port
      expect(proxy.targets).toContain(`127.0.0.1:${firstPort}`)
      firstConnection.close()

      current.studioUrl = second.origin
      current.gatewayUri = second.gatewayUri
      current.proxyUrl = null
      const secondConnect = transport.connect(listener)
      expect((await second.openControlStream()).statusCode).toBe(200)
      const secondConnection = await secondConnect
      expect(second.getHandshake()).toMatchObject({ instance: instanceId })
      expect(second.getHandshake()?.headers['kk-environment-id']).toBe(config.environmentId)
      expect(proxy.targets).toHaveLength(1)
      secondConnection.close()
    } finally {
      await transport.close()
      await proxy.close()
      await first.close()
      await second.close()
    }
  })

  it('establishes WSS directly even when global and platform proxies are unusable', async () => {
    const key = join(tempDir, 'key.pem')
    const cert = join(tempDir, 'cert.pem')
    execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', key, '-out', cert,
      '-days', '1', '-subj', '/CN=localhost'], { stdio: 'ignore' })
    const server = https.createServer({ key: readFileSync(key), cert: readFileSync(cert) })
    const wss = new WebSocketServer({ server })
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
    const port = (server.address() as net.AddressInfo).port
    let handshake = false
    wss.on('connection', (socket) => {
      handshake = true
      socket.terminate()
    })
    vi.stubEnv('NODE_TLS_REJECT_UNAUTHORIZED', '0')
    vi.stubEnv('HTTPS_PROXY', 'http://127.0.0.1:1')
    const globalAgent = vi.spyOn(https.globalAgent, 'addRequest').mockImplementation(() => {
      throw new Error('global agent must not be used')
    })
    const transport = createDaemonTransport({
      gatewayUri: `wss://127.0.0.1:${port}/ws/daemon`,
      config,
      ide: {} as IdeService,
      daemonInstanceId: randomUUID(),
      platformKey: 'linux-amd64',
    })
    try {
      await expect(transport.connect({
        onEnvelope: () => {}, onDisconnected: () => {}, onGoaway: () => {}, onUpgradeFailed: () => {},
      })).rejects.toThrow()
      expect(handshake).toBe(true)
      expect(globalAgent).not.toHaveBeenCalled()
    } finally {
      await transport.close()
      globalAgent.mockRestore()
      vi.unstubAllEnvs()
      await new Promise<void>((resolve) => wss.close(() => server.close(() => resolve())))
    }
  })

  it('handles 401 registration rejected as terminal FAILED', async () => {
    peer = new FakeStudioPeer({
      onHandshake: (_req, res) => {
        res.writeHead(401, { 'content-type': 'text/plain' })
        res.end('Unauthorized')
        return true
      },
    })
    await peer.start()

    let failedStatusCode = 0
    const transport = createDaemonTransport({
      gatewayUri: peer.gatewayUri,
      config,
      ide: {} as unknown as IdeService,
      daemonInstanceId: randomUUID(),
      platformKey: 'linux-amd64',
    })

    await expect(
      transport.connect({
        onEnvelope: () => {},
        onDisconnected: () => {},
        onGoaway: () => {},
        onUpgradeFailed: (code) => {
          failedStatusCode = code
        },
      }),
    ).rejects.toThrow(/handshake failed with status 401/)

    expect(failedStatusCode).toBe(401)
    await transport.close()
  })

  it('handles 426 UpgradeRequired with target version', async () => {
    const upgradeRequired = create(UpgradeRequiredSchema, { targetVersion: '1.2.3' })

    peer = new FakeStudioPeer({
      onHandshake: (_req, res) => {
        const body = toBinary(UpgradeRequiredSchema, upgradeRequired)
        res.writeHead(426, { 'content-type': 'application/x-protobuf' })
        res.end(Buffer.from(body))
        return true
      },
    })
    await peer.start()

    let upgradeReceived: unknown = null
    const transport = createDaemonTransport({
      gatewayUri: peer.gatewayUri,
      config,
      ide: {} as unknown as IdeService,
      daemonInstanceId: randomUUID(),
      platformKey: 'linux-amd64',
    })

    await expect(
      transport.connect({
        onEnvelope: () => {},
        onDisconnected: () => {},
        onGoaway: () => {},
        onUpgradeFailed: (_code, upgrade) => {
          upgradeReceived = upgrade
        },
      }),
    ).rejects.toThrow(/handshake failed with 426/)

    expect(upgradeReceived).toMatchObject({ targetVersion: '1.2.3' })
    await transport.close()
  })

  it('rejects duplicate POST /control with REFUSED_STREAM', async () => {
    peer = new FakeStudioPeer()
    await peer.start()

    const transport = createDaemonTransport({
      gatewayUri: peer.gatewayUri,
      config,
      ide: {} as unknown as IdeService,
      daemonInstanceId: randomUUID(),
      platformKey: 'linux-amd64',
    })

    const connectPromise = transport.connect({
      onEnvelope: () => {},
      onDisconnected: () => {},
      onGoaway: () => {},
      onUpgradeFailed: () => {},
    })

    const first = await peer.openControlStream()
    expect(first.statusCode).toBe(200)
    await connectPromise

    // 第二次打开 /control
    const session = await peer.awaitSession()
    const duplicate = session.request({
      ':method': 'POST',
      ':path': '/control',
    })
    duplicate.on('error', () => {})

    const duplicateClosed = new Promise<number>((resolve) => {
      duplicate.on('close', () => {
        resolve(duplicate.rstCode)
      })
    })

    const rstCode = await duplicateClosed
    expect(rstCode).toBe(h2constants.NGHTTP2_REFUSED_STREAM)

    await transport.close()
  })

  it('performs SETTINGS -> READY exchange on the control stream', async () => {
    peer = new FakeStudioPeer()
    await peer.start()

    let envelopeReceived: Envelope | null = null
    const transport = createDaemonTransport({
      gatewayUri: peer.gatewayUri,
      config,
      ide: {} as unknown as IdeService,
      daemonInstanceId: randomUUID(),
      platformKey: 'linux-amd64',
    })

    const connectPromise = transport.connect({
      onEnvelope: (env) => {
        envelopeReceived = env
      },
      onDisconnected: () => {},
      onGoaway: () => {},
      onUpgradeFailed: () => {},
    })

    await peer.openControlStream()
    const conn = await connectPromise

    // Studio 发送 SETTINGS
    const settingsEnvelope = create(EnvelopeSchema, {
      messageType: MessageType.SETTINGS,
      environmentId: '11111111-1111-4111-8111-111111111111',
      payload: {
        case: 'settings',
        value: create(SettingsSchema, {
          noProxyHosts: '',
          resourceBudgets: create(ResourceBudgetsSchema, {
            maxResourceBytes: 1048576n,
            temporaryResourceTtlSeconds: 3600n,
            temporaryResourceCleanupIntervalSeconds: 60n,
          }),
        }),
      },
    })
    peer.sendEnvelope(settingsEnvelope)

    // 等待 Daemon 收到并处理
    await vi.waitFor(() => {
      expect(envelopeReceived).not.toBeNull()
    })
    expect((envelopeReceived as unknown as Envelope).messageType).toBe(MessageType.SETTINGS)

    // Daemon 回复 RECOVER_NOT_FOUND
    const recoverNotFound = create(EnvelopeSchema, {
      messageType: MessageType.RECOVER_NOT_FOUND,
      environmentId: '11111111-1111-4111-8111-111111111111',
      invocationId: 'invoke-123',
      payload: {
        case: 'recoverNotFound',
        value: create(RecoverNotFoundSchema, {}),
      },
    })
    const sent = conn.send(recoverNotFound)
    expect(sent).toBe(true)

    const receivedByPeer = await peer.nextEnvelope()
    expect(receivedByPeer.messageType).toBe(MessageType.RECOVER_NOT_FOUND)
    expect(receivedByPeer.invocationId).toBe('invoke-123')

    await transport.close()
  })

  it('triggers onGoaway on HTTP/2 GOAWAY frame with NO_ERROR', async () => {
    peer = new FakeStudioPeer()
    await peer.start()

    let goawayFired = false
    const transport = createDaemonTransport({
      gatewayUri: peer.gatewayUri,
      config,
      ide: {} as unknown as IdeService,
      daemonInstanceId: randomUUID(),
      platformKey: 'linux-amd64',
    })

    const connectPromise = transport.connect({
      onEnvelope: () => {},
      onDisconnected: () => {},
      onGoaway: () => {
        goawayFired = true
      },
      onUpgradeFailed: () => {},
    })

    await peer.openControlStream()
    await connectPromise

    peer.sendGoaway(h2constants.NGHTTP2_NO_ERROR)
    await vi.waitFor(() => {
      expect(goawayFired).toBe(true)
    })

    await transport.close()
  })

  it('fails session on inactivity timeout (45s requirement)', async () => {
    peer = new FakeStudioPeer()
    await peer.start()

    let disconnected = false
    const transport = createDaemonTransport({
      gatewayUri: peer.gatewayUri,
      config,
      ide: {} as unknown as IdeService,
      daemonInstanceId: randomUUID(),
      platformKey: 'linux-amd64',
      inactivityTimeoutMillis: 150, // 缩短超时用于快速断言
    })

    const connectPromise = transport.connect({
      onEnvelope: () => {},
      onDisconnected: () => {
        disconnected = true
      },
      onGoaway: () => {},
      onUpgradeFailed: () => {},
    })

    await peer.openControlStream()
    await connectPromise

    // 等待超过 150ms 无数据触发 inactivity timeout 断开连接
    await vi.waitFor(() => {
      expect(disconnected).toBe(true)
    }, { timeout: 3000 })

    await transport.close()
  })

  describe('IDE HTTP Tunnel', () => {
    it('proxies HTTP requests to code-server and filters headers', async () => {
      // 启动假本地 code-server
      const fakeCodeServer = http.createServer((req, res) => {
        expect(req.url).toBe('/__kk/ide/view/11111111-1111-4111-8111-111111111111/1/index.html?token=abc')
        expect(req.headers['cookie']).toBe('kk-private-session=secret')
        expect(req.headers['x-custom']).toBe('foo')
        res.writeHead(200, {
          'content-type': 'text/html',
          'x-code-server': 'active',
          connection: 'keep-alive', // 应被过滤
        })
        res.end('<h1>Hello code-server</h1>')
      })

      const csPort = await new Promise<number>((resolve) => {
        fakeCodeServer.listen(0, '127.0.0.1', () => {
          resolve((fakeCodeServer.address() as net.AddressInfo).port)
        })
      })

      peer = new FakeStudioPeer()
      await peer.start()

      let released = false
      const mockIde: IdeService = {
        resolveView: async (_viewId, _epoch, req) => {
          return {
            host: '127.0.0.1',
            port: csPort,
            path: `/__kk/ide/view/11111111-1111-4111-8111-111111111111/1${req.path}`,
            headers: [['x-custom', 'foo']],
            cookie: 'kk-private-session=secret',
            signal: new AbortController().signal,
            release: () => {
              released = true
            },
          }
        },
      } as unknown as IdeService

      const transport = createDaemonTransport({
        gatewayUri: peer.gatewayUri,
        config,
        ide: mockIde,
        daemonInstanceId: randomUUID(),
        platformKey: 'linux-amd64',
      })

      const connectPromise = transport.connect({
        onEnvelope: () => {},
        onDisconnected: () => {},
        onGoaway: () => {},
        onUpgradeFailed: () => {},
      })

      await peer.openControlStream()
      await connectPromise

      const res = await peer.requestHttp(
        '/ide/11111111-1111-4111-8111-111111111111/1/index.html?token=abc',
        'GET',
      )

      expect(res.status).toBe(200)
      expect(res.headers['x-code-server']).toBe('active')
      expect(res.headers['connection']).toBeUndefined()
      expect(res.body.toString()).toBe('<h1>Hello code-server</h1>')
      expect(released).toBe(true)

      await new Promise<void>((r) => fakeCodeServer.close(() => r()))
      await transport.close()
    })

    it('maps resolveView failures to appropriate HTTP status codes', async () => {
      peer = new FakeStudioPeer()
      await peer.start()

      let currentMode: 'VIEW_GONE' | 'UNAVAILABLE' | 'OTHER' | 'INVALID_UPSTREAM' = 'VIEW_GONE'
      const mockIde: IdeService = {
        resolveView: async () => {
          if (currentMode === 'VIEW_GONE') {
            throw new IdeError('view gone', 'VIEW_GONE')
          }
          if (currentMode === 'UNAVAILABLE') {
            throw new IdeError('code-server down', 'UNAVAILABLE')
          }
          if (currentMode === 'INVALID_UPSTREAM') {
            return { host: 'invalid.upstream.example.com' } as unknown as IdeStreamUpstream
          }
          throw new Error('network explosion')
        },
      } as unknown as IdeService

      const transport = createDaemonTransport({
        gatewayUri: peer.gatewayUri,
        config,
        ide: mockIde,
        daemonInstanceId: randomUUID(),
        platformKey: 'linux-amd64',
      })

      const connectPromise = transport.connect({
        onEnvelope: () => {},
        onDisconnected: () => {},
        onGoaway: () => {},
        onUpgradeFailed: () => {},
      })

      await peer.openControlStream()
      await connectPromise

      currentMode = 'VIEW_GONE'
      const res404 = await peer.requestHttp('/ide/11111111-1111-4111-8111-111111111111/1/', 'GET')
      expect(res404.status).toBe(404)

      currentMode = 'UNAVAILABLE'
      const res503 = await peer.requestHttp('/ide/11111111-1111-4111-8111-111111111111/1/', 'GET')
      expect(res503.status).toBe(503)

      currentMode = 'OTHER'
      const res502 = await peer.requestHttp('/ide/11111111-1111-4111-8111-111111111111/1/', 'GET')
      expect(res502.status).toBe(502)

      currentMode = 'INVALID_UPSTREAM'
      const res503Invalid = await peer.requestHttp('/ide/11111111-1111-4111-8111-111111111111/1/', 'GET')
      expect(res503Invalid.status).toBe(503)

      // 格式错误的路径返回 400
      const res400 = await peer.requestHttp('/ide/invalid-path', 'GET')
      expect(res400.status).toBe(400)

      await transport.close()
    })
  })

  describe('IDE extended CONNECT (RFC 8441 WebSocket)', () => {
    it('tunnels bytes bidirectionally to local WebSocket upstream', async () => {
      // 建立假本地 WebSocket Echo 服务（模拟 code-server 的 websocket 终端/通信）
      const localHttp = http.createServer()
      const localWss = new WebSocketServer({ noServer: true })

      localHttp.on('upgrade', (req, socket, head) => {
        expect(req.headers['cookie']).toBe('kk-session=123')
        localWss.handleUpgrade(req, socket, head, (ws) => {
          ws.on('message', (data) => {
            ws.send(`echo:${data.toString()}`)
          })
        })
      })

      const wsPort = await new Promise<number>((resolve) => {
        localHttp.listen(0, '127.0.0.1', () => {
          resolve((localHttp.address() as net.AddressInfo).port)
        })
      })

      peer = new FakeStudioPeer()
      await peer.start()

      let released = false
      const mockIde: IdeService = {
        resolveView: async (_viewId, _epoch, req) => {
          return {
            host: '127.0.0.1',
            port: wsPort,
            path: `/__kk/ide/view/11111111-1111-4111-8111-111111111111/1${req.path}`,
            headers: [],
            cookie: 'kk-session=123',
            signal: new AbortController().signal,
            release: () => {
              released = true
            },
          }
        },
      } as unknown as IdeService

      const transport = createDaemonTransport({
        gatewayUri: peer.gatewayUri,
        config,
        ide: mockIde,
        daemonInstanceId: randomUUID(),
        platformKey: 'linux-amd64',
      })

      const connectPromise = transport.connect({
        onEnvelope: () => {},
        onDisconnected: () => {},
        onGoaway: () => {},
        onUpgradeFailed: () => {},
      })

      await peer.openControlStream()
      await connectPromise

      const connectStream = await peer.openConnectStream(
        '/ide/11111111-1111-4111-8111-111111111111/1/pty',
      )

      const responsePromise = new Promise<{ status: number }>((resolve) => {
        connectStream.on('response', (headers) => {
          resolve({ status: Number(headers[':status']) })
        })
      })

      const res = await responsePromise
      expect(res.status).toBe(200)

      // 发送并接收符合 RFC 6455 规范的 WebSocket 文本帧
      const encodeWsClientFrame = (payloadStr: string): Buffer => {
        const payload = Buffer.from(payloadStr, 'utf8')
        const mask = Buffer.from([0x12, 0x34, 0x56, 0x78])
        const masked = Buffer.alloc(payload.length)
        for (let i = 0; i < payload.length; i++) {
          masked[i] = payload[i] ^ mask[i % 4]
        }
        return Buffer.concat([Buffer.from([0x81, 0x80 | payload.length]), mask, masked])
      }

      const decodeWsServerFrame = (buf: Buffer): string => {
        const len = buf[1] & 0x7f
        return buf.subarray(2, 2 + len).toString('utf8')
      }

      const echoPromise = new Promise<string>((resolve) => {
        connectStream.on('data', (chunk: Buffer) => {
          resolve(decodeWsServerFrame(chunk))
        })
      })

      connectStream.write(encodeWsClientFrame('test-byte-payload'))
      const echoed = await echoPromise
      expect(echoed).toBe('echo:test-byte-payload')

      // 测试半关闭：客户端 end，检查不崩溃
      connectStream.end()
      if (!connectStream.writableFinished) {
        await new Promise((r) => connectStream.once('finish', r))
      }

      await new Promise<void>((r) => localHttp.close(() => r()))
      await transport.close()
      await vi.waitFor(() => {
        expect(released).toBe(true)
      })
    })
  })

  describe('Runtime end-to-end integration with Unified Transport', () => {
    it('performs SETTINGS -> READY, handles INVOKE and RECOVER_NOT_FOUND', async () => {
      peer = new FakeStudioPeer()
      await peer.start()
      const runtimeConfig: DaemonConfig = {
        ...config,
        studioUrl: peer.origin,
        gatewayUri: peer.gatewayUri,
      }

      const transport = createDaemonTransport({
        gatewayUri: peer.gatewayUri,
        config: runtimeConfig,
        ide: {} as unknown as IdeService,
        daemonInstanceId: randomUUID(),
        platformKey: 'linux-amd64',
      })

      const runtime = new Runtime({
        config: runtimeConfig,
        transport,
        capabilities: {
          'test.echo': async (call) => ({
            callId: call.invocationId,
            error: false,
            details: {},
            contents: [{ type: 'text', text: `result:${call.arguments.msg}` }],
          }),
        },
        processes: {} as any,
      })

      runtime.start()

      // Studio 打开控制流
      await peer.openControlStream()

      // Studio 发送 SETTINGS
      peer.sendEnvelope(
        create(EnvelopeSchema, {
              messageType: MessageType.SETTINGS,
          environmentId: '11111111-1111-4111-8111-111111111111',
          payload: {
            case: 'settings',
            value: create(SettingsSchema, {
              resourceBudgets: create(ResourceBudgetsSchema, {
                maxResourceBytes: 1048576n,
                temporaryResourceTtlSeconds: 3600n,
                temporaryResourceCleanupIntervalSeconds: 60n,
              }),
            }),
          },
        }),
      )
      peer.sendEnvelope(
        create(EnvelopeSchema, {
          messageType: MessageType.ENVIRONMENT_CONFIG,
          environmentId: '11111111-1111-4111-8111-111111111111',
          payload: {
            case: 'environmentConfig',
            value: create(EnvironmentConfigSchema, {
              version: 0n,
              config: create(DaemonConfigurationSchema, { studioUrl: peer.origin }),
            }),
          },
        }),
      )

      // 接收 Daemon 的 READY
      const ready = await peer.nextEnvelope()
      expect(ready.messageType).toBe(MessageType.READY)
      expect(runtime.getState()).toBe('READY')

      // Studio 发送 INVOKE
      peer.sendEnvelope(
        create(EnvelopeSchema, {
              messageType: MessageType.INVOKE,
          environmentId: '11111111-1111-4111-8111-111111111111',
          invocationId: 'invoke-100',
          payload: {
            case: 'invoke',
            value: create(InvokeSchema, {
              capabilityId: 'test.echo',
              argumentsJson: JSON.stringify({ msg: 'ping' }),
              timeoutMillis: 5000n,
            }),
          },
        }),
      )

      // 接收 STARTED
      const started = await peer.nextEnvelope()
      expect(started.messageType).toBe(MessageType.STARTED)
      expect(started.invocationId).toBe('invoke-100')

      // 接收 COMPLETED
      const completed = await peer.nextEnvelope()
      expect(completed.messageType).toBe(MessageType.COMPLETED)
      expect(completed.invocationId).toBe('invoke-100')

      // 测试 RECOVER 未知调用 -> 回复 RECOVER_NOT_FOUND
      peer.sendEnvelope(
        create(EnvelopeSchema, {
              messageType: MessageType.RECOVER,
          environmentId: '11111111-1111-4111-8111-111111111111',
          invocationId: 'unknown-invoke-999',
          payload: {
            case: 'recover',
            value: create(RecoverSchema, {}),
          },
        }),
      )

      const recoverNotFound = await peer.nextEnvelope()
      expect(recoverNotFound.messageType).toBe(MessageType.RECOVER_NOT_FOUND)
      expect(recoverNotFound.invocationId).toBe('unknown-invoke-999')

      runtime.close()
      await runtime.awaitCompletion()
    })

    it('cleans up all resources and leaves no active handles after upgrade failure (401)', async () => {
      peer = new FakeStudioPeer()
      await peer.start()
      peer.rejectAndClose(401)

      let upgradeFailedCode: number | null = null
      const transport = createDaemonTransport({
        gatewayUri: peer.gatewayUri,
        config,
        ide: {} as unknown as IdeService,
        daemonInstanceId: randomUUID(),
        platformKey: 'linux-amd64',
      })

      const failedPromise = new Promise<number>((resolve) => {
        transport
          .connect({
            onEnvelope: () => {},
            onDisconnected: () => {},
            onGoaway: () => {},
            onUpgradeFailed: (statusCode) => {
              upgradeFailedCode = statusCode
              resolve(statusCode)
            },
          })
          .catch(() => {})
      })

      const code = await failedPromise
      expect(code).toBe(401)
      expect(upgradeFailedCode).toBe(401)

      await transport.close()
    })

    it('handles 426 UpgradeRequired with empty body', async () => {
      peer = new FakeStudioPeer({
        onHandshake: (_req, res) => {
          res.writeHead(426, { 'content-type': 'application/x-protobuf' })
          res.end(Buffer.alloc(0))
          return true
        },
      })
      await peer.start()

      let failedCode = 0
      let upgradeObj: unknown = 'init'
      const transport = createDaemonTransport({
        gatewayUri: peer.gatewayUri,
        config,
        ide: {} as unknown as IdeService,
        daemonInstanceId: randomUUID(),
        platformKey: 'linux-amd64',
      })

      await expect(
        transport.connect({
          onEnvelope: () => {},
          onDisconnected: () => {},
          onGoaway: () => {},
          onUpgradeFailed: (code, upgrade) => {
            failedCode = code
            upgradeObj = upgrade
          },
        }),
      ).rejects.toThrow(/handshake failed with 426/)

      expect(failedCode).toBe(426)
      expect(upgradeObj).toBeUndefined()
      await transport.close()
    })

    it('handles 503 service unavailable during handshake', async () => {
      peer = new FakeStudioPeer({
        onHandshake: (_req, res) => {
          res.writeHead(503, { 'content-type': 'text/plain' })
          res.end('Unavailable')
          return true
        },
      })
      await peer.start()

      let failedCode = 0
      const transport = createDaemonTransport({
        gatewayUri: peer.gatewayUri,
        config,
        ide: {} as unknown as IdeService,
        daemonInstanceId: randomUUID(),
        platformKey: 'linux-amd64',
      })

      await expect(
        transport.connect({
          onEnvelope: () => {},
          onDisconnected: () => {},
          onGoaway: () => {},
          onUpgradeFailed: (code) => {
            failedCode = code
          },
        }),
      ).rejects.toThrow(/handshake failed with status 503/)

      expect(failedCode).toBe(503)
      await transport.close()
    })

    it('fails connect when control stream is not opened within timeout', async () => {
      peer = new FakeStudioPeer()
      await peer.start()

      let disconnected = false
      const transport = createDaemonTransport({
        gatewayUri: peer.gatewayUri,
        config,
        ide: {} as unknown as IdeService,
        daemonInstanceId: randomUUID(),
        platformKey: 'linux-amd64',
        controlStreamTimeoutMillis: 100,
      })

      await expect(
        transport.connect({
          onEnvelope: () => {},
          onDisconnected: () => {
            disconnected = true
          },
          onGoaway: () => {},
          onUpgradeFailed: () => {},
        }),
      ).rejects.toThrow(/connection failed before control stream established/)

      expect(disconnected).toBe(true)
      await transport.close()
    })

    it('closes connection when receiving text WebSocket frame', async () => {
      peer = new FakeStudioPeer()
      await peer.start()

      let disconnected = false
      const transport = createDaemonTransport({
        gatewayUri: peer.gatewayUri,
        config,
        ide: {} as unknown as IdeService,
        daemonInstanceId: randomUUID(),
        platformKey: 'linux-amd64',
      })

      const connectPromise = transport.connect({
        onEnvelope: () => {},
        onDisconnected: () => {
          disconnected = true
        },
        onGoaway: () => {},
        onUpgradeFailed: () => {},
      })

      await peer.openControlStream()
      await connectPromise

      // 对端 WebSocket 发送文本帧
      const activeWs = peer.getActiveWs()
      expect(activeWs).not.toBeNull()
      activeWs?.send('illegal text message')

      await vi.waitFor(() => {
        expect(disconnected).toBe(true)
      })
      await transport.close()
    })

    it('responds 404 to unknown HTTP/2 stream path', async () => {
      peer = new FakeStudioPeer()
      await peer.start()

      const transport = createDaemonTransport({
        gatewayUri: peer.gatewayUri,
        config,
        ide: {} as unknown as IdeService,
        daemonInstanceId: randomUUID(),
        platformKey: 'linux-amd64',
      })

      const connectPromise = transport.connect({
        onEnvelope: () => {},
        onDisconnected: () => {},
        onGoaway: () => {},
        onUpgradeFailed: () => {},
      })

      await peer.openControlStream()
      await connectPromise

      const res = await peer.requestHttp('/unknown/non-ide-path', 'GET')
      expect(res.status).toBe(404)

      await transport.close()
    })

    it('ignores GOAWAY with error code other than NO_ERROR', async () => {
      peer = new FakeStudioPeer()
      await peer.start()

      let goawayFired = false
      const transport = createDaemonTransport({
        gatewayUri: peer.gatewayUri,
        config,
        ide: {} as unknown as IdeService,
        daemonInstanceId: randomUUID(),
        platformKey: 'linux-amd64',
      })

      const connectPromise = transport.connect({
        onEnvelope: () => {},
        onDisconnected: () => {},
        onGoaway: () => {
          goawayFired = true
        },
        onUpgradeFailed: () => {},
      })

      await peer.openControlStream()
      await connectPromise

      peer.sendGoaway(h2constants.NGHTTP2_PROTOCOL_ERROR)
      // 反向断言：非 NO_ERROR 错误码的 GOAWAY 不触发 onGoaway 回调；短暂等待确认回调未被调用
      await new Promise((r) => setTimeout(r, 100))
      expect(goawayFired).toBe(false)

      await transport.close()
    })

    it('handles control stream protocol violation (corrupt frame length or invalid protobuf)', async () => {
      peer = new FakeStudioPeer()
      await peer.start()

      let disconnected = false
      const transport = createDaemonTransport({
        gatewayUri: peer.gatewayUri,
        config,
        ide: {} as unknown as IdeService,
        daemonInstanceId: randomUUID(),
        platformKey: 'linux-amd64',
      })

      const connectPromise = transport.connect({
        onEnvelope: () => {},
        onDisconnected: () => {
          disconnected = true
        },
        onGoaway: () => {},
        onUpgradeFailed: () => {},
      })

      await peer.openControlStream()
      await connectPromise

      const controlStream = peer.getControlStream()
      expect(controlStream).not.toBeNull()
      // 发送 4 字节 length = 4，加上 4 字节的乱码 payload，触发 decodeFrame 异常
      controlStream?.write(Buffer.from([0x00, 0x00, 0x00, 0x04, 0xff, 0xff, 0xff, 0xff]))

      await vi.waitFor(() => {
        expect(disconnected).toBe(true)
      })
      await transport.close()
    })

    it('ControlConnection handles closed state and close() correctly', async () => {
      peer = new FakeStudioPeer()
      await peer.start()

      const transport = createDaemonTransport({
        gatewayUri: peer.gatewayUri,
        config,
        ide: {} as unknown as IdeService,
        daemonInstanceId: randomUUID(),
        platformKey: 'linux-amd64',
      })

      const connectPromise = transport.connect({
        onEnvelope: () => {},
        onDisconnected: () => {},
        onGoaway: () => {},
        onUpgradeFailed: () => {},
      })

      await peer.openControlStream()
      const conn = await connectPromise
      expect(conn.isOpen()).toBe(true)

      const dummyEnvelope = create(EnvelopeSchema, {
        messageType: MessageType.READY,
        environmentId: '11111111-1111-4111-8111-111111111111',
        payload: { case: 'ready', value: create(ReadySchema, {}) },
      })

      // 测试 controlStream.write 报错时 send 返回 false
      const rawStream = (conn as any).controlStream
      if (rawStream) {
        const origWrite = rawStream.write
        rawStream.write = () => {
          throw new Error('stream write error')
        }
        expect(conn.send(dummyEnvelope)).toBe(false)
        rawStream.write = origWrite
      }

      // 关闭连接
      conn.close()
      await vi.waitFor(() => {
        expect(conn.isOpen()).toBe(false)
      })

      // 已关闭连接调用 send 返回 false
      expect(conn.send(dummyEnvelope)).toBe(false)

      await transport.close()

      // 已关闭的 transport 再次 connect 抛出异常
      await expect(
        transport.connect({
          onEnvelope: () => {},
          onDisconnected: () => {},
          onGoaway: () => {},
          onUpgradeFailed: () => {},
        }),
      ).rejects.toThrow('transport is closed')
    })

    it('rejects connect if registration token cannot be read', async () => {
      const badConfig = {
        ...config,
        tokenPath: '/non/existent/path/for/token/never/exists',
      }
      const transport = createDaemonTransport({
        gatewayUri: 'ws://127.0.0.1:1234',
        config: badConfig as any,
        ide: {} as unknown as IdeService,
        daemonInstanceId: randomUUID(),
        platformKey: 'linux-amd64',
      })

      await expect(
        transport.connect({
          onEnvelope: () => {},
          onDisconnected: () => {},
          onGoaway: () => {},
          onUpgradeFailed: () => {},
        }),
      ).rejects.toThrow(/cannot read registration token/)
    })
  })
})
