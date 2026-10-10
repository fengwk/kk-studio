/**
 * Wire codec for the reverse IDE data plane (one connection per HTTP exchange / WebSocket).
 *
 * The protocol is defined by the environment refactor contract:
 * - Text (JSON) frames carry control metadata only.
 * - Binary frames carry bounded payloads with an explicit credit window.
 *
 * Text frames:
 *   { type: 'HTTP_RESPONSE', status, headers: [[name, value], ...] }  emitted once, before body
 *   { type: 'WS_READY' }                                              upstream WS accepted
 *   { type: 'ERROR', message }                                        fixed safe message
 *
 * Binary frames:
 *   byte 1 + payload                       HTTP body chunk (payload <= 65536)
 *   byte 2                                 HTTP END / half close
 *   byte 3 + uint32 big endian             incremental ACK of consumed payload bytes
 *   byte 4 + last(0|1) + payload           WS text fragment
 *   byte 5 + last(0|1) + payload           WS binary fragment
 *
 * Unknown frame types, malformed ACKs and out-of-window traffic fail closed.
 */

export const INITIAL_CREDIT = 262_144
export const MAX_PAYLOAD = 65_536
export const MAX_TEXT_FRAME_BYTES = 16 * 1024

export const FRAME_HTTP_BODY = 1
export const FRAME_HTTP_END = 2
export const FRAME_ACK = 3
export const FRAME_WS_TEXT = 4
export const FRAME_WS_BINARY = 5

/** Protocol level error; messages are safe to surface to operators. */
export class WireError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'WireError'
  }
}

export type BinaryFrame =
  | { kind: 'http-body'; payload: Buffer }
  | { kind: 'http-end' }
  | { kind: 'ack'; bytes: number }
  | { kind: 'ws-text'; last: boolean; payload: Buffer }
  | { kind: 'ws-binary'; last: boolean; payload: Buffer }

export interface HttpResponseFrame {
  type: 'HTTP_RESPONSE'
  status: number
  headers: [string, string][]
}

export interface WsReadyFrame {
  type: 'WS_READY'
}

export interface ErrorFrame {
  type: 'ERROR'
  message: string
}

export type TextFrame = HttpResponseFrame | WsReadyFrame | ErrorFrame

function assertPayloadSize(length: number, what: string): void {
  if (length > MAX_PAYLOAD) {
    throw new WireError(`${what} exceeds ${MAX_PAYLOAD} byte payload limit`)
  }
}

export function decodeBinaryFrame(data: Buffer): BinaryFrame {
  if (data.length === 0) {
    throw new WireError('empty binary frame')
  }
  const type = data[0]
  switch (type) {
    case FRAME_HTTP_BODY: {
      const payload = data.subarray(1)
      assertPayloadSize(payload.length, 'http body frame')
      return { kind: 'http-body', payload }
    }
    case FRAME_HTTP_END: {
      if (data.length !== 1) {
        throw new WireError('http END frame must have no payload')
      }
      return { kind: 'http-end' }
    }
    case FRAME_ACK: {
      if (data.length !== 5) {
        throw new WireError('ack frame must be exactly 5 bytes')
      }
      return { kind: 'ack', bytes: data.readUInt32BE(1) }
    }
    case FRAME_WS_TEXT:
    case FRAME_WS_BINARY: {
      if (data.length < 2) {
        throw new WireError('ws frame is missing the final flag')
      }
      const last = data[1]
      if (last !== 0 && last !== 1) {
        throw new WireError('ws frame final flag must be 0 or 1')
      }
      const payload = data.subarray(2)
      assertPayloadSize(payload.length, 'ws frame')
      return {
        kind: type === FRAME_WS_TEXT ? 'ws-text' : 'ws-binary',
        last: last === 1,
        payload,
      }
    }
    default:
      throw new WireError(`unknown binary frame type ${String(type)}`)
  }
}

export function encodeHttpBody(payload: Buffer): Buffer {
  assertPayloadSize(payload.length, 'http body frame')
  const out = Buffer.allocUnsafe(1 + payload.length)
  out[0] = FRAME_HTTP_BODY
  payload.copy(out, 1)
  return out
}

export function encodeHttpEnd(): Buffer {
  return Buffer.from([FRAME_HTTP_END])
}

export function encodeAck(bytes: number): Buffer {
  if (!Number.isInteger(bytes) || bytes < 0 || bytes > 0xffff_ffff) {
    throw new WireError('invalid ack value')
  }
  const out = Buffer.allocUnsafe(5)
  out[0] = FRAME_ACK
  out.writeUInt32BE(bytes, 1)
  return out
}

export function encodeWsFrame(kind: 'text' | 'binary', payload: Buffer, last: boolean): Buffer {
  assertPayloadSize(payload.length, 'ws frame')
  const out = Buffer.allocUnsafe(2 + payload.length)
  out[0] = kind === 'text' ? FRAME_WS_TEXT : FRAME_WS_BINARY
  out[1] = last ? 1 : 0
  payload.copy(out, 2)
  return out
}

/** Splits a WS message into protocol frames, preserving order and the FIN flag. */
export function* fragmentWsMessage(kind: 'text' | 'binary', payload: Buffer): Generator<Buffer> {
  if (payload.length === 0) {
    yield encodeWsFrame(kind, payload, true)
    return
  }
  let offset = 0
  while (offset < payload.length) {
    const end = Math.min(offset + MAX_PAYLOAD, payload.length)
    yield encodeWsFrame(kind, payload.subarray(offset, end), end === payload.length)
    offset = end
  }
}

