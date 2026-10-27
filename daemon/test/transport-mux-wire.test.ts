import { Buffer } from 'node:buffer'

import { describe, expect, it } from 'vitest'

import {
  FRAME_CLOSE,
  FRAME_COMPLETE,
  FRAME_DATA,
  FRAME_END,
  FRAME_HEADERS,
  FRAME_OPEN,
  FRAME_READY,
  FRAME_RESET,
  FRAME_WINDOW,
  MUX_FRAME_HEADER_BYTES,
  MUX_MAX_BINDING_EPOCH,
  MUX_MAX_METADATA_BYTES,
  MUX_MAX_PAYLOAD_BYTES,
  MUX_MAX_STREAM_ID,
  MUX_WIRE_VERSION,
  MuxWireError,
  allocateStreamId,
  decodeDataFlags,
  decodeFrame,
  encodeFrame,
  frameCost,
  type MuxFrame,
  type OpenRequest,
} from '../src/transport/mux-wire.js'

const request: OpenRequest = {
  viewId: '01234567-89ab-4def-8123-456789abcdef',
  bindingEpoch: '9223372036854775807',
  mode: 'HTTP',
  method: 'GET',
  path: '/vscode-remote-resource?path=%2Ftmp%2Fa',
  headers: [
    ['accept', 'text/html'],
    ['cookie', 'a=1'],
    ['cookie', 'b=2'],
  ],
}

function rawFrame(
  type: number,
  streamId: number,
  payload: Uint8Array = new Uint8Array(0),
  options: { flags?: number; version?: number; claimedLength?: number } = {},
): Buffer {
  const frame = Buffer.alloc(MUX_FRAME_HEADER_BYTES + payload.byteLength)
  frame[0] = options.version ?? MUX_WIRE_VERSION
  frame[1] = type
  frame.writeUInt16BE(options.flags ?? 0, 2)
  frame.writeUInt32BE(streamId, 4)
  frame.writeUInt32BE(options.claimedLength ?? payload.byteLength, 8)
  frame.set(payload, MUX_FRAME_HEADER_BYTES)
  return frame
}

function metadata(value: unknown): Uint8Array {
  return Buffer.from(JSON.stringify(value), 'utf8')
}

function encode(value: MuxFrame): Buffer {
  return Buffer.from(encodeFrame(value))
}

