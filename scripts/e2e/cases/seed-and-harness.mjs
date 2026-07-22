import { assert, envelopeData, expectHttpError, httpJson, pageResults, sleep, cid } from '../lib/http.mjs'
import { registerCase, getCase } from '../lib/registry.mjs'

registerCase({
  id: 'seed.structured_model_config',
  level: 'L1',
  title: 'Model 公开契约为结构化 config',
  docs: 'GET /api/models：存在 config.defaultVariant；禁止 configJson/capabilitiesJson',
  async run(ctx) {
    const { json } = await ctx.call('GET', '/api/models?pageNumber=1&pageSize=50')
    const models = pageResults(json)
    assert(models.length > 0, 'no models seeded')
    for (const model of models) {
      assert('config' in model, `missing config keys=${Object.keys(model)}`)
      assert(!('configJson' in model), 'legacy configJson must not be public')
      assert(!('capabilitiesJson' in model), 'legacy capabilitiesJson must not be public')
      assert(model.config?.defaultVariant, JSON.stringify(model.config))
      assert(Array.isArray(model.config?.variants) && model.config.variants.length > 0, JSON.stringify(model.config))
      assert(Number(model.config?.limit?.context || 0) > 0, JSON.stringify(model.config))
      ctx.vars.seedModel = model
    }
  },
})

registerCase({
  id: 'seed.agent_and_provider',
  level: 'L1',
  title: 'Agent/Provider seed 可用',
  docs: 'seed agent 存在；provider.configured=true',
  async run(ctx) {
    const { json: agentsJson } = await ctx.call('GET', '/api/agents?pageNumber=1&pageSize=50')
    const agents = pageResults(agentsJson)
    assert(agents.length > 0, 'no agents')
    const agent = agents.find((a) => a.name === 'default-assistant') || agents[0]
    assert(agent.modelId && agent.variant && agent.config, JSON.stringify(agent))
    ctx.vars.agent = agent
    const { json: providersJson } = await ctx.call('GET', '/api/providers?pageNumber=1&pageSize=50')
    const providers = pageResults(providersJson)
    assert(providers[0]?.configured === true, JSON.stringify(providers[0]))
    ctx.vars.provider = providers[0]
  },
})

registerCase({
  id: 'chat.session.main_thread',
  level: 'L1',
  title: 'Chat/Session/MainThread 创建关联',
  docs: 'create chat/session/attach/get thread',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    const agent = ctx.vars.agent
    const { json: chatJson } = await ctx.call('POST', '/api/chats', {
      title: `e2e-${cid().slice(0, 8)}`,
      defaultAgentId: String(agent.id),
    })
    const chat = envelopeData(chatJson)
    ctx.vars.chatId = String(chat.id)
    const { json: sessionJson } = await ctx.call('POST', '/api/sessions', {})
    const session = envelopeData(sessionJson)
    ctx.vars.sessionId = String(session.sessionId)
    ctx.vars.mainThreadId = String(session.mainThreadId)
    await ctx.call('POST', `/api/chats/${ctx.vars.chatId}/sessions`, { sessionId: ctx.vars.sessionId })
    const { json: threadJson } = await ctx.call('GET', `/api/threads/${ctx.vars.mainThreadId}`)
    const thread = envelopeData(threadJson)
    assert(String(thread.sessionId) === ctx.vars.sessionId, JSON.stringify(thread))
  },
})

registerCase({
  id: 'thread.blank_first_send_order',
  level: 'L1',
  title: 'Blank 首发 SET_AGENT 先于 USER_MESSAGE',
  docs: 'PUT agent + POST message；inputs 顺序与 APPLIED',
  async run(ctx) {
    if (!ctx.vars.mainThreadId) await getCase('chat.session.main_thread').run(ctx)
    const agent = ctx.vars.agent
    const tid = ctx.vars.mainThreadId
    const { json: setAgentJson } = await ctx.call('PUT', `/api/threads/${tid}/agent`, {
      agentDefinitionId: String(agent.id),
      clientMessageId: cid(),
    })
    assert(envelopeData(setAgentJson).inputType === 'SET_AGENT', JSON.stringify(setAgentJson))
    const { json: msgJson } = await ctx.call('POST', `/api/threads/${tid}/messages`, {
      content: 'e2e L1 ping',
      clientMessageId: cid(),
    })
    assert(envelopeData(msgJson).inputType === 'USER_MESSAGE', JSON.stringify(msgJson))
    let applied = []
    for (let i = 0; i < 40; i++) {
      const { json } = await ctx.call('GET', `/api/threads/${tid}/inputs`)
      applied = (envelopeData(json) || []).filter((x) => x.status === 'APPLIED')
      if (applied.length >= 2) break
      await sleep(250)
    }
    const types = applied
      .slice()
      .sort((a, b) => Number(a.sequence || 0) - Number(b.sequence || 0))
      .map((x) => x.inputType)
    assert(types.indexOf('SET_AGENT') < types.indexOf('USER_MESSAGE'), JSON.stringify(types))
  },
})

