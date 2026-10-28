import { Buffer } from 'node:buffer'
import { createHash } from 'node:crypto'
import {
  createServer as createHttpServer,
  type IncomingMessage,
  type ServerResponse,
} from 'node:http'
import {
  createServer as createNetServer,
  type AddressInfo,
  type Socket,
} from 'node:net'

import { afterEach, describe, expect, it } from 'vitest'

import {
  createMuxDataConnection,
  type MuxDataConnection,
  type MuxStreamUpstream,
  type MuxViewRequest,
} from '../src/transport/mux-connection.js'
import { MUX_CONNECTION_CREDIT, MUX_STREAM_CREDIT } from '../src/transport/mux.js'
import {
  decodeFrame,
  encodeFrame,
  frameCost,
  type DataFrame,
  type MuxFrame,
  type OpenRequest,
} from '../src/transport/mux-wire.js'
import { tick, waitFor } from './helpers.js'

const VIEW_ID = '01234567-89ab-4def-8123-456789abcdef'
const OTHER_VIEW_ID = '11234567-89ab-4def-8123-456789abcdef'
const EPOCH = '7'

const cleanups: Array<() => Promise<void> | void> = []

afterEach(async () => {
  for (const cleanup of cleanups.splice(0).reverse()) {
    await cleanup()
  }
})

interface Loopback {
  host: string
  port: number
}

function listen(server: {
  listen(port: number, host: string, callback: () => void): unknown
  address(): AddressInfo | string | null
}): Promise<number> {
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve((server.address() as AddressInfo).port)))
}

function closeServer(server: { closeAllConnections?(): void; close(callback: () => void): unknown }): Promise<void> {
  server.closeAllConnections?.()
  return new Promise((resolve) => server.close(() => resolve()))
}

async function startHttp(handler: (request: IncomingMessage, response: ServerResponse) => void): Promise<Loopback> {
  const server = createHttpServer(handler)
  const port = await listen(server)
  cleanups.push(() => closeServer(server))
  return { host: '127.0.0.1', port }
}

interface UpstreamOptions {
  path?: string
  headers?: [string, string][]
  cookie?: string
  protocols?: string[]
  signal?: AbortSignal
  release?: () => void
}

function toUpstream(target: Loopback, request: MuxViewRequest, options: UpstreamOptions = {}): MuxStreamUpstream {
  return {
    host: target.host,
    port: target.port,
    path: options.path ?? request.path,
    headers: options.headers ?? [],
    cookie: options.cookie,
    protocols: options.protocols,
    signal: options.signal ?? new AbortController().signal,
    release: options.release ?? (() => {}),
  }
}

function openRequest(mode: 'HTTP' | 'WS', path: string, method = 'GET', headers: [string, string][] = []): OpenRequest {
  return { viewId: VIEW_ID, bindingEpoch: EPOCH, mode, method, path, headers }
}

interface Waiter {
  predicate(frame: MuxFrame): boolean
  resolve(frame: MuxFrame): void
}

/** Server-side logical peer: it records daemon frames and optionally returns stream credit immediately. */
class Gateway {
  readonly frames: MuxFrame[] = []
  readonly connection: MuxDataConnection
  private readonly waiters: Waiter[] = []

  constructor(resolve: (viewId: string, epoch: string, request: MuxViewRequest) => Promise<MuxStreamUpstream>) {
    this.connection = createMuxDataConnection({
      send: (raw) => this.record(raw),
      resolveView: resolve,
      readyTimeoutMillis: 2_000,
      httpFirstResponseTimeoutMillis: 2_000,
      httpIdleTimeoutMillis: 2_000,
      wsHandshakeTimeoutMillis: 2_000,
      wsCloseTimeoutMillis: 100,
    })
  }

  send(frame: MuxFrame): void {
    this.connection.receive(encodeFrame(frame))
  }

  open(streamId: number, request: OpenRequest): void {
    this.send({ type: 'OPEN', streamId, request })
  }

  window(streamId: number, credit: number): void {
    this.send({ type: 'WINDOW', streamId, credit })
  }

  async waitFor(predicate: (frame: MuxFrame) => boolean, label: string): Promise<MuxFrame> {
    const existing = this.frames.find(predicate)
    if (existing !== undefined) {
      return existing
    }
    return new Promise<MuxFrame>((resolve, reject) => {
      const timer = setTimeout(() => {
        const index = this.waiters.findIndex((waiter) => waiter.resolve === wrapped)
        if (index >= 0) {
          this.waiters.splice(index, 1)
        }
        reject(new Error(`timed out waiting for ${label}; frames=${this.frames.map((frame) => frame.type).join(',')}`))
      }, 5_000)
      timer.unref()
      const wrapped = (frame: MuxFrame): void => {
        clearTimeout(timer)
        resolve(frame)
      }
      this.waiters.push({ predicate, resolve: wrapped })
    })
  }

