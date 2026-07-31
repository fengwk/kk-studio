import { assert, envelopeData, expectHttpError, pageResults, cid } from '../lib/http.mjs'
import {
  baseModelConfig,
  providerCreateBody,
  providerUpdateBody,
} from '../lib/fixtures.mjs'
import { registerCase } from '../lib/registry.mjs'

registerCase({
  id: 'crud.provider.invalid_blank_base_url_type_ok_name_only_fails',
  level: 'L1',
  title: 'Provider 空白 name 拒绝（创建校验）',
  docs: '与 lifecycle 中断言互补：仅 name 空白即可 400',
  async run(ctx) {
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/ai/catalog/providers', {
          name: '',
          description: 'x',
          providerType: 'openai',
          baseUrl: 'https://example.com/v1',
          credential: 'sk',
          modelCallTimeoutMillis: 1000,
          modelCallIdleTimeoutMillis: 1000,
        }),
      { status: 400, messageIncludes: /name must not be blank/i },
    )
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
  title: 'Model 更新为非法 config 被拒绝且不破坏原配置',
  docs: '先创建合法 model，再 PUT 非法 defaultVariant；期望 400，随后 GET 仍为合法配置',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const { json: pJson } = await ctx.call('POST', '/api/ai/catalog/providers', providerCreateBody(suffix))
    const provider = envelopeData(pJson)
    const providerId = String(provider.id)
    const { json: mJson } = await ctx.call('POST', '/api/ai/catalog/models', {
      providerId,
      name: `e2e-model-invupd-${suffix}`,
      description: 'ok',
      config: baseModelConfig(),
    })
    const model = envelopeData(mJson)
    const modelId = String(model.id)
    await expectHttpError(
      () =>
        ctx.call('PUT', `/api/ai/catalog/models/${modelId}`, {
          name: `e2e-model-invupd-${suffix}`,
          description: 'bad',
          config: baseModelConfig({ defaultVariant: 'nope' }),
          expectedVersion: model.version,
        }),
      { status: 400, messageIncludes: /defaultVariant/i },
    )
    const { json: getList } = await ctx.call('GET', '/api/ai/catalog/models?pageNumber=1&pageSize=100')
    const still = pageResults(getList).find((m) => String(m.id) === modelId)
    assert(still, 'model disappeared after rejected update')
    assert(still.config?.defaultVariant === 'default', JSON.stringify(still.config))
    await ctx.call('DELETE', `/api/ai/catalog/models/${modelId}?expectedVersion=${encodeURIComponent(model.version)}`)
    await ctx.call('DELETE', `/api/ai/catalog/providers/${providerId}?expectedVersion=${encodeURIComponent(provider.version)}`)
  },
})

registerCase({
  id: 'crud.agent.invalid_blank_name',
  level: 'L1',
  title: 'Agent 空白 name 拒绝',
  docs: 'POST /api/ai/catalog/agents name 空白 => 400',
  async run(ctx) {
    const { json: modelsJson } = await ctx.call('GET', '/api/ai/catalog/models?pageNumber=1&pageSize=5')
    const model = pageResults(modelsJson)[0]
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/ai/catalog/agents', {
          name: '  ',
          description: 'd',
          systemPrompt: 's',
          modelId: String(model.id),
          variant: model.config.defaultVariant,
          config: { tools: [], skills: [] },
        }),
      { status: 400, messageIncludes: /name|blank/i },
    )
  },
})

