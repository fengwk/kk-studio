import {
  MUX_MAX_PAYLOAD_BYTES,
  allocateStreamId,
  decodeFrame,
  encodeFrame,
  frameCost,
  type ClosePayload,
  type DataFrame,
  type DataFlags,
  type HeadersPayload,
  type MuxFrame,
  type OpenRequest,
  type ResetPayload,
} from './mux-wire.js'

/**
 * Transport-neutral IDE data mux v2 engine.
 *
 * <p>The adapter owns the physical WebSocket and supplies one asynchronous {@link MuxConnectionOptions.send}
 * callback. This engine is the only writer: it serializes frames, prioritizes management frames, gives each
 * logical stream at most one DATA per round-robin pass, and never sends an END or WS CLOSE ahead of that
 * stream's earlier DATA. A rejected send is terminal: the sender is disabled, queued work is rejected and
 * {@link MuxConnectionOptions.onFatal} fires once. There is no retry or replay.
 *
 * <p>Inbound DATA is delivered to {@link MuxConnectionOptions.onData}; credit is returned only after that
 * callback's promise settles. Per-stream and per-connection credits are enforced in both directions, DATA
 * and management queues are bounded, and RESET discards unsent/undelivered buffers while returning connection
 * credit. The monotonic stream-id high-water mark identifies closed streams for late DATA without retaining
 * per-stream tombstones.
 *
 * <p>Roles follow the data wire: a server calls {@link MuxConnection.open}; a daemon receives OPEN through
 * {@link MuxConnectionOptions.onOpen}, then accepts with {@link MuxStream.accept} or replies with
 * {@link MuxStream.reset}. HTTP uses DATA plus END/COMPLETE half-closes. WS uses DATA fragments and CLOSE;
 * close-handshake timers and the physical socket stay with the adapter, which must call
 * {@link MuxConnection.shutdown} when the socket is gone. RESET messages must already be redacted by the
 * caller; this module enforces shape, bounds and control-character safety only.
 */

/** Maximum concurrent logical streams. */
export const MUX_MAX_STREAMS = 64
/** Initial credit for one stream and one direction. */
export const MUX_STREAM_CREDIT = 262_144
/** Initial credit for the whole connection and one direction. */
export const MUX_CONNECTION_CREDIT = 4 * 1024 * 1024
/** Return consumed credit after at least this many bytes, or when the stream/connection drains. */
export const MUX_ACK_THRESHOLD = 64 * 1024
/** Outbound management queue frame limit. */
export const MUX_MAX_MANAGEMENT_FRAMES = 256
/** Outbound management queue byte limit. */
export const MUX_MAX_MANAGEMENT_BYTES = 256 * 1024

export type MuxRole = 'server' | 'daemon'

/** {@link MuxConnectionOptions.send} outcome; rejection is a permanent physical-write failure. */
export type MuxFrameSender = (frame: Uint8Array) => Promise<void> | void

/** One DATA write. HTTP accepts only {@code signal}; WS requires {@code text} and {@code fin}. */
export interface MuxWriteOptions {
  text?: boolean
  fin?: boolean
  /** Cancels only the wait for credit; an already queued frame is not cancellable. */
  signal?: AbortSignal
}

export interface MuxConnectionOptions {
  role: MuxRole
  send: MuxFrameSender
  /** Daemon only: a new OPEN arrived. The stream is pending until accepted or reset. */
  onOpen?: (stream: MuxStream) => void
  /** Server only: the daemon accepted the logical stream. */
  onReady?: (stream: MuxStream) => void
  /** Server only: response headers or the WS 101 handshake result arrived. */
  onHeaders?: (stream: MuxStream, headers: HeadersPayload) => void
  /** DATA is consumed here. Credit returns after the returned promise settles; omitting it discards DATA. */
  onData?: (stream: MuxStream, data: DataFrame) => void | Promise<void>
  /** The peer ended this HTTP direction; queued DATA was already consumed. */
  onEnd?: (stream: MuxStream) => void | Promise<void>
  /** Daemon only: the server confirmed the response END was flushed to the browser. */
  onComplete?: (stream: MuxStream) => void
  /** The peer reset an active stream; late RESET frames for closed ids are ignored. */
  onReset?: (streamId: number, reset: ResetPayload) => void
  /** WS close arrived after queued DATA was consumed. Call {@link MuxStream.close} to complete the handshake. */
  onClose?: (stream: MuxStream, close: ClosePayload) => void | Promise<void>
  /** Terminal protocol or physical-write failure. The adapter must close the physical connection. */
  onFatal?: (error: MuxError) => void
}

/**
 * Adapter-facing logical stream. All async send methods settle after the corresponding physical frame was
 * accepted by the sender (or reject when the stream/connection terminates first).
 */
export interface MuxStream {
  readonly id: number
  readonly request: OpenRequest
  readonly mode: 'HTTP' | 'WS'
  /** Daemon: accept the OPEN by sending READY. */
  accept(): Promise<void>
  /** Daemon: send the single response HEADERS/WS 101 frame. */
  sendHeaders(headers: HeadersPayload): Promise<void>
  /** Send one DATA frame, waiting for credit when necessary. A second wait on the same stream is rejected. */
  write(payload: Uint8Array, options?: MuxWriteOptions): Promise<void>
  /** HTTP: end this direction (request from server, response from daemon). */
  end(): Promise<void>
  /** Server HTTP: confirm the response END was consumed and flushed. */
  complete(): Promise<void>
  /** WS: send CLOSE after all earlier DATA. */
  close(code: number, reason?: string): Promise<void>
  /** Reset both directions immediately and discard unsent/undelivered stream data. */
  reset(code: string, message: string): Promise<void>
}

