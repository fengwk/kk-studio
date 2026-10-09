/**
 * Canonical notification carrier/reassembler/outbox contract for the browser and Node probe. The
 * fixture tests read the exact same Java-generated JSON the share module verifies, so the two
 * runtimes are held to one wire format instead of two hand-written variants.
 */

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  CHUNK_BYTES,
  DEFAULT_MAX_MESSAGE_BYTES,
  NotificationCarrier,
  NotificationOutbox,
  NotificationPacket,
  NotificationReassembler,
  carrierChunk,
  carrierCount,
  createNotificationLimits,
  decodeNotificationCarrier,
  defaultNotificationLimits,
  type NotificationLimits,
} from '../notification.mjs'

const TOPIC = 'fixture.events'
const SELF = '5f0e2c1a-1111-4111-8111-000000000001'
const PUBLISHER = 'a1b2c3d4-2222-4222-8222-000000000002'
const FOREIGN = 'c1d2e3f4-4444-4444-8444-000000000004'
const MESSAGE_ID = 'b1c2d3e4-3333-4333-8333-000000000003'
const MESSAGE_ID_ALT = 'b1c2d3e4-3333-4333-8333-00000000000a'
const RANDOM_ID = 'b1c2d3e4-3333-4333-8333-00000000000b'
const JAVA_INT_MAX = 2147483647

interface RoundTripCase {
  name: string
  publisher: string
  target: string | null
  topic: string
  messageId: string
  payloadBase64: string
  frames: string[]
}

interface RejectionCase {
  name: string
  frame: string
}

interface OwnEchoCase {
  name: string
  self: string
  frame: string
}

interface InteropFixture {
  self: string
  topic: string
  roundTrips: RoundTripCase[]
  rejections: RejectionCase[]
  ownEchoes: OwnEchoCase[]
}

const fixture: InteropFixture = JSON.parse(
  fs.readFileSync(
    path.resolve(
      process.cwd(),
      '..',
      'share/src/test/resources/fun/fengwk/kkstudio/share/notification/notification-interop.json',
    ),
    'utf8',
  ),
)

const FIXED_ERRORS = [
  'oversized notification carrier',
  'invalid notification carrier header',
  'invalid notification carrier identity',
  'invalid notification carrier dimension',
  'invalid notification carrier dimensions',
  'invalid notification carrier chunk',
]

function utf8(value: string): Uint8Array {
  return new TextEncoder().encode(value)
}

function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) {
    return false
  }
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) {
      return false
    }
  }
  return true
}

function packet(
  publisher: string,
  target: string | null,
  body: string,
  messageId = MESSAGE_ID,
): NotificationPacket {
  return new NotificationPacket(publisher, target, TOPIC, messageId, utf8(body))
}

function tinyLimits(overrides: Partial<NotificationLimits> = {}): NotificationLimits {
  return createNotificationLimits({
    maxMessageBytes: 20000,
    pendingBytes: 40000,
    queueCapacity: 8,
    reassemblyBytes: 40000,
    reassemblyMessages: 2,
    reassemblyTimeoutMs: 5000,
    sendBatchFrames: 1,
    ...overrides,
  })
}

function outboxLimits(
  maxMessageBytes: number,
  pendingBytes: number,
  queueCapacity: number,
  sendBatchFrames: number,
): NotificationLimits {
  return createNotificationLimits({
    maxMessageBytes,
    pendingBytes,
    queueCapacity,
    reassemblyBytes: Math.max(maxMessageBytes, pendingBytes),
    reassemblyMessages: 4,
    reassemblyTimeoutMs: 5000,
    sendBatchFrames,
  })
}

function collector() {
  const delivered: NotificationPacket[] = []
  const resyncs: string[] = []
  return { delivered, resyncs }
}

/**
 * A real `Uint8Array` subclass whose iterator is spied. The implementation must copy typed arrays
 * through the native typed-array path, never through the iterator protocol, so the spy stays at 0.
 */
function spyBytes(length: number) {
  const calls = { count: 0 }
  class Spy extends Uint8Array {}
  Object.defineProperty(Spy.prototype, Symbol.iterator, {
    configurable: true,
    value: function* iterate() {
      calls.count += 1
      yield 0
    },
  })
  return { bytes: new Spy(length) as Uint8Array, calls }
}

describe('notification carrier interop fixture', () => {
  it('encodes and reassembles every shared Java fixture case', () => {
    for (const testCase of fixture.roundTrips) {
      const payload = Uint8Array.from(Buffer.from(testCase.payloadBase64, 'base64'))
      const message = new NotificationPacket(
        testCase.publisher,
        testCase.target,
        testCase.topic,
        testCase.messageId,
        payload,
      )
      expect(carrierCount(payload.length), testCase.name).toBe(testCase.frames.length)
      testCase.frames.forEach((frame, index) => {
        expect(carrierChunk(message, index).encode(), testCase.name).toBe(frame)
      })

      const { delivered, resyncs } = collector()
      const reassembler = new NotificationReassembler(
        fixture.self,
        defaultNotificationLimits(),
        null,
        (value) => delivered.push(value),
        (topic) => resyncs.push(topic),
      )
      for (const frame of testCase.frames) {
        reassembler.accept(
          decodeNotificationCarrier(frame, defaultNotificationLimits(), fixture.self),
        )
      }
      expect(delivered, testCase.name).toHaveLength(1)
      expect(bytesEqual(delivered[0].bytes(), payload), testCase.name).toBe(true)
      expect(delivered[0].messageId(), testCase.name).toBe(testCase.messageId)
      expect(delivered[0].topic(), testCase.name).toBe(testCase.topic)
      expect(delivered[0].target(), testCase.name).toBe(testCase.target)
      expect(resyncs, testCase.name).toHaveLength(0)
    }
  })

  it('rejects every malformed fixture frame with a fixed, payload-free error', () => {
    for (const testCase of fixture.rejections) {
      let error: unknown
      try {
        decodeNotificationCarrier(testCase.frame, defaultNotificationLimits(), fixture.self)
      } catch (caught) {
        error = caught
      }
      expect(error, testCase.name).toBeInstanceOf(RangeError)
      expect(FIXED_ERRORS, testCase.name).toContain((error as Error).message)
    }
  })

  it('drops every own echo before Base64 decoding or reassembly', () => {
    for (const testCase of fixture.ownEchoes) {
      expect(
        decodeNotificationCarrier(testCase.frame, defaultNotificationLimits(), testCase.self),
        testCase.name,
      ).toBeNull()
    }
  })
})