registerCase({
  id: 'crud.agent.invalid_variant',
  level: 'L1',
  title: 'Agent 不存在的 Variant 拒绝',
  docs: 'POST /api/ai/catalog/agents variant 不属于所选 Model => 400',
  async run(ctx) {
    const { json: modelsJson } = await ctx.call('GET', '/api/ai/catalog/models?pageNumber=1&pageSize=5')
    const model = pageResults(modelsJson)[0]
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/ai/catalog/agents', {
          name: `e2e-invalid-variant-${cid().slice(0, 8)}`,
          description: 'd',
          systemPrompt: 's',
          modelId: String(model.id),
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
  title: 'Provider 创建/读列表/更新/删除',
  docs: `POST/GET/PUT/DELETE /api/ai/catalog/providers
断言：创建后出现在列表；更新 name；删除后列表不存在；空白 name 创建失败`,
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const createBody = providerCreateBody(suffix)
    const { status: createStatus, json: createJson } = await ctx.call('POST', '/api/ai/catalog/providers', createBody)
    assert([200, 201].includes(createStatus), `create status ${createStatus}`)
    const created = envelopeData(createJson)
    assert(created?.id, JSON.stringify(createJson))
    const id = String(created.id)
    ctx.writeArtifact('provider-created.json', JSON.stringify(created, null, 2))

    const { json: listJson } = await ctx.call('GET', '/api/ai/catalog/providers?pageNumber=1&pageSize=100')
    const listed = pageResults(listJson).some((p) => String(p.id) === id)
    assert(listed, 'created provider not listed')

    const { json: updateJson } = await ctx.call(
      'PUT',
      `/api/ai/catalog/providers/${id}`,
      { ...providerUpdateBody(`e2e-provider-upd-${suffix}`), expectedVersion: created.version },
    )
    const updated = envelopeData(updateJson)
    assert(updated.name === `e2e-provider-upd-${suffix}`, JSON.stringify(updated))
    assert(updated.configured === true, 'empty credential should keep configured')

    await expectHttpError(
      () =>
        ctx.call('POST', '/api/ai/catalog/providers', {
          name: ' ',
          providerType: 'openai',
          baseUrl: 'https://example.com/v1',
          credential: 'x',
          modelCallTimeoutMillis: 1000,
          modelCallIdleTimeoutMillis: 1000,
        }),
      { status: 400, messageIncludes: /name must not be blank/i },
    )

    await ctx.call('DELETE', `/api/ai/catalog/providers/${id}?expectedVersion=${encodeURIComponent(updated.version)}`)
    const { json: listAfter } = await ctx.call('GET', '/api/ai/catalog/providers?pageNumber=1&pageSize=100')
    assert(!pageResults(listAfter).some((p) => String(p.id) === id), 'provider still listed after delete')
  },
})

registerCase({
  id: 'crud.model.lifecycle',
  level: 'L1',
  title: 'Model 创建/更新/删除（依赖临时 Provider）',
  docs: `在临时 provider 上 create/update/delete model；更新完整 config body`,
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const { json: pJson } = await ctx.call('POST', '/api/ai/catalog/providers', providerCreateBody(suffix))
    const provider = envelopeData(pJson)
    const providerId = String(provider.id)
    const createBody = {
      providerId,
      name: `e2e-model-${suffix}`,
      description: 'create',
      config: baseModelConfig(),
    }
    const { json: mJson } = await ctx.call('POST', '/api/ai/catalog/models', createBody)
    const model = envelopeData(mJson)
    assert(model?.id && model.config?.defaultVariant === 'default', JSON.stringify(mJson))
    const modelId = String(model.id)

    const updatedConfig = baseModelConfig({
      limit: { context: 8192, output: 1024 },
      abilities: { tools: false, reasoning: true, inputModalities: ['TEXT', 'IMAGE'] },
      defaultVariant: 'fast',
      variants: [
        { id: 'fast', temperature: 0.1 },
        { id: 'quality', temperature: 0.4, reasoningEffort: 'high' },
      ],
    })
    const { json: uJson } = await ctx.call('PUT', `/api/ai/catalog/models/${modelId}`, {
      name: `e2e-model-upd-${suffix}`,
      description: 'updated',
      config: updatedConfig,
      expectedVersion: model.version,
    })
    const updated = envelopeData(uJson)
    assert(updated.name === `e2e-model-upd-${suffix}`, JSON.stringify(updated))
    assert(updated.config.defaultVariant === 'fast', JSON.stringify(updated.config))
    assert(updated.config.limit.context === 8192, JSON.stringify(updated.config))
    ctx.writeArtifact('model-updated.json', JSON.stringify(updated, null, 2))

    await ctx.call('DELETE', `/api/ai/catalog/models/${modelId}?expectedVersion=${encodeURIComponent(updated.version)}`)
    const { json: listJson } = await ctx.call('GET', '/api/ai/catalog/models?pageNumber=1&pageSize=100')
    assert(!pageResults(listJson).some((m) => String(m.id) === modelId), 'model still listed')
    await ctx.call('DELETE', `/api/ai/catalog/providers/${providerId}?expectedVersion=${encodeURIComponent(provider.version)}`)
  },
})