export interface MuxConnection {
  readonly role: MuxRole
  /** Server: allocate and enqueue an OPEN. Throws for daemon role or exhausted resources. */
  open(request: OpenRequest): MuxStream
  /** Feed exactly one encoded physical frame (one WebSocket binary message). */
  receive(frame: Uint8Array): void
  /** Resolves when all currently queued physical frames have been sent. Pending credit waits are excluded. */
  drain(): Promise<void>
  /** Local physical disconnect: stop the sender and reject every pending operation without onFatal. */
  shutdown(): void
}

/** Engine-level contract/protocol failure. Messages contain no credentials or payload contents. */
export class MuxError extends Error {
  constructor(message: string, cause?: unknown) {
    super(message, cause === undefined ? undefined : { cause })
    this.name = 'MuxError'
  }
}

interface CreditWaiter {
  resolve(): void
  reject(error: Error): void
  signal?: AbortSignal
  onAbort?: () => void
}

interface DataJob {
  frame: Uint8Array
  cost: number
  stream: MuxStreamState
  resolve(): void
  reject(error: Error): void
}

interface ManagementJob {
  frame: Uint8Array
  stream: MuxStreamState | null
  /** END/CLOSE must not overtake queued DATA from the same stream. */
  barrier: boolean
  /** A queued server OPEN must still reach the peer so stream-id allocation stays consecutive. */
  preserveOnClose?: boolean
  resolve(): void
  reject(error: Error): void
  onSent?: () => void
}

interface InboundData {
  frame: DataFrame
  cost: number
}

type Terminal = { kind: 'end' } | { kind: 'close'; close: ClosePayload }

function abortError(): Error {
  const error = new Error('credit wait aborted')
  error.name = 'AbortError'
  return error
}

function isAborted(signal: AbortSignal | undefined): boolean {
  return signal?.aborted === true
}

function asError(error: unknown): Error {
  return error instanceof Error ? error : new Error(String(error))
}

function rejectLater(promise: Promise<void>): void {
  void promise.catch(() => {})
}

class MuxStreamState implements MuxStream {
  closed = false
  accepted = false
  ready = false
  headersSent = false
  headersReceived = false
  localEndQueued = false
  localEndSent = false
  remoteEndReceived = false
  remoteEndConsumed = false
  completeQueued = false
  completeSent = false
  completeReceived = false
  localCloseQueued = false
  localCloseSent = false
  remoteCloseReceived = false
  closeDelivered = false
  sendReserved = 0
  sentUnacked = 0
  receiveReserved = 0
  receiveAckPending = 0
  receiveAckInFlight = 0
  outboundWsType: 'text' | 'binary' | null = null
  inboundWsType: 'text' | 'binary' | null = null
  dataQueue: DataJob[] = []
  delivery: InboundData[] = []
  deliveryRunning = false
  terminal: Terminal | null = null
  creditWaiter: CreditWaiter | null = null

  constructor(
    private readonly connection: DefaultMuxConnection,
    readonly id: number,
    readonly request: OpenRequest,
  ) {}

  get mode(): 'HTTP' | 'WS' {
    return this.request.mode
  }

  accept(): Promise<void> {
    return this.connection.accept(this)
  }

  sendHeaders(headers: HeadersPayload): Promise<void> {
    return this.connection.sendHeaders(this, headers)
  }

  write(payload: Uint8Array, options: MuxWriteOptions = {}): Promise<void> {
    return this.connection.writeData(this, payload, options)
  }

  end(): Promise<void> {
    return this.connection.endStream(this)
  }

  complete(): Promise<void> {
    return this.connection.completeStream(this)
  }

  close(code: number, reason = ''): Promise<void> {
    return this.connection.closeStream(this, code, reason)
  }

  reset(code: string, message: string): Promise<void> {
    return this.connection.resetStream(this, code, message)
  }
}

class DefaultMuxConnection implements MuxConnection {
  private readonly streams = new Map<number, MuxStreamState>()
  private highestAllocated = 0
  private management: ManagementJob[] = []
  private managementBytes = 0
  private dataRoundRobin: MuxStreamState[] = []
  private outboundConnectionReserved = 0
  private outboundConnectionSentUnacked = 0
  private inboundConnectionReserved = 0
  private inboundConnectionAckPending = 0
  private inboundQueuedCount = 0
  private sending = false
  private currentJob: ManagementJob | DataJob | null = null
  private failed = false
  private closed = false
  private fatalError: MuxError | null = null
  private drainWaiters: { resolve(): void; reject(error: Error): void }[] = []

  constructor(readonly role: MuxRole, private readonly options: MuxConnectionOptions) {
    if (role !== 'server' && role !== 'daemon') {
      throw new MuxError('mux role must be server or daemon')
    }
    if (typeof options.send !== 'function') {
      throw new MuxError('mux send callback is required')
    }
  }

  open(request: OpenRequest): MuxStreamState {
    this.ensureUsable()
    if (this.role !== 'server') {
      throw new MuxError('only the server can open logical streams')
    }
    if (this.streams.size >= MUX_MAX_STREAMS) {
      throw new MuxError('concurrent stream limit exceeded')
    }
    const streamId = allocateStreamId(this.highestAllocated)
    const frame = encodeFrame({ type: 'OPEN', streamId, request })
    const stream = new MuxStreamState(this, streamId, {
      ...request,
      headers: request.headers.map(([name, value]) => [name, value]),
    })
    this.highestAllocated = streamId
    this.streams.set(streamId, stream)
    rejectLater(this.enqueueManagement(frame, stream, false, undefined, true))
    return stream
  }

