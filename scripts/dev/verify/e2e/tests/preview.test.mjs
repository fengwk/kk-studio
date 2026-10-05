import assert from 'node:assert/strict'
import test from 'node:test'

import '../cases/seed-and-harness.mjs'
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
