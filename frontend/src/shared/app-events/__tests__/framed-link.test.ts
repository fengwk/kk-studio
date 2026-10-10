import { describe, expect, it } from 'vitest'
import { FramedEventLink } from '@/shared/app-events/framed-link.mjs'
import {
  NotificationPacket,
  carrierChunk,
  carrierCount,
  createNotificationLimits,
  type NotificationLimits,
} from '@/shared/notification/notification.mjs'

const SELF = '5f0e2c1a-1111-4111-8111-000000000001'
const PEER = 'a1b2c3d4-2222-4222-8222-000000000002'
const FOREIGN = 'c1d2e3f4-4444-4444-8444-000000000004'
const TOPIC = 'app.events.v2'
const encoder = new TextEncoder()

let seq = 0
function messageId(): string {
  seq += 1
  return `00000000-0000-4000-8000-${String(seq).padStart(12, '0')}`
}

function limits(overrides: Partial<NotificationLimits> = {}): NotificationLimits {
  return createNotificationLimits({
    maxMessageBytes: 20000,
    pendingBytes: 40000,
    queueCapacity: 8,
    reassemblyBytes: 20000,
    reassemblyMessages: 4,
    reassemblyTimeoutMs: 5000,
    sendBatchFrames: 2,
    ...overrides,
  })
}

function carrier(
  body: string,
  { publisher = PEER, target = null, topic = TOPIC, bytes = null, id = messageId() } = {},
): string {
  const packet = new NotificationPacket(
    publisher,
    target,
    topic,
    id,
    bytes ?? encoder.encode(body),
  )
  return carrierChunk(packet, 0).encode()
}

function frame(body: string, options: Parameters<typeof carrier>[1] = {}): string[] {
  const packet = new NotificationPacket(
    options.publisher ?? PEER,
    options.target ?? null,
    options.topic ?? TOPIC,
    options.id ?? messageId(),
    encoder.encode(body),
  )
  const count = carrierCount(packet.byteLength())
  return Array.from({ length: count }, (_, index) => carrierChunk(packet, index).encode())
}

function link(
  overrides: Partial<NotificationLimits> = {},
  clock: (() => number) | null = null,
  self = SELF,
) {
  const delivered: string[] = []
  const failures: boolean[] = []
  const instance = new FramedEventLink({
    self,
    limits: limits(overrides),
    clock,
    deliver: (body) => delivered.push(body),
    onFailure: (recover) => failures.push(recover),
  })
  return { instance, delivered, failures }
}

