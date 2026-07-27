import { assert, envelopeData, expectHttpError, httpJson, pageResults, sleep, cid } from '../lib/http.mjs'
import {
  bootstrapThread,
  createBootstrappedThread,
  createUnboundThread,
  getThread,
  listSessionEntries,
  listThreadEntries,
  listThreadInputs,
  updateThreadHead,
} from '../lib/harness.mjs'
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
  id: 'thread.unbound_create',
  level: 'L1',
  title: 'POST /api/threads 创建 UNBOUND Thread',
  docs: '无请求体 => 201；status=UNBOUND，headEntryId/sessionId 为空，executionEpoch=0；无路径 Entry',
  async run(ctx) {
    const thread = await createUnboundThread(ctx)
    assert(Number(thread.executionEpoch) === 0, JSON.stringify(thread))
    assert((await listThreadEntries(ctx, thread.threadId)).length === 0, 'unbound thread must have no path entries')
    const listed = envelopeData((await ctx.call('GET', '/api/threads')).json) || []
    assert(listed.some((t) => t.threadId === thread.threadId), 'created thread missing from global list')
    ctx.vars.unboundThread = thread
  },
})

registerCase({
  id: 'thread.unbound_message_rejected',
  level: 'L1',
  title: 'UNBOUND Thread 拒绝 mailbox 输入',
  docs: 'POST /api/threads/{id}/messages 在未绑定 head 时 => 409 thread is unbound',
  async run(ctx) {
    if (!ctx.vars.unboundThread) await getCase('thread.unbound_create').run(ctx)
    const thread = ctx.vars.unboundThread
    await expectHttpError(
      () =>
        ctx.call('POST', `/api/threads/${thread.threadId}/messages`, {
          content: 'before bootstrap',
          clientMessageId: cid(),
          expectedExecutionEpoch: Number(thread.executionEpoch),
        }),
      { status: 409, messageIncludes: /unbound/i },
    )
    assert((await listThreadInputs(ctx, thread.threadId)).length === 0, 'rejected input must not be persisted')
  },
})

registerCase({
  id: 'thread.bootstrap_binds_session',
  level: 'L1',
  title: 'bootstrap 原子创建 Session/ROOT/RUNTIME_CONFIG 并绑定 head',
  docs: 'POST /api/threads/{id}/bootstrap => 201 {session, thread}；epoch+1；head 指向 RUNTIME_CONFIG；Thread 派生 sessionId',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.unboundThread) await getCase('thread.unbound_create').run(ctx)
    const created = ctx.vars.unboundThread
    const { session, thread } = await bootstrapThread(ctx, created, {
      agentDefinitionId: ctx.vars.agent.id,
      title: `e2e-${cid().slice(0, 8)}`,
    })
    assert(thread.threadId === created.threadId, 'bootstrap must reuse the same Thread')
    assert(thread.status === 'IDLE', JSON.stringify(thread))
    assert(Number(thread.executionEpoch) === Number(created.executionEpoch) + 1, JSON.stringify(thread))

    const entries = await listSessionEntries(ctx, session.sessionId)
    assert(entries.length === 2, JSON.stringify(entries))
    assert(entries[0].entryType === 'ROOT', JSON.stringify(entries[0]))
    assert(entries[1].entryType === 'RUNTIME_CONFIG', JSON.stringify(entries[1]))
    assert(thread.headEntryId === entries[1].entryId, JSON.stringify(thread))

    // Session is derived from the head Entry on the query side, not stored on the Thread row.
    const view = await getThread(ctx, thread.threadId)
    assert(view.sessionId === session.sessionId, JSON.stringify(view))
    ctx.vars.boundThread = view
    ctx.vars.boundSession = session
    ctx.vars.boundRootEntryId = entries[0].entryId
  },
})

