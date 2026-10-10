/**
 * Node E2E terminal control probe.
 *
 * It drives the real browser terminal protocol over the single shared app-events carrier: every
 * command is a `{version:2,type:"shell.command",command:<TerminalCommand>}` logical frame carried by
 * `app.events.v2` NotificationCarrier fragments through `FramedEventSocket` (which reuses the exact
 * browser/shared framing implementation). No chunking, reassembly or raw-JSON bypass is
 * re-implemented here, and no writer token, input bytes, terminal screen or history ever leaves the
 * process through artifacts or logs.
 *
 * The probe owns exactly one viewer scope (one `viewerId` on one connection) and therefore exactly
 * one observer stream, matching the daemon observer key `(route,viewerId)`. It applies the
 * structured RESET/PATCH view wire into a bounded numeric mirror and derives `VIEW_APPLIED` from the
 * applied `version` — never from a transport ack.
 */

import { createHash, randomUUID } from 'node:crypto'
import { assert, sleep } from './http.mjs'
import { FramedEventSocket, eventUrl } from './framed-event-socket.mjs'

/** Bounded retained logical frames; terminal output must never accumulate without limit. */
const MAX_RETAINED_FRAMES = 64

/** SHA-256 digest of an INPUT operation: tag 0x01 || big-endian int64 inputModeRevision || bytes. */
export function inputDigest(bytes, inputModeRevision) {
  const revision = Buffer.alloc(8)
  revision.writeBigInt64BE(BigInt(inputModeRevision))
  return createHash('sha256').update(Buffer.from([0x01])).update(revision).update(bytes).digest('hex')
}

/** SHA-256 digest of a RESIZE operation: tag 0x02 || big-endian int32 cols || big-endian int32 rows. */
export function resizeDigest(cols, rows) {
  const dims = Buffer.alloc(8)
  dims.writeInt32BE(cols, 0)
  dims.writeInt32BE(rows, 4)
  return createHash('sha256').update(Buffer.from([0x02])).update(dims).digest('hex')
}

function record(list, item) {
  list.push(item)
  if (list.length > MAX_RETAINED_FRAMES) list.splice(0, list.length - MAX_RETAINED_FRAMES)
}

export class TerminalProbe {
  #hook

  /**
   * @param {string} baseUrl node HTTP(S) base URL the probe connects to
   * @param {{environmentId: string, viewerId?: string, Socket?: typeof WebSocket, heartbeatMs?: number, autoApplied?: boolean}} options
   */
  constructor(baseUrl, { environmentId, viewerId = randomUUID(), Socket = WebSocket, heartbeatMs = 5000, autoApplied = true } = {}) {
    assert(environmentId, 'terminal probe requires an environmentId')
    this.environmentId = environmentId
    this.viewerId = viewerId
    this.heartbeatMs = heartbeatMs
    this.autoApplied = autoApplied

    this.framed = new FramedEventSocket(eventUrl(baseUrl), { Socket })
    this.socket = this.framed.socket
    this.hooks = this.framed.hooks

    // Terminal scope / last applied state (in-memory only).
    this.identity = null
    this.streamId = null
    this.terminalStatus = null
    this.exitCode = null
    this.writer = null
    this.grant = null
    this.cols = 0
    this.rows = 0
    this.history = 0
    this.alternate = false
    this.inputModeRevision = 0
    this.lastAppliedVersion = 0
    this.ackedVersion = 0
    /** Rstrip-ed screen rows (no history); scoped to the current stream and compacted in place. */
    this.screen = []
    this.failure = null

    // Bounded observation records (never raw frames, payloads, tokens or screen content).
    this.counters = {
      attached: 0,
      viewUpdates: 0,
      resets: 0,
      patches: 0,
      writerChanged: 0,
      opAcks: 0,
      droppedOpAcks: 0,
      exited: 0,
      errors: 0,
      keepalives: 0,
    }
    this.attachResults = []
    this.controlResults = []
    this.opAcks = []
    this.exitEvents = []
    this.errorEvents = []

    this.dropOpAcks = 0
    this.heartbeatTimer = null

    this.#hook = (frame) => {
      try {
        if (frame && frame.type === 'shell.event') this.#onShellEvent(frame.event)
      } catch (error) {
        this.failure ||= error
      } finally {
        this.#trimFrames()
      }
    }
    this.framed.hooks.add(this.#hook)
    this.#startHeartbeat()
  }