registerCase({
  id: 'crud.agent.lifecycle',
  level: 'L1',
  title: 'Agent 创建/更新/删除',
  docs: `基于 seed model 创建 agent；更新 systemPrompt/config；删除`,
  async run(ctx) {
    const { json: modelsJson } = await ctx.call('GET', '/api/ai/catalog/models?pageNumber=1&pageSize=10')
    const model = pageResults(modelsJson)[0]
    assert(model?.id, 'need seeded model')
    const variant = model.config.defaultVariant
    const suffix = cid().slice(0, 8)
    const createBody = {
      name: `e2e-agent-${suffix}`,
      description: 'create',
      systemPrompt: 'you are e2e',
      modelId: String(model.id),
      variant,
      config: {
        tools: [],
        skills: [],
      },
    }
    const { json: aJson } = await ctx.call('POST', '/api/ai/catalog/agents', createBody)
    const agent = envelopeData(aJson)
    assert(agent?.id, JSON.stringify(aJson))
    const agentId = String(agent.id)

    const { json: uJson } = await ctx.call('PUT', `/api/ai/catalog/agents/${agentId}`, {
      name: `e2e-agent-upd-${suffix}`,
      description: 'updated',
      systemPrompt: 'updated prompt',
      modelId: String(model.id),
      variant,
      config: {
        tools: [],
        skills: [],
      },
      expectedVersion: agent.version,
    })
    const updated = envelopeData(uJson)
    assert(updated.name === `e2e-agent-upd-${suffix}`, JSON.stringify(updated))
    assert(updated.systemPrompt === 'updated prompt', JSON.stringify(updated))
    const updatedConfig = updated.config || {}
    assert(
      Array.isArray(updatedConfig.tools)
        && updatedConfig.tools.length === 0
        && Array.isArray(updatedConfig.skills)
        && updatedConfig.skills.length === 0
        && updatedConfig.environmentName == null
        && !('allowedSubagents' in updatedConfig)
        && !('executionPolicy' in updatedConfig),
      JSON.stringify(updatedConfig),
    )
    ctx.writeArtifact('agent-updated.json', JSON.stringify(updated, null, 2))

    await ctx.call('DELETE', `/api/ai/catalog/agents/${agentId}?expectedVersion=${encodeURIComponent(updated.version)}`)
    const { json: listJson } = await ctx.call('GET', '/api/ai/catalog/agents?pageNumber=1&pageSize=100')
    assert(!pageResults(listJson).some((a) => String(a.id) === agentId), 'agent still listed')
  },
})

registerCase({
  id: 'crud.chat.invalid_agent_id',
  level: 'L1',
  title: 'Chat 非法 defaultAgentId 拒绝',
  docs: 'POST /api/ai/chat defaultAgentId 非正整数字符串 => 400',
  async run(ctx) {
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/ai/chat', {
          title: 'bad-agent',
          defaultAgentId: 'not-a-number',
        }),
      { status: 400, messageIncludes: /defaultAgentId|positive|blank|invalid/i },
    )
  },
})

registerCase({
  id: 'crud.model.delete_unknown_rejected',
  level: 'L1',
  title: '删除不存在 Model 被拒绝',
  docs: 'DELETE /api/ai/catalog/models/999999999?expectedVersion=0 => 404 resource_not_found',
  async run(ctx) {
    await expectHttpError(() => ctx.call('DELETE', '/api/ai/catalog/models/999999999?expectedVersion=0'), {
      status: 404,
      messageIncludes: /not found|unknown|model/i,
    })
  },
})

registerCase({
  id: 'crud.chat.lifecycle',
  level: 'L1',
  title: 'Chat 创建/更新/删除',
  docs: `POST/PUT/DELETE chat；空白 title 400；删后 404。Chat 不持有 Session。`,
  async run(ctx) {
    const { json: agentsJson } = await ctx.call('GET', '/api/ai/catalog/agents?pageNumber=1&pageSize=10')
    const agent = pageResults(agentsJson)[0]
    const { json: cJson } = await ctx.call('POST', '/api/ai/chat', {
      title: 'e2e-chat',
      defaultAgentId: String(agent.id),
    })
    const chat = envelopeData(cJson)
    const chatId = String(chat.id)
    const { json: uJson } = await ctx.call('PUT', `/api/ai/chat/${chatId}`, {
      title: 'e2e-chat-upd',
      defaultAgentId: String(agent.id),
      expectedVersion: chat.version,
    })
    const updated = envelopeData(uJson)
    assert(updated.title === 'e2e-chat-upd', JSON.stringify(uJson))
    await expectHttpError(
      () => ctx.call('PUT', `/api/ai/chat/${chatId}`, { title: '   ', expectedVersion: updated.version }),
      { status: 400, messageIncludes: /title.*blank/i },
    )

    await ctx.call('DELETE', `/api/ai/chat/${chatId}?expectedVersion=${encodeURIComponent(updated.version)}`)
    await expectHttpError(() => ctx.call('GET', `/api/ai/chat/${chatId}`), { status: 404 })
  },
})
