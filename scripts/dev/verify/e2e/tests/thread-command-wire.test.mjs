import assert from 'node:assert/strict'
import test from 'node:test'

import {
  acceptCommandBatch,
  chatOwner,
  DRAFT_PREVIEW_NOTICE,
  newThreadTarget,
  previewProviderRequest,
  stopThread,
  threadTarget,
  userMessageCommand,
} from '../lib/harness.mjs'
import { cid, HttpError } from '../lib/http.mjs'

const threadDto = (threadId, { status = 'IDLE', executionControl = 'RUNNABLE', headEntryId } = {}) => ({
  threadId,
  sessionId: cid(),
  headEntryId: headEntryId ?? cid(),
  name: 'main',
  parentThreadId: null,
  nextCommandSequence: '2',
  version: '1',
  status,
  processing: status !== 'IDLE' && status !== 'STOPPED',
  executionControl,
  yoloPolicy: { mode: 'DISABLE', rootThreadId: null },
})

const acceptedDto = (threadId, command) => ({
  session: { sessionId: cid(), name: 'fixture-session' },
  rootEntry: { entryId: cid(), entryType: 'ROOT', sessionId: cid() },
  thread: threadDto(threadId),
  replayed: false,
  acceptedCommands: [{
    type: command.type,
    idempotencyKey: command.idempotencyKey,
    threadId,
    sequence: '1',
  }],
})

test('THREAD continuation routes to the owner-free thread command-batches endpoint', async () => {
  // 测试意图：既有 Thread 续写面 owner-free——真实请求 body 只含 cursor 与 commands，
  // 即使调用方传入 owner 也绝不进入 wire，也不带 helper 路由描述符的 type/threadId。
  const threadId = cid()
  const headEntryId = cid()
  const command = userMessageCommand('continue', cid())
  const calls = []
  const ctx = {
    async call(...args) {
      calls.push(args)
      return { status: 202, json: { data: acceptedDto(threadId, command) } }
    },
  }
  await acceptCommandBatch(ctx, {
    owner: chatOwner(cid()),
    target: threadTarget({
      threadId,
      expectedHeadEntryId: headEntryId,
      expectedNextCommandSequence: '1',
    }),
    commands: [command],
  })

  assert.equal(calls.length, 1)
  const [method, url, body] = calls[0]
  assert.equal(method, 'POST')
  assert.equal(url, `/api/harness/threads/${threadId}/command-batches`)
  assert.deepEqual(Object.keys(body).sort(), [
    'commands',
    'expectedHeadEntryId',
    'expectedNextCommandSequence',
  ])
  assert.equal(body.expectedHeadEntryId, headEntryId)
  assert.equal(body.expectedNextCommandSequence, '1')
  assert.deepEqual(body.commands, [command])
  assert.equal(Object.hasOwn(body, 'owner'), false)
  assert.equal(Object.hasOwn(body, 'target'), false)
})

test('THREAD continuation surfaces the public rejection of NOTIFICATION commands', async () => {
  // 测试意图：helper 不静默丢弃/改写命令，NOTIFICATION 会原样到达 owner-free 端点，
  // 服务端的产品面拒绝（400）必须向上冒泡而不是被吞成成功。
  const threadId = cid()
  const command = {
    type: 'NOTIFICATION',
    idempotencyKey: cid(),
    contents: [{ type: 'TEXT', text: 'system notice' }],
  }
  const ctx = {
    async call(method, url, body) {
      assert.equal(url, `/api/harness/threads/${threadId}/command-batches`)
      assert.equal(body.commands[0].type, 'NOTIFICATION')
      throw new HttpError(400, JSON.stringify({ errors: { detail: 'NOTIFICATION is internal-only' } }), url)
    },
  }
  await assert.rejects(
    () =>
      acceptCommandBatch(ctx, {
        target: threadTarget({
          threadId,
          expectedHeadEntryId: cid(),
          expectedNextCommandSequence: '1',
        }),
        commands: [command],
      }),
    (error) => error instanceof HttpError && error.status === 400,
  )
})

const previewDto = (overrides = {}) => ({
  kind: 'DRAFT_REQUEST_PREVIEW',
  generatedAt: 1_700_000_000,
  providerType: 'openai',
  modelName: 'fixture-model',
  bodyByteSize: Buffer.byteLength('{"model":"fixture"}', 'utf8'),
  bodyJson: '{"model":"fixture"}',
  sourceHeadEntryId: cid(),
  notice: DRAFT_PREVIEW_NOTICE,
  ...overrides,
})

test('previewProviderRequest posts the owner-free cursor body and validates the preview DTO', async () => {
  // 测试意图：发送前预览与续写面共用 owner-free body，响应必须是精确 8 字段的 DRAFT_REQUEST_PREVIEW。
  const threadId = cid()
  const headEntryId = cid()
  const command = userMessageCommand('preview', cid())
  const calls = []
  const ctx = {
    async call(...args) {
      calls.push(args)
      return { status: 200, json: { data: previewDto() } }
    },
  }
  const preview = await previewProviderRequest(ctx, threadId, {
    expectedHeadEntryId: headEntryId,
    expectedNextCommandSequence: '1',
    commands: [command],
  })
  assert.equal(preview.kind, 'DRAFT_REQUEST_PREVIEW')

  const [method, url, body] = calls[0]
  assert.equal(method, 'POST')
  assert.equal(url, `/api/harness/threads/${threadId}/provider-request-preview`)
  assert.deepEqual(Object.keys(body).sort(), [
    'commands',
    'expectedHeadEntryId',
    'expectedNextCommandSequence',
  ])
  assert.equal(Object.hasOwn(body, 'owner'), false)
  assert.equal(Object.hasOwn(body, 'target'), false)
})

