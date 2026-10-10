import assert from 'node:assert/strict'
import test from 'node:test'

import * as harnessModule from '../lib/harness.mjs'
import {
  acceptCommandBatch,
  assertRootYoloPolicy,
  assertThreadYoloPolicy,
  canonicalUuid,
  chatOwner,
  createChat,
  listEnvironments,
  listSessionThreads,
  newSessionTarget,
  newThreadTarget,
  threadParentIdOf,
  threadIdOf,
  threadTarget,
  userMessageCommand,
} from '../lib/harness.mjs'
import { cid } from '../lib/http.mjs'

const sampleId = () => '00000000-0000-4000-8000-000000000000'

test('harness owner helpers expose only the Chat owner shape', async () => {
  // Test intent: Canvas 不持有 Harness Session（公共 batch 端点只服务 CHAT），
  // 因此 canvasOwner 死代码与其 /sessions 查询 helper 必须保持删除状态。
  const chat = chatOwner(sampleId())
  assert.deepEqual(chat, { type: 'CHAT', chatId: sampleId() })
  assert.equal(Object.hasOwn(harnessModule, 'canvasOwner'), false)
  assert.equal(Object.hasOwn(harnessModule, 'listCanvasSessions'), false)
  await assert.rejects(
    () =>
      acceptCommandBatch(
        { call: async () => ({ status: 202, json: { data: {} } }) },
        {
          owner: { type: 'CANVAS', id: sampleId() },
          target: newSessionTarget({ sessionId: sampleId(), threadId: sampleId(), rootSettings: {} }),
          commands: [userMessageCommand('x', sampleId())],
        },
      ),
    /owner\.type must be CHAT/,
  )
})

test('newThreadTarget builds the sealed NEW_THREAD wire target with exactly six keys', () => {
  // Test intent: the NEW_THREAD wire shape is sealed — no alias token, and the branch display name is a
  // required, caller-supplied creation input (never a server-derived default).
  const sessionId = cid()
  const startEntryId = cid()
  const threadId = cid()
  const target = newThreadTarget({
    sessionId,
    startEntryId,
    threadId,
    threadName: 'branch',
    yoloEnabled: true,
  })
  assert.equal(target.type, 'NEW_THREAD')
  assert.deepEqual(Object.keys(target).sort(), [
    'sessionId',
    'startEntryId',
    'threadId',
    'threadName',
    'type',
    'yoloEnabled',
  ])
  assert.equal(target.sessionId, sessionId)
  assert.equal(target.startEntryId, startEntryId)
  assert.equal(target.threadId, threadId)
  assert.equal(target.threadName, 'branch')
  assert.equal(target.yoloEnabled, true)
  assert.equal(Object.hasOwn(target, 'name'), false, 'NEW_THREAD must not accept the legacy name input')
  assert.equal(Object.hasOwn(target, 'rootSettings'), false)
})

