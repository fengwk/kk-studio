import assert from 'node:assert/strict'
import test from 'node:test'
import { FramedEventSocket, eventUrl } from '../lib/framed-event-socket.mjs'
import {
  NotificationPacket,
  carrierChunk,
  carrierCount,
  decodeNotificationCarrier,
  defaultNotificationLimits,
} from '../../../../../frontend/src/shared/notification/notification.mjs'

const LIMITS = defaultNotificationLimits()
const SERVER_PUBLISHER = 'ffffffff-ffff-4fff-8fff-000000000001'
const encoder = new TextEncoder()
const decoder = new TextDecoder('utf-8', { fatal: true })
let seq = 0

function serverFrames(logical, { topic = 'app.events.v2', target = null } = {}) {
  seq += 1
  const packet = new NotificationPacket(
    SERVER_PUBLISHER,
    target,
    topic,
    `ffffffff-ffff-4fff-8fff-${String(seq).padStart(12, '0')}`,
    encoder.encode(JSON.stringify(logical)),
  )
  return Array.from({ length: carrierCount(packet.byteLength()) }, (_, index) =>
    carrierChunk(packet, index).encode())
}

/** Fake native socket whose frames are real shared carriers in both directions. */
class Socket extends EventTarget {
  readyState = 1
  sent = []
  send(physical) {
    this.sent.push(physical)
  }
  deliver(logical, options) {
    for (const raw of serverFrames(logical, options)) {
      this.dispatchEvent(new MessageEvent('message', { data: raw }))
    }
  }
  raw(data) {
    this.dispatchEvent(new MessageEvent('message', { data }))
  }
  close() {
    this.readyState = 3
    this.dispatchEvent(new Event('close'))
  }
}

function openFramed(url = 'http://a.test') {
  const created = []
  class CapturingSocket extends Socket {
    constructor() {
      super()
      created.push(this)
    }
  }
  const framed = new FramedEventSocket(eventUrl(url), { Socket: CapturingSocket })
  return { framed, socket: created[0] }
}

test('event URL normalizes both schemes', () => {
  assert.equal(eventUrl('https://b.test/base?x=1'), 'wss://b.test/api/events/v1')
})

test('send emits real shared carrier fragments carrying the v2 logical subscribe', () => {
  const { framed, socket } = openFramed()
  framed.subscribe({ kind: 'projects' })
  assert.equal(socket.sent.length, 1)
  // 物理帧是 canonical carrier；逻辑体是 v2 subscribe，绝非 raw JSON。
  const carrier = decodeNotificationCarrier(socket.sent[0], LIMITS, null)
  assert.ok(carrier)
  assert.equal(carrier.topic(), 'app.events.v2')
  assert.deepEqual(JSON.parse(decoder.decode(carrier.bytes())), {
    version: 2,
    type: 'subscribe',
    resource: { kind: 'projects' },
  })
  framed.close()
})

test('reassembles a multi-fragment logical frame through the shared reassembler', () => {
  const { framed, socket } = openFramed()
  const data = { blob: 'x'.repeat(20_000) }
  const frames = serverFrames({
    version: 2,
    type: 'event',
    resource: { kind: 'thread', id: '11111111-2222-4333-8444-555555555555' },
    name: 'realtime',
    data,
  })
  assert.ok(frames.length > 1)
  for (const raw of frames) socket.raw(raw)
  assert.equal(framed.frames.length, 1)
  assert.deepEqual(framed.frames[0].data, data)
  assert.equal(framed.failure, null)
  framed.close()
})

test('a raw non-carrier frame is a framing failure', () => {
  const { framed, socket } = openFramed()
  socket.raw(JSON.stringify({ version: 2, type: 'heartbeat' }))
  assert.ok(framed.failure)
  assert.throws(() => framed.check(), /invalid application event frame/)
  framed.close()
})

test('wrong topic and foreign target are framing failures', () => {
  const topicCase = openFramed()
  topicCase.socket.deliver({ version: 2, type: 'heartbeat' }, { topic: 'other.topic' })
  assert.ok(topicCase.framed.failure)
  topicCase.framed.close()

  const targetCase = openFramed()
  targetCase.socket.deliver(
    { version: 2, type: 'heartbeat' },
    { target: '99999999-9999-4999-8999-999999999999' },
  )
  assert.ok(targetCase.framed.failure)
  targetCase.framed.close()
})
