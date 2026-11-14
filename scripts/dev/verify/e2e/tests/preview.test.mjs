import assert from 'node:assert/strict'
import test from 'node:test'

import { getCase } from '../run-matrix.mjs'
import { problemError } from './fixtures/problem.mjs'

const id = (n) => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`

test('seed case reads the execution contract, including a null builtin variant', async () => {
  // Execute the registered case against a faithful catalog response, not a source-text probe.
  const agent = {
    name: 'fixture-agent', description: 'fixture', systemPrompt: 'prompt',
    execution: { driver: 'builtin', settings: { modelName: 'fixture-model', variant: null } },
    config: {}, version: '0', createdAt: 0, updatedAt: 0,
  }
  const providers = Object.entries({
    minimax: 'openai_responses', openai: 'openai_responses', xai: 'openai_responses',
    deepseek: 'openai', google: 'google', anthropic: 'anthropic', zai: 'openai',
    'minimax-anthropic': 'anthropic',
  }).map(([name, providerType]) => ({
    name, description: 'fixture', providerType, credential: { configured: false },
    modelCallTimeoutMillis: null, modelCallIdleTimeoutMillis: null, modelHttpRetryStatusCodes: null,
    promptCacheRetentions: ['NONE', 'SHORT'], version: '0', createdAt: 0, updatedAt: 0,
  }))
  const ctx = {
    vars: {},
    async call(method, path) {
      assert.equal(method, 'GET')
      if (path.startsWith('/api/platform/agents')) {
        return { json: [agent] }
      }
      return { json: providers }
    },
  }
  await getCase('seed.agent_and_provider').run(ctx)
  assert.deepEqual(ctx.vars.agent.execution, agent.execution)
  agent.model = 'fixture-model'
  await assert.rejects(getCase('seed.agent_and_provider').run(ctx), /AgentDTO/)
})

/**
 * 只 fake HTTP 的 Model Request Debug 后端：按当前契约建模只读 POST（草稿 model/environmentName
 * 回显、缺失 Agent 确定性 PLANNING_FAILED 的安全空投影）与缺 model / 非 canonical environmentName
 * 的 400、GET 的 405，用来真实执行 `thread.model_request_debug_draft_settings` case。
 */
function modelRequestDebugFixture() {
  const agent = {
    name: 'fixture-agent',
    execution: { driver: 'builtin', settings: { modelName: 'fixture-model', variant: 'default' } },
  }
  const model = {
    providerName: 'fixture-provider',
    name: 'fixture-model',
    config: { defaultVariant: 'default' },
    version: '1',
  }
  const requests = []
  let chat = null
  let thread = null
  const call = async (method, path, body) => {
    requests.push({ method, path, body })
    const pathname = new URL(path, 'http://fixture.local').pathname
    if (method === 'GET' && pathname === '/api/platform/agents') {
      return { status: 200, json: [agent] }
    }
    if (method === 'GET' && pathname === '/api/platform/models') {
      return { status: 200, json: [model] }
    }
    if (method === 'POST' && pathname === '/api/chat/chats') {
      chat = {
        id: id(1),
        title: body.title,
        agentName: body.agentName,
        environmentName: body.environmentName ?? null,
        yoloEnabled: body.yoloEnabled === true,
        version: '0',
      }
      return { status: 201, json: { ...chat } }
    }
    if (method === 'POST' && pathname === '/api/harness/command-batches') {
      assert.match(body.target.rootSettings.agentName, /^missing-debug-agent-/)
      const { threadId, sessionId } = body.target
      thread = {
        threadId,
        sessionId,
        headEntryId: id(2),
        parentThreadId: null,
        name: 'main',
        version: '1',
        nextCommandSequence: '2',
        status: 'IDLE',
        executionControl: 'RUNNABLE',
        rootThreadId: threadId, yoloEnabled: false,
      }
      return {
        status: 202,
        json: {
          session: { sessionId, name: 'fixture' },
          rootEntry: { entryId: id(2), entryType: 'ROOT', sessionId },
          thread,
          replayed: false,
          acceptedCommands: body.commands.map((command, index) => ({
            type: command.type,
            idempotencyKey: command.idempotencyKey,
            threadId,
            sequence: String(index + 1),
          })),
        },
      }
    }
    if (method === 'GET' && pathname === `/api/harness/threads/${thread.threadId}`) {
      return {
        status: 200,
        json: {
          thread: { ...thread },
          entries: [],
          queuedCommands: [],
          activeAssistantOutput: null,
          toolInvocations: [],
          generationFailures: [],
          stopReceipts: [],
        },
      }
    }
    if (pathname === `/api/harness/builtin/threads/${thread.threadId}/model-request-debug`) {
      if (method === 'GET') {
        throw problemError(405, 'validation', 'Method Not Allowed', pathname)
      }
      assert.equal(method, 'POST')
      if (body.model == null) {
        throw problemError(400, 'validation', 'model must not be null', pathname)
      }
      if (body.environmentName != null && String(body.environmentName).includes('/')) {
        throw problemError(
          400,
          'validation',
          "environmentName must not contain '/'",
          pathname,
        )
      }
      return {
        status: 200,
        json: {
          kind: 'NEXT_REQUEST_PREVIEW',
          generatedAt: Date.parse('2026-10-02T00:00:00Z'),
          model: body.model,
          environmentName: body.environmentName ?? null,
          systemInstruction: '',
          tools: [],
          skills: [],
          subagents: [],
          cacheControl: null,
          planningError: 'PLANNING_FAILED',
          frozenInvocation: null,
        },
      }
    }
    if (method === 'DELETE' && pathname === `/api/chat/chats/${chat.id}`) {
      return { status: 204, json: null }
    }
    throw new Error(`unexpected call ${method} ${pathname}`)
  }
  return { ctx: { call }, requests }
}

test('model request debug case executes the draft wire and read-only invariants', async () => {
  // 测试意图：真实执行 thread.model_request_debug_draft_settings（只 fake HTTP），证明它发送
  // {model, environmentName:null} 草稿、GET 被 405 拒绝，并且查询前后 head/version/sequence 不变。
  const fake = modelRequestDebugFixture()
  await getCase('thread.model_request_debug_draft_settings').run(fake.ctx)

  const debugPosts = fake.requests.filter(
    (request) => request.method === 'POST' && request.path.endsWith('/model-request-debug'),
  )
  assert.ok(debugPosts.length >= 1)
  const draft = debugPosts[0].body
  assert.deepEqual(draft.model, {
    modelName: 'fixture-model',
    variant: 'default',
  })
  // environmentName 是 required-nullable：显式出现且为 null。
  assert.equal(Object.hasOwn(draft, 'environmentName'), true)
  assert.equal(draft.environmentName, null)
  // 缺 model 与非 canonical environmentName 的拒绝请求确实被发出。
  assert.ok(debugPosts.some((request) => request.body.model == null))
  assert.ok(debugPosts.some((request) => request.body.environmentName === 'bad/name'))
  assert.ok(
    fake.requests.some(
      (request) =>
        request.method === 'GET' && request.path.endsWith('/model-request-debug'),
    ),
  )
})
