/**
 * Reverse IDE data plane (Daemon side).
 *
 * The daemon is the WebSocket client. It dials the Server controlled stream endpoint
 * (`streamUrl`, ticket bound) and proxies a single local IDE exchange:
 *
 *   browser <-> Server/Gateway ===(this credit controlled WS)===> Daemon <-> local code-server
 *
 * HTTP exchanges stream the request body upstream and the response headers/body back.
 * WebSocket exchanges preserve text/binary type, message order/FIN and close codes. The gateway
 * side uses the pinned `ws` library (frames are already bounded to MAX_PAYLOAD), while the local
 * code-server WS is a bounded streaming RFC 6455 client: it forwards one payload piece at a time
 * and never reassembles a whole message, so an arbitrarily large IDE message stays bounded.
 * Every payload direction is bounded by an independent credit window; the peer may only
 * send as many bytes as it has been granted, and bytes are acknowledged only after we
 * have actually consumed them (no unbounded buffering, no ack-before-consume).
 */

import http from 'node:http'
import type { IncomingMessage } from 'node:http'
import { once } from 'node:events'
import WebSocket, { type RawData } from 'ws'
import { getOutboundNetwork } from '../network.js'
import {
  CreditWindow,
  MAX_PAYLOAD,
  WireError,
  decodeBinaryFrame,
  decodeTextFrame,
  encodeAck,
  encodeHttpBody,
  encodeHttpEnd,
  encodeTextFrame,
  encodeWsFrame,
  filterResponseHeaders,
  type TextFrame,
} from '../ide/wire.js'
import { UpstreamWebSocket, isValidCloseCode } from './upstream-websocket.js'

/**
 * Maximum number of wire frames buffered in one direction. The credit window already bounds
 * payload bytes; this cap additionally bounds a flood of zero-length frames, which reserve no
 * credit, so a peer can never grow an unbounded queue.
 */
const MAX_PENDING_FRAMES = 1024

export interface StreamRequest {
  mode: 'HTTP' | 'WS'
  method: string
  path: string
  headers: [string, string][]
}

export interface StreamUpstream {
  host: string
  port: number
  /** Private upstream cookie injected by the daemon; never derived from browser state. */
  cookie?: string
  /** WebSocket subprotocols requested from the local IDE. */
  protocols?: string[]
}

export interface StreamExchangeOptions {
  /** Server controlled stream endpoint; the ticket is already part of this URL. */
  streamUrl: string
  request: StreamRequest
  upstream: StreamUpstream
  signal: AbortSignal
  connectTimeoutMillis?: number
}

const DROP_REQUEST_HEADERS = new Set([
  'host',
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'cookie',
  'authorization',
  'origin',
  'referer',
  'sec-websocket-key',
  'sec-websocket-version',
  'sec-websocket-extensions',
  'sec-websocket-protocol',
])

function buildUpstreamHeaders(request: StreamRequest, upstream: StreamUpstream, declareBody: boolean): [string, string][] {
  const headers: [string, string][] = []
  let hasContentLength = false
  for (const [name, value] of request.headers) {
    const key = name.toLowerCase()
    if (DROP_REQUEST_HEADERS.has(key)) {
      continue
    }
    if (key === 'content-length') {
      hasContentLength = true
    }
    headers.push([name, value])
  }
  headers.push(['host', `${upstream.host}:${upstream.port}`])
  // Without a length Node only streams a body for methods it expects to carry one; declaring the
  // framing explicitly lets a bodyful GET/HEAD reach the upstream unchanged. WebSocket handshakes
  // must never carry it.
  if (declareBody && !hasContentLength) {
    headers.push(['transfer-encoding', 'chunked'])
  }
  if (upstream.cookie !== undefined && upstream.cookie.length > 0) {
    headers.push(['cookie', upstream.cookie])
  }
  return headers
}