  async waitForClose(streamId: number): Promise<Extract<MuxFrame, { type: 'CLOSE' }>> {
    return (await this.waitFor((frame) => frame.type === 'CLOSE' && frame.streamId === streamId, 'WS CLOSE')) as Extract<
      MuxFrame,
      { type: 'CLOSE' }
    >
  }

  async waitForReset(streamId: number): Promise<Extract<MuxFrame, { type: 'RESET' }>> {
    return (await this.waitFor((frame) => frame.type === 'RESET' && frame.streamId === streamId, 'RESET')) as Extract<
      MuxFrame,
      { type: 'RESET' }
    >
  }

  private record(raw: Uint8Array): void {
    const frame = this.observe(raw)
    if (frame.type === 'DATA') {
      const credit = frameCost(frame.payload.byteLength)
      this.connection.receive(encodeFrame({ type: 'WINDOW', streamId: frame.streamId, credit }))
      this.connection.receive(encodeFrame({ type: 'WINDOW', streamId: 0, credit }))
    }
  }

  observe(raw: Uint8Array): MuxFrame {
    const frame = decodeFrame(raw)
    this.frames.push(frame)
    for (const waiter of [...this.waiters]) {
      if (waiter.predicate(frame)) {
        this.waiters.splice(this.waiters.indexOf(waiter), 1)
        waiter.resolve(frame)
      }
    }
    return frame
  }
}

function dataFrames(frames: MuxFrame[], streamId: number): DataFrame[] {
  return frames.filter((frame): frame is DataFrame => frame.type === 'DATA' && frame.streamId === streamId)
}

