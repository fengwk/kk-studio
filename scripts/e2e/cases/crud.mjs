import { assert, envelopeData, expectHttpError, pageResults, cid } from '../lib/http.mjs'
import { baseModelConfig, providerCreateBody } from '../lib/fixtures.mjs'
import {
  branchSettingsOf,
  createChat,
  createChatThread,
  getThreadSnapshot,
  listChatThreads,
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
  title: 'Provider name identity 创建/更新/硬删除/同名重建',
  docs: '记录存续期间 name 不可修改；PUT 仅更新 editable properties；DELETE 带 expectedVersion 硬删除后同名可重建，version 从 0 重新开始且读取到新数据',
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

    // 硬删除后同名可重建：version 从 0 重新开始，且读取到新 body 的数据（旧 v2 baseUrl 不残留）。
    const recreated = envelopeData(
      (
        await ctx.call('POST', '/api/ai/catalog/providers', {
          ...providerCreateBody(suffix),
          description: 'e2e provider recreated',
          baseUrl: 'https://example.com/v3',
        })
      ).json,
    )
    assert(recreated.name === created.name, JSON.stringify(recreated))
    assert(String(recreated.version) === '0', JSON.stringify(recreated))
    assert(
      recreated.baseUrl === 'https://example.com/v3'
        && recreated.description === 'e2e provider recreated',
      JSON.stringify(recreated),
    )
    const relisted = pageResults(
      (await ctx.call('GET', '/api/ai/catalog/providers?pageNumber=1&pageSize=100')).json,
    )
    assert(
      relisted.some((provider) => provider.name === created.name),
      'recreated Provider not listed',
    )
    await deleteProvider(ctx, recreated)
  },
})

registerCase({
  id: 'crud.model.lifecycle',
  level: 'L1',
  title: 'Model 复合 name identity 创建/更新/硬删除/同名重建',
  docs: '记录存续期间 providerName/name 不可修改；PUT 不修改 identity；modelName 可包含 /；DELETE 硬删除后同名可重建，version 从 0 重新开始；Provider 保持到最后再删',
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

    // 硬删除后同名可重建：version 从 0 重新开始，且读取到新 body 的数据（旧 updated 配置不残留）。
    const recreated = envelopeData(
      (
        await ctx.call('POST', '/api/ai/catalog/models', {
          providerName: provider.name,
          name,
          description: 'recreated',
          config: baseModelConfig(),
        })
      ).json,
    )
    assert(
      recreated.providerName === provider.name && recreated.name === name,
      JSON.stringify(recreated),
    )
    assert(String(recreated.version) === '0', JSON.stringify(recreated))
    assert(recreated.description === 'recreated', JSON.stringify(recreated))
    assert(
      recreated.config.defaultVariant === 'default',
      `recreated Model must not carry old config: ${JSON.stringify(recreated)}`,
    )
    assert(await findModel(ctx, provider.name, name), 'recreated Model not listed')
    await deleteModel(ctx, recreated)
    await deleteProvider(ctx, provider)
  },
})

