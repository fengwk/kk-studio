import assert from 'node:assert/strict'
import test from 'node:test'

import {
  acceptCommandBatch,
  stopThread,
  stopThreadForCleanup,
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
  executionControl,
  rootThreadId: threadId,
  yoloEnabled: false,
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
  // 测试意图：cursor-bound 批次的 wire body 只含 cursor 与 commands，不带 helper 路由描述符。
  const threadId = cid()
  const headEntryId = cid()
  const command = userMessageCommand('ship it', cid())
  const calls = []
  const ctx = {
    async call(...args) {
      calls.push(args)
      return { status: 202, json: acceptedDto(threadId, command) }
    },
  }
  await acceptCommandBatch(ctx, {
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

test('pure THREAD message omits both cursor fields from the owner-free wire body', async () => {
  // 测试意图：单条纯 USER_MESSAGE 不冻结 cursor，wire body 只有 commands；服务端按当前邮箱尾部入队。
  const threadId = cid()
  const command = userMessageCommand('cursorless', cid())
  const calls = []
  const ctx = {
    async call(...args) {
      calls.push(args)
      return { status: 202, json: acceptedDto(threadId, command) }
    },
  }
  await acceptCommandBatch(ctx, {
    target: threadTarget({ threadId }),
    commands: [command],
  })

  const [, url, body] = calls[0]
  assert.equal(url, `/api/harness/threads/${threadId}/command-batches`)
  assert.deepEqual(Object.keys(body).sort(), ['commands'])
  assert.deepEqual(body.commands, [command])
})

test('cursor-bound THREAD batch without cursor is not silently completed by the helper', async () => {
  // 测试意图：SET_EXECUTION batch 缺少 cursor 时 helper 不得合成字段，服务端 400 必须原样冒泡。
  const threadId = cid()
  const commands = [
    { type: 'SET_EXECUTION', idempotencyKey: cid(), execution: { driver: 'builtin', settings: { modelName: 'model' } } },
    userMessageCommand('mixed', cid()),
  ]
  const ctx = {
    async call(method, url, body) {
      assert.equal(url, `/api/harness/threads/${threadId}/command-batches`)
      assert.deepEqual(Object.keys(body).sort(), ['commands'])
      throw new HttpError(400, JSON.stringify({ type: 'about:blank', title: 'Invalid request', status: 400, code: 'validation', detail: 'cursor required' }), url)
    },
  }
  await assert.rejects(
    () =>
      acceptCommandBatch(ctx, {
        target: threadTarget({ threadId }),
        commands,
      }),
    (error) => error instanceof HttpError && error.status === 400,
  )
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
      throw new HttpError(400, JSON.stringify({ type: 'about:blank', title: 'Invalid request', status: 400, code: 'validation', detail: 'NOTIFICATION is internal-only' }), url)
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

test('cleanup does not repeat an accepted stop while the Thread remains RUNNING', async () => {
  // 停止请求已生效不代表退出；cleanup 不重复发 stop，也不改写活动事实。
  const threadId = cid()
  const snapshot = {
    thread: threadDto(threadId, { status: 'RUNNING', executionControl: 'STOPPED' }),
    queuedCommands: [],
    activeAssistantOutput: null,
    toolInvocations: [],
  }
  const calls = []
  await stopThreadForCleanup({
    async call(method, path) {
      calls.push([method, path])
      assert.equal(method, 'GET')
      return { json: snapshot }
    },
  }, threadId)
  assert.deepEqual(calls, [['GET', `/api/harness/threads/${threadId}`]])
  assert.equal(snapshot.thread.status, 'RUNNING')
})

test('stopThread validates per-thread receipts', async () => {
  // 测试意图：Stop 结果的权威形状是每个受影响 Thread 一条回执。
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
      payload: { type: 'USER_MESSAGE' },
    }],
  }
  const stopCtx = {
    async call() {
      return {
        status: 202,
        json: {
          status: 'STOPPED',
          thread: threadDto(threadId, {
            status: 'IDLE',
            executionControl: 'STOPPED',
            headEntryId: stoppedTurnEndEntryId,
          }),
          stoppedThreads: [receipt],
        },
      }
    },
  }
  const stop = await stopThread(stopCtx, threadId, { stopRequestId, expectedVersion: '1' })
  assert.equal(stop.status, 'STOPPED')
  assert.equal(stop.thread.executionControl, 'STOPPED')
  assert.equal(stop.stoppedThreads[0].cancelledInputs[0].type, 'USER_MESSAGE')

  // 缺少 stoppedThreads 或非 USER_MESSAGE 输入必须被拒绝。
  for (const malformed of [
    { status: 'STOPPED', thread: threadDto(threadId) },
    {
      status: 'STOPPED',
      thread: threadDto(threadId),
      stoppedThreads: [{ ...receipt, cancelledInputs: [{ ...receipt.cancelledInputs[0], type: 'NOTIFICATION' }] }],
    },
  ]) {
    await assert.rejects(() =>
      stopThread(
        { call: async () => ({ status: 202, json: malformed }) },
        threadId,
        { stopRequestId, expectedVersion: '1' },
      ),
    )
  }
})