describe('mux data connection: real loopback HTTP', () => {
  it('runs concurrent fast and slow streams over one writer with exact large-response bytes', async () => {
    let releaseSlow!: () => void
    const slowGate = new Promise<void>((resolve) => {
      releaseSlow = resolve
    })
    let slowStarted!: () => void
    const slowReached = new Promise<void>((resolve) => {
      slowStarted = resolve
    })
    const big = Buffer.allocUnsafe(2 * 1024 * 1024 + 17)
    for (let index = 0; index < big.length; index += 1) {
      big[index] = index % 251
    }
    const upstream = await startHttp((request, response) => {
      if (request.url === '/fast') {
        response.writeHead(200, { 'content-type': 'application/octet-stream' })
        response.end(big)
        return
      }
      slowStarted()
      void slowGate.then(() => {
        response.writeHead(200, { 'content-type': 'text/plain' })
        response.write('slow-')
        response.end('tail')
      })
    })
    const releases = new Map<string, number>()
    const gateway = new Gateway(async (_viewId, _epoch, request) =>
      toUpstream(upstream, request, {
        release: () => releases.set(request.path, (releases.get(request.path) ?? 0) + 1),
      }),
    )
    let idle = 0
    const connection = createMuxDataConnection({
      send: (raw) => {
        const frame = gateway.observe(raw)
        if (frame.type === 'DATA') {
          const credit = frameCost(frame.payload.byteLength)
          connection.receive(encodeFrame({ type: 'WINDOW', streamId: frame.streamId, credit }))
          connection.receive(encodeFrame({ type: 'WINDOW', streamId: 0, credit }))
        }
      },
      resolveView: async (_viewId, _epoch, request) =>
        toUpstream(upstream, request, {
          release: () => releases.set(request.path, (releases.get(request.path) ?? 0) + 1),
        }),
      onIdle: () => {
        idle++
      },
      readyTimeoutMillis: 2_000,
      httpFirstResponseTimeoutMillis: 2_000,
      httpIdleTimeoutMillis: 2_000,
    })
    cleanups.push(() => connection.close())

    connection.receive(encodeFrame({ type: 'OPEN', streamId: 1, request: openRequest('HTTP', '/fast') }))
    connection.receive(encodeFrame({ type: 'OPEN', streamId: 2, request: openRequest('HTTP', '/slow') }))
    await waitFor(() => gateway.frames.filter((frame) => frame.type === 'READY').length === 2, 5_000)
    connection.receive(encodeFrame({ type: 'END', streamId: 1 }))
    connection.receive(encodeFrame({ type: 'END', streamId: 2 }))
    await slowReached
    await waitFor(() => dataFrames(gateway.frames, 1).length > 0, 5_000)
    await waitFor(() => gateway.frames.some((frame) => frame.type === 'END' && frame.streamId === 1), 5_000)
    connection.receive(encodeFrame({ type: 'COMPLETE', streamId: 1 }))
    await waitFor(() => connection.activeStreamCount() === 1, 5_000)
    await waitFor(() => releases.get('/fast') === 1, 5_000)
    expect(idle).toBe(0)

    releaseSlow()
    await waitFor(() => gateway.frames.some((frame) => frame.type === 'END' && frame.streamId === 2), 5_000)
    const slow = dataFrames(gateway.frames, 2)
      .map((frame) => frame.payload.toString())
      .join('')
    expect(slow).toBe('slow-tail')
    const all = Buffer.concat(dataFrames(gateway.frames, 1).map((frame) => Buffer.from(frame.payload)))
    expect(all.byteLength).toBe(big.byteLength)
    expect(createHash('sha256').update(all).digest('hex')).toBe(createHash('sha256').update(big).digest('hex'))
    connection.receive(encodeFrame({ type: 'COMPLETE', streamId: 2 }))
    await waitFor(() => idle === 1, 5_000)
    expect(connection.activeStreamCount()).toBe(0)
    expect(releases.get('/slow')).toBe(1)
  })

  it('lets the response progress before the request half is complete', async () => {
    let seenBody = Buffer.alloc(0)
    let requestEnded!: () => void
    const upstreamEnded = new Promise<void>((resolve) => {
      requestEnded = resolve
    })
    const upstream = await startHttp((request, response) => {
      response.writeHead(200, { 'content-type': 'text/plain' })
      response.write('early-')
      request.on('data', (chunk: Buffer) => {
        seenBody = Buffer.concat([seenBody, chunk])
      })
      request.on('end', () => {
        requestEnded()
        response.end('late')
      })
    })
    const gateway = new Gateway(async (_viewId, _epoch, request) => toUpstream(upstream, request))
    let idle = false
    const connection = createMuxDataConnection({
      send: (raw) => {
        const frame = gateway.observe(raw)
        if (frame.type === 'DATA') {
          const credit = frameCost(frame.payload.byteLength)
          connection.receive(encodeFrame({ type: 'WINDOW', streamId: frame.streamId, credit }))
          connection.receive(encodeFrame({ type: 'WINDOW', streamId: 0, credit }))
        }
      },
      resolveView: async (_viewId, _epoch, request) => toUpstream(upstream, request),
      onIdle: () => {
        idle = true
      },
      readyTimeoutMillis: 2_000,
      httpFirstResponseTimeoutMillis: 2_000,
      httpIdleTimeoutMillis: 2_000,
    })
    cleanups.push(() => connection.close())
    connection.receive(encodeFrame({ type: 'OPEN', streamId: 1, request: openRequest('HTTP', '/half', 'POST') }))
    await gateway.waitFor((frame) => frame.type === 'READY', 'READY')
    connection.receive(encodeFrame({ type: 'DATA', streamId: 1, flags: { kind: 'http' }, payload: Buffer.from('part') }))
    await waitFor(() => gateway.frames.some((frame) => frame.type === 'HEADERS'), 5_000)
    await waitFor(() => dataFrames(gateway.frames, 1).some((frame) => frame.payload.toString() === 'early-'), 5_000)
    // The response is already flowing even though Studio has not sent request END yet.
    const beforeEnd = dataFrames(gateway.frames, 1)
      .map((frame) => frame.payload.toString())
      .join('')
    expect(beforeEnd).toBe('early-')

    connection.receive(encodeFrame({ type: 'END', streamId: 1 }))
    await upstreamEnded
    await waitFor(() => gateway.frames.some((frame) => frame.type === 'END'), 5_000)
    expect(seenBody.toString()).toBe('part')
    const afterEnd = dataFrames(gateway.frames, 1)
      .map((frame) => frame.payload.toString())
      .join('')
    expect(afterEnd).toBe('early-late')
    connection.receive(encodeFrame({ type: 'COMPLETE', streamId: 1 }))
    await waitFor(() => idle, 5_000)
  })

  it('sends only the resolver-provided path and filtered headers plus the private cookie', async () => {
    let seen: IncomingMessage | undefined
    const upstream = await startHttp((request, response) => {
      seen = request
      request.resume()
      response.writeHead(200, { 'content-type': 'text/plain' })
      response.end('ok')
    })
    const viewPath = `/__kk/ide/view/${VIEW_ID}/${EPOCH}/asset.js?x=1`
    const gateway = new Gateway(async (_viewId, _epoch, request) =>
      toUpstream(upstream, request, {
        path: viewPath,
        headers: [['x-service', 'yes']],
        cookie: 'kk-session=private',
      }),
    )
    const connection = createMuxDataConnection({
      send: (raw) => gateway.observe(raw),
      resolveView: async (_viewId, _epoch, request) =>
        toUpstream(upstream, request, {
          path: viewPath,
          headers: [['x-service', 'yes']],
          cookie: 'kk-session=private',
        }),
      readyTimeoutMillis: 2_000,
      httpFirstResponseTimeoutMillis: 2_000,
    })
    cleanups.push(() => connection.close())
    connection.receive(
      encodeFrame({
        type: 'OPEN',
        streamId: 1,
        request: openRequest('HTTP', '/browser/path', 'GET', [
          ['host', 'evil.example'],
          ['authorization', 'Bearer secret'],
          ['x-browser', 'ignored'],
        ]),
      }),
    )
    await gateway.waitFor((frame) => frame.type === 'HEADERS', 'HEADERS')
    await gateway.waitFor((frame) => frame.type === 'END', 'response END')
    await waitFor(() => seen !== undefined, 5_000)
    expect(seen?.url).toBe(viewPath)
    expect(seen?.headers.host).toBe(`127.0.0.1:${upstream.port}`)
    expect(seen?.headers.cookie).toBe('kk-session=private')
    expect(seen?.headers['x-service']).toBe('yes')
    expect(seen?.headers.authorization).toBeUndefined()
    expect(seen?.headers['x-browser']).toBeUndefined()
    connection.receive(encodeFrame({ type: 'END', streamId: 1 }))
    await tick(5)
    connection.receive(encodeFrame({ type: 'COMPLETE', streamId: 1 }))
    await waitFor(() => connection.activeStreamCount() === 0, 5_000)
  })

  it('resets an incomplete response and a cancelled view without a fake END or replay', async () => {
    let cancelled!: () => void
    const cancelSeen = new Promise<void>((resolve) => {
      cancelled = resolve
    })
    const upstream = await startHttp((request, response) => {
      if (request.url === '/broken') {
        response.writeHead(200, { 'content-type': 'text/plain' })
        response.write('partial')
        setImmediate(() => response.destroy())
        return
      }
      response.writeHead(200, { 'content-type': 'text/plain' })
      response.write('waiting')
      response.on('close', cancelled)
    })
    let releases = 0
    const gateway = new Gateway(async (_viewId, _epoch, request) =>
      toUpstream(upstream, request, { release: () => (releases += 1) }),
    )
    const connection = createMuxDataConnection({
      send: (raw) => {
        const frame = gateway.observe(raw)
        if (frame.type === 'DATA') {
          const credit = frameCost(frame.payload.byteLength)
          connection.receive(encodeFrame({ type: 'WINDOW', streamId: frame.streamId, credit }))
          connection.receive(encodeFrame({ type: 'WINDOW', streamId: 0, credit }))
        }
      },
      resolveView: async (_viewId, _epoch, request) =>
        toUpstream(upstream, request, { release: () => (releases += 1) }),
      readyTimeoutMillis: 2_000,
      httpFirstResponseTimeoutMillis: 2_000,
      httpIdleTimeoutMillis: 2_000,
    })
    cleanups.push(() => connection.close())
    connection.receive(encodeFrame({ type: 'OPEN', streamId: 1, request: openRequest('HTTP', '/broken') }))
    await gateway.waitFor((frame) => frame.type === 'READY', 'READY')
    connection.receive(encodeFrame({ type: 'END', streamId: 1 }))
    const reset = await gateway.waitForReset(1)
    expect(reset.reset.code).toBe('RESPONSE_FAILED')
    expect(gateway.frames.some((frame) => frame.type === 'END' && frame.streamId === 1)).toBe(false)
    await waitFor(() => connection.activeStreamCount() === 0, 5_000)
    await waitFor(() => releases === 1, 5_000)

    connection.receive(encodeFrame({ type: 'OPEN', streamId: 2, request: openRequest('HTTP', '/cancel') }))
    await gateway.waitFor((frame) => frame.type === 'READY' && frame.streamId === 2, 'READY 2')
    connection.receive(encodeFrame({ type: 'END', streamId: 2 }))
    await gateway.waitFor((frame) => frame.type === 'DATA' && frame.streamId === 2, 'DATA 2')
    expect(await connection.resetView(OTHER_VIEW_ID, EPOCH)).toBe(0)
    expect(await connection.resetView(VIEW_ID, EPOCH)).toBe(1)
    const viewReset = await gateway.waitFor((frame) => frame.type === 'RESET' && frame.streamId === 2, 'view RESET')
    expect(viewReset.type === 'RESET' ? viewReset.reset.code : '').toBe('VIEW_CLOSED')
    await cancelSeen
    await waitFor(() => connection.activeStreamCount() === 0, 5_000)
    expect(releases).toBe(2)
  })

  it('times out a resolver that never produces READY and releases the late upstream', async () => {
    let release!: (target: MuxStreamUpstream) => void
    let releases = 0
    const pending = new Promise<MuxStreamUpstream>((resolve) => {
      release = resolve
    })
    const gateway = new Gateway(async () => pending)
    const connection = createMuxDataConnection({
      send: (raw) => gateway.observe(raw),
      resolveView: async () => pending,
      readyTimeoutMillis: 80,
    })
    cleanups.push(() => connection.close())
    connection.receive(encodeFrame({ type: 'OPEN', streamId: 1, request: openRequest('HTTP', '/pending') }))
    const reset = await gateway.waitForReset(1)
    expect(reset.reset.code).toBe('READY_TIMEOUT')
    expect(gateway.frames.some((frame) => frame.type === 'READY')).toBe(false)
    await waitFor(() => connection.activeStreamCount() === 0, 5_000)

    release({
      host: '127.0.0.1',
      port: 1,
      path: '/late',
      headers: [],
      signal: new AbortController().signal,
      release: () => {
        releases++
      },
    })
    await tick(5)
    expect(releases).toBe(1)
    expect(gateway.frames.some((frame) => frame.type === 'HEADERS')).toBe(false)
  })

  it('resets only the affected stream when the resolver lifecycle aborts', async () => {
    let cancelled!: () => void
    const cancelSeen = new Promise<void>((resolve) => {
      cancelled = resolve
    })
    const upstream = await startHttp((_request, response) => {
      response.writeHead(200, { 'content-type': 'text/plain' })
      response.write('open')
      response.on('close', cancelled)
    })
    const controller = new AbortController()
    let releases = 0
    const gateway = new Gateway(async (_viewId, _epoch, request) =>
      toUpstream(upstream, request, {
        signal: controller.signal,
        release: () => {
          releases++
        },
      }),
    )
    const connection = createMuxDataConnection({
      send: (raw) => {
        const frame = gateway.observe(raw)
        if (frame.type === 'DATA') {
          const credit = frameCost(frame.payload.byteLength)
          connection.receive(encodeFrame({ type: 'WINDOW', streamId: frame.streamId, credit }))
          connection.receive(encodeFrame({ type: 'WINDOW', streamId: 0, credit }))
        }
      },
      resolveView: async (_viewId, _epoch, request) =>
        toUpstream(upstream, request, {
          signal: controller.signal,
          release: () => {
            releases++
          },
        }),
      readyTimeoutMillis: 2_000,
      httpFirstResponseTimeoutMillis: 2_000,
    })
    cleanups.push(() => connection.close())
    connection.receive(encodeFrame({ type: 'OPEN', streamId: 1, request: openRequest('HTTP', '/live') }))
    await gateway.waitFor((frame) => frame.type === 'READY', 'READY')
    connection.receive(encodeFrame({ type: 'END', streamId: 1 }))
    await gateway.waitFor((frame) => frame.type === 'DATA', 'DATA')
    controller.abort()
    const reset = await gateway.waitForReset(1)
    expect(reset.reset.code).toBe('VIEW_CLOSED')
    await cancelSeen
    await waitFor(() => connection.activeStreamCount() === 0, 5_000)
    expect(releases).toBe(1)
  })

  it('fails the connection without retrying when the first physical write fails', async () => {
    const upstream = await startHttp((_request, response) => response.end('never'))
    let releases = 0
    const gateway = new Gateway(async (_viewId, _epoch, request) =>
      toUpstream(upstream, request, { release: () => (releases += 1) }),
    )
    const connection = createMuxDataConnection({
      send: () => {
        throw new Error('physical writer is gone')
      },
      resolveView: async (_viewId, _epoch, request) =>
        toUpstream(upstream, request, { release: () => (releases += 1) }),
      onFatal: () => {
        // The physical owner observes the terminal state and closes the socket.
      },
      readyTimeoutMillis: 2_000,
    })
    cleanups.push(() => connection.close())
    connection.receive(encodeFrame({ type: 'OPEN', streamId: 1, request: openRequest('HTTP', '/fail') }))
    await waitFor(() => connection.isClosed(), 5_000)
    await waitFor(() => connection.activeStreamCount() === 0, 5_000)
    await waitFor(() => releases === 1, 5_000)
    expect(gateway.frames).toHaveLength(0)
  })

  it('returns inbound WINDOW credit only after the local request body is actually consumed', async () => {
    let held: IncomingMessage | null = null
    let received = 0
    const server = createHttpServer((request, response) => {
      held = request
      request.on('end', () => {
        response.writeHead(200, { 'content-type': 'text/plain' })
        response.end('ok')
      })
    })
    const port = await listen(server)
    cleanups.push(() => closeServer(server))
    const upstream: Loopback = { host: '127.0.0.1', port }
    const gateway = new Gateway(async (_viewId, _epoch, request) => toUpstream(upstream, request))
    const connection = createMuxDataConnection({
      send: (raw) => gateway.observe(raw),
      resolveView: async (_viewId, _epoch, request) => toUpstream(upstream, request),
      readyTimeoutMillis: 5_000,
      httpFirstResponseTimeoutMillis: 20_000,
      httpIdleTimeoutMillis: 20_000,
    })
    cleanups.push(() => connection.close())
    connection.receive(encodeFrame({ type: 'OPEN', streamId: 1, request: openRequest('HTTP', '/upload', 'POST') }))
    await gateway.waitFor((frame) => frame.type === 'READY', 'READY')

    const chunk = Buffer.alloc(64 * 1024, 0x61)
    const cost = frameCost(chunk.byteLength)
    const totalChunks = 192

    const windowFrames = (): Array<Extract<MuxFrame, { type: 'WINDOW' }>> =>
      gateway.frames.filter((frame): frame is Extract<MuxFrame, { type: 'WINDOW' }> => frame.type === 'WINDOW')
    const available = (streamId: number): number => {
      let credit = streamId === 0 ? MUX_CONNECTION_CREDIT : MUX_STREAM_CREDIT
      for (const frame of windowFrames()) {
        if (frame.streamId === streamId) {
          credit += frame.credit
        }
      }
      return credit - sent * cost
    }
    let sent = 0
    const canSend = (): boolean => available(0) >= cost && available(1) >= cost

    let stalled = false
    while (sent < totalChunks) {
      if (!canSend()) {
        const before = windowFrames().length
        try {
          await waitFor(() => canSend(), 250)
        } catch {
          expect(windowFrames().length).toBe(before)
          stalled = true
          break
        }
      }
      connection.receive(encodeFrame({ type: 'DATA', streamId: 1, flags: { kind: 'http' }, payload: chunk }))
      sent++
    }
    expect(stalled).toBe(true)
    expect(sent).toBeGreaterThan(0)
    expect(held).not.toBeNull()

    const windowsBeforeResume = windowFrames().length
    held!.on('data', (chunk: Buffer) => {
      received += chunk.byteLength
    })
    held!.resume()
    await waitFor(() => windowFrames().length > windowsBeforeResume, 5_000)
    while (sent < totalChunks) {
      await waitFor(canSend, 5_000)
      connection.receive(encodeFrame({ type: 'DATA', streamId: 1, flags: { kind: 'http' }, payload: chunk }))
      sent++
    }
    connection.receive(encodeFrame({ type: 'END', streamId: 1 }))
    await waitFor(() => gateway.frames.some((frame) => frame.type === 'HEADERS'), 10_000)
    await waitFor(() => gateway.frames.some((frame) => frame.type === 'END' && frame.streamId === 1), 10_000)
    await waitFor(() => received === totalChunks * chunk.byteLength, 10_000)
    connection.receive(encodeFrame({ type: 'COMPLETE', streamId: 1 }))
    await waitFor(() => connection.activeStreamCount() === 0, 5_000)
  }, 30_000)
})

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'

