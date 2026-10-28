import { Buffer } from 'node:buffer'
import http from 'node:http'
import type { IncomingMessage } from 'node:http'
import net from 'node:net'

import { UpstreamWebSocket, isValidCloseCode } from './upstream-websocket.js'
import { MUX_MAX_PAYLOAD_BYTES, type ClosePayload, type DataFrame, type HeadersPayload } from './mux-wire.js'
import { MuxError, createMuxConnection, type MuxConnection, type MuxStream } from './mux.js'

/**
 * Daemon-side adapter for one accepted DATA_BIND WebSocket.
 *
 * <p>The physical socket and its heartbeat belong to {@link createMuxUpstreamService}; this module only
 * feeds complete binary messages into the reviewed mux engine and bridges every logical stream to one
 * private loopback HTTP or WebSocket upstream. The resolver is the sole authority for turning a
 * server-supplied view identity into a loopback target: no host, URL or port from the wire is dialled.
 * The resolver returns the view-scoped path and already-filtered private headers, plus the service
 * cancellation signal and release callback from the IDE lifecycle.
 *
 * <p>HTTP keeps request and response half-closes independent. Inbound DATA is acknowledged only after
 * the bytes were accepted by the local request (including Node write callbacks/backpressure), and
 * response DATA waits for the mux writer. A successful HTTP stream therefore needs both a successful
 * response END write and the peer COMPLETE. Any reset destroys the local exchange without replay.
 *
 * <p>WebSocket streams reuse {@link UpstreamWebSocket}, which streams individual RFC 6455 fragments
 * and never reassembles an unbounded message. Fragment type and FIN are preserved in both directions.
 * A close handshake is bounded by {@link MuxDataConnectionOptions.wsCloseTimeoutMillis}; a timeout resets
 * only that logical stream.
 */

const DROP_RESPONSE_HEADERS = new Set(['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade'])
const PROTOCOL_TOKEN = /^[A-Za-z0-9!#$%&'*+.^_`|~-]+$/
const DEFAULT_HTTP_TIMEOUT_MILLIS = 5 * 60 * 1000
const DEFAULT_READY_TIMEOUT_MILLIS = 10_000
const DEFAULT_WS_CLOSE_TIMEOUT_MILLIS = 5_000
const DEFAULT_WS_HANDSHAKE_TIMEOUT_MILLIS = 10_000

/**
 * The private target supplied by the IDE view resolver; it must always be numeric loopback.
 *
 * <p>This is structurally the same contract as {@code IdeStreamUpstream} in the IDE service. The
 * resolver owns path scoping, header filtering, view-lifecycle cancellation and the single release
 * callback; the transport only adds the private host, cookie and WebSocket control headers.
 */
export interface MuxStreamUpstream {
  host: string
  port: number
  /** View-scoped private upstream path, never the browser path verbatim. */
  path: string
  /** Request headers already filtered by the resolver; Host and Cookie remain adapter-owned. */
  headers: [string, string][]
  /** Daemon-owned code-server session cookie; never derived from browser input. */
  cookie?: string
  /** Requested local WebSocket subprotocols. */
  protocols?: string[]
  /** Resolver-owned cancellation; aborting it resets this logical stream. */
  signal: AbortSignal
  /** Called exactly once when the logical stream really ended; repeated calls are harmless. */
  release(): void
}

/** Transport-neutral request metadata copied from a validated OPEN frame. */
export interface MuxViewRequest {
  mode: 'HTTP' | 'WS'
  method: string
  path: string
  headers: [string, string][]
}

/**
 * Resolves and validates a view immediately before the local dial. Implementations must re-check the
 * current claim after any asynchronous wait and reject if the view/epoch was detached or replaced.
 * The returned upstream supplies its own view-lifecycle signal; the adapter also has a bounded
 * readiness timer covering this resolution, the local handshake, and the READY write.
 */
export type MuxViewResolver = (
  viewId: string,
  bindingEpoch: string,
  request: MuxViewRequest,
) => Promise<MuxStreamUpstream>

export interface MuxDataConnectionOptions {
  /** The one physical writer. Rejection is terminal: the mux engine never retries a failed write. */
  send: (frame: Uint8Array) => Promise<void> | void
  resolveView: MuxViewResolver
  /** Called only after the last active logical stream converges. */
  onIdle?: () => void
  /** Terminal mux/protocol failure; the physical owner must close the DATA_BIND socket. */
  onFatal?: (error: MuxError) => void
  /**
   * Limit for resolving the view, connecting the private upstream and writing READY. It is not a
   * total exchange duration; a timeout resets only this logical stream. Defaults to ten seconds.
   */
  readyTimeoutMillis?: number
  /** First-upstream-response limit; not a total exchange duration. Defaults to five minutes. */
  httpFirstResponseTimeoutMillis?: number
  /** Longest gap without non-empty response progress; not a total exchange duration. */
  httpIdleTimeoutMillis?: number
  /** Per-stream WS close handshake limit. Defaults to five seconds. */
  wsCloseTimeoutMillis?: number
  /** Local WebSocket upstream handshake limit. Defaults to ten seconds. */
  wsHandshakeTimeoutMillis?: number
}

export interface MuxDataConnection {
  /** Active and still-starting logical streams on this physical connection. */
  activeStreamCount(): number
  /** Reset only streams of the exact view+epoch; returns the number ended. */
  resetView(viewId: string, bindingEpoch: string): Promise<number>
  /** Feed one complete physical binary message. */
  receive(message: Uint8Array): void
  /** Local physical disconnect: abort streams without sending new frames. */
  close(): Promise<void>
  isClosed(): boolean
}

function positiveTimeout(value: number | undefined, fallback: number): number {
  if (value === undefined) {
    return fallback
  }
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new MuxError('adapter timeout must be a positive integer')
  }
  return value
}