describe('mux v2 frame codec', () => {
  it('round-trips every frame type and preserves WS fragment flags', () => {
    const frames: MuxFrame[] = [
      { type: 'OPEN', streamId: 1, request },
      { type: 'READY', streamId: 1 },
      { type: 'HEADERS', streamId: 1, headers: { status: 200, headers: [['content-type', 'text/plain']] } },
      { type: 'DATA', streamId: 1, payload: Buffer.from('hello'), flags: { kind: 'http' } },
      { type: 'DATA', streamId: 1, payload: Buffer.from('text'), flags: { kind: 'ws', text: true, fin: false } },
      { type: 'DATA', streamId: 1, payload: Buffer.from('bin'), flags: { kind: 'ws', text: false, fin: true } },
      { type: 'WINDOW', streamId: 0, credit: 0x01020304 },
      { type: 'WINDOW', streamId: 1, credit: 1 },
      { type: 'END', streamId: 1 },
      { type: 'COMPLETE', streamId: 1 },
      { type: 'RESET', streamId: 1, reset: { code: 'UPSTREAM_UNAVAILABLE', message: 'upstream unavailable' } },
      { type: 'CLOSE', streamId: 1, close: { code: 1000, reason: 'done' } },
    ]
    for (const frame of frames) {
      expect(decodeFrame(encode(frame))).toEqual(frame)
    }
  })

  it('writes the fixed 12-byte network-order header', () => {
    const frame = encode({ type: 'WINDOW', streamId: 0, credit: 0x01020304 })
    expect([...frame]).toEqual([2, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 1, 2, 3, 4])
  })

  it('serializes OPEN and HEADERS with the exact fixed key sets', () => {
    const open = encode({ type: 'OPEN', streamId: 1, request })
    expect(JSON.parse(open.subarray(MUX_FRAME_HEADER_BYTES).toString('utf8'))).toEqual({
      viewId: request.viewId,
      bindingEpoch: request.bindingEpoch,
      mode: 'HTTP',
      method: 'GET',
      path: request.path,
      headers: request.headers,
    })
    const headers = encode({ type: 'HEADERS', streamId: 1, headers: { status: 101, headers: [] } })
    expect(JSON.parse(headers.subarray(MUX_FRAME_HEADER_BYTES).toString('utf8'))).toEqual({
      status: 101,
      headers: [],
    })
  })

  it('accepts the documented boundary values', () => {
    expect(decodeFrame(encode({ type: 'OPEN', streamId: MUX_MAX_STREAM_ID, request })).type).toBe('OPEN')
    expect(decodeFrame(encode({ type: 'DATA', streamId: 1, payload: Buffer.alloc(MUX_MAX_PAYLOAD_BYTES), flags: { kind: 'http' } })).type).toBe(
      'DATA',
    )
    expect(decodeFrame(encode({ type: 'RESET', streamId: 1, reset: { code: 'A', message: '' } })).type).toBe('RESET')
    expect(decodeFrame(encode({ type: 'CLOSE', streamId: 1, close: { code: 4999, reason: 'x'.repeat(123) } })).type).toBe('CLOSE')
    expect(request.bindingEpoch).toBe(MUX_MAX_BINDING_EPOCH.toString())
  })

  it('rejects malformed headers, lengths and flags', () => {
    expect(() => decodeFrame(Buffer.alloc(11))).toThrow(/12-byte header/)
    expect(() => decodeFrame(rawFrame(FRAME_READY, 1, undefined, { version: 3 }))).toThrow(/version/)
    expect(() => decodeFrame(rawFrame(FRAME_READY, 1, undefined, { claimedLength: 1 }))).toThrow(/does not match/)
    expect(() => decodeFrame(rawFrame(FRAME_READY, 1, Buffer.alloc(1)))).toThrow(/empty payload/)
    expect(() => decodeFrame(rawFrame(FRAME_READY, 1, undefined, { claimedLength: MUX_MAX_PAYLOAD_BYTES + 1 }))).toThrow(
      /payload exceeds/,
    )
    expect(() => decodeFrame(rawFrame(99, 1))).toThrow(/unknown frame type/)
    expect(() => decodeFrame(rawFrame(FRAME_READY, 1, undefined, { flags: 1 }))).toThrow(/zero flags/)
    expect(() => decodeFrame(rawFrame(FRAME_DATA, 0))).toThrow(/stream id/)
    expect(() => decodeFrame(rawFrame(FRAME_DATA, 1, undefined, { flags: 4 }))).toThrow(/DATA flags/)
    expect(() => decodeFrame(rawFrame(FRAME_DATA, 1, undefined, { flags: 3 }))).toThrow(/DATA flags/)
    expect(() => decodeFrame(rawFrame(FRAME_DATA, 1, undefined, { flags: 8 }))).toThrow(/DATA flags/)
    expect(decodeDataFlags(0)).toEqual({ kind: 'http' })
    expect(decodeDataFlags(1)).toEqual({ kind: 'ws', text: true, fin: false })
    expect(decodeDataFlags(6)).toEqual({ kind: 'ws', text: false, fin: true })
    expect(() => decodeDataFlags(-1)).toThrow(MuxWireError)
    expect(() => decodeDataFlags(0x10000)).toThrow(MuxWireError)
  })

  it('enforces payload-free control frames and positive WINDOW values', () => {
    expect(() => decodeFrame(rawFrame(FRAME_READY, 1, Buffer.from([1])))).toThrow(/READY/)
    expect(() => decodeFrame(rawFrame(FRAME_END, 1, Buffer.from([1])))).toThrow(/END/)
    expect(() => decodeFrame(rawFrame(FRAME_COMPLETE, 1, Buffer.from([1])))).toThrow(/COMPLETE/)
    expect(() => decodeFrame(rawFrame(FRAME_WINDOW, 0, Buffer.alloc(3)))).toThrow(/exactly 4/)
    expect(() => decodeFrame(rawFrame(FRAME_WINDOW, 0, Buffer.alloc(4)))).toThrow(/positive/)
    const window = Buffer.alloc(4)
    window.writeUInt32BE(1, 0)
    expect(decodeFrame(rawFrame(FRAME_WINDOW, 0, window))).toEqual({ type: 'WINDOW', streamId: 0, credit: 1 })
    expect(() => encodeFrame({ type: 'WINDOW', streamId: 0, credit: 0 })).toThrow(/positive uint32/)
    expect(() => encodeFrame({ type: 'WINDOW', streamId: 0, credit: 0x1_0000_0000 })).toThrow(/positive uint32/)
  })

  it('validates WS close codes, reason bytes and UTF-8', () => {
    for (const code of [1000, 1003, 1007, 1014, 3000, 4999]) {
      expect(decodeFrame(encode({ type: 'CLOSE', streamId: 1, close: { code, reason: '' } })).type).toBe('CLOSE')
    }
    for (const code of [999, 1004, 1005, 1006, 1015, 1016, 2999, 5000]) {
      expect(() => encodeFrame({ type: 'CLOSE', streamId: 1, close: { code, reason: '' } })).toThrow(/close code/)
    }
    expect(() => decodeFrame(rawFrame(FRAME_CLOSE, 1, Buffer.from([0])))).toThrow(/close code/)
    const invalidUtf8 = Buffer.from([0x03, 0xe8, 0xff])
    expect(() => decodeFrame(rawFrame(FRAME_CLOSE, 1, invalidUtf8))).toThrow(/UTF-8/)
    const oversizedReason = Buffer.concat([Buffer.from([0x03, 0xe8]), Buffer.alloc(124, 0x61)])
    expect(() => decodeFrame(rawFrame(FRAME_CLOSE, 1, oversizedReason))).toThrow(/reason exceeds/)
    expect(() => encodeFrame({ type: 'CLOSE', streamId: 1, close: { code: 1000, reason: 'x'.repeat(124) } })).toThrow(
      /reason exceeds/,
    )
    expect(() =>
      encodeFrame({ type: 'CLOSE', streamId: 1, close: { code: 1000, reason: '\ud800' } }),
    ).toThrow(/unpaired/)
  })
})

