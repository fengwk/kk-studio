import assert from 'node:assert/strict'
import test from 'node:test'

import { ProviderPreviewTrap } from '../cases/seed-and-harness.mjs'
import { getCase } from '../lib/registry.mjs'
import { HttpError } from '../lib/http.mjs'

const id = (n) => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`

function fixture(reasons = ['PREVIEW_STALE_CURSOR', 'PREVIEW_PLANNING_FAILED']) {
  let thread
  let previews = 0
  const ctx = {
    vars: {
      agent: { name: 'fixture-agent', model: 'fixture-provider/fixture-model', variant: 'default' },
      seedModel: { config: { defaultVariant: 'default' } },
    },
    async call(method, path, body) {
      if (method === 'POST' && path === '/api/ai/chats') {
        return { status: 201, json: { data: { id: id(1), agentName: body.agentName, environmentName: body.environmentName ?? null } } }
      }
      if (method === 'POST' && path === '/api/harness/command-batches') {
        assert.match(body.target.rootSettings.agentName, /^e2e-preview-missing-/)
        const { threadId, sessionId } = body.target
        thread = {
          threadId, sessionId, headEntryId: id(2), parentThreadId: null,
          name: 'main', version: '1', nextCommandSequence: '2', status: 'IDLE', processing: false,
          executionControl: 'RUNNABLE',
          yoloPolicy: { mode: 'DISABLE', rootThreadId: null },
        }
        return { status: 202, json: { data: {
          session: { sessionId, name: 'fixture' },
          rootEntry: { entryId: id(2), entryType: 'ROOT', sessionId },
          thread, replayed: false,
          acceptedCommands: body.commands.map((command, i) => ({
            type: command.type, idempotencyKey: command.idempotencyKey,
            threadId, sequence: String(i + 1),
          })),
        } } }
      }
      if (method === 'GET' && path === `/api/harness/threads/${thread.threadId}`) {
        return { status: 200, json: { data: {
          thread, queuedCommands: [], modelInvocation: null, toolInvocations: [],
        } } }
      }
      if (method === 'POST' && path.endsWith('/provider-request-preview')) {
        // owner-free 预览 body：cursor 直接挂在顶层，不携带 owner/target。
        assert.equal(body.expectedHeadEntryId, thread.headEntryId)
        assert.equal(body.expectedNextCommandSequence, previews === 0 ? '3' : '2')
        assert.equal(Object.hasOwn(body, 'owner'), false)
        assert.equal(Object.hasOwn(body, 'target'), false)
        const reason = reasons[previews++]
        throw new HttpError(409, JSON.stringify({
          errors: { reason, detail: 'PREVIEW_STALE_CURSOR PREVIEW_PLANNING_FAILED' },
        }), path)
      }
      assert.fail(`unexpected call ${method} ${path}`)
    },
  }
  return { ctx, previews: () => previews }
}

test('free preview case checks stale and planning wire reasons', async () => {
  // 测试意图：执行真实 case、只 fake HTTP，证明两次 409 按结构化 reason 区分且仍校验游标不变。
  const fake = fixture()
  await getCase('thread.provider_request_preview_guard').run(fake.ctx)
  assert.equal(fake.previews(), 2)
})

test('free preview case rejects missing or mismatched reasons even when detail contains codes', async () => {
  // 测试意图：不接受仅有 status 或描述文本的假阳性，分别锁住 stale/planning 两个断言。
  for (const reasons of [
    [undefined, 'PREVIEW_PLANNING_FAILED'],
    ['PREVIEW_THREAD_BUSY', 'PREVIEW_PLANNING_FAILED'],
    ['PREVIEW_STALE_CURSOR', undefined],
    ['PREVIEW_STALE_CURSOR', 'PREVIEW_PROVIDER_UNAVAILABLE'],
  ]) {
    await assert.rejects(
      getCase('thread.provider_request_preview_guard').run(fixture(reasons).ctx),
      /preview (stale cursor|planning) reason missing or mismatched/,
    )
  }
})

test('readonly preview case is a free L1 host-mock case covering provider unavailability', () => {
  // 测试意图：readonly case 依赖宿主 127.0.0.1 trap，必须登记 host-mock；docs 必须锁住 409 契约。
  const caseDef = getCase('thread.provider_request_preview_readonly')
  assert.ok(caseDef)
  assert.equal(caseDef.level, 'L1')
  assert.deepEqual([...caseDef.requires], ['host-mock'])
  assert.match(caseDef.docs, /PREVIEW_PROVIDER_UNAVAILABLE/)
})

test('readonly preview builds its local model config and cleans up a failed fixture', async () => {
  // 在模型创建边界停止，验证前置配置构造实际可执行且失败后清理专用 Provider。
  const stopped = new Error('stop at model creation')
  let config = null
  let deleted = false
  const ctx = {
    async call(method, path, body) {
      if (method === 'POST' && path === '/api/ai/catalog/providers') {
        return { json: { data: { name: body.name, version: '1' } } }
      }
      if (method === 'POST' && path === '/api/ai/catalog/models') {
        config = body.config
        throw stopped
      }
      if (method === 'DELETE' && path.startsWith('/api/ai/catalog/providers/')) {
        deleted = true
        return { json: null }
      }
      assert.fail(`unexpected fixture call: ${method} ${path}`)
    },
    writeArtifact() {
      assert.fail('successful cleanup must not write failure artifacts')
    },
  }
  await assert.rejects(getCase('thread.provider_request_preview_readonly').run(ctx), error => error === stopped)
  assert.deepEqual(config.limit, { context: 4096, output: 128 })
  assert.ok(config.pricing)
  assert.equal(deleted, true)
})

/**
 * 只 fake HTTP 的 Model Request Debug 后端：按当前契约建模只读 POST（草稿 model/environmentName
 * 回显、缺失 Agent 确定性 PLANNING_FAILED 的安全空投影）与缺 model / 非 canonical environmentName
 * 的 400、GET 的 405，用来真实执行 `thread.model_request_debug_draft_settings` case。
 */
function modelRequestDebugFixture() {
  const agent = {
    name: 'fixture-agent',
    type: 'USER',
    model: 'fixture-provider/fixture-model',
    variant: 'default',
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
    if (method === 'GET' && pathname === '/api/ai/catalog/agents') {
      return { status: 200, json: { data: { results: [agent] } } }
    }
    if (method === 'GET' && pathname === '/api/ai/catalog/models') {
      return { status: 200, json: { data: { results: [model] } } }
    }
    if (method === 'POST' && pathname === '/api/ai/chats') {
      chat = {
        id: id(1),
        title: body.title,
        agentName: body.agentName,
        environmentName: body.environmentName ?? null,
        yoloEnabled: body.yoloEnabled === true,
        version: '0',
      }
      return { status: 201, json: { data: { ...chat } } }
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
        processing: false,
        executionControl: 'RUNNABLE',
        yoloPolicy: { mode: 'DISABLE', rootThreadId: null },
      }
      return {
        status: 202,
        json: {
          data: {
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
        },
      }
    }
    if (method === 'GET' && pathname === `/api/harness/threads/${thread.threadId}`) {
      return {
        status: 200,
        json: {
          data: {
            thread: { ...thread },
            entries: [],
            queuedCommands: [],
            modelInvocation: null,
            toolInvocations: [],
            modelAttemptFailures: [],
            stopReceipts: [],
          },
        },
      }
    }
    if (pathname === `/api/harness/threads/${thread.threadId}/model-request-debug`) {
      if (method === 'GET') {
        throw new HttpError(405, JSON.stringify({ message: 'Method Not Allowed' }), pathname)
      }
      assert.equal(method, 'POST')
      if (body.model == null) {
        throw new HttpError(400, JSON.stringify({ message: 'model must not be null' }), pathname)
      }
      if (body.environmentName != null && String(body.environmentName).includes('/')) {
        throw new HttpError(
          400,
          JSON.stringify({ message: "environmentName must not contain '/'" }),
          pathname,
        )
      }
      return {
        status: 200,
        json: {
          data: {
            kind: 'NEXT_REQUEST_PREVIEW',
            generatedAt: '2026-10-02T00:00:00Z',
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
        },
      }
    }
    if (method === 'DELETE' && pathname === `/api/ai/chats/${chat.id}`) {
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
    providerName: 'fixture-provider',
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

test('preview trap only counts calls, retains no request material, and closes cleanly', async () => {
  // 测试意图：证明 trap 只累计调用次数、不保留 header/token/body，且 close 后监听真正释放。
  const trap = new ProviderPreviewTrap()
  const secret = 'sk-preview-secret-must-not-be-retained'
  await trap.start()
  const url = `${trap.baseUrl('/v1')}/chat/completions`
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { Authorization: `Bearer ${secret}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ apiKey: secret }),
    })
    assert.equal(response.status, 500)
    await response.text()
    assert.equal(trap.requests, 1)
    // 只暴露计数：任何自有字符串属性都不携带提交的凭据。
    assert.ok(
      !Object.values(trap).some((value) => typeof value === 'string' && value.includes(secret)),
      'trap must not retain request material',
    )
  } finally {
    await trap.close()
  }
  assert.equal(trap.listening, false)
  await assert.rejects(
    fetch(url, { signal: AbortSignal.timeout(3000) }),
    'closed trap must release its listener',
  )
})
