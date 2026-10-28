import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { expectStorage } from '../ui/composer-matrix.mjs'
import { waitForThreadSubscription } from '../ui/workspace-contracts.mjs'

test('workspace observer requires a v2 thread ack, ignoring connected and other resources', async () => {
  const page = new EventEmitter()
  const threadId = '11111111-1111-4111-8111-111111111111'
  const socket = (path) => Object.assign(new EventEmitter(), {
    url: () => `ws://127.0.0.1${path}`,
  })
  const pending = waitForThreadSubscription(page, threadId)
  let resolved = false
  pending.then(() => { resolved = true })
  const old = socket('/api/events/v1')
  const current = socket('/api/events/v2')
  page.emit('websocket', old)
  page.emit('websocket', current)
  const ack = { version: 2, type: 'subscribed', resource: { kind: 'thread', id: threadId }, cursor: '0' }
  const emit = (target, frame) => target.emit('framereceived', { payload: JSON.stringify(frame) })
  emit(old, ack)
  emit(current, { version: 2, type: 'connected', eventNodeId: threadId,
    connectionId: '22222222-2222-4222-8222-222222222222' })
  emit(current, { ...ack, version: 1 })
  emit(current, { ...ack, resource: { kind: 'canvas', id: threadId } })
  await Promise.resolve()
  assert.equal(resolved, false)
  emit(current, ack)
  await pending
  assert.equal(page.listenerCount('websocket'), 0)
  assert.equal(current.listenerCount('framereceived'), 0)
})

test('composer storage observer retains production draft v1, independently of event wire v2', async () => {
  const source = readFileSync(new URL('../../../../../frontend/src/features/chat/composer/composer-draft.ts', import.meta.url), 'utf8')
  assert.match(source, /JSON\.stringify\(\{\s*version: 1,\s*parts:/)
  const original = globalThis.localStorage
  let version = 1
  globalThis.localStorage = {
    getItem: () => JSON.stringify({ version, parts: [{ type: 'text', text: 'draft' }] }),
  }
  const page = {
    async waitForFunction(predicate, input) { assert.equal(predicate(input), true) },
    async evaluate(predicate, input) { return predicate(input) },
  }
  try {
    await expectStorage(page, 'draft', 'draft')
    version = 2
    await assert.rejects(expectStorage(page, 'draft', 'draft'), /localStorage mismatch/)
  } finally {
    if (original === undefined) delete globalThis.localStorage
    else globalThis.localStorage = original
  }
})