registerCase({
  id: 'thread.stale_epoch_rejected',
  level: 'L1',
  title: 'stale expectedExecutionEpoch 被拒绝',
  docs: 'message / PUT head 携带过期 epoch => 409 stale execution epoch；不写入 Input，不移动 head',
  async run(ctx) {
    if (!ctx.vars.boundThread) await getCase('thread.bootstrap_binds_session').run(ctx)
    const thread = ctx.vars.boundThread
    const staleEpoch = Number(thread.executionEpoch) - 1
    await expectHttpError(
      () =>
        ctx.call('POST', `/api/threads/${thread.threadId}/messages`, {
          content: 'stale',
          clientMessageId: cid(),
          expectedExecutionEpoch: staleEpoch,
        }),
      { status: 409, messageIncludes: /stale execution epoch/i },
    )
    await expectHttpError(
      () =>
        ctx.call('PUT', `/api/threads/${thread.threadId}/head`, {
          headEntryId: ctx.vars.boundRootEntryId,
          expectedExecutionEpoch: staleEpoch,
        }),
      { status: 409, messageIncludes: /stale execution epoch/i },
    )
    const after = await getThread(ctx, thread.threadId)
    assert(after.headEntryId === thread.headEntryId, JSON.stringify(after))
    assert(Number(after.executionEpoch) === Number(thread.executionEpoch), JSON.stringify(after))
    assert((await listThreadInputs(ctx, thread.threadId)).length === 0, 'stale mutation must not enqueue input')
  },
})

registerCase({
  id: 'thread.rebind_same_session',
  level: 'L1',
  title: '同 Session 内 PUT /head 回退到历史 Entry',
  docs: 'PUT /api/threads/{id}/head 指向同 Session 的 ROOT => head 更新、epoch+1、sessionId 不变',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    const { session, thread } = await createBootstrappedThread(ctx, {
      agentDefinitionId: ctx.vars.agent.id,
      title: `e2e-rebind-${cid().slice(0, 8)}`,
    })
    const entries = await listSessionEntries(ctx, session.sessionId)
    const rootEntryId = entries[0].entryId

    const rewound = await updateThreadHead(ctx, thread, rootEntryId)
    assert(rewound.headEntryId === rootEntryId, JSON.stringify(rewound))
    assert(Number(rewound.executionEpoch) === Number(thread.executionEpoch) + 1, JSON.stringify(rewound))

    const view = await getThread(ctx, thread.threadId)
    assert(view.sessionId === session.sessionId, JSON.stringify(view))
    assert(view.status === 'IDLE', JSON.stringify(view))
    // Path entries follow the new head: ROOT only.
    const path = await listThreadEntries(ctx, thread.threadId)
    assert(path.length === 1 && path[0].entryId === rootEntryId, JSON.stringify(path))
    ctx.vars.rebindThread = view
  },
})

registerCase({
  id: 'thread.rebind_cross_session',
  level: 'L1',
  title: '跨 Session PUT /head 复用同一 Thread',
  docs: '把已绑定 Thread 的 head 指向另一 Session 的 Entry => threadId 不变、派生 sessionId 切换',
  async run(ctx) {
    if (!ctx.vars.rebindThread) await getCase('thread.rebind_same_session').run(ctx)
    const thread = ctx.vars.rebindThread
    const other = await createBootstrappedThread(ctx, {
      agentDefinitionId: ctx.vars.agent.id,
      title: `e2e-other-${cid().slice(0, 8)}`,
    })
    assert(other.thread.threadId !== thread.threadId, 'expected a distinct Thread for the other Session')

    const moved = await updateThreadHead(ctx, thread, other.thread.headEntryId)
    assert(moved.threadId === thread.threadId, 'cross-session rebind must reuse the same Thread')
    assert(moved.headEntryId === other.thread.headEntryId, JSON.stringify(moved))

    const view = await getThread(ctx, thread.threadId)
    assert(view.sessionId === other.session.sessionId, JSON.stringify(view))
    ctx.vars.rebindThread = view
  },
})

