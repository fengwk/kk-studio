import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { expectStorage } from '../ui/composer-matrix.mjs'
import { waitForThreadSubscription } from '../ui/workspace-contracts.mjs'

test('workspace observer requires a thread ack, ignoring connected and other resources', async () => {
  const page = new EventEmitter()
  const threadId = '11111111-1111-4111-8111-111111111111'
  const socket = (path) => Object.assign(new EventEmitter(), {
    url: () => `ws://127.0.0.1${path}`,
  })
  const pending = waitForThreadSubscription(page, threadId)
  let resolved = false
  pending.then(() => { resolved = true })
  const other = socket('/api/other')
  const current = socket('/ws/events')
  page.emit('websocket', other)
  page.emit('websocket', current)
  const ack = { type: 'subscribed', resource: { kind: 'thread', id: threadId }, cursor: '0' }
  const emit = (target, frame) => target.emit('framereceived', { payload: JSON.stringify(frame) })
  emit(other, ack)
  emit(current, { type: 'connected', eventNodeId: threadId,
    connectionId: '22222222-2222-4222-8222-222222222222' })
  emit(current, { ...ack, resource: { kind: 'canvas', id: threadId } })
  await Promise.resolve()
  assert.equal(resolved, false)
  emit(current, ack)
  await pending
  assert.equal(page.listenerCount('websocket'), 0)
  assert.equal(current.listenerCount('framereceived'), 0)
})

test('composer storage observer retains production draft schema', async () => {
  const source = readFileSync(new URL('../../../../../frontend/src/features/thread/composer/composer-draft.ts', import.meta.url), 'utf8')
  assert.match(source, /JSON\.stringify\(\{\s*parts:/)
  const original = globalThis.localStorage
  let payload = { parts: [{ type: 'text', text: 'draft' }] }
  globalThis.localStorage = {
    getItem: () => JSON.stringify(payload),
  }
  const page = {
    async waitForFunction(predicate, input) { assert.equal(predicate(input), true) },
    async evaluate(predicate, input) { return predicate(input) },
  }
  try {
    await expectStorage(page, 'draft', 'draft')
    payload = { parts: [{ type: 'text', text: 'draft' }], extra: 1 }
    await assert.rejects(expectStorage(page, 'draft', 'draft'), /localStorage mismatch/)
  } finally {
    if (original === undefined) delete globalThis.localStorage
    else globalThis.localStorage = original
  }
})

test('thread draft observer waits for committed text instead of accepting an async false', async () => {
  const original = globalThis.indexedDB
  const threadId = '11111111-1111-4111-8111-111111111111'
  let storedText = 'older'
  let reads = 0
  let closed = 0
  const database = {
    transaction: () => ({
      objectStore: () => ({
        get: (id) => {
          assert.equal(id, threadId)
          reads += 1
          const request = { result: {
            threadId, parts: [{ type: 'text', partId: 'part', text: storedText }],
            appliedStopRequestIds: [],
          } }
          queueMicrotask(() => request.onsuccess())
          return request
        },
      }),
    }),
    close: () => { closed += 1 },
  }
  globalThis.indexedDB = {
    open: () => {
      const request = { result: database }
      queueMicrotask(() => request.onsuccess())
      return request
    },
  }
  const page = {
    // 与 Playwright 一样，Promise 的 truthiness 不代表它最终返回 true。
    async waitForFunction(predicate, input) {
      const result = predicate(input)
      assert.ok(result)
      return result
    },
    async evaluate(predicate, input) { return predicate(input) },
  }
  try {
    let settled = false
    const pending = expectStorage(page, { threadId }, 'latest').then(() => { settled = true })
    await new Promise(setImmediate)
    assert.equal(settled, false, 'an older committed draft must not release the reload barrier')
    storedText = 'latest'
    await pending
    assert.ok(reads >= 2)
    assert.equal(closed, reads)
  } finally {
    if (original === undefined) delete globalThis.indexedDB
    else globalThis.indexedDB = original
  }
})