describe('notification carrier', () => {
  it('counts empty, exact-chunk and boundary payloads', () => {
    expect(carrierCount(0)).toBe(1)
    expect(carrierCount(CHUNK_BYTES)).toBe(1)
    expect(carrierCount(CHUNK_BYTES + 1)).toBe(2)
    expect(carrierCount(DEFAULT_MAX_MESSAGE_BYTES)).toBe(1554)
    expect(() => carrierCount(-1)).toThrow(RangeError)
    expect(() => carrierCount(1.5)).toThrow(RangeError)
  })

  it('keeps the 8 MiB logical limit inside the wire budget and round-trips it', () => {
    const size = DEFAULT_MAX_MESSAGE_BYTES
    const body = new Uint8Array(size)
    for (let index = 0; index < size; index += 1) {
      body[index] = (index * 31 + 7) & 0xff
    }
    const message = new NotificationPacket(PUBLISHER, null, TOPIC, MESSAGE_ID, body)
    const total = carrierCount(size)
    let largestFrame = 0
    const { delivered, resyncs } = collector()
    const reassembler = new NotificationReassembler(
      SELF,
      defaultNotificationLimits(),
      null,
      (value) => delivered.push(value),
      (topic) => resyncs.push(topic),
    )
    for (let index = 0; index < total; index += 1) {
      const encoded = carrierChunk(message, index).encode()
      largestFrame = Math.max(largestFrame, new TextEncoder().encode(encoded).length)
      reassembler.accept(decodeNotificationCarrier(encoded, defaultNotificationLimits(), SELF))
    }
    expect(largestFrame).toBeLessThan(7900)
    expect(delivered).toHaveLength(1)
    expect(delivered[0].byteLength()).toBe(size)
    expect(bytesEqual(delivered[0].bytes(), body)).toBe(true)
    expect(resyncs).toHaveLength(0)
    expect(() =>
      new NotificationPacket(PUBLISHER, null, TOPIC, MESSAGE_ID, new Uint8Array(size + 1)),
    ).toThrow(RangeError)
  })

  it('enforces constructor bounds, canonical topic and exact chunk length', () => {
    expect(() => new NotificationCarrier(null, null, TOPIC, MESSAGE_ID, 0, 1, 0, utf8(''))).toThrow(
      TypeError,
    )
    expect(() =>
      new NotificationCarrier(PUBLISHER, null, null, MESSAGE_ID, 0, 1, 0, utf8('')),
    ).toThrow(TypeError)
    expect(() =>
      new NotificationCarrier(PUBLISHER, null, 'INVALID', MESSAGE_ID, 0, 1, 0, utf8('')),
    ).toThrow(RangeError)
    expect(() =>
      new NotificationCarrier(PUBLISHER, null, TOPIC, null, 0, 1, 0, utf8('')),
    ).toThrow(TypeError)
    expect(() => new NotificationCarrier(PUBLISHER, null, TOPIC, MESSAGE_ID, 0, 1, 0, null)).toThrow(
      TypeError,
    )
    expect(() =>
      new NotificationCarrier(PUBLISHER, null, TOPIC, MESSAGE_ID, 0, 1, -1, utf8('')),
    ).toThrow(RangeError)
    expect(() =>
      new NotificationCarrier(PUBLISHER, null, TOPIC, MESSAGE_ID, 0, 2, 0, utf8('')),
    ).toThrow(RangeError)
    expect(() =>
      new NotificationCarrier(PUBLISHER, null, TOPIC, MESSAGE_ID, -1, 1, 0, utf8('')),
    ).toThrow(RangeError)
    expect(() =>
      new NotificationCarrier(PUBLISHER, null, TOPIC, MESSAGE_ID, 1, 1, 0, utf8('')),
    ).toThrow(RangeError)
    expect(() =>
      new NotificationCarrier(PUBLISHER, null, TOPIC, MESSAGE_ID, 0, 1, 6000, new Uint8Array(5400)),
    ).toThrow(RangeError)
    expect(() =>
      new NotificationCarrier(PUBLISHER, null, TOPIC, MESSAGE_ID, 0, 2, 6000, new Uint8Array(6000)),
    ).toThrow(RangeError)
    expect(() =>
      new NotificationCarrier(PUBLISHER, null, TOPIC, MESSAGE_ID, 0, 1, 1.5, new Uint8Array(1)),
    ).toThrow(RangeError)
    expect(() => new NotificationPacket(null, null, TOPIC, MESSAGE_ID, utf8(''))).toThrow(TypeError)
    expect(() => new NotificationPacket(PUBLISHER, null, TOPIC, null, utf8(''))).toThrow(TypeError)
    expect(() => new NotificationPacket(PUBLISHER, null, TOPIC, MESSAGE_ID, null)).toThrow(TypeError)
    expect(() => carrierChunk(null, 0)).toThrow(TypeError)
    expect(() =>
      carrierChunk(new NotificationPacket(PUBLISHER, null, TOPIC, MESSAGE_ID, utf8('a')), 1),
    ).toThrow(RangeError)

    expect(new NotificationCarrier(PUBLISHER, null, TOPIC, MESSAGE_ID, 0, 1, 0, utf8('')).count()).toBe(
      1,
    )
    expect(
      new NotificationCarrier(PUBLISHER, null, TOPIC, MESSAGE_ID, 0, 2, 6000, new Uint8Array(5400))
        .count(),
    ).toBe(2)
    expect(
      new NotificationCarrier(PUBLISHER, null, TOPIC, MESSAGE_ID, 1, 2, 6000, new Uint8Array(600))
        .totalBytes(),
    ).toBe(6000)
  })

  it('copies the body in and out and never renders it', () => {
    const source = utf8('secret-body')
    const message = new NotificationPacket(PUBLISHER, SELF, TOPIC, MESSAGE_ID, source)
    source[0] = 0
    expect(bytesEqual(message.bytes(), utf8('secret-body'))).toBe(true)
    expect(message.bytes()).not.toBe(message.bytes())
    const copy = message.bytes()
    copy[0] = 0
    expect(bytesEqual(message.bytes(), utf8('secret-body'))).toBe(true)
    expect(message.byteLength()).toBe(11)
    expect(message.toString()).not.toContain('secret-body')
    expect(message.toString()).toContain(TOPIC)

    const carrier = carrierChunk(message, 0)
    expect(carrier.index()).toBe(0)
    expect(carrier.publisher()).toBe(PUBLISHER)
    expect(carrier.target()).toBe(SELF)
    expect(carrier.messageId()).toBe(MESSAGE_ID)
    expect(carrier.bytes()).not.toBe(carrier.bytes())
    expect(carrier.toString()).not.toContain('secret-body')
    const frame = carrier.encode()
    expect(frame.startsWith('1|')).toBe(true)
    const decoded = decodeNotificationCarrier(frame, defaultNotificationLimits(), SELF)
    expect(decoded).not.toBeNull()
    expect(decoded?.topic()).toBe(TOPIC)
    expect(decoded?.messageId()).toBe(MESSAGE_ID)
  })

  it('rejects non-string and missing-limit decode inputs with typed errors', () => {
    expect(() => decodeNotificationCarrier(42 as unknown as string, defaultNotificationLimits())).toThrow(
      TypeError,
    )
    expect(() => decodeNotificationCarrier('x', null as unknown as NotificationLimits)).toThrow(
      TypeError,
    )
  })

  it('keeps owned bytes unreachable through instance or prototype symbols', () => {
    const message = packet(PUBLISHER, SELF, 'body')
    const carrier = carrierChunk(message, 0)
    // The old implementation exposed a symbol-keyed raw-bytes method; reflection must not find any
    // way to reach the owned array and mutate an already queued body.
    expect(Object.getOwnPropertySymbols(NotificationPacket.prototype)).toEqual([])
    expect(Object.getOwnPropertySymbols(NotificationCarrier.prototype)).toEqual([])
    expect(Object.getOwnPropertySymbols(message)).toEqual([])
    expect(Object.getOwnPropertySymbols(carrier)).toEqual([])
    expect(Reflect.ownKeys(message).filter((key) => typeof key === 'symbol')).toEqual([])
    expect(Reflect.ownKeys(carrier).filter((key) => typeof key === 'symbol')).toEqual([])

    const snapshot = message.bytes()
    const frameBefore = carrier.encode()
    snapshot[0] = 0
    expect(bytesEqual(message.bytes(), utf8('body'))).toBe(true)
    expect(carrier.encode()).toBe(frameBefore)
  })

  it('requires canonical UUID identities and rejects coercible values', () => {
    const coercible = { toString: () => PUBLISHER } as unknown as string
    expect(() => new NotificationPacket(coercible, null, TOPIC, MESSAGE_ID, utf8(''))).toThrow(
      TypeError,
    )
    expect(() => new NotificationPacket('not-a-uuid', null, TOPIC, MESSAGE_ID, utf8(''))).toThrow(
      RangeError,
    )
    expect(() =>
      new NotificationPacket(PUBLISHER.toUpperCase(), null, TOPIC, MESSAGE_ID, utf8('')),
    ).toThrow(RangeError)
    expect(() => new NotificationPacket(PUBLISHER, 'not-a-uuid', TOPIC, MESSAGE_ID, utf8(''))).toThrow(
      RangeError,
    )
    expect(() => new NotificationPacket(PUBLISHER, null, TOPIC, 'not-a-uuid', utf8(''))).toThrow(
      RangeError,
    )
    expect(() => new NotificationCarrier(coercible, null, TOPIC, MESSAGE_ID, 0, 1, 0, utf8(''))).toThrow(
      TypeError,
    )
    expect(() =>
      new NotificationCarrier(PUBLISHER, coercible, TOPIC, MESSAGE_ID, 0, 1, 0, utf8('')),
    ).toThrow(TypeError)
    expect(() =>
      new NotificationCarrier(PUBLISHER, null, TOPIC, 'not-a-uuid', 0, 1, 0, utf8('')),
    ).toThrow(RangeError)

    // A valid value can only ever encode a canonical header.
    const frame = carrierChunk(packet(PUBLISHER, null, 'x'), 0).encode()
    expect(frame.split('|')[1]).toBe(PUBLISHER)
  })

  it('rejects non-typed-array bytes and never copies before validating the length', () => {
    const oversized = spyBytes(DEFAULT_MAX_MESSAGE_BYTES + 1)
    expect(() =>
      new NotificationPacket(PUBLISHER, null, TOPIC, MESSAGE_ID, oversized.bytes),
    ).toThrow(RangeError)
    expect(oversized.calls.count).toBe(0)

    const wrongChunk = spyBytes(CHUNK_BYTES)
    expect(() =>
      new NotificationCarrier(PUBLISHER, null, TOPIC, MESSAGE_ID, 0, 1, 1, wrongChunk.bytes),
    ).toThrow(RangeError)
    expect(wrongChunk.calls.count).toBe(0)

    const valid = spyBytes(4)
    const copied = new NotificationPacket(PUBLISHER, null, TOPIC, MESSAGE_ID, valid.bytes)
    expect(valid.calls.count).toBe(0)
    expect(copied.byteLength()).toBe(4)

    expect(() => new NotificationPacket(PUBLISHER, null, TOPIC, MESSAGE_ID, [1, 2, 3] as never)).toThrow(
      TypeError,
    )
    expect(() =>
      new NotificationPacket(PUBLISHER, null, TOPIC, MESSAGE_ID, { length: 3 } as never),
    ).toThrow(TypeError)
    expect(() =>
      new NotificationCarrier(PUBLISHER, null, TOPIC, MESSAGE_ID, 0, 1, 0, null as never),
    ).toThrow(TypeError)
  })

  it('requires verified packet instances instead of duck-typed carriers', () => {
    const fake = {
      byteLength: () => DEFAULT_MAX_MESSAGE_BYTES,
      publisher: () => PUBLISHER,
      target: () => null,
      topic: () => TOPIC,
      messageId: () => MESSAGE_ID,
      index: () => 0,
      count: () => 1,
      totalBytes: () => DEFAULT_MAX_MESSAGE_BYTES,
    }
    expect(() => carrierChunk(fake as never, 0)).toThrow(TypeError)
    const outbox = new NotificationOutbox(defaultNotificationLimits())
    expect(() => outbox.offer(fake as never)).toThrow(TypeError)
    const reassembler = new NotificationReassembler(
      SELF,
      defaultNotificationLimits(),
      () => 0,
      () => {},
      () => {},
    )
    expect(() => reassembler.accept(fake as never)).toThrow(TypeError)
  })
})