describe('mux metadata validation', () => {
  it('rejects invalid UTF-8, non-objects and oversized metadata', () => {
    expect(() => decodeFrame(rawFrame(FRAME_OPEN, 1, Buffer.from([0xff])))).toThrow(/UTF-8/)
    expect(() => decodeFrame(rawFrame(FRAME_OPEN, 1, Buffer.from('[]')))).toThrow(/JSON object/)
    expect(() => decodeFrame(rawFrame(FRAME_OPEN, 1, Buffer.alloc(MUX_MAX_METADATA_BYTES + 1, 0x20)))).toThrow(/metadata exceeds/)
    expect(() =>
      encodeFrame({
        type: 'RESET',
        streamId: 1,
        reset: { code: 'A', message: 'x' },
      }),
    ).not.toThrow()
    expect(() =>
      encodeFrame({
        type: 'OPEN',
        streamId: 1,
        request: { ...request, headers: [['x', 'y'.repeat(MUX_MAX_METADATA_BYTES)]] },
      }),
    ).toThrow(/metadata exceeds/)
  })

  it('detects duplicate, unknown and missing keys and malformed JSON', () => {
    const duplicate = Buffer.from('{"code":"A","code":"B","message":"x"}')
    expect(() => decodeFrame(rawFrame(FRAME_RESET, 1, duplicate))).toThrow(/duplicate field/)
    const escapedDuplicate = Buffer.from('{"code":"A","\\u0063ode":"B","message":"x"}')
    expect(() => decodeFrame(rawFrame(FRAME_RESET, 1, escapedDuplicate))).toThrow(/duplicate field/)
    expect(() => decodeFrame(rawFrame(FRAME_RESET, 1, metadata({ code: 'A', message: 'x', extra: 1 })))).toThrow(
      /unknown field/,
    )
    expect(() => decodeFrame(rawFrame(FRAME_RESET, 1, metadata({ code: 'A' })))).toThrow(/missing field/)
    expect(() => decodeFrame(rawFrame(FRAME_RESET, 1, Buffer.from('"x"')))).toThrow(/JSON object/)
    for (const malformed of [
      '{"code":}',
      '{"code":"A" "message":"x"}',
      '{"code" "A","message":"x"}',
      '{"code":"A","message":[1 2]}',
      '{"code":"A","message":"\\q"}',
      '{"code":"A","message":"\\u12"}',
      '{"code":"A","message":"\n"}',
      '{"code":"A","message":[1,]}',
      '{"code":"A","message":tru}',
    ]) {
      expect(() => decodeFrame(rawFrame(FRAME_RESET, 1, Buffer.from(malformed)))).toThrow(/valid JSON/)
    }
    expect(() => decodeFrame(rawFrame(FRAME_RESET, 1, Buffer.from('{"code":"A","message":1e999}')))).toThrow(/non-finite/)
    expect(() =>
      decodeFrame(rawFrame(FRAME_RESET, 1, Buffer.from(`{"code":"A","message":${'1'.repeat(65)}}`))),
    ).toThrow(/number token exceeds/)
    expect(() => decodeFrame(rawFrame(FRAME_OPEN, 1, Buffer.from('['.repeat(40))))).toThrow(/nesting depth/)
  })

  it('validates every OPEN field and its bounds', () => {
    const cases: [Partial<OpenRequest>, RegExp][] = [
      [{ viewId: 'not-a-uuid' }, /viewId/],
      [{ bindingEpoch: '0' }, /bindingEpoch/],
      [{ bindingEpoch: '01' }, /bindingEpoch/],
      [{ bindingEpoch: '9223372036854775808' }, /Java long/],
      [{ mode: 'TCP' as never }, /mode/],
      [{ method: 'GE T' }, /method/],
      [{ method: 'G'.repeat(33) }, /method/],
      [{ path: 'relative' }, /path/],
      [{ path: `/${'x'.repeat(8192)}` }, /path exceeds/],
      [{ path: '/a\rb' }, /CR or LF/],
      [{ headers: Array.from({ length: 101 }, () => ['a', 'b'] as [string, string]) }, /headers exceeds/],
      [{ headers: [['bad name', 'v']] }, /header/],
      [{ headers: [['a', 'x\r\ny']] }, /prohibited/],
      [{ headers: [['a', 'x\u0000y']] }, /prohibited/],
    ]
    for (const [patch, expected] of cases) {
      expect(() => encodeFrame({ type: 'OPEN', streamId: 1, request: { ...request, ...patch } })).toThrow(expected)
    }
    expect(() => encodeFrame({ type: 'OPEN', streamId: 1, request: { ...request, path: '/\ud800' } })).toThrow(/unpaired/)
    expect(() => encodeFrame({ type: 'OPEN', streamId: 1, request: { ...request, headers: [['a']] as never } })).toThrow(
      /name\/value/,
    )
    expect(() => encodeFrame({ type: 'OPEN', streamId: 1, request: null as never })).toThrow(/required/)
  })

  it('rejects malformed HEADERS and RESET values', () => {
    expect(() => decodeFrame(rawFrame(FRAME_HEADERS, 1, metadata({ status: 99, headers: [] })))).toThrow(/status/)
    expect(() => decodeFrame(rawFrame(FRAME_HEADERS, 1, metadata({ status: 600, headers: [] })))).toThrow(/status/)
    expect(() => decodeFrame(rawFrame(FRAME_HEADERS, 1, metadata({ status: 200 })))).toThrow(/missing field/)
    expect(() => decodeFrame(rawFrame(FRAME_HEADERS, 1, metadata({ status: 200, headers: [['bad name', 'v']] })))).toThrow(
      /header/,
    )
    expect(() => encodeFrame({ type: 'HEADERS', streamId: 1, headers: { status: 99, headers: [] } })).toThrow(/status/)
    expect(() => encodeFrame({ type: 'HEADERS', streamId: 1, headers: null as never })).toThrow(/required/)

    expect(() => decodeFrame(rawFrame(FRAME_RESET, 1, metadata({ code: 'lower', message: 'x' })))).toThrow(/RESET code/)
    expect(() => decodeFrame(rawFrame(FRAME_RESET, 1, metadata({ code: 'A'.repeat(65), message: 'x' })))).toThrow(
      /RESET code/,
    )
    expect(() => decodeFrame(rawFrame(FRAME_RESET, 1, metadata({ code: 'A', message: 'x'.repeat(501) })))).toThrow(
      /message exceeds/,
    )
    expect(() => decodeFrame(rawFrame(FRAME_RESET, 1, metadata({ code: 'A', message: 'a\u0000b' })))).toThrow(
      /control character/,
    )
    expect(() => encodeFrame({ type: 'RESET', streamId: 1, reset: { code: 'lower', message: 'x' } })).toThrow(/RESET code/)
    expect(() => encodeFrame({ type: 'RESET', streamId: 1, reset: { code: 'A', message: 'x'.repeat(501) } })).toThrow(
      /message exceeds/,
    )
    expect(() => encodeFrame({ type: 'RESET', streamId: 1, reset: { code: 'A', message: '\ud800' } })).toThrow(/unpaired/)
    expect(() => encodeFrame({ type: 'RESET', streamId: 1, reset: null as never })).toThrow(/required/)
  })

  it('rejects invalid encoded frame shapes', () => {
    expect(() => encodeFrame(null as never)).toThrow(/required/)
    expect(() => encodeFrame({ type: 'UNKNOWN' } as never)).toThrow(/unknown frame type/)
    expect(() => encodeFrame({ type: 'READY', streamId: 0 })).toThrow(/stream id/)
    expect(() => encodeFrame({ type: 'DATA', streamId: 1, payload: Buffer.alloc(MUX_MAX_PAYLOAD_BYTES + 1), flags: { kind: 'http' } })).toThrow(
      /payload/,
    )
    expect(() => encodeFrame({ type: 'DATA', streamId: 1, payload: 'x' as never, flags: { kind: 'http' } })).toThrow(
      /Uint8Array/,
    )
    expect(() => encodeFrame({ type: 'DATA', streamId: 1, payload: Buffer.alloc(0), flags: null as never })).toThrow(
      /flags/,
    )
    expect(() =>
      encodeFrame({ type: 'DATA', streamId: 1, payload: Buffer.alloc(0), flags: { kind: 'ws', text: 'yes' } as never }),
    ).toThrow(/flags/)
    expect(frameCost(0)).toBe(512)
    expect(frameCost(513)).toBe(513)
    expect(() => frameCost(-1)).toThrow(/payload length/)
  })

  it('allocates consecutive stream ids and rejects exhaustion', () => {
    expect(allocateStreamId(0)).toBe(1)
    expect(allocateStreamId(41)).toBe(42)
    expect(allocateStreamId(MUX_MAX_STREAM_ID - 1)).toBe(MUX_MAX_STREAM_ID)
    expect(() => allocateStreamId(MUX_MAX_STREAM_ID)).toThrow(/exhausted/)
    expect(() => allocateStreamId(-1)).toThrow(/exhausted/)
  })
})