function headerRecord(headers: readonly [string, string][]): Record<string, string | string[]> {
  const record: Record<string, string | string[]> = {}
  for (const [name, value] of headers) {
    const key = name.toLowerCase()
    const existing = record[key]
    if (existing === undefined) {
      record[key] = value
    } else if (Array.isArray(existing)) {
      existing.push(value)
    } else {
      record[key] = [existing, value]
    }
  }
  return record
}

function abortError(): Error {
  const error = new Error('stream exchange aborted')
  error.name = 'AbortError'
  return error
}

/** Resolves or rejects exactly once. */
class Completion {
  readonly promise: Promise<void>
  private resolveFn: () => void = () => undefined
  private rejectFn: (error: unknown) => void = () => undefined
  private settled = false

  constructor() {
    this.promise = new Promise<void>((resolve, reject) => {
      this.resolveFn = resolve
      this.rejectFn = reject
    })
  }

  resolve(): void {
    if (!this.settled) {
      this.settled = true
      this.resolveFn()
    }
  }

  reject(error: unknown): void {
    if (!this.settled) {
      this.settled = true
      this.rejectFn(error)
    }
  }
}

/** Minimal bounded async queue bridging push based WebSocket frames into pull based streams. */
class AsyncQueue<T> {
  private readonly items: T[] = []
  private readonly resolvers: ((result: IteratorResult<T>) => void)[] = []
  private closed = false

  constructor(private readonly limit: number) {}

  push(item: T): void {
    if (this.closed) {
      throw new WireError('queue is closed')
    }
    if (this.items.length >= this.limit) {
      throw new WireError('queue limit exceeded')
    }
    const resolve = this.resolvers.shift()
    if (resolve) {
      resolve({ value: item, done: false })
    } else {
      this.items.push(item)
    }
  }

  close(): void {
    if (this.closed) {
      return
    }
    this.closed = true
    for (const resolve of this.resolvers.splice(0)) {
      resolve({ value: undefined as never, done: true })
    }
  }

  [Symbol.asyncIterator](): AsyncIterator<T> {
    return {
      next: (): Promise<IteratorResult<T>> => {
        if (this.items.length > 0) {
          return Promise.resolve({ value: this.items.shift() as T, done: false })
        }
        if (this.closed) {
          return Promise.resolve({ value: undefined as never, done: true })
        }
        return new Promise<IteratorResult<T>>((resolve) => this.resolvers.push(resolve))
      },
    }
  }
}

/** Serializes outbound frames so control text always precedes the body it announces. */
class Outbox {
  private tail: Promise<void> = Promise.resolve()

  constructor(private readonly socket: WebSocket) {}

  private enqueue(task: () => Promise<void> | void): Promise<void> {
    const run = async (): Promise<void> => {
      if (this.socket.readyState !== WebSocket.OPEN) {
        throw new WireError('stream socket is not open')
      }
      await task()
    }
    const next = this.tail.then(run)
    this.tail = next.catch(() => undefined)
    return next
  }

  sendText(frame: TextFrame): Promise<void> {
    return this.enqueue(() => {
      this.socket.send(encodeTextFrame(frame))
    })
  }

  sendEnd(): Promise<void> {
    return this.enqueue(() => {
      this.socket.send(encodeHttpEnd())
    })
  }

  sendBody(chunk: Buffer, window: CreditWindow, signal: AbortSignal): Promise<void> {
    return this.enqueue(async () => {
      let offset = 0
      while (offset < chunk.length) {
        const end = Math.min(offset + MAX_PAYLOAD, chunk.length)
        const payload = chunk.subarray(offset, end)
        await window.waitFor(payload.length, signal)
        // Reserve before the frame is observable so an early ack can never over-release credit.
        window.reserve(payload.length)
        this.socket.send(encodeHttpBody(payload))
        offset = end
      }
    })
  }

