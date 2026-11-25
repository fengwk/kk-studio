/**
 * Contract regression for the offline workspace and container Canvas smoke.
 *
 * 共享纯函数契约（offline-canvas-contract / offline-workspace-payload）的成功与失败边界是这里的主回归面。
 */

import assert from 'node:assert/strict'
import test from 'node:test'

import {
  assertCompletedRun,
  assertFunctionDefinitions,
  assertPatch,
  assertRunRevision,
  decimalRevision,
  definitionVersion,
  documentRevision,
  functionBatch,
  resourceBatch,
} from '../offline-canvas-contract.mjs'
import { buildOfflineSessionRequest } from '../offline-workspace-payload.mjs'
import {
  MARKER,
  REQUIRED_FUNCTIONS,
  THREAD_ID,
  documentDto,
  functionDefinitions,
  patchDto,
  runDto,
} from './support/canvas-dto.mjs'

// ---------- 命令批 payload 必须是当前 CanvasCommandDTO 的精确形状 ----------

test('test intent: the upload batch claims the READY upload by uploadId', () => {
  assert.deepEqual(resourceBatch('node-1', 'upload-1', 'key-1'), {
    idempotencyKey: 'key-1',
    commands: [
      {
        type: 'CREATE_NODE',
        nodeId: 'node-1',
        name: 'uploaded',
        transform: { x: 0, y: 0, width: 320, height: 260 },
        resources: [{ type: 'BLOB', name: 'tiny.png', uploadId: 'upload-1' }],
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

test('test intent: the document snapshot requires a canonical revision', () => {
  assert.equal(documentRevision(documentDto(8)), 8)
  const { revision: _revision, ...withoutRevision } = documentDto(8)
  assert.throws(() => documentRevision(withoutRevision), assert.AssertionError)
})

test('test intent: a successful batch advances the revision by one', () => {
  assert.equal(assertPatch(patchDto('1', [{ type: 'UPSERT', node: { id: 'n1' } }]), 0), 1)
})

test('test intent: patches with non-canonical or non-advancing revisions are rejected', () => {
  for (const revision of ['01', '1.0', 1]) {
    assert.throws(() => assertPatch(patchDto(revision), 0), assert.AssertionError, String(revision))
  }
  assert.throws(() => assertPatch(patchDto('5'), 0), assert.AssertionError)
  assert.throws(() => assertPatch({ revision: '1', groups: {}, nodes: [] }, 0), assert.AssertionError)
})

test('test intent: an exact replay is an empty patch at the same revision', () => {
  assert.equal(assertPatch(patchDto('1'), 1, true), 1)
  assert.throws(
    () => assertPatch(patchDto('1', [{ type: 'UPSERT', node: { id: 'n1' } }]), 1, true),
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

test('test intent: function versions contain only a canonical fingerprint', () => {
  // 额外字段及非法 fingerprint 都不能作为启动请求的版本身份。
  const definitions = functionDefinitions()
  const version = definitionVersion(definitions, 'fake-image')
  assert.deepEqual(Object.keys(version), ['definitionFingerprint'])
  for (const invalid of [
    { ...version, unknownField: '7' },
    {},
    { definitionFingerprint: version.definitionFingerprint.toUpperCase() },
    { definitionFingerprint: 'abc' },
  ]) {
    const altered = functionDefinitions()
    altered[0].version = invalid
    assert.throws(() => definitionVersion(altered, 'fake-image'))
    assert.throws(() => assertFunctionDefinitions(altered))
  }
})

test('test intent: non-strict, unavailable, or missing definitions fail the smoke', () => {
  const unavailable = functionDefinitions(['fake-image'])
  Object.assign(unavailable[0], { available: false, unavailableReason: 'fake hub disabled' })
  const withSchema = (schema) => functionDefinitions().map((definition) => ({ ...definition, argsSchema: schema }))
  const cases = {
    空目录: [],
    '非 object schema': withSchema({ type: 'string' }),
    允许额外参数: withSchema({ type: 'object', additionalProperties: true }),
    必需函数不可用: unavailable,
    '必需的 seedance2.0_vip 缺失': functionDefinitions().filter((item) => item.name !== 'seedance2.0_vip'),
  }
  for (const [reason, definitions] of Object.entries(cases)) {
    assert.throws(() => assertFunctionDefinitions(definitions), reason)
  }
})

// ---------- 离线会话请求必须匹配 Chat Space 创建接口 ----------

test('test intent: session creation submits the initial user message and YOLO state', () => {
  assert.deepEqual(buildOfflineSessionRequest({ marker: MARKER }), {
    message: [{ type: 'TEXT', text: MARKER }],
    yoloEnabled: false,
  })
  assert.deepEqual(buildOfflineSessionRequest({ marker: MARKER, yoloEnabled: true }), {
    message: [{ type: 'TEXT', text: MARKER }],
    yoloEnabled: true,
  })
})

test('test intent: the session request builder rejects unknown options', () => {
  assert.throws(
    () => buildOfflineSessionRequest({ marker: MARKER, threadId: THREAD_ID }),
    TypeError,
  )
})
