import { once } from 'node:events'
import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import { isDeepStrictEqual } from 'node:util'

import {
  assert,
  envelopeData,
  expectHttpError,
  httpJson,
  pageResults,
  cid,
} from '../lib/http.mjs'
import { baseModelConfig, providerCreateBody } from '../lib/fixtures.mjs'
import {
  createConfiguredChatThread,
  getThread,
  getThreadSnapshot,
  listSessionEntries,
  snapshotEntries,
  snapshotInputs,
  threadPageData,
  updateThreadHead,
  waitForQuiescentThread,
  waitForThreadInputApplied,
} from '../lib/harness.mjs'
import { registerCase, getCase } from '../lib/registry.mjs'

const PI_MODEL_CATALOG = JSON.parse(
  readFileSync(
    new URL(
      '../../../core/src/test/resources/fun/fengwk/kkstudio/core/ai/runtime/persistence/postgresql/pi-model-catalog.json',
      import.meta.url,
    ),
    'utf8',
  ),
)

registerCase({
  id: 'seed.structured_model_config',
  level: 'L1',
  title: 'Model 公开契约与 Pi 默认目录一致',
  docs: 'GET /api/ai/catalog/models：按 providerName/name 完整匹配 Pi 0.82.1 快照；禁止资源 bigint ID 与旧 JSON 字段',
  async run(ctx) {
    const { json } = await ctx.call('GET', '/api/ai/catalog/models?pageNumber=1&pageSize=50')
    const models = pageResults(json)
    const actualCatalog = models
      .map((model) => ({
        provider: model.providerName,
        name: model.name,
        description: model.description,
        config: model.config,
      }))
      .sort(compareModel)
    const expectedCatalog = [...PI_MODEL_CATALOG].sort(compareModel)
    assert(
      actualCatalog.length === expectedCatalog.length,
      `expected ${expectedCatalog.length} Pi models, got ${actualCatalog.length}`,
    )
    const mismatch = actualCatalog.findIndex(
      (model, index) => !isDeepStrictEqual(model, expectedCatalog[index]),
    )
    assert(
      mismatch === -1,
      `Pi model catalog mismatch at index ${mismatch}: ${JSON.stringify({
        expected: expectedCatalog[mismatch],
        actual: actualCatalog[mismatch],
      })}`,
    )
    for (const model of models) {
      assert(model.providerName && model.name, JSON.stringify(model))
      assert(!('id' in model) && !('providerId' in model), JSON.stringify(model))
      assert('config' in model, `missing config keys=${Object.keys(model)}`)
      assert(!('configJson' in model), 'legacy configJson must not be public')
      assert(!('capabilitiesJson' in model), 'legacy capabilitiesJson must not be public')
      assert(model.config?.defaultVariant, JSON.stringify(model.config))
      assert(
        Array.isArray(model.config?.variants) && model.config.variants.length > 0,
        JSON.stringify(model.config),
      )
      assert(Number(model.config?.limit?.context || 0) > 0, JSON.stringify(model.config))
    }
    ctx.vars.seedModel = models.find(
      (model) => model.providerName === 'minimax' && model.name === 'MiniMax-M2.7',
    )
    assert(ctx.vars.seedModel, 'missing minimax/MiniMax-M2.7 seed model')
  },
})

registerCase({
  id: 'seed.agent_and_provider',
  level: 'L1',
  title: 'Agent/Provider seed 可用',
  docs: 'seed Agent 以 name/model 引用；Provider 以 name 标识，七种协议映射正确',
  async run(ctx) {
    const { json: agentsJson } = await ctx.call(
      'GET',
      '/api/ai/catalog/agents?pageNumber=1&pageSize=50',
    )
    const agents = pageResults(agentsJson)
    assert(agents.length > 0, 'no agents')
    const agent = agents.find((candidate) => candidate.name === 'default-assistant') || agents[0]
    assert(agent.name && agent.model && agent.variant && agent.config, JSON.stringify(agent))
    assert(!('id' in agent) && !('modelId' in agent), JSON.stringify(agent))
    ctx.vars.agent = agent

    const { json: providersJson } = await ctx.call(
      'GET',
      '/api/ai/catalog/providers?pageNumber=1&pageSize=50',
    )
    const providers = pageResults(providersJson)
    const expectedProviderTypes = new Map([
      ['minimax', 'openai_response'],
      ['openai', 'openai_response'],
      ['xai', 'openai_response'],
      ['deepseek', 'openai'],
      ['google', 'google'],
      ['anthropic', 'anthropic'],
      ['zai', 'openai'],
    ])
    for (const [name, providerType] of expectedProviderTypes) {
      const provider = providers.find((candidate) => candidate.name === name)
      assert(provider?.providerType === providerType, JSON.stringify({ name, providerType, provider }))
      assert(!('id' in provider), JSON.stringify(provider))
    }
    ctx.vars.provider = providers.find((provider) => provider.name === 'minimax')
  },
})