  /** Sends one already-bounded WS fragment; credit is managed by the caller. */
  sendWsFragment(kind: 'text' | 'binary', payload: Buffer, last: boolean): Promise<void> {
    return this.enqueue(() => {
      this.socket.send(encodeWsFrame(kind, payload, last))
    })
  }
}

function normalizeCloseCode(code: number | undefined): number {
  if (code === undefined) {
    return 1000
  }
  // Only codes the peer may legally use are forwarded verbatim; reserved codes become a normal close.
  return isValidCloseCode(code) ? code : 1000
}

function normalizeCloseReason(reason: Buffer | string | undefined): string {
  const text = typeof reason === 'string' ? reason : (reason?.toString('utf8') ?? '')
  return text.slice(0, 120)
}

/** 与 lookup 共享同一绝对 deadline 的剩余毫秒数；至少 1ms，绝不重新计时。 */
function remainingMillis(timeoutMillis: number, startedAt: number): number {
  return Math.max(1, timeoutMillis - (Date.now() - startedAt))
}

function waitForOpen(socket: WebSocket, timeoutMillis: number): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    const cleanup = (): void => {
      clearTimeout(timer)
      socket.off('open', onOpen)
      socket.off('error', onError)
      socket.off('close', onClose)
    }
    const onOpen = (): void => {
      cleanup()
      resolve()
    }
    const onError = (error: Error): void => {
      cleanup()
      reject(new WireError(`stream socket error: ${error.message}`))
    }
    const onClose = (): void => {
      cleanup()
      reject(new WireError('stream socket closed before opening'))
    }
    const timer = setTimeout(() => {
      cleanup()
      reject(new WireError('stream socket open timeout'))
    }, timeoutMillis)
    timer.unref()
    socket.once('open', onOpen)
    socket.once('error', onError)
    socket.once('close', onClose)
  })
}

function toBuffer(data: RawData): Buffer {
  if (Buffer.isBuffer(data)) {
    return data
  }
  if (Array.isArray(data)) {
    return Buffer.concat(data)
  }
  return Buffer.from(data)
}

async function waitForDrain(request: http.ClientRequest): Promise<void> {
  await once(request, 'drain')
}

abstract class Exchange {
  protected readonly completion = new Completion()
  protected readonly sendWindow = new CreditWindow()
  protected readonly recvWindow = new CreditWindow()
  protected readonly outbox: Outbox
  /** Aborts credit waits so a settled exchange never strands a blocked sender. */
  protected readonly abort = new AbortController()
  protected settled = false

  constructor(
    protected readonly options: StreamExchangeOptions,
    protected readonly gateway: WebSocket,
  ) {
    this.outbox = new Outbox(gateway)
  }

  get done(): Promise<void> {
    return this.completion.promise
  }

  /** Registers inbound handling before the socket opens so no early frame is dropped. */
  listen(): void {
    this.gateway.on('message', (data, isBinary) => {
      try {
        if (isBinary) {
          this.handleBinary(toBuffer(data))
        } else {
          this.handleText(toBuffer(data).toString('utf8'))
        }
      } catch (error) {
        this.fail(error)
      }
    })
  }

  /** Starts the upstream exchange once the stream socket is open. */
  abstract start(): void

  succeed(): void {
    if (this.settled) {
      return
    }
    this.settled = true
    this.abort.abort()
    this.cleanup()
    this.completion.resolve()
  }

  fail(error: unknown): void {
    if (this.settled) {
      return
    }
    this.settled = true
    this.abort.abort()
    this.cleanup(error)
    this.completion.reject(error)
  }

  protected abstract cleanup(error?: unknown): void

  protected abstract handleBinary(frame: Buffer): void

  protected handleText(data: string): void {
    const frame = decodeTextFrame(data)
    if (frame.type === 'ERROR') {
      this.fail(new WireError(`stream peer rejected exchange: ${frame.message}`))
      return
    }
    this.fail(new WireError(`unexpected text frame ${frame.type}`))
  }