test('preview wire rejects a non-preview shape instead of guessing notice/kind', async () => {
  // 测试意图：旧的 click-time snapshotNotice 字段（以及缺字段/多字段/错 kind）都不是预览契约，
  // helper 必须直接拒绝，而不是继续接受一个无法证明「按当前 catalog 重建」的响应。
  const { notice: _omitted, ...withoutNotice } = previewDto()
  const cases = [
    withoutNotice,
    { ...withoutNotice, snapshotNotice: 'click-time snapshot' },
    previewDto({ kind: 'HISTORICAL_REQUEST_PREVIEW' }),
  ]
  for (const malformed of cases) {
    const ctx = {
      async call() {
        return { status: 200, json: { data: malformed } }
      },
    }
    await assert.rejects(
      () =>
        previewProviderRequest(ctx, cid(), {
          expectedHeadEntryId: cid(),
          expectedNextCommandSequence: '1',
          commands: [userMessageCommand('preview', cid())],
        }),
      /preview/,
    )
  }
})

test('NEW_THREAD target requires an explicit normalized threadName', () => {
  // 测试意图：分支名不再是服务端派生默认值，而是进入创建请求身份；helper 必须先于 wire 规范化并拒绝非法名。
  const target = newThreadTarget({
    sessionId: cid(),
    startEntryId: cid(),
    threadId: cid(),
    threadName: '  branch   name  ',
  })
  assert.deepEqual(Object.keys(target).sort(), [
    'sessionId',
    'startEntryId',
    'threadId',
    'threadName',
    'type',
    'yoloEnabled',
  ])
  assert.equal(target.threadName, 'branch name')
  assert.equal(target.yoloEnabled, false)

  for (const threadName of [undefined, null, '', '   ', 'a'.repeat(257)]) {
    assert.throws(() =>
      newThreadTarget({
        sessionId: cid(),
        startEntryId: cid(),
        threadId: cid(),
        threadName,
      }),
    )
  }
  // 256 码点边界仍是合法名（按码点而非 UTF-16 长度计数）。
  assert.equal(
    newThreadTarget({
      sessionId: cid(),
      startEntryId: cid(),
      threadId: cid(),
      threadName: '分支'.repeat(128),
    }).threadName.length,
    256,
  )
})

test('NEW_THREAD first creation asserts the normalized name, exact replay does not', async () => {
  // 测试意图：CREATE 响应的分支名必须等于规范化后的请求名；replay 可能命中已重命名的 Thread，
  // helper 不做默认名断言，但也绝不能把「已重命名」误判为失败。
  const threadId = cid()
  const sessionId = cid()
  const command = userMessageCommand('branch', cid())
  const target = newThreadTarget({ sessionId, startEntryId: cid(), threadId, threadName: 'branch   name' })
  const response = (threadName, replayed) => ({
    status: 202,
    json: { data: {
      ...acceptedDto(threadId, command),
      session: { sessionId, name: 'fixture-session' },
      rootEntry: { entryId: cid(), entryType: 'ROOT', sessionId },
      thread: { ...threadDto(threadId), sessionId, name: threadName },
      replayed,
    } },
  })

  const owner = chatOwner(cid())
  await acceptCommandBatch(
    { call: async () => response('branch name', false) },
    { owner, target, commands: [command] },
  )
  await assert.rejects(
    () =>
      acceptCommandBatch(
        { call: async () => response('branch-name', false) },
        { owner, target, commands: [command] },
      ),
    /thread name/,
  )
  await acceptCommandBatch(
    { call: async () => response('renamed later', true) },
    { owner, target, commands: [command] },
  )
})

test('stopThread validates per-thread receipts and rejects the removed root-only shape', async () => {
  // 测试意图：Stop 结果的权威形状是每个受影响 Thread 一条回执；顶层 cancelledUserMessages/stoppedTurnEndEntryId
  // 已删除，缺失 stoppedThreads[] 的回执不再被接受。
  const threadId = cid()
  const stopRequestId = cid()
  const stoppedTurnEndEntryId = cid()
  const receipt = {
    threadId,
    stopRequestId,
    stoppedTurnEndEntryId,
    cancelledCommandCount: 1,
    cancelledInputs: [{
      sequence: '1',
      idempotencyKey: cid(),
      type: 'USER_MESSAGE',
      payloadJson: '{"type":"USER_MESSAGE"}',
    }],
  }
  const stopCtx = {
    async call() {
      return {
        status: 200,
        json: { data: {
          status: 'STOPPED',
          thread: threadDto(threadId, {
            status: 'STOPPED',
            executionControl: 'STOPPED',
            headEntryId: stoppedTurnEndEntryId,
          }),
          stoppedThreads: [receipt],
        } },
      }
    },
  }
  const stop = await stopThread(stopCtx, threadId, { stopRequestId, expectedVersion: '1' })
  assert.equal(stop.status, 'STOPPED')
  assert.equal(stop.thread.executionControl, 'STOPPED')
  assert.equal(stop.stoppedThreads[0].cancelledInputs[0].type, 'USER_MESSAGE')

  // 旧 root-only 形状（无 stoppedThreads）必须被拒绝；CUSTOM_MESSAGE/NOTIFICATION 不得冒充人类草稿。
  for (const malformed of [
    { status: 'STOPPED', thread: threadDto(threadId), stoppedTurnEndEntryId },
    {
      status: 'STOPPED',
      thread: threadDto(threadId),
      stoppedThreads: [{ ...receipt, cancelledInputs: [{ ...receipt.cancelledInputs[0], type: 'NOTIFICATION' }] }],
    },
  ]) {
    await assert.rejects(() =>
      stopThread(
        { call: async () => ({ status: 200, json: { data: malformed } }) },
        threadId,
        { stopRequestId, expectedVersion: '1' },
      ),
    )
  }
})
