import assert from 'node:assert/strict'
import test from 'node:test'

import { cid } from '../../e2e/lib/http.mjs'
import { branchSettingsOf } from '../../e2e/lib/harness.mjs'
import { MODELS, TOOL_NAMES, VARIANT } from '../matrix.mjs'
import { assertThreadSettings, buildNewSessionRequest } from '../run-agent-matrix.mjs'

test('reliability contract freezes model-visible tool names as the only selectable identity', () => {
  // Test intent: the model-visible name is the single Agent-facing tool identity used by
  // Agent config, permission keys and the catalog; internal resolver tools stay unselectable.
  assert.deepEqual(TOOL_NAMES, ['read', 'write', 'edit', 'bash', 'grep', 'find'])
  assert.equal(TOOL_NAMES.includes('load_skill'), false)
  assert.equal(TOOL_NAMES.includes('task'), false)
})

test('run-agent-matrix contract: Chat Space Session request freezes the initial prompt and selected model', () => {
  const model = fakeModel()
  const prompt = 'repair this case'
  assert.deepEqual(buildNewSessionRequest({
    testCase: fakeCase(model),
    prompt,
  }), {
    folderId: null,
    message: [{ type: 'TEXT', text: prompt }],
    modelSelection: { modelName: model.modelName, variant: VARIANT },
    yoloEnabled: true,
  })
})

test('run-agent-matrix contract: accepted snapshot assertions enforce the canonical envelope', () => {
  const chat = fakeChat()
  const model = fakeModel()
  const accepted = acceptedEnvelope(chat, model)
  assert.doesNotThrow(() =>
    assertThreadSettings(accepted, fakeCase(model), chat.agentName),
  )
  // 不匹配的 agentName 必须拒绝。
  const mismatchedAgent = structuredClone(accepted)
  mismatchedAgent.thread.branchSettings.agentName = 'other-agent'
  assert.throws(
    () => assertThreadSettings(
      mismatchedAgent,
      fakeCase(model),
      chat.agentName,
    ),
    /Thread Agent mismatch/,
  )
  // 非规范执行字段必须被精确的 execution 校验拒绝。
  const invalidExecution = structuredClone(accepted)
  invalidExecution.thread.branchSettings.execution.invalidField = 'unexpected'
  assert.throws(
    () => assertThreadSettings(
      invalidExecution,
      fakeCase(model),
      chat.agentName,
    ),
    /Thread execution mismatch/,
  )
  // 携带未预期字段时，branch settings shape 校验必须拒绝。
  const unexpectedShape = structuredClone(accepted)
  unexpectedShape.thread.branchSettings.unexpected = 'invalid'
  assert.throws(
    () => assertThreadSettings(
      unexpectedShape,
      fakeCase(model),
      chat.agentName,
    ),
    /Thread branch settings shape mismatch/,
  )
  // 断言必须拒绝非原子形状：无首条 accepted command 时不可能通过身份/游标校验。
  const nonAtomic = structuredClone(accepted)
  nonAtomic.acceptedCommands = []
  assert.throws(
    () => assertThreadSettings(nonAtomic, fakeCase(model), chat.agentName),
    /Thread identity mismatch/,
  )
})

test('accepted Thread requires an enabled root YOLO switch', () => {
  // 查询快照必须投影执行根自指与显式开启的 YOLO 开关。
  const chat = fakeChat()
  const model = fakeModel()
  const accepted = acceptedEnvelope(chat, model)
  for (const corrupt of [
    (thread) => delete thread.yoloEnabled,
    (thread) => (thread.yoloEnabled = false),
    (thread) => (thread.rootThreadId = cid()),
    (thread) => delete thread.rootThreadId,
  ]) {
    const invalid = structuredClone(accepted)
    corrupt(invalid.thread)
    assert.throws(() => assertThreadSettings(invalid, fakeCase(model), chat.agentName))
  }
})

// ---------- 最小保真 fixture（只承载被测函数真正读取的字段） ----------

function fakeChat() {
  return { id: cid(), agentName: 'reliability-probe' }
}

function fakeAgent() {
  return {
    name: 'reliability-probe',
    execution: { driver: 'builtin', settings: { modelName: fakeModel().modelName, variant: VARIANT } },
  }
}

function fakeModel() {
  return MODELS.find((model) => model.key === 'm3')
}

function fakeCase(model) {
  return { model }
}

function acceptedEnvelope(_chat, model) {
  const threadId = cid()
  return {
    thread: {
      threadId,
      sessionId: cid(),
      headEntryId: cid(),
      nextCommandSequence: '2',
      version: '1',
      parentThreadId: null,
      rootThreadId: threadId,
      yoloEnabled: true,
      branchSettings: branchSettingsOf(fakeAgent(), model),
      status: 'RUNNING',
      executionControl: 'RUNNABLE',
    },
    acceptedCommands: [{ threadId }],
  }
}
