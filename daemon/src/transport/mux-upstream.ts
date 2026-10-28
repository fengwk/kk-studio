import { Buffer } from 'node:buffer'

import WebSocket, { type RawData } from 'ws'

import { getOutboundNetwork, type WebSocketAgent } from '../network.js'
import { validateDataBindOrigin, type DataBind } from '../protocol.js'
import { MUX_FRAME_HEADER_BYTES, MUX_MAX_PAYLOAD_BYTES } from './mux-wire.js'
import { createMuxDataConnection, type MuxDataConnection, type MuxViewResolver } from './mux-connection.js'

/**
 * Owner of the single accepted DATA_BIND WebSocket for the current control generation.
 *
 * <p>{@link MuxUpstreamService.bind} accepts only a strictly newer data generation, validates the Studio
 * same-origin endpoint and connects with the process outbound network policy (proxy-aware). The ticket
 * is attached to the already controlled URL as the `ticket` query parameter and is never logged. A new
 * binding supersedes the previous socket; callbacks captured by the old binding cannot close the new
 * one. There is no automatic replay, reconnect or control request.
 *
 * <p>The physical socket uses native WebSocket ping/pong (15s probe, 45s without a pong fails), rejects
 * text messages and bounds each binary message to one wire frame at exactly 65548 bytes. The per-stream
 * bridging and credit accounting live in {@link MuxDataConnection}.
 */

/** Maximum DATA_BIND binary message: 12-byte mux header plus the 65536-byte maximum payload. */
export const MUX_DATA_MESSAGE_BYTES = MUX_FRAME_HEADER_BYTES + MUX_MAX_PAYLOAD_BYTES
export const MUX_DATA_PING_INTERVAL_MILLIS = 15_000
export const MUX_DATA_PONG_TIMEOUT_MILLIS = 45_000
const DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000

/** Safe transport error; never includes the ticket, URL or upstream payload. */
export class MuxUpstreamError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'MuxUpstreamError'
  }
}

export interface MuxUpstreamOptions {
  /** Configured Studio origin used to validate every DATA_BIND endpoint. */
  studioUrl: string
  resolveView: MuxViewResolver
  /** The current binding became completely idle (no logical streams remain). */
  onIdle?: () => void
  /** The current physical DATA_BIND connection terminated; stale generations do not notify. */
  onDataClose?: (error: Error | null) => void
  connectTimeoutMillis?: number
  pingIntervalMillis?: number
  pongTimeoutMillis?: number
  readyTimeoutMillis?: number
  httpFirstResponseTimeoutMillis?: number
  httpIdleTimeoutMillis?: number
  wsCloseTimeoutMillis?: number
  wsHandshakeTimeoutMillis?: number
}

export interface MuxUpstreamService {
  /**
   * Accepts one DATA_BIND binding. The returned promise represents the binding lifetime: it rejects if
   * the socket cannot be established and resolves when this accepted generation closes or is replaced.
   */
  bind(binding: DataBind, signal: AbortSignal): Promise<void>
  /**
   * Closes the current DATA_BIND binding and waits for its logical streams to converge. The service
   * remains able to accept a later binding generation; a local close does not notify onDataClose.
   */
  close(): Promise<void>
  /** Permanently stops the service, closes the current binding, and rejects later bindings. */
  shutdown(): Promise<void>
  activeStreamCount(): number
  /** Reset every stream of the exact view+epoch on the current binding. */
  resetView(viewId: string, bindingEpoch: string): Promise<number>
}

interface ConnectionPlan {
  agent: WebSocketAgent['agent']
  destroy(): void
}

interface ConnectResult {
  socket: WebSocket
  plan: ConnectionPlan
  deadline: AbortSignal
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

function positiveTimeout(value: number | undefined, fallback: number): number {
  if (value === undefined) {
    return fallback
  }
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new MuxUpstreamError('data transport timeout must be a positive integer')
  }
  return value
}

function dataGeneration(value: string): bigint {
  if (typeof value !== 'string' || !/^[1-9][0-9]*$/.test(value)) {
    throw new MuxUpstreamError('DATA_BIND generation must be a positive decimal string')
  }
  return BigInt(value)
}

/**
 * Converts the controlled HTTP(S) stream URL to the WebSocket endpoint and binds the one-use ticket.
 * A URL that already carries a different or repeated ticket is rejected rather than rewritten.
 */
