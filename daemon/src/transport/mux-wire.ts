import { Buffer } from 'node:buffer'

/**
 * IDE data mux v2 wire codec.
 *
 * <p>A physical WebSocket binary message carries exactly one frame:
 *
 * <pre>
 * version:u8 | type:u8 | flags:u16 | streamId:u32 | payloadLength:u32 | payload
 * </pre>
 *
 * <p>All integers are network byte order. The codec is pure and strict: it never retries, repairs or
 * ignores malformed input. Adapters accumulate at most one WebSocket message and call
 * {@link decodeFrame}; the returned {@link MuxFrame} is the complete logical frame.
 *
 * <p>Frames:
 * <ul>
 *   <li>1 OPEN: request JSON {@code {viewId, bindingEpoch, mode, method, path, headers}}; server to daemon.</li>
 *   <li>2 READY: empty; daemon accepted an OPEN.</li>
 *   <li>3 HEADERS: response JSON {@code {status, headers}}; once, before response DATA.</li>
 *   <li>4 DATA: HTTP flags=0, or WS TEXT/BINARY plus optional FIN.</li>
 *   <li>5 WINDOW: positive u32 credit; streamId=0 is connection credit.</li>
 *   <li>6 END: empty HTTP direction half-close.</li>
 *   <li>7 COMPLETE: empty; server consumed the response END and flushed the browser response.</li>
 *   <li>8 RESET: JSON {@code {code, message}}; ends both directions without replay.</li>
 *   <li>9 CLOSE: WS close code plus UTF-8 reason.</li>
 * </ul>
 *
 * <p>OPEN/HEADERS/RESET metadata is bounded to 16 KiB. JSON objects reject unknown and duplicate
 * keys; strings must be well formed UTF-16 and metadata uses strict UTF-8 decoding. Application
 * strings are still caller-supplied: RESET content must already be redacted, and request headers
 * must already be filtered to the safe forwarding set.
 */

/** Wire version carried by every frame. */
export const MUX_WIRE_VERSION = 2
/** Fixed frame header size. */
export const MUX_FRAME_HEADER_BYTES = 12
/** Maximum frame payload (DATA or metadata JSON). */
export const MUX_MAX_PAYLOAD_BYTES = 65_536
/** Maximum OPEN/HEADERS/RESET JSON size. */
export const MUX_MAX_METADATA_BYTES = 16 * 1024
/** Maximum OPEN request headers. */
export const MUX_MAX_OPEN_HEADERS = 100
/** Maximum OPEN path size. */
export const MUX_MAX_PATH_BYTES = 8 * 1024
/** Maximum HTTP method token length. */
export const MUX_MAX_METHOD_BYTES = 32
/** Maximum RESET code length. */
export const MUX_MAX_RESET_CODE_BYTES = 64
/** Maximum RESET message length in UTF-16 code units. */
export const MUX_MAX_RESET_MESSAGE_CHARS = 500
/** Maximum WS close reason size. */
export const MUX_MAX_CLOSE_REASON_BYTES = 123
/** Maximum binding epoch accepted by the Java long side. */
export const MUX_MAX_BINDING_EPOCH = 9_223_372_036_854_775_807n
/** Stream id 0 is reserved for connection WINDOW frames. */
export const MUX_MAX_STREAM_ID = 0xffff_ffff

export const FRAME_OPEN = 1
export const FRAME_READY = 2
export const FRAME_HEADERS = 3
export const FRAME_DATA = 4
export const FRAME_WINDOW = 5
export const FRAME_END = 6
export const FRAME_COMPLETE = 7
export const FRAME_RESET = 8
export const FRAME_CLOSE = 9

export type MuxMode = 'HTTP' | 'WS'
export type HeaderPair = [string, string]

export interface OpenRequest {
  /** Canonical lowercase UUID. */
  viewId: string
  /** Canonical positive decimal string, at most the Java long maximum. */
  bindingEpoch: string
  mode: MuxMode
  /** HTTP token, at most 32 bytes. */
  method: string
  /** Relative request path beginning with '/', at most 8192 UTF-8 bytes. */
  path: string
  /** Ordered multi-value request headers. */
  headers: HeaderPair[]
}