describe('notification reassembler', () => {
  it('delivers reordered and duplicated fragments exactly once', () => {
    const { delivered, resyncs } = collector()
    const reassembler = new NotificationReassembler(
      SELF,
      tinyLimits({ reassemblyMessages: 2 }),
      () => 0,
      (value) => delivered.push(value),
      (topic) => resyncs.push(topic),
    )
    const message = packet(PUBLISHER, SELF, '中😀'.repeat(2000))
    expect(carrierCount(message.byteLength())).toBe(3)
    reassembler.accept(carrierChunk(message, 2))
    reassembler.accept(carrierChunk(message, 0))
    reassembler.accept(carrierChunk(message, 0))
    expect(delivered).toHaveLength(0)
    reassembler.accept(carrierChunk(message, 1))
    for (let index = 0; index < 3; index += 1) {
      reassembler.accept(carrierChunk(message, index))
    }
    expect(delivered).toHaveLength(1)
    expect(bytesEqual(delivered[0].bytes(), message.bytes())).toBe(true)
    expect(delivered[0].target()).toBe(SELF)
    expect(reassembler.reservedBytes()).toBe(0)
    expect(resyncs).toHaveLength(0)
  })

  it('routes count=1 and empty payloads through the same path', () => {
    const { delivered, resyncs } = collector()
    const reassembler = new NotificationReassembler(
      SELF,
      tinyLimits(),
      undefined,
      (value) => delivered.push(value),
      (topic) => resyncs.push(topic),
    )
    reassembler.accept(carrierChunk(packet(PUBLISHER, null, ''), 0))
    reassembler.accept(carrierChunk(packet(PUBLISHER, null, 'hint', MESSAGE_ID_ALT), 0))
    expect(delivered).toHaveLength(2)
    expect(delivered[0].byteLength()).toBe(0)
    expect(reassembler.reservedBytes()).toBe(0)
    expect(resyncs).toHaveLength(0)
  })

  it('discards the whole message on conflicting duplicate or header mismatch', () => {
    const { delivered, resyncs } = collector()
    const reassembler = new NotificationReassembler(
      SELF,
      tinyLimits(),
      () => 0,
      (value) => delivered.push(value),
      (topic) => resyncs.push(topic),
    )
    const firstMessage = packet(PUBLISHER, null, 'a'.repeat(10000))
    const first = carrierChunk(firstMessage, 0)
    reassembler.accept(first)
    const conflicting = first.bytes()
    conflicting[0] = 2
    reassembler.accept(
      new NotificationCarrier(
        first.publisher(),
        first.target(),
        first.topic(),
        first.messageId(),
        0,
        first.count(),
        first.totalBytes(),
        conflicting,
      ),
    )
    reassembler.accept(carrierChunk(firstMessage, 1))
    expect(delivered).toHaveLength(0)
    expect(resyncs).toEqual([TOPIC])
    expect(reassembler.reservedBytes()).toBe(0)

    const secondMessage = packet(PUBLISHER, null, 'b'.repeat(10000), MESSAGE_ID_ALT)
    const original = carrierChunk(secondMessage, 0)
    reassembler.accept(original)
    reassembler.accept(
      new NotificationCarrier(
        original.publisher(),
        SELF,
        'other.topic',
        original.messageId(),
        1,
        2,
        original.totalBytes(),
        carrierChunk(secondMessage, 1).bytes(),
      ),
    )
    expect(resyncs).toEqual([TOPIC, TOPIC, 'other.topic'])
    expect(reassembler.reservedBytes()).toBe(0)
  })

  it('expires incomplete messages at the deadline without applying partial payload', () => {
    const { delivered, resyncs } = collector()
    let now = 0
    const reassembler = new NotificationReassembler(
      SELF,
      tinyLimits(),
      () => now,
      (value) => delivered.push(value),
      (topic) => resyncs.push(topic),
    )
    const message = packet(PUBLISHER, null, 'x'.repeat(10000))
    reassembler.accept(carrierChunk(message, 0))
    expect(reassembler.reservedBytes()).toBe(10000)
    now = 5000
    reassembler.expire()
    reassembler.accept(carrierChunk(message, 1))
    expect(resyncs).toEqual([TOPIC])
    expect(delivered).toHaveLength(0)
    expect(reassembler.reservedBytes()).toBe(0)
    now = 10001
    reassembler.expire()
    reassembler.clear()
  })

  it('bounds pending messages while dropping echo and foreign frames without allocating', () => {
    const { delivered, resyncs } = collector()
    const reassembler = new NotificationReassembler(
      SELF,
      tinyLimits({ reassemblyMessages: 2 }),
      () => 0,
      (value) => delivered.push(value),
      (topic) => resyncs.push(topic),
    )
    for (let index = 0; index < 3; index += 1) {
      reassembler.accept(
        carrierChunk(packet(PUBLISHER, null, 'x'.repeat(10000), `${MESSAGE_ID.slice(0, -1)}${index}`), 0),
      )
    }
    expect(reassembler.reservedBytes()).toBe(20000)
    expect(resyncs).toHaveLength(1)
    reassembler.accept(carrierChunk(packet(SELF, null, 'echo'.repeat(2000), MESSAGE_ID_ALT), 0))
    reassembler.accept(carrierChunk(packet(PUBLISHER, FOREIGN, 'foreign', RANDOM_ID), 0))
    expect(reassembler.reservedBytes()).toBe(20000)
    reassembler.clear()
    expect(reassembler.reservedBytes()).toBe(0)
  })

  it('enforces the aggregate byte budget before accepting a fragment', () => {
    const { delivered, resyncs } = collector()
    const limits = tinyLimits({
      maxMessageBytes: 20000,
      pendingBytes: 40000,
      queueCapacity: 2,
      reassemblyBytes: 20000,
      reassemblyMessages: 8,
    })
    const reassembler = new NotificationReassembler(
      SELF,
      limits,
      () => 0,
      (value) => delivered.push(value),
      (topic) => resyncs.push(topic),
    )
    reassembler.accept(carrierChunk(packet(PUBLISHER, null, 'a'.repeat(15000)), 0))
    reassembler.accept(carrierChunk(packet(PUBLISHER, null, 'b'.repeat(15000), MESSAGE_ID_ALT), 0))
    expect(reassembler.reservedBytes()).toBe(15000)
    expect(resyncs).toHaveLength(1)
    expect(delivered).toHaveLength(0)
  })

  it('rejects a hand-built over-budget carrier without inflating allocation', () => {
    const { delivered, resyncs } = collector()
    const chunk = carrierChunk(packet(PUBLISHER, null, 'z'.repeat(60000)), 0)
    const reassembler = new NotificationReassembler(
      SELF,
      tinyLimits({
        maxMessageBytes: 20000,
        pendingBytes: 40000,
        queueCapacity: 2,
        reassemblyBytes: 20000,
        reassemblyMessages: 8,
      }),
      () => 0,
      (value) => delivered.push(value),
      (topic) => resyncs.push(topic),
    )
    reassembler.accept(chunk)
    expect(resyncs).toEqual([TOPIC])
    expect(reassembler.reservedBytes()).toBe(0)
    expect(delivered).toHaveLength(0)
  })

  it('applies the logical limit even when the aggregate budget has room', () => {
    const { delivered, resyncs } = collector()
    const reassembler = new NotificationReassembler(
      SELF,
      tinyLimits(),
      () => 0,
      (value) => delivered.push(value),
      (topic) => resyncs.push(topic),
    )
    const message = packet(PUBLISHER, SELF, 'z'.repeat(30000))
    for (let index = 0; index < carrierCount(message.byteLength()); index += 1) {
      reassembler.accept(carrierChunk(message, index))
    }
    expect(resyncs).toEqual([TOPIC])
    expect(reassembler.reservedBytes()).toBe(0)
    expect(delivered).toHaveLength(0)
  })

  it('evicts the oldest completion tombstone when the bounded set fills', () => {
    const { delivered, resyncs } = collector()
    const reassembler = new NotificationReassembler(
      SELF,
      tinyLimits({ queueCapacity: 1 }),
      () => 0,
      (value) => delivered.push(value),
      (topic) => resyncs.push(topic),
    )
    const first = packet(PUBLISHER, null, 'one')
    const second = packet(PUBLISHER, null, 'two', MESSAGE_ID_ALT)
    reassembler.accept(carrierChunk(first, 0))
    reassembler.accept(carrierChunk(second, 0))
    expect(delivered).toHaveLength(2)
    // The first tombstone was evicted by the bounded set, so its late duplicate is reprocessed.
    reassembler.accept(carrierChunk(first, 0))
    expect(delivered).toHaveLength(3)
    expect(reassembler.reservedBytes()).toBe(0)
    reassembler.clear()
    expect(resyncs).toHaveLength(0)
  })

  it('shares the primitive across two independent receiving identities', () => {
    const first = collector()
    const second = collector()
    const firstReassembler = new NotificationReassembler(
      SELF,
      tinyLimits({ reassemblyMessages: 8 }),
      () => 0,
      (value) => first.delivered.push(value),
      (topic) => first.resyncs.push(topic),
    )
    const secondReassembler = new NotificationReassembler(
      FOREIGN,
      tinyLimits({ reassemblyMessages: 8 }),
      () => 0,
      (value) => second.delivered.push(value),
      (topic) => second.resyncs.push(topic),
    )
    const directed = packet(PUBLISHER, FOREIGN, 'directed'.repeat(2000))
    for (let index = 0; index < carrierCount(directed.byteLength()); index += 1) {
      const encoded = carrierChunk(directed, index).encode()
      firstReassembler.accept(decodeNotificationCarrier(encoded, tinyLimits(), SELF))
      secondReassembler.accept(decodeNotificationCarrier(encoded, tinyLimits(), FOREIGN))
    }
    expect(first.delivered).toHaveLength(0)
    expect(second.delivered).toHaveLength(1)
    expect(bytesEqual(second.delivered[0].bytes(), directed.bytes())).toBe(true)

    const broadcast = packet(PUBLISHER, null, 'broadcast'.repeat(1000), MESSAGE_ID_ALT)
    for (let index = 0; index < carrierCount(broadcast.byteLength()); index += 1) {
      const encoded = carrierChunk(broadcast, index).encode()
      firstReassembler.accept(decodeNotificationCarrier(encoded, tinyLimits(), SELF))
      secondReassembler.accept(decodeNotificationCarrier(encoded, tinyLimits(), FOREIGN))
    }
    expect(first.delivered).toHaveLength(1)
    expect(second.delivered).toHaveLength(2)

    const own = carrierChunk(packet(SELF, null, 'own'.repeat(1000), RANDOM_ID), 0).encode()
    expect(
      decodeNotificationCarrier(`${own.slice(0, own.lastIndexOf('|') + 1)}bad`, tinyLimits(), SELF),
    ).toBeNull()
  })

  it('validates its construction arguments', () => {
    expect(
      () => new NotificationReassembler(SELF, null as unknown as NotificationLimits, null, () => {}, () => {}),
    ).toThrow(TypeError)
    expect(() => new NotificationReassembler(SELF, tinyLimits(), null, null as never, () => {})).toThrow(
      TypeError,
    )
    expect(() => new NotificationReassembler(SELF, tinyLimits(), null, () => {}, null as never)).toThrow(
      TypeError,
    )
    expect(() => new NotificationReassembler(SELF, tinyLimits(), null, () => {}, () => {}).accept(null as never)).toThrow(
      TypeError,
    )
  })

  it('owns its budgets so later caller mutation cannot change the bounds', () => {
    const { delivered, resyncs } = collector()
    const raw: NotificationLimits = {
      maxMessageBytes: 20000,
      pendingBytes: 40000,
      queueCapacity: 4,
      reassemblyBytes: 40000,
      reassemblyMessages: 2,
      reassemblyTimeoutMs: 5000,
      sendBatchFrames: 1,
    }
    const reassembler = new NotificationReassembler(
      SELF,
      raw,
      () => 0,
      (value) => delivered.push(value),
      (topic) => resyncs.push(topic),
    )
    raw.maxMessageBytes = 1
    raw.reassemblyBytes = 1
    reassembler.accept(carrierChunk(packet(PUBLISHER, null, 'x'.repeat(10000)), 0))
    expect(reassembler.reservedBytes()).toBe(10000)
    expect(resyncs).toHaveLength(0)
    reassembler.clear()
    expect(reassembler.reservedBytes()).toBe(0)
  })
})