  protected sendAck(bytes: number): void {
    if (bytes <= 0) {
      return
    }
    this.recvWindow.ack(bytes)
    if (this.gateway.readyState === WebSocket.OPEN) {
      this.gateway.send(encodeAck(bytes))
    }
  }

  protected closeGateway(): void {
    if (this.gateway.readyState === WebSocket.OPEN) {
      this.gateway.close()
    } else if (this.gateway.readyState === WebSocket.CONNECTING) {
      this.gateway.terminate()
    }
  }
}

class HttpExchange extends Exchange {
  private readonly inbound = new AsyncQueue<Buffer>(MAX_PENDING_FRAMES)
  private upstreamRequest?: http.ClientRequest
  private upstreamResponse?: IncomingMessage
  private inboundEnded = false

  listen(): void {
    super.listen()
    this.gateway.on('close', () => this.succeed())
  }

  start(): void {
    const { request, upstream } = this.options
    const clientRequest = http.request({
      host: upstream.host,
      port: upstream.port,
      method: request.method.toUpperCase(),
      path: request.path,
      headers: headerRecord(buildUpstreamHeaders(request, upstream, true)),
      agent: false,
      signal: this.options.signal,
    })
    this.upstreamRequest = clientRequest
    clientRequest.on('error', (error: Error) => this.fail(new WireError(`upstream request failed: ${error.message}`)))
    // Send the request headers immediately so an upstream that answers without reading a body
    // (health probes, plain GETs) can respond while the body is still streaming in.
    clientRequest.flushHeaders()
    clientRequest.on('response', (response) => {
      this.upstreamResponse = response
      void this.pumpResponse(response).catch((error: unknown) => this.fail(error))
    })
    // Every request body is pumped, including GET/HEAD: a body frame is real client data and
    // dropping it would silently rewrite the request. The pump ends the request on http-end.
    void this.pumpRequestBody().catch((error: unknown) => this.fail(error))
  }

  private async pumpRequestBody(): Promise<void> {
    const clientRequest = this.upstreamRequest
    if (!clientRequest) {
      return
    }
    try {
      for await (const chunk of this.inbound) {
        if (this.settled) {
          return
        }
        if (!clientRequest.write(chunk)) {
          await waitForDrain(clientRequest)
        }
        // Acknowledge only after the bytes have been handed to the upstream request.
        this.sendAck(chunk.length)
      }
    } catch {
      // The response can finish before the request body; a destroy during cleanup is not fatal.
      if (!this.settled) {
        throw new WireError('upstream request body failed')
      }
      return
    }
    if (!this.settled && !clientRequest.destroyed) {
      clientRequest.end()
    }
  }

  private async pumpResponse(response: IncomingMessage): Promise<void> {
    await this.outbox.sendText({
      type: 'HTTP_RESPONSE',
      status: response.statusCode ?? 502,
      headers: filterResponseHeaders(response.rawHeaders),
    })
    for await (const chunk of response) {
      await this.outbox.sendBody(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk as Uint8Array), this.sendWindow, this.abort.signal)
    }
    await this.outbox.sendEnd()
    this.succeed()
  }

  protected handleBinary(data: Buffer): void {
    const frame = decodeBinaryFrame(data)
    switch (frame.kind) {
      case 'http-body': {
        if (this.inboundEnded) {
          throw new WireError('received an http body frame after the request end')
        }
        if (frame.payload.length === 0) {
          throw new WireError('http body frame must not be empty')
        }
        this.recvWindow.reserve(frame.payload.length)
        this.inbound.push(frame.payload)
        return
      }
      case 'http-end': {
        if (this.inboundEnded) {
          throw new WireError('received a duplicate http end frame')
        }
        this.inboundEnded = true
        this.inbound.close()
        return
      }
      case 'ack': {
        this.sendWindow.ack(frame.bytes)
        return
      }
      default:
        throw new WireError('unexpected WebSocket frame during HTTP exchange')
    }
  }

  protected cleanup(error?: unknown): void {
    void error
    this.inbound.close()
    this.upstreamRequest?.destroy()
    this.upstreamResponse?.destroy()
    this.closeGateway()
  }
}

