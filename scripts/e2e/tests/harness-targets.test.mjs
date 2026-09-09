import assert from 'node:assert/strict'
import test from 'node:test'

import {
  canonicalUuid,
  chatOwner,
  newSessionTarget,
  newThreadTarget,
  threadTarget,
} from '../lib/harness.mjs'
import { cid } from '../lib/http.mjs'

const sampleId = () => '00000000-0000-4000-8000-000000000000'

test('newThreadTarget builds the sealed NEW_THREAD wire target with exactly five keys', () => {
  // Test intent: the NEW_THREAD wire shape is sealed — no alias token, no name input.
  const sessionId = cid()
  const startEntryId = cid()
  const threadId = cid()
  const target = newThreadTarget({
    sessionId,
    startEntryId,
    threadId,
    yoloEnabled: true,
  })
  assert.equal(target.type, 'NEW_THREAD')
  assert.deepEqual(Object.keys(target).sort(), [
    'sessionId',
    'startEntryId',
    'threadId',
    'type',
    'yoloEnabled',
  ])
  assert.equal(target.sessionId, sessionId)
  assert.equal(target.startEntryId, startEntryId)
  assert.equal(target.threadId, threadId)
  assert.equal(target.yoloEnabled, true)
  assert.equal(Object.hasOwn(target, 'name'), false, 'NEW_THREAD must not accept a name input')
  assert.equal(Object.hasOwn(target, 'rootSettings'), false)
})

test('newThreadTarget defaults yoloEnabled to false and validates canonical ids', () => {
  // Test intent: NEW_SESSION/THREAD shape and the boolean default must remain strict.
  const target = newThreadTarget({
    sessionId: sampleId(),
    startEntryId: sampleId(),
    threadId: sampleId(),
  })
  assert.equal(target.yoloEnabled, false)
  assert.deepEqual(
    Object.keys(
      newSessionTarget({ sessionId: sampleId(), threadId: sampleId(), rootSettings: { a: 1 } }),
    ).sort(),
    ['rootSettings', 'sessionId', 'threadId', 'type', 'yoloEnabled'],
  )
  assert.deepEqual(
    Object.keys(
      threadTarget({
        threadId: sampleId(),
        expectedHeadEntryId: sampleId(),
        expectedNextCommandSequence: '1',
      }),
    ).sort(),
    ['expectedHeadEntryId', 'expectedNextCommandSequence', 'threadId', 'type'],
  )
  assert.throws(
    () => newThreadTarget({ sessionId: 'not-uuid', startEntryId: cid(), threadId: cid() }),
    /canonical UUID/,
  )
})

test('legacy ENTRY target tokens and helpers are no longer part of the lib API', async () => {
  // Test intent: old ENTRY/ENTRY_DRAFT wire vocabulary must not regress into the script surface.
  const { readFile } = await import('node:fs/promises')
  const sourceUrl = new URL('../lib/harness.mjs', import.meta.url)
  const source = await readFile(sourceUrl, 'utf8')
  assert.doesNotMatch(source, /\bENTRY\b/)
  assert.doesNotMatch(source, /entryTarget|createEntryThread/)
})

test('chatOwner/canonicalUuid keep canonical UUID owner identity', () => {
  // Test intent: owner discriminator stays CHAT with a canonical UUID id.
  const id = cid()
  assert.deepEqual(chatOwner(id), { type: 'CHAT', id })
  assert.equal(canonicalUuid(id, 'id'), id)
  assert.throws(() => chatOwner('bad'), /canonical UUID/)
})