describe('notification outbox', () => {
  it('sends empty and single-fragment packets as one bounded frame', () => {
    const limits = outboxLimits(100, 1000, 4, 4)
    const outbox = new NotificationOutbox(limits)
    expect(outbox.offer(packet(PUBLISHER, SELF, ''))).toBe(true)
    expect(outbox.offer(packet(PUBLISHER, SELF, 'hint', MESSAGE_ID_ALT))).toBe(true)
    expect(outbox.pendingMessages()).toBe(2)

    const first = outbox.pollBatch()
    expect(first?.totalBytes()).toBe(0)
    expect(first?.frameCount()).toBe(1)
    expect(first?.topic()).toBe(TOPIC)
    expect(first?.publisher()).toBe(PUBLISHER)
    expect(first?.target()).toBe(SELF)
    const decodedEmpty = decodeNotificationCarrier(first?.frames()[0] ?? '', limits, SELF)
    expect(decodedEmpty?.totalBytes()).toBe(0)
    expect(decodedEmpty?.bytes()).toHaveLength(0)
    expect(outbox.complete(first, true)).toBe(true)

    const second = outbox.pollBatch()
    expect(second?.messageId()).toBe(MESSAGE_ID_ALT)
    expect(second?.frameCount()).toBe(1)
    expect(second?.totalBytes()).toBe(4)
    expect(outbox.complete(second, true)).toBe(true)

    expect(outbox.pollBatch()).toBeNull()
    expect(outbox.pendingMessages()).toBe(0)
  })

  it('round-trips a large packet through the shared carrier', () => {
    const limits = outboxLimits(40000, 40000, 8, 2)
    const outbox = new NotificationOutbox(limits)
    const body = new Uint8Array(18000)
    for (let index = 0; index < body.length; index += 1) {
      body[index] = (index * 31) & 0xff
    }
    const message = new NotificationPacket(PUBLISHER, SELF, TOPIC, MESSAGE_ID, body)
    expect(outbox.offer(message)).toBe(true)

    const first = outbox.pollBatch()
    expect(first?.frameCount()).toBe(2)
    expect(outbox.complete(first, true)).toBe(true)
    const second = outbox.pollBatch()
    expect(second?.frameCount()).toBe(2)
    expect(outbox.complete(second, true)).toBe(true)

    const frames = [...(first?.frames() ?? []), ...(second?.frames() ?? [])]
    expect(frames).toHaveLength(carrierCount(body.length))
    const { delivered } = collector()
    const reassembler = new NotificationReassembler(
      SELF,
      limits,
      () => 0,
      (value) => delivered.push(value),
      () => {},
    )
    for (const frame of frames) {
      reassembler.accept(decodeNotificationCarrier(frame, limits, SELF))
    }
    expect(delivered).toHaveLength(1)
    expect(bytesEqual(delivered[0].bytes(), body)).toBe(true)
    expect(outbox.pendingMessages()).toBe(0)
  })

  it('rotates an unfinished packet to the tail so a control message passes it', () => {
    const limits = outboxLimits(40000, 40000, 8, 1)
    const outbox = new NotificationOutbox(limits)
    const large = packet(PUBLISHER, SELF, 'x'.repeat(18000))
    const control = packet(PUBLISHER, SELF, 'control', MESSAGE_ID_ALT)
    expect(carrierCount(large.byteLength())).toBe(4)
    expect(outbox.offer(large)).toBe(true)
    expect(outbox.offer(control)).toBe(true)

    const order = []
    for (let index = 0; index < 5; index += 1) {
      const batch = outbox.pollBatch()
      order.push(batch?.messageId())
      expect(outbox.complete(batch, true)).toBe(true)
    }
    expect(order).toEqual([
      MESSAGE_ID,
      MESSAGE_ID_ALT,
      MESSAGE_ID,
      MESSAGE_ID,
      MESSAGE_ID,
    ])
    expect(outbox.pendingMessages()).toBe(0)
  })

  it('keeps the whole reservation until the final fragment completes', () => {
    const limits = outboxLimits(40000, 40000, 8, 1)
    const outbox = new NotificationOutbox(limits)
    expect(outbox.offer(packet(PUBLISHER, SELF, 'y'.repeat(18000)))).toBe(true)
    expect(outbox.pendingMessages()).toBe(1)
    expect(outbox.pendingBytes()).toBe(18000)

    expect(outbox.complete(outbox.pollBatch(), true)).toBe(true)
    expect(outbox.pendingBytes()).toBe(18000)
    for (let index = 0; index < 2; index += 1) {
      expect(outbox.complete(outbox.pollBatch(), true)).toBe(true)
    }
    const last = outbox.pollBatch()
    expect(outbox.pendingBytes()).toBe(18000)
    expect(outbox.complete(last, true)).toBe(true)
    expect(outbox.pendingMessages()).toBe(0)
    expect(outbox.pendingBytes()).toBe(0)
  })

  it('rejects on queue, byte and per-message budgets without consuming', () => {
    const capacity = new NotificationOutbox(outboxLimits(100, 1000, 1, 1))
    expect(capacity.offer(packet(PUBLISHER, SELF, 'a'))).toBe(true)
    expect(capacity.offer(packet(PUBLISHER, SELF, 'b', MESSAGE_ID_ALT))).toBe(false)
    expect(capacity.pendingMessages()).toBe(1)
    const capacityActive = capacity.pollBatch()
    expect(capacity.offer(packet(PUBLISHER, SELF, 'c', RANDOM_ID))).toBe(false)
    expect(capacity.complete(capacityActive, true)).toBe(true)
    expect(capacity.offer(packet(PUBLISHER, SELF, 'c', RANDOM_ID))).toBe(true)

    const bytes = new NotificationOutbox(outboxLimits(100, 150, 4, 1))
    expect(bytes.offer(packet(PUBLISHER, SELF, 'x'.repeat(100)))).toBe(true)
    expect(bytes.offer(packet(PUBLISHER, SELF, 'y'.repeat(100), MESSAGE_ID_ALT))).toBe(false)
    expect(bytes.pendingBytes()).toBe(100)
    const bytesActive = bytes.pollBatch()
    expect(bytes.offer(packet(PUBLISHER, SELF, 'z'.repeat(51), RANDOM_ID))).toBe(false)
    expect(bytes.complete(bytesActive, true)).toBe(true)
    expect(bytes.offer(packet(PUBLISHER, SELF, 'z'.repeat(100), RANDOM_ID))).toBe(true)

    const perMessage = new NotificationOutbox(outboxLimits(50, 1000, 4, 1))
    expect(perMessage.offer(packet(PUBLISHER, SELF, 'z'.repeat(51)))).toBe(false)
    expect(perMessage.pendingMessages()).toBe(0)
    expect(() => perMessage.offer(null as never)).toThrow(TypeError)
  })

  it('drops a failed batch without retrying', () => {
    const outbox = new NotificationOutbox(outboxLimits(40000, 40000, 8, 1))
    expect(outbox.offer(packet(PUBLISHER, SELF, 'f'.repeat(18000)))).toBe(true)
    const first = outbox.pollBatch()
    expect(outbox.complete(first, false)).toBe(true)
    expect(outbox.pendingMessages()).toBe(0)
    expect(outbox.pendingBytes()).toBe(0)
    expect(outbox.pollBatch()).toBeNull()
  })

  it('releases everything on clear/close and gates further offers', () => {
    const outbox = new NotificationOutbox(outboxLimits(40000, 40000, 8, 1))
    expect(outbox.offer(packet(PUBLISHER, SELF, 'c'.repeat(18000)))).toBe(true)
    const inFlight = outbox.pollBatch()
    outbox.clear()
    expect(outbox.pendingMessages()).toBe(0)
    expect(outbox.pendingBytes()).toBe(0)
    expect(outbox.complete(inFlight, true)).toBe(false)
    expect(outbox.offer(packet(PUBLISHER, SELF, 'again', MESSAGE_ID_ALT))).toBe(true)
    expect(outbox.complete(outbox.pollBatch(), false)).toBe(true)
    expect(outbox.pendingMessages()).toBe(0)

    outbox.close()
    expect(outbox.offer(packet(PUBLISHER, SELF, 'closed'))).toBe(false)
    expect(outbox.pollBatch()).toBeNull()
    expect(outbox.pendingMessages()).toBe(0)
  })

  it('rejects foreign, stale and duplicate batches by identity', () => {
    const limits = outboxLimits(40000, 40000, 8, 2)
    const outbox = new NotificationOutbox(limits)
    const foreignOutbox = new NotificationOutbox(limits)
    expect(outbox.offer(packet(PUBLISHER, SELF, 'z'.repeat(18000)))).toBe(true)
    expect(foreignOutbox.offer(packet(PUBLISHER, SELF, 'other'.repeat(5000), MESSAGE_ID_ALT))).toBe(
      true,
    )
    const batch = outbox.pollBatch()
    const foreign = foreignOutbox.pollBatch()
    expect(outbox.complete(foreign, true)).toBe(false)
    expect(outbox.complete(null, true)).toBe(false)
    expect(outbox.pendingMessages()).toBe(1)
    expect(outbox.pendingBytes()).toBe(18000)
    expect(outbox.complete(batch, true)).toBe(true)
    expect(outbox.complete(batch, true)).toBe(false)
    expect(outbox.complete(foreign, false)).toBe(false)

    const active = new NotificationOutbox(limits)
    expect(active.offer(packet(PUBLISHER, SELF, 's'.repeat(18000)))).toBe(true)
    expect(active.pollBatch()).not.toBeNull()
    expect(active.pollBatch()).toBeNull()
  })

  it('keeps batch frames immutable and redacts body and frames in toString', () => {
    const limits = outboxLimits(40000, 40000, 8, 2)
    const outbox = new NotificationOutbox(limits)
    const body = `secret-payload-${'中😀'.repeat(1000)}-end`
    expect(outbox.offer(packet(PUBLISHER, SELF, body))).toBe(true)
    const batch = outbox.pollBatch()
    expect(batch?.frameCount()).toBe(2)
    expect(Object.isFrozen(batch?.frames())).toBe(true)
    expect(() => (batch?.frames() as string[]).push('tampered')).toThrow(TypeError)
    const text = batch?.toString() ?? ''
    expect(text).not.toContain('secret-payload')
    expect(text).not.toContain(batch?.frames()[0] ?? '')
    expect(text).toContain(TOPIC)
    expect(text).toContain(MESSAGE_ID)
  })

  it('keeps the batch cursor non-rewritable so progress and budget cannot be perturbed', () => {
    const outbox = new NotificationOutbox(outboxLimits(40000, 40000, 8, 1))
    expect(outbox.offer(packet(PUBLISHER, SELF, 'x'.repeat(18000)))).toBe(true)
    const batch = outbox.pollBatch()
    expect(Object.isFrozen(batch)).toBe(true)
    expect(Reflect.ownKeys(batch ?? {}).filter((key) => typeof key === 'symbol')).toEqual([])
    // Reflection cannot move the reserved cursor; frozen instances reject any write attempt.
    for (const key of Reflect.ownKeys(batch as object)) {
      expect(() => {
        ;(batch as unknown as Record<string | symbol, unknown>)[key] = 999
      }).toThrow(TypeError)
    }
    expect(() => {
      ;(batch as unknown as Record<string, unknown>).nextIndex = 999
    }).toThrow(TypeError)
    expect(outbox.complete(batch, true)).toBe(true)
    // Only the first fragment advanced, so the whole 18000-byte packet stays reserved.
    expect(outbox.pendingMessages()).toBe(1)
    expect(outbox.pendingBytes()).toBe(18000)
    for (let index = 0; index < 3; index += 1) {
      expect(outbox.complete(outbox.pollBatch(), true)).toBe(true)
    }
    expect(outbox.pendingMessages()).toBe(0)
    expect(outbox.pendingBytes()).toBe(0)
  })

  it('owns its budgets so later caller mutation cannot change the bounds', () => {
    const raw: NotificationLimits = {
      maxMessageBytes: 100,
      pendingBytes: 1000,
      queueCapacity: 4,
      reassemblyBytes: 1000,
      reassemblyMessages: 4,
      reassemblyTimeoutMs: 5000,
      sendBatchFrames: 1,
    }
    const outbox = new NotificationOutbox(raw)
    raw.maxMessageBytes = 1
    raw.pendingBytes = 0
    raw.queueCapacity = 0
    expect(outbox.offer(packet(PUBLISHER, SELF, 'a'.repeat(50)))).toBe(true)
    expect(outbox.offer(packet(PUBLISHER, SELF, 'b'.repeat(50), MESSAGE_ID_ALT))).toBe(true)
    expect(outbox.pendingBytes()).toBe(100)
  })

  it('validates its construction argument', () => {
    expect(() => new NotificationOutbox(null as unknown as NotificationLimits)).toThrow(TypeError)
  })
})

