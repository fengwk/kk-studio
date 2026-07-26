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
        ctx.call('POST', '/api/providers', {
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
  docs: 'POST /api/providers 无 providerType => 400',
  async run(ctx) {
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/providers', {
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
    const { json: pJson } = await ctx.call('POST', '/api/providers', providerCreateBody(suffix))
    const providerId = String(envelopeData(pJson).id)
    const { json: mJson } = await ctx.call('POST', '/api/models', {
      providerId,
      name: `e2e-model-invupd-${suffix}`,
      description: 'ok',
      config: baseModelConfig(),
    })
    const modelId = String(envelopeData(mJson).id)
    await expectHttpError(
      () =>
        ctx.call('PUT', `/api/models/${modelId}`, {
          name: `e2e-model-invupd-${suffix}`,
          description: 'bad',
          config: baseModelConfig({ defaultVariant: 'nope' }),
        }),
      { status: 400, messageIncludes: /defaultVariant/i },
    )
    const { json: getList } = await ctx.call('GET', '/api/models?pageNumber=1&pageSize=100')
    const still = pageResults(getList).find((m) => String(m.id) === modelId)
    assert(still, 'model disappeared after rejected update')
    assert(still.config?.defaultVariant === 'default', JSON.stringify(still.config))
    await ctx.call('DELETE', `/api/models/${modelId}`)
    await ctx.call('DELETE', `/api/providers/${providerId}`)
  },
})

registerCase({
  id: 'crud.agent.invalid_blank_name',
  level: 'L1',
  title: 'Agent 空白 name 拒绝',
  docs: 'POST /api/agents name 空白 => 400',
  async run(ctx) {
    const { json: modelsJson } = await ctx.call('GET', '/api/models?pageNumber=1&pageSize=5')
    const model = pageResults(modelsJson)[0]
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/agents', {
          name: '  ',
          description: 'd',
          systemPrompt: 's',
          modelId: String(model.id),
          variant: model.config.defaultVariant,
          config: { tools: [], skills: [], allowedSubagents: [], executionPolicy: {} },
        }),
      { status: 400, messageIncludes: /name|blank/i },
    )
  },
})

registerCase({
  id: 'crud.agent.invalid_variant',
  level: 'L1',
  title: 'Agent 不存在的 Variant 拒绝',
  docs: 'POST /api/agents variant 不属于所选 Model => 400',
  async run(ctx) {
    const { json: modelsJson } = await ctx.call('GET', '/api/models?pageNumber=1&pageSize=5')
    const model = pageResults(modelsJson)[0]
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/agents', {
          name: `e2e-invalid-variant-${cid().slice(0, 8)}`,
          description: 'd',
          systemPrompt: 's',
          modelId: String(model.id),
          variant: '__missing_variant__',
          config: { tools: [], skills: [], allowedSubagents: [], executionPolicy: {} },
        }),
      { status: 400, messageIncludes: /variant/i },
    )
  },
})

registerCase({
  id: 'crud.provider.lifecycle',
  level: 'L1',
  title: 'Provider 创建/读列表/更新/删除',
  docs: `POST/GET/PUT/DELETE /api/providers
断言：创建后出现在列表；更新 name；删除后列表不存在；空白 name 创建失败`,
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const createBody = providerCreateBody(suffix)
    const { status: createStatus, json: createJson } = await ctx.call('POST', '/api/providers', createBody)
    assert([200, 201].includes(createStatus), `create status ${createStatus}`)
    const created = envelopeData(createJson)
    assert(created?.id, JSON.stringify(createJson))
    const id = String(created.id)
    ctx.writeArtifact('provider-created.json', JSON.stringify(created, null, 2))

    const { json: listJson } = await ctx.call('GET', '/api/providers?pageNumber=1&pageSize=100')
    const listed = pageResults(listJson).some((p) => String(p.id) === id)
    assert(listed, 'created provider not listed')

    const { json: updateJson } = await ctx.call(
      'PUT',
      `/api/providers/${id}`,
      providerUpdateBody(`e2e-provider-upd-${suffix}`),
    )
    const updated = envelopeData(updateJson)
    assert(updated.name === `e2e-provider-upd-${suffix}`, JSON.stringify(updated))
    assert(updated.configured === true, 'empty credential should keep configured')

    await expectHttpError(
      () =>
        ctx.call('POST', '/api/providers', {
          name: ' ',
          providerType: 'openai',
          baseUrl: 'https://example.com/v1',
          credential: 'x',
          modelCallTimeoutMillis: 1000,
          modelCallIdleTimeoutMillis: 1000,
        }),
      { status: 400, messageIncludes: /name must not be blank/i },
    )

    await ctx.call('DELETE', `/api/providers/${id}`)
    const { json: listAfter } = await ctx.call('GET', '/api/providers?pageNumber=1&pageSize=100')
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
    const { json: pJson } = await ctx.call('POST', '/api/providers', providerCreateBody(suffix))
    const providerId = String(envelopeData(pJson).id)
    const createBody = {
      providerId,
      name: `e2e-model-${suffix}`,
      description: 'create',
      config: baseModelConfig(),
    }
    const { json: mJson } = await ctx.call('POST', '/api/models', createBody)
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
    const { json: uJson } = await ctx.call('PUT', `/api/models/${modelId}`, {
      name: `e2e-model-upd-${suffix}`,
      description: 'updated',
      config: updatedConfig,
    })
    const updated = envelopeData(uJson)
    assert(updated.name === `e2e-model-upd-${suffix}`, JSON.stringify(updated))
    assert(updated.config.defaultVariant === 'fast', JSON.stringify(updated.config))
    assert(updated.config.limit.context === 8192, JSON.stringify(updated.config))
    ctx.writeArtifact('model-updated.json', JSON.stringify(updated, null, 2))

    await ctx.call('DELETE', `/api/models/${modelId}`)
    const { json: listJson } = await ctx.call('GET', '/api/models?pageNumber=1&pageSize=100')
    assert(!pageResults(listJson).some((m) => String(m.id) === modelId), 'model still listed')
    await ctx.call('DELETE', `/api/providers/${providerId}`)
  },
})