export function dataBindWebSocketUrl(binding: DataBind): string {
  let url: URL
  try {
    url = new URL(binding.streamUrl)
  } catch {
    throw new MuxUpstreamError('DATA_BIND streamUrl is invalid')
  }
  if (url.protocol === 'http:') {
    url.protocol = 'ws:'
  } else if (url.protocol === 'https:') {
    url.protocol = 'wss:'
  } else {
    throw new MuxUpstreamError('DATA_BIND streamUrl must use HTTP or HTTPS')
  }
  if (url.username !== '' || url.password !== '' || url.hash !== '') {
    throw new MuxUpstreamError('DATA_BIND streamUrl must not contain credentials or a fragment')
  }
  const tickets = url.searchParams.getAll('ticket')
  if (tickets.length > 1 || (tickets.length === 1 && tickets[0] !== binding.ticket)) {
    throw new MuxUpstreamError('DATA_BIND ticket does not match streamUrl')
  }
  if (tickets.length === 0) {
    url.searchParams.set('ticket', binding.ticket)
  }
  return url.toString()
}

function abortReason(deadline: AbortSignal): MuxUpstreamError {
  const reason = deadline.reason as { name?: unknown } | undefined
  return new MuxUpstreamError(
    reason?.name === 'TimeoutError' ? 'data websocket connection timed out' : 'data websocket aborted',
  )
}

async function waitForOpen(socket: WebSocket, deadline: AbortSignal): Promise<void> {
  if (deadline.aborted) {
    throw abortReason(deadline)
  }
  await new Promise<void>((resolve, reject) => {
    let settled = false
    const finish = (error?: Error): void => {
      if (settled) {
        return
      }
      settled = true
      deadline.removeEventListener('abort', onAbort)
      socket.off('open', onOpen)
      socket.off('error', onError)
      socket.off('close', onClose)
      if (error === undefined) {
        resolve()
      } else {
        reject(error)
      }
    }
    const onOpen = (): void => finish()
    const onError = (): void => finish(new MuxUpstreamError('data websocket connection failed'))
    const onClose = (): void => finish(new MuxUpstreamError('data websocket closed before opening'))
    const onAbort = (): void => finish(abortReason(deadline))
    deadline.addEventListener('abort', onAbort, { once: true })
    socket.once('open', onOpen)
    socket.once('error', onError)
    socket.once('close', onClose)
  })
}

/**
 * Creates the physical socket without waiting for its open event. Callers must attach their message,
 * error and close handlers immediately; the server is allowed to send its first frame as soon as the
 * handshake completes and no listener may be installed after that window.
 */
async function prepareSocket(
  streamUrl: string,
  signal: AbortSignal,
  timeoutMillis: number,
): Promise<ConnectResult> {
  const deadline = AbortSignal.any([signal, AbortSignal.timeout(timeoutMillis)])
  const plan = await getOutboundNetwork().createWebSocketAgent(streamUrl, deadline)
  if (deadline.aborted) {
    plan.destroy()
    throw abortReason(deadline)
  }
  let socket: WebSocket
  try {
    socket = new WebSocket(streamUrl, {
      perMessageDeflate: false,
      maxPayload: MUX_DATA_MESSAGE_BYTES,
      followRedirects: false,
      handshakeTimeout: timeoutMillis,
      agent: plan.agent,
    })
  } catch {
    plan.destroy()
    throw new MuxUpstreamError('data websocket connection failed')
  }
  socket.once('close', () => plan.destroy())
  socket.once('error', () => plan.destroy())
  return { socket, plan, deadline }
}

function startHeartbeat(
  socket: WebSocket,
  intervalMillis: number,
  timeoutMillis: number,
  onFailure: (error: Error) => void,
): () => void {
  let lastPong = Date.now()
  let stopped = false
  const onPong = (): void => {
    lastPong = Date.now()
  }
  socket.on('pong', onPong)
  const timer = setInterval(() => {
    if (stopped) {
      return
    }
    if (Date.now() - lastPong >= timeoutMillis) {
      stop()
      onFailure(new MuxUpstreamError('data websocket pong timeout'))
      return
    }
    try {
      socket.ping()
    } catch {
      stop()
      onFailure(new MuxUpstreamError('data websocket ping failed'))
    }
  }, intervalMillis)
  timer.unref()
  function stop(): void {
    if (stopped) {
      return
    }
    stopped = true
    clearInterval(timer)
    socket.off('pong', onPong)
  }
  return stop
}

class ActiveBinding {
  private readonly abort = new AbortController()
  private readonly completion: Promise<void>
  private resolveCompletion!: () => void
  private rejectCompletion!: (error: Error) => void
  private socket: WebSocket | null = null
  private connection: MuxDataConnection | null = null
  private stopHeartbeat: (() => void) | null = null
  private settled = false
  private opened = false
  private error: Error | null = null
  private readonly onAbort: () => void

