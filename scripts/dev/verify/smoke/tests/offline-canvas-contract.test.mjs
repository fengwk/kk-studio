/**
 * Contract regression for the offline chat and container Canvas smoke.
 *
 * 共享纯函数契约（offline-canvas-contract / offline-chat-payload）的成功与失败边界是这里的主回归面。
 */

import assert from 'node:assert/strict'
import test from 'node:test'

import {
  assertCompletedRun,
  assertFunctionDefinitions,
  assertPatch,
  assertRunRevision,
  decimalRevision,
  documentRevision,
  functionBatch,
  resourceBatch,
} from '../offline-canvas-contract.mjs'
import { buildOfflineChatBatchRequest } from '../offline-chat-payload.mjs'
import {
  BLOB_ID,
  CHAT_ID,
  COMMAND_ID,
  MARKER,
  REQUIRED_FUNCTIONS,
  SESSION_ID,
  THREAD_ID,
  documentDto,
  functionDefinitions,
  patchDto,
  runDto,
} from './support/canvas-dto.mjs'

// ---------- 命令批 payload 必须是当前 CanvasCommandDTO 的精确形状 ----------

test('test intent: the upload batch is one CREATE_NODE whose resource is a BLOB with a blobId', () => {
  assert.deepEqual(resourceBatch('node-1', BLOB_ID, 'key-1'), {
    idempotencyKey: 'key-1',
    commands: [
      {
        type: 'CREATE_NODE',
        nodeId: 'node-1',
        name: 'uploaded',
        transform: { x: 0, y: 0, width: 320, height: 260 },
        resources: [{ kind: 'BLOB', name: 'tiny.png', blobId: BLOB_ID }],
      },
    ],
  })
})

test('test intent: the Function batch creates the node and assigns the function in one batch', () => {
  const args = { prompt: 'mock seedance2.0fast', ratio: '16:9', duration: 4 }
  assert.deepEqual(functionBatch('node-2', 'mock-seedance', 'seedance2.0fast', args, 'key-2'), {
    idempotencyKey: 'key-2',
    commands: [
      {
        type: 'CREATE_NODE',
        nodeId: 'node-2',
        name: 'mock-seedance',
        transform: { x: 100, y: 100, width: 320, height: 260 },
        resources: [],
      },
      {
        type: 'SET_NODE_FUNCTION',
        nodeId: 'node-2',
        expectedFunction: null,
        function: { name: 'seedance2.0fast', args },
      },
    ],
  })
})

test('test intent: function args stay a flat map (no nested configJson shape)', () => {
  const command = functionBatch('node-3', 'generated', 'fake-image', { prompt: 'free', ratio: '16:9' }, 'key-3')
    .commands[1]
  assert.equal(command.expectedFunction, null)
  assert.deepEqual(command.function, { name: 'fake-image', args: { prompt: 'free', ratio: '16:9' } })
})

// ---------- revision 是规范十进制字符串：成功批 +1，精确重放是空 patch 且不推进 ----------

test('test intent: only 0|[1-9][0-9]* is a valid revision representation', () => {
  assert.equal(decimalRevision('0'), 0)
  assert.equal(decimalRevision('42'), 42)
  for (const value of ['01', '1.0', ' 1', '1e2', '-1', '+1', '', 1, null, true, undefined]) {
    assert.throws(() => decimalRevision(value), assert.AssertionError, String(value))
  }
})

test('test intent: the document snapshot is revision-only and a legacy version snapshot fails', () => {
  assert.equal(documentRevision(documentDto(8)), 8)
  const { revision: _revision, ...legacy } = documentDto(8)
  assert.throws(() => documentRevision({ ...legacy, version: '8' }), assert.AssertionError)
})

test('test intent: a successful batch advances the revision by one', () => {
  assert.equal(assertPatch(patchDto('1', [{ op: 'UPSERT', node: { id: 'n1' } }]), 0), 1)
})

test('test intent: patches with legacy fields, non-canonical or non-advancing revisions are rejected', () => {
  assert.throws(() => assertPatch({ ...patchDto('1'), version: '1', baseVersion: '0' }, 0), assert.AssertionError)
  for (const revision of ['01', '1.0', 1]) {
    assert.throws(() => assertPatch(patchDto(revision), 0), assert.AssertionError, String(revision))
  }
  assert.throws(() => assertPatch(patchDto('5'), 0), assert.AssertionError)
  assert.throws(() => assertPatch({ revision: '1', groups: {}, nodes: [] }, 0), assert.AssertionError)
})

test('test intent: an exact replay is an empty patch at the same revision', () => {
  assert.equal(assertPatch(patchDto('1'), 1, true), 1)
  assert.throws(
    () => assertPatch(patchDto('1', [{ op: 'UPSERT', node: { id: 'n1' } }]), 1, true),
    assert.AssertionError,
  )
  assert.throws(() => assertPatch(patchDto('2'), 1, true), assert.AssertionError)
})