/** A queued browser -> upstream fragment, with the opcode decision recorded at enqueue time. */
interface PendingFragment {
  kind: 'text' | 'binary'
  payload: Buffer
  last: boolean
  first: boolean
}

class WebSocketExchange extends Exchange {
  private upstream?: UpstreamWebSocket
  /** Browser -> upstream wire fragments awaiting a serialized, credit friendly send. */
  private readonly pendingFragments: PendingFragment[] = []
  private drainingFragments = false
  private upstreamReady = false
  /** Whether a browser message is still open, so a type change can be rejected as early as possible. */
  private queuedMessageOpen = false
  private queuedKind: 'text' | 'binary' = 'text'

  listen(): void {
    super.listen()
    this.gateway.on('close', (code: number, reason: Buffer) => {
      const closeCode = normalizeCloseCode(code)
      const closeReason = normalizeCloseReason(reason)
      // Ask the local IDE to close before releasing the socket so the close is observable. An
      // already-gone socket is benign, but a real write failure must fail the exchange.
      void (async () => {
        await this.upstream?.close(closeCode, closeReason).catch((error: unknown) => this.fail(error))
        this.succeed()
      })()
    })
  }

  start(): void {
    void this.connectUpstream().catch(async (error: unknown) => {
      // Report a fixed safe message before closing so the gateway can fail the browser handshake.
      await this.outbox.sendText({ type: 'ERROR', message: 'ide websocket unavailable' }).catch(() => undefined)
      this.fail(error)
    })
  }

  private async connectUpstream(): Promise<void> {
    const { request, upstream } = this.options
    const socket = await UpstreamWebSocket.connect({
      host: upstream.host,
      port: upstream.port,
      path: request.path,
      protocols: upstream.protocols ?? [],
      headers: buildUpstreamHeaders(request, upstream, false),
      signal: this.options.signal,
      handshakeTimeoutMillis: this.options.connectTimeoutMillis ?? 10_000,
    })
    if (this.settled) {
      socket.destroy()
      return
    }
    this.upstream = socket
    await this.outbox.sendText({ type: 'WS_READY' })
    this.upstreamReady = true
    this.maybeDrainFragments()
    await this.pumpUpstream(socket)
  }

  /** Streams each upstream fragment to the browser, waiting for credit every time. */
  private async pumpUpstream(socket: UpstreamWebSocket): Promise<void> {
    for await (const frame of socket.frames()) {
      if (this.settled) {
        return
      }
      if (frame.type === 'data') {
        await this.sendWindow.waitFor(frame.payload.length, this.abort.signal)
        if (this.settled) {
          return
        }
        // Reserve before the frame is observable so an early ack can never over-release credit.
        this.sendWindow.reserve(frame.payload.length)
        await this.outbox.sendWsFragment(frame.kind, frame.payload, frame.last)
        continue
      }
      // Upstream closed: relay the close code to the browser before finishing.
      if (this.gateway.readyState === WebSocket.OPEN) {
        this.gateway.close(normalizeCloseCode(frame.code), normalizeCloseReason(frame.reason))
      }
      this.succeed()
      return
    }
  }