registerCase({
  id: 'crud.agent.lifecycle',
  level: 'L1',
  title: 'Agent name identity 创建/更新/硬删除/同名重建',
  docs: '记录存续期间 name/model 不可修改；PUT 仅更新 prompt/variant/config 等 editable properties；DELETE 硬删除后同名可重建，version 从 0 重新开始且读取到新数据',
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

    // 硬删除后同名可重建：version 从 0 重新开始，且读取到新 body 的数据（旧 updated prompt 不残留）。
    const recreated = envelopeData(
      (
        await ctx.call('POST', '/api/ai/catalog/agents', {
          name,
          description: 'recreated',
          systemPrompt: 'recreated prompt',
          model: modelRef(model),
          variant: model.config.defaultVariant,
          config: { tools: [], skills: [] },
        })
      ).json,
    )
    assert(recreated.name === name && recreated.model === modelRef(model), JSON.stringify(recreated))
    assert(String(recreated.version) === '0', JSON.stringify(recreated))
    assert(recreated.systemPrompt === 'recreated prompt', JSON.stringify(recreated))
    const relisted = pageResults(
      (await ctx.call('GET', '/api/ai/catalog/agents?pageNumber=1&pageSize=100')).json,
    )
    assert(relisted.some((candidate) => candidate.name === name), 'recreated Agent not listed')
    await ctx.call(
      'DELETE',
      `/api/ai/catalog/agents/${encodeURIComponent(name)}?expectedVersion=${encodeURIComponent(recreated.version)}`,
    )
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
  id: 'crud.chat.thread_branch_settings_independent',
  level: 'L1',
  title: 'Chat 默认值与 Thread branchSettings 相互独立',
  docs: 'Chat 仅保存 agentName/yoloEnabled/可选默认 environmentName；Thread 创建携带完整 branchSettings（Environment 路由名称或 null）；更新 Chat 默认值不改变既有 Thread',
  async run(ctx) {
    const agent = await firstAgent(ctx)
    const suffix = cid().slice(0, 8)
    const chat = await createChat(ctx, {
      title: `e2e-chat-env-${suffix}`,
      agentName: agent.name,
      yoloEnabled: false,
    })
    let cleanupChat = chat
    try {
      assert(
        chat.agentName === agent.name
          && chat.yoloEnabled === false
          && chat.environmentName === null,
        JSON.stringify(chat),
      )
      // 先创建 Thread，再更新 Chat 默认值，最后 reread 同一 Thread：更新 Chat 不影响既有 Thread
      // 的 branchSettings（immutable Environment route；Thread 快照是运行时事实）。
      const requested = {
        environmentName: null,
        agentName: agent.name,
        model: modelSelectionFor(agent),
        thinkingLevel: 'off',
        activeTools: [],
      }
      const threadSnapshot = await createChatThread(ctx, chat.id, {
        title: null,
        yoloEnabled: false,
        branchSettings: requested,
      })
      const thread = threadSnapshot.thread
      assert(thread.yoloEnabled === false, JSON.stringify(thread))
      assert(
        JSON.stringify(thread.branchSettings) === JSON.stringify(requested),
        JSON.stringify({ expected: requested, actual: thread.branchSettings }),
      )
      const updated = envelopeData(
        (
          await ctx.call('PUT', `/api/ai/chat/${chat.id}`, {
            yoloEnabled: true,
            expectedVersion: chat.version,
          })
        ).json,
      )
      cleanupChat = updated
      assert(
        updated.agentName === agent.name
          && updated.yoloEnabled === true
          && updated.environmentName === null,
        JSON.stringify(updated),
      )
      // 同一 Thread reread：branchSettings 逐字段不变。
      const reread = await getThreadSnapshot(ctx, thread.threadId)
      assert(
        JSON.stringify(reread.thread.branchSettings) === JSON.stringify(requested),
        JSON.stringify({ expected: requested, actual: reread.thread.branchSettings }),
      )
      assert(reread.thread.yoloEnabled === false, JSON.stringify(reread.thread))
      // 缺 branchSettings 的创建 => 400（mapper requireNonNull）。
      await expectHttpError(
        () => ctx.call('POST', `/api/ai/chat/${encodeURIComponent(chat.id)}/threads`, { title: null }),
        { status: 400, messageIncludes: /branchSettings/i },
      )
    } finally {
      await deleteChat(ctx, cleanupChat)
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
  id: 'crud.chat.thread_association_list',
  level: 'L1',
  title: 'Chat Thread 数组列表与幂等 association',
  docs: 'Chat-scoped POST 返回创建快照；PUT /threads/{threadId} association 幂等；GET 返回数组且新到旧；未知 Thread 关联 => 404',
  async run(ctx) {
    const agent = await firstAgent(ctx)
    const suffix = cid().slice(0, 8)
    const chat = await createChat(ctx, {
      title: `e2e-chat-thread-${suffix}`,
      agentName: agent.name,
      yoloEnabled: true,
    })
    const first = await createChatThread(ctx, chat.id, {
      title: null,
      yoloEnabled: true,
      branchSettings: branchSettingsOf(
        { name: agent.name },
        modelSelectionFor(agent),
        { environmentName: null },
      ),
    })
    const second = await createChatThread(ctx, chat.id, {
      title: null,
      yoloEnabled: true,
      branchSettings: branchSettingsOf(
        { name: agent.name },
        modelSelectionFor(agent),
        { environmentName: null },
      ),
    })
    try {
      assert(first.thread.status === 'IDLE', JSON.stringify(first.thread))
      assert(String(first.thread.revision) === '0', JSON.stringify(first.thread))
      assert(first.thread.sessionId && first.thread.headEntryId, JSON.stringify(first.thread))

      // 幂等 association（同一 Thread 两次 PUT 均 204）。
      const third = await createChatThread(ctx, chat.id, {
        title: null,
        yoloEnabled: true,
        branchSettings: branchSettingsOf(
          { name: agent.name },
          modelSelectionFor(agent),
          { environmentName: null },
        ),
      })
      await ctx.call(
        'PUT',
        `/api/ai/chat/${encodeURIComponent(chat.id)}/threads/${encodeURIComponent(first.thread.threadId)}`,
      )
      await ctx.call(
        'PUT',
        `/api/ai/chat/${encodeURIComponent(chat.id)}/threads/${encodeURIComponent(first.thread.threadId)}`,
      )
      const scoped = await listChatThreads(ctx, chat.id)
      const scopedIds = scoped.map((thread) => String(thread.threadId))
      assert(scopedIds.includes(String(first.thread.threadId)), 'first Thread missing')
      assert(scopedIds.includes(String(second.thread.threadId)), 'second Thread missing')
      assert(scopedIds.includes(String(third.thread.threadId)), 'third Thread missing')
      // 新到旧：最近创建（third）排在最前。
      assert(scopedIds[0] === String(third.thread.threadId), `expected newest-first: ${JSON.stringify(scoped)}`)

      // 未知 Thread 关联 => 404。
      await expectHttpError(
        () =>
          ctx.call(
            'PUT',
            `/api/ai/chat/${encodeURIComponent(chat.id)}/threads/999999999`,
          ),
        { status: 404, messageIncludes: /unknown|not found/i },
      )
    } finally {
      await deleteChat(ctx, chat)
    }
  },
})


/** 由 Agent 的 provider/model 字符串构造 model selection（只切第一个 '/'，保留 model name 内后续 '/'）。 */
function modelSelectionFor(agent) {
  const separator = String(agent.model || '').indexOf('/')
  if (separator <= 0) {
    throw new Error(`agent.model must be provider/model: ${JSON.stringify(agent)}`)
  }
  const providerName = String(agent.model).slice(0, separator)
  const modelName = String(agent.model).slice(separator + 1)
  if (!providerName || !modelName) {
    throw new Error(`agent.model must be provider/model: ${JSON.stringify(agent)}`)
  }
  return { providerName, modelName, variant: agent.variant || 'default' }
}

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
