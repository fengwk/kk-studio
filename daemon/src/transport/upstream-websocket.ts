/**
 * Minimal, bounded RFC 6455 WebSocket client for the local code-server.
 *
 * The gateway side of the reverse data plane uses the pinned `ws` library, but a managed IDE
 * message may be arbitrarily large and `ws` reassembles a full message in memory before
 * emitting it. This client streams instead: it performs the upgrade handshake itself, reads
 * frame headers (at most 14 bytes) and payloads incrementally, and yields payload pieces no
 * larger than `MAX_FRAME_PIECE`. Neither direction ever buffers a whole message, and there is
 * no per-message size cap.
 *
 * Only what a loopback client needs is implemented: no compression (permessage-deflate is
 * never advertised and any negotiated extension is refused), no redirects, strict canonical
 * framing and fail-closed handling of malformed input. Writes are complete masked frames and
 * resolve after the TCP flush so callers can release credit only once bytes have left.
 */

import { createHash, randomBytes } from 'node:crypto'
import http from 'node:http'
import type { IncomingMessage } from 'node:http'
import type { Socket } from 'node:net'
import { WireError } from '../ide/wire.js'

/** Maximum payload carried by one yielded piece / one outbound frame (matches MAX_PAYLOAD). */
export const MAX_FRAME_PIECE = 65_536

const HANDSHAKE_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
const DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 10_000
const MAX_CONTROL_PAYLOAD = 125
const EMPTY = Buffer.alloc(0)

const OPCODE_CONTINUATION = 0x0
const OPCODE_TEXT = 0x1
const OPCODE_BINARY = 0x2
const OPCODE_CLOSE = 0x8
const OPCODE_PING = 0x9
const OPCODE_PONG = 0xa

/** Text frames must be valid UTF-8; report it as a protocol error rather than a raw TypeError. */
function decodeText(decoder: TextDecoder, chunk: Buffer): void {
  try {
    decoder.decode(chunk, { stream: true })
  } catch {
    throw new WireError('upstream websocket text message is not valid UTF-8')
  }
}

function flushText(decoder: TextDecoder): void {
  try {
    decoder.decode()
  } catch {
    throw new WireError('upstream websocket text message is not valid UTF-8')
  }
}

export interface UpstreamWebSocketOptions {
  host: string
  port: number
  path: string
  /** Subprotocols requested from the local IDE; the server may only select one of these. */
  protocols?: string[]
  /** Already-built request headers (host/cookie injected by the daemon). */
  headers?: [string, string][]
  signal: AbortSignal
  handshakeTimeoutMillis?: number
}

/** A decoded upstream frame. Data payloads are never larger than `MAX_FRAME_PIECE`. */
export type UpstreamFrame =
  | { type: 'data'; kind: 'text' | 'binary'; payload: Buffer; last: boolean }
  | { type: 'close'; code: number; reason: string }

type OutboundOpcode = 'text' | 'binary' | 'continuation'

function abortError(): Error {
  const error = new Error('upstream websocket aborted')
  error.name = 'AbortError'
  return error
}