export interface HeadersPayload {
  status: number
  headers: HeaderPair[]
}

export interface ResetPayload {
  code: string
  message: string
}

export interface ClosePayload {
  code: number
  reason: string
}

export type DataFlags = { kind: 'http' } | { kind: 'ws'; text: boolean; fin: boolean }

export interface OpenFrame {
  type: 'OPEN'
  streamId: number
  request: OpenRequest
}

export interface ReadyFrame {
  type: 'READY'
  streamId: number
}

export interface HeadersFrame {
  type: 'HEADERS'
  streamId: number
  headers: HeadersPayload
}

export interface DataFrame {
  type: 'DATA'
  streamId: number
  payload: Uint8Array
  flags: DataFlags
}

export interface WindowFrame {
  type: 'WINDOW'
  streamId: number
  credit: number
}

export interface EndFrame {
  type: 'END'
  streamId: number
}

export interface CompleteFrame {
  type: 'COMPLETE'
  streamId: number
}

export interface ResetFrame {
  type: 'RESET'
  streamId: number
  reset: ResetPayload
}

export interface CloseFrame {
  type: 'CLOSE'
  streamId: number
  close: ClosePayload
}

export type MuxFrame = OpenFrame | ReadyFrame | HeadersFrame | DataFrame | WindowFrame | EndFrame | CompleteFrame | ResetFrame | CloseFrame

/** A deterministic, operator-safe wire/codec error. */
export class MuxWireError extends Error {
  constructor(message: string, cause?: unknown) {
    super(message, cause === undefined ? undefined : { cause })
    this.name = 'MuxWireError'
  }
}

const UTF8_DECODER = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true })
const TOKEN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const POSITIVE_DECIMAL = /^[1-9][0-9]*$/
const RESET_CODE = /^[A-Z][A-Z0-9_]*$/
const JSON_CONTROL = /[\u0000-\u001f\u007f-\u009f]/
const MAX_JSON_DEPTH = 32

type JsonObject = { [key: string]: JsonValue }
type JsonValue = null | boolean | number | string | JsonValue[] | JsonObject

class StrictJsonParser {
  private index = 0

  constructor(private readonly text: string) {}

  parse(): JsonValue {
    const value = this.parseValue(0)
    this.skipWhitespace()
    if (this.index !== this.text.length) {
      throw new MuxWireError('metadata has trailing JSON data')
    }
    return value
  }

  private parseValue(depth: number): JsonValue {
    if (depth > MAX_JSON_DEPTH) {
      throw new MuxWireError('metadata JSON is nested too deeply')
    }
    this.skipWhitespace()
    const character = this.text[this.index]
    if (character === '{') {
      return this.parseObject(depth)
    }
    if (character === '[') {
      return this.parseArray(depth)
    }
    if (character === '"') {
      return this.parseString()
    }
    if (character === 't') {
      return this.parseLiteral('true', true)
    }
    if (character === 'f') {
      return this.parseLiteral('false', false)
    }
    if (character === 'n') {
      return this.parseLiteral('null', null)
    }
    if (character === '-' || (character !== undefined && character >= '0' && character <= '9')) {
      return this.parseNumber()
    }
    throw new MuxWireError('metadata contains invalid JSON')
  }

  private parseObject(depth: number): JsonObject {
    this.index++
    const value: JsonObject = Object.create(null) as JsonObject
    this.skipWhitespace()
    if (this.text[this.index] === '}') {
      this.index++
      return value
    }
    for (;;) {
      this.skipWhitespace()
      if (this.text[this.index] !== '"') {
        throw new MuxWireError('metadata JSON object key must be a string')
      }
      const key = this.parseString()
      if (Object.hasOwn(value, key)) {
        throw new MuxWireError('metadata contains a duplicate key')
      }
      this.skipWhitespace()
      if (this.text[this.index] !== ':') {
        throw new MuxWireError('metadata JSON object is missing a colon')
      }
      this.index++
      value[key] = this.parseValue(depth + 1)
      this.skipWhitespace()
      const separator = this.text[this.index]
      if (separator === '}') {
        this.index++
        return value
      }
      if (separator !== ',') {
        throw new MuxWireError('metadata JSON object is missing a comma')
      }
      this.index++
    }
  }