describe('notification limits', () => {
  it('exposes exact frozen defaults', () => {
    expect(defaultNotificationLimits()).toEqual({
      maxMessageBytes: 8 * 1024 * 1024,
      pendingBytes: 32 * 1024 * 1024,
      queueCapacity: 256,
      reassemblyBytes: 32 * 1024 * 1024,
      reassemblyMessages: 8,
      reassemblyTimeoutMs: 5000,
      sendBatchFrames: 32,
    })
    expect(Object.isFrozen(defaultNotificationLimits())).toBe(true)
  })

  it('rejects every under-sized or zero budget', () => {
    const valid = defaultNotificationLimits()
    expect(() => createNotificationLimits(null as unknown as NotificationLimits)).toThrow(TypeError)
    expect(() =>
      createNotificationLimits({ ...valid, maxMessageBytes: DEFAULT_MAX_MESSAGE_BYTES + 1 }),
    ).toThrow(RangeError)
    expect(() => createNotificationLimits({ ...valid, maxMessageBytes: 0 })).toThrow(RangeError)
    expect(() => createNotificationLimits({ ...valid, pendingBytes: valid.maxMessageBytes - 1 })).toThrow(
      RangeError,
    )
    expect(() => createNotificationLimits({ ...valid, queueCapacity: 0 })).toThrow(RangeError)
    expect(() =>
      createNotificationLimits({ ...valid, reassemblyBytes: valid.maxMessageBytes - 1 }),
    ).toThrow(RangeError)
    expect(() => createNotificationLimits({ ...valid, reassemblyMessages: 0 })).toThrow(RangeError)
    expect(() => createNotificationLimits({ ...valid, reassemblyTimeoutMs: 0 })).toThrow(RangeError)
    expect(() => createNotificationLimits({ ...valid, reassemblyTimeoutMs: -1 })).toThrow(RangeError)
    expect(() => createNotificationLimits({ ...valid, reassemblyTimeoutMs: Number.NaN })).toThrow(
      RangeError,
    )
    expect(() => createNotificationLimits({ ...valid, reassemblyTimeoutMs: Number.POSITIVE_INFINITY })).toThrow(
      RangeError,
    )
    expect(() => createNotificationLimits({ ...valid, sendBatchFrames: 0 })).toThrow(RangeError)
  })

  it('rejects unsafe, fractional and out-of-range integer budgets', () => {
    const valid = defaultNotificationLimits()
    expect(() => createNotificationLimits({ ...valid, pendingBytes: JAVA_INT_MAX + 1 })).toThrow(
      RangeError,
    )
    expect(() =>
      createNotificationLimits({ ...valid, queueCapacity: Number.MAX_SAFE_INTEGER + 1 }),
    ).toThrow(RangeError)
    expect(() => createNotificationLimits({ ...valid, queueCapacity: 1.5 })).toThrow(RangeError)
    expect(() => createNotificationLimits({ ...valid, reassemblyMessages: 2 ** 53 })).toThrow(
      RangeError,
    )
    expect(() =>
      createNotificationLimits({ ...valid, reassemblyTimeoutMs: Number.MAX_SAFE_INTEGER + 1 }),
    ).toThrow(RangeError)
  })

  it('rejects a decode budget that cannot bound the message', () => {
    const frame = carrierChunk(packet(PUBLISHER, null, 'x'), 0).encode()
    expect(() =>
      decodeNotificationCarrier(frame, { maxMessageBytes: Number.NaN } as NotificationLimits, SELF),
    ).toThrow(RangeError)
    expect(() =>
      decodeNotificationCarrier(frame, { maxMessageBytes: undefined } as unknown as NotificationLimits, SELF),
    ).toThrow(RangeError)
    expect(() =>
      decodeNotificationCarrier(frame, { maxMessageBytes: JAVA_INT_MAX + 1 } as NotificationLimits, SELF),
    ).toThrow(RangeError)
  })
})