  receive(frame: Uint8Array): void {
    if (this.failed || this.closed) {
      return
    }
    let decoded: MuxFrame
    try {
      decoded = decodeFrame(frame)
    } catch (error) {
      this.fail(new MuxError('invalid mux frame', error))
      return
    }
    try {
      this.handleFrame(decoded)
    } catch (error) {
      this.fail(error instanceof MuxError ? error : new MuxError('mux protocol handler failed', error))
    }
  }

  drain(): Promise<void> {
    if (this.failed) {
      return Promise.reject(this.fatalError ?? new MuxError('mux connection failed'))
    }
    if (this.closed) {
      return Promise.reject(new MuxError('mux connection is closed'))
    }
    if (this.isIdle()) {
      return Promise.resolve()
    }
    return new Promise<void>((resolve, reject) => {
      this.drainWaiters.push({ resolve, reject })
    })
  }

  shutdown(): void {
    if (this.failed || this.closed) {
      return
    }
    this.closed = true
    this.abortAll(new MuxError('mux connection is closed'))
  }

  accept(stream: MuxStreamState): Promise<void> {
    return this.acceptStream(stream)
  }

  sendHeaders(stream: MuxStreamState, headers: HeadersPayload): Promise<void> {
    return this.sendStreamHeaders(stream, headers)
  }

  writeData(stream: MuxStreamState, payload: Uint8Array, options: MuxWriteOptions): Promise<void> {
    return this.writeStreamData(stream, payload, options)
  }

  endStream(stream: MuxStreamState): Promise<void> {
    return this.endHttpStream(stream)
  }

  completeStream(stream: MuxStreamState): Promise<void> {
    return this.completeHttpStream(stream)
  }

  closeStream(stream: MuxStreamState, code: number, reason: string): Promise<void> {
    return this.closeWebSocketStream(stream, code, reason)
  }

  resetStream(stream: MuxStreamState, code: string, message: string): Promise<void> {
    return this.resetLogicalStream(stream, code, message)
  }

  private async acceptStream(stream: MuxStreamState): Promise<void> {
    this.ensureUsable()
    this.ensureActive(stream)
    if (this.role !== 'daemon') {
      throw new MuxError('only the daemon can accept a stream')
    }
    if (stream.accepted) {
      throw new MuxError('stream is already accepted')
    }
    stream.accepted = true
    const frame = encodeFrame({ type: 'READY', streamId: stream.id })
    return this.enqueueManagement(frame, stream, false)
  }

  private async sendStreamHeaders(stream: MuxStreamState, headers: HeadersPayload): Promise<void> {
    this.ensureUsable()
    this.ensureActive(stream)
    if (this.role !== 'daemon') {
      throw new MuxError('only the daemon can send response headers')
    }
    if (!stream.accepted) {
      throw new MuxError('stream must be accepted before response headers')
    }
    if (stream.headersSent) {
      throw new MuxError('response headers were already sent')
    }
    if (stream.mode === 'WS' && headers.status !== 101) {
      throw new MuxError('WS headers status must be 101')
    }
    const frame = encodeFrame({ type: 'HEADERS', streamId: stream.id, headers })
    stream.headersSent = true
    return this.enqueueManagement(frame, stream, false)
  }

  private async writeStreamData(stream: MuxStreamState, payload: Uint8Array, options: MuxWriteOptions): Promise<void> {
    this.ensureUsable()
    this.ensureActive(stream)
    this.assertWritableDirection(stream)
    if (stream.creditWaiter !== null) {
      throw new MuxError('stream already has a pending credit wait')
    }
    if (!(payload instanceof Uint8Array)) {
      throw new MuxError('DATA payload must be a Uint8Array')
    }
    if (payload.byteLength > MUX_MAX_PAYLOAD_BYTES) {
      throw new MuxError('DATA payload limit exceeded')
    }
    const flags = this.dataFlagsForWrite(stream, options)
    const frame = encodeFrame({ type: 'DATA', streamId: stream.id, payload, flags })
    const cost = frameCost(payload.byteLength)
    if (isAborted(options.signal)) {
      throw abortError()
    }
    while (!this.canSendData(stream, cost)) {
      await this.waitForCredit(stream, options.signal)
      stream.creditWaiter = null
      this.ensureUsable()
      this.ensureActive(stream)
      this.assertWritableDirection(stream)
      if (stream.mode === 'WS' && stream.remoteCloseReceived) {
        throw new MuxError('WS stream is closed by the peer')
      }
      if (isAborted(options.signal)) {
        throw abortError()
      }
    }
    this.outboundConnectionReserved += cost
    stream.sendReserved += cost
    if (flags.kind === 'ws') {
      stream.outboundWsType = flags.fin ? null : flags.text ? 'text' : 'binary'
    }
    return this.enqueueData(stream, frame, cost)
  }

  private async endHttpStream(stream: MuxStreamState): Promise<void> {
    this.ensureUsable()
    this.ensureActive(stream)
    if (stream.mode !== 'HTTP') {
      throw new MuxError('END is only valid for HTTP streams')
    }
    if (stream.localEndQueued) {
      throw new MuxError('HTTP direction is already ended')
    }
    this.assertWritableDirection(stream)
    this.cancelCreditWaiter(stream, new MuxError('stream ended while waiting for credit'))
    stream.localEndQueued = true
    const frame = encodeFrame({ type: 'END', streamId: stream.id })
    return this.enqueueManagement(frame, stream, true, () => {
      if (!stream.closed) {
        stream.localEndSent = true
        this.maybeFinishHttp(stream)
      }
    })
  }

