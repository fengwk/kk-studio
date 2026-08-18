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
  getThreadEntries,
  getThreadSnapshot,
  listChatThreads,
  setActiveToolsCommand,
  setAgentCommand,
  setEnvironmentCommand,
  setModelCommand,
  setThreadYolo,
  stopThread,
  threadIdOf,
  updateThreadHead,
  userMessageCommand,
  waitForDurableMessages,
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
  docs: 'POST /api/ai/chat/{chatId}/threads body={title,branchSettings,yoloEnabled} => 201 HarnessThreadSnapshotDTO；thread/entry/session/command 标识全为 canonical UUID string，nextCommandSequence 从 1 开始，快照含 thread/entries/queuedCommands/modelInvocation/toolInvocations',
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
      'environment',
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
  docs: 'EnvironmentBinding/agentName/model/activeTools/yoloEnabled 原样持久化并投影；null title 保持 null',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-settings-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const requested = {
      environment: null,
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
  title: 'USER_MESSAGE 严格 wire（仅 contents TEXT/ATTACHMENT）与 exact replay',
  docs: 'USER_MESSAGE 只接受一个非空有序 contents 列表（TEXT/ATTACHMENT）；text/content shorthand、role、未知字段、空 contents、IMAGE/AUDIO/VIDEO 内容类型与非 canonical uploadId => 400；202 payloadJson 是 canonical AgentMessage 且响应携带 64 位小写 hex requestHash；同 batch exact replay 返回既有命令（sequence/requestHash 稳定）',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    // 隔离 Thread：本 case 独占队列状态，不依赖其他 case 的 cursor。
    const { snapshot } = await createConfiguredChatThread(ctx, {
      agent: ctx.vars.agent,
      model: modelSelectionOf(ctx),
      title: `e2e-user-wire-${cid().slice(0, 8)}`,
      branchAgentName: `e2e-user-wire-missing-${cid().slice(0, 8)}`,
    })
    const thread = snapshot.thread
    const singleClientCommandId = cid()
    const structuredClientCommandId = cid()
    const secondClientCommandId = cid()
    const batch = () => ({
      expectedHeadEntryId: thread.headEntryId,
      expectedNextCommandSequence: thread.nextCommandSequence,
      commands: [
        {
          type: 'USER_MESSAGE',
          clientCommandId: singleClientCommandId,
          contents: [{ type: 'TEXT', text: 'strict wire probe' }],
        },
        {
          type: 'USER_MESSAGE',
          clientCommandId: structuredClientCommandId,
          contents: [
            { type: 'TEXT', text: 'animate this' },
            { type: 'TEXT', text: 'and this' },
          ],
        },
        userMessageCommand('second probe', secondClientCommandId),
      ],
    })
    const commandsDto = await enqueueCommands(ctx, thread.threadId, batch())
    assert(commandsDto.length === 3, JSON.stringify(commandsDto))
    const command = commandsDto[0]
    assert(String(command.clientCommandId) === singleClientCommandId, JSON.stringify(command))
    assert(commandsDto.every((item) => item.type === 'USER_MESSAGE' && item.state === 'QUEUED'), JSON.stringify(commandsDto))
    assert(commandsDto.every((item) => String(item.threadId) === String(thread.threadId)), JSON.stringify(commandsDto))
    assert(commandsDto.every((item) => /^[1-9]\d*$/.test(String(item.sequence))), JSON.stringify(commandsDto))
    assert(commandsDto.every((item) => /^[0-9a-f]{64}$/.test(String(item.requestHash))), JSON.stringify(commandsDto))
    const payload = JSON.parse(commandsDto[0].payloadJson)
    assert(
      isDeepStrictEqual(payload, { message: { role: 'USER', contents: [{ type: 'text', text: 'strict wire probe' }] } }),
      `USER_MESSAGE payload must be the canonical AgentMessage: ${JSON.stringify(payload)}`,
    )
    const structuredPayload = JSON.parse(commandsDto[1].payloadJson)
    assert(
      isDeepStrictEqual(structuredPayload, {
        message: {
          role: 'USER',
          contents: [
            { type: 'text', text: 'animate this' },
            { type: 'text', text: 'and this' },
          ],
        },
      }),
      `structured USER_MESSAGE payload must be canonical: ${JSON.stringify(structuredPayload)}`,
    )
    const secondPayload = JSON.parse(commandsDto[2].payloadJson)
    assert(
      isDeepStrictEqual(secondPayload, {
        message: { role: 'USER', contents: [{ type: 'text', text: 'second probe' }] },
      }),
      `contents TEXT payload must be canonical: ${JSON.stringify(secondPayload)}`,
    )
    // Exact replay：同 clientCommandId + 同 hash 整批重放返回既有命令（无副作用），
    // 不依赖异步 processor 是否已消费。
    const replay = await enqueueCommands(ctx, thread.threadId, batch())
    assert(
      replay.length === commandsDto.length
        && replay.every(
          (item, index) =>
            String(item.sequence) === String(commandsDto[index].sequence)
            && String(item.requestHash) === String(commandsDto[index].requestHash),
        ),
      `replay must return the existing commands: ${JSON.stringify({ commandsDto, replay })}`,
    )
    await waitForQuiescentThread(ctx, thread.threadId, {
      timeoutMs: 60_000,
      intervalMs: 100,
    })
    const validationThread = (await getThreadSnapshot(ctx, thread.threadId)).thread

    const expectInvalidUserCommand = (command, options = { status: 400 }) =>
      expectHttpError(
        () =>
          enqueueCommands(ctx, thread.threadId, {
            expectedHeadEntryId: validationThread.headEntryId,
            expectedNextCommandSequence: validationThread.nextCommandSequence,
            commands: [command],
          }),
        options,
      )

    // 文本 shorthand（text / content）已从 USER_MESSAGE wire 移除。
    await expectInvalidUserCommand({ type: 'USER_MESSAGE', clientCommandId: cid(), text: 'x' })
    await expectInvalidUserCommand(
      { type: 'USER_MESSAGE', clientCommandId: cid(), content: 'x' },
      { status: 400, messageIncludes: /content|forbidden/i },
    )
    // 多余字段（role 等）与缺少 contents 字段均确定性 400。
    await expectInvalidUserCommand(
      {
        type: 'USER_MESSAGE',
        clientCommandId: cid(),
        contents: [{ type: 'TEXT', text: 'x' }],
        role: 'USER',
      },
      { status: 400, messageIncludes: /role/ },
    )
    await expectInvalidUserCommand(
      { type: 'USER_MESSAGE', clientCommandId: cid() },
      { status: 400, messageIncludes: /contents/i },
    )
    // IMAGE/AUDIO/VIDEO 内容类型已从 wire 契约移除。
    for (const [type, mediaType, source] of [
      ['IMAGE', 'image/png', 'https://example.test/image.png'],
      ['AUDIO', 'audio/mpeg', 'https://example.test/audio.mp3'],
      ['VIDEO', 'video/mp4', 'https://example.test/video.mp4'],
    ]) {
      await expectInvalidUserCommand({
        type: 'USER_MESSAGE',
        clientCommandId: cid(),
        contents: [{ type, mediaType, source }],
      })
    }
    // ATTACHMENT uploadId 必须是 canonical UUID，且必须指向 READY upload。
    await expectInvalidUserCommand(
      {
        type: 'USER_MESSAGE',
        clientCommandId: cid(),
        contents: [{ type: 'ATTACHMENT', uploadId: 'not-a-uuid' }],
      },
      { status: 400, messageIncludes: /uploadId/i },
    )
    await expectInvalidUserCommand({
      type: 'USER_MESSAGE',
      clientCommandId: cid(),
      contents: [{ type: 'ATTACHMENT', uploadId: cid() }],
    })
  },
})

