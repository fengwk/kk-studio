import { afterEach, describe, expect, it } from 'vitest'
import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { WebSocketServer, WebSocket } from 'ws'
import { openStreamExchange } from '../../src/transport/stream.js'
import {
  INITIAL_CREDIT,
  MAX_PAYLOAD,
  decodeBinaryFrame,
  decodeTextFrame,
  encodeAck,
  encodeHttpBody,
  encodeHttpEnd,
  encodeTextFrame,
  encodeWsFrame,
  type TextFrame,
} from '../../src/ide/wire.js'

interface Harness {
  url: string
  close(): Promise<void>
}

const cleanups: (() => Promise<void> | void)[] = []

afterEach(async () => {
  for (const cleanup of cleanups.splice(0).reverse()) {
    await cleanup()
  }
})

function delay(millis: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, millis))
}

async function waitFor(predicate: () => boolean, timeoutMillis = 8000): Promise<void> {
  const deadline = Date.now() + timeoutMillis
  while (!predicate()) {
    if (Date.now() > deadline) {
      throw new Error('condition not met before timeout')
    }
    await delay(2)
  }
}

async function listen(server: Server): Promise<number> {
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  return (server.address() as AddressInfo).port
}

function closeServer(server: Server): Promise<void> {
  server.closeAllConnections?.()
  return new Promise<void>((resolve) => server.close(() => resolve()))
}

async function startHttpUpstream(handler: Parameters<typeof createServer>[0]): Promise<Harness> {
  const server = createServer(handler)
  const port = await listen(server)
  const harness = { url: `http://127.0.0.1:${port}`, close: () => closeServer(server) }
  cleanups.push(harness.close)
  return harness
}

async function startWsUpstream(onConnection: (socket: WebSocket) => void): Promise<Harness> {
  const server = createServer()
  const port = await listen(server)
  const wss = new WebSocketServer({ server })
  wss.on('connection', onConnection)
  const harness = {
    url: `ws://127.0.0.1:${port}`,
    close: () =>
      new Promise<void>((resolve) => {
        for (const client of wss.clients) {
          client.terminate()
        }
        wss.close(() => closeServer(server).then(resolve))
      }),
  }
  cleanups.push(harness.close)
  return harness
}

interface Gateway {
  socket: WebSocket
  sendText(frame: TextFrame): void
  sendBody(payload: Buffer): void
  sendEnd(): void
  sendAck(bytes: number): void
  sendWs(kind: 'text' | 'binary', payload: Buffer, last: boolean): void
  sendRaw(data: Buffer): void
}

async function startGateway(onConnection: (gateway: Gateway) => void): Promise<Harness> {
  const server = createServer()
  const port = await listen(server)
  const wss = new WebSocketServer({ server })
  wss.on('connection', (socket) => {
    const gateway: Gateway = {
      socket,
      sendText: (frame) => socket.send(encodeTextFrame(frame)),
      sendBody: (payload) => socket.send(encodeHttpBody(payload)),
      sendEnd: () => socket.send(encodeHttpEnd()),
      sendAck: (bytes) => socket.send(encodeAck(bytes)),
      sendWs: (kind, payload, last) => socket.send(encodeWsFrame(kind, payload, last)),
      sendRaw: (data) => socket.send(data),
    }
    onConnection(gateway)
  })
  const harness = {
    url: `ws://127.0.0.1:${port}/api/harness/environment-stream/v1/ticket`,
    close: () =>
      new Promise<void>((resolve) => {
        for (const client of wss.clients) {
          client.terminate()
        }
        wss.close(() => closeServer(server).then(resolve))
      }),
  }
  cleanups.push(harness.close)
  return harness
}

function upstreamOf(url: string): { host: string; port: number } {
  const parsed = new URL(url)
  return { host: parsed.hostname, port: Number(parsed.port) }
}

function newSignal(): AbortController {
  const controller = new AbortController()
  cleanups.push(() => controller.abort())
  return controller
}

function chunkBuffer(buffer: Buffer, size = MAX_PAYLOAD): Buffer[] {
  const chunks: Buffer[] = []
  for (let offset = 0; offset < buffer.length; offset += size) {
    chunks.push(buffer.subarray(offset, Math.min(offset + size, buffer.length)))
  }
  return chunks
}

