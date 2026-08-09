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
import {
  branchSettingsOf,
  createChat,
  createChatThread,
  createConfiguredChatThread,
  customMessageCommand,
  enqueueCommands,
  getThreadSnapshot,
  listChatThreads,
  setActiveToolsCommand,
  setAgentCommand,
  setEnvironmentCommand,
  setModelCommand,
  setYoloCommand,
  stopThread,
  threadIdOf,
  updateThreadHead,
  userMessageCommand,
  waitForQuiescentThread,
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
  id: 'catalog.internal_tools_hidden',
  level: 'L1',
  title: '内部 Platform Tool 不进入 Agent 可选目录',
  docs: 'GET /api/ai/catalog/tools 只返回 SELECTABLE Tool；load_skill/task 由 skills/subagents 派生激活，不能直接写入 Agent config.tools',
  async run(ctx) {
    const tools = envelopeData((await ctx.call('GET', '/api/ai/catalog/tools')).json)
    assert(Array.isArray(tools), JSON.stringify(tools))
    const names = tools.map((tool) => String(tool.name))
    assert(!names.includes('load_skill'), `load_skill must be internal: ${JSON.stringify(names)}`)
    assert(!names.includes('task'), `task must be internal: ${JSON.stringify(names)}`)
    assert(
      ['create_goal', 'get_goal', 'update_goal'].every((name) => names.includes(name)),
      `Goal plugin tools must remain selectable: ${JSON.stringify(names)}`,
    )
  },
})

registerCase({
  id: 'thread.chat_scoped_create_atomic',
  level: 'L1',
  title: 'Chat-scoped Thread 原子创建返回完整快照',
  docs: 'POST /api/ai/chat/{chatId}/threads body={title,branchSettings,yoloEnabled} => 201 HarnessThreadSnapshotDTO；thread 标识全为 decimal string，nextCommandSequence 从 1 开始，快照含 thread/entries/queuedCommands/modelInvocation/toolInvocations',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-thread-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const snapshot = await createChatThread(ctx, chat.id, {
      title: null,
      yoloEnabled: false,
      branchSettings: branchSettingsOf(ctx.vars.agent, modelSelectionOf(ctx), {
        activeTools: [],
      }),
    })
    const thread = snapshot.thread
    assert(thread.status === 'IDLE' && thread.processing === false, JSON.stringify(thread))
    threadIdOf(thread)
    assert(String(thread.revision) === '0', JSON.stringify(thread))
    // ThreadState 构造约束：nextCommandSequence 从 1 开始（runtime 事实）。
    assert(String(thread.nextCommandSequence) === '1', JSON.stringify(thread))
    assert(snapshot.entries.length === 1 && snapshot.entries[0].entryType === 'ROOT', JSON.stringify(snapshot.entries))
    assert(
      String(snapshot.entries[0].entryId) === String(thread.headEntryId),
      JSON.stringify(snapshot.entries),
    )
    assert(snapshot.queuedCommands.length === 0, JSON.stringify(snapshot.queuedCommands))
    assert(snapshot.modelInvocation === null, JSON.stringify(snapshot.modelInvocation))
    assert(snapshot.toolInvocations.length === 0, JSON.stringify(snapshot.toolInvocations))
    for (const hidden of [
      'executionEpoch',
      'environmentName',
      'activeAgentDefinitionId',
      'activeAgentName',
      'modelId',
      'usage',
    ]) {
      assert(!(hidden in thread), `Thread leaked ${hidden}: ${JSON.stringify(thread)}`)
    }

    // Chat-scoped list 返回数组且包含新 Thread（按关联时间新到旧）。
    const listed = await listChatThreads(ctx, chat.id)
    assert(
      listed.some((item) => String(item.threadId) === String(thread.threadId)),
      'created Thread missing from Chat list',
    )
    assert(listed[0]?.threadId === thread.threadId, `expected newest-first: ${JSON.stringify(listed)}`)
  },
})

