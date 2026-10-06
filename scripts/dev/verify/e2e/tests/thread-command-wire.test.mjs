import assert from 'node:assert/strict'
import test from 'node:test'

import {
  acceptCommandBatch,
  chatOwner,
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

test('previewProviderRequest posts the owner-free cursor body and validates the preview DTO', async () => {
  // 测试意图：发送前预览与续写面共用 owner-free body，响应必须是 DRAFT_REQUEST_PREVIEW 快照。
  const threadId = cid()
  const headEntryId = cid()
  const command = userMessageCommand('preview', cid())
  const calls = []
  const ctx = {
    async call(...args) {
      calls.push(args)
      return {
        status: 200,
        json: { data: {
          kind: 'DRAFT_REQUEST_PREVIEW',
          bodyJson: '{"model":"fixture"}',
          snapshotNotice: 'click-time snapshot',
        } },
      }
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