function isLoopbackHost(host: string): boolean {
  const value = host.startsWith('[') && host.endsWith(']') ? host.slice(1, -1) : host
  if (value === '::1') {
    return true
  }
  return net.isIPv4(value) && value.split('.')[0] === '127'
}

function hasHeaderUnsafeCharacter(value: string): boolean {
  return /[\u0000-\u001f\u007f]/.test(value)
}

function validateUpstream(target: MuxStreamUpstream): MuxStreamUpstream {
  if (typeof target !== 'object' || target === null) {
    throw new MuxError('resolved upstream is required')
  }
  if (typeof target.host !== 'string' || !isLoopbackHost(target.host)) {
    throw new MuxError('resolved upstream must be a numeric loopback host')
  }
  if (!Number.isSafeInteger(target.port) || target.port < 1 || target.port > 65_535) {
    throw new MuxError('resolved upstream port is invalid')
  }
  if (
    typeof target.path !== 'string' ||
    !target.path.startsWith('/') ||
    target.path.startsWith('//') ||
    hasHeaderUnsafeCharacter(target.path)
  ) {
    throw new MuxError('resolved upstream path is invalid')
  }
  if (
    !Array.isArray(target.headers) ||
    target.headers.some(
      (header) =>
        !Array.isArray(header) ||
        header.length !== 2 ||
        typeof header[0] !== 'string' ||
        typeof header[1] !== 'string' ||
        !PROTOCOL_TOKEN.test(header[0]) ||
        hasHeaderUnsafeCharacter(header[1]),
    )
  ) {
    throw new MuxError('resolved upstream headers are invalid')
  }
  if (target.cookie !== undefined && (typeof target.cookie !== 'string' || hasHeaderUnsafeCharacter(target.cookie))) {
    throw new MuxError('resolved upstream cookie is invalid')
  }
  if (target.protocols !== undefined) {
    if (!Array.isArray(target.protocols) || target.protocols.some((value) => typeof value !== 'string' || !PROTOCOL_TOKEN.test(value))) {
      throw new MuxError('resolved upstream protocols are invalid')
    }
  }
  if (!(target.signal instanceof AbortSignal) || typeof target.release !== 'function') {
    throw new MuxError('resolved upstream lifecycle is invalid')
  }
  return target
}

function upstreamAuthority(target: MuxStreamUpstream): string {
  const host = target.host.startsWith('[') ? target.host : target.host.includes(':') ? `[${target.host}]` : target.host
  return `${host}:${target.port}`
}

