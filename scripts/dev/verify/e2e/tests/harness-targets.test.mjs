import assert from 'node:assert/strict'
import test from 'node:test'

import {
  assertRootYoloEnabled,
  listEnvironments,
  threadParentIdOf,
  threadIdOf,
  threadYoloOf,
} from '../lib/harness.mjs'
import { cid } from '../lib/http.mjs'

const sampleId = () => '00000000-0000-4000-8000-000000000000'

test('threadParentIdOf/threadIdOf enforce the immutable execution parent relation', () => {
  // 测试意图：Thread 的执行父关系是不可变事实（根为 null、其余为 canonical UUID），且不再物化进
  // ROOT payload。委派链路断言必须靠这个字段，所以 helper 要拒绝缺失字段，根也必须显式返回 null。
  const threadId = '00000000-0000-4000-8000-000000000000'
  const base = {
    threadId,
    sessionId: '11111111-1111-4111-8111-111111111111',
    headEntryId: '22222222-2222-4222-8222-222222222222',
    name: 'main',
    rootThreadId: threadId,
    yoloEnabled: false,
    nextCommandSequence: '1',
    version: '0',
    status: 'IDLE',
    executionControl: 'RUNNABLE',
  }

  assert.equal(threadParentIdOf({ ...base, parentThreadId: null }), null)
  assert.throws(() => threadParentIdOf(base), /canonical UUID/)
  const parentThreadId = '33333333-3333-4333-8333-333333333333'
  const child = { ...base, parentThreadId, rootThreadId: parentThreadId }
  assert.equal(threadParentIdOf(child), parentThreadId)
  assert.equal(threadIdOf(child), threadId)

  for (const invalid of [undefined, 'not-uuid', '33333333333343338333333333333333', '']) {
    assert.throws(() => threadParentIdOf({ ...base, parentThreadId: invalid }), /canonical UUID/)
    assert.throws(() => threadIdOf({ ...child, parentThreadId: invalid }), /canonical UUID/)
  }
})

test('threadIdOf accepts the four runtime states independently of execution control', () => {
  // 退出等待是独立状态，STOPPED 不等于物理退出；严格接纳四态与执行控制的八种合法组合。
  const threadId = '00000000-0000-4000-8000-000000000000'
  const base = {
    threadId,
    sessionId: '11111111-1111-4111-8111-111111111111',
    headEntryId: '22222222-2222-4222-8222-222222222222',
    parentThreadId: null,
    name: 'main',
    rootThreadId: threadId,
    yoloEnabled: false,
    nextCommandSequence: '1',
    version: '0',
  }
  for (const status of ['RUNNING', 'EXIT_PENDING', 'WAITING', 'IDLE']) {
    for (const executionControl of ['RUNNABLE', 'STOPPED']) {
      assert.equal(
        threadIdOf({ ...base, status, executionControl }),
        threadId,
        `status=${status} executionControl=${executionControl}`,
      )
    }
  }

  for (const status of [
    'QUEUED',
    'STOPPED',
    'CONTINUATION_DUE',
    'MODEL_READY',
    'MODEL_RUNNING',
    'APPLYING',
    'TOOL_WAITING_APPROVAL',
    'TOOL_WAITING_INPUT',
    'TOOL_RUNNING',
    'TOOL_READY',
    'PROCESSING',
  ]) {
    assert.throws(
      () => threadIdOf({ ...base, status, executionControl: 'RUNNABLE' }),
      /unknown ThreadRuntimeStatus/,
      `invalid status ${status} must be rejected`,
    )
  }
})

test('thread YOLO wire is an explicit boolean with an immutable execution root', () => {
  // 测试意图：Thread 投影直接携带 yoloEnabled/rootThreadId；根必须自指且可开关，
  // 子代理必须指向执行根且自身开关恒为 false，缺失字段不得兜底为 false。
  const rootThreadId = '33333333-3333-4333-8333-333333333333'
  const rootThread = {
    threadId: sampleId(),
    sessionId: '11111111-1111-4111-8111-111111111111',
    headEntryId: '22222222-2222-4222-8222-222222222222',
    parentThreadId: null,
    name: 'main',
    rootThreadId: sampleId(),
    yoloEnabled: false,
    nextCommandSequence: '1',
    version: '0',
    status: 'IDLE',
    executionControl: 'RUNNABLE',
  }
  const childThread = {
    ...rootThread,
    threadId: rootThreadId,
    parentThreadId: rootThread.threadId,
    rootThreadId: rootThread.threadId,
  }

  assert.equal(assertRootYoloEnabled(rootThread, false), false)
  assert.equal(threadYoloOf(rootThread), false)
  assert.equal(threadYoloOf(childThread), false)

  assert.throws(() => assertRootYoloEnabled(rootThread, true), /yoloEnabled false != true/)
  assert.throws(() => assertRootYoloEnabled(childThread, false), /must be a root thread/)
  assert.throws(() => threadYoloOf({ ...rootThread, yoloEnabled: undefined }), /explicit boolean/)
  assert.throws(() => threadYoloOf({ ...rootThread, rootThreadId: undefined }), /canonical UUID/)
  assert.throws(() => threadYoloOf({ ...rootThread, parentThreadId: undefined }), /canonical UUID/)
  assert.throws(() => threadYoloOf({ ...rootThread, parentThreadId: 'not-uuid' }), /canonical UUID/)
  assert.throws(
    () => threadYoloOf({ ...rootThread, rootThreadId: rootThreadId }),
    /root thread itself/,
  )
  assert.throws(
    () => threadYoloOf({ ...childThread, yoloEnabled: true }),
    /must be false for a child thread/,
  )
  assert.throws(() => threadIdOf({ ...rootThread, yoloEnabled: undefined }), /explicit boolean/)
})

test('the Project session route and its helper are gone from the lib surface', async () => {
  // Test intent: 项目级 Session 路由已随 Coordinator 移除，lib 不得保留过期 helper 或端点文档。
  const { readFile } = await import('node:fs/promises')
  const source = await readFile(new URL('../lib/harness.mjs', import.meta.url), 'utf8')
  assert.doesNotMatch(source, /listProjectSessions/)
  assert.doesNotMatch(source, /projects\/\$\{[^}]*\}\/sessions/)
})
test('environment status deadline is required and explicitly null while offline', async () => {
  // 截止时间是唯一状态时间投影：无连接不能省略字段，也不能携带有效截止时间。
  const environment = {
    id: cid(), name: 'offline', version: '0', ready: false, status: 'OFFLINE', statusExpiresAt: null,
  }
  const ctx = (data) => ({ call: async () => ({ json: [data] }) })
  assert.deepEqual(await listEnvironments(ctx(environment)), [environment])
  const { statusExpiresAt: _deadline, ...missing } = environment
  await assert.rejects(() => listEnvironments(ctx(missing)), /statusExpiresAt/)
  await assert.rejects(
    () => listEnvironments(ctx({ ...environment, statusExpiresAt: Date.parse('2026-10-07T00:00:00Z') })),
    /OFFLINE/,
  )
})