  private async completeHttpStream(stream: MuxStreamState): Promise<void> {
    this.ensureUsable()
    this.ensureActive(stream)
    if (this.role !== 'server') {
      throw new MuxError('only the server can complete a request')
    }
    if (stream.mode !== 'HTTP') {
      throw new MuxError('COMPLETE is only valid for HTTP streams')
    }
    if (!stream.localEndQueued) {
      throw new MuxError('request must be ended before COMPLETE')
    }
    if (!stream.remoteEndConsumed) {
      throw new MuxError('response END must be consumed before COMPLETE')
    }
    if (stream.completeQueued) {
      throw new MuxError('COMPLETE was already sent')
    }
    stream.completeQueued = true
    const frame = encodeFrame({ type: 'COMPLETE', streamId: stream.id })
    return this.enqueueManagement(frame, stream, false, () => {
      if (!stream.closed) {
        stream.completeSent = true
        this.maybeFinishHttp(stream)
      }
    })
  }

  private async closeWebSocketStream(stream: MuxStreamState, code: number, reason: string): Promise<void> {
    this.ensureUsable()
    this.ensureActive(stream)
    if (stream.mode !== 'WS') {
      throw new MuxError('CLOSE is only valid for WS streams')
    }
    if (stream.localCloseQueued) {
      throw new MuxError('WS CLOSE was already sent')
    }
    this.assertWritableDirection(stream)
    const frame = encodeFrame({ type: 'CLOSE', streamId: stream.id, close: { code, reason } })
    this.cancelCreditWaiter(stream, new MuxError('stream closed while waiting for credit'))
    stream.localCloseQueued = true
    return this.enqueueManagement(frame, stream, true, () => {
      if (!stream.closed) {
        stream.localCloseSent = true
        this.maybeFinishWebSocket(stream)
      }
    })
  }

  private async resetLogicalStream(stream: MuxStreamState, code: string, message: string): Promise<void> {
    this.ensureUsable()
    this.ensureActive(stream)
    const frame = encodeFrame({ type: 'RESET', streamId: stream.id, reset: { code, message } })
    this.finishStream(stream, true)
    return this.enqueueManagement(frame, null, false)
  }

  private assertWritableDirection(stream: MuxStreamState): void {
    if (stream.localEndQueued || stream.localCloseQueued) {
      throw new MuxError('stream direction is already closed')
    }
    if (this.role === 'server') {
      if (!stream.ready) {
        throw new MuxError('stream is not ready')
      }
      if (stream.mode === 'WS' && !stream.headersReceived) {
        throw new MuxError('WS handshake has not completed')
      }
      return
    }
    if (!stream.accepted) {
      throw new MuxError('stream is not accepted')
    }
    if (!stream.headersSent) {
      throw new MuxError('response headers must be sent before DATA or END')
    }
  }

  private dataFlagsForWrite(stream: MuxStreamState, options: MuxWriteOptions): DataFlags {
    if (stream.mode === 'HTTP') {
      if (options.text !== undefined || options.fin !== undefined) {
        throw new MuxError('HTTP DATA does not accept WS fragment flags')
      }
      return { kind: 'http' }
    }
    if (stream.remoteCloseReceived) {
      throw new MuxError('WS stream is closed by the peer')
    }
    if (typeof options.text !== 'boolean' || typeof options.fin !== 'boolean') {
      throw new MuxError('WS DATA requires text and fin flags')
    }
    this.assertWsFragmentType(stream, options.text ? 'text' : 'binary')
    return { kind: 'ws', text: options.text, fin: options.fin }
  }

  private assertWsFragmentType(stream: MuxStreamState, type: 'text' | 'binary'): void {
    if (stream.outboundWsType !== null && stream.outboundWsType !== type) {
      throw new MuxError('WS fragment type changed before FIN')
    }
  }

  private canSendData(stream: MuxStreamState, cost: number): boolean {
    return (
      stream.sendReserved + cost <= MUX_STREAM_CREDIT &&
      this.outboundConnectionReserved + cost <= MUX_CONNECTION_CREDIT
    )
  }

  private waitForCredit(stream: MuxStreamState, signal: AbortSignal | undefined): Promise<void> {
    if (stream.creditWaiter !== null) {
      return Promise.reject(new MuxError('stream already has a pending credit wait'))
    }
    return new Promise<void>((resolve, reject) => {
      const waiter: CreditWaiter = {
        resolve,
        reject,
        signal,
      }
      const onAbort = (): void => {
        stream.creditWaiter = null
        signal?.removeEventListener('abort', onAbort)
        reject(abortError())
      }
      waiter.onAbort = onAbort
      stream.creditWaiter = waiter
      signal?.addEventListener('abort', onAbort, { once: true })
    })
  }

  private wakeCreditWaiter(stream: MuxStreamState): void {
    const waiter = stream.creditWaiter
    if (waiter === null) {
      return
    }
    // The waiting write clears the reference after it resumes. Keeping it here prevents a later
    // write from overtaking that write between the grant and its continuation.
    if (waiter.onAbort !== undefined) {
      waiter.signal?.removeEventListener('abort', waiter.onAbort)
    }
    waiter.resolve()
  }