registerCase({
  id: 'thread.chat_scoped_create_atomic',
  level: 'L1',
  title: 'Chat-scoped Thread 原子创建 Session/ROOT',
  docs: '创建 Chat 后 POST /threads body={environmentName} => 201；Thread 已绑定 Session/ROOT，epoch=0，并直接返回当前 environmentName',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    const { chat, thread, session } = await createConfiguredChatThread(ctx, {
      title: `e2e-thread-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      threadEnvironmentName: null,
      yoloEnabled: false,
    })
    assert(thread.status === 'IDLE', JSON.stringify(thread))
    assert(Number(thread.executionEpoch) === 0, JSON.stringify(thread))
    assert(thread.environmentName === null, JSON.stringify(thread))
    assert(thread.sessionId === session.sessionId && thread.headEntryId, JSON.stringify(thread))
    for (const hidden of [
      'activeAgentDefinitionId',
      'activeAgentName',
      'activeEnvironmentName',
      'modelId',
      'variant',
      'yoloEnabled',
    ]) {
      assert(!(hidden in thread), `Thread leaked ${hidden}: ${JSON.stringify(thread)}`)
    }
    const entries = await listSessionEntries(ctx, session.sessionId)
    assert(
      entries.length === 1
        && entries[0].entryType === 'ROOT'
        && entries[0].entryId === thread.headEntryId,
      JSON.stringify(entries),
    )
    const listed = threadPageData((await ctx.call('GET', '/api/ai/runtime/threads')).json).items
    assert(listed.some((item) => item.threadId === thread.threadId), 'created Thread missing')
    ctx.vars.boundChat = chat
    ctx.vars.boundThread = thread
    ctx.vars.boundRootEntryId = thread.headEntryId
  },
})

registerCase({
  id: 'thread.stale_epoch_rejected',
  level: 'L1',
  title: 'stale expectedExecutionEpoch 被拒绝',
  docs: 'head rebind 先推进 epoch；随后 message/head 携带旧 epoch => 409，且不写入 Input',
  async run(ctx) {
    if (!ctx.vars.boundThread) await getCase('thread.chat_scoped_create_atomic').run(ctx)
    const original = ctx.vars.boundThread
    const rebound = await updateThreadHead(ctx, original, original.headEntryId)
    await expectHttpError(
      () =>
        ctx.call('POST', `/api/ai/runtime/threads/${rebound.threadId}/messages`, {
          agentName: ctx.vars.agent.name,
          yoloEnabled: false,
          content: 'stale',
          clientMessageId: cid(),
          expectedExecutionEpoch: Number(original.executionEpoch),
        }),
      { status: 409, messageIncludes: /stale execution epoch/i },
    )
    await expectHttpError(
      () =>
        ctx.call('PUT', `/api/ai/runtime/threads/${rebound.threadId}/head`, {
          headEntryId: rebound.headEntryId,
          expectedExecutionEpoch: Number(original.executionEpoch),
        }),
      { status: 409, messageIncludes: /stale execution epoch/i },
    )
    assert(
      (await snapshotInputs(ctx, rebound.threadId)).length === 0,
      'stale mutation must not enqueue input',
    )
    ctx.vars.boundThread = rebound
  },
})

registerCase({
  id: 'thread.rebind_same_session',
  level: 'L1',
  title: '同 Session 内 PUT /head 回退到 ROOT',
  docs: 'PUT /head 指向当前 ROOT => head 不变、epoch+1、sessionId 不变',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    const { thread, session } = await createConfiguredChatThread(ctx, {
      title: `e2e-rebind-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
    })
    const rewound = await updateThreadHead(ctx, thread, thread.headEntryId)
    assert(rewound.headEntryId === thread.headEntryId, JSON.stringify(rewound))
    assert(
      Number(rewound.executionEpoch) === Number(thread.executionEpoch) + 1,
      JSON.stringify(rewound),
    )
    const view = await getThread(ctx, thread.threadId)
    assert(view.sessionId === session.sessionId && view.status === 'IDLE', JSON.stringify(view))
    const path = await snapshotEntries(ctx, thread.threadId)
    assert(path.length === 1 && path[0].entryId === thread.headEntryId, JSON.stringify(path))
    ctx.vars.rebindThread = view
  },
})