registerCase({
  id: 'thread.branch_settings_projection',
  level: 'L1',
  title: '创建时完整 branchSettings 精确投影到 Thread 快照',
  docs: 'environmentName/agentName/model/activeTools/yoloEnabled 原样持久化并投影；null title 保持 null',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-settings-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const requested = {
      environmentName: null,
      agentName: ctx.vars.agent.name,
      model: modelSelectionOf(ctx),
      activeTools: ['read', 'grep'],
    }
    const snapshot = await createChatThread(ctx, chat.id, {
      title: null,
      yoloEnabled: true,
      branchSettings: requested,
    })
    assert(snapshot.thread.yoloEnabled === true, JSON.stringify(snapshot.thread))
    assert(
      isDeepStrictEqual(snapshot.thread.branchSettings, requested),
      JSON.stringify({ expected: requested, actual: snapshot.thread.branchSettings }),
    )
    // Thread branchSettings 独立于 Chat 默认值：Chat 仍保存自身 defaults。
    assert(chat.yoloEnabled === false, JSON.stringify(chat))
    // null title 保持 null（Session 拒绝 blank title 与此无关）。
    const reread = await getThreadSnapshot(ctx, snapshot.thread.threadId)
    assert(
      isDeepStrictEqual(reread.thread.branchSettings, requested),
      JSON.stringify(reread.thread.branchSettings),
    )
  },
})

registerCase({
  id: 'thread.user_message_strict_wire',
  level: 'L1',
  title: 'USER_MESSAGE 严格 wire 与 exact replay',
  docs: 'USER_MESSAGE 只能携带 type/clientCommandId/content（带 role 等多余字段 => 400）；202 返回 command DTO（decimal commandId/sequence、QUEUED、payloadJson 无 role）；同 batch exact replay 返回既有命令（不依赖异步消费）',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    // 隔离 Thread：本 case 独占队列状态，不依赖其他 case 的 cursor。
    const { snapshot } = await createConfiguredChatThread(ctx, {
      agent: ctx.vars.agent,
      model: modelSelectionOf(ctx),
      title: `e2e-user-wire-${cid().slice(0, 8)}`,
    })
    const thread = snapshot.thread
    const clientCommandId = cid()
    const batch = () => ({
      expectedHeadEntryId: thread.headEntryId,
      expectedNextCommandSequence: thread.nextCommandSequence,
      commands: [userMessageCommand('strict wire probe', clientCommandId)],
    })
    const commandsDto = await enqueueCommands(ctx, thread.threadId, batch())
    const command = commandsDto[0]
    assert(String(command.clientCommandId) === clientCommandId, JSON.stringify(command))
    assert(command.type === 'USER_MESSAGE' && command.state === 'QUEUED', JSON.stringify(command))
    assert(String(command.threadId) === String(thread.threadId), JSON.stringify(command))
    assert(/^[1-9]\d*$/.test(String(command.commandId)), JSON.stringify(command))
    assert(/^[1-9]\d*$/.test(String(command.sequence)), JSON.stringify(command))
    const payload = JSON.parse(command.payloadJson)
    assert(
      isDeepStrictEqual(payload, { message: { role: 'USER', contents: [{ type: 'text', text: 'strict wire probe' }] } }),
      `USER_MESSAGE payload must be the canonical AgentMessage: ${JSON.stringify(payload)}`,
    )
    // Exact replay：同 clientCommandId + 同 payload 整批重放返回既有命令（无副作用），
    // 不依赖异步 processor 是否已消费。
    const replay = await enqueueCommands(ctx, thread.threadId, batch())
    assert(
      String(replay[0].commandId) === String(command.commandId)
        && String(replay[0].sequence) === String(command.sequence),
      `replay must return the existing command: ${JSON.stringify({ command, replay })}`,
    )

    // 多余字段（role 等）=> 400（mapper requireForbidden）；400 不推进 cursor，可复用原 cursor。
    await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: thread.headEntryId,
          expectedNextCommandSequence: thread.nextCommandSequence,
          commands: [
            { type: 'USER_MESSAGE', clientCommandId: cid(), content: 'x', role: 'USER' },
          ],
        }),
      { status: 400, messageIncludes: /role/ },
    )
  },
})