  private parseArray(depth: number): JsonValue[] {
    this.index++
    const value: JsonValue[] = []
    this.skipWhitespace()
    if (this.text[this.index] === ']') {
      this.index++
      return value
    }
    for (;;) {
      value.push(this.parseValue(depth + 1))
      this.skipWhitespace()
      const separator = this.text[this.index]
      if (separator === ']') {
        this.index++
        return value
      }
      if (separator !== ',') {
        throw new MuxWireError('metadata JSON array is missing a comma')
      }
      this.index++
    }
  }

  private parseString(): string {
    this.index++
    let value = ''
    for (;;) {
      const character = this.text[this.index]
      if (character === undefined) {
        throw new MuxWireError('metadata JSON string is unterminated')
      }
      this.index++
      if (character === '"') {
        return value
      }
      if (character === '\\') {
        const escape = this.text[this.index]
        this.index++
        switch (escape) {
          case '"':
          case '\\':
          case '/':
            value += escape
            break
          case 'b':
            value += '\b'
            break
          case 'f':
            value += '\f'
            break
          case 'n':
            value += '\n'
            break
          case 'r':
            value += '\r'
            break
          case 't':
            value += '\t'
            break
          case 'u': {
            const hex = this.text.slice(this.index, this.index + 4)
            if (!/^[0-9a-fA-F]{4}$/.test(hex)) {
              throw new MuxWireError('metadata JSON has an invalid unicode escape')
            }
            value += String.fromCharCode(Number.parseInt(hex, 16))
            this.index += 4
            break
          }
          default:
            throw new MuxWireError('metadata JSON has an invalid string escape')
        }
        continue
      }
      if (character.charCodeAt(0) < 0x20) {
        throw new MuxWireError('metadata JSON string contains a control character')
      }
      value += character
    }
  }

  private parseNumber(): number {
    const match = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(this.text.slice(this.index))
    if (match === null) {
      throw new MuxWireError('metadata contains an invalid JSON number')
    }
    this.index += match[0].length
    const value = Number(match[0])
    if (!Number.isFinite(value)) {
      throw new MuxWireError('metadata number is not finite')
    }
    return value
  }

  private parseLiteral<T extends JsonValue>(literal: string, value: T): T {
    if (this.text.slice(this.index, this.index + literal.length) !== literal) {
      throw new MuxWireError('metadata contains invalid JSON')
    }
    this.index += literal.length
    return value
  }

  private skipWhitespace(): void {
    while (true) {
      const character = this.text[this.index]
      if (character !== ' ' && character !== '\t' && character !== '\n' && character !== '\r') {
        return
      }
      this.index++
    }
  }
}

function parseMetadata(payload: Uint8Array): JsonObject {
  if (payload.byteLength > MUX_MAX_METADATA_BYTES) {
    throw new MuxWireError(`metadata exceeds ${MUX_MAX_METADATA_BYTES} bytes`)
  }
  let text: string
  try {
    text = UTF8_DECODER.decode(payload)
  } catch (error) {
    throw new MuxWireError('metadata is not valid UTF-8', error)
  }
  const value = new StrictJsonParser(text).parse()
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new MuxWireError('metadata must be a JSON object')
  }
  return value
}

function expectExactKeys(value: JsonObject, allowed: readonly string[], what: string): void {
  for (const key of Object.keys(value)) {
    if (!allowed.includes(key)) {
      throw new MuxWireError(`${what} contains an unknown field`)
    }
  }
  for (const key of allowed) {
    if (!Object.hasOwn(value, key)) {
      throw new MuxWireError(`${what} is missing field ${key}`)
    }
  }
}

function expectString(value: JsonValue | undefined, what: string): string {
  if (typeof value !== 'string') {
    throw new MuxWireError(`${what} must be a string`)
  }
  assertWellFormed(value, what)
  return value
}

function expectInteger(value: JsonValue | undefined, what: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) {
    throw new MuxWireError(`${what} must be an integer`)
  }
  return value
}

function expectArray(value: JsonValue | undefined, what: string): JsonValue[] {
  if (!Array.isArray(value)) {
    throw new MuxWireError(`${what} must be an array`)
  }
  return value
}