registerCase({
  id: 'thread.rebind_cross_session',
  level: 'L1',
  title: '跨 Session PUT /head 复用同一 Thread',
  docs: '把一个 Thread 的 head 指向另一 Session ROOT => threadId 不变，派生 sessionId 切换',
  async run(ctx) {
    if (!ctx.vars.rebindThread) await getCase('thread.rebind_same_session').run(ctx)
    const thread = ctx.vars.rebindThread
    const other = await createConfiguredChatThread(ctx, {
      title: `e2e-other-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
    })
    assert(other.thread.threadId !== thread.threadId, 'expected a distinct Thread')
    const moved = await updateThreadHead(ctx, thread, other.thread.headEntryId)
    assert(moved.threadId === thread.threadId, 'cross-session rebind changed Thread')
    assert(moved.headEntryId === other.thread.headEntryId, JSON.stringify(moved))
    const view = await getThread(ctx, thread.threadId)
    assert(view.sessionId === other.session.sessionId, JSON.stringify(view))
    ctx.vars.rebindThread = view
  },
})

registerCase({
  id: 'thread.stop_then_rebind',
  level: 'L1',
  title: 'stop 后 Thread 立即可 rebind',
  docs: '消息直接携带 agentName/yoloEnabled；stop 递增 epoch 并取消执行，随后 PUT /head 成功',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    const { thread } = await createConfiguredChatThread(ctx, {
      title: `e2e-stop-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
    })
    await ctx.call('POST', `/api/ai/runtime/threads/${thread.threadId}/messages`, {
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
      content: 'e2e stop then rebind',
      clientMessageId: cid(),
      expectedExecutionEpoch: Number(thread.executionEpoch),
    })
    const { json: stopJson } = await ctx.call(
      'POST',
      `/api/ai/runtime/threads/${thread.threadId}/stop`,
      { expectedExecutionEpoch: Number(thread.executionEpoch) },
    )
    const stop = envelopeData(stopJson)
    assert(Number(stop.executionEpoch) === Number(thread.executionEpoch) + 1, JSON.stringify(stop))
    const stopped = await getThread(ctx, thread.threadId)
    const rebound = await updateThreadHead(ctx, stopped, thread.headEntryId)
    assert(rebound.headEntryId === thread.headEntryId, JSON.stringify(rebound))
    assert(
      Number(rebound.executionEpoch) === Number(stopped.executionEpoch) + 1,
      JSON.stringify(rebound),
    )
  },
})

