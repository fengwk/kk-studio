import assert from 'node:assert/strict'
import test from 'node:test'

import {
  acceptCommandBatch,
  canonicalUuid,
  chatOwner,
  newSessionTarget,
  newThreadTarget,
  threadParentIdOf,
  threadIdOf,
  threadTarget,
  userMessageCommand,
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

test('threadParentIdOf/threadIdOf enforce the immutable execution parent relation', () => {
  // 测试意图：Thread 的执行父关系是不可变事实（根为 null、其余为 canonical UUID），且不再物化进
  // ROOT payload。委派链路断言必须靠这个字段，所以 helper 要拒绝缺失字段，根也必须显式返回 null。
  const threadId = '00000000-0000-4000-8000-000000000000'
  const base = {
    threadId,
    sessionId: '11111111-1111-4111-8111-111111111111',
    headEntryId: '22222222-2222-4222-8222-222222222222',
    name: 'main',
    nextCommandSequence: '1',
    version: '0',
  }

  assert.equal(threadParentIdOf({ ...base, parentThreadId: null }), null)
  assert.throws(() => threadParentIdOf(base), /canonical UUID/)
  const parentThreadId = '33333333-3333-4333-8333-333333333333'
  assert.equal(threadParentIdOf({ ...base, parentThreadId }), parentThreadId)
  assert.equal(threadIdOf({ ...base, parentThreadId }), threadId)

  for (const invalid of [undefined, 'not-uuid', '33333333333343338333333333333333', '']) {
    assert.throws(() => threadParentIdOf({ ...base, parentThreadId: invalid }), /canonical UUID/)
    assert.throws(() => threadIdOf({ ...base, parentThreadId: invalid }), /canonical UUID/)
  }
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

test('acceptCommandBatch rejects the removed PROJECT owner before any HTTP call', async () => {
  // Test intent: Project 不再有 Coordinator Session/命令入口，helper 必须在本地就拒绝 PROJECT owner，
  // 不能把已删除的 owner 判别式重新放行给后端。
  const calls = []
  const ctx = {
    call: async (...args) => {
      calls.push(args)
      throw new Error('unexpected HTTP call')
    },
  }
  await assert.rejects(
    () =>
      acceptCommandBatch(ctx, {
        owner: { type: 'PROJECT', id: sampleId() },
        target: newSessionTarget({ sessionId: sampleId(), threadId: sampleId(), rootSettings: {} }),
        commands: [userMessageCommand('plan', sampleId())],
      }),
    /owner\.type must be CHAT\|CANVAS/,
  )
  assert.equal(calls.length, 0, 'invalid owner must not reach the HTTP client')
})

test('the Project session route and its helper are gone from the lib surface', async () => {
  // Test intent: 项目级 Session 路由已随 Coordinator 移除，lib 不得保留过期 helper 或端点文档。
  const { readFile } = await import('node:fs/promises')
  const source = await readFile(new URL('../lib/harness.mjs', import.meta.url), 'utf8')
  assert.doesNotMatch(source, /listProjectSessions/)
  assert.doesNotMatch(source, /projects\/\$\{[^}]*\}\/sessions/)
})