registerCase({
  id: 'thread.custom_message_strict_wire',
  level: 'L1',
  title: 'CUSTOM_MESSAGE role 仅 SYSTEM|USER 且顺序稳定',
  docs: '同一原子 batch 同时入队 SYSTEM+USER 两条 CUSTOM_MESSAGE，顺序与 payload 稳定；非法/缺失 role => 400；USER_MESSAGE 缺 content => 400',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const { snapshot } = await createConfiguredChatThread(ctx, {
      agent: ctx.vars.agent,
      model: modelSelectionOf(ctx),
      title: `e2e-custom-wire-${cid().slice(0, 8)}`,
    })
    const thread = snapshot.thread
    const systemClientCommandId = cid()
    const userClientCommandId = cid()
    const commandsDto = await enqueueCommands(ctx, thread.threadId, {
      expectedHeadEntryId: thread.headEntryId,
      expectedNextCommandSequence: thread.nextCommandSequence,
      commands: [
        customMessageCommand('SYSTEM', 'system probe', systemClientCommandId),
        customMessageCommand('USER', 'user probe', userClientCommandId),
      ],
    })
    assert(
      commandsDto.map((command) => command.type).join(',') === 'CUSTOM_MESSAGE,CUSTOM_MESSAGE',
      JSON.stringify(commandsDto),
    )
    assert(
      String(commandsDto[0].clientCommandId) === systemClientCommandId
        && String(commandsDto[1].clientCommandId) === userClientCommandId,
      JSON.stringify(commandsDto),
    )
    assert(
      Number(commandsDto[1].sequence) === Number(commandsDto[0].sequence) + 1,
      `sequences must be contiguous: ${JSON.stringify(commandsDto)}`,
    )
    const systemPayload = JSON.parse(commandsDto[0].payloadJson)
    const userPayload = JSON.parse(commandsDto[1].payloadJson)
    assert(
      isDeepStrictEqual(systemPayload, { message: { role: 'SYSTEM', contents: [{ type: 'text', text: 'system probe' }] } }),
      JSON.stringify(systemPayload),
    )
    assert(
      isDeepStrictEqual(userPayload, { message: { role: 'USER', contents: [{ type: 'text', text: 'user probe' }] } }),
      JSON.stringify(userPayload),
    )
    // 非法 role（ASSISTANT）=> 400（mapper requireRole）。
    await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: thread.headEntryId,
          expectedNextCommandSequence: thread.nextCommandSequence,
          commands: [
            { type: 'CUSTOM_MESSAGE', clientCommandId: cid(), content: 'x', role: 'ASSISTANT' },
          ],
        }),
      { status: 400, messageIncludes: /SYSTEM|USER|role/i },
    )
    // 缺 content => 400（mapper requireText）。
    await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: thread.headEntryId,
          expectedNextCommandSequence: thread.nextCommandSequence,
          commands: [{ type: 'USER_MESSAGE', clientCommandId: cid() }],
        }),
      { status: 400, messageIncludes: /content/i },
    )
  },
})

