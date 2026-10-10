/**
 * Reverse IDE data plane (Daemon side).
 *
 * The daemon is the WebSocket client. It dials the Server controlled stream endpoint
 * (`streamUrl`, ticket bound) and proxies a single local IDE exchange:
 *
 *   browser <-> Server/Gateway ===(this credit controlled WS)===> Daemon <-> local code-server
 *
 * HTTP exchanges stream the request body upstream and the response headers/body back.
 * WebSocket exchanges preserve text/binary type, message order/FIN and close codes.
 * Every payload direction is bounded by an independent credit window; the peer may only
 * send as many bytes as it has been granted, and bytes are acknowledged only after we
 * have actually consumed them (no unbounded buffering, no ack-before-consume).
 */

import http from 'node:http'
import type { IncomingMessage } from 'node:http'
import { once } from 'node:events'
import WebSocket, { type RawData } from 'ws'
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
  filterResponseHeaders,
  fragmentWsMessage,
  type TextFrame,
} from '../ide/wire.js'

/** Upper bound on a single upstream WebSocket message before it is fragmented for the stream. */
const UPSTREAM_MAX_MESSAGE = 256 * 1024 * 1024

/**
 * Bounded residual of upstream WebSocket messages that may be buffered while the browser is
 * still consuming. The upstream socket is paused as soon as one message is queued, so this only
 * absorbs whatever the ws receiver already parsed; exceeding it fails the exchange closed.
 */
const MAX_UPSTREAM_PENDING_MESSAGES = 32
const MAX_UPSTREAM_PENDING_BYTES = 8 * 1024 * 1024

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

/** Minimal async queue bridging push based WebSocket frames into pull based streams. */
class AsyncQueue<T> {
  private readonly items: T[] = []
  private readonly resolvers: ((result: IteratorResult<T>) => void)[] = []
  private closed = false

  push(item: T): void {
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

  sendBody(chunk: Buffer, window: CreditWindow): Promise<void> {
    return this.enqueue(async () => {
      let offset = 0
      while (offset < chunk.length) {
        const end = Math.min(offset + MAX_PAYLOAD, chunk.length)
        const payload = chunk.subarray(offset, end)
        await window.waitFor(payload.length)
        this.socket.send(encodeHttpBody(payload))
        window.reserve(payload.length)
        offset = end
      }
    })
  }

  sendWsMessage(kind: 'text' | 'binary', payload: Buffer, window: CreditWindow): Promise<void> {
    return this.enqueue(async () => {
      for (const frame of fragmentWsMessage(kind, payload)) {
        const size = frame.length - 2
        await window.waitFor(size)
        this.socket.send(frame)
        window.reserve(size)
      }
    })
  }
}

const SENDABLE_CLOSE_CODES = new Set([1000, 1001, 1002, 1003, 1007, 1008, 1009, 1010, 1011])

function normalizeCloseCode(code: number | undefined): number {
  if (code === undefined) {
    return 1000
  }
  if (SENDABLE_CLOSE_CODES.has(code) || (code >= 3000 && code <= 4999)) {
    return code
  }
  return 1000
}

function normalizeCloseReason(reason: Buffer | string | undefined): string {
  const text = typeof reason === 'string' ? reason : (reason?.toString('utf8') ?? '')
  return text.slice(0, 120)
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
    this.cleanup()
    this.completion.resolve()
  }