interface RawFrame {
  fin: boolean
  opcode: number
  payload: Buffer
}

class RawWebSocketUpstream {
  readonly frames: RawFrame[] = []
  private readonly waiters: Array<{ predicate(frame: RawFrame): boolean; resolve(frame: RawFrame): void }> = []
  private buffer = Buffer.alloc(0)
  private readonly sockets = new Set<Socket>()

  constructor(private readonly socket: Socket) {
    this.sockets.add(socket)
    socket.on('close', () => this.sockets.delete(socket))
    socket.on('error', () => undefined)
    socket.on('data', (chunk) => this.receive(chunk))
  }

  sendText(payload: string, fin: boolean, continuation = false): void {
    this.write(continuation ? 0x0 : 0x1, Buffer.from(payload), fin)
  }

  sendBinary(payload: Buffer, fin: boolean, continuation = false): void {
    this.write(continuation ? 0x0 : 0x2, payload, fin)
  }

  sendClose(code: number, reason: string): void {
    const payload = Buffer.allocUnsafe(2 + Buffer.byteLength(reason))
    payload.writeUInt16BE(code, 0)
    payload.write(reason, 2)
    this.write(0x8, payload, true)
  }

  async waitFor(predicate: (frame: RawFrame) => boolean, label: string): Promise<RawFrame> {
    const existing = this.frames.find(predicate)
    if (existing !== undefined) {
      return existing
    }
    return new Promise<RawFrame>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`timed out waiting for ${label}`)), 5_000)
      timer.unref()
      this.waiters.push({
        predicate,
        resolve: (frame) => {
          clearTimeout(timer)
          resolve(frame)
        },
      })
    })
  }

  destroy(): void {
    for (const socket of this.sockets) {
      socket.destroy()
    }
    this.sockets.clear()
  }

  private write(opcode: number, payload: Buffer, fin: boolean): void {
    const length = payload.byteLength
    const extended = length < 126 ? 0 : length <= 0xffff ? 2 : 8
    const frame = Buffer.allocUnsafe(2 + extended + length)
    frame[0] = (fin ? 0x80 : 0) | opcode
    if (extended === 0) {
      frame[1] = length
    } else if (extended === 2) {
      frame[1] = 126
      frame.writeUInt16BE(length, 2)
    } else {
      frame[1] = 127
      frame.writeBigUInt64BE(BigInt(length), 2)
    }
    payload.copy(frame, 2 + extended)
    this.socket.write(frame)
  }

  private receive(chunk: Buffer): void {
    this.buffer = this.buffer.length === 0 ? chunk : Buffer.concat([this.buffer, chunk])
    for (;;) {
      if (this.buffer.length < 2) {
        return
      }
      const first = this.buffer[0]!
      const second = this.buffer[1]!
      const fin = (first & 0x80) !== 0
      const opcode = first & 0x0f
      let length = second & 0x7f
      let offset = 2
      if (length === 126) {
        if (this.buffer.length < 4) {
          return
        }
        length = this.buffer.readUInt16BE(2)
        offset = 4
      } else if (length === 127) {
        if (this.buffer.length < 10) {
          return
        }
        length = Number(this.buffer.readBigUInt64BE(2))
        offset = 10
      }
      if ((second & 0x80) === 0) {
        throw new Error('client frame was not masked')
      }
      if (this.buffer.length < offset + 4 + length) {
        return
      }
      const mask = this.buffer.subarray(offset, offset + 4)
      offset += 4
      const payload = Buffer.allocUnsafe(length)
      for (let index = 0; index < length; index += 1) {
        payload[index] = this.buffer[offset + index]! ^ mask[index & 3]!
      }
      this.buffer = this.buffer.subarray(offset + length)
      const frame = { fin, opcode, payload }
      this.frames.push(frame)
      for (const waiter of [...this.waiters]) {
        if (waiter.predicate(frame)) {
          this.waiters.splice(this.waiters.indexOf(waiter), 1)
          waiter.resolve(frame)
        }
      }
    }
  }
}