registerCase({
  id: 'thread.turn_settings_wysiwyg_failure',
  level: 'L1',
  title: '每条消息冻结可见名称引用并显式报告解析失败',
  docs: 'USER_MESSAGE 直接携带 agentName/yoloEnabled；不存在 Agent => ASSISTANT_ERROR，且不创建伪 ModelInvocation',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    const { thread } = await createConfiguredChatThread(ctx, {
      title: `e2e-turn-settings-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
    })
    const missingAgentName = `missing-agent-${cid().slice(0, 8)}`
    const clientMessageId = cid()
    const { json: messageJson } = await ctx.call(
      'POST',
      `/api/ai/runtime/threads/${thread.threadId}/messages`,
      {
        agentName: missingAgentName,
        yoloEnabled: true,
        content: 'must fail before provider invocation',
        clientMessageId,
        expectedExecutionEpoch: Number(thread.executionEpoch),
      },
    )
    const input = envelopeData(messageJson)
    await waitForThreadInputApplied(ctx, thread.threadId, input.inputId)
    const payload = JSON.parse(input.payloadJson)
    assert(
      isDeepStrictEqual(payload.turnSettings, {
        agentName: missingAgentName,
        yoloEnabled: true,
      }),
      JSON.stringify(payload),
    )
    const snapshot = await getThreadSnapshot(ctx, thread.threadId)
    const errorEntry = (snapshot.entries || []).find((entry) => entry.entryType === 'ASSISTANT_ERROR')
    assert(errorEntry, JSON.stringify(snapshot.entries))
    const errorPayload = JSON.parse(errorEntry.payloadJson)
    assert(
      String(errorPayload.error?.message || '').includes('AGENT_NOT_FOUND'),
      JSON.stringify(errorPayload),
    )
    assert(
      (snapshot.modelInvocations || []).length === 0,
      `planning failure created ModelInvocation: ${JSON.stringify(snapshot.modelInvocations)}`,
    )

    const { json: replayJson } = await ctx.call(
      'POST',
      `/api/ai/runtime/threads/${thread.threadId}/messages`,
      {
        agentName: ctx.vars.agent.name,
        yoloEnabled: false,
        content: 'ignored retry body',
        clientMessageId,
        expectedExecutionEpoch: Number(thread.executionEpoch),
      },
    )
    const replay = envelopeData(replayJson)
    assert(
      replay.inputId === input.inputId && replay.payloadJson === input.payloadJson,
      JSON.stringify({ input, replay }),
    )
  },
})

registerCase({
  id: 'thread.environment_capability_projection',
  level: 'L1',
  title: '非 READY Environment 只投影 Platform Tool',
  docs:
    'Agent 可配置 Environment Tool/Skill；null、stale、offline Thread Environment 均不阻断 planning，只向 fake Provider 暴露 Platform Tool；Provider 返回不可见 read 时写入明确 ASSISTANT_ERROR 且不物化 ToolInvocation',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const environmentToolName = 'read'
    const tools = pageResults((await ctx.call('GET', '/api/ai/catalog/tools')).json)
    const platformTool = tools.find((tool) => tool.type === 'PLATFORM')
    assert(platformTool?.name, `missing selectable Platform Tool: ${JSON.stringify(tools)}`)
    assert(
      tools.find((tool) => tool.name === environmentToolName)?.type === 'ENVIRONMENT',
      `read must be an Environment Tool: ${JSON.stringify(tools)}`,
    )

    const fakeProvider = await startUnavailableToolProvider(environmentToolName)
    let provider = null
    let model = null
    let agent = null
    const chats = []
    try {
      provider = envelopeData(
        (
          await ctx.call('POST', '/api/ai/catalog/providers', {
            ...providerCreateBody(`visibility-${suffix}`),
            baseUrl: fakeProvider.baseUrl,
            providerType: 'openai',
          })
        ).json,
      )
      model = envelopeData(
        (
          await ctx.call('POST', '/api/ai/catalog/models', {
            providerName: provider.name,
            name: `e2e-visibility-model-${suffix}`,
            description: 'E2E fake provider model for Environment visibility.',
            config: baseModelConfig({
              abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
              variants: [{ id: 'default' }],
              defaultVariant: 'default',
            }),
          })
        ).json,
      )
      agent = envelopeData(
        (
          await ctx.call('POST', '/api/ai/catalog/agents', {
            name: `e2e-visibility-agent-${suffix}`,
            description: 'E2E Agent with Platform and Environment capabilities.',
            systemPrompt: 'Return the requested tool call exactly as supplied by the provider.',
            model: `${provider.name}/${model.name}`,
            variant: model.config.defaultVariant,
            config: {
              tools: [platformTool.name, environmentToolName],
              skills: [`e2e-visibility-skill-${suffix}`],
            },
          })
        ).json,
      )

      // L1 does not start a daemon; unique named bindings exercise the same non-READY projection
      // used by stale/offline Environments without inventing a daemon lifecycle API.
      for (const scenario of [
        { name: 'null', environmentName: null },
        { name: 'stale', environmentName: `e2e-stale-${suffix}` },
        { name: 'offline', environmentName: `e2e-offline-${suffix}` },
      ]) {
        const requestCount = fakeProvider.requests.length
        const { chat, thread } = await createConfiguredChatThread(ctx, {
          title: `e2e-capability-${scenario.name}-${suffix}`,
          agentName: agent.name,
          threadEnvironmentName: scenario.environmentName,
          yoloEnabled: false,
        })
        chats.push(chat)
        assert(
          (thread.environmentName ?? null) === scenario.environmentName,
          JSON.stringify({ scenario, thread }),
        )

        const { json: messageJson } = await ctx.call(
          'POST',
          `/api/ai/runtime/threads/${thread.threadId}/messages`,
          {
            agentName: agent.name,
            yoloEnabled: false,
            content: `request invisible ${environmentToolName} for ${scenario.name}`,
            clientMessageId: cid(),
            expectedExecutionEpoch: Number(thread.executionEpoch),
          },
        )
        const input = envelopeData(messageJson)
        await waitForThreadInputApplied(ctx, thread.threadId, input.inputId, {
          timeoutMs: 60_000,
        })
        await waitForQuiescentThread(ctx, thread.threadId, { timeoutMs: 60_000 })
        const snapshot = await getThreadSnapshot(ctx, thread.threadId)
        const errorEntry = [...(snapshot.entries || [])]
          .reverse()
          .find((entry) => entry.entryType === 'ASSISTANT_ERROR')
        assert(errorEntry, JSON.stringify(snapshot.entries))
        const errorPayload = JSON.parse(errorEntry.payloadJson)
        const errorMessage = String(errorPayload.error?.message || '')
        assert(
          errorMessage.includes(
            `tool is not available in this model invocation: ${environmentToolName}`,
          ),
          JSON.stringify(errorPayload),
        )
        assert(
          errorMessage.includes(`available tools: [${platformTool.name}]`),
          JSON.stringify(errorPayload),
        )
        assert(
          (snapshot.toolInvocations || []).length === 0,
          `invisible Tool must not materialize ToolInvocation: ${JSON.stringify(snapshot)}`,
        )
        assert(
          (snapshot.modelInvocations || []).length >= 1,
          `Provider call must create a ModelInvocation: ${JSON.stringify(snapshot)}`,
        )
        const persistedRequest = JSON.parse(snapshot.modelInvocations.at(-1).requestJson)
        const persistedTools = (persistedRequest.toolBindings || []).map(
          (binding) => binding.descriptor?.name,
        )
        assert(
          isDeepStrictEqual(persistedTools, [platformTool.name])
            && (persistedRequest.skillBindings || []).length === 0,
          `ModelInvocation persisted unavailable capabilities: ${JSON.stringify(persistedRequest)}`,
        )
        assert(
          fakeProvider.requests.length > requestCount,
          `fake Provider was not called for ${scenario.name}: ${JSON.stringify(fakeProvider.requests)}`,
        )
        const providerRequest = fakeProvider.requests.at(-1)
        const requestedTools = (providerRequest.tools || []).map(
          (tool) => tool.function?.name || tool.name,
        )
        assert(
          isDeepStrictEqual(requestedTools, [platformTool.name]),
          `Environment capability leaked into Provider request: ${JSON.stringify({
            scenario,
            requestedTools,
            providerRequest,
          })}`,
        )
      }
    } finally {
      for (const chat of chats.reverse()) {
        await ignoreCleanupError(() =>
          ctx.call(
            'DELETE',
            `/api/ai/chat/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
          ),
        )
      }
      if (agent?.name) {
        await ignoreCleanupError(() =>
          ctx.call(
            'DELETE',
            `/api/ai/catalog/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(agent.version)}`,
          ),
        )
      }
      if (model?.providerName && model?.name) {
        await ignoreCleanupError(() =>
          ctx.call(
            `DELETE`,
            `/api/ai/catalog/models?providerName=${encodeURIComponent(model.providerName)}&modelName=${encodeURIComponent(model.name)}&expectedVersion=${encodeURIComponent(model.version)}`,
          ),
        )
      }
      if (provider?.name) {
        await ignoreCleanupError(() =>
          ctx.call(
            'DELETE',
            `/api/ai/catalog/providers/${encodeURIComponent(provider.name)}?expectedVersion=${encodeURIComponent(provider.version)}`,
          ),
        )
      }
      await ignoreCleanupError(() => fakeProvider.close())
    }
  },
})