  protected handleBinary(data: Buffer): void {
    const frame = decodeBinaryFrame(data)
    switch (frame.kind) {
      case 'ws-text':
      case 'ws-binary': {
        const kind = frame.kind === 'ws-text' ? 'text' : 'binary'
        const first = !this.queuedMessageOpen
        // One IDE message may span many wire fragments but must never change its type.
        if (!first && kind !== this.queuedKind) {
          throw new WireError('websocket message type changed across fragments')
        }
        if (first) {
          this.queuedKind = kind
        }
        this.queuedMessageOpen = !frame.last
        // Reserve per fragment and forward each fragment as it is flushed so a message larger
        // than the credit window never deadlocks. A flood of zero-length frames reserves no
        // credit, so the frame count is bounded separately.
        this.recvWindow.reserve(frame.payload.length)
        this.pendingFragments.push({ kind, payload: frame.payload, last: frame.last, first })
        if (this.pendingFragments.length > MAX_PENDING_FRAMES) {
          throw new WireError('too many buffered websocket fragments')
        }
        this.maybeDrainFragments()
        return
      }
      case 'ack': {
        this.sendWindow.ack(frame.bytes)
        return
      }
      case 'http-end': {
        // The browser stopped sending; a WebSocket stays open until the socket closes.
        return
      }
      default:
        throw new WireError('unexpected HTTP frame during WebSocket exchange')
    }
  }

  /** Starts the fragment drain once the upstream is open; fragments may arrive earlier. */
  private maybeDrainFragments(): void {
    if (!this.upstreamReady || this.drainingFragments || this.pendingFragments.length === 0) {
      return
    }
    this.drainingFragments = true
    void this.drainFragments().catch((error: unknown) => this.fail(error))
  }

  private async drainFragments(): Promise<void> {
    while (!this.settled && this.pendingFragments.length > 0) {
      const fragment = this.pendingFragments.shift() as PendingFragment
      const socket = this.upstream
      if (!socket) {
        throw new WireError('upstream websocket is not connected')
      }
      await socket.sendFragment(fragment.first ? fragment.kind : 'continuation', fragment.payload, fragment.last)
      // The frame is flushed to the TCP socket, so the peer can safely have its credit back.
      this.sendAck(fragment.payload.length)
    }
    this.drainingFragments = false
  }

  protected cleanup(error?: unknown): void {
    void error
    this.pendingFragments.length = 0
    this.upstream?.destroy()
    this.closeGateway()
  }
}

export async function openStreamExchange(options: StreamExchangeOptions): Promise<void> {
  if (options.signal.aborted) {
    throw abortError()
  }
  const timeout = options.connectTimeoutMillis ?? 10_000
  const startedAt = Date.now()
  // lookup 与原连接 deadline/取消共享同一 signal；socket close/error 必定释放独占 Agent。
  const deadline = AbortSignal.any([options.signal, AbortSignal.timeout(timeout)])
  const plan = await getOutboundNetwork().createWebSocketAgent(options.streamUrl, deadline)
  // lookup 返回后立刻检查取消，覆盖“await 之后、listener 之前”的窗口。
  if (deadline.aborted) {
    plan.destroy()
    throw abortError()
  }
  // 握手沿用共享 deadline 的剩余时间；构造抛错也必须销毁已建 Agent。
  let gateway: WebSocket
  try {
    gateway = new WebSocket(options.streamUrl, {
      perMessageDeflate: false,
      maxPayload: MAX_PAYLOAD + 2,
      followRedirects: false,
      handshakeTimeout: remainingMillis(timeout, startedAt),
      agent: plan.agent,
    })
  } catch (error) {
    plan.destroy()
    throw error
  }
  gateway.once('close', () => plan.destroy())
  gateway.once('error', () => plan.destroy())
  const exchange = options.request.mode === 'HTTP' ? new HttpExchange(options, gateway) : new WebSocketExchange(options, gateway)
  exchange.listen()
  const onAbort = (): void => exchange.fail(abortError())
  options.signal.addEventListener('abort', onAbort, { once: true })
  try {
    await waitForOpen(gateway, remainingMillis(timeout, startedAt))
    exchange.start()
    await exchange.done
  } catch (error) {
    exchange.fail(error)
    gateway.terminate()
    await exchange.done.catch(() => undefined)
    throw error
  } finally {
    options.signal.removeEventListener('abort', onAbort)
  }
}