test('newThreadTarget defaults yoloEnabled to false and validates canonical ids', () => {
  // Test intent: NEW_SESSION/THREAD shape, the boolean default and canonical ids must remain strict.
  const target = newThreadTarget({
    sessionId: sampleId(),
    startEntryId: sampleId(),
    threadId: sampleId(),
    threadName: 'branch',
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
    () =>
      newThreadTarget({
        sessionId: 'not-uuid',
        startEntryId: cid(),
        threadId: cid(),
        threadName: 'branch',
      }),
    /canonical UUID/,
  )
})

test('newThreadTarget requires a non-blank threadName and normalizes it like the server', () => {
  // 测试意图：分支名是创建请求身份的一部分，必须非空且按 Names 规则折叠空白、去首尾；
  // 空白名在 helper 层就被拒绝，不会让服务端替调用方猜测展示名。
  const base = { sessionId: sampleId(), startEntryId: sampleId(), threadId: sampleId() }
  assert.equal(
    newThreadTarget({ ...base, threadName: '  分  支\n名称  ' }).threadName,
    '分 支 名称',
  )
  for (const threadName of [undefined, null, '', '   ', '\t\n']) {
    assert.throws(() => newThreadTarget({ ...base, threadName }), /threadName/)
  }
  assert.throws(() => newThreadTarget({ ...base, threadName: 42 }), /threadName/)
  // 上限按 Unicode 码点计数（256 合法、257 拒绝），超长绝不静默截断。
  assert.equal(newThreadTarget({ ...base, threadName: 'a'.repeat(256) }).threadName.length, 256)
  assert.throws(() => newThreadTarget({ ...base, threadName: 'a'.repeat(257) }), /256/)
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
    yoloPolicy: { mode: 'DISABLE', rootThreadId: null },
    nextCommandSequence: '1',
    version: '0',
    status: 'IDLE',
    processing: false,
    executionControl: 'RUNNABLE',
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

test('Session summaries preserve same-named root and child parent identities', async () => {
  // 摘要 helper 不按名称去重，也不过滤子节点；根 null 和子 canonical UUID 均可通过契约检查。
  const root = {
    threadId: sampleId(),
    parentThreadId: null,
    name: 'same name',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    status: 'IDLE',
    processing: false,
    model: { providerName: 'provider', modelName: 'model', variant: 'default' },
    headMessagePreview: null,
  }
  const child = { ...root, threadId: cid(), parentThreadId: root.threadId }
  const ctx = {
    call: async (method, path) => {
      assert.equal(method, 'GET')
      assert.equal(path, `/api/harness/sessions/${sampleId()}/threads`)
      return { json: { data: [root, child] } }
    },
  }
  assert.deepEqual(await listSessionThreads(ctx, sampleId()), [root, child])

  const missingParent = { ...root }
  delete missingParent.parentThreadId
  const invalidParents = [missingParent, ...[
    undefined, 42, '', 'not-uuid', 'ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF',
  ].map((parentThreadId) => ({ ...child, parentThreadId }))]
  for (const invalid of invalidParents) {
    await assert.rejects(
      () => listSessionThreads({ call: async () => ({ json: { data: [invalid] } }) }, sampleId()),
      /parentThreadId/,
    )
  }
})

test('thread YOLO policy replaces the legacy boolean with root/follow invariants', () => {
  // 测试意图：Thread 投影用持久 yoloPolicy 取代 yoloEnabled，根只允许 ENABLE/DISABLE 且
  // rootThreadId 必须显式为 null，子代理只允许 FOLLOW 并携带 canonical 执行根。
  const rootThreadId = '33333333-3333-4333-8333-333333333333'
  const rootThread = {
    threadId: sampleId(),
    sessionId: '11111111-1111-4111-8111-111111111111',
    headEntryId: '22222222-2222-4222-8222-222222222222',
    parentThreadId: null,
    name: 'main',
    yoloPolicy: { mode: 'DISABLE', rootThreadId: null },
    nextCommandSequence: '1',
    version: '0',
    status: 'IDLE',
    processing: false,
    executionControl: 'RUNNABLE',
  }

  assert.equal(assertRootYoloPolicy(rootThread, false).mode, 'DISABLE')
  assert.equal(assertThreadYoloPolicy(rootThread.yoloPolicy, 'thread.yoloPolicy').mode, 'DISABLE')
  assert.deepEqual(
    assertThreadYoloPolicy({ mode: 'FOLLOW', rootThreadId }),
    { mode: 'FOLLOW', rootThreadId },
  )

  assert.throws(() => assertRootYoloPolicy(rootThread, true), /ENABLE/)
  assert.throws(() => assertThreadYoloPolicy({ mode: 'FOLLOW', rootThreadId: null }), /canonical UUID/)
  assert.throws(
    () => assertThreadYoloPolicy({ mode: 'ENABLE', rootThreadId }),
    /rootThreadId must be null/,
  )
  assert.throws(() => threadIdOf({ ...rootThread, yoloPolicy: undefined }), /must be an object/)
})

test('legacy ENTRY target tokens and helpers are no longer part of the lib API', async () => {
  // Test intent: old ENTRY/ENTRY_DRAFT wire vocabulary must not regress into the script surface.
  const { readFile } = await import('node:fs/promises')
  const sourceUrl = new URL('../lib/harness.mjs', import.meta.url)
  const source = await readFile(sourceUrl, 'utf8')
  assert.doesNotMatch(source, /\bENTRY\b/)
  assert.doesNotMatch(source, /entryTarget|createEntryThread/)
})

test('chatOwner/canonicalUuid keep the strict Chat owner wire shape', () => {
  // Test intent: public command owner is {type,chatId}, never the legacy {type,id}.
  const id = cid()
  assert.deepEqual(chatOwner(id), { type: 'CHAT', chatId: id })
  assert.equal(canonicalUuid(id, 'id'), id)
  assert.throws(() => chatOwner('bad'), /canonical UUID/)
})

test('acceptCommandBatch rejects legacy owner aliases before HTTP', async () => {
  // Test intent: the backend rejects unknown owner keys, including an id alongside chatId.
  const ctx = { call: async () => { throw new Error('unexpected HTTP call') } }
  const target = newSessionTarget({ sessionId: cid(), threadId: cid(), rootSettings: {} })
  for (const owner of [{ type: 'CHAT', id: cid() }, { ...chatOwner(cid()), id: cid() }]) {
    await assert.rejects(
      () => acceptCommandBatch(ctx, { owner, target, commands: [userMessageCommand('x', cid())] }),
      /owner\.chatId|owner must contain only type and chatId/,
    )
  }
})

test('acceptCommandBatch rejects the removed PROJECT owner before any HTTP call', async () => {
  // Test intent: 后端 owner 判别式只有 CHAT 与 ISSUE_AGENT（Canvas 不持有 Harness Session），
  // helper 必须在本地就拒绝 PROJECT 等旧判别式，不能把已删除的 owner 放行给后端。
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
    /owner\.type must be CHAT:/,
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
test('createChat sends a nullable environmentName and requires the projected field', async () => {
  // 测试意图：Chat 默认环境是 required-nullable 投影（DTO 用 ALWAYS 显式发 null）；helper 必须把
  // 省略/null 变成显式 environmentName:null 发送，并拒绝缺失该投影字段或非 canonical 的输入。
  const calls = []
  const ctx = {
    call: async (method, path, body) => {
      calls.push({ method, path, body })
      return {
        status: 201,
        json: {
          data: {
            id: cid(),
            agentName: body.agentName,
            environmentName: body.environmentName,
            yoloEnabled: body.yoloEnabled,
            version: '0',
          },
        },
      }
    },
  }
  const chat = await createChat(ctx, { title: 't', agentName: 'agent' })
  assert.equal(chat.environmentName, null)
  assert.equal(calls[0].method, 'POST')
  assert.equal(calls[0].path, '/api/ai/chats')
  assert.equal(Object.hasOwn(calls[0].body, 'environmentName'), true)
  assert.equal(calls[0].body.environmentName, null)
  assert.equal(calls[0].body.yoloEnabled, false)

  const named = await createChat(ctx, {
    title: 't',
    agentName: 'agent',
    environmentName: 'dev-node',
  })
  assert.equal(named.environmentName, 'dev-node')
  assert.equal(calls[1].body.environmentName, 'dev-node')

  await assert.rejects(
    createChat(
      {
        call: async () => ({
          status: 201,
          json: { data: { id: cid(), agentName: 'agent', yoloEnabled: false, version: '0' } },
        }),
      },
      { title: 't', agentName: 'agent' },
    ),
    /required-nullable environmentName/,
  )
  await assert.rejects(
    createChat(
      { call: async () => { throw new Error('unexpected HTTP call') } },
      { title: 't', agentName: 'agent', environmentName: 'bad/name' },
    ),
    /must not contain/,
  )
})

test('environment status deadline is required and explicitly null while offline', async () => {
  // 截止时间是唯一状态时间投影：无连接不能省略字段，也不能携带有效截止时间。
  const environment = {
    id: cid(), name: 'offline', version: '0', ready: false, status: 'OFFLINE', statusExpiresAt: null,
  }
  const ctx = (data) => ({ call: async () => ({ json: { data: [data] } }) })
  assert.deepEqual(await listEnvironments(ctx(environment)), [environment])
  const { statusExpiresAt: _deadline, ...missing } = environment
  await assert.rejects(() => listEnvironments(ctx(missing)), /statusExpiresAt/)
  await assert.rejects(
    () => listEnvironments(ctx({ ...environment, statusExpiresAt: '2026-10-07T00:00:00Z' })),
    /OFFLINE/,
  )
})