registerCase({
  id: 'thread.unbind_head',
  level: 'L1',
  title: 'PUT /head 传 null 使 Thread 回到 UNBOUND',
  docs: 'headEntryId=null => status=UNBOUND、sessionId/headEntryId 为空；未知 Entry => 404',
  async run(ctx) {
    if (!ctx.vars.rebindThread) await getCase('thread.rebind_cross_session').run(ctx)
    const thread = ctx.vars.rebindThread
    const unbound = await updateThreadHead(ctx, thread, null)
    assert(unbound.status === 'UNBOUND', JSON.stringify(unbound))
    assert(!unbound.headEntryId, JSON.stringify(unbound))

    const view = await getThread(ctx, thread.threadId)
    assert(!view.sessionId && !view.headEntryId, JSON.stringify(view))
    assert((await listThreadEntries(ctx, thread.threadId)).length === 0, 'unbound thread must have no path entries')

    await expectHttpError(
      () =>
        ctx.call('PUT', `/api/threads/${thread.threadId}/head`, {
          headEntryId: '999999999999',
          expectedExecutionEpoch: Number(unbound.executionEpoch),
        }),
      { status: 404, messageIncludes: /unknown entry/i },
    )
  },
})

registerCase({
  id: 'thread.stop_then_rebind',
  level: 'L1',
  title: 'stop 后 Thread 立即可 rebind',
  docs: 'stop 递增 epoch、取消 queued Input 与 OPEN Interaction；随后 PUT /head 成功',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    const { session, thread } = await createBootstrappedThread(ctx, {
      agentDefinitionId: ctx.vars.agent.id,
      title: `e2e-stop-${cid().slice(0, 8)}`,
    })
    await ctx.call('POST', `/api/threads/${thread.threadId}/messages`, {
      content: 'e2e stop then rebind',
      clientMessageId: cid(),
      expectedExecutionEpoch: Number(thread.executionEpoch),
    })

    const { json: stopJson } = await ctx.call('POST', `/api/threads/${thread.threadId}/stop`, {
      expectedExecutionEpoch: Number(thread.executionEpoch),
    })
    const stop = envelopeData(stopJson)
    assert(Number(stop.executionEpoch) === Number(thread.executionEpoch) + 1, JSON.stringify(stop))

    const stopped = await getThread(ctx, thread.threadId)
    const rootEntryId = (await listSessionEntries(ctx, session.sessionId))[0].entryId
    const rebound = await updateThreadHead(ctx, stopped, rootEntryId)
    assert(rebound.headEntryId === rootEntryId, JSON.stringify(rebound))
    assert(Number(rebound.executionEpoch) === Number(stopped.executionEpoch) + 1, JSON.stringify(rebound))
    ctx.writeArtifact('stop-then-rebind.json', JSON.stringify({ stop, stopped, rebound }, null, 2))
  },
})