function assertWellFormed(value: string, what: string): void {
  for (let index = 0; index < value.length; index++) {
    const unit = value.charCodeAt(index)
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = value.charCodeAt(index + 1)
      if (index + 1 >= value.length || next < 0xdc00 || next > 0xdfff) {
        throw new MuxWireError(`${what} contains an unpaired surrogate`)
      }
      index++
    } else if (unit >= 0xdc00 && unit <= 0xdfff) {
      throw new MuxWireError(`${what} contains an unpaired surrogate`)
    }
  }
}

function assertToken(value: string, what: string, maxBytes: number): void {
  if (!TOKEN.test(value)) {
    throw new MuxWireError(`${what} is not a valid HTTP token`)
  }
  if (Buffer.byteLength(value, 'utf8') > maxBytes) {
    throw new MuxWireError(`${what} exceeds ${maxBytes} bytes`)
  }
}

function decodeHeaderPairs(value: JsonValue | undefined, what: string, maxItems?: number): HeaderPair[] {
  const array = expectArray(value, what)
  if (maxItems !== undefined && array.length > maxItems) {
    throw new MuxWireError(`${what} exceeds ${maxItems} entries`)
  }
  return array.map((entry, index) => {
    if (!Array.isArray(entry) || entry.length !== 2) {
      throw new MuxWireError(`${what} entry ${index} must be a name/value pair`)
    }
    const name = expectString(entry[0], `${what} name`)
    const headerValue = expectString(entry[1], `${what} value`)
    assertToken(name, `${what} name`, Number.MAX_SAFE_INTEGER)
    if (headerValue.includes('\r') || headerValue.includes('\n') || headerValue.includes('\0')) {
      throw new MuxWireError(`${what} value contains a prohibited character`)
    }
    return [name, headerValue]
  })
}

function decodeOpenRequest(payload: Uint8Array): OpenRequest {
  const object = parseMetadata(payload)
  expectExactKeys(object, ['viewId', 'bindingEpoch', 'mode', 'method', 'path', 'headers'], 'OPEN')
  const viewId = expectString(object.viewId, 'OPEN viewId')
  if (!UUID.test(viewId)) {
    throw new MuxWireError('OPEN viewId must be a canonical lowercase UUID')
  }
  const bindingEpoch = expectString(object.bindingEpoch, 'OPEN bindingEpoch')
  if (!POSITIVE_DECIMAL.test(bindingEpoch)) {
    throw new MuxWireError('OPEN bindingEpoch must be a canonical positive decimal string')
  }
  if (BigInt(bindingEpoch) > MUX_MAX_BINDING_EPOCH) {
    throw new MuxWireError('OPEN bindingEpoch exceeds the Java long range')
  }
  const mode = expectString(object.mode, 'OPEN mode')
  if (mode !== 'HTTP' && mode !== 'WS') {
    throw new MuxWireError('OPEN mode must be HTTP or WS')
  }
  const method = expectString(object.method, 'OPEN method')
  assertToken(method, 'OPEN method', MUX_MAX_METHOD_BYTES)
  const path = expectString(object.path, 'OPEN path')
  if (!path.startsWith('/')) {
    throw new MuxWireError('OPEN path must be relative and begin with /')
  }
  if (Buffer.byteLength(path, 'utf8') > MUX_MAX_PATH_BYTES) {
    throw new MuxWireError(`OPEN path exceeds ${MUX_MAX_PATH_BYTES} UTF-8 bytes`)
  }
  if (path.includes('\r') || path.includes('\n')) {
    throw new MuxWireError('OPEN path must not contain CR or LF')
  }
  const headers = decodeHeaderPairs(object.headers, 'OPEN headers', MUX_MAX_OPEN_HEADERS)
  return { viewId, bindingEpoch, mode, method, path, headers }
}

function decodeHeaders(payload: Uint8Array): HeadersPayload {
  const object = parseMetadata(payload)
  expectExactKeys(object, ['status', 'headers'], 'HEADERS')
  const status = expectInteger(object.status, 'HEADERS status')
  if (status < 100 || status > 599) {
    throw new MuxWireError('HEADERS status must be between 100 and 599')
  }
  return { status, headers: decodeHeaderPairs(object.headers, 'HEADERS headers') }
}