registerCase({
  id: 'thread.command_idempotent_replay',
  level: 'L1',
  title: 'clientCommandId 幂等 replay 与部分重放拒绝',
  docs: '整批同 clientCommandId 重放 => 返回既有命令（无副作用）；仅部分存在 => 409 PARTIAL_COMMAND_REPLAY',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const { snapshot } = await createConfiguredChatThread(ctx, {
      agent: ctx.vars.agent,
      model: modelSelectionOf(ctx),
      title: `e2e-replay-${cid().slice(0, 8)}`,
    })
    const thread = snapshot.thread
    const clientCommandId = cid()
    const first = await enqueueCommands(ctx, thread.threadId, {
      expectedHeadEntryId: thread.headEntryId,
      expectedNextCommandSequence: thread.nextCommandSequence,
      commands: [userMessageCommand('idempotent replay', clientCommandId)],
    })
    const replay = await enqueueCommands(ctx, thread.threadId, {
      expectedHeadEntryId: thread.headEntryId,
      expectedNextCommandSequence: thread.nextCommandSequence,
      commands: [userMessageCommand('idempotent replay', clientCommandId)],
    })
    assert(
      String(replay[0].commandId) === String(first[0].commandId),
      `replay must return the existing command: ${JSON.stringify({ first, replay })}`,
    )
    // 部分重放：新命令 + 已存在命令 => 409。
    await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: thread.headEntryId,
          expectedNextCommandSequence: thread.nextCommandSequence,
          commands: [
            userMessageCommand('replayed again', clientCommandId),
            userMessageCommand('brand new', cid()),
          ],
        }),
      { status: 409, messageIncludes: /PARTIAL_COMMAND_REPLAY|replays only/i },
    )
  },
})

registerCase({
  id: 'thread.stale_command_cas_rejected',
  level: 'L1',
  title: 'stale 命令 CAS cursor 被拒绝',
  docs: 'expectedHeadEntryId/expectedNextCommandSequence 与快照不符 => 409；隔离 Thread 上精确断言 nextCommandSequence/revision/head 前后不变',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const { snapshot } = await createConfiguredChatThread(ctx, {
      agent: ctx.vars.agent,
      model: modelSelectionOf(ctx),
      title: `e2e-stale-cas-${cid().slice(0, 8)}`,
    })
    const thread = snapshot.thread
    const before = await getThreadSnapshot(ctx, thread.threadId)
    await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: '999999999',
          expectedNextCommandSequence: thread.nextCommandSequence,
          commands: [userMessageCommand('stale head', cid())],
        }),
      { status: 409, messageIncludes: /head|conflict|stale/i },
    )
    await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: thread.headEntryId,
          expectedNextCommandSequence: '999999999',
          commands: [userMessageCommand('stale sequence', cid())],
        }),
      { status: 409, messageIncludes: /sequence|conflict|stale/i },
    )
    const after = await getThreadSnapshot(ctx, thread.threadId)
    assert(
      String(after.thread.nextCommandSequence) === String(before.thread.nextCommandSequence)
        && String(after.thread.revision) === String(before.thread.revision)
        && String(after.thread.headEntryId) === String(before.thread.headEntryId),
      `stale batches must not mutate the Thread: ${JSON.stringify({
        before: before.thread,
        after: after.thread,
      })}`,
    )
  },
})

registerCase({
  id: 'thread.rebind_same_session',
  level: 'L1',
  title: 'PUT /head 同 target no-op 与 revision CAS',
  docs: '同 target 在 revision 校验前 no-op（即使 stale 也不 bump）；非同 target + stale revision => 409；真实同 Session 历史回退由 L3 branch case 覆盖',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const { chat, snapshot } = await createConfiguredChatThread(ctx, {
      agent: ctx.vars.agent,
      model: modelSelectionOf(ctx),
      title: `e2e-rebind-${cid().slice(0, 8)}`,
    })
    const thread = snapshot.thread
    // L1 验证 same-target replay 优先级与不同 target 的 revision CAS；真实同 Session 历史
    // 回退由 L3 branch case 覆盖。
    const moved = await updateThreadHead(ctx, thread.threadId, {
      targetEntryId: thread.headEntryId,
      expectedRevision: thread.revision,
    })
    assert(String(moved.headEntryId) === String(thread.headEntryId), JSON.stringify(moved))
    assert(String(moved.revision) === String(thread.revision), 'same-target rebind must be a no-op')
    assert(String(moved.sessionId) === String(thread.sessionId), JSON.stringify(moved))

    const replayed = await updateThreadHead(ctx, thread.threadId, {
      targetEntryId: thread.headEntryId,
      expectedRevision: '999999999',
    })
    assert(
      String(replayed.headEntryId) === String(thread.headEntryId)
        && String(replayed.revision) === String(thread.revision),
      `same-target replay must precede revision CAS: ${JSON.stringify(replayed)}`,
    )

    const differentTargetEntryId = String(BigInt(thread.headEntryId) + 999999999n)
    await expectHttpError(
      () =>
        updateThreadHead(ctx, thread.threadId, {
          targetEntryId: differentTargetEntryId,
          expectedRevision: '999999999',
        }),
      { status: 409, messageIncludes: /revision/i },
    )
  },
})

