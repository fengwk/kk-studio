import { Buffer } from 'node:buffer'
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import type { AddressInfo } from 'node:net'

import { afterEach, describe, expect, it } from 'vitest'
import { WebSocket, WebSocketServer } from 'ws'

import { decodeDataBind, type DataBind } from '../src/protocol.js'
import {
  MUX_DATA_MESSAGE_BYTES,
  MUX_DATA_PING_INTERVAL_MILLIS,
  MUX_DATA_PONG_TIMEOUT_MILLIS,
  createMuxUpstreamService,
  dataBindWebSocketUrl,
  type MuxUpstreamService,
} from '../src/transport/mux-upstream.js'
import type { MuxStreamUpstream, MuxViewRequest, MuxViewResolver } from '../src/transport/mux-connection.js'
import { decodeFrame, encodeFrame, frameCost, type MuxFrame } from '../src/transport/mux-wire.js'
import { tick, waitFor } from './helpers.js'

const VIEW_ID = '01234567-89ab-4def-8123-456789abcdef'
const EPOCH = '11'
const TICKET_1 = 'a'.repeat(43)
const TICKET_2 = 'b'.repeat(43)

const cleanups: Array<() => Promise<void> | void> = []

afterEach(async () => {
  for (const cleanup of cleanups.splice(0).reverse()) {
    await cleanup()
  }
})

function listen(server: Server): Promise<number> {
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve((server.address() as AddressInfo).port)))
}

function closeServer(server: Server): Promise<void> {
  server.closeAllConnections?.()
  return new Promise((resolve) => server.close(() => resolve()))
}

interface HttpTarget {
  host: string
  port: number
  requests: string[]
}

async function startHttpHandler(
  handler: (request: IncomingMessage, response: ServerResponse) => void,
): Promise<HttpTarget> {
  const requests: string[] = []
  const server = createServer((request, response) => {
    requests.push(request.url ?? '')
    handler(request, response)
  })
  const port = await listen(server)
  cleanups.push(() => closeServer(server))
  return { host: '127.0.0.1', port, requests }
}

async function startHttp(body: string): Promise<HttpTarget> {
  return startHttpHandler((request, response) => {
    request.resume()
    request.on('end', () => {
      response.writeHead(200, { 'content-type': 'text/plain' })
      response.end(`${body}:${request.url ?? ''}`)
    })
  })
}

interface StreamOptions {
  protocols?: string[]
  signal?: AbortSignal
  release?: () => void
}

/** Mirrors the IDE service result: view-scoped path/headers plus lifecycle signal and release. */
function streamFor(
  target: { host: string; port: number },
  request: MuxViewRequest,
  options: StreamOptions = {},
): MuxStreamUpstream {
  return {
    host: target.host,
    port: target.port,
    path: request.path,
    headers: [],
    protocols: options.protocols,
    signal: options.signal ?? new AbortController().signal,
    release: options.release ?? (() => {}),
  }
}

function resolverFor(target: { host: string; port: number }, onRelease?: () => void): MuxViewResolver {
  return async (_viewId, _epoch, request) =>
    streamFor(target, request, {
      release: onRelease,
    })
}

interface Peer {
  socket: WebSocket
  frames: MuxFrame[]
  send(frame: MuxFrame): void
  waitFor(predicate: (frame: MuxFrame) => boolean, label: string): Promise<MuxFrame>
}

interface DataServer {
  studioUrl: string
  streamUrl: string
  peers: Peer[]
  requestUrls: string[]
  pingCount(): number
  waitPeer(index: number): Promise<Peer>
  close(): Promise<void>
}

