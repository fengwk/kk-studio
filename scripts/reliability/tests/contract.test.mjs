import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import { cid } from '../../e2e/lib/http.mjs'
import {
  branchSettingsOf,
  chatOwner,
  userMessageCommand,
} from '../../e2e/lib/harness.mjs'
import { ACTIVE_TOOLS, VARIANT } from '../matrix.mjs'
import { assertThreadSettings, buildNewSessionRequest } from '../run-agent-matrix.mjs'

const DAEMON_ENV = 'docker-reliability'
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

// 迁移契约离线回归：模块可加载（旧 helper 已删除，若仍导入则加载即失败）且 NEW_SESSION
// 请求形状/断言可独立于真实后端构造并验证。后端 wire 形状由 e2e L1 矩阵回归
// （seed-and-harness.mjs），这里不伪造兼容层。
test('run-agent-matrix migration contract: module imports only current primitives', () => {
  const source = readFileSync(new URL('../run-agent-matrix.mjs', import.meta.url), 'utf8')
  assert.doesNotMatch(source, /createChatThread|enqueueCommands/, 'legacy helpers must be gone')
  assert.match(source, /\bchatOwner\b/)
  assert.match(source, /\bmaterializeNewSession\b/)
  assert.match(
    source,
    /environment:\s*\{\s*name:\s*daemonEnv,\s*workspacePath:\s*'\.'\s*\}/,
    'Chat/rootSettings must use the environment binding, not environmentName',
  )
  assert.doesNotMatch(source, /environmentName/, 'legacy environmentName field must be gone')
})

test('run-agent-matrix migration contract: NEW_SESSION request is atomic and settings-bound', () => {
  const chat = fakeChat()
  const agent = fakeAgent()
  const model = fakeModel()
  const prompt = 'repair this case'
  const request = buildNewSessionRequest({
    chat,
    agent,
    testCase: fakeCase(model),
    daemonEnv: DAEMON_ENV,
    prompt,
  })
  assert.deepEqual(request.owner, chatOwner(chat.id))
  assert.match(request.sessionId, UUID_RE)
  assert.match(request.threadId, UUID_RE)
  assert.equal(request.yoloEnabled, true)
  assert.deepEqual(
    request.rootSettings,
    branchSettingsOf(agent, model, {
      environment: { name: DAEMON_ENV, workspacePath: '.' },
      activeTools: [...ACTIVE_TOOLS],
    }),
  )
  // 单批原子提交：恰一条首 USER command，且与 harness 严格 builder 的 wire 形状逐字一致。
  assert.equal(request.commands.length, 1)
  assert.deepEqual(request.commands[0], userMessageCommand(prompt, request.commands[0].clientCommandId))
  assert.equal(request.commands[0].type, 'USER_MESSAGE')
  assert.match(request.commands[0].clientCommandId, UUID_RE)
  assert.deepEqual(request.commands[0].contents, [{ type: 'TEXT', text: prompt }])
})

test('run-agent-matrix migration contract: accepted snapshot assertions fit the real envelope', () => {
  const chat = fakeChat()
  const model = fakeModel()
  const accepted = acceptedEnvelope(chat, model)
  assert.doesNotThrow(() =>
    assertThreadSettings(accepted, fakeCase(model), DAEMON_ENV, chat.agentName),
  )
  // 断言必须拒绝旧 wire 形状：environmentName 不再存在。
  const legacy = structuredClone(accepted)
  legacy.thread.branchSettings.environment = undefined
  legacy.thread.branchSettings.environmentName = DAEMON_ENV
  assert.throws(
    () => assertThreadSettings(legacy, fakeCase(model), DAEMON_ENV, chat.agentName),
    /Thread Environment mismatch/,
  )
  // 断言必须拒绝非原子形状：无首条 accepted command 时不可能通过身份/游标校验。
  const nonAtomic = structuredClone(accepted)
  nonAtomic.acceptedCommands = []
  assert.throws(
    () => assertThreadSettings(nonAtomic, fakeCase(model), DAEMON_ENV, chat.agentName),
    /Thread identity mismatch/,
  )
})

// ---------- 最小保真 fixture（只承载被测函数真正读取的字段） ----------

function fakeChat() {
  return { id: cid(), agentName: 'reliability-probe' }
}

function fakeAgent() {
  return { name: 'reliability-probe' }
}

function fakeModel() {
  return { providerName: 'minimax', modelName: 'MiniMax-M3', ref: 'minimax/MiniMax-M3', variant: VARIANT }
}

function fakeCase(model) {
  return { model }
}

function acceptedEnvelope(chat, model) {
  const sessionId = cid()
  const threadId = cid()
  const promptCommandId = cid()
  return {
    session: { sessionId },
    rootEntry: {
      entryId: cid(),
      sessionId,
      entryType: 'ROOT',
      payloadJson: '{}',
    },
    thread: {
      threadId,
      sessionId,
      headEntryId: cid(),
      nextCommandSequence: '2',
      revision: '1',
      yoloEnabled: true,
      branchSettings: branchSettingsOf(fakeAgent(), model, {
        environment: { name: DAEMON_ENV, workspacePath: '.' },
        activeTools: [...ACTIVE_TOOLS],
      }),
      status: 'PROCESSING',
      processing: true,
    },
    acceptedCommands: [
      {
        type: 'USER_MESSAGE',
        clientCommandId: promptCommandId,
        threadId,
        sequence: '1',
        requestHash: '0'.repeat(64),
      },
    ],
    replayed: false,
  }
}