registerCase({
  id: 'thread.rebind_cross_session_rejected',
  level: 'L1',
  title: '跨 Session move head 被拒绝',
  docs: '另一 Thread（另一 Session）的 entry id 作为 target => 409 MOVE_TARGET_CROSS_SESSION；head/session 不变',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const { snapshot: first } = await createConfiguredChatThread(ctx, {
      agent: ctx.vars.agent,
      model: modelSelectionOf(ctx),
      title: `e2e-cross-a-${cid().slice(0, 8)}`,
    })
    const { snapshot: second } = await createConfiguredChatThread(ctx, {
      agent: ctx.vars.agent,
      model: modelSelectionOf(ctx),
      title: `e2e-cross-b-${cid().slice(0, 8)}`,
    })
    assert(
      String(first.thread.sessionId) !== String(second.thread.sessionId),
      'distinct Threads must have distinct Sessions',
    )
    await expectHttpError(
      () =>
        updateThreadHead(ctx, first.thread.threadId, {
          targetEntryId: second.entries[0].entryId,
          expectedRevision: first.thread.revision,
        }),
      { status: 409, messageIncludes: /session/i },
    )
    const after = await getThreadSnapshot(ctx, first.thread.threadId)
    assert(String(after.thread.headEntryId) === String(first.thread.headEntryId), JSON.stringify(after.thread))
    assert(String(after.thread.sessionId) === String(first.thread.sessionId), JSON.stringify(after.thread))
  },
})

registerCase({
  id: 'thread.stop_idle_noop',
  level: 'L1',
  title: 'IDLE stop 为 no-op 且 stale revision 被拒绝',
  docs: 'POST /stop body={stopRequestId,expectedRevision}；IDLE 无 queued 时 status=IDLE、stoppedTurnEndEntryId=null、revision 不变；同 stopRequestId 再次调用仍为 IDLE no-op（IDLE 不写持久 marker，无 replay）；stale revision => 409。真实 STOPPED/REPLAYED 由 L2 real.stop_partial_continue 覆盖',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const { snapshot } = await createConfiguredChatThread(ctx, {
      agent: ctx.vars.agent,
      model: modelSelectionOf(ctx),
      title: `e2e-stop-${cid().slice(0, 8)}`,
    })
    const thread = snapshot.thread
    const stopRequestId = cid()
    const first = await stopThread(ctx, thread.threadId, {
      stopRequestId,
      expectedRevision: thread.revision,
    })
    assert(first.status === 'IDLE', JSON.stringify(first))
    assert(first.stoppedTurnEndEntryId === null, JSON.stringify(first))
    assert(first.cancelledCommandCount === 0, JSON.stringify(first))
    assert(String(first.thread.revision) === String(thread.revision), JSON.stringify(first))
    // IDLE stop 不写持久 marker：同 stopRequestId 再次调用仍是 IDLE no-op（不是 REPLAYED）。
    const again = await stopThread(ctx, thread.threadId, {
      stopRequestId,
      expectedRevision: thread.revision,
    })
    assert(again.status === 'IDLE', JSON.stringify(again))
    assert(again.stoppedTurnEndEntryId === null, JSON.stringify(again))
    assert(String(again.thread.revision) === String(thread.revision), JSON.stringify(again))
    await expectHttpError(
      () =>
        stopThread(ctx, thread.threadId, {
          stopRequestId: cid(),
          expectedRevision: '999999999',
        }),
      { status: 409, messageIncludes: /revision/i },
    )
  },
})