function decodeReset(payload: Uint8Array): ResetPayload {
  const object = parseMetadata(payload)
  expectExactKeys(object, ['code', 'message'], 'RESET')
  const code = expectString(object.code, 'RESET code')
  if (!RESET_CODE.test(code) || Buffer.byteLength(code, 'utf8') > MUX_MAX_RESET_CODE_BYTES) {
    throw new MuxWireError(`RESET code must be an uppercase token of at most ${MUX_MAX_RESET_CODE_BYTES} bytes`)
  }
  const message = expectString(object.message, 'RESET message')
  if (message.length > MUX_MAX_RESET_MESSAGE_CHARS) {
    throw new MuxWireError(`RESET message exceeds ${MUX_MAX_RESET_MESSAGE_CHARS} characters`)
  }
  if (JSON_CONTROL.test(message)) {
    throw new MuxWireError('RESET message contains a control character')
  }
  return { code, message }
}

function encodeOpenRequest(request: OpenRequest): Uint8Array {
  if (typeof request !== 'object' || request === null) {
    throw new MuxWireError('OPEN request is required')
  }
  if (typeof request.viewId !== 'string' || !UUID.test(request.viewId)) {
    throw new MuxWireError('OPEN viewId must be a canonical lowercase UUID')
  }
  if (typeof request.bindingEpoch !== 'string' || !POSITIVE_DECIMAL.test(request.bindingEpoch)) {
    throw new MuxWireError('OPEN bindingEpoch must be a canonical positive decimal string')
  }
  if (BigInt(request.bindingEpoch) > MUX_MAX_BINDING_EPOCH) {
    throw new MuxWireError('OPEN bindingEpoch exceeds the Java long range')
  }
  if (request.mode !== 'HTTP' && request.mode !== 'WS') {
    throw new MuxWireError('OPEN mode must be HTTP or WS')
  }
  if (typeof request.method !== 'string') {
    throw new MuxWireError('OPEN method must be a string')
  }
  assertToken(request.method, 'OPEN method', MUX_MAX_METHOD_BYTES)
  if (typeof request.path !== 'string' || !request.path.startsWith('/')) {
    throw new MuxWireError('OPEN path must be relative and begin with /')
  }
  assertWellFormed(request.path, 'OPEN path')
  if (Buffer.byteLength(request.path, 'utf8') > MUX_MAX_PATH_BYTES) {
    throw new MuxWireError(`OPEN path exceeds ${MUX_MAX_PATH_BYTES} UTF-8 bytes`)
  }
  if (request.path.includes('\r') || request.path.includes('\n')) {
    throw new MuxWireError('OPEN path must not contain CR or LF')
  }
  return encodeMetadata({
    viewId: request.viewId,
    bindingEpoch: request.bindingEpoch,
    mode: request.mode,
    method: request.method,
    path: request.path,
    headers: encodeHeaderPairs(request.headers, 'OPEN headers', MUX_MAX_OPEN_HEADERS),
  })
}

function encodeHeadersPayload(headers: HeadersPayload): Uint8Array {
  if (typeof headers !== 'object' || headers === null) {
    throw new MuxWireError('HEADERS payload is required')
  }
  if (!Number.isSafeInteger(headers.status) || headers.status < 100 || headers.status > 599) {
    throw new MuxWireError('HEADERS status must be between 100 and 599')
  }
  return encodeMetadata({ status: headers.status, headers: encodeHeaderPairs(headers.headers, 'HEADERS headers') })
}

function encodeResetPayload(reset: ResetPayload): Uint8Array {
  if (typeof reset !== 'object' || reset === null) {
    throw new MuxWireError('RESET payload is required')
  }
  if (typeof reset.code !== 'string' || !RESET_CODE.test(reset.code) || Buffer.byteLength(reset.code, 'utf8') > MUX_MAX_RESET_CODE_BYTES) {
    throw new MuxWireError(`RESET code must be an uppercase token of at most ${MUX_MAX_RESET_CODE_BYTES} bytes`)
  }
  if (typeof reset.message !== 'string') {
    throw new MuxWireError('RESET message must be a string')
  }
  assertWellFormed(reset.message, 'RESET message')
  if (reset.message.length > MUX_MAX_RESET_MESSAGE_CHARS) {
    throw new MuxWireError(`RESET message exceeds ${MUX_MAX_RESET_MESSAGE_CHARS} characters`)
  }
  if (JSON_CONTROL.test(reset.message)) {
    throw new MuxWireError('RESET message contains a control character')
  }
  return encodeMetadata({ code: reset.code, message: reset.message })
}