registerCase({
  id: 'thread.blank_first_send_order',
  level: 'L1',
  title: 'Blank 首发顺序：createThread -> bootstrap -> USER_MESSAGE',
  docs: 'bootstrap 的 RUNTIME_CONFIG 先于消息生效；mailbox 只有 USER_MESSAGE 且被 APPLIED',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    const { session, thread } = await createBootstrappedThread(ctx, {
      agentDefinitionId: ctx.vars.agent.id,
      title: `e2e-first-send-${cid().slice(0, 8)}`,
    })
    const { json: msgJson } = await ctx.call('POST', `/api/threads/${thread.threadId}/messages`, {
      content: 'e2e L1 ping',
      clientMessageId: cid(),
      expectedExecutionEpoch: Number(thread.executionEpoch),
    })
    const input = envelopeData(msgJson)
    assert(input.inputType === 'USER_MESSAGE', JSON.stringify(msgJson))
    assert(Number(input.sequence) === 1, JSON.stringify(msgJson))

    // The bootstrap RUNTIME_CONFIG is already on the path before the message is harvested.
    const bootstrapEntries = await listSessionEntries(ctx, session.sessionId)
    assert(
      bootstrapEntries.map((e) => e.entryType).join(',') === 'ROOT,RUNTIME_CONFIG',
      JSON.stringify(bootstrapEntries),
    )

    let inputs = []
    for (let i = 0; i < 40; i++) {
      inputs = await listThreadInputs(ctx, thread.threadId)
      if (inputs.some((x) => x.status === 'APPLIED')) break
      await sleep(250)
    }
    const applied = inputs.filter((x) => x.status === 'APPLIED').map((x) => x.inputType)
    assert(applied.length === 1 && applied[0] === 'USER_MESSAGE', JSON.stringify(inputs))
    ctx.vars.firstSendThreadId = thread.threadId
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
  title: 'Thread SET_AGENT / SET_MODEL / SET_YOLO 入队并应用',
  docs: '独立 bootstrap Thread，避免被前序 RUNNING turn 干扰；命令均携带当前 executionEpoch',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const { thread } = await createBootstrappedThread(ctx, {
      agentDefinitionId: ctx.vars.agent.id,
      title: `e2e-commands-${cid().slice(0, 8)}`,
    })
    const tid = thread.threadId
    // Enqueue never advances the epoch; only stop / head rebind do.
    const epoch = Number(thread.executionEpoch)
    const model = ctx.vars.seedModel
    const variant = model.config.defaultVariant

    await ctx.call('PUT', `/api/threads/${tid}/agent`, {
      agentDefinitionId: String(ctx.vars.agent.id),
      clientMessageId: cid(),
      expectedExecutionEpoch: epoch,
    })
    const { json: modelSet } = await ctx.call('PUT', `/api/threads/${tid}/model`, {
      modelId: String(model.id),
      variant,
      clientMessageId: cid(),
      expectedExecutionEpoch: epoch,
    })
    assert(String(envelopeData(modelSet).inputType || '').includes('MODEL'), JSON.stringify(modelSet))
    const { json: yoloSet } = await ctx.call('PUT', `/api/threads/${tid}/yolo`, {
      yoloEnabled: true,
      clientMessageId: cid(),
      expectedExecutionEpoch: epoch,
    })
    assert(String(envelopeData(yoloSet).inputType || '').includes('YOLO'), JSON.stringify(yoloSet))

    let th = null
    let inputs = []
    for (let i = 0; i < 60; i++) {
      inputs = await listThreadInputs(ctx, tid)
      const queuedOrApplied = inputs.map((x) => x.inputType)
      th = await getThread(ctx, tid)
      // final Thread DTO derives agent/model/yolo from the RUNTIME_CONFIG path (may be null on row);
      // command success is authoritative via durable mailbox inputs.
      if (
        queuedOrApplied.includes('SET_AGENT') &&
        queuedOrApplied.some((t) => String(t).includes('MODEL')) &&
        queuedOrApplied.some((t) => String(t).includes('YOLO')) &&
        !th.processing
      ) {
        ctx.writeArtifact('thread-after-commands.json', JSON.stringify({ th, inputs }, null, 2))
        return
      }
      await sleep(250)
    }
    assert(
      inputs.some((x) => x.inputType === 'SET_AGENT'),
      JSON.stringify({ th, inputs }),
    )
    assert(
      inputs.some((x) => String(x.inputType || '').includes('MODEL')),
      JSON.stringify({ th, inputs }),
    )
    assert(
      inputs.some((x) => String(x.inputType || '').includes('YOLO')),
      JSON.stringify({ th, inputs }),
    )
  },
})