function buildUpstreamHeaders(target: MuxStreamUpstream, declareBody: boolean): [string, string][] {
  const headers: [string, string][] = target.headers.map(([name, value]) => [name, value])
  headers.push(['host', upstreamAuthority(target)])
  if (declareBody && !target.headers.some(([name]) => name.toLowerCase() === 'content-length')) {
    headers.push(['transfer-encoding', 'chunked'])
  }
  if (target.cookie !== undefined && target.cookie.length > 0) {
    headers.push(['cookie', target.cookie])
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

function filterResponseHeaders(rawHeaders: readonly string[]): [string, string][] {
  const result: [string, string][] = []
  for (let index = 0; index + 1 < rawHeaders.length; index += 2) {
    const name = rawHeaders[index]
    const value = rawHeaders[index + 1]
    if (name === undefined || value === undefined || DROP_RESPONSE_HEADERS.has(name.toLowerCase())) {
      continue
    }
    result.push([name, value])
  }
  return result
}

function abortError(): Error {
  const error = new Error('stream adapter aborted')
  error.name = 'AbortError'
  return error
}

/** Releases a late resolver result exactly once even if it arrived after the stream ended. */
function releaseResolved(target: MuxStreamUpstream): void {
  try {
    target.release()
  } catch {
    // A resolver's bookkeeping failure cannot revive a closed logical stream.
  }
}

/** Stable wire-safe messages: local host, path and error text never reach RESET. */
const RESET_MESSAGES = {
  READY_TIMEOUT: 'upstream did not become ready in time',
  UPSTREAM_FAILED: 'view upstream is unavailable',
  RESPONSE_FAILED: 'upstream response failed',
  RESPONSE_TIMEOUT: 'upstream response timed out',
  WS_UPSTREAM_FAILED: 'upstream websocket failed',
  WS_CLOSE_TIMEOUT: 'websocket close timed out',
  VIEW_CLOSED: 'view binding is no longer active',
} as const

class StreamBridge {
  private readonly abort = new AbortController()
  private upstreamRequest: http.ClientRequest | null = null
  private upstreamResponse: IncomingMessage | null = null
  private upstreamSocket: UpstreamWebSocket | null = null
  private upstreamTarget: MuxStreamUpstream | null = null
  private upstreamSignal: AbortSignal | null = null
  private firstResponseTimer: NodeJS.Timeout | undefined
  private idleTimer: NodeJS.Timeout | undefined
  private closeTimer: NodeJS.Timeout | undefined
  private readyTimer: NodeJS.Timeout | undefined
  private requestEnded = false
  private requestEndWritten = false
  private localWsClose = false
  private wsMessageOpen = false
  private finished = false
  private readonly onTargetAbort = (): void => {
    void this.owner.failStream(this, 'VIEW_CLOSED', RESET_MESSAGES.VIEW_CLOSED)
  }

  constructor(
    private readonly owner: ConnectionRuntime,
    readonly stream: MuxStream,
    private readonly resolveView: MuxViewResolver,
    private readonly timeouts: {
      ready: number
      firstResponse: number
      idle: number
      wsClose: number
      wsHandshake: number
    },
  ) {}

  get viewId(): string {
    return this.stream.request.viewId
  }

  get bindingEpoch(): string {
    return this.stream.request.bindingEpoch
  }

  get isFinished(): boolean {
    return this.finished
  }

  async start(): Promise<void> {
    this.armReadyTimer()
    try {
      if (this.stream.mode === 'HTTP') {
        await this.startHttp()
      } else {
        await this.startWebSocket()
      }
    } catch {
      if (!this.finished) {
        await this.owner.failStream(this, 'UPSTREAM_FAILED', RESET_MESSAGES.UPSTREAM_FAILED)
      }
    }
  }

  /** DATA from Studio: resolve only after the private upstream accepted and flushed the bytes. */
  async receive(data: DataFrame): Promise<void> {
    if (this.finished) {
      throw new MuxError('stream adapter is closed')
    }
    if (this.stream.mode === 'HTTP') {
      if (data.flags.kind !== 'http') {
        throw new MuxError('HTTP DATA has WS flags')
      }
      const request = this.upstreamRequest
      if (request === null || this.requestEnded) {
        throw new MuxError('upstream request is not writable')
      }
      await this.writeRequest(request, Buffer.from(data.payload))
      return
    }
    if (data.flags.kind !== 'ws') {
      throw new MuxError('WS DATA is missing fragment flags')
    }
    const socket = this.upstreamSocket
    if (socket === null) {
      throw new MuxError('upstream websocket is not connected')
    }
    const first = !this.wsMessageOpen
    const opcode = first ? (data.flags.text ? 'text' : 'binary') : 'continuation'
    await socket.sendFragment(opcode, Buffer.from(data.payload), data.flags.fin)
    if (this.finished) {
      throw abortError()
    }
    this.wsMessageOpen = !data.flags.fin
  }

  /** Studio request END: half-close the local request independently of the response. */
  async endRequest(): Promise<void> {
    if (this.finished) {
      return
    }
    const request = this.upstreamRequest
    if (request === null || this.requestEnded) {
      throw new MuxError('upstream request cannot be ended')
    }
    this.requestEnded = true
    await new Promise<void>((resolve, reject) => {
      if (request.destroyed) {
        reject(new MuxError('upstream request is closed'))
        return
      }
      const signal = this.abort.signal
      const onAbort = (): void => {
        cleanup()
        reject(abortError())
      }
      const cleanup = (): void => {
        signal.removeEventListener('abort', onAbort)
      }
      if (signal.aborted) {
        cleanup()
        reject(abortError())
        return
      }
      signal.addEventListener('abort', onAbort, { once: true })
      request.end(() => {
        cleanup()
        this.requestEndWritten = true
        resolve()
      })
    })
  }

  /** The engine only calls this after the response END write and peer COMPLETE both succeeded. */
  complete(): void {
    this.dispose()
  }

  /** Peer RESET or a local downstream failure: cancel the private exchange without replay. */
  reset(): void {
    this.dispose()
  }

  /** Peer WS CLOSE after all prior inbound DATA was consumed. */
  async receiveClose(close: ClosePayload): Promise<void> {
    if (this.finished) {
      return
    }
    this.armCloseTimer()
    try {
      if (!this.localWsClose) {
        this.localWsClose = true
        await this.stream.close(close.code, close.reason)
      }
      await this.closeUpstream(close)
    } finally {
      this.clearCloseTimer()
      this.dispose()
    }
  }

  dispose(): void {
    if (this.finished) {
      return
    }
    this.finished = true
    this.abort.abort()
    this.clearReadyTimer()
    this.clearFirstResponseTimer()
    this.clearIdleTimer()
    this.clearCloseTimer()
    this.releaseTarget()
    this.upstreamRequest?.destroy()
    this.upstreamResponse?.destroy()
    this.upstreamSocket?.destroy()
    this.upstreamRequest = null
    this.upstreamResponse = null
    this.upstreamSocket = null
  }

  private async resolveTarget(): Promise<MuxStreamUpstream> {
    const request: MuxViewRequest = {
      mode: this.stream.request.mode,
      method: this.stream.request.method,
      path: this.stream.request.path,
      headers: this.stream.request.headers.map(([name, value]) => [name, value]),
    }
    const resolved = await this.resolveView(this.stream.request.viewId, this.stream.request.bindingEpoch, request)
    // The resolver's async view check can overlap a reset or a newer claim. Re-check this exact
    // stream before any local socket is opened, and release a late lifecycle reservation.
    if (this.finished || this.abort.signal.aborted || !this.owner.hasStream(this.stream)) {
      releaseResolved(resolved)
      throw abortError()
    }
    let target: MuxStreamUpstream
    try {
      target = validateUpstream(resolved)
    } catch (error) {
      releaseResolved(resolved)
      throw error
    }
    if (target.signal.aborted) {
      releaseResolved(target)
      throw abortError()
    }
    this.upstreamTarget = target
    target.signal.addEventListener('abort', this.onTargetAbort, { once: true })
    this.upstreamSignal = AbortSignal.any([this.abort.signal, target.signal])
    return target
  }

  private async startHttp(): Promise<void> {
    const target = await this.resolveTarget()
    const request = http.request({
      host: target.host,
      port: target.port,
      method: this.stream.request.method.toUpperCase(),
      path: target.path,
      headers: headerRecord(buildUpstreamHeaders(target, true)),
      agent: false,
      signal: this.upstreamSignal ?? this.abort.signal,
    })
    this.upstreamRequest = request
    request.on('error', () => {
      if (!this.finished && !this.requestEndWritten) {
        void this.owner.failStream(this, 'UPSTREAM_FAILED', RESET_MESSAGES.UPSTREAM_FAILED)
      }
    })
    request.on('response', (response) => {
      this.clearFirstResponseTimer()
      this.upstreamResponse = response
      response.on('error', () => {
        if (!this.finished) {
          void this.owner.failStream(this, 'RESPONSE_FAILED', RESET_MESSAGES.RESPONSE_FAILED)
        }
      })
      response.on('aborted', () => {
        if (!this.finished) {
          void this.owner.failStream(this, 'RESPONSE_FAILED', RESET_MESSAGES.RESPONSE_FAILED)
        }
      })
      void this.pumpResponse(response)
    })
    await this.stream.accept()
    this.clearReadyTimer()
    if (this.finished) {
      return
    }
    request.flushHeaders()
    this.armFirstResponseTimer()
  }

  private async pumpResponse(response: IncomingMessage): Promise<void> {
    try {
      if (this.finished) {
        return
      }
      const status = response.statusCode
      if (status === undefined || status < 100 || status > 599) {
        throw new MuxError('invalid upstream response status')
      }
      const headers: HeadersPayload = { status, headers: filterResponseHeaders(response.rawHeaders) }
      await this.stream.sendHeaders(headers)
      this.armIdleTimer()
      for await (const chunk of response) {
        if (this.finished) {
          return
        }
        const payload = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk as Uint8Array)
        if (payload.byteLength > 0) {
          this.armIdleTimer()
        }
        let offset = 0
        while (offset < payload.byteLength) {
          const end = Math.min(offset + MUX_MAX_PAYLOAD_BYTES, payload.byteLength)
          await this.stream.write(payload.subarray(offset, end))
          offset = end
        }
        if (this.finished) {
          return
        }
      }
      this.clearIdleTimer()
      if (!response.complete) {
        throw new MuxError('upstream response ended before completion')
      }
      await this.stream.end()
    } catch {
      if (!this.finished) {
        await this.owner.failStream(this, 'RESPONSE_FAILED', RESET_MESSAGES.RESPONSE_FAILED)
      }
    }
  }

  private async startWebSocket(): Promise<void> {
    const target = await this.resolveTarget()
    const socket = await UpstreamWebSocket.connect({
      host: target.host,
      port: target.port,
      path: target.path,
      protocols: target.protocols ?? [],
      headers: buildUpstreamHeaders(target, false),
      signal: this.upstreamSignal ?? this.abort.signal,
      handshakeTimeoutMillis: this.timeouts.wsHandshake,
    })
    if (this.finished || !this.owner.hasStream(this.stream)) {
      socket.destroy()
      return
    }
    this.upstreamSocket = socket
    await this.stream.accept()
    this.clearReadyTimer()
    if (this.finished) {
      return
    }
    const headers: [string, string][] = socket.protocol === undefined ? [] : [['sec-websocket-protocol', socket.protocol]]
    await this.stream.sendHeaders({ status: 101, headers })
    if (this.finished) {
      return
    }
    void this.pumpUpstream(socket)
  }

  private async pumpUpstream(socket: UpstreamWebSocket): Promise<void> {
    try {
      for await (const frame of socket.frames()) {
        if (this.finished) {
          return
        }
        if (frame.type === 'data') {
          await this.stream.write(Buffer.from(frame.payload), { text: frame.kind === 'text', fin: frame.last })
          continue
        }
        await this.handleUpstreamClose(frame.code, frame.reason)
        return
      }
    } catch {
      if (!this.finished) {
        await this.owner.failStream(this, 'WS_UPSTREAM_FAILED', RESET_MESSAGES.WS_UPSTREAM_FAILED)
      }
    }
  }

  private async handleUpstreamClose(code: number, reason: string): Promise<void> {
    if (this.finished) {
      return
    }
    if (!isValidCloseCode(code)) {
      await this.owner.failStream(this, 'WS_UPSTREAM_FAILED', RESET_MESSAGES.WS_UPSTREAM_FAILED)
      return
    }
    this.armCloseTimer()
    this.localWsClose = true
    try {
      await this.stream.close(code, reason)
    } catch {
      if (!this.finished) {
        await this.owner.failStream(this, 'WS_UPSTREAM_FAILED', RESET_MESSAGES.WS_UPSTREAM_FAILED)
      }
    }
    // Keep the bridge until the peer CLOSE arrives; the close timer bounds a missing response.
  }

  private async closeUpstream(close: ClosePayload): Promise<void> {
    const socket = this.upstreamSocket
    if (socket === null) {
      return
    }
    try {
      await socket.close(close.code, close.reason)
    } catch {
      socket.destroy()
    }
  }

  private writeRequest(request: http.ClientRequest, payload: Buffer): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      if (request.destroyed) {
        reject(new MuxError('upstream request is closed'))
        return
      }
      const signal = this.abort.signal
      const onAbort = (): void => {
        cleanup()
        reject(abortError())
      }
      const cleanup = (): void => {
        signal.removeEventListener('abort', onAbort)
      }
      if (signal.aborted) {
        cleanup()
        reject(abortError())
        return
      }
      signal.addEventListener('abort', onAbort, { once: true })
      request.write(payload, (error?: Error | null) => {
        cleanup()
        if (error) {
          reject(error)
          return
        }
        resolve()
      })
    })
  }

  private releaseTarget(): void {
    const target = this.upstreamTarget
    if (target === null) {
      return
    }
    this.upstreamTarget = null
    this.upstreamSignal = null
    target.signal.removeEventListener('abort', this.onTargetAbort)
    releaseResolved(target)
  }

  private armReadyTimer(): void {
    this.clearReadyTimer()
    const timer = setTimeout(() => {
      this.readyTimer = undefined
      void this.owner.failStream(this, 'READY_TIMEOUT', RESET_MESSAGES.READY_TIMEOUT)
    }, this.timeouts.ready)
    timer.unref()
    this.readyTimer = timer
  }

  private clearReadyTimer(): void {
    if (this.readyTimer !== undefined) {
      clearTimeout(this.readyTimer)
      this.readyTimer = undefined
    }
  }

  private armFirstResponseTimer(): void {
    this.clearFirstResponseTimer()
    const timer = setTimeout(() => {
      this.firstResponseTimer = undefined
      void this.owner.failStream(this, 'RESPONSE_TIMEOUT', RESET_MESSAGES.RESPONSE_TIMEOUT)
    }, this.timeouts.firstResponse)
    timer.unref()
    this.firstResponseTimer = timer
  }

  private clearFirstResponseTimer(): void {
    if (this.firstResponseTimer !== undefined) {
      clearTimeout(this.firstResponseTimer)
      this.firstResponseTimer = undefined
    }
  }

  private armIdleTimer(): void {
    this.clearIdleTimer()
    const timer = setTimeout(() => {
      this.idleTimer = undefined
      void this.owner.failStream(this, 'RESPONSE_TIMEOUT', RESET_MESSAGES.RESPONSE_TIMEOUT)
    }, this.timeouts.idle)
    timer.unref()
    this.idleTimer = timer
  }

  private clearIdleTimer(): void {
    if (this.idleTimer !== undefined) {
      clearTimeout(this.idleTimer)
      this.idleTimer = undefined
    }
  }

  private armCloseTimer(): void {
    this.clearCloseTimer()
    const timer = setTimeout(() => {
      this.closeTimer = undefined
      void this.owner.failStream(this, 'WS_CLOSE_TIMEOUT', RESET_MESSAGES.WS_CLOSE_TIMEOUT)
    }, this.timeouts.wsClose)
    timer.unref()
    this.closeTimer = timer
  }

  private clearCloseTimer(): void {
    if (this.closeTimer !== undefined) {
      clearTimeout(this.closeTimer)
      this.closeTimer = undefined
    }
  }
}