registerCase({
  id: 'thread.custom_message_strict_wire',
  level: 'L1',
  title: 'CUSTOM_MESSAGE role 仅 SYSTEM|USER 且顺序稳定',
  docs: '同一原子 batch 同时入队 SYSTEM+USER 两条 CUSTOM_MESSAGE，顺序与 payload 稳定；非法/缺失 role => 400；USER_MESSAGE 缺 contents => 400',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const { snapshot } = await createConfiguredChatThread(ctx, {
      agent: ctx.vars.agent,
      model: modelSelectionOf(ctx),
      title: `e2e-custom-wire-${cid().slice(0, 8)}`,
      branchAgentName: `e2e-custom-wire-missing-${cid().slice(0, 8)}`,
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
    await waitForQuiescentThread(ctx, thread.threadId, {
      timeoutMs: 60_000,
      intervalMs: 100,
    })
    const validationThread = (await getThreadSnapshot(ctx, thread.threadId)).thread
    // 非法 role（ASSISTANT）=> 400（mapper requireRole）。
    await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: validationThread.headEntryId,
          expectedNextCommandSequence: validationThread.nextCommandSequence,
          commands: [
            { type: 'CUSTOM_MESSAGE', clientCommandId: cid(), content: 'x', role: 'ASSISTANT' },
          ],
        }),
      { status: 400, messageIncludes: /SYSTEM|USER|role/i },
    )
    // USER_MESSAGE 缺 contents => 400（仅 contents 形态）。
    await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: validationThread.headEntryId,
          expectedNextCommandSequence: validationThread.nextCommandSequence,
          commands: [{ type: 'USER_MESSAGE', clientCommandId: cid() }],
        }),
      { status: 400, messageIncludes: /contents/i },
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
      branchAgentName: `e2e-replay-missing-${cid().slice(0, 8)}`,
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
      String(replay[0].sequence) === String(first[0].sequence)
        && String(replay[0].requestHash) === String(first[0].requestHash),
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
  docs: '从当前 Catalog 解析任一有效 Agent/Model；expectedHeadEntryId/expectedNextCommandSequence 与快照不符 => 409 + errors.reason=STALE_COMMAND_CURSOR；隔离 Thread 上精确断言 nextCommandSequence/revision/head 前后不变',
  async run(ctx) {
    const { agent, model } = await resolveAnyCatalogTarget(ctx)
    const { snapshot } = await createConfiguredChatThread(ctx, {
      agent,
      model,
      title: `e2e-stale-cas-${cid().slice(0, 8)}`,
    })
    const thread = snapshot.thread
    const before = await getThreadSnapshot(ctx, thread.threadId)
    const staleHead = await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: cid(),
          expectedNextCommandSequence: thread.nextCommandSequence,
          commands: [userMessageCommand('stale head', cid())],
        }),
      { status: 409, messageIncludes: /head|conflict|stale/i },
    )
    const staleHeadBody = JSON.parse(staleHead.body)
    assert(
      staleHeadBody.errors?.reason === 'STALE_COMMAND_CURSOR',
      `stale head conflict reason: ${staleHead.body}`,
    )
    const staleSequence = await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: thread.headEntryId,
          expectedNextCommandSequence: '999999999',
          commands: [userMessageCommand('stale sequence', cid())],
        }),
      { status: 409, messageIncludes: /sequence|conflict|stale/i },
    )
    const staleSequenceBody = JSON.parse(staleSequence.body)
    assert(
      staleSequenceBody.errors?.reason === 'STALE_COMMAND_CURSOR',
      `stale sequence conflict reason: ${staleSequence.body}`,
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

    const differentTargetEntryId = cid()
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
  id: 'thread.session_entry_tree',
  level: 'L1',
  title: '完整 Session Entry Tree 保留非当前历史分支',
  docs: 'GET /api/ai/runtime/threads/{threadId}/entries 返回 Thread 所属 Session 的全部 immutable Entries；head 回退后新建分支，snapshot 仍仅含当前 root-to-head，而 entries 同时保留原分支与当前分支及稳定 parent 关系',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const suffix = cid().slice(0, 8)
    const trunkMessage = `entry tree trunk ${suffix}`
    const originalMessage = `entry tree original ${suffix}`
    const alternateMessage = `entry tree alternate ${suffix}`
    const { snapshot: created } = await createConfiguredChatThread(ctx, {
      agent: ctx.vars.agent,
      model: modelSelectionOf(ctx),
      title: `e2e-entry-tree-${suffix}`,
      branchAgentName: `e2e-entry-tree-missing-${suffix}`,
    })
    const threadId = created.thread.threadId

    await enqueueCommands(ctx, threadId, {
      expectedHeadEntryId: created.thread.headEntryId,
      expectedNextCommandSequence: created.thread.nextCommandSequence,
      commands: [userMessageCommand(trunkMessage, cid())],
    })
    const { snapshot: trunk } = await waitForDurableMessages(ctx, threadId, [trunkMessage])
    const branchPointEntryId = trunk.thread.headEntryId

    await enqueueCommands(ctx, threadId, {
      expectedHeadEntryId: trunk.thread.headEntryId,
      expectedNextCommandSequence: trunk.thread.nextCommandSequence,
      commands: [userMessageCommand(originalMessage, cid())],
    })
    const { snapshot: original } = await waitForDurableMessages(
      ctx,
      threadId,
      [trunkMessage, originalMessage],
    )
    const moved = await updateThreadHead(ctx, threadId, {
      targetEntryId: branchPointEntryId,
      expectedRevision: original.thread.revision,
    })
    await enqueueCommands(ctx, threadId, {
      expectedHeadEntryId: moved.headEntryId,
      expectedNextCommandSequence: moved.nextCommandSequence,
      commands: [userMessageCommand(alternateMessage, cid())],
    })
    const { snapshot: active, entries } = await waitForDurableMessages(
      ctx,
      threadId,
      [trunkMessage, originalMessage, alternateMessage],
    )

    const entryText = (entry) => {
      if (String(entry.entryType || '').toUpperCase() !== 'MESSAGE') return ''
      const message = JSON.parse(entry.payloadJson).message
      return (message?.contents || [])
        .filter((content) => content?.type === 'text')
        .map((content) => content.text)
        .join('\n')
    }
    const originalUser = entries.find((entry) => entryText(entry) === originalMessage)
    const alternateUser = entries.find((entry) => entryText(entry) === alternateMessage)
    assert(originalUser && alternateUser, `full Entry Tree lost a branch: ${JSON.stringify(entries)}`)
    assert(
      !active.entries.some((entry) => entryText(entry) === originalMessage)
        && active.entries.some((entry) => entryText(entry) === alternateMessage),
      `snapshot must remain current root-to-head only: ${JSON.stringify(active.entries)}`,
    )
    const byId = new Map(entries.map((entry) => [entry.entryId, entry]))
    const originalTurnStart = byId.get(originalUser.parentEntryId)
    const alternateTurnStart = byId.get(alternateUser.parentEntryId)
    assert(
      originalTurnStart?.entryType === 'TURN_START'
        && alternateTurnStart?.entryType === 'TURN_START'
        && originalTurnStart.entryId !== alternateTurnStart.entryId
        && originalTurnStart.parentEntryId === branchPointEntryId
        && alternateTurnStart.parentEntryId === branchPointEntryId,
      `historical branches do not share the expected parent: ${JSON.stringify({
        branchPointEntryId,
        originalTurnStart,
        alternateTurnStart,
      })}`,
    )
    assert(
      JSON.stringify(await getThreadEntries(ctx, threadId)) === JSON.stringify(entries),
      'full Entry Tree ordering must be stable across reads',
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
  docs: '前端固定顺序 SET_ENVIRONMENT,SET_AGENT,SET_MODEL,SET_ACTIVE_TOOLS,USER_MESSAGE 一个 batch（yolo 走直接控制面，绝不进入 mailbox）；SET_AGENT 使用 canonical 但不存在的名称，使 Resolver 在调用 Provider 前确定性 PLANNING_FAILED；等 quiescent 后 Thread branchSettings 精确投影、queue 清空、USER entry 与 AssistantError 可见，最终 TURN_END(FAILED, continueModel=false)；未知类型、额外字段、显式 null 与非法 EnvironmentBinding => 400',
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
      userMessageCommand(`${marker} 消费 SET 后的第一条消息。`, cid()),
    ]
    const dto = await enqueueCommands(ctx, thread.threadId, {
      expectedHeadEntryId: thread.headEntryId,
      expectedNextCommandSequence: thread.nextCommandSequence,
      commands,
    })
    assert(
      dto.map((command) => command.type).join(',') ===
        'SET_ENVIRONMENT,SET_AGENT,SET_MODEL,SET_ACTIVE_TOOLS,USER_MESSAGE',
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
      environment: null,
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
    // yolo 是直接控制面：batch 不含 SET_YOLO，Thread 保持创建时的值（直接更新见 thread.yolo_direct_update）。
    assert(
      finalSnapshot.thread.yoloEnabled === thread.yoloEnabled,
      JSON.stringify(finalSnapshot.thread),
    )
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
          commands: [{ type: 'RENAME_THREAD', clientCommandId: cid() }],
        }),
      { status: 400, messageIncludes: /type/i },
    )
    await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: fresh.thread.headEntryId,
          expectedNextCommandSequence: fresh.thread.nextCommandSequence,
          commands: [
            {
              type: 'SET_AGENT',
              clientCommandId: cid(),
              agentName: 'x',
              unexpected: true,
            },
          ],
        }),
      { status: 400 },
    )
    // SET_ENVIRONMENT 必须区分字段缺省与显式 null：缺省拒绝，显式 null（上方主 batch）合法解绑。
    await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: fresh.thread.headEntryId,
          expectedNextCommandSequence: fresh.thread.nextCommandSequence,
          commands: [{ type: 'SET_ENVIRONMENT', clientCommandId: cid() }],
        }),
      { status: 400, messageIncludes: /environment/i },
    )
    // 其他 discriminator 即使显式传 environment:null 也必须按 forbidden 拒绝。
    await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: fresh.thread.headEntryId,
          expectedNextCommandSequence: fresh.thread.nextCommandSequence,
          commands: [
            {
              type: 'SET_MODEL',
              clientCommandId: cid(),
              model: {
                providerName: modelSelection.providerName,
                modelName: modelSelection.modelName,
                variant: modelSelection.variant,
              },
              environment: null,
            },
          ],
        }),
      { status: 400, messageIncludes: /environment/i },
    )
    // SET_ENVIRONMENT 只接受完整 binding；name/path 在 mapper 处校验，resolver 运行时才查 registry READY。
    await expectHttpError(
      () =>
        enqueueCommands(ctx, thread.threadId, {
          expectedHeadEntryId: fresh.thread.headEntryId,
          expectedNextCommandSequence: fresh.thread.nextCommandSequence,
          commands: [
            {
              type: 'SET_ENVIRONMENT',
              clientCommandId: cid(),
              environment: { name: 'Not-A-Name', workspacePath: '.' },
            },
          ],
        }),
      { status: 400, messageIncludes: /environment|name/i },
    )
  },
})