function encodeHeaderPairs(headers: HeaderPair[], what: string, maxItems?: number): HeaderPair[] {
  if (!Array.isArray(headers)) {
    throw new MuxWireError(`${what} must be an array`)
  }
  if (maxItems !== undefined && headers.length > maxItems) {
    throw new MuxWireError(`${what} exceeds ${maxItems} entries`)
  }
  return headers.map((entry, index) => {
    if (!Array.isArray(entry) || entry.length !== 2) {
      throw new MuxWireError(`${what} entry ${index} must be a name/value pair`)
    }
    const name = entry[0]
    const value = entry[1]
    if (typeof name !== 'string' || typeof value !== 'string') {
      throw new MuxWireError(`${what} entry ${index} must be a name/value pair`)
    }
    assertWellFormed(name, `${what} name`)
    assertWellFormed(value, `${what} value`)
    assertToken(name, `${what} name`, Number.MAX_SAFE_INTEGER)
    if (value.includes('\r') || value.includes('\n') || value.includes('\0')) {
      throw new MuxWireError(`${what} value contains a prohibited character`)
    }
    return [name, value]
  })
}

function encodeMetadata(value: JsonObject): Uint8Array {
  const encoded = Buffer.from(JSON.stringify(value), 'utf8')
  if (encoded.byteLength > MUX_MAX_METADATA_BYTES) {
    throw new MuxWireError(`metadata exceeds ${MUX_MAX_METADATA_BYTES} bytes`)
  }
  return encoded
}

function assertStreamId(streamId: number, allowZero: boolean): void {
  if (!Number.isSafeInteger(streamId) || streamId < (allowZero ? 0 : 1) || streamId > MUX_MAX_STREAM_ID) {
    throw new MuxWireError('invalid stream id')
  }
}

function encodeDataFlags(flags: DataFlags): number {
  if (typeof flags !== 'object' || flags === null) {
    throw new MuxWireError('DATA flags are required')
  }
  if (flags.kind === 'http') {
    return 0
  }
  if (flags.kind !== 'ws' || typeof flags.text !== 'boolean' || typeof flags.fin !== 'boolean') {
    throw new MuxWireError('invalid DATA flags')
  }
  return (flags.text ? 0x1 : 0x2) | (flags.fin ? 0x4 : 0)
}

/** Decodes DATA flags; HTTP has zero flags and WS uses exactly one of TEXT/BINARY plus optional FIN. */
export function decodeDataFlags(flags: number): DataFlags {
  if (!Number.isInteger(flags) || flags < 0 || flags > 0xffff) {
    throw new MuxWireError('invalid DATA flags')
  }
  if (flags === 0) {
    return { kind: 'http' }
  }
  const reserved = flags & ~0x7
  const text = (flags & 0x1) !== 0
  const binary = (flags & 0x2) !== 0
  if (reserved !== 0 || text === binary) {
    throw new MuxWireError('invalid DATA flags')
  }
  return { kind: 'ws', text, fin: (flags & 0x4) !== 0 }
}

function payloadBytes(payload: Uint8Array, what: string): Uint8Array {
  if (!(payload instanceof Uint8Array)) {
    throw new MuxWireError(`${what} payload must be a Uint8Array`)
  }
  if (payload.byteLength > MUX_MAX_PAYLOAD_BYTES) {
    throw new MuxWireError(`${what} payload exceeds ${MUX_MAX_PAYLOAD_BYTES} bytes`)
  }
  return payload
}

/** Credit charged by one DATA frame; empty/small frames cost at least 512. */
export function frameCost(payloadLength: number): number {
  if (!Number.isSafeInteger(payloadLength) || payloadLength < 0) {
    throw new MuxWireError('invalid DATA payload length')
  }
  return Math.max(payloadLength, 512)
}