describe('FramedEventLink', () => {
  it('freezes the peer on the first valid frame and rejects a later foreign publisher', () => {
    const { instance, delivered, failures } = link()
    instance.accept(carrier('{"version":2,"type":"heartbeat"}'))
    expect(instance.peer()).toBe(PEER)
    expect(delivered).toEqual(['{"version":2,"type":"heartbeat"}'])

    instance.accept(carrier('{"version":2,"type":"heartbeat"}', { publisher: FOREIGN }))
    expect(failures).toEqual([false])
    expect(instance.isClosed()).toBe(true)
  })

  it('drops own echoes before delivery and never freezes a peer on them', () => {
    const { instance, delivered, failures } = link()
    instance.accept(carrier('{"version":2,"type":"heartbeat"}', { publisher: SELF }))
    expect(delivered).toEqual([])
    expect(failures).toEqual([])
    expect(instance.peer()).toBeNull()
    expect(instance.isClosed()).toBe(false)
  })

  it('rejects wrong topic, foreign target, non-string and invalid UTF-8 frames as terminal', () => {
    const wrongTopic = link()
    wrongTopic.instance.accept(carrier('x', { topic: 'other.topic' }))
    expect(wrongTopic.failures).toEqual([false])

    const wrongTarget = link()
    wrongTarget.instance.accept(carrier('x', { target: FOREIGN }))
    expect(wrongTarget.failures).toEqual([false])

    const binary = link()
    binary.instance.accept(new Uint8Array([1, 2, 3]) as unknown as string)
    expect(binary.failures).toEqual([false])

    // 合法 carrier 但逻辑字节不是有效 UTF-8：终态，绝不交付。
    const invalidUtf8 = link()
    invalidUtf8.instance.accept(carrier('', { bytes: new Uint8Array([0xff, 0xfe, 0xfd]) }))
    expect(invalidUtf8.delivered).toEqual([])
    expect(invalidUtf8.failures).toEqual([false])
  })

  it('reassembles a multi-fragment body exactly once', () => {
    const { instance, delivered, failures } = link()
    const body = JSON.stringify({ blob: 'x'.repeat(15000) })
    const frames = frame(body)
    expect(frames.length).toBeGreaterThan(1)
    for (const part of frames.slice(0, -1)) {
      instance.accept(part)
    }
    expect(delivered).toEqual([])
    instance.accept(frames.at(-1) as string)
    expect(delivered).toEqual([body])
    expect(failures).toEqual([])
  })

  it('requests recovery on an expired partial reassembly', () => {
    let now = 0
    const { instance, failures } = link({}, () => now)
    const frames = frame(JSON.stringify({ blob: 'y'.repeat(15000) }))
    instance.accept(frames[0] as string)
    now += 5000
    instance.expire()
    expect(failures).toEqual([true])
    expect(instance.isClosed()).toBe(true)
  })

  it('requests recovery when the reassembly byte budget is exceeded', () => {
    const { instance, failures } = link({ reassemblyBytes: 20000, maxMessageBytes: 20000 })
    const big = JSON.stringify({ blob: 'z'.repeat(15000) })
    // 第一条消息只发首片，先占满预算。
    instance.accept(frame(big)[0] as string)
    expect(failures).toEqual([])
    // 第二条消息的首片超出剩余预算 → resync → recover。
    instance.accept(frame(big)[0] as string)
    expect(failures).toEqual([true])
  })

  it('bounds each outbox batch and rotates unfinished packets fairly', () => {
    const { instance } = link({ sendBatchFrames: 2 })
    const body = JSON.stringify({ blob: 'w'.repeat(15000) })
    const totalFrames = carrierCount(encoder.encode(body).length)
    expect(totalFrames).toBeGreaterThan(2)
    expect(instance.offer(messageId(), body)).toBe(true)
    expect(instance.offer(messageId(), body)).toBe(true)

    const seen: string[] = []
    for (let round = 0; round < 8 && instance.hasPending(); round += 1) {
      const batch = instance.pollBatch()
      expect(batch).not.toBeNull()
      // 每批最多 sendBatchFrames 片。
      expect(batch?.frameCount()).toBeLessThanOrEqual(2)
      seen.push(batch?.messageId() as string)
      expect(instance.complete(batch, true)).toBe(true)
    }
    expect(instance.hasPending()).toBe(false)
    // 公平轮转：两条消息交替出现，而非先刷完第一条。
    expect(new Set(seen).size).toBe(2)
    expect(seen[0]).not.toBe(seen[1])
  })

  it('close releases every reserved byte and refuses further batches', () => {
    const { instance } = link({ sendBatchFrames: 1 })
    const body = JSON.stringify({ blob: 'v'.repeat(15000) })
    expect(instance.offer(messageId(), body)).toBe(true)
    expect(instance.pendingBytes()).toBeGreaterThan(0)
    instance.close()
    expect(instance.pendingBytes()).toBe(0)
    expect(instance.pollBatch()).toBeNull()
    expect(instance.isClosed()).toBe(true)
  })

  it('is inert after close: accept/expire/offer/complete/fail are all no-ops', () => {
    const { instance, delivered, failures } = link()
    instance.close()
    instance.accept(carrier('{"version":2,"type":"heartbeat"}'))
    instance.expire()
    expect(delivered).toEqual([])
    expect(failures).toEqual([])
    expect(instance.offer(messageId(), 'body')).toBe(false)
    expect(instance.complete(null, true)).toBe(false)
    expect(instance.peer()).toBeNull()
  })

  it('reports at most one failure even when a second violation arrives after teardown', () => {
    const { instance, failures } = link()
    instance.accept(carrier('x', { topic: 'other.topic' }))
    instance.accept(carrier('x', { topic: 'other.topic' }))
    expect(failures).toEqual([false])
  })

  it('rejects an oversized, malformed or non-string body at offer time', () => {
    const { instance } = link({ maxMessageBytes: 64 })
    expect(instance.offer(messageId(), 'y'.repeat(1000))).toBe(false)
    expect(instance.offer('not-a-uuid', 'ok')).toBe(false)
    expect(instance.offer(messageId(), 'ok')).toBe(true)
    expect(() => instance.offer(messageId(), new Uint8Array([1]))).toThrow()
  })
})
