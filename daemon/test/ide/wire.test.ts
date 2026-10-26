import { describe, expect, it } from 'vitest'
import {
  CreditWindow,
  INITIAL_CREDIT,
  MAX_IN_FLIGHT_FRAMES,
  MAX_PAYLOAD,
  MIN_FRAME_CREDIT,
  WireError,
  decodeBinaryFrame,
  decodeTextFrame,
  encodeAck,
  encodeHttpBody,
  encodeHttpEnd,
  encodeTextFrame,
  encodeWsFrame,
  filterResponseHeaders,
  frameCost,
  isHopByHopHeader,
  validateIdePath,
} from '../../src/ide/wire.js'

describe('binary frame codec', () => {
  it('round-trips every frame type', () => {
    const body = encodeHttpBody(Buffer.from('hello'))
    expect(decodeBinaryFrame(body)).toEqual({ kind: 'http-body', payload: Buffer.from('hello') })
    expect(decodeBinaryFrame(encodeHttpEnd())).toEqual({ kind: 'http-end' })
    expect(decodeBinaryFrame(encodeAck(0x01020304))).toEqual({ kind: 'ack', bytes: 0x01020304 })
    expect(decodeBinaryFrame(encodeWsFrame('text', Buffer.from('t'), true))).toEqual({
      kind: 'ws-text',
      last: true,
      payload: Buffer.from('t'),
    })
    expect(decodeBinaryFrame(encodeWsFrame('binary', Buffer.from('b'), false))).toEqual({
      kind: 'ws-binary',
      last: false,
      payload: Buffer.from('b'),
    })
  })

  it('encodes ACK as unsigned big-endian uint32', () => {
    expect([...encodeAck(0x01020304)]).toEqual([3, 1, 2, 3, 4])
  })

  it('fails closed on unknown, malformed and oversized frames', () => {
    expect(() => decodeBinaryFrame(Buffer.alloc(0))).toThrow(WireError)
    expect(() => decodeBinaryFrame(Buffer.from([9]))).toThrow(/unknown binary frame type/)
    expect(() => decodeBinaryFrame(Buffer.from([2, 1]))).toThrow(/no payload/)
    expect(() => decodeBinaryFrame(Buffer.from([3, 1, 2]))).toThrow(/exactly 5 bytes/)
    expect(() => decodeBinaryFrame(Buffer.from([4, 2, 1]))).toThrow(/final flag/)
    expect(() => decodeBinaryFrame(Buffer.from([4]))).toThrow(/missing the final flag/)
    expect(() => encodeHttpBody(Buffer.alloc(MAX_PAYLOAD + 1))).toThrow(/payload limit/)
    expect(() => encodeAck(-1)).toThrow(WireError)
    expect(() => encodeAck(2 ** 33)).toThrow(WireError)
  })
})

describe('text frame codec', () => {
  it('round-trips control frames and preserves multi-value headers', () => {
    const frame = {
      type: 'HTTP_RESPONSE' as const,
      status: 200,
      headers: [
        ['content-type', 'text/html'],
        ['set-cookie', 'a=1'],
        ['set-cookie', 'b=2'],
      ] as [string, string][],
    }
    expect(decodeTextFrame(encodeTextFrame(frame))).toEqual(frame)
    expect(decodeTextFrame(encodeTextFrame({ type: 'WS_READY' }))).toEqual({ type: 'WS_READY' })
    expect(decodeTextFrame(encodeTextFrame({ type: 'ERROR', message: 'nope' }))).toEqual({ type: 'ERROR', message: 'nope' })
  })

  it('rejects unknown types, fields and invalid values', () => {
    expect(() => decodeTextFrame('not json')).toThrow(/not valid JSON/)
    expect(() => decodeTextFrame('[]')).toThrow(/JSON object/)
    expect(() => decodeTextFrame('{"type":"NOPE"}')).toThrow(/unknown text frame type/)
    expect(() => decodeTextFrame('{"type":"HTTP_RESPONSE","status":99,"headers":[]}')).toThrow(/status/)
    expect(() => decodeTextFrame('{"type":"HTTP_RESPONSE","status":200,"headers":[["a"]]}')).toThrow(/name\/value/)
    expect(() => decodeTextFrame('{"type":"HTTP_RESPONSE","status":200,"headers":[],"extra":1}')).toThrow(/unexpected/)
    expect(() => decodeTextFrame('{"type":"ERROR"}')).toThrow(/bounded message/)
    expect(() => decodeTextFrame(JSON.stringify({ type: 'ERROR', message: 'x'.repeat(2000) }))).toThrow(WireError)
  })
})