  private wakeAllCreditWaiters(): void {
    for (const stream of this.streams.values()) {
      this.wakeCreditWaiter(stream)
    }
  }

  private cancelCreditWaiter(stream: MuxStreamState, error: Error): void {
    const waiter = stream.creditWaiter
    if (waiter === null) {
      return
    }
    stream.creditWaiter = null
    if (waiter.onAbort !== undefined) {
      waiter.signal?.removeEventListener('abort', waiter.onAbort)
    }
    waiter.reject(error)
  }

  private enqueueData(stream: MuxStreamState, frame: Uint8Array, cost: number): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const wasEmpty = stream.dataQueue.length === 0
      stream.dataQueue.push({ frame, cost, stream, resolve, reject })
      if (wasEmpty) {
        this.dataRoundRobin.push(stream)
      }
      this.pump()
    })
  }

  private enqueueManagement(
    frame: Uint8Array,
    stream: MuxStreamState | null,
    barrier: boolean,
    onSent?: () => void,
    preserveOnClose = false,
  ): Promise<void> {
    if (this.failed || this.closed) {
      return Promise.reject(new MuxError('mux connection is closed'))
    }
    if (
      this.management.length >= MUX_MAX_MANAGEMENT_FRAMES ||
      this.managementBytes + frame.byteLength > MUX_MAX_MANAGEMENT_BYTES
    ) {
      const error = new MuxError('management queue limit exceeded')
      this.fail(error)
      return Promise.reject(error)
    }
    return new Promise<void>((resolve, reject) => {
      this.management.push({ frame, stream, barrier, resolve, reject, onSent, preserveOnClose })
      this.managementBytes += frame.byteLength
      this.pump()
    })
  }

  private pump(): void {
    if (this.sending || this.failed || this.closed) {
      return
    }
    const job = this.nextJob()
    if (job === null) {
      this.notifyDrain()
      return
    }
    this.sending = true
    this.currentJob = job
    let sent: Promise<void> | void
    try {
      sent = this.options.send(job.frame)
    } catch (error) {
      this.sending = false
      this.currentJob = null
      job.reject(asError(error))
      this.fail(new MuxError('physical mux send failed', error))
      return
    }
    Promise.resolve(sent).then(
      () => {
        this.sending = false
        this.currentJob = null
        if (this.failed || this.closed) {
          return
        }
        if ('stream' in job && 'cost' in job) {
          this.afterDataSent(job)
        } else {
          job.onSent?.()
        }
        job.resolve()
        this.pump()
        this.notifyDrain()
      },
      (error: unknown) => {
        this.sending = false
        this.currentJob = null
        job.reject(asError(error))
        this.fail(new MuxError('physical mux send failed', error))
      },
    )
  }

  private nextJob(): ManagementJob | DataJob | null {
    if (this.management.length > 0) {
      const head = this.management[0]!
      if (head.barrier && head.stream !== null && !head.stream.closed && head.stream.dataQueue.length > 0) {
        return this.takeSpecificData(head.stream)
      }
      this.management.shift()
      this.managementBytes -= head.frame.byteLength
      return head
    }
    return this.takeRoundRobinData()
  }

  private takeSpecificData(stream: MuxStreamState): DataJob {
    const index = this.dataRoundRobin.indexOf(stream)
    if (index >= 0) {
      this.dataRoundRobin.splice(index, 1)
    }
    const job = stream.dataQueue.shift()!
    if (stream.dataQueue.length > 0) {
      this.dataRoundRobin.push(stream)
    }
    return job
  }

  private takeRoundRobinData(): DataJob | null {
    const stream = this.dataRoundRobin.shift()
    if (stream === undefined) {
      return null
    }
    const job = stream.dataQueue.shift()!
    if (stream.dataQueue.length > 0) {
      this.dataRoundRobin.push(stream)
    }
    return job
  }

  private afterDataSent(job: DataJob): void {
    this.outboundConnectionSentUnacked += job.cost
    if (!job.stream.closed) {
      job.stream.sentUnacked += job.cost
    }
    this.wakeCreditWaiter(job.stream)
  }

  private notifyDrain(): void {
    if (!this.isIdle() || this.drainWaiters.length === 0) {
      return
    }
    const waiters = this.drainWaiters
    this.drainWaiters = []
    for (const waiter of waiters) {
      waiter.resolve()
    }
  }

  private isIdle(): boolean {
    return !this.sending && this.management.length === 0 && this.dataRoundRobin.length === 0
  }

  private handleFrame(frame: MuxFrame): void {
    switch (frame.type) {
      case 'OPEN':
        this.handleOpen(frame.streamId, frame.request)
        break
      case 'READY':
        this.handleReady(frame.streamId)
        break
      case 'HEADERS':
        this.handleHeaders(frame.streamId, frame.headers)
        break
      case 'DATA':
        this.handleData(frame)
        break
      case 'WINDOW':
        this.handleWindow(frame.streamId, frame.credit)
        break
      case 'END':
        this.handleEnd(frame.streamId)
        break
      case 'COMPLETE':
        this.handleComplete(frame.streamId)
        break
      case 'RESET':
        this.handleReset(frame.streamId, frame.reset)
        break
      case 'CLOSE':
        this.handleClose(frame.streamId, frame.close)
        break
    }
  }

  private handleOpen(streamId: number, request: OpenRequest): void {
    if (this.role !== 'daemon') {
      throw new MuxError('OPEN is only valid from the server')
    }
    if (streamId !== this.highestAllocated + 1) {
      throw new MuxError('OPEN stream id is not strictly consecutive')
    }
    if (this.streams.size >= MUX_MAX_STREAMS) {
      throw new MuxError('concurrent stream limit exceeded')
    }
    if (this.options.onOpen === undefined) {
      throw new MuxError('OPEN handler is not configured')
    }
    const stream = new MuxStreamState(this, streamId, request)
    this.highestAllocated = streamId
    this.streams.set(streamId, stream)
    this.options.onOpen(stream)
  }

  private handleReady(streamId: number): void {
    if (this.role !== 'server') {
      throw new MuxError('READY is only valid for the server')
    }
    const stream = this.streams.get(streamId)
    if (stream === undefined) {
      if (streamId > this.highestAllocated) {
        throw new MuxError('READY for an unallocated stream')
      }
      return
    }
    if (stream.ready) {
      throw new MuxError('duplicate READY')
    }
    stream.ready = true
    this.options.onReady?.(stream)
  }

  private handleHeaders(streamId: number, headers: HeadersPayload): void {
    if (this.role !== 'server') {
      throw new MuxError('HEADERS is only valid from the daemon')
    }
    const stream = this.streams.get(streamId)
    if (stream === undefined) {
      if (streamId > this.highestAllocated) {
        throw new MuxError('HEADERS for an unallocated stream')
      }
      return
    }
    if (!stream.ready) {
      throw new MuxError('HEADERS arrived before READY')
    }
    if (stream.headersReceived || stream.remoteEndReceived) {
      throw new MuxError('duplicate HEADERS')
    }
    if (stream.mode === 'WS' && headers.status !== 101) {
      throw new MuxError('WS headers status must be 101')
    }
    stream.headersReceived = true
    this.options.onHeaders?.(stream, headers)
  }

  private handleData(frame: DataFrame): void {
    const cost = frameCost(frame.payload.byteLength)
    const stream = this.streams.get(frame.streamId)
    if (stream === undefined) {
      if (frame.streamId > this.highestAllocated) {
        throw new MuxError('DATA for an unallocated stream')
      }
      this.receiveClosedStreamData(cost)
      return
    }
    this.assertInboundDirection(stream)
    this.validateInboundDataFlags(stream, frame.flags)
    if (this.inboundConnectionReserved + cost > MUX_CONNECTION_CREDIT) {
      throw new MuxError('connection receive credit exceeded')
    }
    if (stream.receiveReserved + cost > MUX_STREAM_CREDIT) {
      throw new MuxError('stream receive credit exceeded')
    }
    this.inboundConnectionReserved += cost
    stream.receiveReserved += cost
    stream.delivery.push({ frame, cost })
    this.inboundQueuedCount++
    this.pumpDelivery(stream)
  }

  private assertInboundDirection(stream: MuxStreamState): void {
    if (stream.remoteCloseReceived) {
      throw new MuxError('DATA arrived after WS CLOSE')
    }
    if (stream.remoteEndReceived) {
      throw new MuxError('DATA arrived after END')
    }
    if (this.role === 'server') {
      if (!stream.ready) {
        throw new MuxError('response DATA arrived before READY')
      }
      if (!stream.headersReceived) {
        throw new MuxError('response DATA arrived before HEADERS')
      }
      return
    }
    if (!stream.accepted) {
      throw new MuxError('request DATA arrived before READY')
    }
  }

  private validateInboundDataFlags(stream: MuxStreamState, flags: DataFlags): void {
    if (stream.mode === 'HTTP') {
      if (flags.kind !== 'http') {
        throw new MuxError('HTTP DATA must have zero WS flags')
      }
      return
    }
    if (flags.kind !== 'ws') {
      throw new MuxError('WS DATA requires TEXT or BINARY flags')
    }
    const type = flags.text ? 'text' : 'binary'
    if (stream.inboundWsType !== null && stream.inboundWsType !== type) {
      throw new MuxError('WS fragment type changed before FIN')
    }
    stream.inboundWsType = flags.fin ? null : type
  }

  private receiveClosedStreamData(cost: number): void {
    if (this.inboundConnectionReserved + cost > MUX_CONNECTION_CREDIT) {
      throw new MuxError('connection receive credit exceeded')
    }
    this.inboundConnectionReserved += cost
    this.inboundConnectionAckPending += cost
    this.flushConnectionAck(false)
  }

  private handleWindow(streamId: number, credit: number): void {
    if (streamId === 0) {
      if (credit > this.outboundConnectionSentUnacked) {
        throw new MuxError('WINDOW exceeds connection credit outstanding')
      }
      this.outboundConnectionSentUnacked -= credit
      this.outboundConnectionReserved -= credit
      this.wakeAllCreditWaiters()
      return
    }
    const stream = this.streams.get(streamId)
    if (stream === undefined) {
      if (streamId > this.highestAllocated) {
        throw new MuxError('WINDOW for an unallocated stream')
      }
      return
    }
    if (credit > stream.sentUnacked) {
      throw new MuxError('WINDOW exceeds stream credit outstanding')
    }
    stream.sentUnacked -= credit
    stream.sendReserved -= credit
    this.wakeCreditWaiter(stream)
  }

  private handleEnd(streamId: number): void {
    const stream = this.streams.get(streamId)
    if (stream === undefined) {
      if (streamId > this.highestAllocated) {
        throw new MuxError('END for an unallocated stream')
      }
      return
    }
    if (stream.mode !== 'HTTP') {
      throw new MuxError('END is only valid for HTTP streams')
    }
    if (stream.remoteEndReceived) {
      throw new MuxError('duplicate END')
    }
    if (this.role === 'server') {
      if (!stream.ready || !stream.headersReceived) {
        throw new MuxError('response END arrived before HEADERS')
      }
    } else if (!stream.accepted) {
      throw new MuxError('request END arrived before READY')
    }
    stream.remoteEndReceived = true
    stream.terminal = { kind: 'end' }
    this.pumpDelivery(stream)
  }

  private handleComplete(streamId: number): void {
    const stream = this.streams.get(streamId)
    if (stream === undefined) {
      if (streamId > this.highestAllocated) {
        throw new MuxError('COMPLETE for an unallocated stream')
      }
      return
    }
    if (this.role !== 'daemon') {
      throw new MuxError('COMPLETE is only valid from the server')
    }
    if (stream.mode !== 'HTTP' || stream.completeReceived) {
      throw new MuxError('invalid COMPLETE')
    }
    if (!stream.localEndSent) {
      throw new MuxError('COMPLETE arrived before response END')
    }
    stream.completeReceived = true
    this.options.onComplete?.(stream)
    this.finishStream(stream, false)
  }

  private handleReset(streamId: number, reset: ResetPayload): void {
    const stream = this.streams.get(streamId)
    if (stream === undefined) {
      if (streamId > this.highestAllocated) {
        throw new MuxError('RESET for an unallocated stream')
      }
      return
    }
    this.finishStream(stream, true)
    this.options.onReset?.(streamId, reset)
  }

  private handleClose(streamId: number, close: ClosePayload): void {
    const stream = this.streams.get(streamId)
    if (stream === undefined) {
      if (streamId > this.highestAllocated) {
        throw new MuxError('CLOSE for an unallocated stream')
      }
      return
    }
    if (stream.mode !== 'WS') {
      throw new MuxError('CLOSE is only valid for WS streams')
    }
    if (stream.remoteCloseReceived) {
      throw new MuxError('duplicate WS CLOSE')
    }
    stream.remoteCloseReceived = true
    this.cancelCreditWaiter(stream, new MuxError('WS CLOSE arrived while waiting for credit'))
    this.dropOutboundData(stream, false)
    stream.terminal = { kind: 'close', close }
    this.pumpDelivery(stream)
  }

  private pumpDelivery(stream: MuxStreamState): void {
    if (this.failed || this.closed || stream.closed || stream.deliveryRunning) {
      return
    }
    const item = stream.delivery.shift()
    if (item === undefined) {
      this.runTerminal(stream)
      return
    }
    this.inboundQueuedCount--
    stream.deliveryRunning = true
    let consumed: Promise<void> | void
    try {
      consumed = this.options.onData?.(stream, item.frame)
    } catch {
      stream.deliveryRunning = false
      this.downstreamFailure(stream)
      return
    }
    Promise.resolve(consumed).then(
      () => {
        stream.deliveryRunning = false
        this.afterInboundConsumed(stream, item)
      },
      () => {
        stream.deliveryRunning = false
        this.downstreamFailure(stream)
      },
    )
  }

  private afterInboundConsumed(stream: MuxStreamState, item: InboundData): void {
    if (this.failed || this.closed || stream.closed) {
      return
    }
    stream.receiveAckPending += item.cost
    this.inboundConnectionAckPending += item.cost
    if (stream.receiveAckPending >= MUX_ACK_THRESHOLD || stream.delivery.length === 0) {
      this.flushStreamAck(stream)
    }
    this.flushConnectionAck(false)
    if (stream.delivery.length > 0) {
      this.pumpDelivery(stream)
      return
    }
    this.flushStreamAck(stream)
    this.flushConnectionAck(false)
    this.runTerminal(stream)
  }

  private runTerminal(stream: MuxStreamState): void {
    if (
      this.failed ||
      this.closed ||
      stream.closed ||
      stream.deliveryRunning ||
      stream.delivery.length > 0 ||
      stream.terminal === null
    ) {
      return
    }
    const terminal = stream.terminal
    stream.terminal = null
    if (terminal.kind === 'end') {
      stream.remoteEndConsumed = true
      let consumed: Promise<void> | void
      try {
        consumed = this.options.onEnd?.(stream)
      } catch {
        this.downstreamFailure(stream)
        return
      }
      Promise.resolve(consumed).then(
        () => {},
        () => {
          this.downstreamFailure(stream)
        },
      )
      return
    }
    let closed: Promise<void> | void
    try {
      closed = this.options.onClose?.(stream, terminal.close)
    } catch {
      this.downstreamFailure(stream)
      return
    }
    Promise.resolve(closed).then(
      () => {
        if (this.failed || this.closed || stream.closed) {
          return
        }
        stream.closeDelivered = true
        this.maybeFinishWebSocket(stream)
      },
      () => {
        this.downstreamFailure(stream)
      },
    )
  }

  private downstreamFailure(stream: MuxStreamState): void {
    if (this.failed || this.closed || stream.closed) {
      return
    }
    const frame = encodeFrame({
      type: 'RESET',
      streamId: stream.id,
      reset: { code: 'DOWNSTREAM_ERROR', message: 'downstream consumption failed' },
    })
    this.finishStream(stream, true)
    rejectLater(this.enqueueManagement(frame, null, false))
  }

  private flushStreamAck(stream: MuxStreamState): void {
    if (stream.closed || stream.receiveAckPending <= 0) {
      return
    }
    const amount = stream.receiveAckPending
    stream.receiveAckPending = 0
    stream.receiveAckInFlight += amount
    const frame = encodeFrame({ type: 'WINDOW', streamId: stream.id, credit: amount })
    rejectLater(
      this.enqueueManagement(frame, stream, false, () => {
        if (!stream.closed) {
          stream.receiveReserved -= amount
          stream.receiveAckInFlight -= amount
        }
      }),
    )
  }

  private flushConnectionAck(force: boolean): void {
    if (this.inboundConnectionAckPending <= 0) {
      return
    }
    if (!force && this.inboundConnectionAckPending < MUX_ACK_THRESHOLD && this.hasPendingInbound()) {
      return
    }
    const amount = this.inboundConnectionAckPending
    this.inboundConnectionAckPending = 0
    const frame = encodeFrame({ type: 'WINDOW', streamId: 0, credit: amount })
    rejectLater(
      this.enqueueManagement(frame, null, false, () => {
        this.inboundConnectionReserved -= amount
      }),
    )
  }

  private hasPendingInbound(): boolean {
    if (this.inboundQueuedCount > 0) {
      return true
    }
    for (const stream of this.streams.values()) {
      if (stream.deliveryRunning || stream.delivery.length > 0) {
        return true
      }
    }
    return false
  }

  private maybeFinishHttp(stream: MuxStreamState): void {
    if (this.role === 'server' && stream.completeSent && stream.remoteEndConsumed && stream.localEndSent) {
      this.finishStream(stream, false)
    }
  }

  private maybeFinishWebSocket(stream: MuxStreamState): void {
    if (stream.localCloseSent && stream.remoteCloseReceived && stream.closeDelivered) {
      this.finishStream(stream, false)
    }
  }

  private finishStream(stream: MuxStreamState, resetInbound: boolean): void {
    if (stream.closed) {
      return
    }
    stream.closed = true
    if (this.streams.get(stream.id) === stream) {
      this.streams.delete(stream.id)
    }
    this.cancelCreditWaiter(stream, new MuxError('stream is closed'))
    this.dropOutboundData(stream, true)
    this.dropManagementForStream(stream)
    this.abandonInbound(stream, resetInbound)
    stream.terminal = null
    this.notifyDrain()
  }

  private dropOutboundData(stream: MuxStreamState, closing: boolean): void {
    const jobs = stream.dataQueue
    stream.dataQueue = []
    let dropped = 0
    for (const job of jobs) {
      dropped += job.cost
      job.reject(new MuxError('stream is closed'))
    }
    this.outboundConnectionReserved -= dropped
    if (closing) {
      stream.sendReserved = 0
      stream.sentUnacked = 0
    } else {
      // Keep in-flight reservations for the remaining WS close handshake; sent data may still be acknowledged.
      stream.sendReserved -= dropped
    }
    const index = this.dataRoundRobin.indexOf(stream)
    if (index >= 0) {
      this.dataRoundRobin.splice(index, 1)
    }
  }

  private dropManagementForStream(stream: MuxStreamState): void {
    if (this.management.length === 0) {
      return
    }
    const kept: ManagementJob[] = []
    for (const job of this.management) {
      if (job.stream === stream && job.preserveOnClose !== true) {
        this.managementBytes -= job.frame.byteLength
        job.reject(new MuxError('stream is closed'))
      } else {
        kept.push(job)
      }
    }
    this.management = kept
  }

  private abandonInbound(stream: MuxStreamState, forceConnectionAck: boolean): void {
    const consumedNotAcked = stream.receiveAckPending + stream.receiveAckInFlight
    const unconsumed = Math.max(0, stream.receiveReserved - consumedNotAcked)
    if (unconsumed > 0) {
      this.inboundConnectionAckPending += unconsumed
    }
    stream.receiveReserved = 0
    stream.receiveAckPending = 0
    stream.receiveAckInFlight = 0
    if (stream.delivery.length > 0) {
      this.inboundQueuedCount -= stream.delivery.length
    }
    stream.delivery = []
    this.flushConnectionAck(forceConnectionAck)
  }

  private ensureActive(stream: MuxStreamState): void {
    if (stream.closed || this.streams.get(stream.id) !== stream) {
      throw new MuxError('stream is closed')
    }
  }

  private ensureUsable(): void {
    if (this.failed) {
      throw this.fatalError ?? new MuxError('mux connection failed')
    }
    if (this.closed) {
      throw new MuxError('mux connection is closed')
    }
  }

  private fail(error: MuxError): void {
    if (this.failed || this.closed) {
      return
    }
    this.failed = true
    this.fatalError = error
    this.abortAll(error)
    try {
      this.options.onFatal?.(error)
    } catch {
      // The transport is already terminal; the notification callback cannot reopen it.
    }
  }

  private abortAll(error: Error): void {
    const management = this.management
    this.management = []
    this.managementBytes = 0
    for (const job of management) {
      job.reject(error)
    }
    for (const stream of this.streams.values()) {
      stream.closed = true
      this.cancelCreditWaiter(stream, error)
      for (const job of stream.dataQueue) {
        job.reject(error)
      }
      stream.dataQueue = []
      stream.delivery = []
    }
    this.streams.clear()
    this.dataRoundRobin = []
    this.inboundQueuedCount = 0
    this.currentJob?.reject(error)
    this.currentJob = null
    this.drainWaiters.splice(0).forEach((waiter) => waiter.reject(error))
  }
}

/** Creates a mux engine. There is exactly one writer for the physical connection. */
export function createMuxConnection(options: MuxConnectionOptions): MuxConnection {
  return new DefaultMuxConnection(options.role, options)
}