registerCase({
  id: 'thread.yolo_direct_update',
  level: 'L1',
  title: 'Thread YOLO 直接控制面（revision CAS 与同值 no-op）',
  docs: 'PUT /api/ai/runtime/threads/{id}/yolo {expectedRevision,yoloEnabled} => 200 权威 Thread；同值请求在任何 CAS 之前 no-op 成功（过期 revision 不冲突、revision 零触碰）；值变化时 revision 精确 +1，过期 revision => 409 STALE_REVISION；不创建 Command/Entry/Work',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const { snapshot } = await createConfiguredChatThread(ctx, {
      agent: ctx.vars.agent,
      model: modelSelectionOf(ctx),
      title: `e2e-yolo-${cid().slice(0, 8)}`,
    })
    const thread = snapshot.thread
    assert(thread.yoloEnabled === false, JSON.stringify(thread))

    // 变化 + 精确 revision：revision +1，返回权威 Thread。
    const enabled = await setThreadYolo(ctx, thread.threadId, {
      expectedRevision: thread.revision,
      yoloEnabled: true,
    })
    assert(enabled.yoloEnabled === true, JSON.stringify(enabled))
    assert(
      String(Number(enabled.revision)) === String(Number(thread.revision) + 1),
      JSON.stringify({ before: thread.revision, after: enabled.revision }),
    )
    assert(enabled.headEntryId === thread.headEntryId, JSON.stringify(enabled))

    // 同值 no-op 先于 revision CAS：携带过期 expectedRevision 仍 200，revision/head 零触碰。
    const sameValue = await setThreadYolo(ctx, thread.threadId, {
      expectedRevision: '999999999',
      yoloEnabled: true,
    })
    assert(sameValue.yoloEnabled === true, JSON.stringify(sameValue))
    assert(String(sameValue.revision) === String(enabled.revision), JSON.stringify(sameValue))

    // 变化 + 过期 revision：409 STALE_REVISION。
    await expectHttpError(
      () =>
        setThreadYolo(ctx, thread.threadId, {
          expectedRevision: thread.revision,
          yoloEnabled: false,
        }),
      { status: 409, messageIncludes: /revision/i },
    )

    // 关闭并精确 +1；快照反映同一权威值，且全程不产生 queued Command / Entry / Work。
    const disabled = await setThreadYolo(ctx, thread.threadId, {
      expectedRevision: enabled.revision,
      yoloEnabled: false,
    })
    assert(disabled.yoloEnabled === false, JSON.stringify(disabled))
    assert(
      String(Number(disabled.revision)) === String(Number(enabled.revision) + 1),
      JSON.stringify({ before: enabled.revision, after: disabled.revision }),
    )
    const fresh = await getThreadSnapshot(ctx, thread.threadId)
    assert(fresh.thread.yoloEnabled === false, JSON.stringify(fresh.thread))
    assert(String(fresh.thread.revision) === String(disabled.revision), JSON.stringify(fresh.thread))
    assert(fresh.queuedCommands.length === 0, JSON.stringify(fresh.queuedCommands))
    // entries 仍只有 ROOT（创建即快照）；setThreadYolo 绝不追加 Entry。
    assert(fresh.entries.length === 1, JSON.stringify(fresh.entries))
  },
})