async function startDataServer(
  options: { autoPong?: boolean; onPeer?: (peer: Peer) => void } = {},
): Promise<DataServer> {
  const server = createServer()
  const wss = new WebSocketServer({
    noServer: true,
    perMessageDeflate: false,
    maxPayload: MUX_DATA_MESSAGE_BYTES,
    autoPong: options.autoPong ?? true,
  })
  const peers: Peer[] = []
  const requestUrls: string[] = []
  let pings = 0
  server.on('upgrade', (request, socket, head) => {
    requestUrls.push(request.url ?? '')
    wss.handleUpgrade(request, socket, head, (ws) => {
      ws.on('ping', () => {
        pings++
      })
      const peer: Peer = {
        socket: ws,
        frames: [],
        send(frame) {
          ws.send(encodeFrame(frame), { binary: true })
        },
        async waitFor(predicate, label) {
          const existing = this.frames.find(predicate)
          if (existing !== undefined) {
            return existing
          }
          const deadline = Date.now() + 5_000
          while (Date.now() < deadline) {
            await tick()
            const match = this.frames.find(predicate)
            if (match !== undefined) {
              return match
            }
          }
          throw new Error(`timed out waiting for ${label}; got ${this.frames.map((frame) => frame.type).join(',')}`)
        },
      }
      ws.on('message', (data, isBinary) => {
        if (!isBinary) {
          return
        }
        const frame = decodeFrame(Buffer.isBuffer(data) ? data : Buffer.from(data as Uint8Array))
        peer.frames.push(frame)
        if (frame.type === 'DATA') {
          const credit = frameCost(frame.payload.byteLength)
          peer.send({ type: 'WINDOW', streamId: frame.streamId, credit })
          peer.send({ type: 'WINDOW', streamId: 0, credit })
        }
      })
      peers.push(peer)
      options.onPeer?.(peer)
    })
  })
  const port = await listen(server)
  const studioUrl = `http://127.0.0.1:${port}`
  const data: DataServer = {
    studioUrl,
    streamUrl: `${studioUrl}/api/harness/environment-data/v2/environment`,
    peers,
    requestUrls,
    pingCount: () => pings,
    waitPeer: async (index) => {
      await waitFor(() => peers.length > index, 5_000)
      return peers[index]!
    },
    async close() {
      for (const peer of peers) {
        peer.socket.terminate()
      }
      await new Promise<void>((resolve) => wss.close(() => resolve()))
      await closeServer(server)
    },
  }
  cleanups.push(() => data.close())
  return data
}

function binding(generation: string, ticket: string, streamUrl: string): DataBind {
  return { generation, ticket, streamUrl }
}

function httpOpen(path: string): Extract<MuxFrame, { type: 'OPEN' }>['request'] {
  return { viewId: VIEW_ID, bindingEpoch: EPOCH, mode: 'HTTP', method: 'GET', path, headers: [] }
}