function assertOnlyKeys(object: Record<string, unknown>, allowed: readonly string[]): void {
  for (const key of Object.keys(object)) {
    if (!allowed.includes(key)) {
      throw new WireError(`unexpected text frame field ${key}`)
    }
  }
}

export function decodeTextFrame(raw: string): TextFrame {
  if (Buffer.byteLength(raw, 'utf8') > MAX_TEXT_FRAME_BYTES) {
    throw new WireError('text frame exceeds control message limit')
  }
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    throw new WireError('text frame is not valid JSON')
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new WireError('text frame must be a JSON object')
  }
  const object = value as Record<string, unknown>
  switch (object.type) {
    case 'HTTP_RESPONSE': {
      assertOnlyKeys(object, ['type', 'status', 'headers'])
      const status = object.status
      if (typeof status !== 'number' || !Number.isInteger(status) || status < 100 || status > 599) {
        throw new WireError('invalid HTTP_RESPONSE status')
      }
      const rawHeaders = object.headers
      if (!Array.isArray(rawHeaders)) {
        throw new WireError('HTTP_RESPONSE headers must be an array')
      }
      const headers: [string, string][] = rawHeaders.map((entry) => {
        if (!Array.isArray(entry) || entry.length !== 2) {
          throw new WireError('HTTP_RESPONSE header entry must be a name/value pair')
        }
        const [name, headerValue] = entry
        if (typeof name !== 'string' || name.length === 0 || typeof headerValue !== 'string') {
          throw new WireError('HTTP_RESPONSE header entry must be a name/value pair')
        }
        return [name, headerValue]
      })
      return { type: 'HTTP_RESPONSE', status, headers }
    }
    case 'WS_READY': {
      assertOnlyKeys(object, ['type'])
      return { type: 'WS_READY' }
    }
    case 'ERROR': {
      assertOnlyKeys(object, ['type', 'message'])
      const message = object.message
      if (typeof message !== 'string' || message.length === 0 || message.length > 1024) {
        throw new WireError('ERROR frame requires a bounded message')
      }
      return { type: 'ERROR', message }
    }
    default:
      throw new WireError(`unknown text frame type ${String(object.type)}`)
  }
}

export function encodeTextFrame(frame: TextFrame): string {
  return JSON.stringify(frame)
}

/**
 * Tracks how many payload bytes were reserved but not yet acknowledged.
 *
 * Used in both directions; `reserve` guards against window overrun, `ack` releases
 * capacity and wakes blocked senders.
 */
export class CreditWindow {
  private unacked = 0
  private readonly waiters = new Set<() => void>()

  constructor(readonly limit: number = INITIAL_CREDIT) {
    if (!Number.isInteger(limit) || limit <= 0) {
      throw new WireError('invalid credit window size')
    }
  }

  get available(): number {
    return this.limit - this.unacked
  }

  get outstanding(): number {
    return this.unacked
  }

  reserve(bytes: number): void {
    if (!Number.isInteger(bytes) || bytes < 0) {
      throw new WireError('invalid credit reservation')
    }
    if (bytes > this.available) {
      throw new WireError('credit window exceeded')
    }
    this.unacked += bytes
  }

  ack(bytes: number): void {
    if (!Number.isInteger(bytes) || bytes < 0) {
      throw new WireError('invalid ack amount')
    }
    if (bytes > this.unacked) {
      throw new WireError('ack exceeds unacknowledged payload')
    }
    this.unacked -= bytes
    if (this.waiters.size > 0 && this.available > 0) {
      const waiters = [...this.waiters]
      this.waiters.clear()
      for (const wake of waiters) {
        wake()
      }
    }
  }

  async waitFor(bytes: number): Promise<void> {
    if (!Number.isInteger(bytes) || bytes < 0 || bytes > this.limit) {
      throw new WireError('invalid credit wait')
    }
    while (this.available < bytes) {
      await new Promise<void>((resolve) => this.waiters.add(resolve))
    }
  }
}

const HOP_BY_HOP = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
])

export function isHopByHopHeader(name: string): boolean {
  return HOP_BY_HOP.has(name.toLowerCase())
}

/** Filters hop-by-hop headers while preserving order, duplicates and multi-value entries. */
export function filterResponseHeaders(rawHeaders: readonly string[]): [string, string][] {
  const result: [string, string][] = []
  for (let index = 0; index + 1 < rawHeaders.length; index += 2) {
    const name = rawHeaders[index]
    const value = rawHeaders[index + 1]
    if (name === undefined || value === undefined) {
      continue
    }
    if (isHopByHopHeader(name)) {
      continue
    }
    result.push([name, value])
  }
  return result
}

/** Rejects IDE-relative paths that could redirect the upstream connection elsewhere. */
export function validateIdePath(path: string): string {
  if (typeof path !== 'string' || path.length === 0) {
    throw new WireError('request path is required')
  }
  if (!path.startsWith('/')) {
    throw new WireError('request path must be absolute')
  }
  if (path.startsWith('//')) {
    throw new WireError('request path must not be network-relative')
  }
  if (path.includes('\\') || path.includes(' ')) {
    throw new WireError('request path contains illegal characters')
  }
  for (const character of path) {
    const code = character.codePointAt(0)
    if (code === undefined || code < 0x21 || code === 0x7f) {
      throw new WireError('request path contains control characters')
    }
  }
  return path
}
