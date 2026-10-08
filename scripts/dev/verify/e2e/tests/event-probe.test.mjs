import assert from 'node:assert/strict'
import test from 'node:test'
import { EventProbe, eventUrl, validateFrame, withCleanup } from '../lib/event-probe.mjs'
import { ALL_CASES } from '../lib/registry.mjs'
import '../cases/distributed-events.mjs'

const id = '11111111-1111-4111-8111-111111111111'
const changed = { version: 1, type: 'event', resource: { kind: 'projects' }, name: 'changed', data: { projectId: id } }

/** Only a helper unit fake: no simulated transport is used as real E2E evidence. */
class Socket extends EventTarget {
  readyState = 1
  registered = new Set()
  addEventListener(type, listener) {
    this.registered.add(listener)
    super.addEventListener(type, listener)
  }
  removeEventListener(type, listener) {
    this.registered.delete(listener)
    super.removeEventListener(type, listener)
  }
  message(value) {
    this.dispatchEvent(new MessageEvent('message', { data: value }))
  }
  send(text) {
    const frame = JSON.parse(text)
    if (frame.type === 'subscribe') this.message(JSON.stringify({
      version: 1, type: 'subscribed', resource: frame.resource, cursor: '0',
    }))
  }
  close() {
    this.readyState = 3
    this.dispatchEvent(new Event('close'))
  }
}

test('event URL normalizes both schemes and removes query/hash', () => {
  assert.equal(eventUrl('https://b.test/base?x=1#fragment'), 'wss://b.test/api/events/v1')
  assert.equal(eventUrl('http://a.test/'), 'ws://a.test/api/events/v1')
})

test('strict v1 frames reject malformed coordinates, extra fields and wrong resource shapes', () => {
  const canvas = { version: 1, type: 'event', resource: { kind: 'canvas', id }, name: 'revision', cursor: '1', data: { revision: '1' } }
  const environment = { version: 1, type: 'event', resource: { kind: 'environments' }, name: 'changed', data: {} }
  for (const frame of [changed, canvas, environment, { version: 1, type: 'heartbeat' },
    { version: 1, type: 'resync', resource: { kind: 'projects' } }]) assert.doesNotThrow(() => validateFrame(frame))
  for (const frame of [
    null, [], { ...changed, version: 2 }, { ...changed, cursor: '0' },
    { ...changed, data: { projectId: 'bad' } }, { ...changed, resource: { kind: 'projects', id } },
    { ...canvas, cursor: '01' }, { ...canvas, data: { revision: '2' } },
    { ...environment, data: { environmentId: id } },
    { version: 1, type: 'subscribed', resource: { kind: 'projects' }, cursor: '1' },
    { version: 1, type: 'error', code: 'SEND_FAILED', message: 'failed' },
  ]) assert.throws(() => validateFrame(frame))
})

test('capture is append-only, synchronous hooks see pre-HTTP arrivals and duplicates remain countable', async () => {
  const probe = new EventProbe('http://a.test', Socket)
  await probe.subscribe({ kind: 'projects' })
  const start = probe.frames.length
  const seen = []
  probe.hooks.add((frame) => seen.push(frame))
  probe.socket.message(JSON.stringify(changed))
  probe.socket.message(JSON.stringify(changed))
  assert.equal(seen.length, 2)
  assert.equal(probe.count((f) => f.type === 'event', start), 2)
  assert.equal(probe.frames.length, 3)
  await probe.close()
  assert.equal(probe.hooks.size, 0)
  assert.equal(probe.socket.registered.size, 0)
})

test('invalid JSON is fatal and failure cleanup releases listeners and hooks', async () => {
  const probe = new EventProbe('http://a.test', Socket)
  probe.hooks.add(() => {})
  probe.socket.message('not json')
  await assert.rejects(() => probe.until(() => true), /invalid application event frame/)
  await assert.rejects(() => probe.close(), /invalid application event frame/)
  assert.equal(probe.socket.readyState, 3)
  assert.equal(probe.socket.registered.size, 0)
  assert.equal(probe.hooks.size, 0)
})

test('unexpected close, socket error and bounded timeout fail rather than produce success', async () => {
  const closed = new EventProbe('http://a.test', Socket)
  closed.socket.close()
  await assert.rejects(() => closed.until(() => false), /closed unexpectedly/)
  await closed.close()
  const failed = new EventProbe('http://a.test', Socket)
  failed.socket.dispatchEvent(new Event('error'))
  await assert.rejects(() => failed.until(() => true), /socket error/)
  await assert.rejects(() => failed.close(), /socket error/)
  assert.equal(failed.socket.registered.size, 0)
  const timeout = new EventProbe('http://a.test', Socket)
  await assert.rejects(() => timeout.until(() => false, 1), /timed out/)
  await timeout.close()
})