class ConnectionRuntime implements MuxDataConnection {
  private readonly engine: MuxConnection
  private readonly bridges = new Map<number, StreamBridge>()
  private readonly readyTimeout: number
  private readonly firstResponseTimeout: number
  private readonly idleTimeout: number
  private readonly wsCloseTimeout: number
  private readonly wsHandshakeTimeout: number
  private closed = false
  private hadStream = false

  constructor(private readonly options: MuxDataConnectionOptions) {
    this.readyTimeout = positiveTimeout(options.readyTimeoutMillis, DEFAULT_READY_TIMEOUT_MILLIS)
    this.firstResponseTimeout = positiveTimeout(options.httpFirstResponseTimeoutMillis, DEFAULT_HTTP_TIMEOUT_MILLIS)
    this.idleTimeout = positiveTimeout(options.httpIdleTimeoutMillis, DEFAULT_HTTP_TIMEOUT_MILLIS)
    this.wsCloseTimeout = positiveTimeout(options.wsCloseTimeoutMillis, DEFAULT_WS_CLOSE_TIMEOUT_MILLIS)
    this.wsHandshakeTimeout = positiveTimeout(options.wsHandshakeTimeoutMillis, DEFAULT_WS_HANDSHAKE_TIMEOUT_MILLIS)
    this.engine = createMuxConnection({
      role: 'daemon',
      send: options.send,
      onOpen: (stream) => this.openStream(stream),
      onData: (stream, data) => {
        const bridge = this.bridges.get(stream.id)
        return bridge === undefined ? Promise.reject(new MuxError('stream is not bridged')) : bridge.receive(data)
      },
      onEnd: (stream) => {
        const bridge = this.bridges.get(stream.id)
        return bridge === undefined ? Promise.reject(new MuxError('stream is not bridged')) : bridge.endRequest()
      },
      onComplete: (stream) => {
        const bridge = this.bridges.get(stream.id)
        if (bridge !== undefined) {
          bridge.complete()
          this.finishBridge(stream.id, bridge)
        }
      },
      onReset: (streamId) => {
        const bridge = this.bridges.get(streamId)
        if (bridge !== undefined) {
          bridge.reset()
          this.finishBridge(streamId, bridge)
        }
      },
      onClose: (stream, close) => {
        const bridge = this.bridges.get(stream.id)
        if (bridge === undefined) {
          return
        }
        return bridge
          .receiveClose(close)
          .catch(() => undefined)
          .finally(() => this.finishBridge(stream.id, bridge))
      },
      onFatal: (error) => this.fatal(error),
    })
  }