  // ------------------------------------------------------------------ commands

  #sendCommand(type, payload) {
    const requestId = randomUUID()
    const command = {
      version: 1,
      requestId,
      environmentId: this.environmentId,
      viewerId: this.viewerId,
      type,
      payload,
    }
    assert(this.framed.send({ type: 'shell.command', command }), 'failed to queue shell.command')
    return requestId
  }

  #requireIdentity() {
    assert(this.identity, 'terminal identity is not attached yet')
    return this.identity
  }

  #requireStream() {
    assert(this.streamId, 'terminal stream is not attached yet')
    return this.streamId
  }

  open({ expectedExited = null } = {}) {
    return this.#sendCommand('OPEN', { expectedExited })
  }

  attach(identity = this.#requireIdentity()) {
    return this.#sendCommand('ATTACH', { identity })
  }

  /** DETACH this viewer's stream; the terminal keeps running and this probe stops scoping commands. */
  detach() {
    const requestId = this.#sendCommand('DETACH', { identity: this.#requireIdentity(), streamId: this.#requireStream() })
    this.streamId = null
    return requestId
  }

  claim({ recovery = null } = {}) {
    return this.#sendCommand('CLAIM', { identity: this.#requireIdentity(), streamId: this.#requireStream(), recovery })
  }

  takeover(expectedWriterEpoch) {
    return this.#sendCommand('TAKEOVER', {
      identity: this.#requireIdentity(),
      streamId: this.#requireStream(),
      expectedWriterEpoch,
    })
  }

  release(grant = this.grant) {
    assert(grant, 'release requires a grant')
    return this.#sendCommand('RELEASE', { identity: this.#requireIdentity(), streamId: this.#requireStream(), grant })
  }

  input(bytes, { seq, inputModeRevision = this.inputModeRevision, grant = this.grant } = {}) {
    assert(grant, 'INPUT requires a writer grant')
    return this.#sendCommand('INPUT', {
      identity: this.#requireIdentity(),
      streamId: this.#requireStream(),
      grant,
      seq,
      inputModeRevision,
      bytes: Buffer.from(bytes).toString('base64'),
    })
  }

  resize(cols, rows, { seq }) {
    assert(this.grant, 'RESIZE requires an owned writer grant')
    return this.#sendCommand('RESIZE', {
      identity: this.#requireIdentity(),
      streamId: this.#requireStream(),
      grant: this.grant,
      seq,
      cols,
      rows,
    })
  }

  viewApplied(version) {
    return this.#sendCommand('VIEW_APPLIED', {
      identity: this.#requireIdentity(),
      streamId: this.#requireStream(),
      version,
    })
  }

  keepalive(grant = this.grant) {
    if (!this.identity || !this.streamId) return null
    return this.#sendCommand('KEEPALIVE', { identity: this.identity, streamId: this.streamId, grant })
  }

  sendClose(expectedWriterEpoch = this.grant?.epoch ?? this.writer?.writerEpoch ?? null) {
    return this.#sendCommand('CLOSE', { identity: this.#requireIdentity(), expectedWriterEpoch })
  }

  // ------------------------------------------------------------------ typed operation helpers

  /** Sends one INPUT with the current input mode revision and returns the exact expected digest. */
  sendInput(bytes, seq, { grant = this.grant } = {}) {
    const inputModeRevision = this.inputModeRevision
    const buffer = Buffer.from(bytes)
    const requestId = this.input(buffer, { seq, inputModeRevision, grant })
    return { requestId, seq, digest: inputDigest(buffer, inputModeRevision) }
  }

  /** Sends one RESIZE and returns the exact expected digest. */
  sendResize(cols, rows, seq) {
    const requestId = this.resize(cols, rows, { seq })
    return { requestId, seq, digest: resizeDigest(cols, rows), cols, rows }
  }

  waitOperation(operation, timeoutMs = 20_000) {
    assert(this.grant, 'operation wait requires an owned writer grant')
    return this.waitOpAck({ epoch: this.grant.epoch, seq: operation.seq, digest: operation.digest }, timeoutMs)
  }

  /** Sends the VIEW_APPLIED acknowledgement for the last applied version (idempotent per version). */
  ack(version = this.lastAppliedVersion) {
    assert(version > 0, 'cannot ack before a structured view update was applied')
    const requestId = this.viewApplied(version)
    if (version > this.ackedVersion) this.ackedVersion = version
    return requestId
  }

  setAutoApplied(value) {
    this.autoApplied = !!value
  }

  /** Drops exactly the next wire OP_ACK (test-only hook); the real write is still observed via screen. */
  dropNextOpAck() {
    this.dropOpAcks += 1
  }

  // ------------------------------------------------------------------ incoming events

  #onShellEvent(event) {
    if (!event || typeof event !== 'object') {
      this.failure ||= new Error('invalid shell.event frame')
      return
    }
    // Identity/stream fences: only this viewer's events for the targeted environment are consumable.
    if (event.viewerId !== this.viewerId || event.environmentId !== this.environmentId) return
    switch (event.type) {
      case 'ATTACHED':
        this.#onAttached(event)
        break
      case 'VIEW_UPDATE':
        this.#onViewUpdate(event)
        break
      case 'WRITER_CHANGED':
        this.#onWriterChanged(event)
        break
      case 'OP_ACK':
        this.#onOpAck(event)
        break
      case 'EXITED':
        this.#onExited(event)
        break
      case 'ERROR':
        this.#onError(event)
        break
      default:
        this.failure ||= new Error('unknown shell.event type')
    }
  }

  #onAttached(event) {
    const payload = event.payload
    assert(payload && typeof payload === 'object', 'ATTACHED must carry a payload')
    assert(event.identity && typeof event.identity === 'object', 'ATTACHED must carry an identity')
    this.identity = {
      daemonInstanceId: event.identity.daemonInstanceId,
      terminalId: event.identity.terminalId,
    }
    this.streamId = payload.streamId
    this.terminalStatus = payload.status
    this.exitCode = payload.exitCode
    this.writer = payload.writer
    this.grant = null
    this.inputModeRevision = payload.inputModeRevision
    // A new ATTACHED always starts a fresh observer stream: drop the old baseline mirror.
    this.cols = 0
    this.rows = 0
    this.history = 0
    this.alternate = false
    this.screen = []
    this.lastAppliedVersion = 0
    this.ackedVersion = 0
    this.counters.attached += 1
    record(this.attachResults, {
      requestId: event.requestId,
      status: payload.status,
      exitCode: payload.exitCode,
      terminalId: this.identity.terminalId,
      daemonInstanceId: this.identity.daemonInstanceId,
      streamId: payload.streamId,
      inputModeRevision: payload.inputModeRevision,
    })
  }

  #onViewUpdate(event) {
    const update = event.payload?.update
    this.#applyUpdate(update)
    this.counters.viewUpdates += 1
    if (this.autoApplied && this.lastAppliedVersion > this.ackedVersion) {
      // The ack must target the exact in-flight structured version, never a transport ack.
      this.viewApplied(this.lastAppliedVersion)
      this.ackedVersion = this.lastAppliedVersion
    }
  }

  #onWriterChanged(event) {
    const payload = event.payload
    assert(payload && typeof payload === 'object', 'WRITER_CHANGED must carry a payload')
    this.writer = payload.writer
    if (this.grant && (!payload.writer || payload.writer.writerEpoch !== this.grant.epoch)) {
      // Control rotated or expired: this probe no longer owns the writer.
      this.grant = null
    }
    if (event.requestId != null && payload.result != null) {
      const result = payload.result
      record(this.controlResults, {
        requestId: event.requestId,
        status: result.status,
        reason: result.reason ?? null,
        recovered: result.recovered ?? null,
        epoch: result.grant ? result.grant.epoch : null,
        granted: Boolean(result.grant),
      })
      if (result.status === 'GRANTED' && result.grant) {
        this.grant = { epoch: result.grant.epoch, token: result.grant.token }
      } else if (result.status === 'RELEASED') {
        this.grant = null
      }
    }
    this.counters.writerChanged += 1
  }

  #onOpAck(event) {
    if (this.dropOpAcks > 0) {
      this.dropOpAcks -= 1
      this.counters.droppedOpAcks += 1
      return
    }
    const payload = event.payload
    assert(payload && typeof payload === 'object', 'OP_ACK must carry a payload')
    const result = payload.result
    assert(result && typeof result === 'object', 'OP_ACK must carry a result')
    record(this.opAcks, {
      writerEpoch: payload.writerEpoch,
      kind: result.kind,
      seq: result.seq,
      digest: result.digest ?? null,
      outcome: result.outcome ?? null,
      reason: result.reason ?? null,
      code: payload.code ?? null,
    })
    this.counters.opAcks += 1
  }

  #onExited(event) {
    const payload = event.payload
    assert(payload && typeof payload === 'object', 'EXITED must carry a payload')
    this.terminalStatus = payload.status
    this.exitCode = payload.exitCode
    record(this.exitEvents, {
      status: payload.status,
      exitCode: payload.exitCode,
      terminalId: event.identity ? event.identity.terminalId : null,
    })
    this.counters.exited += 1
  }

  #onError(event) {
    const payload = event.payload
    assert(payload && typeof payload === 'object', 'ERROR must carry a payload')
    record(this.errorEvents, {
      requestId: event.requestId,
      code: payload.code,
      disposition: payload.disposition,
      terminalId: event.identity ? event.identity.terminalId : null,
    })
    this.counters.errors += 1
  }

  // ------------------------------------------------------------------ structured view mirror

  /** Extracts the visible row text of one wire line (`[id, wrapped, slots]`); no VT interpretation. */
  #lineText(line, cols) {
    assert(Array.isArray(line) && line.length === 3, 'view line must be [id, wrapped, slots]')
    const slots = line[2]
    assert(Array.isArray(slots) && slots.length === cols, 'view line must carry exactly cols slots')
    let text = ''
    for (const slot of slots) {
      assert(Array.isArray(slot) && slot.length === 3, 'view slot must be [kind, code, styleIndex]')
      const kind = slot[0]
      if (kind === 2) continue // DWC continuation cell carries no glyph of its own.
      if (kind === 1) {
        text += ' '
        continue
      }
      assert(kind === 0, 'unknown view slot kind')
      const code = slot[1]
      text += code === 0 ? ' ' : String.fromCharCode(code)
    }
    return text.replace(/ +$/, '')
  }

  #applyUpdate(update) {
    assert(this.identity && this.streamId, 'VIEW_UPDATE received before ATTACHED')
    assert(update && typeof update === 'object', 'VIEW_UPDATE must carry an update object')
    assert(update.terminalId === this.identity.terminalId, 'VIEW_UPDATE terminalId does not match the attached identity')
    assert(update.streamId === this.streamId, 'VIEW_UPDATE streamId does not match the attached stream')
    const cols = update.cols
    const rows = update.rows
    assert(Number.isInteger(cols) && Number.isInteger(rows), 'VIEW_UPDATE must declare integer cols/rows')
    assert(Array.isArray(update.screenRows), 'VIEW_UPDATE must declare screenRows')
    if (update.type === 'RESET') {
      assert(update.baseVersion === null, 'RESET must not declare a baseVersion')
      // RESET replaces the whole active screen and history: no trim and exactly `history` appended lines.
      assert(update.historyTrim === 0, 'RESET must not trim history')
      assert(Array.isArray(update.historyAppend), 'RESET must declare historyAppend')
      assert(update.historyAppend.length === update.history, 'RESET must append exactly history lines')
      const next = new Map()
      for (const change of update.screenRows) {
        assert(change && Number.isInteger(change.row) && change.row >= 0 && change.row < rows, 'RESET row out of range')
        assert(!next.has(change.row), 'RESET replaced the same row twice')
        next.set(change.row, this.#lineText(change.line, cols))
      }
      assert(next.size === rows, 'RESET must replace every screen row exactly once')
      this.screen = Array.from({ length: rows }, (_, row) => next.get(row) ?? '')
      this.cols = cols
      this.rows = rows
      this.history = update.history
      this.alternate = update.alternate
      this.inputModeRevision = update.inputModeRevision
      this.lastAppliedVersion = update.version
      this.counters.resets += 1
      return
    }
    assert(update.type === 'PATCH', 'unknown VIEW_UPDATE type')
    assert(this.lastAppliedVersion > 0, 'PATCH must follow an applied RESET baseline')
    assert(update.baseVersion === this.lastAppliedVersion, 'PATCH baseVersion does not match the applied version')
    assert(update.version > update.baseVersion, 'PATCH version must advance the baseVersion')
    assert(cols === this.cols && rows === this.rows && update.alternate === this.alternate, 'PATCH must not change geometry or alternate screen')
    assert(update.inputModeRevision >= this.inputModeRevision, 'PATCH must not regress inputModeRevision')
    assert(Array.isArray(update.historyAppend), 'PATCH must declare historyAppend')
    assert(this.history - update.historyTrim + update.historyAppend.length === update.history, 'PATCH history identity mismatch')
    const nextScreen = this.screen.slice()
    for (const change of update.screenRows) {
      assert(change && Number.isInteger(change.row) && change.row >= 0 && change.row < rows, 'PATCH row out of range')
      nextScreen[change.row] = this.#lineText(change.line, cols)
    }
    this.screen = nextScreen
    this.history = update.history
    this.inputModeRevision = update.inputModeRevision
    this.lastAppliedVersion = update.version
    this.counters.patches += 1
  }

  // ------------------------------------------------------------------ bounded retention & heartbeat

  #trimFrames() {
    const frames = this.framed.frames
    if (frames.length > MAX_RETAINED_FRAMES) frames.splice(0, frames.length - MAX_RETAINED_FRAMES)
  }

  #startHeartbeat() {
    if (!this.heartbeatMs) return
    this.heartbeatTimer = setInterval(() => {
      if (this.closed || !this.identity || !this.streamId) return
      try {
        this.keepalive(this.grant)
        this.counters.keepalives += 1
      } catch {
        // A refused heartbeat is surfaced by the next scoped command, never swallowed into state.
      }
    }, this.heartbeatMs)
    this.heartbeatTimer.unref?.()
  }

  // ------------------------------------------------------------------ waiting / querying

  get closed() {
    return this.framed.closed
  }

  check() {
    if (this.failure) throw this.failure
  }

  async waitUntil(predicate, timeoutMs = 10_000, { allowClosed = false } = {}) {
    const deadline = Date.now() + timeoutMs
    for (;;) {
      this.check()
      if (predicate()) return
      if (!allowClosed && this.closed) throw new Error('terminal probe socket closed unexpectedly')
      if (Date.now() >= deadline) throw new Error(`terminal probe wait timed out after ${timeoutMs}ms`)
      await sleep(20)
    }
  }

  async waitAttached(requestId, timeoutMs = 30_000) {
    await this.waitUntil(() => this.attachResults.some((item) => item.requestId === requestId), timeoutMs)
    return this.attachResults.find((item) => item.requestId === requestId)
  }

  async waitControlResult(requestId, timeoutMs = 20_000) {
    await this.waitUntil(() => this.controlResults.some((item) => item.requestId === requestId), timeoutMs)
    return this.controlResults.find((item) => item.requestId === requestId)
  }

  async waitErrorEvent(requestId, timeoutMs = 20_000) {
    await this.waitUntil(() => this.errorEvents.some((item) => item.requestId === requestId), timeoutMs)
    return this.errorEvents.find((item) => item.requestId === requestId)
  }

  async waitOpAck({ epoch, seq, digest }, timeoutMs = 20_000) {
    const matches = (item) => item.writerEpoch === epoch && item.seq === seq && item.digest === digest
    await this.waitUntil(() => this.opAcks.some(matches), timeoutMs)
    return this.opAcks.find(matches)
  }

  async waitScreenRow(text, timeoutMs = 20_000) {
    await this.waitUntil(() => this.screen.includes(text), timeoutMs)
  }

  async waitExit(timeoutMs = 15_000) {
    await this.waitUntil(() => this.exitEvents.length > 0, timeoutMs)
    return this.exitEvents[this.exitEvents.length - 1]
  }

  /** Bounded negative/duplicate observation window; not a claim of infinite silence. */
  async settle(ms = 400) {
    const deadline = Date.now() + ms
    while (Date.now() < deadline) {
      this.check()
      if (this.closed) return
      await sleep(Math.min(20, deadline - Date.now()))
    }
  }

  screenRowCount(text) {
    return this.screen.filter((row) => row === text).length
  }

  /**
   * Sends a CLAIM and returns the granted control result. A `VIEW_NOT_APPLIED` error is retried
   * because `VIEW_APPLIED` has no wire ack; any other rejection fails fast.
   */
  async claimGranted({ recovery = null, timeoutMs = 20_000 } = {}) {
    const deadline = Date.now() + timeoutMs
    for (;;) {
      const requestId = this.claim({ recovery })
      const remaining = Math.max(500, deadline - Date.now())
      await this.waitUntil(
        () => this.controlResults.some((item) => item.requestId === requestId)
          || this.errorEvents.some((item) => item.requestId === requestId),
        remaining,
      )
      const error = this.errorEvents.find((item) => item.requestId === requestId)
      if (error) {
        if (error.code === 'VIEW_NOT_APPLIED' && Date.now() < deadline) {
          await sleep(100)
          continue
        }
        throw new Error(`CLAIM failed with ${error.code}`)
      }
      const result = this.controlResults.find((item) => item.requestId === requestId)
      if (result.status !== 'GRANTED') {
        throw new Error(`CLAIM was not granted: ${result.status}${result.reason ? `/${result.reason}` : ''}`)
      }
      return result
    }
  }

  // ------------------------------------------------------------------ teardown

  /** Best-effort explicit CLOSE of the live terminal, taking over the writer if we lost it. */
  async closeTerminal(timeoutMs = 8_000) {
    try {
      if (!this.identity || this.closed) return
      if (this.terminalStatus && this.terminalStatus !== 'RUNNING') return
      if (this.lastAppliedVersion <= 0) return
      const currentEpoch = this.writer ? this.writer.writerEpoch : null
      if (this.grant && this.grant.epoch === currentEpoch) {
        this.sendClose(currentEpoch)
      } else if (currentEpoch === null) {
        this.sendClose(null)
      } else {
        const takeoverId = this.takeover(currentEpoch)
        await this.waitUntil(
          () => this.controlResults.some((item) => item.requestId === takeoverId),
          timeoutMs,
          { allowClosed: true },
        ).catch(() => {})
        if (!this.grant) return
        this.sendClose(this.grant.epoch)
      }
      await this.waitUntil(() => this.exitEvents.length > 0, timeoutMs, { allowClosed: true }).catch(() => {})
    } catch {
      // Cleanup is best-effort; the primary assertion failure stays the reported one.
    }
  }

  /** Closes the WebSocket and stops the heartbeat timer. */
  async close() {
    try {
      if (this.heartbeatTimer) {
        clearInterval(this.heartbeatTimer)
        this.heartbeatTimer = null
      }
      if (!this.closed) this.socket.close(1000, 'E2E complete')
      const deadline = Date.now() + 5_000
      while (!this.closed && Date.now() < deadline) await sleep(20)
    } finally {
      this.framed.hooks.delete(this.#hook)
      this.framed.close()
    }
  }

  /** Artifact-safe projection: only behaviour booleans, counters and dimensions. */
  summary() {
    return {
      attached: Boolean(this.identity),
      hasStream: Boolean(this.streamId),
      terminalStatus: this.terminalStatus,
      lastAppliedVersion: this.lastAppliedVersion,
      ackedVersion: this.ackedVersion,
      dims: { cols: this.cols, rows: this.rows },
      counters: { ...this.counters },
      exited: this.exitEvents.length > 0,
    }
  }
}