registerCase({
  id: 'thread.commands_model_invalid_variant_rejected',
  level: 'L1',
  title: 'Thread SET_MODEL 非法 Variant 在入队前拒绝',
  docs: 'PUT model 使用 seed Model + 不存在 Variant => 400；不写入 SET_MODEL input',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const model = ctx.vars.seedModel
    const { thread } = await createBootstrappedThread(ctx, {
      agentDefinitionId: ctx.vars.agent.id,
      title: `e2e-bad-variant-${cid().slice(0, 8)}`,
    })

    await expectHttpError(
      () =>
        ctx.call('PUT', `/api/threads/${thread.threadId}/model`, {
          modelId: String(model.id),
          variant: '__missing_variant__',
          clientMessageId: cid(),
          expectedExecutionEpoch: Number(thread.executionEpoch),
        }),
      { status: 400, messageIncludes: /variant/i },
    )
    assert((await listThreadInputs(ctx, thread.threadId)).length === 0, 'rejected command must not enqueue input')
  },
})

registerCase({
  id: 'harness.retry_policy_round_trip',
  level: 'L1',
  title: 'Retry policy GET/PUT 全量替换往返',
  docs: 'GET /api/harness/retry-policy 读 original；PUT 合法且可观察差异的策略；再 GET 断言四字段一致；finally 恢复 original',
  async run(ctx) {
    const { json: originalJson } = await ctx.call('GET', '/api/harness/retry-policy')
    const original = envelopeData(originalJson)
    assert(original && typeof original === 'object', JSON.stringify(originalJson))
    assert(
      ['maxRetries', 'backoffStrategy', 'baseDelayMillis', 'maxDelayMillis'].every((k) => k in original),
      JSON.stringify(original),
    )

    const next = {
      maxRetries: Number(original.maxRetries) === 2 ? 4 : 2,
      backoffStrategy: original.backoffStrategy === 'FIXED' ? 'EXPONENTIAL' : 'FIXED',
      baseDelayMillis: Number(original.baseDelayMillis) === 4000 ? 3000 : 4000,
      maxDelayMillis: Number(original.maxDelayMillis) === 8000 ? 10000 : 8000,
    }
    if (next.maxDelayMillis < next.baseDelayMillis) {
      next.maxDelayMillis = next.baseDelayMillis
    }
    assert(
      next.maxRetries !== Number(original.maxRetries) ||
        next.backoffStrategy !== original.backoffStrategy ||
        next.baseDelayMillis !== Number(original.baseDelayMillis) ||
        next.maxDelayMillis !== Number(original.maxDelayMillis),
      `next policy must differ from original: ${JSON.stringify({ original, next })}`,
    )

    try {
      const { json: putJson } = await ctx.call('PUT', '/api/harness/retry-policy', next)
      const putData = envelopeData(putJson)
      assert(Number(putData.maxRetries) === next.maxRetries, JSON.stringify(putData))
      assert(putData.backoffStrategy === next.backoffStrategy, JSON.stringify(putData))
      assert(Number(putData.baseDelayMillis) === next.baseDelayMillis, JSON.stringify(putData))
      assert(Number(putData.maxDelayMillis) === next.maxDelayMillis, JSON.stringify(putData))

      const { json: rereadJson } = await ctx.call('GET', '/api/harness/retry-policy')
      const reread = envelopeData(rereadJson)
      assert(Number(reread.maxRetries) === next.maxRetries, JSON.stringify(reread))
      assert(reread.backoffStrategy === next.backoffStrategy, JSON.stringify(reread))
      assert(Number(reread.baseDelayMillis) === next.baseDelayMillis, JSON.stringify(reread))
      assert(Number(reread.maxDelayMillis) === next.maxDelayMillis, JSON.stringify(reread))
    } finally {
      await ctx.call('PUT', '/api/harness/retry-policy', {
        maxRetries: original.maxRetries,
        backoffStrategy: original.backoffStrategy,
        baseDelayMillis: original.baseDelayMillis,
        maxDelayMillis: original.maxDelayMillis,
      })
    }
  },
})