registerCase({
  id: 'thread.custom_message_turn_settings',
  level: 'L1',
  title: 'CUSTOM_MESSAGE 同样携带逐消息可见配置',
  docs: '自定义 system/user 消息不依赖 Thread 隐藏配置，Input payload 保存精确 TurnSettings',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    const { thread } = await createConfiguredChatThread(ctx, {
      title: `e2e-custom-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
    })
    const missingAgentName = `missing-custom-agent-${cid().slice(0, 8)}`
    const { json } = await ctx.call(
      'POST',
      `/api/ai/runtime/threads/${thread.threadId}/messages/custom`,
      {
        role: 'system',
        content: 'custom context',
        agentName: missingAgentName,
        yoloEnabled: false,
        clientMessageId: cid(),
        expectedExecutionEpoch: Number(thread.executionEpoch),
      },
    )
    const input = envelopeData(json)
    await waitForThreadInputApplied(ctx, thread.threadId, input.inputId)
    const payload = JSON.parse(input.payloadJson)
    assert(
      isDeepStrictEqual(payload.turnSettings, {
        agentName: missingAgentName,
        yoloEnabled: false,
      }),
      JSON.stringify(payload),
    )
  },
})

registerCase({
  id: 'thread_snapshot.unknown_thread_404',
  level: 'L1',
  title: '未知 Thread snapshot 404',
  docs: 'GET /api/ai/runtime/threads/999999999/snapshot => 404 unknown thread',
  async run(ctx) {
    await expectHttpError(
      () => ctx.call('GET', '/api/ai/runtime/threads/999999999/snapshot'),
      { status: 404, messageIncludes: /unknown thread/ },
    )
  },
})

registerCase({
  id: 'frontend.proxy_model_contract',
  level: 'L1',
  title: 'Frontend 代理 model 契约',
  docs: '5173 /api/ai/catalog/models 返回 name-based 结构化 config',
  async run(ctx) {
    assert(ctx.vars.frontendUrl, 'frontendUrl required')
    const { json } = await httpJson(
      ctx.vars.frontendUrl,
      'GET',
      '/api/ai/catalog/models?pageNumber=1&pageSize=1',
    )
    const model = pageResults(json)[0]
    assert(model?.providerName && model?.name && model?.config?.defaultVariant, JSON.stringify(model))
    assert(!('id' in model) && !('providerId' in model), JSON.stringify(model))
  },
})

registerCase({
  id: 'harness.retry_policy_round_trip',
  level: 'L1',
  title: 'Retry policy GET/PUT 全量替换往返',
  docs: '读取 original；PUT 合法且可观察差异的策略；再 GET 断言一致；finally 恢复 original',
  async run(ctx) {
    const { json: originalJson } = await ctx.call('GET', '/api/ai/runtime/settings/retry-policy')
    const original = envelopeData(originalJson)
    assert(original && typeof original === 'object', JSON.stringify(originalJson))
    assert(
      ['maxRetries', 'backoffStrategy', 'baseDelayMillis', 'maxDelayMillis'].every(
        (key) => key in original,
      ),
      JSON.stringify(original),
    )
    const next = {
      maxRetries: Number(original.maxRetries) === 2 ? 4 : 2,
      backoffStrategy: original.backoffStrategy === 'FIXED' ? 'EXPONENTIAL' : 'FIXED',
      baseDelayMillis: Number(original.baseDelayMillis) === 4000 ? 3000 : 4000,
      maxDelayMillis: Number(original.maxDelayMillis) === 8000 ? 10000 : 8000,
    }
    if (next.maxDelayMillis < next.baseDelayMillis) next.maxDelayMillis = next.baseDelayMillis
    try {
      await ctx.call('PUT', '/api/ai/runtime/settings/retry-policy', next)
      const reread = envelopeData(
        (await ctx.call('GET', '/api/ai/runtime/settings/retry-policy')).json,
      )
      assert(Number(reread.maxRetries) === next.maxRetries, JSON.stringify(reread))
      assert(reread.backoffStrategy === next.backoffStrategy, JSON.stringify(reread))
      assert(Number(reread.baseDelayMillis) === next.baseDelayMillis, JSON.stringify(reread))
      assert(Number(reread.maxDelayMillis) === next.maxDelayMillis, JSON.stringify(reread))
    } finally {
      await ctx.call('PUT', '/api/ai/runtime/settings/retry-policy', original)
    }
  },
})

registerCase({
  id: 'harness.realtime_stream_policy_round_trip',
  level: 'L1',
  title: 'Realtime Stream policy GET/PUT 往返',
  docs: '读取 original；PUT 可观察差异的 maxLength；再 GET 断言一致；finally 恢复 original',
  async run(ctx) {
    const original = envelopeData(
      (await ctx.call('GET', '/api/ai/runtime/settings/realtime-stream-policy')).json,
    )
    const originalMaxLength = Number(original?.maxLength)
    assert(Number.isSafeInteger(originalMaxLength) && originalMaxLength > 0, JSON.stringify(original))
    const next = { maxLength: originalMaxLength === 5_000 ? 5_001 : 5_000 }
    try {
      await ctx.call('PUT', '/api/ai/runtime/settings/realtime-stream-policy', next)
      const reread = envelopeData(
        (await ctx.call('GET', '/api/ai/runtime/settings/realtime-stream-policy')).json,
      )
      assert(Number(reread.maxLength) === next.maxLength, JSON.stringify(reread))
    } finally {
      await ctx.call('PUT', '/api/ai/runtime/settings/realtime-stream-policy', {
        maxLength: originalMaxLength,
      })
    }
  },
})

function compareModel(left, right) {
  return `${left.provider}/${left.name}`.localeCompare(`${right.provider}/${right.name}`)
}

async function startUnavailableToolProvider(toolName) {
  const requests = []
  let callSequence = 0
  const server = createServer(async (request, response) => {
    const body = await readRequestBody(request)
    if (request.method !== 'POST' || request.url !== '/v1/chat/completions') {
      response.writeHead(404, { 'Content-Type': 'text/plain' })
      response.end('not found')
      return
    }
    requests.push(JSON.parse(body))
    const callId = `e2e-hidden-${++callSequence}`
    const base = {
      id: `e2e-chat-${callSequence}`,
      object: 'chat.completion.chunk',
      created: Math.floor(Date.now() / 1000),
      model: 'e2e-visibility-model',
    }
    const firstChunk = {
      ...base,
      choices: [
        {
          index: 0,
          delta: {
            role: 'assistant',
            tool_calls: [
              {
                index: 0,
                id: callId,
                type: 'function',
                function: { name: toolName, arguments: '{}' },
              },
            ],
          },
          finish_reason: null,
        },
      ],
    }
    const terminalChunk = {
      ...base,
      choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }],
    }
    const payload =
      `data: ${JSON.stringify(firstChunk)}\n\n`
      + `data: ${JSON.stringify(terminalChunk)}\n\n`
      + 'data: [DONE]\n\n'
    response.writeHead(200, {
      'Cache-Control': 'no-cache',
      Connection: 'close',
      'Content-Type': 'text/event-stream; charset=utf-8',
    })
    response.end(payload)
  })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address()
  assert(address && typeof address === 'object', `fake Provider did not bind: ${address}`)
  return {
    baseUrl: `http://127.0.0.1:${address.port}/v1`,
    requests,
    async close() {
      if (!server.listening) return
      await new Promise((resolve, reject) => {
        server.close((error) => (error ? reject(error) : resolve()))
      })
    },
  }
}

async function readRequestBody(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  return Buffer.concat(chunks).toString('utf8')
}

async function ignoreCleanupError(action) {
  try {
    await action()
  } catch {
    // Preserve the primary case failure; the matrix uses an isolated E2E database.
  }
}
