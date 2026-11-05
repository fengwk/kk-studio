import { MAX_MESSAGE_BYTES } from './runtime.js'

const PREFIX_BYTES = 4

/** 控制消息字节长度必须落在 1..MAX_MESSAGE_BYTES；编码、解码与分帧共用同一判据。 */
export function requireMessageLength(length: number): void {
  if (length < 1 || length > MAX_MESSAGE_BYTES) {
    throw new Error(`control message length must be in 1..${MAX_MESSAGE_BYTES} bytes`)
  }
}

/** Wraps one non-empty control message in a four-byte big-endian length prefix. */
export function frame(message: Uint8Array): Uint8Array {
  requireMessageLength(message.length)
  const framed = new Uint8Array(PREFIX_BYTES + message.length)
  framed[0] = (message.length >>> 24) & 0xff
  framed[1] = (message.length >>> 16) & 0xff
  framed[2] = (message.length >>> 8) & 0xff
  framed[3] = message.length & 0xff
  framed.set(message, PREFIX_BYTES)
  return framed
}

/** Reads exactly one frame; truncated, oversized and trailing bytes are rejected. */
export function unframe(framed: Uint8Array): Uint8Array {
  if (framed.length < PREFIX_BYTES) {
    throw new Error('control frame must contain a 4-byte length prefix')
  }
  const length =
    (((framed[0]! << 24) | (framed[1]! << 16) | (framed[2]! << 8) | framed[3]!) >>> 0)
  requireMessageLength(length)
  if (framed.length !== PREFIX_BYTES + length) {
    throw new Error('control frame length does not match its prefix')
  }
  return framed.slice(PREFIX_BYTES)
}