describe('mux upstream service', () => {
  it('uses the controlled same-origin ticket endpoint and completes one HTTP stream', async () => {
    const data = await startDataServer()
    const upstream = await startHttp('ok')
    let idle = 0
    let releases = 0
    const closes: Array<Error | null> = []
    const service = createMuxUpstreamService({
      studioUrl: data.studioUrl,
      resolveView: resolverFor(upstream, () => {
        releases++
      }),
      onIdle: () => {
        idle++
      },
      onDataClose: (error) => {
        closes.push(error)
      },
      connectTimeoutMillis: 2_000,
    })
    cleanups.push(() => service.shutdown())
    const controller = new AbortController()
    const bind = service.bind(binding('1', TICKET_1, data.streamUrl), controller.signal)
    const peer = await data.waitPeer(0)
    expect(new URL(data.requestUrls[0]!, data.studioUrl).searchParams.get('ticket')).toBe(TICKET_1)
    expect(data.requestUrls[0]).not.toContain('data-stream')
    expect(service.activeStreamCount()).toBe(0)

    peer.send({ type: 'OPEN', streamId: 1, request: httpOpen('/hello') })
    await peer.waitFor((frame) => frame.type === 'READY', 'READY')
    expect(service.activeStreamCount()).toBe(1)
    peer.send({ type: 'END', streamId: 1 })
    await peer.waitFor((frame) => frame.type === 'HEADERS', 'HEADERS')
    await peer.waitFor((frame) => frame.type === 'DATA', 'response DATA')
    await peer.waitFor((frame) => frame.type === 'END' && frame.streamId === 1, 'response END')
    peer.send({ type: 'COMPLETE', streamId: 1 })
    await waitFor(() => idle === 1, 5_000)
    await waitFor(() => releases === 1, 5_000)
    expect(service.activeStreamCount()).toBe(0)

    peer.socket.close(1000)
    await bind
    await waitFor(() => closes.length === 1, 5_000)
    expect(closes[0]).toBeNull()
  })

  it('reuses one physical data websocket for sequential HTTP streams', async () => {
    const data = await startDataServer()
    const upstream = await startHttp('reuse')
    let idle = 0
    const service = createMuxUpstreamService({
      studioUrl: data.studioUrl,
      resolveView: resolverFor(upstream),
      onIdle: () => {
        idle++
      },
      connectTimeoutMillis: 2_000,
    })
    cleanups.push(() => service.shutdown())
    const bind = service.bind(binding('1', TICKET_1, data.streamUrl), new AbortController().signal)
    const peer = await data.waitPeer(0)

    const exchange = async (streamId: number, path: string): Promise<void> => {
      peer.send({ type: 'OPEN', streamId, request: httpOpen(path) })
      await peer.waitFor((frame) => frame.type === 'READY' && frame.streamId === streamId, `READY ${streamId}`)
      peer.send({ type: 'END', streamId })
      await peer.waitFor((frame) => frame.type === 'HEADERS' && frame.streamId === streamId, `HEADERS ${streamId}`)
      await peer.waitFor((frame) => frame.type === 'DATA' && frame.streamId === streamId, `DATA ${streamId}`)
      await peer.waitFor((frame) => frame.type === 'END' && frame.streamId === streamId, `END ${streamId}`)
      peer.send({ type: 'COMPLETE', streamId })
      await waitFor(() => idle === streamId, 5_000)
    }

    await exchange(1, '/first')
    expect(peer.socket.readyState).toBe(WebSocket.OPEN)
    await exchange(2, '/second')
    expect(peer.socket.readyState).toBe(WebSocket.OPEN)
    expect(data.peers).toHaveLength(1)
    expect(upstream.requests).toEqual(['/first', '/second'])

    await service.shutdown()
    await bind
  })

  it('accepts the first peer frame sent immediately after the physical handshake', async () => {
    const data = await startDataServer({
      onPeer: (peer) => {
        // Deliberately earlier than the client's open continuation: the old adapter dropped this.
        peer.send({ type: 'OPEN', streamId: 1, request: httpOpen('/early') })
      },
    })
    const upstream = await startHttp('early')
    let idle = 0
    const service = createMuxUpstreamService({
      studioUrl: data.studioUrl,
      resolveView: resolverFor(upstream),
      onIdle: () => {
        idle++
      },
      connectTimeoutMillis: 2_000,
    })
    cleanups.push(() => service.shutdown())
    const bind = service.bind(binding('1', TICKET_1, data.streamUrl), new AbortController().signal)
    const peer = await data.waitPeer(0)
    await peer.waitFor((frame) => frame.type === 'READY', 'early READY')
    peer.send({ type: 'END', streamId: 1 })
    const headers = (await peer.waitFor((frame) => frame.type === 'HEADERS', 'HEADERS')) as Extract<
      MuxFrame,
      { type: 'HEADERS' }
    >
    expect(headers.headers.status).toBe(200)
    await peer.waitFor((frame) => frame.type === 'END' && frame.streamId === 1, 'response END')
    peer.send({ type: 'COMPLETE', streamId: 1 })
    await waitFor(() => idle === 1, 5_000)
    expect(upstream.requests).toEqual(['/early'])

    await service.shutdown()
    await bind
  })

  it('replaces only on a strictly newer generation and ignores old close callbacks', async () => {
    const data = await startDataServer()
    const upstream = await startHttp('gen')
    let idle = 0
    const closes: Array<Error | null> = []
    const service = createMuxUpstreamService({
      studioUrl: data.studioUrl,
      resolveView: resolverFor(upstream),
      onIdle: () => {
        idle++
      },
      onDataClose: (error) => {
        closes.push(error)
      },
      connectTimeoutMillis: 2_000,
    })
    cleanups.push(() => service.shutdown())
    const first = service.bind(binding('1', TICKET_1, data.streamUrl), new AbortController().signal)
    const peerOne = await data.waitPeer(0)
    peerOne.send({ type: 'OPEN', streamId: 1, request: httpOpen('/one') })
    await peerOne.waitFor((frame) => frame.type === 'READY', 'first READY')

    const second = service.bind(binding('2', TICKET_2, data.streamUrl), new AbortController().signal)
    await first
    const peerTwo = await data.waitPeer(1)
    await waitFor(() => peerOne.socket.readyState === 3, 5_000)
    expect(data.requestUrls[1]).toContain(`ticket=${TICKET_2}`)
    expect(closes).toHaveLength(0)

    // A late callback from generation 1 must not have closed generation 2.
    peerTwo.send({ type: 'OPEN', streamId: 1, request: httpOpen('/two') })
    await peerTwo.waitFor((frame) => frame.type === 'READY', 'second READY')
    peerTwo.send({ type: 'END', streamId: 1 })
    await peerTwo.waitFor((frame) => frame.type === 'END', 'second response END')
    peerTwo.send({ type: 'COMPLETE', streamId: 1 })
    await waitFor(() => idle === 1, 5_000)

    await expect(service.bind(binding('1', TICKET_1, data.streamUrl), new AbortController().signal)).rejects.toThrow(
      /strictly newer/,
    )
    await expect(service.bind(binding('2', TICKET_2, data.streamUrl), new AbortController().signal)).rejects.toThrow(
      /strictly newer/,
    )
    await service.shutdown()
    await second
    expect(closes).toHaveLength(0)
  })

  it('releases a late resolved view after reset while the resolver is still pending', async () => {
    const data = await startDataServer()
    const upstream = await startHttp('late')
    let release!: (target: MuxStreamUpstream) => void
    let releases = 0
    let pendingRequest: MuxViewRequest | null = null
    let resolverStarted!: () => void
    const started = new Promise<void>((resolve) => {
      resolverStarted = resolve
    })
    const service = createMuxUpstreamService({
      studioUrl: data.studioUrl,
      resolveView: (_viewId, _epoch, request) => {
        pendingRequest = request
        resolverStarted()
        return new Promise<MuxStreamUpstream>((resolve) => {
          release = resolve
        })
      },
      connectTimeoutMillis: 2_000,
    })
    cleanups.push(() => service.shutdown())
    const bind = service.bind(binding('1', TICKET_1, data.streamUrl), new AbortController().signal)
    const peer = await data.waitPeer(0)
    peer.send({ type: 'OPEN', streamId: 1, request: httpOpen('/late') })
    await started
    expect(await service.resetView(VIEW_ID, EPOCH)).toBe(1)
    await peer.waitFor((frame) => frame.type === 'RESET', 'view reset')
    expect(service.activeStreamCount()).toBe(0)
    release(
      streamFor(upstream, pendingRequest!, {
        release: () => {
          releases++
        },
      }),
    )
    await waitFor(() => releases === 1, 5_000)
    expect(upstream.requests).toHaveLength(0)

    await service.shutdown()
    await bind
  })

  it('closes the current data connection and streams on control abort without replay', async () => {
    let responseClosed!: () => void
    const responseClosedPromise = new Promise<void>((resolve) => {
      responseClosed = resolve
    })
    const upstream = await startHttpHandler((request, response) => {
      request.resume()
      response.writeHead(200, { 'content-type': 'text/plain' })
      response.write('open')
      response.on('close', responseClosed)
    })
    let releases = 0
    const closes: Array<Error | null> = []
    const data = await startDataServer()
    const service = createMuxUpstreamService({
      studioUrl: data.studioUrl,
      resolveView: resolverFor(upstream, () => {
        releases++
      }),
      onDataClose: (error) => {
        closes.push(error)
      },
      connectTimeoutMillis: 2_000,
    })
    cleanups.push(() => service.shutdown())
    const controller = new AbortController()
    const bind = service.bind(binding('1', TICKET_1, data.streamUrl), controller.signal)
    const peer = await data.waitPeer(0)
    peer.send({ type: 'OPEN', streamId: 1, request: httpOpen('/live') })
    await peer.waitFor((frame) => frame.type === 'READY', 'READY')
    peer.send({ type: 'END', streamId: 1 })
    await peer.waitFor((frame) => frame.type === 'HEADERS', 'HEADERS')
    await peer.waitFor((frame) => frame.type === 'DATA', 'DATA')
    expect(service.activeStreamCount()).toBe(1)

    controller.abort()
    await bind
    await waitFor(() => peer.socket.readyState === 3, 5_000)
    await waitFor(() => service.activeStreamCount() === 0, 5_000)
    await waitFor(() => releases === 1, 5_000)
    await responseClosedPromise
    expect(upstream.requests).toEqual(['/live'])
    expect(data.peers).toHaveLength(1)
    expect(closes).toHaveLength(0)
  })

  it('closes only the current binding and distinguishes shutdown from close', async () => {
    const data = await startDataServer()
    const upstream = await startHttp('lifecycle')
    let idle = 0
    const closes: Array<Error | null> = []
    const service = createMuxUpstreamService({
      studioUrl: data.studioUrl,
      resolveView: resolverFor(upstream),
      onIdle: () => {
        idle++
      },
      onDataClose: (error) => {
        closes.push(error)
      },
      connectTimeoutMillis: 2_000,
    })
    cleanups.push(() => service.shutdown())

    const first = service.bind(binding('1', TICKET_1, data.streamUrl), new AbortController().signal)
    const peerOne = await data.waitPeer(0)
    await service.close()
    await first
    await waitFor(() => peerOne.socket.readyState === 3, 5_000)
    expect(closes).toHaveLength(0)

    const second = service.bind(binding('2', TICKET_2, data.streamUrl), new AbortController().signal)
    const peerTwo = await data.waitPeer(1)
    peerTwo.send({ type: 'OPEN', streamId: 1, request: httpOpen('/reopen') })
    await peerTwo.waitFor((frame) => frame.type === 'READY', 'reopened READY')
    peerTwo.send({ type: 'END', streamId: 1 })
    await peerTwo.waitFor((frame) => frame.type === 'END', 'reopened END')
    peerTwo.send({ type: 'COMPLETE', streamId: 1 })
    await waitFor(() => idle === 1, 5_000)

    await service.shutdown()
    await second
    await waitFor(() => peerTwo.socket.readyState === 3, 5_000)
    expect(closes).toHaveLength(0)
    await expect(
      service.bind(binding('3', TICKET_1, data.streamUrl), new AbortController().signal),
    ).rejects.toThrow(/closed/)
  })

  it('rejects malformed, cross-origin and non-newer bindings before opening a socket', async () => {
    const data = await startDataServer()
    const upstream = await startHttp('unused')
    const service = createMuxUpstreamService({
      studioUrl: data.studioUrl,
      resolveView: resolverFor(upstream),
      connectTimeoutMillis: 2_000,
    })
    cleanups.push(() => service.shutdown())
    await expect(
      service.bind(binding('1', 'short', data.streamUrl), new AbortController().signal),
    ).rejects.toThrow(/ticket is invalid/)
    await expect(
      service.bind(binding('1', TICKET_1, `${data.streamUrl}?ticket=${TICKET_2}`), new AbortController().signal),
    ).rejects.toThrow(/does not match/)
    await expect(
      service.bind(
        binding('1', TICKET_1, 'http://127.0.0.1:1/api/harness/environment-data/v2/environment'),
        new AbortController().signal,
      ),
    ).rejects.toThrow(/same-origin/)
    expect(data.peers).toHaveLength(0)
  })

  it('fails the physical socket when pong probes are not answered', async () => {
    const data = await startDataServer({ autoPong: false })
    const upstream = await startHttp('unused')
    const closes: Array<Error | null> = []
    const service = createMuxUpstreamService({
      studioUrl: data.studioUrl,
      resolveView: resolverFor(upstream),
      onDataClose: (error) => {
        closes.push(error)
      },
      connectTimeoutMillis: 2_000,
      pingIntervalMillis: 20,
      pongTimeoutMillis: 80,
    })
    cleanups.push(() => service.shutdown())
    const bind = service.bind(binding('1', TICKET_1, data.streamUrl), new AbortController().signal)
    await data.waitPeer(0)
    await waitFor(() => data.pingCount() >= 1, 5_000)
    await bind
    await waitFor(() => closes.length === 1, 5_000)
    expect(closes[0]).toBeInstanceOf(Error)
  })

  it('reports the exact wire limits and URL conversion without leaking the ticket into changes', () => {
    expect(MUX_DATA_MESSAGE_BYTES).toBe(65_548)
    expect(MUX_DATA_PING_INTERVAL_MILLIS).toBe(15_000)
    expect(MUX_DATA_PONG_TIMEOUT_MILLIS).toBe(45_000)
    const url = dataBindWebSocketUrl({
      generation: '1',
      ticket: TICKET_1,
      streamUrl: 'https://studio.example.com/api/harness/environment-data/v2/e',
    })
    expect(url).toBe(`wss://studio.example.com/api/harness/environment-data/v2/e?ticket=${TICKET_1}`)
    expect(decodeDataBind(JSON.stringify(binding('1', TICKET_1, 'https://studio.example.com/data')))).toEqual(
      binding('1', TICKET_1, 'https://studio.example.com/data'),
    )
  })
})