// ---------- FunctionRun 终态与运行期间的 revision 推进必须可判定 ----------

test('test intent: a completed run is the exact six-field DTO with no leaked state', () => {
  assert.equal(assertCompletedRun(runDto('n1', 'r1'), 'n1', 'r1'), undefined)
  const rejected = [
    [runDto('n1', 'r1', { status: 'FAILED', error: 'provider error' }), '失败必须暴露'],
    [runDto('n1', 'r1', { error: 'provider error' }), 'SUCCEEDED 不得携带 error'],
    [{ ...runDto('n1', 'r1'), stateJson: '{}' }, 'stateJson 已不再输出'],
    [runDto('n1', 'r1', { status: 'RUNNING' }), '未终态不能视为完成'],
    [runDto('n1', 'other'), 'requestId 必须匹配'],
    [runDto('other', 'r1'), 'nodeId 必须匹配'],
  ]
  for (const [run, reason] of rejected) {
    assert.throws(() => assertCompletedRun(run, 'n1', 'r1'), assert.AssertionError, reason)
  }
})

test('test intent: run revisions count the lifecycle advances and the durable checkpoints', () => {
  // start/beginSubmit/confirmSubmitted/complete advance once each, checkpoints advance one by one.
  for (const [patchRevision, checkpoints, expected] of [
    [2, 2, 8],
    [8, 4, 16],
    [10, 6, 20],
  ]) {
    assert.equal(assertRunRevision(documentDto(expected), patchRevision, checkpoints), expected)
  }
  // Without a checkpoint count only the four lifecycle advances are required.
  assert.equal(assertRunRevision(documentDto(9), 2), 9)
  assert.throws(() => assertRunRevision(documentDto(5), 2), assert.AssertionError)
  assert.throws(() => assertRunRevision(documentDto(17), 8, 4), assert.AssertionError)
})

// ---------- 函数目录按 name + 严格 object schema 发布 ----------

test('test intent: available definitions use name and a strict schema', () => {
  assert.equal(assertFunctionDefinitions(functionDefinitions()), undefined)
})

test('test intent: legacy, non-strict, unavailable, missing or retired definitions fail the smoke', () => {
  const unavailable = functionDefinitions(['fake-image'])
  Object.assign(unavailable[0], { available: false, unavailableReason: 'fake hub disabled' })
  const withSchema = (schema) => functionDefinitions().map((definition) => ({ ...definition, argsSchema: schema }))
  const cases = {
    空目录: [],
    '旧 key 字段': functionDefinitions().map(({ name, ...rest }) => ({ ...rest, key: name })),
    '非 object schema': withSchema({ type: 'string' }),
    允许额外参数: withSchema({ type: 'object', additionalProperties: true }),
    必需函数不可用: unavailable,
    '必需的 seedance2.0_vip 缺失': functionDefinitions().filter((item) => item.name !== 'seedance2.0_vip'),
    '已下线的 mini 变体': functionDefinitions([...REQUIRED_FUNCTIONS, 'seedance2.0mini']),
  }
  for (const [reason, definitions] of Object.entries(cases)) {
    assert.throws(() => assertFunctionDefinitions(definitions), reason)
  }
})

// ---------- 离线 Chat 批请求必须匹配当前 harness wire ----------

test('test intent: the NEW_SESSION batch carries no workspace field and the owner is the chat', () => {
  const request = buildOfflineChatBatchRequest({
    chatId: CHAT_ID,
    sessionId: SESSION_ID,
    threadId: THREAD_ID,
    commandId: COMMAND_ID,
    marker: MARKER,
  })
  assert.deepEqual(request.owner, { type: 'CHAT', chatId: CHAT_ID })
  assert.deepEqual(request.target, {
    type: 'NEW_SESSION',
    sessionId: SESSION_ID,
    threadId: THREAD_ID,
    // canonical root settings 精确为 agentName + model + environmentName 三字段：
    // environmentName 是 nullable 快照字段，null 表示未选择 Environment。
    rootSettings: {
      agentName: 'default-assistant',
      model: { providerName: 'stub', modelName: 'acceptance-stub', variant: 'default' },
      environmentName: null,
    },
    yoloEnabled: false,
  })
  assert.deepEqual(request.commands, [
    { type: 'USER_MESSAGE', idempotencyKey: COMMAND_ID, contents: [{ type: 'TEXT', text: MARKER }] },
  ])
})

test('test intent: the removed workspace path option must not silently reappear on the builder', () => {
  assert.throws(
    () =>
      buildOfflineChatBatchRequest({
        chatId: CHAT_ID,
        sessionId: SESSION_ID,
        threadId: THREAD_ID,
        commandId: COMMAND_ID,
        marker: MARKER,
        workspacePath: 'src/tests',
      }),
    TypeError,
  )
})