/** Test-side gateway sender that honours the daemon's credit window. */
class GatewayPump {
  private outstanding = 0
  private index = 0
  private completed = false

  constructor(
    private readonly gateway: Gateway,
    private readonly chunks: Buffer[],
    private readonly encode: (payload: Buffer) => Buffer,
    private readonly onComplete: () => void,
  ) {}

  start(): void {
    this.flush()
  }

  ack(bytes: number): void {
    this.outstanding -= bytes
    this.flush()
  }

  private flush(): void {
    while (this.index < this.chunks.length) {
      const chunk = this.chunks[this.index] as Buffer
      if (this.outstanding + chunk.length > INITIAL_CREDIT) {
        return
      }
      this.index += 1
      this.gateway.socket.send(this.encode(chunk))
      this.outstanding += chunk.length
    }
    if (!this.completed) {
      this.completed = true
      this.onComplete()
    }
  }
}

const ACK_THRESHOLD = 64 * 1024

describe('HTTP exchanges over the reverse stream', () => {
  it('streams a >=16MiB response with bounded credit and preserves headers', async () => {
    const size = 16 * 1024 * 1024 + 12_345
    const upstream = await startHttpUpstream((_req, res) => {
      res.writeHead(200, {
        'content-type': 'application/octet-stream',
        'content-length': String(size),
        'x-multi': 'a',
      })
      const chunk = Buffer.alloc(64 * 1024, 0x5a)
      let written = 0
      const write = (): void => {
        while (written < size) {
          const end = Math.min(written + chunk.length, size)
          const slice = chunk.subarray(0, end - written)
          written = end
          if (!res.write(slice)) {
            res.once('drain', write)
            return
          }
        }
        res.end()
      }
      write()
    })

    let status = 0
    let headerPairs: [string, string][] = []
    let total = 0
    let unacked = 0
    let peakUnacked = 0
    let ended = false

    const gateway = await startGateway((conn) => {
      conn.socket.on('message', (data, isBinary) => {
        if (!isBinary) {
          const frame = decodeTextFrame(data.toString('utf8'))
          if (frame.type === 'HTTP_RESPONSE') {
            status = frame.status
            headerPairs = frame.headers
          }
          return
        }
        const frame = decodeBinaryFrame(data as Buffer)
        if (frame.kind === 'http-body') {
          total += frame.payload.length
          unacked += frame.payload.length
          peakUnacked = Math.max(peakUnacked, unacked)
          if (unacked >= ACK_THRESHOLD) {
            conn.sendAck(unacked)
            unacked = 0
          }
        } else if (frame.kind === 'http-end') {
          if (unacked > 0) {
            conn.sendAck(unacked)
            unacked = 0
          }
          ended = true
        }
      })
    })

    await openStreamExchange({
      streamUrl: gateway.url,
      request: { mode: 'HTTP', method: 'GET', path: '/big', headers: [] },
      upstream: upstreamOf(upstream.url),
      signal: newSignal().signal,
    })

    await waitFor(() => ended)
    expect(status).toBe(200)
    expect(headerPairs).toContainEqual(['content-length', String(size)])
    expect(headerPairs).toContainEqual(['content-type', 'application/octet-stream'])
    expect(total).toBe(size)
    expect(peakUnacked).toBeLessThanOrEqual(INITIAL_CREDIT)
  })

  it('streams the POST request body upstream and echoes the response', async () => {
    const body = Buffer.alloc(3 * 1024 * 1024 + 7, 0x21)
    let receivedBody = Buffer.alloc(0)
    const upstream = await startHttpUpstream((req, res) => {
      const chunks: Buffer[] = []
      req.on('data', (chunk) => chunks.push(chunk as Buffer))
      req.on('end', () => {
        receivedBody = Buffer.concat(chunks)
        res.writeHead(200, { 'content-type': 'text/plain' })
        res.end(receivedBody)
      })
    })

    let responseBody = Buffer.alloc(0)
    let responseDone = false
    let responseHeaders = false
    const gateway = await startGateway((conn) => {
      // Request body is chunked and credit controlled; END follows the last chunk.
      const pump = new GatewayPump(conn, chunkBuffer(body), encodeHttpBody, () => conn.sendEnd())
      pump.start()
      conn.socket.on('message', (data, isBinary) => {
        if (!isBinary) {
          if (decodeTextFrame(data.toString('utf8')).type === 'HTTP_RESPONSE') {
            responseHeaders = true
          }
          return
        }
        const frame = decodeBinaryFrame(data as Buffer)
        if (frame.kind === 'ack') {
          pump.ack(frame.bytes)
        } else if (frame.kind === 'http-body') {
          responseBody = Buffer.concat([responseBody, frame.payload])
          conn.sendAck(frame.payload.length)
        } else if (frame.kind === 'http-end') {
          responseDone = true
        }
      })
    })

    await openStreamExchange({
      streamUrl: gateway.url,
      request: { mode: 'HTTP', method: 'POST', path: '/echo', headers: [['content-type', 'application/octet-stream']] },
      upstream: upstreamOf(upstream.url),
      signal: newSignal().signal,
    })

    await waitFor(() => responseDone)
    expect(responseHeaders).toBe(true)
    expect(receivedBody.equals(body)).toBe(true)
    expect(responseBody.equals(body)).toBe(true)
  })

  it('injects only the daemon cookie and strips browser auth headers', async () => {
    let seenCookie: string | undefined
    let seenAuthorization: string | undefined
    const upstream = await startHttpUpstream((req, res) => {
      seenCookie = req.headers.cookie
      seenAuthorization = req.headers.authorization
      res.writeHead(200)
      res.end('ok')
    })

    let ended = false
    const gateway = await startGateway((conn) => {
      conn.socket.on('message', (data, isBinary) => {
        if (isBinary && decodeBinaryFrame(data as Buffer).kind === 'http-end') {
          ended = true
        }
      })
    })

    await openStreamExchange({
      streamUrl: gateway.url,
      request: {
        mode: 'HTTP',
        method: 'GET',
        path: '/',
        headers: [
          ['cookie', 'browser-session=leak'],
          ['authorization', 'Bearer secret'],
          ['origin', 'https://abc.ide.example.com'],
          ['accept', 'text/html'],
        ],
      },
      upstream: { ...upstreamOf(upstream.url), cookie: 'code-server-session=private' },
      signal: newSignal().signal,
    })

    await waitFor(() => ended)
    expect(seenCookie).toBe('code-server-session=private')
    expect(seenAuthorization).toBeUndefined()
  })

  it('fails closed when the peer acknowledges more than it received', async () => {
    // The upstream never answers, keeping the exchange open until the bad ACK arrives.
    const upstream = await startHttpUpstream(() => undefined)
    const gateway = await startGateway((conn) => conn.sendAck(1_000_000))

    await expect(
      openStreamExchange({
        streamUrl: gateway.url,
        request: { mode: 'HTTP', method: 'GET', path: '/', headers: [] },
        upstream: upstreamOf(upstream.url),
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/ack exceeds unacknowledged/)
  })

  it('rejects a malformed frame and aborts on signal', async () => {
    const upstream = await startHttpUpstream(() => undefined)

    const bad = await startGateway((conn) => conn.sendRaw(Buffer.from([77])))
    await expect(
      openStreamExchange({
        streamUrl: bad.url,
        request: { mode: 'HTTP', method: 'GET', path: '/', headers: [] },
        upstream: upstreamOf(upstream.url),
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/unknown binary frame type/)

    const slow = await startGateway(() => undefined)
    const controller = new AbortController()
    const pending = openStreamExchange({
      streamUrl: slow.url,
      request: { mode: 'HTTP', method: 'GET', path: '/', headers: [] },
      upstream: upstreamOf(upstream.url),
      signal: controller.signal,
    })
    await delay(20)
    controller.abort()
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  })
})

describe('WebSocket exchanges over the reverse stream', () => {
  it('preserves text/binary type, order and reassembles UTF-8 fragments', async () => {
    const upstream = await startWsUpstream((socket) => {
      socket.on('message', (data, isBinary) => socket.send(data, { binary: isBinary }))
    })

    const received: { kind: 'text' | 'binary'; payload: Buffer }[] = []
    let ready = false
    let gatewaySocket: WebSocket | undefined
    const gateway = await startGateway((conn) => {
      gatewaySocket = conn.socket
      const hello = Buffer.from('héllo-世界', 'utf8')
      conn.sendWs('text', hello.subarray(0, 3), false)
      conn.sendWs('text', hello.subarray(3), true)
      conn.sendWs('binary', Buffer.from([1, 2, 3]), true)
      conn.socket.on('message', (data, isBinary) => {
        if (!isBinary) {
          if (decodeTextFrame(data.toString('utf8')).type === 'WS_READY') {
            ready = true
          }
          return
        }
        const frame = decodeBinaryFrame(data as Buffer)
        if (frame.kind === 'ws-text' || frame.kind === 'ws-binary') {
          received.push({ kind: frame.kind === 'ws-text' ? 'text' : 'binary', payload: frame.payload })
          conn.sendAck(frame.payload.length)
        }
      })
    })

    const exchange = openStreamExchange({
      streamUrl: gateway.url,
      request: { mode: 'WS', method: 'GET', path: '/ws', headers: [] },
      upstream: upstreamOf(upstream.url),
      signal: newSignal().signal,
    })

    await waitFor(() => ready && received.length >= 2)
    expect(received[0]?.kind).toBe('text')
    expect(received[0]?.payload.toString('utf8')).toBe('héllo-世界')
    expect(received[1]?.kind).toBe('binary')
    expect(received[1]?.payload).toEqual(Buffer.from([1, 2, 3]))

    gatewaySocket?.close()
    await exchange
  })

  it('relays a large binary message under credit control', async () => {
    const payload = Buffer.alloc(8 * 1024 * 1024, 0x33)
    const upstream = await startWsUpstream((socket) => {
      socket.send(payload)
    })

    let total = 0
    let peakUnacked = 0
    let unacked = 0
    let completed = false
    let gatewaySocket: WebSocket | undefined
    const gateway = await startGateway((conn) => {
      gatewaySocket = conn.socket
      conn.socket.on('message', (data, isBinary) => {
        if (!isBinary) {
          return
        }
        const frame = decodeBinaryFrame(data as Buffer)
        if (frame.kind === 'ws-binary' || frame.kind === 'ws-text') {
          total += frame.payload.length
          unacked += frame.payload.length
          peakUnacked = Math.max(peakUnacked, unacked)
          if (unacked >= ACK_THRESHOLD) {
            conn.sendAck(unacked)
            unacked = 0
          }
          if (total === payload.length) {
            completed = true
          }
        }
      })
    })

    const exchange = openStreamExchange({
      streamUrl: gateway.url,
      request: { mode: 'WS', method: 'GET', path: '/ws', headers: [] },
      upstream: upstreamOf(upstream.url),
      signal: newSignal().signal,
    })

    await waitFor(() => completed)
    expect(total).toBe(payload.length)
    expect(peakUnacked).toBeLessThanOrEqual(INITIAL_CREDIT + MAX_PAYLOAD)

    gatewaySocket?.close()
    await exchange
  })

  it('forwards the upstream WebSocket close code to the stream', async () => {
    const upstream = await startWsUpstream((socket) => {
      socket.close(4001, 'bye')
    })

    let closeCode: number | undefined
    const gateway = await startGateway((conn) => {
      conn.socket.on('close', (code) => {
        closeCode = code
      })
    })

    await openStreamExchange({
      streamUrl: gateway.url,
      request: { mode: 'WS', method: 'GET', path: '/ws', headers: [] },
      upstream: upstreamOf(upstream.url),
      signal: newSignal().signal,
    })

    await waitFor(() => closeCode !== undefined)
    expect(closeCode).toBe(4001)
  })

  it('signals a fixed ERROR frame when the upstream websocket is unavailable', async () => {
    const probe = createServer()
    const port = await listen(probe)
    await closeServer(probe)

    let errorFrame: TextFrame | undefined
    const gateway = await startGateway((conn) => {
      conn.socket.on('message', (data, isBinary) => {
        if (!isBinary) {
          const frame = decodeTextFrame(data.toString('utf8'))
          if (frame.type === 'ERROR') {
            errorFrame = frame
          }
        }
      })
    })

    await expect(
      openStreamExchange({
        streamUrl: gateway.url,
        request: { mode: 'WS', method: 'GET', path: '/ws', headers: [] },
        upstream: { host: '127.0.0.1', port },
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/stream socket error|upstream websocket failed/)
    await waitFor(() => errorFrame !== undefined)
    expect(errorFrame).toEqual({ type: 'ERROR', message: 'ide websocket unavailable' })
  })

  it('fails closed on an unexpected control frame during an HTTP exchange', async () => {
    const upstream = await startHttpUpstream(() => undefined)
    const gateway = await startGateway((conn) => conn.sendText({ type: 'WS_READY' }))

    await expect(
      openStreamExchange({
        streamUrl: gateway.url,
        request: { mode: 'HTTP', method: 'GET', path: '/', headers: [] },
        upstream: upstreamOf(upstream.url),
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/unexpected text frame WS_READY/)
  })

  it('accounts for body frames on a bodyless request', async () => {
    const upstream = await startHttpUpstream((_req, res) => {
      res.writeHead(200, { 'content-type': 'text/plain' })
      res.end('head')
    })
    let ended = false
    const gateway = await startGateway((conn) => {
      conn.sendBody(Buffer.from('ignored'))
      conn.sendEnd()
      conn.socket.on('message', (data, isBinary) => {
        if (isBinary && decodeBinaryFrame(data as Buffer).kind === 'http-end') {
          ended = true
        }
      })
    })

    await openStreamExchange({
      streamUrl: gateway.url,
      request: { mode: 'HTTP', method: 'HEAD', path: '/h', headers: [] },
      upstream: upstreamOf(upstream.url),
      signal: newSignal().signal,
    })
    await waitFor(() => ended)
    expect(ended).toBe(true)
  })

  it('rejects immediately when the signal is already aborted', async () => {
    const controller = new AbortController()
    controller.abort()
    await expect(
      openStreamExchange({
        streamUrl: 'ws://127.0.0.1:1/api/harness/environment-stream/v1/t',
        request: { mode: 'HTTP', method: 'GET', path: '/', headers: [] },
        upstream: { host: '127.0.0.1', port: 1 },
        signal: controller.signal,
      }),
    ).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('reports an upstream connection failure', async () => {
    const probe = createServer()
    const port = await listen(probe)
    await closeServer(probe)
    const gateway = await startGateway(() => undefined)

    await expect(
      openStreamExchange({
        streamUrl: gateway.url,
        request: { mode: 'HTTP', method: 'GET', path: '/', headers: [] },
        upstream: { host: '127.0.0.1', port },
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/upstream request failed/)
  })

  it('merges duplicate request headers into a single upstream header', async () => {
    let accept: string | undefined
    const upstream = await startHttpUpstream((req, res) => {
      accept = req.headers.accept
      res.writeHead(200)
      res.end('ok')
    })
    let ended = false
    const gateway = await startGateway((conn) => {
      conn.socket.on('message', (data, isBinary) => {
        if (isBinary && decodeBinaryFrame(data as Buffer).kind === 'http-end') {
          ended = true
        }
      })
    })

    await openStreamExchange({
      streamUrl: gateway.url,
      request: {
        mode: 'HTTP',
        method: 'GET',
        path: '/',
        headers: [
          ['accept', 'text/html'],
          ['accept', 'application/json'],
        ],
      },
      upstream: upstreamOf(upstream.url),
      signal: newSignal().signal,
    })
    await waitFor(() => ended)
    expect(accept).toBe('text/html, application/json')
  })

  it('closes the upstream websocket when the stream closes', async () => {
    let upstreamClosed = false
    const upstream = await startWsUpstream((socket) => {
      socket.on('close', () => {
        upstreamClosed = true
      })
    })
    let gatewaySocket: WebSocket | undefined
    const gateway = await startGateway((conn) => {
      gatewaySocket = conn.socket
    })

    const exchange = openStreamExchange({
      streamUrl: gateway.url,
      request: { mode: 'WS', method: 'GET', path: '/ws', headers: [] },
      upstream: upstreamOf(upstream.url),
      signal: newSignal().signal,
    })
    await waitFor(() => gatewaySocket !== undefined)
    await delay(30)
    gatewaySocket?.close()
    await exchange
    await waitFor(() => upstreamClosed)
    expect(upstreamClosed).toBe(true)
  })
})
