import { assert, envelopeData, expectHttpError, pageResults, cid } from '../lib/http.mjs'
import { baseModelConfig, providerCreateBody } from '../lib/fixtures.mjs'
import {
  createConfiguredChatThread,
  threadPageData,
} from '../lib/harness.mjs'
import { registerCase } from '../lib/registry.mjs'

registerCase({
  id: 'crud.provider.invalid_name',
  level: 'L1',
  title: 'Provider 非法 name 拒绝',
  docs: 'POST /api/ai/catalog/providers name 空白或包含 / => 400',
  async run(ctx) {
    for (const [name, messageIncludes] of [
      ['', /name must not be blank/i],
      ['provider/name', /name must not contain/i],
    ]) {
      await expectHttpError(
        () =>
          ctx.call('POST', '/api/ai/catalog/providers', {
            name,
            description: 'x',
            providerType: 'openai',
            baseUrl: 'https://example.com/v1',
            credential: 'sk',
            modelCallTimeoutMillis: 1000,
            modelCallIdleTimeoutMillis: 1000,
          }),
        { status: 400, messageIncludes },
      )
    }
  },
})

registerCase({
  id: 'crud.provider.invalid_missing_type',
  level: 'L1',
  title: 'Provider 缺少 providerType 拒绝',
  docs: 'POST /api/ai/catalog/providers 无 providerType => 400',
  async run(ctx) {
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/ai/catalog/providers', {
          name: `e2e-missing-type-${cid().slice(0, 6)}`,
          description: 'x',
          baseUrl: 'https://example.com/v1',
          credential: 'sk',
          modelCallTimeoutMillis: 1000,
          modelCallIdleTimeoutMillis: 1000,
        }),
      { status: 400, messageIncludes: /providerType|blank|required/i },
    )
  },
})

registerCase({
  id: 'crud.model.invalid_update_config',
  level: 'L1',
  title: 'Model 非法更新不破坏原配置',
  docs: 'name identity 通过 query 指定；PUT 非法 defaultVariant => 400，随后 GET 原配置不变',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const provider = envelopeData(
      (await ctx.call('POST', '/api/ai/catalog/providers', providerCreateBody(suffix))).json,
    )
    const modelName = `e2e-model-invupd-${suffix}`
    const model = envelopeData(
      (
        await ctx.call('POST', '/api/ai/catalog/models', {
          providerName: provider.name,
          name: modelName,
          description: 'ok',
          config: baseModelConfig(),
        })
      ).json,
    )
    await expectHttpError(
      () =>
        ctx.call(
          'PUT',
          modelPath(provider.name, modelName),
          {
            description: 'bad',
            config: baseModelConfig({ defaultVariant: 'nope' }),
            expectedVersion: model.version,
          },
        ),
      { status: 400, messageIncludes: /defaultVariant/i },
    )
    const still = await findModel(ctx, provider.name, modelName)
    assert(still?.config?.defaultVariant === 'default', JSON.stringify(still))
    await deleteModel(ctx, still)
    await deleteProvider(ctx, provider)
  },
})

registerCase({
  id: 'crud.agent.invalid_name',
  level: 'L1',
  title: 'Agent 非法 name 拒绝',
  docs: 'POST /api/ai/catalog/agents name 空白或包含 / => 400',
  async run(ctx) {
    const model = await firstModel(ctx)
    for (const [name, messageIncludes] of [
      ['  ', /name|blank/i],
      ['agent/name', /name must not contain/i],
    ]) {
      await expectHttpError(
        () =>
          ctx.call('POST', '/api/ai/catalog/agents', {
            name,
            description: 'd',
            systemPrompt: 's',
            model: modelRef(model),
            variant: model.config.defaultVariant,
            config: { tools: [], skills: [] },
          }),
        { status: 400, messageIncludes },
      )
    }
  },
})

registerCase({
  id: 'crud.agent.invalid_variant',
  level: 'L1',
  title: 'Agent 不存在的 Variant 拒绝',
  docs: 'POST /api/ai/catalog/agents variant 不属于所选 Model => 400',
  async run(ctx) {
    const model = await firstModel(ctx)
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/ai/catalog/agents', {
          name: `e2e-invalid-variant-${cid().slice(0, 8)}`,
          description: 'd',
          systemPrompt: 's',
          model: modelRef(model),
          variant: '__missing_variant__',
          config: { tools: [], skills: [] },
        }),
      { status: 400, messageIncludes: /variant/i },
    )
  },
})