registerCase({
  id: 'thread_snapshot.unknown_thread_404',
  level: 'L1',
  title: '未知 Thread snapshot 404',
  docs: 'GET /api/ai/runtime/threads/{canonical unknown UUID}/snapshot => 404 unknown thread',
  async run(ctx) {
    const unknownThreadId = '00000000-0000-0000-0000-000000000999'
    await expectHttpError(
      () => ctx.call('GET', `/api/ai/runtime/threads/${unknownThreadId}/snapshot`),
      { status: 404, messageIncludes: new RegExp(`thread ${unknownThreadId} does not exist`) },
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

/** 免费控制面 case 只需一个当前 Catalog 可解析的 Agent/Model，不依赖完整 E2E seed。 */
async function resolveAnyCatalogTarget(ctx) {
  const agents = pageResults(
    (await ctx.call('GET', '/api/ai/catalog/agents?pageNumber=1&pageSize=50')).json,
  )
  const models = pageResults(
    (await ctx.call('GET', '/api/ai/catalog/models?pageNumber=1&pageSize=50')).json,
  )
  for (const agent of agents) {
    const separator = String(agent.model || '').indexOf('/')
    if (separator <= 0) continue
    const providerName = String(agent.model).slice(0, separator)
    const modelName = String(agent.model).slice(separator + 1)
    const model = models.find(
      (candidate) => candidate.providerName === providerName && candidate.name === modelName,
    )
    if (!model) continue
    const variant = agent.variant || model.config?.defaultVariant
    if (!variant) continue
    return {
      agent,
      model: { providerName, modelName, variant },
    }
  }
  throw new Error(
    `no resolvable Agent/Model in current Catalog: ${JSON.stringify({ agents, models })}`,
  )
}
