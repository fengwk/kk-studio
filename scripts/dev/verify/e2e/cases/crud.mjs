import { assert, envelopeData, expectHttpError, pageResults, cid } from '../lib/http.mjs'
import { baseModelConfig, providerCreateBody } from '../lib/fixtures.mjs'
import {
  branchSettingsOf,
  chatOwner,
  createChat,
  getThreadSnapshot,
  listChatSessions,
  listSessionThreads,
  createNewSession,
  userMessageCommand,
  waitForQuiescentThread,
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
  docs: 'name identity 通过 path 指定；PUT 非法 defaultVariant => 400，随后 GET 原配置不变',
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
          modelId: `wire-invupd-${suffix}`,
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
            modelId: model.modelId,
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
            config: { tools: [], skills: [], subagents: [], inheritParentEnvironment: true },
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
          config: { tools: [], skills: [], subagents: [], inheritParentEnvironment: true },
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

    // 硬删除后同名可重建：version 从 0 重新开始，且严格使用新请求体属性。
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
          modelId: `wire-lifecycle-${suffix}`,
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
        { id: 'fast' },
        { id: 'quality', reasoningEffort: 'high' },
      ],
    })
    const updated = envelopeData(
      (
        await ctx.call('PUT', modelPath(provider.name, name), {
          modelId: model.modelId,
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

    // 硬删除后同名可重建：version 从 0 重新开始，且严格使用新请求体配置。
    const recreated = envelopeData(
      (
        await ctx.call('POST', '/api/ai/catalog/models', {
          providerName: provider.name,
          name,
          modelId: `wire-recreated-${suffix}`,
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
      `recreated Model must reflect current body config: ${JSON.stringify(recreated)}`,
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
  docs: '记录存续期间 name 不可修改；PUT 可更新 default model/prompt/variant/config；DELETE 硬删除后同名可重建，version 从 0 重新开始且读取到新数据',
  async run(ctx) {
    const [model, updatedModel] = await firstTwoModels(ctx)
    const name = `e2e-agent-${cid().slice(0, 8)}`
    const agent = envelopeData(
      (
        await ctx.call('POST', '/api/ai/catalog/agents', {
          name,
          description: 'create',
          systemPrompt: 'you are e2e',
          model: modelRef(model),
          variant: model.config.defaultVariant,
          config: { tools: [], skills: [], subagents: [], inheritParentEnvironment: true },
        })
      ).json,
    )
    assert(agent?.name === name && agent.model === modelRef(model) && !('id' in agent), JSON.stringify(agent))
    const updated = envelopeData(
      (
        await ctx.call('PUT', `/api/ai/catalog/agents/${encodeURIComponent(name)}`, {
          description: 'updated',
          systemPrompt: 'updated prompt',
          model: modelRef(updatedModel),
          variant: updatedModel.config.defaultVariant,
          config: { tools: [], skills: [], subagents: [], inheritParentEnvironment: false },
          expectedVersion: agent.version,
        })
      ).json,
    )
    assert(
      updated.name === name && updated.model === modelRef(updatedModel),
      JSON.stringify(updated),
    )
    assert(updated.systemPrompt === 'updated prompt', JSON.stringify(updated))
    assert(updated.config?.inheritParentEnvironment === false, JSON.stringify(updated))
    await ctx.call(
      'DELETE',
      `/api/ai/catalog/agents/${encodeURIComponent(name)}?expectedVersion=${encodeURIComponent(updated.version)}`,
    )
    const agents = pageResults(
      (await ctx.call('GET', '/api/ai/catalog/agents?pageNumber=1&pageSize=100')).json,
    )
    assert(!agents.some((candidate) => candidate.name === name), 'Agent still listed')

    // 硬删除后同名可重建：version 从 0 重新开始，且严格使用新请求体 prompt。
    const recreated = envelopeData(
      (
        await ctx.call('POST', '/api/ai/catalog/agents', {
          name,
          description: 'recreated',
          systemPrompt: 'recreated prompt',
          model: modelRef(model),
          variant: model.config.defaultVariant,
          config: { tools: [], skills: [], subagents: [], inheritParentEnvironment: true },
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
  id: 'crud.agent.subagent_reference_lifecycle',
  level: 'L1',
  title: 'Agent subagents 名称引用与删除保护',
  docs: '创建 parent.subagents=[child] 后 child DELETE => 409；PUT parent 移除引用后 child 可硬删除；公开 config 始终完整返回 tools/skills/subagents/inheritParentEnvironment',
  async run(ctx) {
    const model = await firstModel(ctx)
    const suffix = cid().slice(0, 8)
    let child = null
    let parent = null
    try {
      child = envelopeData(
        (
          await ctx.call('POST', '/api/ai/catalog/agents', {
            name: `e2e-child-${suffix}`,
            description: 'subagent child',
            systemPrompt: 'return a concise report',
            model: modelRef(model),
            variant: model.config.defaultVariant,
            config: { tools: [], skills: [], subagents: [], inheritParentEnvironment: true },
          })
        ).json,
      )
      parent = envelopeData(
        (
          await ctx.call('POST', '/api/ai/catalog/agents', {
            name: `e2e-parent-${suffix}`,
            description: 'subagent parent',
            systemPrompt: 'delegate when needed',
            model: modelRef(model),
            variant: model.config.defaultVariant,
            config: {
              tools: [],
              skills: [],
              subagents: [child.name],
              inheritParentEnvironment: false,
            },
          })
        ).json,
      )
      assert(
        Array.isArray(parent.config?.tools)
          && parent.config.tools.length === 0
          && Array.isArray(parent.config?.skills)
          && parent.config.skills.length === 0
          && JSON.stringify(parent.config?.subagents) === JSON.stringify([child.name])
          && parent.config?.inheritParentEnvironment === false,
        JSON.stringify(parent),
      )
      await expectHttpError(
        () =>
          ctx.call(
            'DELETE',
            `/api/ai/catalog/agents/${encodeURIComponent(child.name)}?expectedVersion=${encodeURIComponent(child.version)}`,
          ),
        { status: 409, messageIncludes: /in use|subagent|referenc/i },
      )
      parent = envelopeData(
        (
          await ctx.call(
            'PUT',
            `/api/ai/catalog/agents/${encodeURIComponent(parent.name)}`,
            {
              description: parent.description,
              systemPrompt: parent.systemPrompt,
              model: parent.model,
              variant: parent.variant,
              config: {
                tools: [],
                skills: [],
                subagents: [],
                inheritParentEnvironment: true,
              },
              expectedVersion: parent.version,
            },
          )
        ).json,
      )
      await ctx.call(
        'DELETE',
        `/api/ai/catalog/agents/${encodeURIComponent(child.name)}?expectedVersion=${encodeURIComponent(child.version)}`,
      )
      child = null
      await ctx.call(
        'DELETE',
        `/api/ai/catalog/agents/${encodeURIComponent(parent.name)}?expectedVersion=${encodeURIComponent(parent.version)}`,
      )
      parent = null
    } finally {
      for (const agent of [parent, child]) {
        if (!agent?.name) continue
        try {
          await ctx.call(
            'DELETE',
            `/api/ai/catalog/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(agent.version)}`,
          )
        } catch {
          // 保留主断言失败；隔离 E2E schema 会在下一次 rebuild 清理。
        }
      }
    }
  },
})

registerCase({
  id: 'crud.chat.invalid_agent_name',
  level: 'L1',
  title: 'Chat 不存在 Agent name 拒绝',
  docs: 'POST /api/ai/chats 请求体中的 agentName 必须引用现有 Agent，否则返回 400 validation',
  async run(ctx) {
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/ai/chats', {
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
  docs: 'Chat 仅保存 agentName/yoloEnabled；NEW_SESSION rootSettings 携带 agentName/model/environmentName branch draft；更新 Chat 默认值不改变既有 Thread',
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
          && !Object.hasOwn(chat, 'workspacePath')
          && !Object.hasOwn(chat, 'environment')
          && !Object.hasOwn(chat, 'environmentName'),
        JSON.stringify(chat),
      )
      // 先创建 Thread（NEW_SESSION 初始创建），再更新 Chat 默认值，最后 reread 同一 Thread：
      // 更新 Chat 不影响既有 Thread 的 branchSettings（Thread 快照是运行时事实）。
      const requested = {
        agentName: agent.name,
        model: modelSelectionFor(agent),
        environmentName: null,
      }
      const threadId = cid()
      const accepted = await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId: cid(),
        threadId,
        rootSettings: requested,
        yoloEnabled: false,
        commands: [userMessageCommand(`settings independent ${suffix}`, cid())],
      })
      const thread = accepted.thread
      assert(thread.yoloEnabled === false, JSON.stringify(thread))
      assert(
        JSON.stringify(thread.branchSettings) === JSON.stringify(requested),
        JSON.stringify({ expected: requested, actual: thread.branchSettings }),
      )
      const updated = envelopeData(
        (
          await ctx.call('PUT', `/api/ai/chats/${chat.id}`, {
            yoloEnabled: true,
            expectedVersion: chat.version,
          })
        ).json,
      )
      cleanupChat = updated
      assert(
        updated.agentName === agent.name
          && updated.yoloEnabled === true
          && !Object.hasOwn(updated, 'workspacePath')
          && !Object.hasOwn(updated, 'environment')
          && !Object.hasOwn(updated, 'environmentName'),
        JSON.stringify(updated),
      )
      // 同一 Thread reread：branchSettings 逐字段不变。
      const reread = await getThreadSnapshot(ctx, threadId)
      assert(
        JSON.stringify(reread.thread.branchSettings) === JSON.stringify(requested),
        JSON.stringify({ expected: requested, actual: reread.thread.branchSettings }),
      )
      assert(reread.thread.yoloEnabled === false, JSON.stringify(reread.thread))
      // 缺 rootSettings 的 NEW_SESSION => 400（mapper requireNonNull）。
      await expectHttpError(
        () =>
          ctx.call('POST', '/api/harness/command-batches', {
            owner: chatOwner(chat.id),
            target: { type: 'NEW_SESSION', sessionId: cid(), threadId: cid(), yoloEnabled: false },
            commands: [userMessageCommand('missing root settings', cid())],
          }),
        { status: 400, messageIncludes: /rootSettings/i },
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
  docs: 'DELETE 使用 providerName/modelName path；未知复合 identity => 404',
  async run(ctx) {
    await expectHttpError(
      () =>
        ctx.call(
          'DELETE',
          '/api/ai/catalog/models/missing-provider/missing-model?expectedVersion=0',
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
        await ctx.call('POST', '/api/ai/chats', {
          title: 'e2e-chat',
          agentName: agent.name,
          yoloEnabled: false,
        })
      ).json,
    )
    const updated = envelopeData(
      (
        await ctx.call('PUT', `/api/ai/chats/${chat.id}`, {
          title: 'e2e-chat-upd',
          expectedVersion: chat.version,
        })
      ).json,
    )
    assert(updated.title === 'e2e-chat-upd' && updated.agentName === agent.name, JSON.stringify(updated))
    await expectHttpError(
      () =>
        ctx.call('PUT', `/api/ai/chats/${chat.id}`, {
          title: '   ',
          expectedVersion: updated.version,
        }),
      { status: 400, messageIncludes: /title.*blank/i },
    )
    await deleteChat(ctx, updated)
    await expectHttpError(() => ctx.call('GET', `/api/ai/chats/${chat.id}`), { status: 404 })
  },
})

registerCase({
  id: 'crud.chat.session_ownership_list',
  level: 'L1',
  title: 'Chat Session 摘要与 Session Thread 列表',
  docs: 'NEW_SESSION 原子创建即建立 Chat owner 归属；GET /api/ai/chats/{chatId}/sessions 返回 Session 摘要（新到旧，firstMessagePreview 精确等于首条 USER 文本、threadCount=1）；同批 NEW_SESSION 幂等重放 replayed=true 且不新增 Session/Thread relation；GET /api/harness/sessions/{sessionId}/threads 返回 Thread 摘要；未知 Chat sessions => 404',
  async run(ctx) {
    const agent = await firstAgent(ctx)
    const suffix = cid().slice(0, 8)
    const chat = await createChat(ctx, {
      title: `e2e-chat-thread-${suffix}`,
      agentName: agent.name,
      yoloEnabled: true,
    })
    const modelSelection = modelSelectionFor(agent)
    const createdThreadIds = []
    const makeSession = async (title) => {
      const threadId = cid()
      const commands = [userMessageCommand(`${title} ${suffix}`, cid())]
      const accepted = await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId: cid(),
        threadId,
        rootSettings: branchSettingsOf({ name: agent.name }, modelSelection),
        yoloEnabled: true,
        commands,
      })
      createdThreadIds.push(threadId)
      return { accepted, commands }
    }
    const first = await makeSession('first')
    const second = await makeSession('second')
    const third = await makeSession('third')
    try {
      // accepted projection 可能已含 processor 消费；只断言结构，不锁定瞬时 status。
      const firstThread = first.accepted.thread
      assert(/^\d+$/.test(String(firstThread.version)), JSON.stringify(firstThread))
      assert(firstThread.sessionId && firstThread.headEntryId, JSON.stringify(firstThread))
      // name 是必填展示名（root Thread 恒为 main；NEW_SESSION 无 name 输入）。
      assert(firstThread.name === 'main', JSON.stringify(firstThread))

      // 精确 preview 断言依赖 USER entry 已持久化：先等三个 Thread 的 turn 收敛。
      for (const threadId of createdThreadIds) {
        await waitForQuiescentThread(ctx, threadId, {
          timeoutMs: 60_000,
          intervalMs: 100,
        })
      }

      // NEW_SESSION 初始创建是 Chat owner 归属的唯一入口：连续三次创建产生三个 Session。
      const sessions = await listChatSessions(ctx, chat.id)
      const sessionIds = sessions.map((item) => String(item.sessionId))
      assert(sessionIds.includes(String(first.accepted.thread.sessionId)), 'first Session missing')
      assert(sessionIds.includes(String(second.accepted.thread.sessionId)), 'second Session missing')
      assert(sessionIds.includes(String(third.accepted.thread.sessionId)), 'third Session missing')
      // 新到旧：最近创建（third）排在最前。
      assert(
        sessionIds[0] === String(third.accepted.thread.sessionId),
        `expected newest-first: ${JSON.stringify(sessions)}`,
      )
      // 精确摘要：firstMessagePreview 等于该 Session 首条 USER 文本；每个 Session 恰一个 Thread。
      const byId = new Map(sessions.map((item) => [String(item.sessionId), item]))
      for (const { accepted, commands } of [first, second, third]) {
        const summary = byId.get(String(accepted.thread.sessionId))
        assert(summary, `Session summary missing: ${accepted.thread.sessionId}`)
        const expectedPreview = commands[0].contents.find((c) => c.type === 'TEXT').text
        assert(
          summary.firstMessagePreview === expectedPreview,
          `firstMessagePreview ${JSON.stringify(summary.firstMessagePreview)} != ${JSON.stringify(expectedPreview)}: ${JSON.stringify(summary)}`,
        )
        assert(summary.threadCount === 1, `threadCount must be 1: ${JSON.stringify(summary)}`)
      }

      // 同批 NEW_SESSION 幂等重放：replayed=true 且不新增 Session/Thread relation。
      const { accepted: thirdAccepted, commands: thirdCommands } = third
      const replayed = await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId: String(thirdAccepted.session.sessionId),
        threadId: String(thirdAccepted.thread.threadId),
        rootSettings: branchSettingsOf({ name: agent.name }, modelSelection),
        yoloEnabled: true,
        commands: thirdCommands,
      })
      assert(replayed.replayed === true, `expected replayed=true: ${JSON.stringify(replayed)}`)
      const sessionsAfterReplay = await listChatSessions(ctx, chat.id)
      assert(
        sessionsAfterReplay.length === sessions.length,
        `replay must not add Session relation: ${JSON.stringify(sessionsAfterReplay)}`,
      )
      const replayedSummary = sessionsAfterReplay.find(
        (item) => String(item.sessionId) === String(thirdAccepted.session.sessionId),
      )
      assert(replayedSummary?.name === String(thirdAccepted.session.name), JSON.stringify(replayedSummary))
      assert(
        replayedSummary && replayedSummary.threadCount === 1,
        `replay must not add Thread relation: ${JSON.stringify(sessionsAfterReplay)}`,
      )

      // Session Thread 摘要包含创建的 Thread（其 name 必填非空，root 为 main）。
      const sessionThreads = await listSessionThreads(ctx, thirdAccepted.thread.sessionId)
      const threadIds = sessionThreads.map((item) => String(item.threadId))
      assert(
        threadIds.includes(String(thirdAccepted.thread.threadId)),
        `created Thread missing from Session Thread list: ${JSON.stringify(threadIds)}`,
      )
      const thirdThreadSummary = sessionThreads.find(
        (item) => String(item.threadId) === String(thirdAccepted.thread.threadId),
      )
      assert(thirdThreadSummary?.name === 'main', JSON.stringify(thirdThreadSummary))

      // canonical 但未知的 Chat sessions => 404。
      const unknownChatId = '00000000-0000-0000-0000-000000000999'
      await expectHttpError(
        () => ctx.call('GET', `/api/ai/chats/${unknownChatId}/sessions`),
        { status: 404, messageIncludes: /unknown|not found/i },
      )
    } finally {
      // 再次确认全部 Thread 已收敛，避免测试结束时的异步事件污染后续 case。
      for (const threadId of createdThreadIds) {
        await waitForQuiescentThread(ctx, threadId, {
          timeoutMs: 60_000,
          intervalMs: 100,
        })
      }
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

async function firstTwoModels(ctx) {
  const models = pageResults(
    (await ctx.call('GET', '/api/ai/catalog/models?pageNumber=1&pageSize=10')).json,
  )
  assert(
    models[0]?.providerName
      && models[0]?.name
      && models[1]?.providerName
      && models[1]?.name,
    'need two seeded Models',
  )
  return [models[0], models[1]]
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
  const encodedName = name.split('/').map(encodeURIComponent).join('/')
  return `/api/ai/catalog/models/${encodeURIComponent(providerName)}/${encodedName}`
}

async function deleteModel(ctx, model) {
  await ctx.call(
    'DELETE',
    `${modelPath(model.providerName, model.name)}?expectedVersion=${encodeURIComponent(model.version)}`,
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
    `/api/ai/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
  )
}