registerCase({
  id: 'crud.agent.lifecycle',
  level: 'L1',
  title: 'Agent 创建/更新/删除',
  docs: `基于 seed model 创建 agent；更新 systemPrompt/config；删除`,
  async run(ctx) {
    const { json: modelsJson } = await ctx.call('GET', '/api/models?pageNumber=1&pageSize=10')
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
        allowedSubagents: [],
        executionPolicy: { maxTurns: 3 },
      },
    }
    const { json: aJson } = await ctx.call('POST', '/api/agents', createBody)
    const agent = envelopeData(aJson)
    assert(agent?.id, JSON.stringify(aJson))
    const agentId = String(agent.id)

    const { json: uJson } = await ctx.call('PUT', `/api/agents/${agentId}`, {
      name: `e2e-agent-upd-${suffix}`,
      description: 'updated',
      systemPrompt: 'updated prompt',
      modelId: String(model.id),
      variant,
      config: {
        tools: [],
        skills: [],
        allowedSubagents: [],
        executionPolicy: { maxTurns: 5, maxDepth: 1 },
      },
    })
    const updated = envelopeData(uJson)
    assert(updated.name === `e2e-agent-upd-${suffix}`, JSON.stringify(updated))
    assert(updated.systemPrompt === 'updated prompt', JSON.stringify(updated))
    assert(updated.config?.executionPolicy?.maxTurns === 5, JSON.stringify(updated.config))
    assert(Array.isArray(updated.config?.tools) && updated.config.tools.length === 0, JSON.stringify(updated.config))
    ctx.writeArtifact('agent-updated.json', JSON.stringify(updated, null, 2))

    await ctx.call('DELETE', `/api/agents/${agentId}`)
    const { json: listJson } = await ctx.call('GET', '/api/agents?pageNumber=1&pageSize=100')
    assert(!pageResults(listJson).some((a) => String(a.id) === agentId), 'agent still listed')
  },
})

registerCase({
  id: 'crud.chat.invalid_agent_id',
  level: 'L1',
  title: 'Chat 非法 defaultAgentId 拒绝',
  docs: 'POST /api/chats defaultAgentId 非正整数字符串 => 400',
  async run(ctx) {
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/chats', {
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
  docs: 'DELETE /api/models/999999999 => 4xx（当前实现为 400 agent model not found）',
  async run(ctx) {
    await expectHttpError(() => ctx.call('DELETE', '/api/models/999999999'), {
      status: 400,
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
    const { json: agentsJson } = await ctx.call('GET', '/api/agents?pageNumber=1&pageSize=10')
    const agent = pageResults(agentsJson)[0]
    const { json: cJson } = await ctx.call('POST', '/api/chats', {
      title: 'e2e-chat',
      defaultAgentId: String(agent.id),
    })
    const chat = envelopeData(cJson)
    const chatId = String(chat.id)
    const { json: uJson } = await ctx.call('PUT', `/api/chats/${chatId}`, {
      title: 'e2e-chat-upd',
      defaultAgentId: String(agent.id),
    })
    assert(envelopeData(uJson).title === 'e2e-chat-upd', JSON.stringify(uJson))
    await expectHttpError(
      () => ctx.call('PUT', `/api/chats/${chatId}`, { title: '   ' }),
      { status: 400, messageIncludes: /title.*blank/i },
    )

    await ctx.call('DELETE', `/api/chats/${chatId}`)
    await expectHttpError(() => ctx.call('GET', `/api/chats/${chatId}`), { status: 404 })
  },
})