registerCase({
  id: 'thread.branch_settings_diff_commands',
  level: 'L1',
  title: 'SET_* 命令一个原子 batch 精确 wire 并消费投影',
  docs: '前端固定顺序 SET_ENVIRONMENT,SET_AGENT,SET_MODEL,SET_ACTIVE_TOOLS,SET_YOLO,USER_MESSAGE 一个 batch；SET_AGENT 使用 canonical 但不存在的名称，使 Resolver 在调用 Provider 前确定性 PLANNING_FAILED；等 quiescent 后 Thread branchSettings/yoloEnabled 精确投影、queue 清空、USER entry 与 AssistantError 可见，最终 TURN_END(FAILED, continueModel=false)；额外字段与非法 environmentName => 400',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    // 隔离 Thread：SET_ENVIRONMENT admission 要求 pre-state 干净（无 queued USER/CUSTOM、IDLE、无 Work）。
    const { snapshot } = await createConfiguredChatThread(ctx, {
      agent: ctx.vars.agent,
      model: modelSelectionOf(ctx),
      title: `e2e-set-all-${cid().slice(0, 8)}`,
    })
    const thread = snapshot.thread
    const marker = `SET-ALL-${cid()}`
    const modelSelection = modelSelectionOf(ctx)
    const missingAgentName = `missing-agent-${cid().slice(0, 8)}`
    const commands = [
      setEnvironmentCommand(null, cid()),
      setAgentCommand(missingAgentName, cid()),
      setModelCommand(
        {
          providerName: modelSelection.providerName,
          modelName: modelSelection.modelName,
          variant: modelSelection.variant,
        },
        cid(),
      ),
      setActiveToolsCommand(['read'], cid()),
      setYoloCommand(true, cid()),
      userMessageCommand(`${marker} 消费 SET 后的第一条消息。`, cid()),
    ]
    const dto = await enqueueCommands(ctx, thread.threadId, {
      expectedHeadEntryId: thread.headEntryId,
      expectedNextCommandSequence: thread.nextCommandSequence,
      commands,
    })
    assert(
      dto.map((command) => command.type).join(',') ===
        'SET_ENVIRONMENT,SET_AGENT,SET_MODEL,SET_ACTIVE_TOOLS,SET_YOLO,USER_MESSAGE',
      JSON.stringify(dto),
    )
    for (let i = 1; i < dto.length; i++) {
      assert(
        Number(dto[i].sequence) === Number(dto[i - 1].sequence) + 1,
        `sequences must be contiguous: ${JSON.stringify(dto)}`,
      )
    }

    // 等 quiescent：SET_* 被消费并投影到 base settings；USER_MESSAGE 触发一个 turn。
    const finalThread = await waitForQuiescentThread(ctx, thread.threadId, {
      timeoutMs: 60_000,
      intervalMs: 250,
    })
    assert(finalThread.status === 'IDLE', JSON.stringify(finalThread))
    const finalSnapshot = await getThreadSnapshot(ctx, thread.threadId)
    const expectedSettings = {
      environmentName: null,
      agentName: missingAgentName,
      model: {
        providerName: modelSelection.providerName,
        modelName: modelSelection.modelName,
        variant: modelSelection.variant,
      },
      activeTools: ['read'],
    }
    assert(
      isDeepStrictEqual(finalSnapshot.thread.branchSettings, expectedSettings),
      JSON.stringify({
        expected: expectedSettings,
        actual: finalSnapshot.thread.branchSettings,
      }),
    )
    assert(finalSnapshot.thread.yoloEnabled === true, JSON.stringify(finalSnapshot.thread))
    assert(
      finalSnapshot.queuedCommands.length === 0,
      `SET_* batch must be consumed: ${JSON.stringify(finalSnapshot.queuedCommands)}`,
    )
    // 输入 USER entry 可见；消费证据必须包含 Resolver 在调用 Provider 前产生的 durable
    // AssistantError(PLANNING_FAILED)，不能让 USER MESSAGE 自身满足消费证据。
    const userEntry = finalSnapshot.entries.find((entry) => {
      if (String(entry.entryType || '').toUpperCase() !== 'MESSAGE') return false
      const message = JSON.parse(entry.payloadJson).message
      return message?.role === 'USER' && JSON.stringify(message).includes(marker)
    })
    assert(userEntry, `USER entry missing: ${JSON.stringify(finalSnapshot.entries)}`)
    const planningError = finalSnapshot.entries.find((entry) => {
      if (String(entry.entryType || '').toUpperCase() !== 'ASSISTANT_ERROR') return false
      const error = JSON.parse(entry.payloadJson).error
      return error?.code === 'PLANNING_FAILED' && String(error?.message || '').includes(missingAgentName)
    })
    assert(
      planningError,
      `expected a durable PLANNING_FAILED AssistantError: ${JSON.stringify(finalSnapshot.entries)}`,
    )
    const turnEndEntries = finalSnapshot.entries.filter(
      (entry) => String(entry.entryType || '').toUpperCase() === 'TURN_END',
    )
    assert(turnEndEntries.length >= 1, `expected at least one TURN_END: ${JSON.stringify(finalSnapshot.entries)}`)
    const lastTurnEnd = JSON.parse(turnEndEntries.at(-1).payloadJson)
    assert(
      lastTurnEnd.outcome === 'FAILED' && lastTurnEnd.continueModel === false,
      `expected the final TURN_END to converge: ${JSON.stringify(lastTurnEnd)}`,
    )

    // 不相关字段与未知字段都必须 400；400 不推进 cursor，可复用 quiescent 后最新 cursor。
    const fresh = await getThreadSnapshot(ctx, thread.threadId)
    await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: fresh.thread.headEntryId,
          expectedNextCommandSequence: fresh.thread.nextCommandSequence,
          commands: [{ type: 'SET_YOLO', clientCommandId: cid(), yoloEnabled: true, content: 'x' }],
        }),
      { status: 400, messageIncludes: /content/i },
    )
    await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: fresh.thread.headEntryId,
          expectedNextCommandSequence: fresh.thread.nextCommandSequence,
          commands: [
            {
              type: 'SET_YOLO',
              clientCommandId: cid(),
              yoloEnabled: true,
              unexpected: true,
            },
          ],
        }),
      { status: 400 },
    )
    // SET_ENVIRONMENT 只接受 canonical bounded 小写路由名称（mapper EnvironmentName 校验；resolver 运行时才查 registry READY）。
    await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: fresh.thread.headEntryId,
          expectedNextCommandSequence: fresh.thread.nextCommandSequence,
          commands: [setEnvironmentCommand('Not-A-Name', cid())],
        }),
      { status: 400, messageIncludes: /environmentName/i },
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
      { status: 404, messageIncludes: /thread 999999999 does not exist/ },
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

function compareModel(left, right) {
  return `${left.provider}/${left.name}`.localeCompare(`${right.provider}/${right.name}`)
}

/** 由 seed Agent + seed Model 构造 branchSettings.model 引用（只切第一个 '/'，保留 model name 内 '/'）。 */
function modelSelectionOf(ctx) {
  const agent = ctx.vars.agent
  const model = ctx.vars.seedModel
  const separator = String(agent.model || '').indexOf('/')
  assert(separator > 0, `agent.model must be provider/model: ${JSON.stringify(agent)}`)
  const providerName = String(agent.model).slice(0, separator)
  const modelName = String(agent.model).slice(separator + 1)
  assert(providerName && modelName, `agent.model must be provider/model: ${JSON.stringify(agent)}`)
  return {
    providerName,
    modelName,
    variant: agent.variant || model.config.defaultVariant,
  }
}