  constructor(
    readonly generation: string,
    readonly binding: DataBind,
    private readonly streamUrl: string,
    private readonly service: UpstreamServiceState,
    private readonly signal: AbortSignal,
  ) {
    this.completion = new Promise<void>((resolve, reject) => {
      this.resolveCompletion = resolve
      this.rejectCompletion = reject
    })
    this.onAbort = (): void => {
      if (!this.settled) {
        this.close(false)
      }
    }
  }

  get done(): Promise<void> {
    return this.completion
  }

  start(options: MuxUpstreamOptions, timeoutMillis: number): void {
    if (this.signal.aborted) {
      this.finish(null)
      return
    }
    this.signal.addEventListener('abort', this.onAbort, { once: true })
    void this.connect(options, timeoutMillis)
  }

  activeStreamCount(): number {
    return this.connection?.activeStreamCount() ?? 0
  }

  async resetView(viewId: string, bindingEpoch: string): Promise<number> {
    return this.connection === null ? 0 : this.connection.resetView(viewId, bindingEpoch)
  }

  /** Close and converge. `notify` is false when a newer binding superseded this one. */
  close(notify: boolean): void {
    if (this.settled) {
      return
    }
    this.error ??= null
    this.abort.abort()
    this.connection?.close()
    const socket = this.socket
    if (socket !== null) {
      socket.terminate()
    }
    this.finish(this.error, notify)
  }

  private async connect(options: MuxUpstreamOptions, timeoutMillis: number): Promise<void> {
    let result: ConnectResult
    try {
      result = await prepareSocket(this.streamUrl, this.abort.signal, timeoutMillis)
    } catch (error) {
      if (!this.settled) {
        this.fail(error instanceof Error ? error : new MuxUpstreamError('data websocket connection failed'), false)
      }
      return
    }
    const socket = result.socket
    if (this.settled) {
      result.plan.destroy()
      socket.terminate()
      return
    }
    this.socket = socket
    try {
      this.connection = createMuxDataConnection({
        send: (frame) => this.send(frame),
        resolveView: options.resolveView,
        onIdle: () => {
          if (this.service.isCurrent(this)) {
            options.onIdle?.()
          }
        },
        onFatal: (error) => {
          this.fail(error, true)
        },
        readyTimeoutMillis: options.readyTimeoutMillis,
        httpFirstResponseTimeoutMillis: options.httpFirstResponseTimeoutMillis,
        httpIdleTimeoutMillis: options.httpIdleTimeoutMillis,
        wsCloseTimeoutMillis: options.wsCloseTimeoutMillis,
        wsHandshakeTimeoutMillis: options.wsHandshakeTimeoutMillis,
      })
    } catch (error) {
      result.plan.destroy()
      socket.terminate()
      this.fail(error instanceof Error ? error : new MuxUpstreamError('data stream adapter failed'), false)
      return
    }
    // Attach before awaiting `open`: a server may send its first frame immediately after the
    // handshake, and an EventEmitter does not replay messages to a late listener.
    socket.on('message', (data, isBinary) => {
      if (!isBinary) {
        this.fail(new MuxUpstreamError('data websocket received a text message'), true)
        return
      }
      const message = toBuffer(data)
      if (message.byteLength > MUX_DATA_MESSAGE_BYTES) {
        this.fail(new MuxUpstreamError('data websocket message exceeds the frame limit'), true)
        return
      }
      this.connection?.receive(message)
    })
    socket.on('error', () => {
      this.fail(new MuxUpstreamError('data websocket failed'), true)
    })
    socket.on('close', (code) => {
      this.fail(
        this.opened && code === 1000 ? null : new MuxUpstreamError('data websocket closed'),
        false,
      )
    })
    try {
      await waitForOpen(socket, result.deadline)
    } catch (error) {
      result.plan.destroy()
      socket.terminate()
      this.fail(error instanceof Error ? error : new MuxUpstreamError('data websocket connection failed'), false)
      return
    }
    if (this.settled) {
      result.plan.destroy()
      socket.terminate()
      return
    }
    this.opened = true
    this.stopHeartbeat = startHeartbeat(
      socket,
      positiveTimeout(options.pingIntervalMillis, MUX_DATA_PING_INTERVAL_MILLIS),
      positiveTimeout(options.pongTimeoutMillis, MUX_DATA_PONG_TIMEOUT_MILLIS),
      (error) => this.fail(error, true),
    )
  }