describe('strict metadata parser completeness', () => {
  it('decodes booleans, nulls, empty containers and escaped strings, then rejects invalid fields', () => {
    const base = metadata({ code: 'A', message: 'x' })
    const json = (value: string): Uint8Array => Buffer.from(value, 'utf8')
    for (const payload of [
      json('{"code":"A","message":false}'),
      json('{"code":"A","message":null}'),
      json('{"code":"A","message":[]}'),
      json('{"code":"A","message":{}}'),
      json('{"code":"A","message":"\\"\\\\\\/\\b\\f\\n\\r\\t"}'),
      json('{"code":"A","message":"unterminated}'),
      json('{"code":"A","message":-}'),
      json('{"code":"A","message":1 2}'),
      json('{"code":"A" "message":"x"}'),
      json('{"code":"A","message":"x"}trailing'),
    ]) {
      expect(() => decodeFrame(rawFrame(FRAME_RESET, 1, payload))).toThrow(MuxWireError)
    }
    expect(base.byteLength).toBeGreaterThan(0)
    expect(() => decodeFrame(rawFrame(FRAME_RESET, 1, json('{1:"x"}')))).toThrow(MuxWireError)
    expect(() => decodeFrame(rawFrame(FRAME_OPEN, 1, json('{}')))).toThrow(/missing field/)
  })

  it('validates OPEN and HEADERS fields on the decode path', () => {
    const cases: [Record<string, unknown>, RegExp][] = [
      [{ viewId: 'not-uuid' }, /viewId/],
      [{ bindingEpoch: '0' }, /bindingEpoch/],
      [{ bindingEpoch: '9223372036854775808' }, /Java long/],
      [{ mode: 'TCP' }, /mode/],
      [{ path: 'relative' }, /path/],
      [{ path: `/${'x'.repeat(8192)}` }, /path exceeds/],
      [{ path: '/a\rb' }, /CR or LF/],
      [{ headers: Array.from({ length: 101 }, () => ['a', 'b']) }, /headers exceeds/],
      [{ headers: [['a']] }, /name\/value/],
      [{ headers: [['bad name', 'v']] }, /header/],
      [{ headers: [['a', 'x\r\ny']] }, /prohibited/],
    ]
    for (const [patch, expected] of cases) {
      expect(() => decodeFrame(rawFrame(FRAME_OPEN, 1, metadata({ ...request, ...patch })))).toThrow(expected)
    }
    expect(() => decodeFrame(rawFrame(FRAME_HEADERS, 1, metadata({ status: '200', headers: [] })))).toThrow(/integer/)
    expect(() => decodeFrame(rawFrame(FRAME_HEADERS, 1, metadata({ status: 200, headers: {} })))).toThrow(/array/)
    expect(decodeFrame(encode({ type: 'OPEN', streamId: 1, request: { ...request, path: '/😀' } })).type).toBe('OPEN')
  })

  it('covers remaining encode argument checks', () => {
    expect(() => encodeFrame({ type: 'OPEN', streamId: 1, request: { ...request, viewId: 1 as never } })).toThrow(/viewId/)
    expect(() => encodeFrame({ type: 'OPEN', streamId: 1, request: { ...request, method: 1 as never } })).toThrow(/method/)
    expect(() => encodeFrame({ type: 'OPEN', streamId: 1, request: { ...request, headers: null as never } })).toThrow(
      /array/,
    )
    expect(() =>
      encodeFrame({ type: 'OPEN', streamId: 1, request: { ...request, headers: [['a', 1]] as never } }),
    ).toThrow(/name\/value/)
    expect(() => encodeFrame({ type: 'RESET', streamId: 1, reset: { code: 'A', message: 1 as never } })).toThrow(
      /string/,
    )
    expect(() => encodeFrame({ type: 'RESET', streamId: 1, reset: { code: 'A', message: '\n' } })).toThrow(
      /control character/,
    )
    expect(() => encodeFrame({ type: 'CLOSE', streamId: 1, close: null as never })).toThrow(/required/)
    expect(() => encodeFrame({ type: 'CLOSE', streamId: 1, close: { code: 1000, reason: 1 as never } })).toThrow(
      /string/,
    )
    expect(() => decodeFrame('nope' as never)).toThrow(/Uint8Array/)
    const lowSurrogate = Buffer.from('{"code":"A","message":"\\udc00"}', 'utf8')
    expect(() => decodeFrame(rawFrame(FRAME_RESET, 1, lowSurrogate))).toThrow(/valid Unicode/)
  })
})
