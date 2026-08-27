import assert from 'node:assert/strict'
import test from 'node:test'

import { cid } from '../../e2e/lib/http.mjs'
import {
  branchSettingsOf,
  chatOwner,
  userMessageCommand,
} from '../../e2e/lib/harness.mjs'
import { AGENT_TOOL_IDS, MODEL_TOOL_NAMES, VARIANT } from '../matrix.mjs'
import { assertThreadSettings, buildNewSessionRequest } from '../run-agent-matrix.mjs'

const DAEMON_ENV = 'docker-reliability'
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

test('reliability contract keeps selectable AgentToolIds separate from model tool names', () => {
  // Test intent: freeze the wire IDs and the Provider-facing names independently,
  // while ensuring internal resolver tools cannot become selectable by configuration.
  assert.deepEqual(AGENT_TOOL_IDS, [
    'base.read',
    'base.write',
    'base.edit',
    'base.apply-patch',
    'base.bash',
    'base.grep',
    'base.find',
  ])
  assert.deepEqual(MODEL_TOOL_NAMES, ['read', 'write', 'edit', 'apply_patch', 'bash', 'grep', 'find'])
  assert.equal(AGENT_TOOL_IDS.includes('base.load-skill'), false)
  assert.equal(AGENT_TOOL_IDS.includes('base.task'), false)
})

test('run-agent-matrix contract: NEW_SESSION request is atomic', () => {
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
  assert.equal(request.commands.length, 1)
  assert.deepEqual(request.commands[0], userMessageCommand(prompt, request.commands[0].clientCommandId))
  assert.equal(request.commands[0].type, 'USER_MESSAGE')
  assert.match(request.commands[0].clientCommandId, UUID_RE)
  assert.deepEqual(request.commands[0].contents, [{ type: 'TEXT', text: prompt }])
})

test('run-agent-matrix contract: NEW_SESSION root settings freeze the environment binding', () => {
  const chat = fakeChat()
  const agent = fakeAgent()
  const model = fakeModel()
  const request = buildNewSessionRequest({
    chat,
    agent,
    testCase: fakeCase(model),
    daemonEnv: DAEMON_ENV,
    prompt: 'investigate this case',
  })
  assert.deepEqual(
    request.rootSettings,
    branchSettingsOf(agent, model, {
      environment: { name: DAEMON_ENV, workspacePath: '.' },
    }),
  )
  assert.deepEqual(Object.keys(request.rootSettings).sort(), ['agentName', 'environment', 'model'])
})

test('run-agent-matrix contract: accepted snapshot assertions enforce the canonical envelope', () => {
  const chat = fakeChat()
  const model = fakeModel()
  const accepted = acceptedEnvelope(chat, model)
  assert.doesNotThrow(() =>
    assertThreadSettings(accepted, fakeCase(model), DAEMON_ENV, chat.agentName),
  )
  // name-only Environment 不是完整 binding，必须拒绝。
  const nameOnlyEnvironment = structuredClone(accepted)
  nameOnlyEnvironment.thread.branchSettings.environment = undefined
  nameOnlyEnvironment.thread.branchSettings.environmentName = DAEMON_ENV
  assert.throws(
    () => assertThreadSettings(
      nameOnlyEnvironment,
      fakeCase(model),
      DAEMON_ENV,
      chat.agentName,
    ),
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
      version: '1',
      yoloEnabled: true,
      branchSettings: branchSettingsOf(fakeAgent(), model, {
        environment: { name: DAEMON_ENV, workspacePath: '.' },
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