function assertCloseCode(code: number): void {
  if (
    !Number.isInteger(code) ||
    !((code >= 1000 && code <= 1003) || (code >= 1007 && code <= 1014) || (code >= 3000 && code <= 4999))
  ) {
    throw new MuxWireError('invalid WS close code')
  }
}

function assertUtf8Bytes(payload: Uint8Array, what: string): void {
  try {
    UTF8_DECODER.decode(payload)
  } catch (error) {
    throw new MuxWireError(`${what} is not valid UTF-8`, error)
  }
}

function encodePayloadFrame(type: number, streamId: number, flags: number, payload: Uint8Array): Uint8Array {
  assertStreamId(streamId, type === FRAME_WINDOW)
  const frame = Buffer.allocUnsafe(MUX_FRAME_HEADER_BYTES + payload.byteLength)
  frame[0] = MUX_WIRE_VERSION
  frame[1] = type
  frame.writeUInt16BE(flags, 2)
  frame.writeUInt32BE(streamId, 4)
  frame.writeUInt32BE(payload.byteLength, 8)
  frame.set(payload, MUX_FRAME_HEADER_BYTES)
  return frame
}

/** Encodes one logical frame, validating every fixed shape and bound first. */
export function encodeFrame(frame: MuxFrame): Uint8Array {
  if (typeof frame !== 'object' || frame === null) {
    throw new MuxWireError('frame is required')
  }
  switch (frame.type) {
    case 'OPEN':
      return encodePayloadFrame(FRAME_OPEN, frame.streamId, 0, encodeOpenRequest(frame.request))
    case 'READY':
      return encodePayloadFrame(FRAME_READY, frame.streamId, 0, new Uint8Array(0))
    case 'HEADERS':
      return encodePayloadFrame(FRAME_HEADERS, frame.streamId, 0, encodeHeadersPayload(frame.headers))
    case 'DATA': {
      const payload = payloadBytes(frame.payload, 'DATA')
      return encodePayloadFrame(FRAME_DATA, frame.streamId, encodeDataFlags(frame.flags), payload)
    }
    case 'WINDOW': {
      if (!Number.isSafeInteger(frame.credit) || frame.credit <= 0 || frame.credit > 0xffff_ffff) {
        throw new MuxWireError('WINDOW credit must be a positive uint32')
      }
      const payload = Buffer.allocUnsafe(4)
      payload.writeUInt32BE(frame.credit, 0)
      return encodePayloadFrame(FRAME_WINDOW, frame.streamId, 0, payload)
    }
    case 'END':
      return encodePayloadFrame(FRAME_END, frame.streamId, 0, new Uint8Array(0))
    case 'COMPLETE':
      return encodePayloadFrame(FRAME_COMPLETE, frame.streamId, 0, new Uint8Array(0))
    case 'RESET':
      return encodePayloadFrame(FRAME_RESET, frame.streamId, 0, encodeResetPayload(frame.reset))
    case 'CLOSE': {
      if (typeof frame.close !== 'object' || frame.close === null) {
        throw new MuxWireError('CLOSE payload is required')
      }
      assertCloseCode(frame.close.code)
      if (typeof frame.close.reason !== 'string') {
        throw new MuxWireError('CLOSE reason must be a string')
      }
      assertWellFormed(frame.close.reason, 'CLOSE reason')
      const reason = Buffer.from(frame.close.reason, 'utf8')
      if (reason.byteLength > MUX_MAX_CLOSE_REASON_BYTES) {
        throw new MuxWireError(`CLOSE reason exceeds ${MUX_MAX_CLOSE_REASON_BYTES} UTF-8 bytes`)
      }
      const payload = Buffer.allocUnsafe(2 + reason.byteLength)
      payload.writeUInt16BE(frame.close.code, 0)
      payload.set(reason, 2)
      return encodePayloadFrame(FRAME_CLOSE, frame.streamId, 0, payload)
    }
    default:
      throw new MuxWireError(`unknown frame type ${String((frame as { type?: unknown }).type)}`)
  }
}

function copyPayload(data: Uint8Array, start: number, length: number): Uint8Array {
  return data.subarray(start, start + length)
}