  activeStreamCount(): number {
    return this.bridges.size
  }

  hasStream(stream: MuxStream): boolean {
    return !this.closed && this.bridges.get(stream.id)?.stream === stream
  }

  async failStream(bridge: StreamBridge, code: string, message: string): Promise<void> {
    if (bridge.isFinished) {
      return
    }
    bridge.dispose()
    this.finishBridge(bridge.stream.id, bridge)
    await bridge.stream.reset(code, message).catch(() => undefined)
  }

  resetView(viewId: string, bindingEpoch: string): Promise<number> {
    const matches = [...this.bridges.values()].filter(
      (bridge) => bridge.viewId === viewId && bridge.bindingEpoch === bindingEpoch,
    )
    return Promise.all(matches.map((bridge) => this.failStream(bridge, 'VIEW_CLOSED', RESET_MESSAGES.VIEW_CLOSED))).then(
      () => matches.length,
    )
  }

  receive(message: Uint8Array): void {
    this.engine.receive(message)
  }

  async close(): Promise<void> {
    if (this.closed) {
      return
    }
    this.closed = true
    this.engine.shutdown()
    for (const bridge of [...this.bridges.values()]) {
      bridge.dispose()
    }
    this.bridges.clear()
  }

  isClosed(): boolean {
    return this.closed
  }

