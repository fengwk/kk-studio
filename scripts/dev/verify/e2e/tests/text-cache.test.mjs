import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import { REAL_MODEL_DEFINITIONS } from '../cases/real.mjs'
import { getCase } from '../lib/registry.mjs'

const usages = JSON.parse(readFileSync(new URL('./resources/text-cache/usage.json', import.meta.url), 'utf8'))

// 完整执行已注册 case；只模拟 HTTP 边界，保留真实 harness DTO 校验与两次稳定快照轮询。
function mockContext(def, { hitAt = 2, fault } = {}) {
  const artifacts = []
  const deleted = []
  const chatId = randomUUID()
  const rootId = randomUUID()
  let thread
  let entries = []
  let requestCount = 0
  let snapshotReads = 0
  const response = (data, status = 200) => ({ status, json: { data } })
  const entry = (entryType, payload) => ({
    entryId: randomUUID(), entryType, payloadJson: JSON.stringify(payload),
  })
  return {
    artifacts,
    deleted,
    get requestCount() { return requestCount },
    writeArtifact(name, text) {
      artifacts.push({ name, data: JSON.parse(text) })
    },
    async call(method, url, body) {
      if (method === 'DELETE') {
        deleted.push(url)
        return response(null)
      }
      if (url.startsWith('/api/ai/catalog/providers?')) {
        return response([{ name: def.providerName, providerType: def.providerType,
          configured: true, baseUrl: 'https://mock.invalid', config: { private: 'excluded' } }])
      }
      if (url.startsWith('/api/ai/catalog/models?')) {
        return response([{ providerName: def.providerName, name: def.modelName,
          config: { variants: def.variants.map((id) => ({ id })), private: 'excluded' } }])
      }
      if (method === 'POST' && url === '/api/ai/catalog/agents') {
        assert.deepEqual(body.config.tools, [])
        assert.equal(body.model, `${def.providerName}/${def.modelName}`)
        assert.equal(body.variant, def.variant)
        return response({ name: body.name, version: '0' }, 201)
      }
      if (method === 'POST' && url === '/api/ai/chats') {
        return response({ id: chatId, agentName: body.agentName, version: '0' }, 201)
      }
      if (
        method === 'POST'
        && (url === '/api/harness/command-batches'
          || url === `/api/harness/threads/${thread?.threadId}/command-batches`)
      ) {
        requestCount++
        snapshotReads = 0
        if (url === '/api/harness/command-batches') {
          assert.equal(body.target.type, 'NEW_SESSION')
          thread = { threadId: body.target.threadId, sessionId: body.target.sessionId,
            name: 'cache-test', parentThreadId: null, status: 'IDLE', processing: false,
            executionControl: 'RUNNABLE' }
        } else {
          // 既有 Thread 续写面 owner-free：body 只带 cursor 与 commands。
          assert.equal(body.expectedHeadEntryId, thread.headEntryId)
          assert.equal(body.expectedNextCommandSequence, thread.nextCommandSequence)
          assert.equal(Object.hasOwn(body, 'owner'), false)
          assert.equal(Object.hasOwn(body, 'target'), false)
        }
        const text = body.commands[0].contents[0].text
        const marker = text.match(/MARKER-[\w-]+/)[0]
        const hit = requestCount === hitAt
        const usage = def.providerType === 'anthropic'
          ? usages[hit ? 'anthropicHit' : 'anthropicMiss'] : usages[hit ? 'hit' : 'miss']
        const end = { outcome: 'COMPLETED', continueModel: false }
        if (requestCount === 2 && fault === 'outcome') end.outcome = 'FAILED'
        if (requestCount === 2 && fault === 'continueModel') end.continueModel = true
        entries.push(
          entry('TURN_START', {}),
          entry('MESSAGE', { message: { role: 'USER', contents: [{ type: 'text', text }] } }),
          entry('MESSAGE', { message: { role: 'ASSISTANT', contents: [{ type: 'text', text: marker }] },
            assistantMetadata: { usage: { ...usage, extraProviderData: 'excluded' } } }),
        )
        if (!(requestCount === 2 && fault === 'missingEnd')) entries.push(entry('TURN_END', end))
        thread = { ...thread, headEntryId: entries.at(-1).entryId,
          nextCommandSequence: String(requestCount + 1), version: String(requestCount) }
        return response({
          session: { sessionId: thread.sessionId, name: 'cache-session' },
          rootEntry: { entryId: rootId, sessionId: thread.sessionId, entryType: 'ROOT' },
          thread, replayed: false,
          acceptedCommands: body.commands.map((command) => ({
            type: command.type, idempotencyKey: command.idempotencyKey,
            threadId: thread.threadId, sequence: String(requestCount),
          })),
        }, 202)
      }
      if (method === 'GET' && url === `/api/harness/threads/${thread?.threadId}`) {
        snapshotReads++
        const snapshot = { thread, entries, modelInvocation: null, queuedCommands: [], toolInvocations: [] }
        // 稳定轮询之后的独立快照发生变化，验证 case 自身也会拒绝新出现的 active work。
        if (requestCount === 2 && snapshotReads === 3) {
          if (fault === 'activeInvocation') snapshot.modelInvocation = { id: randomUUID() }
          if (fault === 'queuedCommands') snapshot.queuedCommands = [{ sequence: '3' }]
          if (fault === 'status') snapshot.thread = { ...thread, status: 'MODEL_RUNNING' }
        }
        return response(snapshot)
      }
      throw new Error(`Unexpected mock call: ${method} ${url}`)
    },
  }
}