  private send(frame: Uint8Array): Promise<void> {
    const socket = this.socket
    if (socket === null || socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new MuxUpstreamError('data websocket is not open'))
    }
    return new Promise<void>((resolve, reject) => {
      try {
        socket.send(frame, { binary: true }, (error?: Error) => {
          if (error) {
            reject(new MuxUpstreamError('data websocket write failed'))
          } else {
            resolve()
          }
        })
      } catch {
        reject(new MuxUpstreamError('data websocket write failed'))
      }
    })
  }

  private fail(error: Error | null, terminate: boolean): void {
    if (this.settled) {
      return
    }
    this.error = error
    if (terminate) {
      this.socket?.terminate()
    }
    this.connection?.close()
    this.finish(error, true)
  }

  private finish(error: Error | null, notify = true): void {
    if (this.settled) {
      return
    }
    this.settled = true
    this.detachSignal()
    this.stopHeartbeat?.()
    this.stopHeartbeat = null
    this.connection?.close()
    if (this.opened) {
      this.resolveCompletion()
    } else if (error !== null) {
      this.rejectCompletion(error)
    } else {
      this.resolveCompletion()
    }
    if (notify && this.opened) {
      this.service.notifyClose(this, error)
    }
  }

  private detachSignal(): void {
    this.signal.removeEventListener('abort', this.onAbort)
  }
}

class UpstreamServiceState {
  private readonly bindings = new Set<ActiveBinding>()
  private currentBinding: ActiveBinding | null = null
  private highestGeneration: bigint | null = null
  private closed = false
  private shutdownPromise: Promise<void> | undefined
  private readonly connectTimeout: number
  private readonly options: MuxUpstreamOptions

  constructor(options: MuxUpstreamOptions) {
    this.options = options
    this.connectTimeout = positiveTimeout(options.connectTimeoutMillis, DEFAULT_CONNECT_TIMEOUT_MILLIS)
  }

  bind(binding: DataBind, signal: AbortSignal): Promise<void> {
    if (this.closed) {
      return Promise.reject(new MuxUpstreamError('data upstream service is closed'))
    }
    let generation: bigint
    let streamUrl: string
    try {
      validateDataBindOrigin(binding, this.options.studioUrl)
      // Validate the ticket form before it becomes a URL query parameter.
      if (!/^[A-Za-z0-9_-]{43}$/.test(binding.ticket)) {
        throw new MuxUpstreamError('DATA_BIND ticket is invalid')
      }
      streamUrl = dataBindWebSocketUrl(binding)
      generation = dataGeneration(binding.generation)
    } catch (error) {
      return Promise.reject(error)
    }
    if (this.highestGeneration !== null && generation <= this.highestGeneration) {
      return Promise.reject(new MuxUpstreamError('DATA_BIND generation must be strictly newer'))
    }
    this.highestGeneration = generation
    const previous = this.currentBinding
    const active = new ActiveBinding(binding.generation, binding, streamUrl, this, signal)
    this.currentBinding = active
    this.bindings.add(active)
    // The old socket is superseded only after the new generation was validated. Its close callbacks
    // are identity-checked by isCurrent/notifyClose and cannot touch the new binding.
    if (previous !== null) {
      previous.close(false)
    }
    void active.done
      .finally(() => {
        this.bindings.delete(active)
        if (this.currentBinding === active) {
          this.currentBinding = null
        }
      })
      .catch(() => undefined)
    active.start(this.options, this.connectTimeout)
    return active.done
  }

  activeStreamCount(): number {
    return this.currentBinding?.activeStreamCount() ?? 0
  }

  resetView(viewId: string, bindingEpoch: string): Promise<number> {
    return this.currentBinding?.resetView(viewId, bindingEpoch) ?? Promise.resolve(0)
  }

  async close(): Promise<void> {
    const binding = this.currentBinding
    if (binding === null || this.closed) {
      return
    }
    binding.close(false)
    await binding.done.catch(() => undefined)
  }

  shutdown(): Promise<void> {
    if (this.shutdownPromise === undefined) {
      this.closed = true
      const bindings = [...this.bindings]
      for (const binding of bindings) {
        binding.close(false)
      }
      this.shutdownPromise = Promise.allSettled(bindings.map((binding) => binding.done)).then(() => undefined)
    }
    return this.shutdownPromise
  }

  isCurrent(binding: ActiveBinding): boolean {
    return !this.closed && this.currentBinding === binding && this.bindings.has(binding)
  }

  notifyClose(binding: ActiveBinding, error: Error | null): void {
    if (this.isCurrent(binding) || this.currentBinding === binding) {
      try {
        this.options.onDataClose?.(error)
      } catch {
        // A close notification cannot revive the physical connection.
      }
    }
  }
}

/** Creates the one-socket DATA_BIND owner. No control messages or retries are generated here. */
export function createMuxUpstreamService(options: MuxUpstreamOptions): MuxUpstreamService {
  const state = new UpstreamServiceState(options)
  return {
    bind: (binding, signal) => state.bind(binding, signal),
    close: () => state.close(),
    shutdown: () => state.shutdown(),
    activeStreamCount: () => state.activeStreamCount(),
    resetView: (viewId, bindingEpoch) => state.resetView(viewId, bindingEpoch),
  }
}