interface RawWebSocketServer {
  upstream: Loopback
  waitOpen(): Promise<RawWebSocketUpstream>
  close(): Promise<void>
}

async function startRawWebSocket(onOpen: (socket: RawWebSocketUpstream) => void): Promise<RawWebSocketServer> {
  const sockets = new Set<Socket>()
  let opened: RawWebSocketUpstream | undefined
  let resolveOpen!: (socket: RawWebSocketUpstream) => void
  const openPromise = new Promise<RawWebSocketUpstream>((resolve) => {
    resolveOpen = resolve
  })
  const server = createHttpServer()
  server.on('upgrade', (request, socket) => {
    sockets.add(socket)
    const key = String(request.headers['sec-websocket-key'] ?? '')
    const accept = createHash('sha1').update(key + WS_GUID).digest('base64')
    const selected = request.headers['sec-websocket-protocol']
    socket.write(
      'HTTP/1.1 101 Switching Protocols\r\n' +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        `Sec-WebSocket-Accept: ${accept}\r\n` +
        (selected === undefined ? '' : `Sec-WebSocket-Protocol: ${String(selected).split(',')[0]!.trim()}\r\n`) +
        '\r\n',
    )
    opened = new RawWebSocketUpstream(socket)
    resolveOpen(opened)
    onOpen(opened)
  })
  const port = await listen(server)
  cleanups.push(async () => {
    opened?.destroy()
    for (const socket of sockets) {
      socket.destroy()
    }
    await closeServer(server)
  })
  return {
    upstream: { host: '127.0.0.1', port },
    waitOpen: () => openPromise,
    close: async () => {
      opened?.destroy()
      await closeServer(server)
    },
  }
}