for (const def of REAL_MODEL_DEFINITIONS) {
  test(`${def.idSuffix}: registered text_cache hit preserves allowlisted evidence and real gate`, async () => {
    // 意图：四协议真正执行注册 case，命中即停，验证七字段 allowlist 与单请求 ratio（含长缓存写入）。
    const c = getCase(`real.text_cache.${def.idSuffix}`)
    assert.deepEqual([...c.requires], ['real'])
    const ctx = mockContext(def)
    await c.run(ctx)
    assert.equal(ctx.requestCount, 2)
    assert.equal(ctx.deleted.length, 2)
    assert.equal(ctx.artifacts.length, 2)
    const artifact = ctx.artifacts.at(-1)
    assert.equal(artifact.name, `real-text-cache-${def.idSuffix}.json`)
    assert.deepEqual(Object.keys(artifact.data).sort(),
      ['modelDef', 'threadId', 'cachePolicy', 'cacheOutcome', 'modelRequestCount', 'rounds'].sort())
    assert.equal(artifact.data.cachePolicy, def.providerType === 'google' ? 'observed' : 'required')
    assert.equal(artifact.data.cacheOutcome, 'HIT')
    assert.equal(artifact.data.modelRequestCount, 2)
    assert.deepEqual(artifact.data.rounds.map((round) => round.ordinal), [1, 2])
    assert.equal(artifact.data.rounds[1].cacheReadRatio, def.providerType === 'anthropic' ? 0.2 : 1 / 3)
    assert.deepEqual(artifact.data.rounds[0].usage,
      def.providerType === 'anthropic' ? usages.anthropicMiss : usages.miss)
    assert.deepEqual(artifact.data.rounds[1].usage,
      def.providerType === 'anthropic' ? usages.anthropicHit : usages.hit)
    assert.equal(ctx.artifacts[0].data.cacheOutcome, 'NOT_EVALUATED')
    assert.equal(JSON.stringify(artifact).includes('excluded'), false)
    assert.equal(JSON.stringify(artifact).includes('mock.invalid'), false)
    assert.equal(JSON.stringify(artifact).includes('MARKER-'), false)
  })
}

for (const suffix of ['deepseek_chat', 'google_gemini']) {
  test(`${suffix}: no hit has explicit outcome and fixed budget`, async () => {
    // 意图：DeepSeek 四次无命中必须失败但保留全部数值；Gemini 两次无命中仍通过且不误报 HIT。
    const def = REAL_MODEL_DEFINITIONS.find((candidate) => candidate.idSuffix === suffix)
    const ctx = mockContext(def, { hitAt: Infinity })
    const run = getCase(`real.text_cache.${suffix}`).run(ctx)
    if (suffix === 'deepseek_chat') await assert.rejects(run, /at least one follow-up/)
    else await run
    const budget = suffix === 'deepseek_chat' ? 4 : 2
    assert.equal(ctx.requestCount, budget)
    assert.equal(ctx.artifacts.length, budget)
    const evidence = ctx.artifacts.at(-1).data
    assert.equal(evidence.modelRequestCount, budget)
    assert.equal(evidence.cacheOutcome, 'NOT_OBSERVED')
    assert.deepEqual(evidence.rounds.map((round) => round.ordinal), Array.from({ length: budget }, (_, i) => i + 1))
    assert.ok(evidence.rounds.every((round) => round.cacheReadRatio === 0))
    assert.equal(ctx.deleted.length, 2)
  })
}

test('DeepSeek hit on final allowed follow-up retains all four observations', async () => {
  // 意图：最多三轮 follow-up 的末轮命中应通过，前面 miss 不得提前失败或触发额外请求。
  const def = REAL_MODEL_DEFINITIONS.find((candidate) => candidate.idSuffix === 'deepseek_chat')
  const ctx = mockContext(def, { hitAt: 4 })
  await getCase(`real.text_cache.${def.idSuffix}`).run(ctx)
  assert.equal(ctx.requestCount, 4)
  assert.deepEqual(ctx.artifacts.map((artifact) => artifact.data.cacheOutcome),
    ['NOT_EVALUATED', 'NOT_OBSERVED', 'NOT_OBSERVED', 'HIT'])
  assert.equal(ctx.artifacts.at(-1).data.modelRequestCount, 4)
})

for (const [fault, error] of [
  ['outcome', /COMPLETED TURN_END/],
  ['continueModel', /COMPLETED TURN_END/],
  ['missingEnd', /COMPLETED TURN_END/],
  ['activeInvocation', /no active model invocation/],
  ['queuedCommands', /queued commands must be empty/],
  ['status', /snapshot thread must be IDLE/],
]) {
  test(`follow-up rejects ${fault} despite valid marker, usage and previous completed turn`, async () => {
    // 意图：不能借首轮 TURN_END 或稳定轮询的旧快照通过新轮次验收，失败保留首轮且清理资源。
    const def = REAL_MODEL_DEFINITIONS.find((candidate) => candidate.idSuffix === 'deepseek_chat')
    const ctx = mockContext(def, { fault })
    await assert.rejects(getCase(`real.text_cache.${def.idSuffix}`).run(ctx), error)
    assert.equal(ctx.requestCount, 2)
    assert.equal(ctx.artifacts.length, 1)
    assert.equal(ctx.artifacts[0].data.modelRequestCount, 1)
    assert.equal(ctx.artifacts[0].data.cacheOutcome, 'NOT_EVALUATED')
    assert.equal(ctx.deleted.length, 2)
  })
}