  fail(error: unknown): void {
    if (this.settled) {
      return
    }
    this.settled = true
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
  private readonly inbound = new AsyncQueue<Buffer>()
  private upstreamRequest?: http.ClientRequest
  private upstreamResponse?: IncomingMessage

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
      await this.outbox.sendBody(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk as Uint8Array), this.sendWindow)
    }
    await this.outbox.sendEnd()
    this.succeed()
  }

  protected handleBinary(data: Buffer): void {
    const frame = decodeBinaryFrame(data)
    switch (frame.kind) {
      case 'http-body': {
        this.recvWindow.reserve(frame.payload.length)
        this.inbound.push(frame.payload)
        return
      }
      case 'http-end': {
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

class WebSocketExchange extends Exchange {
  private upstreamSocket?: WebSocket
  /** Browser -> upstream wire fragments awaiting a serialized, credit friendly send. */
  private readonly outboundFragments: { kind: 'text' | 'binary'; payload: Buffer; last: boolean }[] = []
  private drainingFragments = false
  /** Upstream -> browser messages awaiting credit; bounded and backed by socket.pause(). */
  private readonly inboundMessages: { kind: 'text' | 'binary'; payload: Buffer }[] = []
  private inboundBytes = 0
  private drainingInbound = false
  private upstreamReady = false

  listen(): void {
    super.listen()
    this.gateway.on('close', (code: number, reason: Buffer) => {
      this.closeUpstream(normalizeCloseCode(code), normalizeCloseReason(reason))
      this.succeed()
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
    const socket = new WebSocket(
      `ws://${upstream.host}:${upstream.port}${request.path}`,
      upstream.protocols ?? [],
      {
        perMessageDeflate: false,
        maxPayload: UPSTREAM_MAX_MESSAGE,
        followRedirects: false,
        headers: headerRecord(buildUpstreamHeaders(request, upstream, false)),
        handshakeTimeout: this.options.connectTimeoutMillis ?? 10_000,
        // Deferring events gives us a chance to pause the socket between messages so the local
        // IDE cannot outrun the browser and grow an unbounded queue.
        allowSynchronousEvents: false,
      },
    )
    this.upstreamSocket = socket
    await waitForOpen(socket, this.options.connectTimeoutMillis ?? 10_000)
    // Register persistent handlers only after a successful open so a connection failure
    // is reported as a safe ERROR frame instead of a premature success.
    socket.on('error', (error: Error) => this.fail(new WireError(`upstream websocket failed: ${error.message}`)))
    socket.on('message', (data, isBinary) => {
      if (this.settled) {
        return
      }
      this.enqueueInbound(isBinary ? 'binary' : 'text', toBuffer(data))
    })
    socket.on('close', (code: number, reason: Buffer) => {
      if (this.gateway.readyState === WebSocket.OPEN) {
        this.gateway.close(normalizeCloseCode(code), normalizeCloseReason(reason))
      }
      this.succeed()
    })
    await this.outbox.sendText({ type: 'WS_READY' })
    this.upstreamReady = true
    this.maybeDrainFragments()
  }

  /** Queues one upstream message, pausing the socket and failing closed past the bounded residual. */
  private enqueueInbound(kind: 'text' | 'binary', payload: Buffer): void {
    this.inboundMessages.push({ kind, payload })
    this.inboundBytes += payload.length
    if (
      this.inboundMessages.length > MAX_UPSTREAM_PENDING_MESSAGES ||
      this.inboundBytes > MAX_UPSTREAM_PENDING_BYTES
    ) {
      this.fail(new WireError('upstream websocket produced more data than the stream can consume'))
      return
    }
    this.upstreamSocket?.pause()
    if (!this.drainingInbound) {
      this.drainingInbound = true
      void this.drainInbound().catch((error: unknown) => this.fail(error))
    }
  }

  private async drainInbound(): Promise<void> {
    while (!this.settled && this.inboundMessages.length > 0) {
      const message = this.inboundMessages.shift() as { kind: 'text' | 'binary'; payload: Buffer }
      this.inboundBytes -= message.payload.length
      await this.outbox.sendWsMessage(message.kind, message.payload, this.sendWindow)
    }
    this.drainingInbound = false
    if (!this.settled) {
      const socket = this.upstreamSocket
      if (socket && socket.readyState === WebSocket.OPEN) {
        socket.resume()
      }
    }
  }

  protected handleBinary(data: Buffer): void {
    const frame = decodeBinaryFrame(data)
    switch (frame.kind) {
      case 'ws-text':
      case 'ws-binary': {
        const kind = frame.kind === 'ws-text' ? 'text' : 'binary'
        // Reserve per fragment and forward each fragment as it arrives so a message larger
        // than the credit window never deadlocks; the peer is acknowledged as we flush to the
        // upstream. Raw bytes are preserved, including a multi-byte sequence split across frames.
        this.recvWindow.reserve(frame.payload.length)
        this.outboundFragments.push({ kind, payload: frame.payload, last: frame.last })
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
    if (!this.upstreamReady || this.drainingFragments || this.outboundFragments.length === 0) {
      return
    }
    this.drainingFragments = true
    void this.drainFragments().catch((error: unknown) => this.fail(error))
  }

  private async drainFragments(): Promise<void> {
    while (!this.settled && this.outboundFragments.length > 0) {
      const fragment = this.outboundFragments.shift() as { kind: 'text' | 'binary'; payload: Buffer; last: boolean }
      await this.sendFragment(fragment)
      this.sendAck(fragment.payload.length)
    }
    this.drainingFragments = false
  }

  /** Writes one upstream frame and resolves once it has been flushed, then credit is released. */
  private sendFragment(fragment: { kind: 'text' | 'binary'; payload: Buffer; last: boolean }): Promise<void> {
    const socket = this.upstreamSocket
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new WireError('upstream websocket is not open'))
    }
    return new Promise<void>((resolve, reject) => {
      socket.send(
        fragment.payload,
        { binary: fragment.kind === 'binary', fin: fragment.last },
        (error?: Error) => {
          if (error) {
            reject(new WireError(`upstream websocket send failed: ${error.message}`))
          } else {
            resolve()
          }
        },
      )
    })
  }

  private closeUpstream(code: number, reason: string): void {
    const socket = this.upstreamSocket
    if (socket && socket.readyState <= WebSocket.OPEN) {
      socket.close(code, reason)
    }
  }

  protected cleanup(error?: unknown): void {
    void error
    this.outboundFragments.length = 0
    this.inboundMessages.length = 0
    this.inboundBytes = 0
    const socket = this.upstreamSocket
    if (socket && socket.readyState <= WebSocket.OPEN) {
      socket.terminate()
    }
    this.closeGateway()
  }
}

export async function openStreamExchange(options: StreamExchangeOptions): Promise<void> {
  if (options.signal.aborted) {
    throw abortError()
  }
  const timeout = options.connectTimeoutMillis ?? 10_000
  const gateway = new WebSocket(options.streamUrl, {
    perMessageDeflate: false,
    maxPayload: MAX_PAYLOAD + 2,
    followRedirects: false,
    handshakeTimeout: timeout,
  })
  const exchange = options.request.mode === 'HTTP' ? new HttpExchange(options, gateway) : new WebSocketExchange(options, gateway)
  exchange.listen()
  const onAbort = (): void => exchange.fail(abortError())
  options.signal.addEventListener('abort', onAbort, { once: true })
  try {
    await waitForOpen(gateway, timeout)
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