registerCase({
  id: 'usage.unknown_thread_404',
  level: 'L1',
  title: '未知 Thread usage 404',
  docs: 'GET /api/usage/threads/999999999 => 404 unknown thread',
  async run(ctx) {
    await expectHttpError(() => ctx.call('GET', '/api/usage/threads/999999999'), {
      status: 404,
      messageIncludes: /unknown thread/,
    })
  },
})

registerCase({
  id: 'frontend.proxy_model_contract',
  level: 'L1',
  title: 'Frontend 代理 model 契约',
  docs: '5173 /api/models 返回结构化 config',
  async run(ctx) {
    assert(ctx.vars.frontendUrl, 'frontendUrl required')
    const { json } = await httpJson(ctx.vars.frontendUrl, 'GET', '/api/models?pageNumber=1&pageSize=1')
    const model = pageResults(json)[0]
    assert(model?.config?.defaultVariant, JSON.stringify(model))
    assert(!('configJson' in model), JSON.stringify(model))
  },
})

registerCase({
  id: 'thread.commands_model_yolo',
  level: 'L1',
  title: 'Thread SET_MODEL / SET_YOLO 入队并应用',
  docs: '使用新 session/thread，避免被前序 RUNNING turn 干扰；校验 inputs APPLIED 与最终 thread 状态',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    // 独立 session，避免 L1 前序 blank send 让 thread 仍 processing
    const { json: sessionJson } = await ctx.call('POST', '/api/sessions', {})
    const session = envelopeData(sessionJson)
    const tid = String(session.mainThreadId)
    const agent = ctx.vars.agent
    const model = ctx.vars.seedModel
    const variant = model.config.defaultVariant

    await ctx.call('PUT', `/api/threads/${tid}/agent`, {
      agentDefinitionId: String(agent.id),
      clientMessageId: cid(),
    })
    const { json: modelSet } = await ctx.call('PUT', `/api/threads/${tid}/model`, {
      modelId: String(model.id),
      variant,
      clientMessageId: cid(),
    })
    assert(String(envelopeData(modelSet).inputType || '').includes('MODEL'), JSON.stringify(modelSet))
    const { json: yoloSet } = await ctx.call('PUT', `/api/threads/${tid}/yolo`, {
      yoloEnabled: true,
      clientMessageId: cid(),
    })
    assert(String(envelopeData(yoloSet).inputType || '').includes('YOLO'), JSON.stringify(yoloSet))

    let th = null
    for (let i = 0; i < 60; i++) {
      const { json: inputsJson } = await ctx.call('GET', `/api/threads/${tid}/inputs`)
      const inputs = envelopeData(inputsJson) || []
      const appliedTypes = inputs.filter((x) => x.status === 'APPLIED').map((x) => x.inputType)
      const { json } = await ctx.call('GET', `/api/threads/${tid}`)
      th = envelopeData(json)
      if (
        appliedTypes.includes('SET_AGENT') &&
        appliedTypes.some((t) => String(t).includes('MODEL')) &&
        appliedTypes.some((t) => String(t).includes('YOLO')) &&
        String(th.modelId) === String(model.id) &&
        th.yoloEnabled === true &&
        !th.processing
      ) {
        ctx.writeArtifact('thread-after-commands.json', JSON.stringify({ th, inputs }, null, 2))
        return
      }
      await sleep(250)
    }
    assert(String(th?.modelId) === String(model.id), JSON.stringify(th))
    assert(th?.yoloEnabled === true, JSON.stringify(th))
  },
})

registerCase({
  id: 'thread.commands_model_invalid_variant_rejected',
  level: 'L1',
  title: 'Thread SET_MODEL 非法 Variant 在入队前拒绝',
  docs: 'PUT model 使用 seed Model + 不存在 Variant => 400；不写入 SET_MODEL input',
  async run(ctx) {
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const model = ctx.vars.seedModel
    const { json: sessionJson } = await ctx.call('POST', '/api/sessions', {})
    const tid = String(envelopeData(sessionJson).mainThreadId)

    await expectHttpError(
      () =>
        ctx.call('PUT', `/api/threads/${tid}/model`, {
          modelId: String(model.id),
          variant: '__missing_variant__',
          clientMessageId: cid(),
        }),
      { status: 400, messageIncludes: /variant/i },
    )
    const { json: inputsJson } = await ctx.call('GET', `/api/threads/${tid}/inputs`)
    assert((envelopeData(inputsJson) || []).length === 0, JSON.stringify(inputsJson))
  },
})