registerCase({
  id: 'crud.provider.lifecycle',
  level: 'L1',
  title: 'Provider name identity 创建/更新/删除',
  docs: 'name 创建后不可修改；PUT 仅更新 editable properties；按 name path 删除',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const created = envelopeData(
      (await ctx.call('POST', '/api/ai/catalog/providers', providerCreateBody(suffix))).json,
    )
    assert(created?.name && !('id' in created), JSON.stringify(created))
    const listed = pageResults(
      (await ctx.call('GET', '/api/ai/catalog/providers?pageNumber=1&pageSize=100')).json,
    )
    assert(listed.some((provider) => provider.name === created.name), 'created Provider not listed')

    const updated = envelopeData(
      (
        await ctx.call(
          'PUT',
          `/api/ai/catalog/providers/${encodeURIComponent(created.name)}`,
          {
            description: 'e2e provider updated',
            providerType: 'openai',
            baseUrl: 'https://example.com/v2',
            credential: '',
            modelCallTimeoutMillis: 1_800_000,
            modelCallIdleTimeoutMillis: 120_000,
            expectedVersion: created.version,
          },
        )
      ).json,
    )
    assert(updated.name === created.name, JSON.stringify(updated))
    assert(updated.baseUrl === 'https://example.com/v2', JSON.stringify(updated))
    assert(updated.configured === true, 'empty credential should keep configured')
    await deleteProvider(ctx, updated)
    const after = pageResults(
      (await ctx.call('GET', '/api/ai/catalog/providers?pageNumber=1&pageSize=100')).json,
    )
    assert(!after.some((provider) => provider.name === created.name), 'Provider still listed')
  },
})

registerCase({
  id: 'crud.model.lifecycle',
  level: 'L1',
  title: 'Model 复合 name identity 创建/更新/删除',
  docs: 'Model 由 providerName/name 标识；PUT 不修改 identity；modelName 可包含 /',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const provider = envelopeData(
      (await ctx.call('POST', '/api/ai/catalog/providers', providerCreateBody(suffix))).json,
    )
    const name = `vendor/e2e-model-${suffix}`
    const model = envelopeData(
      (
        await ctx.call('POST', '/api/ai/catalog/models', {
          providerName: provider.name,
          name,
          description: 'create',
          config: baseModelConfig(),
        })
      ).json,
    )
    assert(
      model?.providerName === provider.name && model.name === name && !('id' in model),
      JSON.stringify(model),
    )
    const updatedConfig = baseModelConfig({
      limit: { context: 8192, output: 1024 },
      abilities: { tools: false, reasoning: true, inputModalities: ['TEXT', 'IMAGE'] },
      defaultVariant: 'fast',
      variants: [
        { id: 'fast', temperature: 0.1 },
        { id: 'quality', temperature: 0.4, reasoningEffort: 'high' },
      ],
    })
    const updated = envelopeData(
      (
        await ctx.call('PUT', modelPath(provider.name, name), {
          description: 'updated',
          config: updatedConfig,
          expectedVersion: model.version,
        })
      ).json,
    )
    assert(updated.providerName === provider.name && updated.name === name, JSON.stringify(updated))
    assert(updated.config.defaultVariant === 'fast', JSON.stringify(updated.config))
    await deleteModel(ctx, updated)
    assert(!(await findModel(ctx, provider.name, name)), 'Model still listed')
    await deleteProvider(ctx, provider)
  },
})

registerCase({
  id: 'crud.agent.lifecycle',
  level: 'L1',
  title: 'Agent name identity 创建/更新/删除',
  docs: 'Agent name/model 创建后不可修改；PUT 仅更新 prompt/variant/config 等 editable properties',
  async run(ctx) {
    const model = await firstModel(ctx)
    const name = `e2e-agent-${cid().slice(0, 8)}`
    const agent = envelopeData(
      (
        await ctx.call('POST', '/api/ai/catalog/agents', {
          name,
          description: 'create',
          systemPrompt: 'you are e2e',
          model: modelRef(model),
          variant: model.config.defaultVariant,
          config: { tools: [], skills: [] },
        })
      ).json,
    )
    assert(agent?.name === name && agent.model === modelRef(model) && !('id' in agent), JSON.stringify(agent))
    const updated = envelopeData(
      (
        await ctx.call('PUT', `/api/ai/catalog/agents/${encodeURIComponent(name)}`, {
          description: 'updated',
          systemPrompt: 'updated prompt',
          variant: model.config.defaultVariant,
          config: { tools: [], skills: [] },
          expectedVersion: agent.version,
        })
      ).json,
    )
    assert(updated.name === name && updated.model === modelRef(model), JSON.stringify(updated))
    assert(updated.systemPrompt === 'updated prompt', JSON.stringify(updated))
    await ctx.call(
      'DELETE',
      `/api/ai/catalog/agents/${encodeURIComponent(name)}?expectedVersion=${encodeURIComponent(updated.version)}`,
    )
    const agents = pageResults(
      (await ctx.call('GET', '/api/ai/catalog/agents?pageNumber=1&pageSize=100')).json,
    )
    assert(!agents.some((candidate) => candidate.name === name), 'Agent still listed')
  },
})