  private openStream(stream: MuxStream): void {
    if (this.closed) {
      void stream.reset('UPSTREAM_FAILED', RESET_MESSAGES.UPSTREAM_FAILED).catch(() => undefined)
      return
    }
    if (this.bridges.has(stream.id)) {
      void stream.reset('PROTOCOL_ERROR', 'duplicate logical stream').catch(() => undefined)
      return
    }
    const bridge = new StreamBridge(this, stream, this.options.resolveView, {
      ready: this.readyTimeout,
      firstResponse: this.firstResponseTimeout,
      idle: this.idleTimeout,
      wsClose: this.wsCloseTimeout,
      wsHandshake: this.wsHandshakeTimeout,
    })
    this.bridges.set(stream.id, bridge)
    this.hadStream = true
    void bridge.start()
  }

  private finishBridge(streamId: number, bridge: StreamBridge): void {
    if (this.bridges.get(streamId) !== bridge) {
      return
    }
    this.bridges.delete(streamId)
    if (this.bridges.size === 0 && this.hadStream && !this.closed) {
      this.hadStream = false
      try {
        this.options.onIdle?.()
      } catch {
        // The transport is already converging; an admission callback cannot reopen it.
      }
    }
  }

  private fatal(error: MuxError): void {
    if (this.closed) {
      return
    }
    this.closed = true
    for (const bridge of [...this.bridges.values()]) {
      bridge.dispose()
    }
    this.bridges.clear()
    try {
      this.options.onFatal?.(error)
    } catch {
      // Terminal notification failure cannot restore a failed physical writer.
    }
  }
}

/** Creates the per-DATA_BIND adapter. There is exactly one mux writer for the supplied physical send. */
export function createMuxDataConnection(options: MuxDataConnectionOptions): MuxDataConnection {
  return new ConnectionRuntime(options)
}