describe('credit window', () => {
  it('charges every data frame at least the minimum credit', () => {
    // Small and empty frames cost MIN_FRAME_CREDIT, so one window bounds frames in flight as well as bytes.
    expect(frameCost(0)).toBe(MIN_FRAME_CREDIT)
    expect(frameCost(1)).toBe(MIN_FRAME_CREDIT)
    expect(frameCost(MIN_FRAME_CREDIT + 1)).toBe(MIN_FRAME_CREDIT + 1)
    expect(MAX_IN_FLIGHT_FRAMES).toBe(INITIAL_CREDIT / MIN_FRAME_CREDIT)
    const window = new CreditWindow()
    for (let index = 0; index < MAX_IN_FLIGHT_FRAMES; index += 1) {
      expect(window.reserveFrame(index % 2)).toBe(MIN_FRAME_CREDIT)
    }
    expect(window.available).toBe(0)
    expect(() => window.reserveFrame(0)).toThrow(/exceeded/)
  })

  it('tracks outstanding credit and rejects overrun and invalid acknowledgements', () => {
    const window = new CreditWindow(2 * MIN_FRAME_CREDIT)
    expect(window.reserveFrame(600)).toBe(600)
    expect(window.available).toBe(2 * MIN_FRAME_CREDIT - 600)
    expect(() => window.reserveFrame(1)).toThrow(/exceeded/)
    expect(() => window.ack(MIN_FRAME_CREDIT - 1)).toThrow(/at least one frame/)
    expect(() => window.ack(601)).toThrow(/exceeds unacknowledged/)
    expect(() => window.ack(-1)).toThrow(WireError)
    window.ack(600)
    expect(window.available).toBe(2 * MIN_FRAME_CREDIT)
    expect(() => new CreditWindow(MIN_FRAME_CREDIT - 1)).toThrow(/invalid credit window size/)
  })

  it('wakes blocked senders after acknowledgement', async () => {
    const window = new CreditWindow(MIN_FRAME_CREDIT)
    window.reserveFrame(0)
    let released = false
    const waiting = window.waitForFrame(0).then(() => {
      released = true
    })
    await new Promise((resolve) => setTimeout(resolve, 5))
    expect(released).toBe(false)
    window.ack(MIN_FRAME_CREDIT)
    await waiting
    expect(released).toBe(true)
  })
})

describe('header helpers', () => {
  it('filters hop-by-hop response headers but keeps duplicates', () => {
    expect(isHopByHopHeader('Transfer-Encoding')).toBe(true)
    const filtered = filterResponseHeaders([
      'Content-Type', 'text/plain',
      'Connection', 'keep-alive',
      'Set-Cookie', 'a=1',
      'Set-Cookie', 'b=2',
    ])
    expect(filtered).toEqual([
      ['Content-Type', 'text/plain'],
      ['Set-Cookie', 'a=1'],
      ['Set-Cookie', 'b=2'],
    ])
  })

  it('rejects unsafe IDE paths', () => {
    expect(validateIdePath('/vscode-remote-resource?path=x')).toBe('/vscode-remote-resource?path=x')
    expect(() => validateIdePath('http://evil')).toThrow(/absolute/)
    expect(() => validateIdePath('//evil')).toThrow(/network-relative/)
    expect(() => validateIdePath('/a\u0000b')).toThrow(/control/)
    expect(() => validateIdePath('/a b')).toThrow(/illegal/)
    expect(() => validateIdePath('/a\\b')).toThrow(/illegal/)
  })
})