registerCase({
  id: 'crud.chat.invalid_agent_name',
  level: 'L1',
  title: 'Chat 不存在 Agent name 拒绝',
  docs: 'POST /api/ai/chat 请求体中的 agentName 必须引用现有 Agent，否则返回 400 validation',
  async run(ctx) {
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/ai/chat', {
          title: 'bad-agent',
          agentName: `missing-${cid().slice(0, 8)}`,
          yoloEnabled: false,
        }),
      { status: 400, messageIncludes: /agent|unknown/i },
    )
  },
})

registerCase({
  id: 'crud.chat.visible_settings',
  level: 'L1',
  title: 'Chat 可见 Environment/YOLO 创建、更新与清除',
  docs: 'Chat 是唯一可见发送配置；environmentName 显式 null 清除，yoloEnabled 可即时更新',
  async run(ctx) {
    const agent = await firstAgent(ctx)
    const suffix = cid().slice(0, 8)
    const initialEnvironment = `e2e-chat-env-${suffix}`
    const updatedEnvironment = `e2e-chat-env-updated-${suffix}`
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/ai/chat', {
          title: `e2e-invalid-chat-env-${suffix}`,
          agentName: agent.name,
          environmentName: ` invalid-${suffix} `,
          yoloEnabled: false,
        }),
      { status: 400, messageIncludes: /environmentName|whitespace|canonical/i },
    )
    let chat = envelopeData(
      (
        await ctx.call('POST', '/api/ai/chat', {
          title: `e2e-chat-env-${suffix}`,
          agentName: agent.name,
          environmentName: initialEnvironment,
          yoloEnabled: false,
        })
      ).json,
    )
    try {
      assert(
        chat.environmentName === initialEnvironment && chat.yoloEnabled === false,
        JSON.stringify(chat),
      )
      chat = envelopeData(
        (
          await ctx.call('PUT', `/api/ai/chat/${chat.id}`, {
            environmentName: updatedEnvironment,
            yoloEnabled: true,
            expectedVersion: chat.version,
          })
        ).json,
      )
      assert(
        chat.environmentName === updatedEnvironment && chat.yoloEnabled === true,
        JSON.stringify(chat),
      )
      chat = envelopeData(
        (
          await ctx.call('PUT', `/api/ai/chat/${chat.id}`, {
            environmentName: null,
            expectedVersion: chat.version,
          })
        ).json,
      )
      assert(chat.environmentName == null && chat.yoloEnabled === true, JSON.stringify(chat))
    } finally {
      await deleteChat(ctx, chat)
    }
  },
})

registerCase({
  id: 'crud.model.delete_unknown_rejected',
  level: 'L1',
  title: '删除不存在 Model 被拒绝',
  docs: 'DELETE 使用 providerName/modelName query；未知复合 identity => 404',
  async run(ctx) {
    await expectHttpError(
      () =>
        ctx.call(
          'DELETE',
          '/api/ai/catalog/models?providerName=missing-provider&modelName=missing-model&expectedVersion=0',
        ),
      { status: 404, messageIncludes: /not found|unknown|model/i },
    )
  },
})

registerCase({
  id: 'crud.chat.lifecycle',
  level: 'L1',
  title: 'Chat 创建/更新/删除',
  docs: 'POST/PUT/DELETE Chat；Agent name 稳定引用；空白 title 400；删除后 404',
  async run(ctx) {
    const agent = await firstAgent(ctx)
    const chat = envelopeData(
      (
        await ctx.call('POST', '/api/ai/chat', {
          title: 'e2e-chat',
          agentName: agent.name,
          yoloEnabled: false,
        })
      ).json,
    )
    const updated = envelopeData(
      (
        await ctx.call('PUT', `/api/ai/chat/${chat.id}`, {
          title: 'e2e-chat-upd',
          expectedVersion: chat.version,
        })
      ).json,
    )
    assert(updated.title === 'e2e-chat-upd' && updated.agentName === agent.name, JSON.stringify(updated))
    await expectHttpError(
      () =>
        ctx.call('PUT', `/api/ai/chat/${chat.id}`, {
          title: '   ',
          expectedVersion: updated.version,
        }),
      { status: 400, messageIncludes: /title.*blank/i },
    )
    await deleteChat(ctx, updated)
    await expectHttpError(() => ctx.call('GET', `/api/ai/chat/${chat.id}`), { status: 404 })
  },
})