test('cleanup always continues and preserves the primary failure as cause', async () => {
  const primary = new Error('assertion failed')
  const cleanupError = new Error('restore failed')
  const operations = []
  await assert.rejects(() => withCleanup(async () => { throw primary }, [
    () => { operations.push('restore'); throw cleanupError },
    () => { operations.push('close') },
    () => { operations.push('delete') },
  ]), (error) => {
    assert.equal(error.cause, primary)
    assert.deepEqual(error.errors, [primary, cleanupError])
    return true
  })
  assert.deepEqual(operations, ['restore', 'close', 'delete'])
  await assert.rejects(() => withCleanup(async () => {}, [() => { throw cleanupError }]), /cleanup failed/)
})

test('four notification cases require distributed only and fail without node context', async () => {
  const ids = ['projects_notifications', 'canvas_environment_notifications', 'notification_subscription_lifecycle', 'notification_db_recovery']
  for (const suffix of ids) {
    const entry = ALL_CASES.find((c) => c.id === `distributed.${suffix}`)
    assert.ok(entry)
    assert.equal(entry.level, 'L5')
    assert.deepEqual([...entry.requires], ['distributed'])
    await assert.rejects(() => entry.run({ caseId: entry.id }), /requires the distributed capability/)
  }
})

test('case rejects duplicate events delivered before create HTTP returns and still deletes fixture', async () => {
  const original = globalThis.WebSocket
  const sockets = []
  globalThis.WebSocket = class extends Socket {
    constructor() { super(); sockets.push(this) }
  }
  let fixture
  let deleted = false
  let immediateReads = 0
  const artifacts = []
  const ctx = {
    baseUrls: { a: 'http://a.test', b: 'http://b.test' },
    writeArtifact(name, text) { artifacts.push({ name, value: JSON.parse(text) }) },
    async callNode(node, method, path, body) {
      if (method === 'POST') {
        fixture = { id, version: '0', title: body.title }
        for (const socket of sockets) {
          socket.message(JSON.stringify(changed))
          socket.message(JSON.stringify(changed))
        }
        assert.equal(immediateReads, 4, 'reads must start during message dispatch, before create resolves')
      }
      if (method === 'GET') immediateReads++
      if (method === 'DELETE') deleted = true
      return { json: { data: fixture } }
    },
  }
  try {
    const entry = ALL_CASES.find((c) => c.id === 'distributed.projects_notifications')
    await assert.rejects(() => entry.run(ctx), /duplicate create notification/)
    assert.equal(deleted, true)
    assert.ok(sockets.every((socket) => socket.registered.size === 0 && socket.readyState === 3))
    const captured = artifacts.find((item) => item.name === 'ws-frames.json')
    assert.ok(captured)
    assert.ok(captured.value.every((connection) => connection.frames.filter((f) => f.type === 'event').length === 2))
  } finally {
    globalThis.WebSocket = original
  }
})

test('DB-fault case restores network, closes sockets and deletes fixture even if mutation and restore both fail', async () => {
  const original = globalThis.WebSocket
  const sockets = []
  globalThis.WebSocket = class extends Socket {
    constructor() { super(); sockets.push(this) }
  }
  const primary = new Error('B commit failed')
  const restoreError = new Error('network restore failed')
  const commands = []
  let deleted = false
  const ctx = {
    baseUrls: { a: 'http://a.test', b: 'http://b.test' },
    writeArtifact() {},
    runDistributedCommand(command) {
      commands.push(command)
      if (command === 'reconnect-db-a') throw restoreError
    },
    async callNode(node, method) {
      if (method === 'POST') for (const socket of sockets) socket.message(JSON.stringify(changed))
      if (method === 'PUT') throw primary
      if (method === 'DELETE') deleted = true
      return { json: { data: { id, version: '0' } } }
    },
  }
  try {
    const entry = ALL_CASES.find((c) => c.id === 'distributed.notification_db_recovery')
    await assert.rejects(() => entry.run(ctx), (error) => {
      assert.equal(error.cause, primary)
      assert.deepEqual(error.errors, [primary, restoreError])
      return true
    })
    assert.deepEqual(commands, ['disconnect-db-a', 'reconnect-db-a'])
    assert.equal(deleted, true)
    assert.ok(sockets.every((socket) => socket.registered.size === 0 && socket.readyState === 3))
  } finally {
    globalThis.WebSocket = original
  }
})