function toHeaderRecord(pairs: readonly [string, string][]): Record<string, string | string[]> {
  const record: Record<string, string | string[]> = {}
  for (const [name, value] of pairs) {
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

function connectionHasUpgrade(value: string | string[] | undefined): boolean {
  const text = Array.isArray(value) ? value.join(',') : (value ?? '')
  return text
    .toLowerCase()
    .split(',')
    .some((token) => token.trim() === 'upgrade')
}

/**
 * Validates a 101 upgrade response. Redirects never reach here (the client only follows its
 * own request) and a non-101 response is rejected before this runs.
 */
function validateUpgrade(response: IncomingMessage, key: string, protocols: readonly string[]): string | undefined {
  if (response.statusCode !== 101) {
    throw new WireError(`upstream websocket handshake returned status ${response.statusCode}`)
  }
  if ((response.headers.upgrade ?? '').toLowerCase() !== 'websocket') {
    throw new WireError('upstream websocket handshake is missing the Upgrade header')
  }
  if (!connectionHasUpgrade(response.headers.connection)) {
    throw new WireError('upstream websocket handshake is missing Connection: Upgrade')
  }
  const expected = createHash('sha1')
    .update(key + HANDSHAKE_GUID)
    .digest('base64')
  const accept = response.headers['sec-websocket-accept']
  if (typeof accept !== 'string' || accept.trim() !== expected) {
    throw new WireError('upstream websocket handshake accept mismatch')
  }
  // Compression is never advertised; a server that negotiates it is not the frozen IDE.
  const extensions = response.headers['sec-websocket-extensions']
  if (extensions !== undefined && String(extensions).trim().length > 0) {
    throw new WireError('upstream websocket negotiated an unsupported extension')
  }
  const selected = response.headers['sec-websocket-protocol']
  if (selected === undefined) {
    return undefined
  }
  const protocol = Array.isArray(selected) ? (selected[0] ?? '') : selected
  if (!protocols.includes(protocol)) {
    throw new WireError('upstream websocket selected an unexpected subprotocol')
  }
  return protocol
}

function buildMaskedFrame(opcode: number, fin: boolean, payload: Buffer): Buffer {
  const length = payload.length
  const extendedLength = length < 126 ? 0 : length <= 0xffff ? 2 : 8
  const frame = Buffer.allocUnsafe(2 + extendedLength + 4 + length)
  frame[0] = (fin ? 0x80 : 0) | opcode
  if (extendedLength === 0) {
    frame[1] = 0x80 | length
  } else if (extendedLength === 2) {
    frame[1] = 0x80 | 126
    frame.writeUInt16BE(length, 2)
  } else {
    frame[1] = 0x80 | 127
    frame.writeBigUInt64BE(BigInt(length), 2)
  }
  const mask = randomBytes(4)
  const maskOffset = 2 + extendedLength
  mask.copy(frame, maskOffset)
  for (let index = 0; index < length; index += 1) {
    frame[maskOffset + 4 + index] = payload[index]! ^ mask[index & 3]!
  }
  return frame
}

function parseClose(payload: Buffer): { code: number; reason: string } {
  if (payload.length === 0) {
    return { code: 1005, reason: '' }
  }
  if (payload.length === 1) {
    throw new WireError('upstream websocket close frame is truncated')
  }
  const code = payload.readUInt16BE(0)
  if (!isValidCloseCode(code)) {
    throw new WireError('upstream websocket close code is invalid')
  }
  let reason: string
  try {
    reason = new TextDecoder('utf-8', { fatal: true }).decode(payload.subarray(2))
  } catch {
    throw new WireError('upstream websocket close reason is not valid UTF-8')
  }
  return { code, reason }
}

/**
 * Close codes a peer may legally use, matching the `ws` library: 1000-1014 except the reserved
 * 1004/1005/1006, or the private range 3000-4999. Codes such as 1015, 1016 or 2000 are reserved
 * and must be rejected rather than accepted.
 */
export function isValidCloseCode(code: number): boolean {
  return (
    (code >= 1000 && code <= 1014 && code !== 1004 && code !== 1005 && code !== 1006) ||
    (code >= 3000 && code <= 4999)
  )
}

/** Truncates UTF-8 text to at most `maxBytes` bytes without splitting a code point. */
function truncateUtf8(text: string, maxBytes: number): Buffer {
  const bytes = Buffer.from(text, 'utf8')
  if (bytes.length <= maxBytes) {
    return bytes
  }
  let end = maxBytes
  // `bytes[end]` is the first excluded byte; step back while it continues a split code point.
  while (end > 0 && (bytes[end]! & 0xc0) === 0x80) {
    end -= 1
  }
  return bytes.subarray(0, end)
}

export class UpstreamWebSocket {
  private closed = false

  private constructor(
    private readonly socket: Socket,
    private readonly head: Buffer,
    readonly protocol: string | undefined,
  ) {
    // A stray socket error outside an active read must never crash the daemon; the frame
    // iterator reports the failure to its consumer.
    this.socket.on('error', () => undefined)
  }

  static async connect(options: UpstreamWebSocketOptions): Promise<UpstreamWebSocket> {
    if (options.signal.aborted) {
      throw abortError()
    }
    const key = randomBytes(16).toString('base64')
    const protocols = options.protocols ?? []
    const headers = toHeaderRecord(options.headers ?? [])
    headers['connection'] = 'Upgrade'
    headers['upgrade'] = 'websocket'
    headers['sec-websocket-version'] = '13'
    headers['sec-websocket-key'] = key
    if (protocols.length > 0) {
      headers['sec-websocket-protocol'] = protocols.join(', ')
    }
    const timeout = options.handshakeTimeoutMillis ?? DEFAULT_HANDSHAKE_TIMEOUT_MILLIS
    return new Promise<UpstreamWebSocket>((resolve, reject) => {
      let settled = false
      let timer: NodeJS.Timeout | undefined
      let request: http.ClientRequest | undefined
      // Releases the handshake timer and the abort listener without touching a resolved socket.
      const stopWaiting = (): void => {
        if (timer !== undefined) {
          clearTimeout(timer)
          timer = undefined
        }
        options.signal.removeEventListener('abort', onAbort)
      }
      // Every failed handshake destroys the request, so a response that never ends (no length,
      // no EOF) cannot leave the socket open.
      const finish = (error: unknown): void => {
        if (settled) {
          return
        }
        settled = true
        stopWaiting()
        request?.destroy()
        reject(error)
      }
      const onAbort = (): void => {
        finish(abortError())
      }
      options.signal.addEventListener('abort', onAbort, { once: true })
      try {
        request = http.request({
          host: options.host,
          port: options.port,
          method: 'GET',
          path: options.path,
          headers,
          agent: false,
          signal: options.signal,
        })
      } catch (error) {
        finish(new WireError(`upstream websocket request failed: ${(error as Error).message}`))
        return
      }
      // Armed only once a real request exists, so a synchronously rejected request cannot leave a
      // pending timer that later dereferences it.
      timer = setTimeout(() => {
        finish(new WireError('upstream websocket handshake timed out'))
      }, timeout)
      timer.unref()
      request.on('upgrade', (response: IncomingMessage, socket: Socket, head: Buffer) => {
        try {
          const protocol = validateUpgrade(response, key, protocols)
          if (settled) {
            socket.destroy()
            return
          }
          settled = true
          stopWaiting()
          resolve(new UpstreamWebSocket(socket, head ?? EMPTY, protocol))
        } catch (error) {
          socket.destroy()
          finish(error)
        }
      })
      request.on('response', (response: IncomingMessage) => {
        response.resume()
        // `upgrade` only fires for a complete 101 handshake, so any other response is a failed
        // handshake; reusing the validator keeps the reported reason precise.
        try {
          validateUpgrade(response, key, protocols)
        } catch (error) {
          finish(error)
          return
        }
        finish(new WireError(`upstream websocket handshake returned status ${response.statusCode ?? 0}`))
      })
      request.on('error', (error: Error) => {
        finish(options.signal.aborted ? abortError() : new WireError(`upstream websocket failed: ${error.message}`))
      })
      request.end()
    })
  }

  /** Streams decoded frames with per-fragment backpressure from the socket's async iterator. */
  async *frames(): AsyncGenerator<UpstreamFrame> {
    const iterator = this.socket[Symbol.asyncIterator]()
    let buffer = this.head
    const pull = async (): Promise<Buffer | undefined> => {
      const { value, done } = await iterator.next()
      if (done) {
        return undefined
      }
      return Buffer.isBuffer(value) ? value : Buffer.from(value as Uint8Array)
    }
    const need = async (bytes: number): Promise<boolean> => {
      while (buffer.length < bytes) {
        const chunk = await pull()
        if (chunk === undefined) {
          return false
        }
        buffer = buffer.length === 0 ? chunk : Buffer.concat([buffer, chunk])
      }
      return true
    }

    let messageKind: 'text' | 'binary' | undefined
    let decoder: TextDecoder | undefined

    for (;;) {
      if (!(await need(2))) {
        // The socket ended without a close frame.
        yield { type: 'close', code: 1006, reason: '' }
        return
      }
      const first = buffer[0]!
      const second = buffer[1]!
      buffer = buffer.subarray(2)
      const fin = (first & 0x80) !== 0
      if ((first & 0x70) !== 0) {
        throw new WireError('upstream websocket frame has reserved bits set')
      }
      const opcode = first & 0x0f
      if ((second & 0x80) !== 0) {
        throw new WireError('upstream websocket frame must not be masked')
      }
      const lengthCode = second & 0x7f
      let payloadLength: number
      if (lengthCode === 126) {
        if (!(await need(2))) {
          throw new WireError('upstream websocket frame length is truncated')
        }
        payloadLength = buffer.readUInt16BE(0)
        buffer = buffer.subarray(2)
        if (payloadLength < 126) {
          throw new WireError('upstream websocket frame length is not canonical')
        }
      } else if (lengthCode === 127) {
        if (!(await need(8))) {
          throw new WireError('upstream websocket frame length is truncated')
        }
        const high = buffer.readUInt32BE(0)
        const low = buffer.readUInt32BE(4)
        buffer = buffer.subarray(8)
        if ((high & 0x8000_0000) !== 0) {
          throw new WireError('upstream websocket frame length is invalid')
        }
        payloadLength = high * 0x1_0000_0000 + low
        if (payloadLength < 65_536) {
          throw new WireError('upstream websocket frame length is not canonical')
        }
        if (!Number.isSafeInteger(payloadLength)) {
          throw new WireError('upstream websocket frame is too large')
        }
      } else {
        payloadLength = lengthCode
      }

      if ((opcode & 0x08) !== 0) {
        // Control frames are never fragmented and carry at most 125 bytes.
        if (!fin) {
          throw new WireError('upstream websocket control frame must not be fragmented')
        }
        if (payloadLength > MAX_CONTROL_PAYLOAD) {
          throw new WireError('upstream websocket control frame is too large')
        }
        if (!(await need(payloadLength))) {
          throw new WireError('upstream websocket control frame is truncated')
        }
        const payload = buffer.subarray(0, payloadLength)
        buffer = buffer.subarray(payloadLength)
        if (opcode === OPCODE_CLOSE) {
          yield { type: 'close', ...parseClose(payload) }
          return
        }
        if (opcode === OPCODE_PING) {
          // Answer a ping immediately; this does not depend on the consumer's progress. A socket
          // that has already gone away is benign, but a real write failure must fail the exchange.
          await this.tryWrite(OPCODE_PONG, true, payload)
          continue
        }
        if (opcode === OPCODE_PONG) {
          continue
        }
        throw new WireError(`upstream websocket control opcode ${opcode} is unsupported`)
      }

      let kind: 'text' | 'binary'
      if (opcode === OPCODE_TEXT || opcode === OPCODE_BINARY) {
        if (messageKind !== undefined) {
          throw new WireError('upstream websocket started a new message before finishing the previous one')
        }
        kind = opcode === OPCODE_TEXT ? 'text' : 'binary'
        messageKind = kind
        decoder = kind === 'text' ? new TextDecoder('utf-8', { fatal: true }) : undefined
      } else if (opcode === OPCODE_CONTINUATION) {
        if (messageKind === undefined) {
          throw new WireError('upstream websocket continuation without a message')
        }
        kind = messageKind
      } else {
        throw new WireError(`upstream websocket data opcode ${opcode} is unsupported`)
      }

      let remaining = payloadLength
      let emitted = false
      while (remaining > 0) {
        if (buffer.length === 0) {
          const chunk = await pull()
          if (chunk === undefined) {
            throw new WireError('upstream websocket frame payload is truncated')
          }
          buffer = chunk
        }
        const size = Math.min(remaining, MAX_FRAME_PIECE, buffer.length)
        const piece = buffer.subarray(0, size)
        buffer = buffer.subarray(size)
        remaining -= size
        emitted = true
        if (decoder) {
          // Streaming validation: multi-byte sequences may span pieces, output is discarded.
          decodeText(decoder, piece)
        }
        const last = fin && remaining === 0
        if (last && decoder) {
          // Validate the tail before it is published: a truncated sequence must never claim FIN.
          flushText(decoder)
          decoder = undefined
        }
        yield { type: 'data', kind, payload: piece, last }
      }
      if (!emitted) {
        if (fin && decoder) {
          // Same rule for an empty FIN frame: flush first, then publish.
          flushText(decoder)
          decoder = undefined
        }
        yield { type: 'data', kind, payload: EMPTY, last: fin }
      }
      if (fin) {
        messageKind = undefined
      }
    }
  }

  /** Sends one masked data/continuation frame, resolving after the TCP flush. */
  sendFragment(opcode: OutboundOpcode, payload: Buffer, last: boolean): Promise<void> {
    if (this.closed) {
      return Promise.reject(new WireError('upstream websocket is closed'))
    }
    const value = opcode === 'text' ? OPCODE_TEXT : opcode === 'binary' ? OPCODE_BINARY : OPCODE_CONTINUATION
    return this.write(value, last, payload)
  }

  /** Sends a masked close frame and half-closes the socket. */
  async close(code: number, reason: string): Promise<void> {
    if (this.closed) {
      return
    }
    this.closed = true
    const reasonBytes = truncateUtf8(reason, MAX_CONTROL_PAYLOAD - 2)
    const payload = Buffer.allocUnsafe(2 + reasonBytes.length)
    payload.writeUInt16BE(code, 0)
    reasonBytes.copy(payload, 2)
    // A socket that is already gone cannot take the close frame; that is the only benign failure.
    await this.tryWrite(OPCODE_CLOSE, true, payload)
    this.socket.end()
  }

  destroy(): void {
    this.closed = true
    if (!this.socket.destroyed) {
      this.socket.destroy()
    }
  }

  /**
   * Writes a frame, resolving `false` when the socket has already gone away. Any other write
   * failure rejects, so the caller fails the exchange instead of silently dropping data.
   */
  private tryWrite(opcode: number, fin: boolean, payload: Buffer): Promise<boolean> {
    if (this.socket.destroyed) {
      return Promise.resolve(false)
    }
    return this.write(opcode, fin, payload).then(() => true)
  }

  private write(opcode: number, fin: boolean, payload: Buffer): Promise<void> {
    const socket = this.socket
    if (socket.destroyed) {
      return Promise.reject(new WireError('upstream websocket is closed'))
    }
    const frame = buildMaskedFrame(opcode, fin, payload)
    return new Promise<void>((resolve, reject) => {
      socket.write(frame, (error?: Error | null) => {
        if (error) {
          reject(new WireError(`upstream websocket write failed: ${error.message}`))
          return
        }
        resolve()
      })
    })
  }
}