registerCase({
  id: 'crud.chat.thread_association_pagination',
  level: 'L1',
  title: 'Chat 原子建 Thread、跨 Chat 关联与 opaque cursor 分页',
  docs: 'Chat-scoped POST 直接返回 bound Thread；PUT association 幂等；Chat/global 列表均使用 {items,nextCursor}',
  async run(ctx) {
    const agent = await firstAgent(ctx)
    const suffix = cid().slice(0, 8)
    const target = await createConfiguredChatThread(ctx, {
      title: `e2e-chat-thread-${suffix}`,
      agentName: agent.name,
      environmentName: `e2e-chat-thread-env-${suffix}`,
      yoloEnabled: true,
    })
    const source = await createConfiguredChatThread(ctx, {
      title: `e2e-source-thread-${suffix}`,
      agentName: agent.name,
    })
    try {
      assert(target.thread.status === 'IDLE', JSON.stringify(target.thread))
      assert(Number(target.thread.executionEpoch) === 0, JSON.stringify(target.thread))
      assert(target.thread.sessionId && target.thread.headEntryId, JSON.stringify(target.thread))
      await ctx.call(
        'PUT',
        `/api/ai/chat/${encodeURIComponent(target.chat.id)}/threads/${encodeURIComponent(source.thread.threadId)}`,
      )
      await ctx.call(
        'PUT',
        `/api/ai/chat/${encodeURIComponent(target.chat.id)}/threads/${encodeURIComponent(source.thread.threadId)}`,
      )
      const scopedPage = threadPageData(
        (
          await ctx.call(
            'GET',
            `/api/ai/chat/${encodeURIComponent(target.chat.id)}/threads?sort=created&limit=100`,
          )
        ).json,
        'Chat Thread page',
      )
      const scopedIds = new Set(scopedPage.items.map((thread) => String(thread.threadId)))
      assert(scopedIds.has(String(target.thread.threadId)), 'target Thread missing')
      assert(scopedIds.has(String(source.thread.threadId)), 'associated Thread missing')

      const firstGlobal = threadPageData(
        (await ctx.call('GET', '/api/ai/runtime/threads?sort=recent&limit=1')).json,
      )
      assert(firstGlobal.items.length === 1 && firstGlobal.nextCursor, JSON.stringify(firstGlobal))
      const secondGlobal = threadPageData(
        (
          await ctx.call(
            'GET',
            `/api/ai/runtime/threads?sort=recent&cursor=${encodeURIComponent(firstGlobal.nextCursor)}&limit=1`,
          )
        ).json,
      )
      const firstIds = new Set(firstGlobal.items.map((thread) => String(thread.threadId)))
      assert(
        secondGlobal.items.every((thread) => !firstIds.has(String(thread.threadId))),
        'keyset pages must be disjoint',
      )
    } finally {
      await deleteChat(ctx, target.chat)
      await deleteChat(ctx, source.chat)
    }
  },
})

async function firstModel(ctx) {
  const models = pageResults(
    (await ctx.call('GET', '/api/ai/catalog/models?pageNumber=1&pageSize=10')).json,
  )
  assert(models[0]?.providerName && models[0]?.name, 'need seeded Model')
  return models[0]
}

async function firstAgent(ctx) {
  const agents = pageResults(
    (await ctx.call('GET', '/api/ai/catalog/agents?pageNumber=1&pageSize=10')).json,
  )
  assert(agents[0]?.name, 'need seeded Agent')
  return agents[0]
}

async function findModel(ctx, providerName, name) {
  const models = pageResults(
    (await ctx.call('GET', '/api/ai/catalog/models?pageNumber=1&pageSize=100')).json,
  )
  return models.find(
    (model) => model.providerName === providerName && model.name === name,
  )
}

function modelRef(model) {
  return `${model.providerName}/${model.name}`
}

function modelPath(providerName, name) {
  return `/api/ai/catalog/models?providerName=${encodeURIComponent(providerName)}&modelName=${encodeURIComponent(name)}`
}

async function deleteModel(ctx, model) {
  await ctx.call(
    'DELETE',
    `${modelPath(model.providerName, model.name)}&expectedVersion=${encodeURIComponent(model.version)}`,
  )
}

async function deleteProvider(ctx, provider) {
  await ctx.call(
    'DELETE',
    `/api/ai/catalog/providers/${encodeURIComponent(provider.name)}?expectedVersion=${encodeURIComponent(provider.version)}`,
  )
}

async function deleteChat(ctx, chat) {
  await ctx.call(
    'DELETE',
    `/api/ai/chat/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
  )
}