describe('mux data connection: streaming loopback WebSocket', () => {
  it("preserves both directions' fragment type and FIN and relays close", async () => {
    let raw!: RawWebSocketUpstream
    const upstream = await startRawWebSocket((socket) => {
      raw = socket
    })
    let releases = 0
    const gateway = new Gateway(async (_viewId, _epoch, request) =>
      toUpstream(upstream.upstream, request, {
        protocols: ['vscode-api'],
        release: () => {
          releases++
        },
      }),
    )
    let idle = false
    const connection = createMuxDataConnection({
      send: (message) => {
        const frame = gateway.observe(message)
        if (frame.type === 'DATA') {
          const credit = frameCost(frame.payload.byteLength)
          connection.receive(encodeFrame({ type: 'WINDOW', streamId: frame.streamId, credit }))
          connection.receive(encodeFrame({ type: 'WINDOW', streamId: 0, credit }))
        }
      },
      resolveView: async (_viewId, _epoch, request) =>
        toUpstream(upstream.upstream, request, {
          protocols: ['vscode-api'],
          release: () => {
            releases++
          },
        }),
      onIdle: () => {
        idle = true
      },
      readyTimeoutMillis: 2_000,
      wsHandshakeTimeoutMillis: 2_000,
      wsCloseTimeoutMillis: 1_000,
    })
    cleanups.push(async () => {
      await connection.close()
      await upstream.close()
    })
    connection.receive(
      encodeFrame({
        type: 'OPEN',
        streamId: 1,
        request: openRequest('WS', '/ws', 'GET', [['sec-websocket-protocol', 'vscode-api']]),
      }),
    )
    await gateway.waitFor((frame) => frame.type === 'READY', 'READY')
    const headers = (await gateway.waitFor((frame) => frame.type === 'HEADERS', 'HEADERS')) as Extract<
      MuxFrame,
      { type: 'HEADERS' }
    >
    expect(headers.headers.status).toBe(101)
    expect(headers.headers.headers).toContainEqual(['sec-websocket-protocol', 'vscode-api'])
    raw = await upstream.waitOpen()

    connection.receive(
      encodeFrame({ type: 'DATA', streamId: 1, flags: { kind: 'ws', text: true, fin: false }, payload: Buffer.from('frag-') }),
    )
    connection.receive(
      encodeFrame({ type: 'DATA', streamId: 1, flags: { kind: 'ws', text: true, fin: true }, payload: Buffer.from('ment') }),
    )
    const first = await raw.waitFor((frame) => frame.opcode === 0x1, 'text start')
    const second = await raw.waitFor((frame) => frame.opcode === 0x0, 'text continuation')
    expect(first.fin).toBe(false)
    expect(second.fin).toBe(true)
    expect(first.payload.toString() + second.payload.toString()).toBe('frag-ment')

    raw.sendText('re', false)
    raw.sendText('ply', true, true)
    const replyOne = (await gateway.waitFor(
      (frame) => frame.type === 'DATA' && frame.payload.toString() === 're',
      'reply fragment one',
    )) as DataFrame
    const replyTwo = (await gateway.waitFor(
      (frame) => frame.type === 'DATA' && frame.payload.toString() === 'ply',
      'reply fragment two',
    )) as DataFrame
    expect(replyOne.flags).toEqual({ kind: 'ws', text: true, fin: false })
    expect(replyTwo.flags).toEqual({ kind: 'ws', text: true, fin: true })

    connection.receive(encodeFrame({ type: 'CLOSE', streamId: 1, close: { code: 1000, reason: 'done' } }))
    const clientClose = await raw.waitFor((frame) => frame.opcode === 0x8, 'upstream close')
    expect(clientClose.payload.readUInt16BE(0)).toBe(1000)
    const echoed = await gateway.waitForClose(1)
    expect(echoed.close).toEqual({ code: 1000, reason: 'done' })
    await waitFor(() => idle, 5_000)
    expect(connection.activeStreamCount()).toBe(0)
    expect(releases).toBe(1)
  })

  it('relays an upstream close and resets when the peer never answers the handshake', async () => {
    const upstream = await startRawWebSocket((socket) => {
      socket.sendClose(4001, 'gone')
    })
    let releases = 0
    const gateway = new Gateway(async (_viewId, _epoch, request) =>
      toUpstream(upstream.upstream, request, { release: () => (releases += 1) }),
    )
    const connection = createMuxDataConnection({
      send: (message) => {
        gateway.observe(message)
      },
      resolveView: async (_viewId, _epoch, request) =>
        toUpstream(upstream.upstream, request, { release: () => (releases += 1) }),
      readyTimeoutMillis: 2_000,
      wsHandshakeTimeoutMillis: 2_000,
      wsCloseTimeoutMillis: 80,
    })
    cleanups.push(async () => {
      await connection.close()
      await upstream.close()
    })
    connection.receive(encodeFrame({ type: 'OPEN', streamId: 1, request: openRequest('WS', '/ws') }))
    const close = await gateway.waitForClose(1)
    expect(close.close).toEqual({ code: 4001, reason: 'gone' })
    const reset = await gateway.waitForReset(1)
    expect(reset.reset.code).toBe('WS_CLOSE_TIMEOUT')
    await waitFor(() => connection.activeStreamCount() === 0, 5_000)
    expect(releases).toBe(1)
  })

  it('times out a stalled local WebSocket handshake and releases the view stream', async () => {
    const sockets = new Set<Socket>()
    const server = createNetServer((socket) => {
      sockets.add(socket)
      socket.resume()
      socket.on('close', () => sockets.delete(socket))
      // Accept TCP but never complete the HTTP upgrade.
    })
    const port = await listen(server)
    cleanups.push(async () => {
      for (const socket of sockets) {
        socket.destroy()
      }
      await closeServer(server)
    })
    const upstream: Loopback = { host: '127.0.0.1', port }
    let releases = 0
    const gateway = new Gateway(async (_viewId, _epoch, request) =>
      toUpstream(upstream, request, {
        protocols: [],
        release: () => {
          releases++
        },
      }),
    )
    const connection = createMuxDataConnection({
      send: (raw) => gateway.observe(raw),
      resolveView: async (_viewId, _epoch, request) =>
        toUpstream(upstream, request, {
          protocols: [],
          release: () => {
            releases++
          },
        }),
      readyTimeoutMillis: 2_000,
      wsHandshakeTimeoutMillis: 80,
    })
    cleanups.push(() => connection.close())
    connection.receive(encodeFrame({ type: 'OPEN', streamId: 1, request: openRequest('WS', '/ws') }))
    const reset = await gateway.waitForReset(1)
    expect(reset.reset.code).toBe('UPSTREAM_FAILED')
    expect(gateway.frames.some((frame) => frame.type === 'READY')).toBe(false)
    await waitFor(() => connection.activeStreamCount() === 0, 5_000)
    await waitFor(() => releases === 1, 5_000)
    await waitFor(() => sockets.size === 0, 5_000)
  })
})
