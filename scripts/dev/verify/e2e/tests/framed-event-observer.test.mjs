import assert from 'node:assert/strict'
import test from 'node:test'
import { FramedEventObserver } from '../lib/framed-event-observer.mjs'
import { APP_EVENTS_TOPIC } from '../../../../../frontend/src/shared/app-events/framed-link.mjs'
import {
  NotificationPacket,
  carrierChunk,
  carrierCount,
} from '../../../../../frontend/src/shared/notification/notification.mjs'

const BROWSER = '11111111-1111-4111-8111-000000000001'
const SERVER = '22222222-2222-4222-8222-000000000002'
const FOREIGN = '33333333-3333-4333-8333-000000000003'
const encoder = new TextEncoder()
let seq = 0

function messageId() {
  seq += 1
  return `44444444-4444-4444-8444-${String(seq).padStart(12, '0')}`
}

/** Real shared carriers for one logical body in the given direction. */
function frames(body, { publisher, target = null, topic = APP_EVENTS_TOPIC }) {
  const packet = new NotificationPacket(
    publisher,
    target,
    topic,
    messageId(),
    encoder.encode(body),
  )
  return Array.from({ length: carrierCount(packet.byteLength()) }, (_, index) =>
    carrierChunk(packet, index).encode())
}

/** Minimal stand-in for the Playwright WebSocket event surface. */
class FakePlaywrightSocket {
  #listeners = new Map()
  #url

  constructor(url = 'ws://test/api/events/v1') {
    this.#url = url
  }

  url() {
    return this.#url
  }

  on(type, listener) {
    let set = this.#listeners.get(type)
    if (set == null) {
      set = new Set()
      this.#listeners.set(type, set)
    }
    set.add(listener)
  }

  off(type, listener) {
    this.#listeners.get(type)?.delete(listener)
  }

  listenerCount(type) {
    return this.#listeners.get(type)?.size ?? 0
  }

  emit(type, event) {
    for (const listener of [...(this.#listeners.get(type) ?? [])]) {
      listener(event)
    }
  }

  sent(payload) {
    this.emit('framesent', { payload })
  }

  received(payload) {
    this.emit('framereceived', { payload })
  }
}

function observerOf() {
  const delivered = []
  const failures = []
  const observer = new FramedEventObserver({
    onFrame: (frame) => delivered.push(frame),
    onFailure: (recover) => failures.push(recover),
  })
  return { observer, delivered, failures }
}

test('recovers the browser publisher then reassembles count=1 and multi-fragment server frames', () => {
  const { observer, delivered, failures } = observerOf()
  const socket = new FakePlaywrightSocket()
  observer.observe(socket)

  // framesent 的合法 carrier 给出真实 browser publisher：observer 的 self。
  socket.sent(frames('{"version":2,"type":"subscribe"}', { publisher: BROWSER })[0])

  const big = { version: 2, type: 'event', blob: 'x'.repeat(20_000) }
  const parts = frames(JSON.stringify(big), { publisher: SERVER, target: BROWSER })
  assert.ok(parts.length > 1)
  for (const part of parts) socket.received(part)
  // count=1 同样走共享重组器（非旁路）。
  socket.received(frames('{"version":2,"type":"subscribed"}', { publisher: SERVER, target: BROWSER })[0])

  assert.deepEqual(delivered, [big, { version: 2, type: 'subscribed' }])
  assert.deepEqual(failures, [])
  observer.close()
})

test('a foreign server publisher or target is a fatal observation failure', () => {
  const { observer, failures } = observerOf()
  const socket = new FakePlaywrightSocket()
  observer.observe(socket)
  socket.sent(frames('{"version":2,"type":"subscribe"}', { publisher: BROWSER })[0])

  // peer 变化：server 冻结后另一 publisher 的帧是终态协议错误。
  socket.received(frames('{"version":2,"type":"heartbeat"}', { publisher: SERVER, target: BROWSER })[0])
  socket.received(frames('{"version":2,"type":"heartbeat"}', { publisher: FOREIGN, target: BROWSER })[0])
  assert.deepEqual(failures, [false])

  // 错误 target 同样由共享 link 的 self 校验拒绝。
  const wrongTarget = observerOf()
  const other = new FakePlaywrightSocket()
  wrongTarget.observer.observe(other)
  other.sent(frames('{"version":2,"type":"subscribe"}', { publisher: BROWSER })[0])
  other.received(frames('{"version":2,"type":"heartbeat"}', { publisher: SERVER, target: FOREIGN })[0])
  assert.deepEqual(wrongTarget.failures, [false])

  observer.close()
  wrongTarget.observer.close()
})

test('close detaches every listener and releases the shared link', () => {
  const { observer, delivered } = observerOf()
  const socket = new FakePlaywrightSocket()
  observer.observe(socket)
  assert.equal(socket.listenerCount('framesent'), 1)
  assert.equal(socket.listenerCount('framereceived'), 1)
  assert.equal(socket.listenerCount('close'), 1)

  socket.sent(frames('{"version":2,"type":"subscribe"}', { publisher: BROWSER })[0])
  socket.received(frames('{"version":2,"type":"heartbeat"}', { publisher: SERVER, target: BROWSER })[0])
  observer.close()

  assert.equal(socket.listenerCount('framesent'), 0)
  assert.equal(socket.listenerCount('framereceived'), 0)
  assert.equal(socket.listenerCount('close'), 0)
  socket.received(frames('{"version":2,"type":"heartbeat"}', { publisher: SERVER, target: BROWSER })[0])
  assert.equal(delivered.length, 1)

  // socket 自身 close 事件同样解绑；显式 close 幂等。
  const auto = observerOf()
  const autoSocket = new FakePlaywrightSocket()
  auto.observer.observe(autoSocket)
  autoSocket.emit('close', {})
  assert.equal(autoSocket.listenerCount('framereceived'), 0)
  auto.observer.close()

  // 未 close 时重复 observe 明确抛错。
  const double = observerOf()
  const doubleSocket = new FakePlaywrightSocket()
  double.observer.observe(doubleSocket)
  assert.throws(() => double.observer.observe(doubleSocket), /already attached/)
  double.observer.close()
})
