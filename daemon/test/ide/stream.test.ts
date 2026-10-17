import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ClientRequest,
  createServer,
  type IncomingHttpHeaders,
  type IncomingMessage,
  type Server,
  type ServerResponse,
} from 'node:http'
import { createHash } from 'node:crypto'
import type { AddressInfo, Socket } from 'node:net'
import { WebSocketServer, WebSocket } from 'ws'
import { getOutboundNetwork } from '../../src/network.js'
import { openStreamExchange } from '../../src/transport/stream.js'
import { UpstreamWebSocket } from '../../src/transport/upstream-websocket.js'
import {
  INITIAL_CREDIT,
  MAX_PAYLOAD,
  CreditWindow,
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

interface ProgressBudget {
  /** Maximum time without any observable progress before the wait reports a stall. */
  idleMillis: number
  /** Hard upper bound for the whole wait, regardless of progress. */
  totalMillis: number
}

const DEFAULT_PROGRESS_BUDGET: ProgressBudget = { idleMillis: 8000, totalMillis: 60000 }

/**
 * Waits for observable progress rather than a fixed throughput deadline: every increase of
 * `progress()` refreshes the idle deadline, while the total budget still bounds the whole wait.
 * `diagnostics` must name the transfer counters so a timeout states whether bytes were still
 * moving (slow) or had stopped entirely (deadlock).
 */
async function awaitProgress(
  progress: () => number,
  complete: () => boolean,
  diagnostics: () => string,
  budget: ProgressBudget = DEFAULT_PROGRESS_BUDGET,
): Promise<void> {
  const startedAt = Date.now()
  let lastProgress = progress()
  let lastProgressAt = startedAt
  for (;;) {
    if (complete()) {
      return
    }
    const now = Date.now()
    const current = progress()
    if (current !== lastProgress) {
      lastProgress = current
      lastProgressAt = now
    }
    const idle = now - lastProgressAt
    const elapsed = now - startedAt
    if (idle > budget.idleMillis) {
      throw new Error(`no progress for ${idle}ms (stalled): ${diagnostics()}`)
    }
    if (elapsed > budget.totalMillis) {
      throw new Error(
        `still progressing but exceeded ${budget.totalMillis}ms total budget after ${elapsed}ms: ${diagnostics()}`,
      )
    }
    await delay(2)
  }
}

/**
 * Cleanup for an exchange whose body failed: abort the signal first, because an exchange stuck in
 * its gateway handshake only settles once the signal is aborted; then close an assigned gateway
 * and wait for the already-observed settlement.
 */
async function abortAndSettle(
  controller: AbortController,
  closeGateway: () => void,
  settled: Promise<void>,
): Promise<void> {
  controller.abort()
  closeGateway()
  await settled
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

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'

/**
 * A raw RFC 6455 server that completes the handshake and then lets the test write arbitrary
 * bytes, so malformed framing that `ws` would never emit can be exercised.
 */
async function startRawWsUpstream(onOpen: (socket: Socket) => void): Promise<Harness> {
  const sockets = new Set<Socket>()
  const server = createServer()
  server.on('upgrade', (request, socket, head) => {
    sockets.add(socket)
    socket.on('close', () => sockets.delete(socket))
    // A malformed-frame test lets the daemon destroy the connection; that must not throw here.
    socket.on('error', () => undefined)
    const key = String(request.headers['sec-websocket-key'] ?? '')
    socket.write(switchingProtocols(key))
    onOpen(socket)
  })
  const port = await listen(server)
  const harness = {
    url: `ws://127.0.0.1:${port}`,
    close: () => {
      // Upgraded sockets are not tracked by the HTTP server, so close them explicitly.
      for (const socket of sockets) {
        socket.destroy()
      }
      sockets.clear()
      return closeServer(server)
    },
  }
  cleanups.push(harness.close)
  return harness
}

function acceptFor(key: string): string {
  return createHash('sha1')
    .update(key + WS_GUID)
    .digest('base64')
}

/**
 * A raw TCP server that answers the upgrade request with whatever the test wants, so handshake
 * validation (status, headers, accept key, extensions, subprotocol) can be exercised.
 */
async function startHandshakeReplier(
  response: (key: string) => string,
  onRequest?: (request: IncomingMessage) => void,
): Promise<Harness> {
  const sockets = new Set<Socket>()
  const server = createServer()
  server.on('upgrade', (request, socket) => {
    sockets.add(socket)
    socket.on('close', () => sockets.delete(socket))
    socket.on('error', () => undefined)
    onRequest?.(request)
    socket.write(response(String(request.headers['sec-websocket-key'] ?? '')))
  })
  const port = await listen(server)
  const harness = {
    url: `ws://127.0.0.1:${port}`,
    close: () => {
      for (const socket of sockets) {
        socket.destroy()
      }
      sockets.clear()
      return closeServer(server)
    },
  }
  cleanups.push(harness.close)
  return harness
}

/**
 * A server that answers with a raw, never-ended response and exposes whether the client tore the
 * connection down, so a failed handshake can be checked for socket leaks.
 */
async function startStuckResponse(response: string): Promise<{ harness: Harness; gone: Promise<void> }> {
  let resolveGone: () => void = () => undefined
  const gone = new Promise<void>((resolve) => {
    resolveGone = resolve
  })
  const sockets = new Set<Socket>()
  const server = createServer()
  server.on('upgrade', (request, socket) => {
    sockets.add(socket)
    // FIN (end), full close and reset all mean the peer connection is no longer usable.
    let finished = false
    const markGone = (): void => {
      if (!finished) {
        finished = true
        resolveGone()
      }
    }
    // Keep the socket tracked until it really closes so cleanup can still destroy it.
    socket.on('close', () => {
      sockets.delete(socket)
      markGone()
    })
    socket.on('end', markGone)
    socket.on('error', markGone)
    socket.write(response)
    // A paused socket never observes the peer's FIN, so the leak check would hang.
    socket.resume()
  })
  const port = await listen(server)
  const harness = {
    url: `ws://127.0.0.1:${port}`,
    close: () => {
      for (const socket of sockets) {
        socket.destroy()
      }
      sockets.clear()
      return closeServer(server)
    },
  }
  cleanups.push(harness.close)
  return { harness, gone }
}

function connectUpstream(
  harness: Harness,
  extra: { protocols?: string[]; headers?: [string, string][]; handshakeTimeoutMillis?: number } = {},
): Promise<UpstreamWebSocket> {
  const { host, port } = upstreamOf(harness.url)
  return UpstreamWebSocket.connect({
    host,
    port,
    path: '/',
    headers: extra.headers ?? [],
    protocols: extra.protocols,
    handshakeTimeoutMillis: extra.handshakeTimeoutMillis,
    signal: newSignal().signal,
  })
}

function switchingProtocols(key: string, extra = ''): string {
  return (
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\n' +
    'Connection: Upgrade\r\n' +
    `Sec-WebSocket-Accept: ${acceptFor(key)}\r\n` +
    extra +
    '\r\n'
  )
}

/** One invalid upgrade response and the protocol error it must produce. */
const HANDSHAKE_FAILURES: { name: string; response: (key: string) => string; pattern: RegExp; protocols?: string[] }[] = [
  {
    name: 'a non-101 status',
    pattern: /handshake returned status 403/,
    response: () => 'HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n',
  },
  {
    name: 'a missing Upgrade header',
    pattern: /missing the Upgrade header/,
    response: (key) =>
      `HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${acceptFor(key)}\r\n\r\n`,
  },
  {
    name: 'a missing Connection header',
    pattern: /missing Connection: Upgrade/,
    response: (key) =>
      `HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: ${acceptFor(key)}\r\n\r\n`,
  },
  {
    name: 'an accept key mismatch',
    pattern: /accept mismatch/,
    response: (key) =>
      `HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${acceptFor(`${key}x`)}\r\n\r\n`,
  },
  {
    name: 'a negotiated extension',
    pattern: /unsupported extension/,
    response: (key) => switchingProtocols(key, 'Sec-WebSocket-Extensions: permessage-deflate\r\n'),
  },
  {
    name: 'an unrequested subprotocol',
    pattern: /unexpected subprotocol/,
    response: (key) => switchingProtocols(key, 'Sec-WebSocket-Protocol: other\r\n'),
  },
  {
    name: 'a subprotocol outside the allowlist',
    pattern: /unexpected subprotocol/,
    protocols: ['wanted'],
    response: (key) => switchingProtocols(key, 'Sec-WebSocket-Protocol: other\r\n'),
  },
]

/** Builds one unmasked (server-to-client) RFC 6455 frame. */
function serverFrame(opcode: number, payload: Buffer, options: { fin?: boolean; rsv?: number } = {}): Buffer {
  const fin = options.fin ?? true
  const first = (fin ? 0x80 : 0) | (((options.rsv ?? 0) & 0x07) << 4) | opcode
  const length = payload.length
  if (length < 126) {
    return Buffer.concat([Buffer.from([first, length]), payload])
  }
  if (length <= 0xffff) {
    const header = Buffer.alloc(4)
    header[0] = first
    header[1] = 126
    header.writeUInt16BE(length, 2)
    return Buffer.concat([header, payload])
  }
  const header = Buffer.alloc(10)
  header[0] = first
  header[1] = 127
  header.writeBigUInt64BE(BigInt(length), 2)
  return Buffer.concat([header, payload])
}

/** One malformed upstream frame and the protocol error it must produce. */
const MALFORMED_CASES: { name: string; bytes: () => Buffer; pattern: RegExp; end?: boolean }[] = [
  {
    name: 'a masked server frame',
    pattern: /must not be masked/,
    bytes: () => {
      const payload = Buffer.from([1, 2, 3])
      const mask = Buffer.from([9, 8, 7, 6])
      const masked = Buffer.alloc(payload.length)
      for (let index = 0; index < payload.length; index += 1) {
        masked[index] = payload[index]! ^ mask[index & 3]!
      }
      return Buffer.concat([Buffer.from([0x82, 0x80 | payload.length]), mask, masked])
    },
  },
  { name: 'reserved bits', pattern: /reserved bits/, bytes: () => serverFrame(0x1, Buffer.from('a'), { rsv: 1 }) },
  {
    name: 'a non-canonical two byte length',
    pattern: /not canonical/,
    bytes: () => Buffer.concat([Buffer.from([0x82, 126, 0x00, 0x05]), Buffer.alloc(5, 1)]),
  },
  {
    name: 'a non-canonical eight byte length',
    pattern: /not canonical/,
    bytes: () => {
      const header = Buffer.alloc(10)
      header[0] = 0x82
      header[1] = 127
      header.writeBigUInt64BE(BigInt(5), 2)
      return Buffer.concat([header, Buffer.alloc(5, 1)])
    },
  },
  {
    name: 'a fragmented control frame',
    pattern: /control frame must not be fragmented/,
    bytes: () => serverFrame(0x9, Buffer.from('x'), { fin: false }),
  },
  {
    name: 'an oversized control frame',
    pattern: /control frame is too large/,
    bytes: () => serverFrame(0x9, Buffer.alloc(126)),
  },
  {
    name: 'a new message before the previous one finished',
    pattern: /started a new message/,
    bytes: () => Buffer.concat([serverFrame(0x1, Buffer.from('a'), { fin: false }), serverFrame(0x1, Buffer.from('b'))]),
  },
  {
    name: 'a continuation without a message',
    pattern: /continuation without a message/,
    bytes: () => serverFrame(0x0, Buffer.from('a')),
  },
  {
    name: 'an unsupported data opcode',
    pattern: /data opcode 3 is unsupported/,
    bytes: () => serverFrame(0x3, Buffer.from('a')),
  },
  {
    name: 'a truncated close frame',
    pattern: /close frame is truncated/,
    bytes: () => serverFrame(0x8, Buffer.from([0x03])),
  },
  {
    name: 'invalid UTF-8 in a text message',
    pattern: /not valid UTF-8/,
    bytes: () => serverFrame(0x1, Buffer.from([0xff, 0xfe])),
  },
  {
    name: 'a truncated two byte length',
    pattern: /length is truncated/,
    end: true,
    bytes: () => Buffer.from([0x82, 126]),
  },
  {
    name: 'a truncated eight byte length',
    pattern: /length is truncated/,
    end: true,
    bytes: () => Buffer.from([0x82, 127, 0, 0, 0]),
  },
  {
    name: 'an eight byte length with the high bit set',
    pattern: /length is invalid/,
    bytes: () => Buffer.from([0x82, 127, 0x80, 0, 0, 0, 0, 0, 0, 0]),
  },
  {
    name: 'an eight byte length beyond safe integer range',
    pattern: /too large/,
    bytes: () => Buffer.from([0x82, 127, 0x00, 0x20, 0, 0, 0, 0, 0, 0]),
  },
  {
    name: 'a truncated control payload',
    pattern: /control frame is truncated/,
    end: true,
    bytes: () => Buffer.from([0x89, 0x05, 1, 2]),
  },
  {
    name: 'a truncated data payload',
    pattern: /payload is truncated/,
    end: true,
    bytes: () => Buffer.from([0x82, 0x05, 1, 2]),
  },
  {
    name: 'an invalid close code',
    pattern: /close code is invalid/,
    bytes: () => serverFrame(0x8, Buffer.from([0x03, 0xed])),
  },
  {
    name: 'a close reason that is not valid UTF-8',
    pattern: /close reason is not valid UTF-8/,
    bytes: () => serverFrame(0x8, Buffer.from([0x03, 0xe8, 0xff, 0xfe])),
  },
  {
    name: 'an unsupported control opcode',
    pattern: /control opcode 11 is unsupported/,
    bytes: () => serverFrame(0xb, Buffer.alloc(0)),
  },
]

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

/** Sends one WebSocket message as credit controlled wire fragments, marking the final FIN. */
class WsFragmentPump {
  private outstanding = 0
  private index = 0
  private completed = false

  constructor(
    private readonly gateway: Gateway,
    private readonly chunks: Buffer[],
    private readonly kind: 'text' | 'binary',
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
      const last = this.index === this.chunks.length - 1
      this.index += 1
      this.gateway.socket.send(encodeWsFrame(this.kind, chunk, last))
      this.outstanding += chunk.length
    }
    if (!this.completed) {
      this.completed = true
      this.onComplete()
    }
  }
}

const ACK_THRESHOLD = 64 * 1024

/** One raw (server -> client) WebSocket connection the test drives byte by byte. */
interface RawGatewayConn {
  socket: Socket
  /** Decoded wire payloads the daemon has sent to this gateway, in order. */
  received: Buffer[]
  /** Writes one unmasked WebSocket frame carrying `payload`. */
  send(opcode: number, payload: Buffer): void
  /** Writes raw bytes, so several frames can share one TCP write and one event loop turn. */
  write(bytes: Buffer): void
}

/**
 * A gateway that answers the handshake itself and lets the test write arbitrary frames, so the
 * exact arrival turn of several peer frames can be controlled (unlike the `ws` server helper).
 */
async function startRawGateway(onOpen: (conn: RawGatewayConn) => void): Promise<Harness> {
  const sockets = new Set<Socket>()
  const server = createServer()
  server.on('upgrade', (request, socket) => {
    sockets.add(socket)
    socket.on('close', () => sockets.delete(socket))
    socket.on('error', () => undefined)
    socket.write(switchingProtocols(String(request.headers['sec-websocket-key'] ?? '')))
    onOpen(createRawGatewayConn(socket))
  })
  const port = await listen(server)
  const harness = {
    url: `ws://127.0.0.1:${port}/api/harness/environment-stream/v1/ticket`,
    close: () => {
      for (const socket of sockets) {
        socket.destroy()
      }
      sockets.clear()
      return closeServer(server)
    },
  }
  cleanups.push(harness.close)
  return harness
}

/** Parses the daemon's masked client frames and exposes their wire payloads. */
function createRawGatewayConn(socket: Socket): RawGatewayConn {
  const received: Buffer[] = []
  let buffer = Buffer.alloc(0)
  socket.on('data', (chunk: Buffer) => {
    buffer = buffer.length === 0 ? chunk : Buffer.concat([buffer, chunk])
    for (;;) {
      if (buffer.length < 2) {
        return
      }
      const masked = (buffer[1]! & 0x80) !== 0
      let length = buffer[1]! & 0x7f
      let header = 2
      if (length === 126) {
        if (buffer.length < 4) {
          return
        }
        length = buffer.readUInt16BE(2)
        header = 4
      } else if (length === 127) {
        if (buffer.length < 10) {
          return
        }
        length = Number(buffer.readBigUInt64BE(2))
        header = 10
      }
      const maskLength = masked ? 4 : 0
      const payloadStart = header + maskLength
      if (buffer.length < payloadStart + length) {
        return
      }
      let payload = buffer.subarray(payloadStart, payloadStart + length)
      if (masked) {
        const mask = buffer.subarray(header, header + 4)
        const decoded = Buffer.allocUnsafe(length)
        for (let index = 0; index < length; index += 1) {
          decoded[index] = payload[index]! ^ mask[index & 3]!
        }
        payload = decoded
      } else {
        payload = Buffer.from(payload)
      }
      buffer = buffer.subarray(payloadStart + length)
      received.push(payload)
    }
  })
  return {
    socket,
    received,
    send: (opcode, payload) => socket.write(serverFrame(opcode, payload)),
    write: (bytes) => socket.write(bytes),
  }
}

/** A true WebSocket data fragment the daemon forwarded from the local IDE (not a control frame). */
function isForwardedDataFrame(payload: Buffer): boolean {
  try {
    const kind = decodeBinaryFrame(payload).kind
    return kind === 'ws-text' || kind === 'ws-binary'
  } catch {
    return false
  }
}

/** The values of one header name from a raw header list, preserving order and duplicates. */
function rawHeaderValues(rawHeaders: string[], name: string): string[] {
  const values: string[] = []
  for (let index = 0; index + 1 < rawHeaders.length; index += 2) {
    if (rawHeaders[index]?.toLowerCase() === name) {
      values.push(rawHeaders[index + 1] ?? '')
    }
  }
  return values
}

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

  it('reports cancellation while the gateway upgrade is still pending', async () => {
    const upstream = await startHttpUpstream(() => undefined)
    const server = createServer()
    const sockets = new Set<Socket>()
    let markUpgrade!: () => void
    const upgrading = new Promise<void>((resolve) => {
      markUpgrade = resolve
    })
    server.on('upgrade', (_request, socket) => {
      sockets.add(socket)
      socket.on('error', () => undefined)
      socket.on('close', () => sockets.delete(socket))
      markUpgrade()
      // Keep the HTTP upgrade pending so cancellation cannot depend on a fast local handshake.
    })
    const port = await listen(server)
    cleanups.push(async () => {
      for (const socket of sockets) {
        socket.destroy()
      }
      await closeServer(server)
    })
    const controller = new AbortController()
    const pending = openStreamExchange({
      streamUrl: `ws://127.0.0.1:${port}`,
      request: { mode: 'HTTP', method: 'GET', path: '/', headers: [] },
      upstream: upstreamOf(upstream.url),
      signal: controller.signal,
    })
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    await upgrading
    controller.abort()
    await rejected
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

  it('relays a >20MiB fragmented text message across credit rounds without corrupting UTF-8', async () => {
    // A multi-byte heavy message whose fragments (and multi-byte sequences) straddle 64KiB frames.
    const unit = Buffer.from('ab世🙂'.repeat(1024), 'utf8')
    const count = Math.ceil((20 * 1024 * 1024) / unit.length)
    const message = Buffer.alloc(count * unit.length)
    for (let index = 0; index < count; index += 1) {
      unit.copy(message, index * unit.length)
    }

    let received: Buffer | undefined
    let receivedIsBinary: boolean | undefined
    const upstream = await startWsUpstream((socket) => {
      socket.on('message', (data, isBinary) => {
        received = Buffer.from(data as Buffer)
        receivedIsBinary = isBinary
      })
    })

    let ready = false
    let gatewaySocket: WebSocket | undefined
    const gateway = await startGateway((conn) => {
      gatewaySocket = conn.socket
      let pump: WsFragmentPump | undefined
      conn.socket.on('message', (data, isBinary) => {
        if (!isBinary) {
          if (decodeTextFrame(data.toString('utf8')).type === 'WS_READY') {
            ready = true
            pump = new WsFragmentPump(conn, chunkBuffer(message), 'text', () => undefined)
            pump.start()
          }
          return
        }
        const frame = decodeBinaryFrame(data as Buffer)
        if (frame.kind === 'ack') {
          pump?.ack(frame.bytes)
        }
      })
    })

    const exchange = openStreamExchange({
      streamUrl: gateway.url,
      request: { mode: 'WS', method: 'GET', path: '/ws', headers: [] },
      upstream: upstreamOf(upstream.url),
      signal: newSignal().signal,
    })

    await waitFor(() => ready)
    await waitFor(() => received !== undefined && received.length === message.length, 20_000)
    expect(receivedIsBinary).toBe(false)
    expect(received?.equals(message)).toBe(true)

    gatewaySocket?.close()
    await exchange
  }, 30_000)

  it('throttles an upstream that outruns the browser instead of buffering unboundedly', async () => {
    const total = 1500
    const messageSize = 32 * 1024
    const message = Buffer.alloc(messageSize, 0x41)
    let produced = 0
    // Production is driven by flush callbacks: if the daemon stops reading, it stops here.
    const upstream = await startWsUpstream((socket) => {
      let sent = 0
      const next = (): void => {
        if (sent >= total) {
          return
        }
        sent += 1
        socket.send(message, () => {
          produced += 1
          next()
        })
      }
      next()
    })

    let received = 0
    let unacked = 0
    let ackEnabled = false
    let gatewaySocket: WebSocket | undefined
    let flushAck: (() => void) | undefined
    const gateway = await startGateway((conn) => {
      gatewaySocket = conn.socket
      flushAck = (): void => {
        if (unacked > 0) {
          conn.sendAck(unacked)
          unacked = 0
        }
      }
      conn.socket.on('message', (data, isBinary) => {
        if (!isBinary) {
          return
        }
        const frame = decodeBinaryFrame(data as Buffer)
        if (frame.kind === 'ws-text' || frame.kind === 'ws-binary') {
          received += frame.payload.length
          unacked += frame.payload.length
          if (ackEnabled && unacked >= ACK_THRESHOLD) {
            flushAck()
          }
        }
      })
    })

    const controller = newSignal()
    const exchange = openStreamExchange({
      streamUrl: gateway.url,
      request: { mode: 'WS', method: 'GET', path: '/ws', headers: [] },
      upstream: upstreamOf(upstream.url),
      signal: controller.signal,
    })
    // Observe the exchange immediately so cleanup-triggered aborts can never escape as unhandled
    // rejections; the body's own assertion error remains the primary failure.
    const exchangeSettled = exchange.then(
      () => undefined,
      () => undefined,
    )

    try {
      // While the browser withholds credit, the daemon must stop the upstream from producing.
      await waitFor(() => produced > 0)
      await delay(300)
      const throttled = produced
      expect(throttled).toBeLessThan(total / 2)

      ackEnabled = true
      flushAck?.()
      const expected = total * messageSize
      // Drain is bounded by observed byte progress, not by an assumed throughput: a slow machine
      // keeps extending the idle deadline, while a deadlock and an absolute budget both fail with
      // the transfer counters.
      await awaitProgress(
        () => received,
        () => received >= expected && produced >= total,
        () => `produced=${produced}/${total} received=${received}/${expected} unacked=${unacked}`,
      )
      expect(received).toBe(expected)
      expect(produced).toBeGreaterThanOrEqual(total)

      gatewaySocket?.close()
      await exchange
    } finally {
      // Abort before waiting: if the body failed before the gateway was assigned (handshake still
      // in flight), only the signal can settle the exchange; awaiting first would deadlock and
      // defer the abort to afterEach.
      await abortAndSettle(controller, () => gatewaySocket?.terminate(), exchangeSettled)
    }
  }, 90_000)

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

  it('pumps a GET body upstream instead of silently dropping it', async () => {
    const body = Buffer.from('query-params-as-body')
    let receivedBody = Buffer.alloc(0)
    const upstream = await startHttpUpstream((req, res) => {
      const chunks: Buffer[] = []
      req.on('data', (chunk) => chunks.push(chunk as Buffer))
      req.on('end', () => {
        receivedBody = Buffer.concat(chunks)
        res.writeHead(200, { 'content-type': 'text/plain' })
        res.end('ok')
      })
    })
    let ended = false
    const gateway = await startGateway((conn) => {
      conn.sendBody(body)
      conn.sendEnd()
      conn.socket.on('message', (data, isBinary) => {
        if (isBinary && decodeBinaryFrame(data as Buffer).kind === 'http-end') {
          ended = true
        }
      })
    })

    await openStreamExchange({
      streamUrl: gateway.url,
      request: { mode: 'HTTP', method: 'GET', path: '/g', headers: [] },
      upstream: upstreamOf(upstream.url),
      signal: newSignal().signal,
    })
    await waitFor(() => ended)
    expect(ended).toBe(true)
    expect(receivedBody.equals(body)).toBe(true)
  })

  it('completes a HEAD exchange that carries a body frame', async () => {
    const upstream = await startHttpUpstream((_req, res) => {
      res.writeHead(200, { 'content-type': 'text/plain' })
      res.end('head')
    })
    let ended = false
    const gateway = await startGateway((conn) => {
      conn.sendBody(Buffer.from('head-body'))
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

  it(
    'streams a >20MiB single upstream message without buffering it whole',
    async () => {
      const size = 20 * 1024 * 1024
      const payload = Buffer.alloc(size, 0x5a)
      let upstreamFlushed = false
      let releaseRemainder: (() => void) | undefined
      const upstream = await startRawWsUpstream((socket) => {
        const frame = serverFrame(0x2, payload)
        const prefixLength = 10 + INITIAL_CREDIT + MAX_PAYLOAD
        // The rest of this single frame is withheld: whole-message buffering cannot forward it.
        socket.write(frame.subarray(0, prefixLength))
        releaseRemainder = () => {
          socket.write(frame.subarray(prefixLength), () => {
            upstreamFlushed = true
          })
        }
      })

      let received = 0
      let lastSeen = false
      let unacked = 0
      let peakUnacked = 0
      let ackEnabled = false
      let flushAck: (() => void) | undefined
      let gatewaySocket: WebSocket | undefined
      const gateway = await startGateway((conn) => {
        gatewaySocket = conn.socket
        flushAck = (): void => {
          if (unacked > 0) {
            conn.sendAck(unacked)
            unacked = 0
          }
        }
        conn.socket.on('message', (data, isBinary) => {
          if (!isBinary) {
            return
          }
          const frame = decodeBinaryFrame(data as Buffer)
          if (frame.kind !== 'ws-binary' && frame.kind !== 'ws-text') {
            return
          }
          received += frame.payload.length
          lastSeen = lastSeen || frame.last
          unacked += frame.payload.length
          peakUnacked = Math.max(peakUnacked, unacked)
          if (ackEnabled && unacked >= ACK_THRESHOLD) {
            flushAck()
          }
        })
      })

      const exchange = openStreamExchange({
        streamUrl: gateway.url,
        request: { mode: 'WS', method: 'GET', path: '/ws', headers: [] },
        upstream: upstreamOf(upstream.url),
        signal: newSignal().signal,
      })

      // Receiving bytes before the rest of the frame is sent proves incremental forwarding,
      // independently of platform-specific TCP send/receive buffer sizes.
      await waitFor(() => received > 0, 20_000)
      await delay(300)
      expect(upstreamFlushed).toBe(false)
      expect(lastSeen).toBe(false)
      expect(received).toBeLessThan(size)
      expect(peakUnacked).toBeLessThanOrEqual(INITIAL_CREDIT + MAX_PAYLOAD)

      ackEnabled = true
      flushAck?.()
      releaseRemainder!()
      await waitFor(() => received === size && lastSeen, 60_000)
      expect(lastSeen).toBe(true)
      expect(peakUnacked).toBeLessThanOrEqual(INITIAL_CREDIT + MAX_PAYLOAD)
      await waitFor(() => upstreamFlushed, 30_000)

      gatewaySocket?.close()
      await exchange
    },
    120_000,
  )

  it('relays more than 256KiB in both directions at once', async () => {
    const fromBrowser = Buffer.alloc(512 * 1024 + 7, 0x11)
    const fromUpstream = Buffer.alloc(512 * 1024 + 9, 0x22)
    let receivedByUpstream = Buffer.alloc(0)
    const upstream = await startWsUpstream((socket) => {
      socket.on('message', (data) => {
        receivedByUpstream = Buffer.concat([receivedByUpstream, data as Buffer])
      })
      socket.send(fromUpstream)
    })

    let receivedByBrowser = Buffer.alloc(0)
    let gatewaySocket: WebSocket | undefined
    const gateway = await startGateway((conn) => {
      gatewaySocket = conn.socket
      let pump: WsFragmentPump | undefined
      conn.socket.on('message', (data, isBinary) => {
        if (!isBinary) {
          if (decodeTextFrame(data.toString('utf8')).type === 'WS_READY') {
            pump = new WsFragmentPump(conn, chunkBuffer(fromBrowser), 'binary', () => undefined)
            pump.start()
          }
          return
        }
        const frame = decodeBinaryFrame(data as Buffer)
        if (frame.kind === 'ack') {
          pump?.ack(frame.bytes)
        } else if (frame.kind === 'ws-binary' || frame.kind === 'ws-text') {
          receivedByBrowser = Buffer.concat([receivedByBrowser, frame.payload])
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

    await waitFor(() => receivedByUpstream.length === fromBrowser.length, 20_000)
    await waitFor(() => receivedByBrowser.length === fromUpstream.length, 20_000)
    expect(receivedByUpstream.equals(fromBrowser)).toBe(true)
    expect(receivedByBrowser.equals(fromUpstream)).toBe(true)

    gatewaySocket?.close()
    await exchange
  })

  it('preserves UTF-8 text split across frame and socket boundaries', async () => {
    // The first multi-byte character straddles the 64KiB piece boundary on purpose.
    const head = Buffer.alloc(MAX_PAYLOAD - 1, 0x61)
    const message = Buffer.concat([head, Buffer.from('世界🙂'.repeat(2048), 'utf8')])
    const upstream = await startWsUpstream((socket) => {
      // Sent as text so the daemon must validate UTF-8 while streaming it.
      socket.send(message, { binary: false })
    })

    let received = Buffer.alloc(0)
    let lastSeen = false
    let gatewaySocket: WebSocket | undefined
    const gateway = await startGateway((conn) => {
      gatewaySocket = conn.socket
      conn.socket.on('message', (data, isBinary) => {
        if (!isBinary) {
          return
        }
        const frame = decodeBinaryFrame(data as Buffer)
        if (frame.kind === 'ws-text') {
          received = Buffer.concat([received, frame.payload])
          lastSeen = lastSeen || frame.last
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

    await waitFor(() => lastSeen, 30_000)
    expect(received.equals(message)).toBe(true)

    gatewaySocket?.close()
    await exchange
  })

  it('streams a fragmented upstream message whose final frame is empty', async () => {
    const upstream = await startWsUpstream((socket) => {
      socket.send(Buffer.from('hello'), { fin: false })
      socket.send(Buffer.alloc(0), { fin: true })
    })

    const fragments: { payload: Buffer; last: boolean }[] = []
    let gatewaySocket: WebSocket | undefined
    const gateway = await startGateway((conn) => {
      gatewaySocket = conn.socket
      conn.socket.on('message', (data, isBinary) => {
        if (!isBinary) {
          return
        }
        const frame = decodeBinaryFrame(data as Buffer)
        if (frame.kind === 'ws-text' || frame.kind === 'ws-binary') {
          fragments.push({ payload: frame.payload, last: frame.last })
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

    await waitFor(() => fragments.some((fragment) => fragment.last), 8000)
    expect(Buffer.concat(fragments.map((fragment) => fragment.payload)).toString('utf8')).toBe('hello')
    // The empty final frame is still a legal message terminator and is forwarded as such.
    expect(fragments.at(-1)?.last).toBe(true)
    expect(fragments.length).toBeGreaterThanOrEqual(2)

    gatewaySocket?.close()
    await exchange
  })

  it('answers an upstream ping so the local socket is not dropped', async () => {
    let pongReceived = false
    const upstream = await startWsUpstream((socket) => {
      socket.on('pong', () => {
        pongReceived = true
      })
      socket.ping()
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

    await waitFor(() => pongReceived, 8000)
    expect(pongReceived).toBe(true)

    gatewaySocket?.close()
    await exchange
  })

  it('cancels an in-flight upstream handshake', async () => {
    // A server that accepts the connection but never completes the upgrade.
    const silent = createServer(() => undefined)
    const port = await listen(silent)
    cleanups.push(() => closeServer(silent))
    const gateway = await startGateway(() => undefined)

    const controller = new AbortController()
    const pending = openStreamExchange({
      streamUrl: gateway.url,
      request: { mode: 'WS', method: 'GET', path: '/ws', headers: [] },
      upstream: { host: '127.0.0.1', port },
      signal: controller.signal,
    })
    await delay(20)
    controller.abort()
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('ignores an http end frame during a websocket exchange', async () => {
    const upstream = await startWsUpstream((socket) => {
      socket.on('message', () => undefined)
    })
    let gatewaySocket: WebSocket | undefined
    const gateway = await startGateway((conn) => {
      gatewaySocket = conn.socket
      // An HTTP end is meaningless for a WebSocket: only the socket close may end the exchange.
      conn.sendEnd()
    })

    const exchange = openStreamExchange({
      streamUrl: gateway.url,
      request: { mode: 'WS', method: 'GET', path: '/ws', headers: [] },
      upstream: upstreamOf(upstream.url),
      signal: newSignal().signal,
    })
    await waitFor(() => gatewaySocket?.readyState === WebSocket.OPEN)
    await delay(30)
    expect(gatewaySocket?.readyState).toBe(WebSocket.OPEN)
    gatewaySocket?.close()
    await exchange
  })

  it('rejects an http body frame during a websocket exchange', async () => {
    const upstream = await startWsUpstream(() => undefined)
    const gateway = await startGateway((conn) => {
      conn.sendBody(Buffer.from('x'))
    })

    await expect(
      openStreamExchange({
        streamUrl: gateway.url,
        request: { mode: 'WS', method: 'GET', path: '/ws', headers: [] },
        upstream: upstreamOf(upstream.url),
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/unexpected HTTP frame during WebSocket exchange/)
  })
})

describe('upstream websocket framing is validated strictly', () => {
  for (const testCase of MALFORMED_CASES) {
    it(`rejects ${testCase.name}`, async () => {
      const upstream = await startRawWsUpstream((socket) => {
        socket.write(testCase.bytes())
        if (testCase.end) {
          socket.end()
        }
      })
      const gateway = await startGateway(() => undefined)

      await expect(
        openStreamExchange({
          streamUrl: gateway.url,
          request: { mode: 'WS', method: 'GET', path: '/ws', headers: [] },
          upstream: upstreamOf(upstream.url),
          signal: newSignal().signal,
        }),
      ).rejects.toThrow(testCase.pattern)
    })
  }
})

describe('wire framing is validated', () => {
  const httpRequest = { mode: 'HTTP' as const, method: 'GET', path: '/', headers: [] }
  const wsRequest = { mode: 'WS' as const, method: 'GET', path: '/ws', headers: [] }

  it('rejects an empty http body frame', async () => {
    const upstream = await startHttpUpstream(() => undefined)
    const gateway = await startGateway((conn) => {
      conn.sendRaw(encodeHttpBody(Buffer.alloc(0)))
    })
    await expect(
      openStreamExchange({
        streamUrl: gateway.url,
        request: httpRequest,
        upstream: upstreamOf(upstream.url),
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/must not be empty/)
  })

  it('rejects a body frame after the request end', async () => {
    const upstream = await startHttpUpstream(() => undefined)
    const gateway = await startGateway((conn) => {
      conn.sendEnd()
      conn.sendBody(Buffer.from('x'))
    })
    await expect(
      openStreamExchange({
        streamUrl: gateway.url,
        request: httpRequest,
        upstream: upstreamOf(upstream.url),
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/after the request end/)
  })

  it('rejects a duplicate request end', async () => {
    const upstream = await startHttpUpstream(() => undefined)
    const gateway = await startGateway((conn) => {
      conn.sendEnd()
      conn.sendEnd()
    })
    await expect(
      openStreamExchange({
        streamUrl: gateway.url,
        request: httpRequest,
        upstream: upstreamOf(upstream.url),
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/duplicate http end/)
  })

  it('rejects a websocket message that changes type across fragments', async () => {
    const upstream = await startWsUpstream(() => undefined)
    const gateway = await startGateway((conn) => {
      conn.sendWs('text', Buffer.from('a'), false)
      conn.sendWs('binary', Buffer.from('b'), true)
    })
    await expect(
      openStreamExchange({
        streamUrl: gateway.url,
        request: wsRequest,
        upstream: upstreamOf(upstream.url),
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/type changed across fragments/)
  })

  it('bounds a flood of zero length websocket fragments', async () => {
    const upstream = await startWsUpstream(() => undefined)
    const gateway = await startGateway((conn) => {
      // Zero length fragments reserve no credit, so only the frame count can stop them.
      for (let index = 0; index < 4000; index += 1) {
        conn.sendWs('binary', Buffer.alloc(0), false)
      }
    })
    await expect(
      openStreamExchange({
        streamUrl: gateway.url,
        request: wsRequest,
        upstream: upstreamOf(upstream.url),
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/too many buffered websocket fragments/)
  })
})

describe('credit window cancellation', () => {
  it('releases a blocked wait when its signal aborts', async () => {
    const window = new CreditWindow(INITIAL_CREDIT)
    window.reserve(INITIAL_CREDIT)
    const controller = new AbortController()
    const blocked = window.waitFor(1, controller.signal)
    controller.abort()
    await expect(blocked).rejects.toMatchObject({ name: 'AbortError' })

    // An already aborted signal never registers a waiter, and a normal ack still wakes waiters.
    await expect(window.waitFor(1, controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
    window.ack(INITIAL_CREDIT)
    await expect(window.waitFor(1)).resolves.toBeUndefined()
  })
})

describe('upstream websocket client', () => {
  for (const testCase of HANDSHAKE_FAILURES) {
    it(`refuses ${testCase.name}`, async () => {
      const upstream = await startHandshakeReplier(testCase.response)
      await expect(connectUpstream(upstream, { protocols: testCase.protocols })).rejects.toThrow(testCase.pattern)
    })
  }

  it('times out a handshake that never completes', async () => {
    const silent = createServer(() => undefined)
    const port = await listen(silent)
    cleanups.push(() => closeServer(silent))
    await expect(
      UpstreamWebSocket.connect({
        host: '127.0.0.1',
        port,
        path: '/',
        headers: [],
        signal: newSignal().signal,
        handshakeTimeoutMillis: 40,
      }),
    ).rejects.toThrow(/handshake timed out/)
  })

  it('negotiates a requested subprotocol and forwards repeated headers', async () => {
    let seen: IncomingHttpHeaders | undefined
    const upstream = await startHandshakeReplier(
      (key) => switchingProtocols(key, 'Sec-WebSocket-Protocol: vscode-api\r\n'),
      (request) => {
        seen = request.headers
      },
    )
    const client = await connectUpstream(upstream, {
      protocols: ['vscode-api'],
      headers: [
        ['x-dup', 'a'],
        ['x-dup', 'b'],
        ['x-dup', 'c'],
      ],
    })
    expect(client.protocol).toBe('vscode-api')
    expect(seen?.['x-dup']).toBe('a, b, c')
    client.destroy()
  })

  it('sends masked fragments the IDE reassembles, then a masked close frame', async () => {
    const received: Buffer[] = []
    let closed: { code: number; reason: string } | undefined
    const upstream = await startWsUpstream((socket) => {
      socket.on('message', (data) => {
        received.push(Buffer.from(data as Buffer))
      })
      socket.on('close', (code, reason) => {
        closed = { code, reason: reason.toString() }
      })
    })
    const client = await connectUpstream(upstream)
    await client.sendFragment('text', Buffer.from('ab'), false)
    await client.sendFragment('continuation', Buffer.from('cd'), true)
    await waitFor(() => received.length === 1)
    expect(received[0]?.toString()).toBe('abcd')

    await client.close(1001, 'leaving')
    await waitFor(() => closed !== undefined)
    expect(closed).toEqual({ code: 1001, reason: 'leaving' })

    // Once a close frame has been sent no further data frame may follow.
    await expect(client.sendFragment('binary', Buffer.from('late'), true)).rejects.toThrow(/is closed/)
  })

  it('rejects a fragment sent after destroy', async () => {
    const upstream = await startWsUpstream(() => undefined)
    const client = await connectUpstream(upstream)
    client.destroy()
    await expect(client.sendFragment('binary', Buffer.from('x'), true)).rejects.toThrow(/is closed/)
  })

  it('reports an abnormal close when the socket ends without a close frame', async () => {
    const upstream = await startRawWsUpstream((socket) => socket.end())
    const client = await connectUpstream(upstream)
    const iterator = client.frames()
    await expect(iterator.next()).resolves.toEqual({ value: { type: 'close', code: 1006, reason: '' }, done: false })
    client.destroy()
  })

  it('reports an empty close frame as code 1005', async () => {
    const upstream = await startRawWsUpstream((socket) => socket.write(serverFrame(0x8, Buffer.alloc(0))))
    const client = await connectUpstream(upstream)
    const iterator = client.frames()
    await expect(iterator.next()).resolves.toEqual({ value: { type: 'close', code: 1005, reason: '' }, done: false })
    client.destroy()
  })

  it('ignores unsolicited pong frames', async () => {
    const upstream = await startRawWsUpstream((socket) => {
      socket.write(Buffer.concat([serverFrame(0xa, Buffer.from('p')), serverFrame(0x1, Buffer.from('ok'))]))
    })
    const client = await connectUpstream(upstream)
    const iterator = client.frames()
    const first = await iterator.next()
    expect(first.value).toMatchObject({ type: 'data', kind: 'text', last: true })
    expect((first.value as { payload: Buffer }).payload.toString()).toBe('ok')
    client.destroy()
  })

  it('destroys the socket of a handshake response that never ends', async () => {
    // A 200 with neither Content-Length nor EOF: a malicious body could stream forever.
    const { harness, gone } = await startStuckResponse('HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n')
    await expect(connectUpstream(harness)).rejects.toThrow(/handshake returned status 200/)
    // The failed handshake must not leak the socket.
    await gone
  })

  it('destroys the socket of a 101 that is not a complete upgrade', async () => {
    const { harness, gone } = await startStuckResponse(
      'HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\n\r\n',
    )
    await expect(connectUpstream(harness)).rejects.toThrow(/missing the Upgrade header/)
    await gone
  })

  it('rejects an already aborted signal without opening a socket', async () => {
    const controller = new AbortController()
    controller.abort()
    await expect(
      UpstreamWebSocket.connect({
        host: '127.0.0.1',
        port: 1,
        path: '/',
        headers: [],
        signal: controller.signal,
      }),
    ).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('never publishes FIN for a text message whose trailing sequence is truncated', async () => {
    // 'a' followed by the first two bytes of a three byte sequence: the message can never be valid.
    const upstream = await startRawWsUpstream((socket) =>
      socket.write(serverFrame(0x1, Buffer.from([0x61, 0xe4, 0xb8]))),
    )
    const client = await connectUpstream(upstream)
    const iterator = client.frames()
    // The very first read must fail; no data frame (and so no FIN) may be published.
    await expect(iterator.next()).rejects.toThrow(/not valid UTF-8/)
    client.destroy()
  })

  const CLOSE_CODES: { code: number; valid: boolean }[] = [
    { code: 1000, valid: true },
    { code: 1001, valid: true },
    { code: 1011, valid: true },
    { code: 1012, valid: true },
    { code: 1013, valid: true },
    { code: 1014, valid: true },
    { code: 3000, valid: true },
    { code: 4999, valid: true },
    { code: 999, valid: false },
    { code: 1004, valid: false },
    { code: 1005, valid: false },
    { code: 1006, valid: false },
    { code: 1015, valid: false },
    { code: 1016, valid: false },
    { code: 2000, valid: false },
    { code: 2999, valid: false },
    { code: 5000, valid: false },
  ]

  it('accepts only the close codes the protocol allows', async () => {
    for (const { code, valid } of CLOSE_CODES) {
      const payload = Buffer.alloc(2)
      payload.writeUInt16BE(code, 0)
      const upstream = await startRawWsUpstream((socket) => socket.write(serverFrame(0x8, payload)))
      const client = await connectUpstream(upstream)
      const iterator = client.frames()
      if (valid) {
        await expect(iterator.next()).resolves.toEqual({ value: { type: 'close', code, reason: '' }, done: false })
      } else {
        await expect(iterator.next()).rejects.toThrow(/close code is invalid/)
      }
      client.destroy()
    }
  })

  it('truncates a close reason on a code point boundary', async () => {
    let received: { code: number; reason: string } | undefined
    const upstream = await startWsUpstream((socket) => {
      socket.on('close', (code, reason) => {
        received = { code, reason: reason.toString('utf8') }
      })
    })
    const client = await connectUpstream(upstream)
    await client.close(1000, '😀'.repeat(60))
    await waitFor(() => received !== undefined)
    // 123 bytes would split the 31st four byte emoji, so only 30 complete ones fit.
    expect(received).toEqual({ code: 1000, reason: '😀'.repeat(30) })
  })

  it('treats a close frame on an already gone socket as benign', async () => {
    const upstream = await startWsUpstream((socket) => socket.terminate())
    const client = await connectUpstream(upstream)
    const iterator = client.frames()
    // The peer reset the connection; draining reports it, after which the socket is gone.
    await iterator.next().catch(() => undefined)
    await expect(client.close(1000, 'bye')).resolves.toBeUndefined()
  })
})

describe('stream failure boundaries', () => {
  const httpRequest = { mode: 'HTTP' as const, method: 'GET', path: '/', headers: [] }
  const wsRequest = { mode: 'WS' as const, method: 'GET', path: '/ws', headers: [] }

  it('forwards a declared content-length and every duplicate header in order', async () => {
    let rawHeaders: string[] = []
    const upstream = await startHttpUpstream((req, res) => {
      rawHeaders = req.rawHeaders
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
        method: 'POST',
        path: '/',
        // A declared length suppresses the chunked fallback; three duplicates must all survive.
        headers: [
          ['content-length', '0'],
          ['x-dup', 'a'],
          ['x-dup', 'b'],
          ['x-dup', 'c'],
        ],
      },
      upstream: upstreamOf(upstream.url),
      signal: newSignal().signal,
    })

    await waitFor(() => ended)
    expect(rawHeaderValues(rawHeaders, 'content-length')).toEqual(['0'])
    expect(rawHeaderValues(rawHeaders, 'x-dup')).toEqual(['a', 'b', 'c'])
    expect(rawHeaderValues(rawHeaders, 'transfer-encoding')).toEqual([])
  })

  it('rejects the exchange when the gateway reports a peer ERROR frame', async () => {
    let httpReached!: () => void
    const httpBarrier = new Promise<void>((resolve) => {
      httpReached = resolve
    })
    let httpGone = false
    const upstream = await startHttpUpstream((req) => {
      httpReached()
      req.socket.on('close', () => {
        httpGone = true
      })
    })
    const httpGateway = await startGateway((conn) => {
      void (async () => {
        await httpBarrier
        conn.sendText({ type: 'ERROR', message: 'peer says no' })
      })()
    })

    await expect(
      openStreamExchange({
        streamUrl: httpGateway.url,
        request: httpRequest,
        upstream: upstreamOf(upstream.url),
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/stream peer rejected exchange: peer says no/)
    await waitFor(() => httpGone)

    let wsReached!: () => void
    const wsBarrier = new Promise<void>((resolve) => {
      wsReached = resolve
    })
    let wsGone = false
    const wsUpstream = await startWsUpstream((socket) => {
      wsReached()
      socket.on('close', () => {
        wsGone = true
      })
    })
    const wsGateway = await startGateway((conn) => {
      void (async () => {
        await wsBarrier
        conn.sendText({ type: 'ERROR', message: 'peer says no' })
      })()
    })

    await expect(
      openStreamExchange({
        streamUrl: wsGateway.url,
        request: wsRequest,
        upstream: upstreamOf(wsUpstream.url),
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/stream peer rejected exchange: peer says no/)
    await waitFor(() => wsGone)
  })

  it('tears an HTTP exchange down on a WebSocket frame and refuses a late body frame', async () => {
    let reached!: () => void
    const reachedUpstream = new Promise<void>((resolve) => {
      reached = resolve
    })
    const upstream = await startHttpUpstream((req) => {
      reached()
      req.resume()
    })
    const gateway = await startRawGateway((conn) => {
      void (async () => {
        await reachedUpstream
        // A WebSocket frame is meaningless here: it fails the exchange and closes the inbound
        // queue, so the body frame arriving right after teardown can no longer be accepted.
        conn.send(0x2, encodeWsFrame('binary', Buffer.from('x'), true))
        conn.send(0x2, encodeHttpBody(Buffer.from('y')))
      })()
    })

    await expect(
      openStreamExchange({
        streamUrl: gateway.url,
        request: httpRequest,
        upstream: upstreamOf(upstream.url),
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/unexpected WebSocket frame during HTTP exchange/)
  })

  it('forwards an empty final browser fragment without a zero acknowledgement', async () => {
    const messages: string[] = []
    const upstream = await startWsUpstream((socket) => {
      socket.on('message', (data) => {
        messages.push(data.toString('utf8'))
      })
    })
    const acks: number[] = []
    let gatewaySocket: WebSocket | undefined
    const gateway = await startGateway((conn) => {
      gatewaySocket = conn.socket
      conn.socket.on('message', (data, isBinary) => {
        if (!isBinary) {
          if (decodeTextFrame(data.toString('utf8')).type === 'WS_READY') {
            // A message whose final fragment is empty still terminates the message.
            conn.sendWs('text', Buffer.from('hello'), false)
            conn.sendWs('text', Buffer.alloc(0), true)
          }
          return
        }
        const frame = decodeBinaryFrame(data as Buffer)
        if (frame.kind === 'ack') {
          acks.push(frame.bytes)
        }
      })
    })

    const exchange = openStreamExchange({
      streamUrl: gateway.url,
      request: wsRequest,
      upstream: upstreamOf(upstream.url),
      signal: newSignal().signal,
    })

    await waitFor(() => messages.length === 1, 8000)
    expect(messages[0]).toBe('hello')
    await waitFor(() => acks.includes(5))
    // The empty fragment reserved no credit, so no zero-length acknowledgement may be sent.
    expect(acks).not.toContain(0)

    gatewaySocket?.close()
    await exchange
  })

  it('reports a failed request body write and closes the upstream request', async () => {
    let reached!: () => void
    const reachedUpstream = new Promise<void>((resolve) => {
      reached = resolve
    })
    let upstreamGone = false
    const upstream = await startHttpUpstream((req) => {
      reached()
      req.socket.on('close', () => {
        upstreamGone = true
      })
    })
    const gateway = await startGateway((conn) => {
      void (async () => {
        await reachedUpstream
        conn.sendBody(Buffer.from('payload'))
      })()
    })
    const spy = vi.spyOn(ClientRequest.prototype, 'write').mockImplementation((): boolean => {
      throw new Error('write exploded')
    })
    try {
      await expect(
        openStreamExchange({
          streamUrl: gateway.url,
          request: httpRequest,
          upstream: upstreamOf(upstream.url),
          signal: newSignal().signal,
        }),
      ).rejects.toThrow(/upstream request body failed/)
      await waitFor(() => upstreamGone)
    } finally {
      spy.mockRestore()
    }
  })

  it('fails the exchange when the upstream response dies mid body', async () => {
    let upstreamResponse: ServerResponse | undefined
    const upstream = await startHttpUpstream((_req, res) => {
      upstreamResponse = res
      res.writeHead(200, { 'content-length': '1024' })
      res.write('partial')
    })
    const gateway = await startGateway((conn) => {
      conn.socket.on('message', (data, isBinary) => {
        if (!isBinary && decodeTextFrame(data.toString('utf8')).type === 'HTTP_RESPONSE') {
          upstreamResponse?.destroy()
        }
      })
    })

    await expect(
      openStreamExchange({
        streamUrl: gateway.url,
        request: httpRequest,
        upstream: upstreamOf(upstream.url),
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/aborted|premature/i)
  })

  it('destroys a late upstream upgrade that completes after the gateway closed', async () => {
    let key = ''
    let upstreamSocket: Socket | undefined
    const upstream = await startHandshakeReplier(
      (incoming) => {
        key = incoming
        return ''
      },
      (request) => {
        upstreamSocket = request.socket
      },
    )
    let gatewaySocket: WebSocket | undefined
    const gateway = await startGateway((conn) => {
      gatewaySocket = conn.socket
    })
    // The wrapped real `destroy` is the OS boundary signal: the spy keeps the original behaviour.
    const destroySpy = vi.spyOn(UpstreamWebSocket.prototype, 'destroy')
    try {
      const exchange = openStreamExchange({
        streamUrl: gateway.url,
        request: wsRequest,
        upstream: upstreamOf(upstream.url),
        signal: newSignal().signal,
      })
      await waitFor(() => upstreamSocket !== undefined)
      gatewaySocket?.close()
      await exchange
      // Completing the handshake now must hand the socket to the already settled exchange, which
      // destroys it rather than adopting a connection the gateway can no longer use.
      upstreamSocket?.write(switchingProtocols(key))
      await vi.waitFor(() => {
        expect(destroySpy).toHaveBeenCalled()
      })
    } finally {
      destroySpy.mockRestore()
    }
  })

  it('aborts when cancellation lands while the proxy lookup is resolving', async () => {
    const network = getOutboundNetwork()
    const controller = new AbortController()
    let destroyed = false
    const realCreate = network.createWebSocketAgent.bind(network)
    const spy = vi.spyOn(network, 'createWebSocketAgent').mockImplementation(async (target, signal) => {
      const plan = await realCreate(target, signal)
      controller.abort()
      return {
        agent: plan.agent,
        destroy: () => {
          destroyed = true
          plan.destroy()
        },
      }
    })
    try {
      await expect(
        openStreamExchange({
          streamUrl: 'ws://127.0.0.1:1/api/harness/environment-stream/v1/ticket',
          request: httpRequest,
          upstream: { host: '127.0.0.1', port: 1 },
          signal: controller.signal,
        }),
      ).rejects.toMatchObject({ name: 'AbortError' })
    } finally {
      spy.mockRestore()
    }
    expect(destroyed).toBe(true)
  })

  it('bounds a flood of queued body frames instead of buffering unboundedly', async () => {
    let reached!: () => void
    const reachedUpstream = new Promise<void>((resolve) => {
      reached = resolve
    })
    const upstream = await startHttpUpstream((req) => {
      reached()
      req.resume()
    })
    let writes = 0
    const spy = vi.spyOn(ClientRequest.prototype, 'write').mockImplementation((): boolean => {
      writes += 1
      return false
    })
    try {
      const gateway = await startRawGateway((conn) => {
        conn.send(0x2, encodeHttpBody(Buffer.from('a')))
        void (async () => {
          await reachedUpstream
          // Backpressure stalls the pump, so the flood must hit the bounded queue, not memory.
          await waitFor(() => writes >= 1)
          for (let index = 0; index < 1025; index += 1) {
            conn.send(0x2, encodeHttpBody(Buffer.from('b')))
          }
        })()
      })

      await expect(
        openStreamExchange({
          streamUrl: gateway.url,
          request: httpRequest,
          upstream: upstreamOf(upstream.url),
          signal: newSignal().signal,
        }),
      ).rejects.toThrow(/queue limit exceeded/)
    } finally {
      spy.mockRestore()
    }
  })

  it('destroys the acquired plan when the gateway URL cannot be constructed', async () => {
    await expect(
      openStreamExchange({
        streamUrl: 'ws://127.0.0.1:1/stream#fragment',
        request: httpRequest,
        upstream: { host: '127.0.0.1', port: 1 },
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/fragment identifier/)
  })

  it('ignores a request error that follows settlement while draining', async () => {
    let upstreamRequest: IncomingMessage | undefined
    const upstream = await startHttpUpstream((req) => {
      upstreamRequest = req
      req.resume()
    })
    let writes = 0
    const spy = vi.spyOn(ClientRequest.prototype, 'write').mockImplementation((): boolean => {
      writes += 1
      return false
    })
    try {
      const gateway = await startRawGateway((conn) => {
        conn.send(0x2, encodeHttpBody(Buffer.from('a')))
        void (async () => {
          await waitFor(() => upstreamRequest !== undefined && writes >= 1)
          // The peer goes away while the pump waits for a drain that will never come.
          upstreamRequest?.socket.destroy()
        })()
      })

      await expect(
        openStreamExchange({
          streamUrl: gateway.url,
          request: httpRequest,
          upstream: upstreamOf(upstream.url),
          signal: newSignal().signal,
        }),
      ).rejects.toThrow(/upstream request failed/)
    } finally {
      spy.mockRestore()
    }
  })

  it('returns a body frame that arrives as the exchange tears down', async () => {
    let markFirst!: () => void
    const firstBody = new Promise<void>((resolve) => {
      markFirst = resolve
    })
    const upstream = await startHttpUpstream((req) => {
      req.on('data', () => markFirst())
    })
    const gateway = await startRawGateway((conn) => {
      conn.send(0x2, encodeHttpBody(Buffer.from('one')))
      void (async () => {
        await firstBody
        // The body frame and the failing frame share one write, so both are handled in a single
        // turn: the frame resolves a pending read just as the exchange settles.
        conn.write(
          Buffer.concat([
            serverFrame(0x2, encodeHttpBody(Buffer.from('two'))),
            serverFrame(0x2, encodeWsFrame('binary', Buffer.from('x'), true)),
          ]),
        )
      })()
    })

    await expect(
      openStreamExchange({
        streamUrl: gateway.url,
        request: httpRequest,
        upstream: upstreamOf(upstream.url),
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/unexpected WebSocket frame during HTTP exchange/)
  })

  it('stops forwarding once settled while waiting for send credit', async () => {
    const upstream = await startRawWsUpstream((socket) => {
      for (let index = 0; index < 5; index += 1) {
        socket.write(serverFrame(0x2, Buffer.alloc(MAX_PAYLOAD, index + 1)))
      }
    })
    const gateway = await startRawGateway((conn) => {
      void (async () => {
        await waitFor(() => conn.received.filter(isForwardedDataFrame).length >= 4, 8000)
        // The fifth fragment is blocked on credit. One turn grants it and fails the exchange, so
        // the sender must observe settlement and stop instead of forwarding the fragment.
        conn.write(
          Buffer.concat([
            serverFrame(0x2, encodeAck(MAX_PAYLOAD)),
            serverFrame(0x2, encodeHttpBody(Buffer.from('x'))),
          ]),
        )
      })()
    })

    await expect(
      openStreamExchange({
        streamUrl: gateway.url,
        request: wsRequest,
        upstream: upstreamOf(upstream.url),
        signal: newSignal().signal,
      }),
    ).rejects.toThrow(/unexpected HTTP frame during WebSocket exchange/)
  })
})

describe('progress budget', () => {
  it('reports a stall when observed progress stops', async () => {
    await expect(
      awaitProgress(() => 0, () => false, () => 'produced=0/1500 received=0/49152000 unacked=0', {
        idleMillis: 20,
        totalMillis: 5000,
      }),
    ).rejects.toThrow(/stalled.*produced=0\/1500 received=0\/49152000 unacked=0/)
  })

  it('reports continued progress when only the total budget is exhausted', async () => {
    let observed = 0
    await expect(
      awaitProgress(
        () => {
          observed += 1
          return observed
        },
        () => false,
        () => `produced=${observed}`,
        { idleMillis: 5000, totalMillis: 30 },
      ),
    ).rejects.toThrow(/still progressing.*produced=\d+/)
  })

  it('resolves once progress completes within the budget', async () => {
    let observed = 0
    await awaitProgress(
      () => {
        observed += 1
        return observed
      },
      () => observed >= 5,
      () => `observed=${observed}`,
      { idleMillis: 1000, totalMillis: 1000 },
    )
    expect(observed).toBeGreaterThanOrEqual(5)
  })
})

describe('exchange cleanup', () => {
  it('aborts before awaiting settlement so an unfinished handshake cannot hang cleanup', async () => {
    const controller = new AbortController()
    const closed: string[] = []
    let settle: () => void = () => undefined
    const settled = new Promise<void>((resolve) => {
      settle = resolve
    })
    // An exchange still in its gateway handshake settles only once the signal is aborted.
    controller.signal.addEventListener('abort', () => settle(), { once: true })

    const outcome = await Promise.race([
      abortAndSettle(controller, () => closed.push('gateway'), settled).then(() => 'settled'),
      delay(1000).then(() => 'hung'),
    ])

    expect(outcome).toBe('settled')
    expect(controller.signal.aborted).toBe(true)
    expect(closed).toEqual(['gateway'])
  })
})