/** Decodes exactly one physical frame. Trailing bytes, short payloads and unknown shapes fail closed. */
export function decodeFrame(data: Uint8Array): MuxFrame {
  if (!(data instanceof Uint8Array)) {
    throw new MuxWireError('frame must be a Uint8Array')
  }
  if (data.byteLength < MUX_FRAME_HEADER_BYTES) {
    throw new MuxWireError('frame is shorter than the 12-byte header')
  }
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength)
  if (view.getUint8(0) !== MUX_WIRE_VERSION) {
    throw new MuxWireError('unsupported frame version')
  }
  const type = view.getUint8(1)
  const flags = view.getUint16(2)
  const streamId = view.getUint32(4)
  const payloadLength = view.getUint32(8)
  if (payloadLength > MUX_MAX_PAYLOAD_BYTES) {
    throw new MuxWireError(`frame payload exceeds ${MUX_MAX_PAYLOAD_BYTES} bytes`)
  }
  if (data.byteLength !== MUX_FRAME_HEADER_BYTES + payloadLength) {
    throw new MuxWireError('frame payload length does not match the header')
  }
  const payload = copyPayload(data, MUX_FRAME_HEADER_BYTES, payloadLength)
  if (type !== FRAME_WINDOW) {
    assertStreamId(streamId, false)
  } else {
    assertStreamId(streamId, true)
  }
  if (type !== FRAME_DATA && flags !== 0) {
    throw new MuxWireError('non-DATA frame must have zero flags')
  }
  switch (type) {
    case FRAME_OPEN:
      return { type: 'OPEN', streamId, request: decodeOpenRequest(payload) }
    case FRAME_READY:
      assertEmptyPayload(payloadLength, 'READY')
      return { type: 'READY', streamId }
    case FRAME_HEADERS:
      return { type: 'HEADERS', streamId, headers: decodeHeaders(payload) }
    case FRAME_DATA:
      return {
        type: 'DATA',
        streamId,
        payload: payloadBytes(payload, 'DATA'),
        flags: decodeDataFlags(flags),
      }
    case FRAME_WINDOW: {
      if (payloadLength !== 4) {
        throw new MuxWireError('WINDOW payload must be exactly 4 bytes')
      }
      const credit = view.getUint32(MUX_FRAME_HEADER_BYTES)
      if (credit === 0) {
        throw new MuxWireError('WINDOW credit must be positive')
      }
      return { type: 'WINDOW', streamId, credit }
    }
    case FRAME_END:
      assertEmptyPayload(payloadLength, 'END')
      return { type: 'END', streamId }
    case FRAME_COMPLETE:
      assertEmptyPayload(payloadLength, 'COMPLETE')
      return { type: 'COMPLETE', streamId }
    case FRAME_RESET:
      return { type: 'RESET', streamId, reset: decodeReset(payload) }
    case FRAME_CLOSE: {
      if (payloadLength < 2) {
        throw new MuxWireError('CLOSE payload must carry a close code')
      }
      const reasonBytes = payload.subarray(2)
      if (reasonBytes.byteLength > MUX_MAX_CLOSE_REASON_BYTES) {
        throw new MuxWireError(`CLOSE reason exceeds ${MUX_MAX_CLOSE_REASON_BYTES} UTF-8 bytes`)
      }
      assertUtf8Bytes(reasonBytes, 'CLOSE reason')
      const code = view.getUint16(MUX_FRAME_HEADER_BYTES)
      assertCloseCode(code)
      return {
        type: 'CLOSE',
        streamId,
        close: { code, reason: UTF8_DECODER.decode(reasonBytes) },
      }
    }
    default:
      throw new MuxWireError(`unknown frame type ${String(type)}`)
  }
}

function assertEmptyPayload(payloadLength: number, what: string): void {
  if (payloadLength !== 0) {
    throw new MuxWireError(`${what} frame must have an empty payload`)
  }
}

/**
 * Allocates the next server stream id. Ids start at 1 and are strictly consecutive; id 0 and
 * u32 overflow are rejected so the caller must rebuild the data connection after draining.
 */
export function allocateStreamId(previous: number): number {
  if (!Number.isSafeInteger(previous) || previous < 0 || previous >= MUX_MAX_STREAM_ID) {
    throw new MuxWireError('stream id space exhausted')
  }
  return previous + 1
}
