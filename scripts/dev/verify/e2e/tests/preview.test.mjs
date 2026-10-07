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
        return { status: 201, json: { data: { id: id(1), agentName: body.agentName } } }
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
