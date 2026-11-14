import { readFileSync } from 'node:fs'
import path from 'node:path'
import { isDeepStrictEqual } from 'node:util'

import {
  assert,
  assertExactFields,
  expectHttpError,
  httpJson,
  listResults,
  cid,
} from '../lib/http.mjs'
import {
  acceptCommandBatch,
  assertRootYoloEnabled,
  branchSettingsOf,
  chatOwner,
  createChat,
  createNewSession,
  createNewThread,
  getThreadSnapshot,
  listChatSessions,
  listSessionEntries,
  listSessionThreads,
  renameSession,
  renameThread,
  setAgentCommand,
  setExecutionCommand,
  setThreadYolo,
  stopThread,
  threadTarget,
  threadIdOf,
  userMessageCommand,
  waitForDurableMessages,
  waitForQuiescentThread,
} from '../lib/harness.mjs'
import { baseModelConfig, canonicalSeedCatalog, encodePathTail, seedCatalogWithVariants } from '../lib/fixtures.mjs'
import { createCaseAdder, findCase } from '../lib/registry.mjs'
import { REPO_ROOT } from '../../../lib/repo-root.mjs'

export const cases = []
const registerCase = createCaseAdder(cases)
const getCase = (id) => findCase(cases, id)

const PI_MODEL_CATALOG = JSON.parse(
  readFileSync(
    path.join(
      REPO_ROOT,
      'platform/runtime/src/test/resources/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/pi-model-catalog.json',
    ),
    'utf8',
  ),
)
const E2E_MODEL_VARIANTS = JSON.parse(
  readFileSync(
    path.join(
      REPO_ROOT,
      'platform/runtime/src/test/resources/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/e2e-model-variants.json',
    ),
    'utf8',
  ),
)

registerCase({
  id: 'seed.structured_model_config',
  level: 'L1',
  title: 'Model 公开契约与 Pi 默认目录一致',
  docs: 'GET /api/platform/models：按全局逻辑名完整匹配 Pi 0.82.1 快照与公开字段集合；种子持久化形态的 variant 省略 protocolOptionsJson，公开契约返回 canonical 字符串（null/空 ≡ "{}"）',
  async run(ctx) {
    const { json } = await ctx.call('GET', '/api/platform/models')
    const models = listResults(json)
    const actualCatalog = models
      .map((model) => ({
        provider: model.providerName,
        name: model.name,
        modelId: model.modelId,
        description: model.description,
        config: model.config,
      }))
      .sort(compareModel)
    const expectedCatalog = canonicalSeedCatalog(
      seedCatalogWithVariants(PI_MODEL_CATALOG, E2E_MODEL_VARIANTS),
    ).sort(compareModel)
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
      if (model.type === 'MODEL') {
        assertExactFields(
          model,
          ['name', 'type', 'providerName', 'modelId', 'description', 'config', 'version', 'createdAt', 'updatedAt'],
          'ModelDTO(MODEL)',
        )
        assert(model.group === undefined, 'MODEL wire contract must omit group')
        assert(model.providerName && model.name, JSON.stringify(model))
        assert(model.config?.defaultVariant, JSON.stringify(model.config))
        assert(
          Array.isArray(model.config?.variants) && model.config.variants.length > 0,
          JSON.stringify(model.config),
        )
        assert(Number(model.config?.limit?.context || 0) > 0, JSON.stringify(model.config))
      } else if (model.type === 'GROUP') {
        assertExactFields(
          model,
          ['name', 'type', 'description', 'config', 'group', 'version', 'createdAt', 'updatedAt'],
          'ModelDTO(GROUP)',
        )
        assert(model.group && Array.isArray(model.group.members), 'GROUP wire contract must include group with members')
      } else {
        throw new Error(`unknown model kind: ${model.kind}`)
      }
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
  docs: 'seed Agent 以 name/execution 引用；Provider 以 name 标识，八种协议映射及 nullable baseUrl wire 形态正确',
  async run(ctx) {
    const { json: agentsJson } = await ctx.call(
      'GET',
      '/api/platform/agents',
    )
    const agents = listResults(agentsJson)
    assert(agents.length > 0, 'no agents')
    // 1.0.0 没有 Agent 类型：每个 Agent 都是用户 Agent，seed 只要求已配置 builtin execution。
    const agent = agents.find((candidate) => candidate.name === 'default-assistant')
      || agents.find((candidate) => candidate.execution?.driver === 'builtin')
    assert(agent, 'need an Agent with a configured builtin execution')
    assertExactFields(
      agent,
      [
        'name',
        'description',
        'systemPrompt',
        'execution',
        'config',
        'version',
        'createdAt',
        'updatedAt',
      ],
      'AgentDTO',
    )
    assert(agent.name && agent.execution?.driver === 'builtin' && agent.execution.settings?.modelName && agent.config, JSON.stringify(agent))
    ctx.vars.agent = agent

    const { json: providersJson } = await ctx.call(
      'GET',
      '/api/platform/providers',
    )
    const providers = listResults(providersJson)
    const expectedProviderTypes = new Map([
      ['minimax', 'openai_responses'],
      ['openai', 'openai_responses'],
      ['xai', 'openai_responses'],
      ['deepseek', 'openai'],
      ['google', 'google'],
      ['anthropic', 'anthropic'],
      ['zai', 'openai'],
      ['minimax-anthropic', 'anthropic'],
    ])
    for (const [name, providerType] of expectedProviderTypes) {
      const provider = providers.find((candidate) => candidate.name === name)
      assert(provider?.providerType === providerType, JSON.stringify({ name, providerType, provider }))
      const hasBaseUrl = Object.hasOwn(provider, 'baseUrl')
      assert(
        !hasBaseUrl || (typeof provider.baseUrl === 'string' && provider.baseUrl.length > 0),
        `ProviderDTO.baseUrl must be omitted or a non-empty string: ${JSON.stringify(provider)}`,
      )
      assertExactFields(
        provider,
        [
          'name',
          'description',
          'providerType',
          ...(hasBaseUrl ? ['baseUrl'] : []),
          'credential',
          'modelCallTimeoutMillis',
          'modelCallIdleTimeoutMillis',
          'modelHttpRetryStatusCodes',
          'promptCacheRetentions',
          'version',
          'createdAt',
          'updatedAt',
        ],
        'ProviderDTO',
      )
    }
    ctx.vars.provider = providers.find((provider) => provider.name === 'minimax')
  },
})

registerCase({
  id: 'catalog.internal_tools_hidden',
  level: 'L1',
  title: '内部 HOST Tool 不进入 Agent 可选目录',
  docs: 'GET /api/platform/tools 只返回 SELECTABLE Tool；load_skill/task 由 skills/subagents 派生激活，不能直接写入 Agent config.tools；每条目录项精确为 name/description/environmentSupport/provider，provider 精确为 kind/id/name',
  async run(ctx) {
    const tools = (await ctx.call('GET', '/api/platform/tools')).json
    assert(Array.isArray(tools), JSON.stringify(tools))
    const names = tools.map((tool) => String(tool.name))
    assert(!names.includes('load_skill'), `load_skill must be internal: ${JSON.stringify(names)}`)
    assert(!names.includes('task'), `task must be internal: ${JSON.stringify(names)}`)
    for (const tool of tools) {
      assertExactFields(
        tool,
        ['name', 'description', 'environmentSupport', 'provider'],
        'Tool catalog entry',
      )
      assertExactFields(tool.provider, ['kind', 'id', 'name'], 'Tool catalog entry provider')
    }
  },
})

registerCase({
  id: 'thread.new_session_submission_atomic',
  level: 'L1',
  title: 'NEW_SESSION 原子创建返回完整 accepted 快照',
  docs: 'POST /api/harness/command-batches owner={CHAT,id} target=NEW_SESSION{sessionId,threadId,rootSettings,yoloEnabled} => 202 HarnessAcceptedCommandsDTO{session,rootEntry,thread,acceptedCommands,replayed=false}；首 Command sequence=1 已接受 => thread.nextCommandSequence 精确 2、version >= 1；accepted command 的 sequence=1；thread/entry/session/command 标识全为 canonical UUID string；rootEntry 即 head 或其后继（processor 可能已消费）；Chat owner Session 摘要包含新 Session',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-thread-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const sessionId = cid()
    const threadId = cid()
    const accepted = await createNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId,
      threadId,
      rootSettings: branchSettingsOf(ctx.vars.agent, modelSelectionOf(ctx)),
      yoloEnabled: false,
      commands: [userMessageCommand(`materialize ${sessionId.slice(0, 8)}`, cid())],
    })
    assert(accepted.replayed === false, JSON.stringify(accepted))
    assert(String(accepted.session.sessionId) === sessionId, JSON.stringify(accepted.session))
    const thread = accepted.thread
    // 首 Command sequence=1 已接受，nextCommandSequence 必须精确 2；不锁定 status（processor
    // 可能已异步消费），version 至少 1，head 可为 root 或其后继。
    assert(
      String(thread.nextCommandSequence) === '2',
      `nextCommandSequence must be 2 after accepting the first command: ${JSON.stringify(thread)}`,
    )
    assert(
      BigInt(thread.version) >= 1n,
      `version must be at least 1 after accepting the first command: ${JSON.stringify(thread)}`,
    )
    threadIdOf(thread)
    assert(String(thread.threadId) === threadId, JSON.stringify(thread))
    assert(accepted.acceptedCommands.length === 1, JSON.stringify(accepted.acceptedCommands))
    const acceptedCommand = accepted.acceptedCommands[0]
    assert(acceptedCommand.type === 'USER_MESSAGE', JSON.stringify(acceptedCommand))
    assert(String(acceptedCommand.sequence) === '1', JSON.stringify(acceptedCommand))
    for (const hidden of [
      'executionEpoch',
      'environment',
      'environmentName',
      'activeAgentName',
      'modelId',
      'usage',
    ]) {
      assert(!(hidden in thread), `Thread leaked ${hidden}: ${JSON.stringify(thread)}`)
    }

    // Chat owner Session 摘要包含新 Session（归属时间新到旧，newest-first）。
    const sessions = await listChatSessions(ctx, chat.id)
    assert(
      sessions.some((item) => String(item.sessionId) === sessionId),
      'created Session missing from Chat session list',
    )
    assert(sessions[0]?.sessionId === sessionId, `expected newest-first: ${JSON.stringify(sessions)}`)
    // Session 默认名派生自首个非空白用户文本（与本 case 文本一致且 < 40 码点）；Thread name=main。
    assert(
      sessions[0]?.name === `materialize ${sessionId.slice(0, 8)}`,
      JSON.stringify(sessions[0]),
    )
    assert(thread.name === 'main', JSON.stringify(thread))
    // 清理：删除 owner Chat（连带其 Session/Thread/Entry 资源）。
    await ctx.call(
      'DELETE',
      `/api/chat/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
    )
  },
})



registerCase({
  id: 'thread.model_request_debug_draft_settings',
  level: 'L1',
  title: 'Model Request Debug 草稿设置只读投影',
  docs: 'POST /api/harness/threads/{threadId}/model-request-debug 以 UI 草稿 {model, environmentName}（environmentName 可空，null 表示未选 Environment）在真实 snapshot 上只读现算；本 case 以缺失 Agent 的既有 Thread 让规划确定性 PLANNING_FAILED（不调用真实 Provider）：响应回显草稿 model/env，planningError 只回显稳定 error code 且不泄漏缺失 Agent 名，tools/skills/subagents 安全为空；frozenInvocation 与草稿选择相互独立（本 Thread 无活动 Invocation 时显式 null）；缺 model 或非 canonical environmentName => 400，GET => 405；查询不改变 head/version/nextCommandSequence、不入队命令、不启动 Invocation',
  async run(ctx) {
    const target = await resolveAnyCatalogTarget(ctx)
    const missingAgentName = `missing-debug-agent-${cid().slice(0, 8)}`
    const chat = await createChat(ctx, {
      title: `e2e-model-debug-${cid().slice(0, 8)}`,
      agentName: target.agent.name,
      yoloEnabled: false,
    })
    const threadId = cid()
    try {
      await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId: cid(),
        threadId,
        // 缺失 Agent 让 bootstrap turn 在解析 Provider 之前确定性 PLANNING_FAILED，不产生真实模型用量。
        rootSettings: branchSettingsOf({ name: missingAgentName }, target.model),
        yoloEnabled: false,
        commands: [userMessageCommand('model request debug', cid())],
      })
      await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
      const before = await getThreadSnapshot(ctx, threadId)
      const endpoint = `/api/harness/builtin/threads/${encodeURIComponent(threadId)}/model-request-debug`

      const { status, json } = await ctx.call('POST', endpoint, {
        model: target.model,
        environmentName: null,
      })
      assert(status === 200, `model-request-debug status ${status}: ${JSON.stringify(json)}`)
      const debug = json
      assert(debug?.kind === 'NEXT_REQUEST_PREVIEW', JSON.stringify(debug))
      // 草稿选择原样回显：model 是本次请求的草稿，environmentName 显式为 null（required-nullable）。
      assert(isDeepStrictEqual(debug.model, target.model), JSON.stringify(debug.model))
      assert(debug.environmentName === null, JSON.stringify(debug))
      // 缺失 Agent 的确定性拒绝只回显稳定 error code，安全空投影，绝不外泄自由文本（缺失 Agent 名）。
      assert(debug.planningError === 'PLANNING_FAILED', JSON.stringify(debug.planningError))
      assert(
        Array.isArray(debug.tools) && debug.tools.length === 0
          && Array.isArray(debug.skills) && debug.skills.length === 0
          && Array.isArray(debug.subagents) && debug.subagents.length === 0
          && debug.systemInstruction === ''
          && debug.cacheControl === null,
        `rejected planning must project a safe empty view: ${JSON.stringify(debug)}`,
      )
      assert(
        !JSON.stringify(debug).includes(missingAgentName),
        `debug projection must not leak the missing Agent name: ${JSON.stringify(debug)}`,
      )
      // 冻结事实与草稿选择相互独立：本 Thread 无活动 Invocation，因此显式缺席。
      assert(debug.frozenInvocation === null, JSON.stringify(debug.frozenInvocation))

      // 请求形状边界：缺 model 与非法 canonical environmentName 都在写入前 400；该端点是只读 POST，GET 405。
      await expectHttpError(() => ctx.call('POST', endpoint, { environmentName: null }), {
        status: 400,
        code: 'validation',
      })
      await expectHttpError(
        () => ctx.call('POST', endpoint, { model: target.model, environmentName: 'bad/name' }),
        { status: 400, code: 'validation' },
      )
      await expectHttpError(() => ctx.call('GET', endpoint), { status: 405, code: 'validation' })

      // 只读：head/version/nextCommandSequence 不变，不入队命令，也不启动 Invocation。
      const after = await getThreadSnapshot(ctx, threadId)
      assert(
        String(after.thread.headEntryId) === String(before.thread.headEntryId)
          && String(after.thread.version) === String(before.thread.version)
          && String(after.thread.nextCommandSequence) === String(before.thread.nextCommandSequence)
          && after.queuedCommands.length === 0
          && after.activeAssistantOutput === null,
        JSON.stringify({ before: before.thread, after: after.thread }),
      )
    } finally {
      await ctx.call(
        'DELETE',
        `/api/chat/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
      )
    }
  },
})

registerCase({
  id: 'thread.branch_settings_projection',
  level: 'L1',
  title: 'NEW_SESSION rootSettings 完整投影到 Thread 快照',
  docs: 'agentName/model/environmentName 与 Thread YOLO 开关（yoloEnabled/rootThreadId）原样持久化并投影；Chat 默认值独立',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-settings-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const requested = branchSettingsOf(ctx.vars.agent, modelSelectionOf(ctx))
    const accepted = await createNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId: cid(),
      threadId: cid(),
      rootSettings: requested,
      yoloEnabled: true,
      commands: [userMessageCommand(`settings ${cid().slice(0, 8)}`, cid())],
    })
    assertRootYoloEnabled(accepted.thread, true)
    assert(
      isDeepStrictEqual(accepted.thread.branchSettings, requested),
      JSON.stringify({ expected: requested, actual: accepted.thread.branchSettings }),
    )
    // Thread branchSettings 独立于 Chat 默认值：Chat 仍保存自身 defaults。
    assert(chat.yoloEnabled === false, JSON.stringify(chat))
    // reread 同一 Thread：投影稳定。
    const reread = await getThreadSnapshot(ctx, accepted.thread.threadId)
    assert(
      isDeepStrictEqual(reread.thread.branchSettings, requested),
      JSON.stringify(reread.thread.branchSettings),
    )
  },
})

registerCase({
  id: 'thread.user_message_strict_wire',
  level: 'L1',
  title: 'USER_MESSAGE 严格结构化 contents 与 exact replay',
  docs: 'USER_MESSAGE 只接受一个非空有序 contents 列表（TEXT/ATTACHMENT/RESOURCE）；本免费 case 覆盖 TEXT 正向与 text/content shorthand、role、未知字段、空 contents、IMAGE/AUDIO/VIDEO、非 canonical uploadId、TEXT 携带 imageTier 与非法 imageTier 名等非法 shape；RESOURCE 正向与 Session ownership 由 chat.attachment_upload_contract 覆盖。202 payload 是 canonical AgentMessage；同 batch exact replay 返回既有命令（sequence/payload 稳定）',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    // 隔离 Thread：本 case 独占队列状态，不依赖其他 case 的 cursor。
    const chat = await createChat(ctx, {
      title: `e2e-user-wire-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const sessionId = cid()
    const threadId = cid()
    await createNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId,
      threadId,
      rootSettings: branchSettingsOf(
        { name: `e2e-user-wire-missing-${cid().slice(0, 8)}` },
        modelSelectionOf(ctx),
      ),
      yoloEnabled: false,
      commands: [userMessageCommand(`wire materialize ${cid().slice(0, 8)}`, cid())],
    })
    const singleIdempotencyKey = cid()
    const structuredIdempotencyKey = cid()
    const secondIdempotencyKey = cid()
    const commandsOf = () => [
      {
        type: 'USER_MESSAGE',
        idempotencyKey: singleIdempotencyKey,
        contents: [{ type: 'TEXT', text: 'strict wire probe' }],
      },
      {
        type: 'USER_MESSAGE',
        idempotencyKey: structuredIdempotencyKey,
        contents: [
          { type: 'TEXT', text: 'animate this' },
          { type: 'TEXT', text: 'and this' },
        ],
      },
      userMessageCommand('second probe', secondIdempotencyKey),
    ]
    // 产品 HTTP 面：一个 batch 只能恰一条 USER_MESSAGE，三条 USER_MESSAGE 必须拆批。
    // 为保留原 case 对严格 wire + exact replay 的完整覆盖，这里逐条入队后再整体 replay。
    // 每次全新 accept 前等待连续两次一致的 quiescent cursor；单次 IDLE snapshot 可能落在
    // processor 已移除队列、尚未提交 Thread 尾部状态的瞬时窗口。
    const batchFor = (commands) => ({
      owner: chatOwner(chat.id),
      target: threadTarget({ threadId }),
      commands,
    })
    const acceptedList = []
    await waitForQuiescentThread(ctx, threadId, {
      timeoutMs: 60_000,
      intervalMs: 100,
    })
    for (const single of commandsOf()) {
      const acceptedOnce = await acceptCommandBatch(ctx, batchFor([single]))
      acceptedList.push(acceptedOnce.acceptedCommands[0])
      // Exact replay：纯 USER_MESSAGE 批次无 cursor，按 idempotencyKey 与 requestHash 重放。
      const replayAccepted = await acceptCommandBatch(ctx, batchFor([single]))
      assert(replayAccepted.replayed === true, JSON.stringify(replayAccepted))
      assert(
        String(replayAccepted.acceptedCommands[0].sequence)
          === String(acceptedList.at(-1).sequence)
          && JSON.stringify(replayAccepted.acceptedCommands[0].payload)
            === JSON.stringify(acceptedList.at(-1).payload),
        `replay must return the existing command: ${JSON.stringify({
          original: acceptedList.at(-1),
          replay: replayAccepted.acceptedCommands[0],
        })}`,
      )
      await waitForQuiescentThread(ctx, threadId, {
        timeoutMs: 60_000,
        intervalMs: 100,
      })
    }
    const commandsDto = acceptedList
    assert(commandsDto.length === 3, JSON.stringify(commandsDto))
    const command = commandsDto[0]
    assert(String(command.idempotencyKey) === singleIdempotencyKey, JSON.stringify(command))
    assert(commandsDto.every((item) => item.type === 'USER_MESSAGE' && item.state === 'QUEUED'), JSON.stringify(commandsDto))
    assert(commandsDto.every((item) => String(item.threadId) === threadId), JSON.stringify(commandsDto))
    assert(commandsDto.every((item) => /^[1-9]\d*$/.test(String(item.sequence))), JSON.stringify(commandsDto))
    const payload = commandsDto[0].payload
    assert(
      isDeepStrictEqual(payload, { message: { role: 'USER', contents: [{ type: 'text', text: 'strict wire probe' }] } }),
      `USER_MESSAGE payload must be the canonical AgentMessage: ${JSON.stringify(payload)}`,
    )
    const structuredPayload = commandsDto[1].payload
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
    const secondPayload = commandsDto[2].payload
    assert(
      isDeepStrictEqual(secondPayload, {
        message: { role: 'USER', contents: [{ type: 'text', text: 'second probe' }] },
      }),
      `contents TEXT payload must be canonical: ${JSON.stringify(secondPayload)}`,
    )
    const expectInvalidUserCommand = (command, options = { status: 400, code: 'validation' }) =>
      expectHttpError(
        () =>
          acceptCommandBatch(ctx, {
            owner: chatOwner(chat.id),
            target: threadTarget({ threadId }),
            commands: [command],
          }),
        options,
      )

    // 文本 shorthand（text / content）已从 USER_MESSAGE wire 移除。
    // 未知字段在 Jackson 反序列化层即拒绝（detail 指出路径为 "is not allowed"）。
    await expectInvalidUserCommand({ type: 'USER_MESSAGE', idempotencyKey: cid(), text: 'x' })
    await expectInvalidUserCommand(
      { type: 'USER_MESSAGE', idempotencyKey: cid(), content: 'x' },
      { status: 400, code: 'validation' },
    )
    // 多余字段（role 等）与缺少 contents 字段均确定性 400。
    await expectInvalidUserCommand(
      {
        type: 'USER_MESSAGE',
        idempotencyKey: cid(),
        contents: [{ type: 'TEXT', text: 'x' }],
        role: 'USER',
      },
      { status: 400, code: 'validation' },
    )
    await expectInvalidUserCommand(
      { type: 'USER_MESSAGE', idempotencyKey: cid() },
      { status: 400, code: 'validation', messageIncludes: /contents/i },
    )
    // IMAGE/AUDIO/VIDEO 内容类型已从 wire 契约移除。
    for (const [type, mediaType, source] of [
      ['IMAGE', 'image/png', 'https://example.test/image.png'],
      ['AUDIO', 'audio/mpeg', 'https://example.test/audio.mp3'],
      ['VIDEO', 'video/mp4', 'https://example.test/video.mp4'],
    ]) {
      await expectInvalidUserCommand({
        type: 'USER_MESSAGE',
        idempotencyKey: cid(),
        contents: [{ type, mediaType, source }],
      })
    }
    // ATTACHMENT uploadId 必须是 canonical UUID，且必须指向 READY upload。
    await expectInvalidUserCommand(
      {
        type: 'USER_MESSAGE',
        idempotencyKey: cid(),
        contents: [{ type: 'ATTACHMENT', uploadId: 'not-a-uuid' }],
      },
      { status: 400, code: 'validation', messageIncludes: /uploadId/i },
    )
    await expectInvalidUserCommand({
      type: 'USER_MESSAGE',
      idempotencyKey: cid(),
      contents: [{ type: 'ATTACHMENT', uploadId: cid() }],
    })
    // imageTier 只允许出现在 ATTACHMENT/RESOURCE 上，且必须是受支持的档位名。
    await expectInvalidUserCommand(
      {
        type: 'USER_MESSAGE',
        idempotencyKey: cid(),
        contents: [{ type: 'TEXT', text: 'x', imageTier: '720P' }],
      },
      { status: 400, code: 'validation' },
    )
    await expectInvalidUserCommand({
      type: 'USER_MESSAGE',
      idempotencyKey: cid(),
      contents: [{ type: 'ATTACHMENT', uploadId: cid(), imageTier: '4K' }],
    })
    await expectInvalidUserCommand({
      type: 'USER_MESSAGE',
      idempotencyKey: cid(),
      contents: [{ type: 'RESOURCE', blobId: cid(), name: 'photo.png', imageTier: 'P720' }],
    })
  },
})

registerCase({
  id: 'thread.product_http_rejects_custom_message',
  level: 'L1',
  title: '产品 HTTP 面拒绝 CUSTOM_MESSAGE 且命令 shape 严格',
  docs: 'CUSTOM_MESSAGE（SYSTEM/USER 均不可）在产品 HTTP command-batches 面确定性 400；同一 batch 的 USER_MESSAGE 必须恰一条且在末尾，故两条 USER_MESSAGE、非末尾 USER_MESSAGE、乱序 SET、未知类型 => 400；同 batch 非法 shape 不推进 cursor',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-custom-wire-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const sessionId = cid()
    const threadId = cid()
    await createNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId,
      threadId,
      rootSettings: branchSettingsOf(
        { name: `e2e-custom-wire-missing-${cid().slice(0, 8)}` },
        modelSelectionOf(ctx),
      ),
      yoloEnabled: false,
      commands: [userMessageCommand(`custom wire materialize ${cid().slice(0, 8)}`, cid())],
    })
    await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
    const idle = await getThreadSnapshot(ctx, threadId)
    const thread = idle.thread
    const cursor = () => ({
      owner: chatOwner(chat.id),
      target: threadTarget({
        threadId,
        expectedHeadEntryId: thread.headEntryId,
        expectedNextCommandSequence: thread.nextCommandSequence,
      }),
    })

    // CUSTOM_MESSAGE 产品 HTTP 面被拒绝（SYSTEM/USER 均不可）。未知 type 在 Jackson
    // 反序列化层即失败，detail 指出命令路径（不携带命令名）。
    for (const role of ['SYSTEM', 'USER']) {
      await expectHttpError(
        () =>
          acceptCommandBatch(ctx, {
            ...cursor(),
            commands: [
              { type: 'CUSTOM_MESSAGE', role, idempotencyKey: cid(), content: 'x' },
            ],
          }),
        { status: 400, code: 'validation' },
      )
    }
    // 两条 USER_MESSAGE 同一 batch => 400；HTTP 面只允许恰一条且必须在末尾。
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          ...cursor(),
          commands: [
            userMessageCommand('first', cid()),
            userMessageCommand('second', cid()),
          ],
        }),
      { status: 400, code: 'validation', messageIncludes: /terminal user-like command|USER_MESSAGE/i },
    )
    // USER_MESSAGE 不在末尾 => 400。
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          ...cursor(),
          commands: [
            userMessageCommand('first', cid()),
            setAgentCommand('x', cid()),
          ],
        }),
      { status: 400, code: 'validation', messageIncludes: /order|final|USER_MESSAGE/i },
    )
    // 未知命令类型 => 400。
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          ...cursor(),
          commands: [{ type: 'RENAME_THREAD', idempotencyKey: cid() }],
        }),
      { status: 400, code: 'validation', messageIncludes: /type/i },
    )
    // 非法 shape 不推进 cursor。
    const after = await getThreadSnapshot(ctx, threadId)
    assert(
      String(after.thread.headEntryId) === String(thread.headEntryId)
        && String(after.thread.nextCommandSequence) === String(thread.nextCommandSequence)
        && after.queuedCommands.length === 0,
      `invalid batches must not mutate the Thread: ${JSON.stringify({ before: thread, after: after.thread })}`,
    )
  },
})

registerCase({
  id: 'thread.command_idempotent_replay',
  level: 'L1',
  title: 'idempotencyKey 幂等 replay 与部分重放拒绝',
  docs: '整批同 idempotencyKey 重放 => replayed=true 且返回既有命令（无副作用）；仅部分存在 => 409 PARTIAL_COMMAND_REPLAY',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-replay-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const sessionId = cid()
    const threadId = cid()
    await createNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId,
      threadId,
      rootSettings: branchSettingsOf(
        { name: `e2e-replay-missing-${cid().slice(0, 8)}` },
        modelSelectionOf(ctx),
      ),
      yoloEnabled: false,
      commands: [userMessageCommand(`replay materialize ${cid().slice(0, 8)}`, cid())],
    })
    await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
    const idle = await getThreadSnapshot(ctx, threadId)
    const thread = idle.thread
    const idempotencyKey = cid()
    const batch = () => ({
      owner: chatOwner(chat.id),
      target: threadTarget({
        threadId,
      }),
      commands: [userMessageCommand('idempotent replay', idempotencyKey)],
    })
    const first = await acceptCommandBatch(ctx, batch())
    assert(first.replayed === false, JSON.stringify(first))
    const replay = await acceptCommandBatch(ctx, batch())
    assert(replay.replayed === true, JSON.stringify(replay))
    assert(
      String(replay.acceptedCommands[0].sequence) === String(first.acceptedCommands[0].sequence)
        && JSON.stringify(replay.acceptedCommands[0].payload) === JSON.stringify(first.acceptedCommands[0].payload),
      `replay must return the existing command: ${JSON.stringify({ first, replay })}`,
    )
    // 部分重放：HTTP 面 shape 限制一个 batch 恰一条 USER_MESSAGE，因此用
    // SET_AGENT(新) + USER_MESSAGE(已存在) 构造 partial：SET 是新 id、USER 已存在 =>
    // present=1/2 => 409 PARTIAL_COMMAND_REPLAY（Runtime 检测先于命令 admission）。
    // 混合批次按契约必须携带当前游标。
    const freshSnapshot = await getThreadSnapshot(ctx, threadId)
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          owner: chatOwner(chat.id),
          target: threadTarget({
            threadId,
            expectedHeadEntryId: freshSnapshot.thread.headEntryId,
            expectedNextCommandSequence: freshSnapshot.thread.nextCommandSequence,
          }),
          commands: [
            setAgentCommand('x', cid()),
            userMessageCommand('replayed again', idempotencyKey),
          ],
        }),
      { status: 409, code: 'conflict', reason: 'PARTIAL_COMMAND_REPLAY' },
    )
  },
})

registerCase({
  id: 'thread.message_cursorless_and_settings_stale_cas',
  level: 'L1',
  title: '纯 USER_MESSAGE 无需 cursor，设置批次仍拒绝 stale CAS',
  docs: '单条纯 USER_MESSAGE 无 cursor 按当前邮箱尾部入队（即使提交过期 cursor 也不拒绝）；随后 SET_AGENT 混合批次以旧 head/sequence 提交 => 409 STALE_COMMAND_CURSOR，且被拒绝批次不改变 Thread 的 nextCommandSequence/version/head。',
  async run(ctx) {
    const { agent, model } = await resolveAnyCatalogTarget(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-cursorless-${cid().slice(0, 8)}`,
      agentName: agent.name,
      yoloEnabled: false,
    })
    const sessionId = cid()
    const threadId = cid()
    await createNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId,
      threadId,
      rootSettings: branchSettingsOf(agent, model),
      yoloEnabled: false,
      commands: [userMessageCommand(`cursorless materialize ${cid().slice(0, 8)}`, cid())],
    })
    await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
    const before = await getThreadSnapshot(ctx, threadId)

    // 纯消息不携带 cursor：按服务端当前尾部入队。
    const accepted = await acceptCommandBatch(ctx, {
      owner: chatOwner(chat.id),
      target: threadTarget({ threadId }),
      commands: [userMessageCommand('cursorless append', cid())],
    })
    assert(accepted.replayed === false, JSON.stringify(accepted))

    // 等待消息消费并进入稳定状态，确保后续拒绝对比不受异步执行干扰。
    await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
    const afterMessage = await getThreadSnapshot(ctx, threadId)
    assert(
      BigInt(afterMessage.thread.nextCommandSequence) > BigInt(before.thread.nextCommandSequence),
      `pure message must append at the mailbox tail: ${JSON.stringify({
        before: before.thread,
        after: afterMessage.thread,
      })}`,
    )

    // 设置混合批次仍依赖 cursor：旧 cursor 必须 409，且零写入。
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          owner: chatOwner(chat.id),
          target: threadTarget({
            threadId,
            expectedHeadEntryId: before.thread.headEntryId,
            expectedNextCommandSequence: before.thread.nextCommandSequence,
          }),
          commands: [
            setAgentCommand('coder', cid()),
            userMessageCommand('stale settings', cid()),
          ],
        }),
      { status: 409, code: 'conflict', reason: 'STALE_COMMAND_CURSOR' },
    )
    const afterReject = await getThreadSnapshot(ctx, threadId)
    assert(
      String(afterReject.thread.nextCommandSequence) === String(afterMessage.thread.nextCommandSequence)
        && String(afterReject.thread.version) === String(afterMessage.thread.version)
        && String(afterReject.thread.headEntryId) === String(afterMessage.thread.headEntryId),
      `stale settings batch must not mutate the Thread: ${JSON.stringify({
        afterMessage: afterMessage.thread,
        afterReject: afterReject.thread,
      })}`,
    )
  },
})

registerCase({
  id: 'thread.new_thread_same_session',
  level: 'L1',
  title: 'NEW_THREAD 用显式规范化分支名在合法边界创建新 Thread',
  docs: 'NEW_THREAD target 在既有 Session 的合法 fork 边界（ROOT 或已闭合 TURN_END）开新 Thread（不复制 Entry）：分支显示名必须显式给出，创建前按 Names 规则把空白折叠为单空格并去首尾（≤256 码点），规范化结果进入创建请求身份并等于 accepted.thread.name；sessionId 不变、accepted command sequence=1、rootEntry 复用 Session ROOT；quiescent 后分支 Thread snapshot path 必须包含 startEntry 与分支 USER；原 Thread head/version/nextCommandSequence/name 不变；NEW_THREAD 非法 startEntryId（不存在 404）由本 case 覆盖，跨 Session 由 thread.new_thread_cross_session_rejected 覆盖',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-rebind-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const sessionId = cid()
    const threadId = cid()
    const accepted = await createNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId,
      threadId,
      rootSettings: branchSettingsOf(
        { name: `e2e-rebind-missing-${cid().slice(0, 8)}` },
        modelSelectionOf(ctx),
      ),
      yoloEnabled: false,
      commands: [userMessageCommand(`rebind materialize ${cid().slice(0, 8)}`, cid())],
    })
    assert(accepted.thread.name === 'main', JSON.stringify(accepted.thread))
    await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
    const idle = await getThreadSnapshot(ctx, threadId)
    const thread = idle.thread
    assert(thread.name === 'main', JSON.stringify(thread))
    // quiescent 后 head 是已闭合 TURN_END：这是除 ROOT 之外唯一合法的 fork 边界。
    assert(
      (idle.entries || []).some(
        (entry) =>
          String(entry.entryId) === String(thread.headEntryId)
          && String(entry.entryType || '').toUpperCase() === 'TURN_END',
      ),
      `quiescent head must be a closed TURN_END: ${JSON.stringify(idle.entries)}`,
    )
    const startEntryId = String(thread.headEntryId)
    const mainBefore = {
      headEntryId: String(thread.headEntryId),
      version: String(thread.version),
      nextCommandSequence: String(thread.nextCommandSequence),
      name: String(thread.name),
    }
    const branchThreadId = cid()
    const branchUserText = `branch on head ${cid().slice(0, 8)}`
    // 显式给出含多余空白的分支名：服务端与 helper 都必须规范化为折叠后的单空格名称。
    const rawThreadName = `  branch   ${cid().slice(0, 8)}   name  `
    const normalizedThreadName = rawThreadName.replace(/\s+/gu, ' ').trim()
    const branched = await createNewThread(ctx, {
      owner: chatOwner(chat.id),
      sessionId,
      startEntryId,
      threadId: branchThreadId,
      threadName: rawThreadName,
      // NEW_THREAD 独立 fork 新执行根：初始 YOLO 开关沿用原根当前生效值。
      yoloEnabled: thread.yoloEnabled,
      commands: [userMessageCommand(branchUserText, cid())],
    })
    // NEW_THREAD accepted 后 processor 可能已消费分支命令：不锁定 response head=startEntry；
    // 只锁定 session/thread 归属、显式分支名与 accepted command sequence=1。
    assert(
      String(branched.thread.sessionId) === String(thread.sessionId),
      JSON.stringify(branched.thread),
    )
    assert(String(branched.thread.threadId) === branchThreadId, JSON.stringify(branched.thread))
    assert(
      branched.thread.name === normalizedThreadName,
      `branch name must be the normalized explicit threadName: ${JSON.stringify({
        rawThreadName,
        normalizedThreadName,
        actual: branched.thread.name,
      })}`,
    )
    assert(branched.rootEntry.entryId === accepted.rootEntry.entryId, JSON.stringify(branched.rootEntry))
    // 原 Thread 不受影响（head/version/nextCommandSequence/name 逐字段不变）。
    const after = await getThreadSnapshot(ctx, threadId)
    assert(
      String(after.thread.headEntryId) === mainBefore.headEntryId
        && String(after.thread.version) === mainBefore.version
        && String(after.thread.nextCommandSequence) === mainBefore.nextCommandSequence
        && String(after.thread.name) === mainBefore.name,
      JSON.stringify({ before: mainBefore, after: after.thread }),
    )
    // quiescent 后分支 Thread snapshot path 必须包含 startEntry 与分支 USER。
    const branchedFinal = await waitForQuiescentThread(ctx, branchThreadId, {
      timeoutMs: 60_000,
      intervalMs: 100,
    })
    assert(branchedFinal.status === 'IDLE', JSON.stringify(branchedFinal))
    const branchedSnapshot = await getThreadSnapshot(ctx, branchThreadId)
    const branchedEntries = branchedSnapshot.entries || []
    assert(
      branchedEntries.some((entry) => String(entry.entryId) === String(startEntryId)),
      `branch path must include startEntry ${startEntryId}: ${JSON.stringify(branchedEntries)}`,
    )
    assert(
      branchedEntries.some((entry) => {
        if (String(entry.entryType || '').toUpperCase() !== 'MESSAGE') return false
        const message = entry.payload.message
        return (
          message?.role === 'USER'
          && (message.contents || []).some(
            (content) =>
              content?.type === 'text' && String(content.text || '').includes(branchUserText),
          )
        )
      }),
      `branch path must include the branch USER message: ${JSON.stringify(branchedEntries)}`,
    )
    // NEW_THREAD 分支 fork 在共享前缀边界之后追加 FORK 事实节点（不复制前缀）：
    // head 路径必须包含该节点，其 parent 是切点 startEntry，payload 只陈述 BRANCH + 切点来源。
    const forkEntry = branchedEntries.find(
      (entry) => String(entry.entryType || '').toUpperCase() === 'FORK',
    )
    assert(forkEntry, `branch path must include a FORK entry: ${JSON.stringify(branchedEntries)}`)
    assert(
      String(forkEntry.parentEntryId) === startEntryId,
      `FORK entry parent must be the fork cut ${startEntryId}: ${JSON.stringify(forkEntry)}`,
    )
    const forkPayload = forkEntry.payload
    assert(
      forkPayload.mode === 'BRANCH'
        && String(forkPayload.sourceEntryId) === startEntryId
        && forkPayload.sourceThreadId === null,
      `FORK payload must state the branch cut fact: ${JSON.stringify(forkPayload)}`,
    )

    // 非边界 Entry 不得作为 fork 起点：TURN_START / 消息 / ASSISTANT_ERROR 都必须 400，
    // 且不产生任何写入（预分配 threadId 404、原 Session entries 与 head 完全不变）。
    const nonBoundary = (idle.entries || []).filter((entry) => {
      const type = String(entry.entryType || '').toUpperCase()
      return type === 'TURN_START' || type === 'MESSAGE' || type === 'ASSISTANT_ERROR'
    })
    assert(
      nonBoundary.length > 0,
      `expected mid-turn entries to be rejected as fork boundaries: ${JSON.stringify(idle.entries)}`,
    )
    const entriesBeforeReject = await listSessionEntries(ctx, sessionId)
    const rejectedName = `must-not-exist ${cid().slice(0, 8)}`
    for (const entry of nonBoundary) {
      const rejectedThreadId = cid()
      await expectHttpError(
        () =>
          createNewThread(ctx, {
            owner: chatOwner(chat.id),
            sessionId,
            startEntryId: String(entry.entryId),
            threadId: rejectedThreadId,
            threadName: rejectedName,
            yoloEnabled: false,
            commands: [userMessageCommand(`illegal fork ${cid().slice(0, 8)}`, cid())],
          }),
        { status: 400, code: 'validation' },
      )
      // 拒绝必须是零写入：预分配 Thread 未创建、Session Entry Tree 与源 Thread head 一字不动。
      await expectHttpError(() => ctx.call('GET', `/api/harness/threads/${rejectedThreadId}`), {
        status: 404,
        code: 'not_found',
      })
    }
    assert(
      JSON.stringify(await listSessionEntries(ctx, sessionId)) === JSON.stringify(entriesBeforeReject),
      'rejected fork attempts must not write any Entry',
    )
    const afterRejects = await getThreadSnapshot(ctx, threadId)
    assert(
      String(afterRejects.thread.headEntryId) === mainBefore.headEntryId
        && String(afterRejects.thread.version) === String(after.thread.version)
        && String(afterRejects.thread.nextCommandSequence) === String(after.thread.nextCommandSequence),
      JSON.stringify({ before: mainBefore, after: afterRejects.thread }),
    )
    assert(
      !(await listSessionThreads(ctx, sessionId)).some((item) => String(item.name) === rejectedName),
      'rejected forks must not create a Thread',
    )

    // 不存在的 startEntryId => 404（Runtime 找不到 Entry）。
    await expectHttpError(
      () =>
        createNewThread(ctx, {
          owner: chatOwner(chat.id),
          sessionId,
          startEntryId: cid(),
          threadId: cid(),
          threadName: `unknown entry ${cid().slice(0, 8)}`,
          yoloEnabled: false,
          commands: [userMessageCommand('unknown entry', cid())],
        }),
      { status: 404, code: 'not_found' },
    )
    // 清理：删除 owner Chat（连带其 Session/Thread/Entry 资源）。
    await ctx.call(
      'DELETE',
      `/api/chat/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
    )
  },
})

registerCase({
  id: 'thread.session_entry_tree',
  level: 'L1',
  title: '完整 Session Entry Tree 保留非当前历史分支',
  docs: 'GET /api/harness/sessions/{sessionId}/entries 返回 Session 全部 immutable Entries；NEW_THREAD 创建新 Thread 形成分叉后，snapshot 仅含当前 root-to-head，而 entries 同时保留原分支与当前分支及稳定 parent 关系；listSessionThreads 反映两条 Thread',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const suffix = cid().slice(0, 8)
    const trunkMessage = `entry tree trunk ${suffix}`
    const originalMessage = `entry tree original ${suffix}`
    const alternateMessage = `entry tree alternate ${suffix}`
    const chat = await createChat(ctx, {
      title: `e2e-entry-tree-${suffix}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const sessionId = cid()
    const threadId = cid()
    await createNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId,
      threadId,
      rootSettings: branchSettingsOf(
        { name: `e2e-entry-tree-missing-${suffix}` },
        modelSelectionOf(ctx),
      ),
      yoloEnabled: false,
      commands: [userMessageCommand(trunkMessage, cid())],
    })
    await waitForDurableMessages(ctx, threadId, [trunkMessage])
    const trunk = await getThreadSnapshot(ctx, threadId)
    const branchPointEntryId = trunk.thread.headEntryId

    // 原分支：继续原 Thread。
    await acceptCommandBatch(ctx, {
      owner: chatOwner(chat.id),
      target: threadTarget({
        threadId,
      }),
      commands: [userMessageCommand(originalMessage, cid())],
    })
    await waitForDurableMessages(ctx, threadId, [trunkMessage, originalMessage])
    const original = await getThreadSnapshot(ctx, threadId)

    // 分支：NEW_THREAD 在 branchPoint（已闭合 TURN_END）下开新 Thread，写 alternate。
    const alternateThreadId = cid()
    await createNewThread(ctx, {
      owner: chatOwner(chat.id),
      sessionId,
      startEntryId: branchPointEntryId,
      threadId: alternateThreadId,
      threadName: `alternate ${suffix}`,
      yoloEnabled: false,
      commands: [userMessageCommand(alternateMessage, cid())],
    })
    const active = await waitForDurableMessages(ctx, alternateThreadId, [
      trunkMessage,
      alternateMessage,
    ])

    const entries = await listSessionEntries(ctx, sessionId)
    const entryText = (entry) => {
      if (String(entry.entryType || '').toUpperCase() !== 'MESSAGE') return ''
      const message = entry.payload.message
      return (message?.contents || [])
        .filter((content) => content?.type === 'text')
        .map((content) => content.text)
        .join('\n')
    }
    const originalUser = entries.find((entry) => entryText(entry) === originalMessage)
    const alternateUser = entries.find((entry) => entryText(entry) === alternateMessage)
    assert(originalUser && alternateUser, `full Entry Tree lost a branch: ${JSON.stringify(entries)}`)
    assert(
      !active.snapshot.entries.some((entry) => entryText(entry) === originalMessage)
        && active.snapshot.entries.some((entry) => entryText(entry) === alternateMessage),
      `snapshot must remain current root-to-head only: ${JSON.stringify(active.snapshot.entries)}`,
    )
    const byId = new Map(entries.map((entry) => [entry.entryId, entry]))
    const originalTurnStart = byId.get(originalUser.parentEntryId)
    const alternateTurnStart = byId.get(alternateUser.parentEntryId)
    // 分支 fork 在切点后追加 FORK 事实节点：分支 TURN_START 挂在 FORK 上，FORK 再挂回切点；
    // 原分支继续直接从切点展开。两条历史分支不共享同一 TURN_START。
    const alternateFork = alternateTurnStart ? byId.get(alternateTurnStart.parentEntryId) : null
    const alternateForkPayload = alternateFork?.payload ?? null
    assert(
      originalTurnStart?.entryType === 'TURN_START'
        && alternateTurnStart?.entryType === 'TURN_START'
        && originalTurnStart.entryId !== alternateTurnStart.entryId
        && originalTurnStart.parentEntryId === branchPointEntryId
        && alternateFork?.entryType === 'FORK'
        && String(alternateFork.parentEntryId) === String(branchPointEntryId)
        && alternateForkPayload?.mode === 'BRANCH'
        && String(alternateForkPayload.sourceEntryId) === String(branchPointEntryId),
      `historical branches do not share the expected parent: ${JSON.stringify({
        branchPointEntryId,
        originalTurnStart,
        alternateTurnStart,
        alternateFork,
      })}`,
    )
    assert(
      JSON.stringify(await listSessionEntries(ctx, sessionId)) === JSON.stringify(entries),
      'full Entry Tree ordering must be stable across reads',
    )
    // Session Thread 摘要反映两条 Thread。
    const threads = await listSessionThreads(ctx, sessionId)
    const threadIds = threads.map((item) => String(item.threadId))
    assert(
      threadIds.includes(String(threadId)) && threadIds.includes(String(alternateThreadId)),
      `Session Thread list must contain both Threads: ${JSON.stringify(threadIds)}`,
    )
    assert(
      String(original.thread.threadId) === String(threadId),
      `original Thread unchanged: ${JSON.stringify(original.thread)}`,
    )
    // 清理：删除 owner Chat（连带其 Session/Thread/Entry 资源）。
    await ctx.call(
      'DELETE',
      `/api/chat/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
    )
  },
})

registerCase({
  id: 'thread.new_thread_cross_session_rejected',
  level: 'L1',
  title: '跨 Session NEW_THREAD 被拒绝',
  docs: 'NEW_THREAD target 使用另一 Session 的 entry id => 400（start entry 不在目标 Session）；两个原 Thread projection/session entries/thread list 均不变；预分配 rejectedThreadId 的 snapshot 404（未创建）',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-cross-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const firstSessionId = cid()
    const firstThreadId = cid()
    await createNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId: firstSessionId,
      threadId: firstThreadId,
      rootSettings: branchSettingsOf(
        { name: `e2e-cross-a-missing-${cid().slice(0, 8)}` },
        modelSelectionOf(ctx),
      ),
      yoloEnabled: false,
      commands: [userMessageCommand(`cross a ${cid().slice(0, 8)}`, cid())],
    })
    const secondSessionId = cid()
    const secondThreadId = cid()
    await createNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId: secondSessionId,
      threadId: secondThreadId,
      rootSettings: branchSettingsOf(
        { name: `e2e-cross-b-missing-${cid().slice(0, 8)}` },
        modelSelectionOf(ctx),
      ),
      yoloEnabled: false,
      commands: [userMessageCommand(`cross b ${cid().slice(0, 8)}`, cid())],
    })
    assert(firstSessionId !== secondSessionId, 'distinct Sessions must not collide')
    // 先让两个 bootstrap Thread quiescent，再捕获 before snapshots/entries/thread lists。
    const stableFirst = await waitForQuiescentThread(ctx, firstThreadId, {
      timeoutMs: 60_000,
      intervalMs: 100,
    })
    const stableSecond = await waitForQuiescentThread(ctx, secondThreadId, {
      timeoutMs: 60_000,
      intervalMs: 100,
    })
    const beforeFirstEntries = await listSessionEntries(ctx, firstSessionId)
    const beforeSecondEntries = await listSessionEntries(ctx, secondSessionId)
    const beforeFirstThreads = await listSessionThreads(ctx, firstSessionId)
    const beforeSecondThreads = await listSessionThreads(ctx, secondSessionId)
    const secondRootEntryId = beforeSecondEntries.find(
      (entry) => String(entry.entryType || '').toUpperCase() === 'ROOT',
    )?.entryId
    assert(secondRootEntryId, `second Session must have a ROOT entry: ${JSON.stringify(beforeSecondEntries)}`)

    // 预分配 rejectedThreadId：用第二 Session 的 ROOT entry 在第一 Session 下提交错误 NEW_THREAD => 400。
    const rejectedThreadId = cid()
    await expectHttpError(
      () =>
        createNewThread(ctx, {
          owner: chatOwner(chat.id),
          sessionId: firstSessionId,
          startEntryId: secondRootEntryId,
          threadId: rejectedThreadId,
          threadName: `cross session ${cid().slice(0, 8)}`,
          yoloEnabled: false,
          commands: [userMessageCommand('cross session entry', cid())],
        }),
      { status: 400, code: 'validation', messageIncludes: /session|entry/i },
    )
    // 两个原 Thread projection/entries/thread list 均不变。
    const afterFirstSnapshot = await getThreadSnapshot(ctx, firstThreadId)
    const afterSecondSnapshot = await getThreadSnapshot(ctx, secondThreadId)
    assert(
      String(afterFirstSnapshot.thread.headEntryId) === String(stableFirst.headEntryId)
        && String(afterFirstSnapshot.thread.version) === String(stableFirst.version)
        && String(afterFirstSnapshot.thread.nextCommandSequence) === String(stableFirst.nextCommandSequence),
      JSON.stringify({ before: stableFirst, after: afterFirstSnapshot.thread }),
    )
    assert(
      String(afterSecondSnapshot.thread.headEntryId) === String(stableSecond.headEntryId)
        && String(afterSecondSnapshot.thread.version) === String(stableSecond.version)
        && String(afterSecondSnapshot.thread.nextCommandSequence) === String(stableSecond.nextCommandSequence),
      JSON.stringify({ before: stableSecond, after: afterSecondSnapshot.thread }),
    )
    assert(
      JSON.stringify(await listSessionEntries(ctx, firstSessionId)) === JSON.stringify(beforeFirstEntries),
      'first Session entries must not change',
    )
    assert(
      JSON.stringify(await listSessionEntries(ctx, secondSessionId)) === JSON.stringify(beforeSecondEntries),
      'second Session entries must not change',
    )
    assert(
      JSON.stringify(await listSessionThreads(ctx, firstSessionId)) === JSON.stringify(beforeFirstThreads),
      'first Session thread list must not change',
    )
    assert(
      JSON.stringify(await listSessionThreads(ctx, secondSessionId)) === JSON.stringify(beforeSecondThreads),
      'second Session thread list must not change',
    )
    // rejectedThreadId 未创建：snapshot 404。
    await expectHttpError(
      () => ctx.call('GET', `/api/harness/threads/${rejectedThreadId}`),
      { status: 404, code: 'not_found' },
    )
    // 清理：删除 owner Chat（连带两个 Session/Thread/Entry 资源）。
    await ctx.call(
      'DELETE',
      `/api/chat/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
    )
  },
})

registerCase({
  id: 'thread.stop_idle_boundary',
  level: 'L1',
  title: 'IDLE stop 写入持久 STOP barrier 且同 stopRequestId exact replay',
  docs: 'POST /stop body={stopRequestId,expectedVersion}；本线程无 open Turn 时写入完整 STOP barrier Turn（TURN_START(STOP) → ASSISTANT_ERROR(CANCELLED) → TURN_END(STOPPED, continueModel=false, USER_STOP, closeRequestId)）=> stop status=STOPPED、thread.executionControl=STOPPED（Thread 活动三态仍为 IDLE，STOP 不覆盖 status）、stoppedThreads[] 含目标 Thread 回执（threadId/stopRequestId 为请求值、stoppedTurnEndEntryId=该 TURN_END 且成为新 head、cancelledCommandCount=0、cancelledInputs=[]）、version 恰好 +1；同 stopRequestId + 原 expectedVersion 再次调用 => status=REPLAYED、同回执 stoppedTurnEndEntryId、version/head/Entry 集合不变（不写第二条 barrier）；未使用过的 stopRequestId 配 stale version => 409。真实 live Turn 的 STOPPED/REPLAYED 由 L2 real.stop_partial_continue 覆盖',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-stop-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const sessionId = cid()
    const threadId = cid()
    await createNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId,
      threadId,
      rootSettings: branchSettingsOf(
        { name: `e2e-stop-missing-${cid().slice(0, 8)}` },
        modelSelectionOf(ctx),
      ),
      yoloEnabled: false,
      commands: [userMessageCommand(`stop materialize ${cid().slice(0, 8)}`, cid())],
    })
    const thread = await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
    // 前置事实：确定性失败的 Agent 让首个 Turn 已闭合，Thread 处于无本线程 open Turn 的空闲态。
    assert(
      thread.status === 'IDLE',
      `expected quiescent idle Thread: ${JSON.stringify(thread)}`,
    )
    const stopRequestId = cid()
    const first = await stopThread(ctx, threadId, {
      stopRequestId,
      expectedVersion: thread.version,
    })
    // IDLE stop 不是 no-op：它写入 durable STOP barrier Turn，并恰好递增一次 version。
    assert(first.status === 'STOPPED', JSON.stringify(first))
    assert(first.thread.executionControl === 'STOPPED', JSON.stringify(first.thread))
    const firstReceipt = first.stoppedThreads.find((receipt) => receipt.threadId === threadId)
    assert(firstReceipt, `target receipt missing: ${JSON.stringify(first)}`)
    assert(firstReceipt.stopRequestId === stopRequestId, JSON.stringify(firstReceipt))
    assert(firstReceipt.stoppedTurnEndEntryId != null, JSON.stringify(firstReceipt))
    assert(firstReceipt.cancelledCommandCount === 0, JSON.stringify(firstReceipt))
    assert(firstReceipt.cancelledInputs.length === 0, JSON.stringify(firstReceipt))
    assert(
      BigInt(first.thread.version) === BigInt(thread.version) + 1n,
      `idle stop must bump version by one: ${JSON.stringify({ thread, first })}`,
    )
    assert(
      String(first.thread.headEntryId) === String(firstReceipt.stoppedTurnEndEntryId),
      `stop barrier TURN_END must become the new head: ${JSON.stringify(first)}`,
    )

    // barrier Turn 结构：TURN_START(STOP) → ASSISTANT_ERROR(CANCELLED) → TURN_END(STOPPED)。
    const entries = await listSessionEntries(ctx, sessionId)
    const barrierEnd = entries.find(
      (entry) => String(entry.entryId) === String(firstReceipt.stoppedTurnEndEntryId),
    )
    assert(
      barrierEnd && String(barrierEnd.entryType || '').toUpperCase() === 'TURN_END',
      `missing stop barrier TURN_END: ${JSON.stringify(entries)}`,
    )
    const barrierEndPayload = barrierEnd.payload
    assert(
      barrierEndPayload.outcome === 'STOPPED'
        && barrierEndPayload.reason === 'USER_STOP'
        && barrierEndPayload.continueModel === false
        && String(barrierEndPayload.closeRequestId) === String(stopRequestId),
      `unexpected stop barrier TURN_END: ${JSON.stringify(barrierEndPayload)}`,
    )
    const barrierStart = entries.find(
      (entry) => String(entry.entryId) === String(barrierEndPayload.turnStartEntryId),
    )
    assert(
      barrierStart && String(barrierStart.entryType || '').toUpperCase() === 'TURN_START',
      `missing stop barrier TURN_START: ${JSON.stringify(entries)}`,
    )
    const barrierStartPayload = barrierStart.payload
    assert(
      barrierStartPayload.reason === 'STOP'
        && String(barrierStartPayload.ownerThreadId) === String(threadId),
      `unexpected stop barrier TURN_START: ${JSON.stringify(barrierStartPayload)}`,
    )
    const barrierCancel = entries.find(
      (entry) => String(entry.entryId) === String(barrierEnd.parentEntryId),
    )
    assert(
      barrierCancel
        && String(barrierCancel.entryType || '').toUpperCase() === 'ASSISTANT_ERROR'
        && String(barrierCancel.parentEntryId) === String(barrierStart.entryId)
        && barrierCancel.payload.error?.code === 'CANCELLED',
      `stop barrier must carry one CANCELLED ASSISTANT_ERROR: ${JSON.stringify(entries)}`,
    )

    // 同 stopRequestId + 原 expectedVersion 精确 replay：同一 stopped TURN_END，不写第二条 barrier。
    const again = await stopThread(ctx, threadId, {
      stopRequestId,
      expectedVersion: thread.version,
    })
    assert(again.status === 'REPLAYED', JSON.stringify(again))
    const againReceipt = again.stoppedThreads.find((receipt) => receipt.threadId === threadId)
    assert(againReceipt, `replay target receipt missing: ${JSON.stringify(again)}`)
    assert(
      String(againReceipt.stoppedTurnEndEntryId) === String(firstReceipt.stoppedTurnEndEntryId),
      `replay must identify the same stopped TURN_END: ${JSON.stringify({ first, again })}`,
    )
    assert(againReceipt.cancelledCommandCount === 0, JSON.stringify(againReceipt))
    assert(
      String(again.thread.version) === String(first.thread.version)
        && String(again.thread.headEntryId) === String(first.thread.headEntryId),
      `replay must not mutate the Thread: ${JSON.stringify({ first, again })}`,
    )
    assert(
      JSON.stringify(await listSessionEntries(ctx, sessionId)) === JSON.stringify(entries),
      'replay must not append a second stop barrier',
    )

    // 未使用过的 stopRequestId 走 version CAS：stale version 必须 409。
    await expectHttpError(
      () =>
        stopThread(ctx, threadId, {
          stopRequestId: cid(),
          expectedVersion: '999999999',
        }),
      { status: 409, code: 'conflict', reason: 'STALE_VERSION' },
    )
  },
})

registerCase({
  id: 'thread.new_session_default_names',
  level: 'L1',
  title: 'NEW_SESSION 派生 Session 默认名与 root main Thread 名',
  docs: 'NEW_SESSION 创建请求无 name 输入：Session.name 由服务端从初始批次末尾类用户消息的首个非空白文本派生（Unicode 空白折叠为单空格、截前 40 个 Unicode 码点、无省略号），accepted/owner 摘要/fresh 查询一致；root Thread.name 恒为 main 且在 snapshot/Thread 摘要中必填非空；Session/Thread UUID identity 保持不变；不依赖真实 Provider（缺失 Agent 确定性 PLANNING_FAILED）',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-default-names-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const sessionId = cid()
    const threadId = cid()
    const suffix = cid().slice(0, 8)
    // 同时覆盖空白折叠与 40 码点截断（无省略号）。
    const firstUserText = `默认命名探测 ${suffix}  折叠空白\n行内续写 ${'长'.repeat(60)}`
    const expectedSessionName = deriveSessionName(firstUserText)
    try {
      const accepted = await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId,
        threadId,
        rootSettings: branchSettingsOf(
          { name: `e2e-default-names-missing-${suffix}` },
          modelSelectionOf(ctx),
        ),
        yoloEnabled: false,
        commands: [userMessageCommand(firstUserText, cid())],
      })
      assert(String(accepted.session.sessionId) === sessionId, JSON.stringify(accepted.session))
      assert(accepted.session.name === expectedSessionName, JSON.stringify(accepted.session))
      assert(accepted.thread.name === 'main', JSON.stringify(accepted.thread))
      assert(String(accepted.thread.threadId) === threadId, JSON.stringify(accepted.thread))
      // 等 turn 收敛后重新 fresh 查询：owner Session 摘要、Thread 摘要与 snapshot 一致派生。
      await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
      const sessions = await listChatSessions(ctx, chat.id)
      const summary = sessions.find((item) => String(item.sessionId) === sessionId)
      assert(summary, JSON.stringify(sessions))
      assert(summary.name === expectedSessionName, JSON.stringify(summary))
      assert(String(summary.sessionId) === sessionId, JSON.stringify(summary))
      const snapshot = await getThreadSnapshot(ctx, threadId)
      assert(snapshot.thread.name === 'main', JSON.stringify(snapshot.thread))
      const threads = await listSessionThreads(ctx, sessionId)
      const threadSummary = threads.find((item) => String(item.threadId) === threadId)
      assert(threadSummary, JSON.stringify(threads))
      assert(threadSummary.name === 'main', JSON.stringify(threadSummary))
    } finally {
      await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 }).catch(() => {})
      await ctx.call(
        'DELETE',
        `/api/chat/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
      )
    }
  },
})

registerCase({
  id: 'thread.session_thread_rename_persistence',
  level: 'L1',
  title: 'Session/Thread 重命名持久化：UUID 不变、Thread version 精确 +1、规范同名 no-op',
  docs: 'PUT /api/harness/sessions/{id}/name {name} 与 PUT /api/harness/threads/{id}/name {name} 返回权威 DTO：Session（sessionId/name/createdAt）UUID/createdAt 不变、owner 摘要 fresh 持久；Thread 本体 UUID/head/session 不变、实际改名 version 精确 +1，snapshot 可观测的 queuedCommands/activeAssistantOutput/toolInvocations 与 entries 均不变、Thread 摘要 fresh 持久；规范化同名（空白变体）no-op：返回 canonical name 且 version 零触碰；不依赖真实 Provider（缺失 Agent 确定性 PLANNING_FAILED）',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-rename-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const sessionId = cid()
    const threadId = cid()
    const suffix = cid().slice(0, 8)
    try {
      await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId,
        threadId,
        rootSettings: branchSettingsOf(
          { name: `e2e-rename-missing-${suffix}` },
          modelSelectionOf(ctx),
        ),
        yoloEnabled: false,
        commands: [userMessageCommand(`rename materialize ${suffix}`, cid())],
      })
      await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
      const beforeSnapshot = await getThreadSnapshot(ctx, threadId)
      const beforeThread = beforeSnapshot.thread
      const beforeSessions = await listChatSessions(ctx, chat.id)
      const beforeSummary = beforeSessions.find((item) => String(item.sessionId) === sessionId)
      assert(beforeSummary, JSON.stringify(beforeSessions))

      // Session 重命名：UUID/createdAt 不变，owner 摘要 fresh 持久。
      const sessionName = `renamed session ${suffix}`
      const renamedSession = await renameSession(ctx, sessionId, sessionName)
      assert(renamedSession.name === sessionName, JSON.stringify(renamedSession))
      assert(String(renamedSession.sessionId) === sessionId, JSON.stringify(renamedSession))
      assert(
        String(renamedSession.createdAt) === String(beforeSummary.createdAt),
        JSON.stringify({ before: beforeSummary.createdAt, after: renamedSession.createdAt }),
      )
      const freshSessions = await listChatSessions(ctx, chat.id)
      const freshSummary = freshSessions.find((item) => String(item.sessionId) === sessionId)
      assert(freshSummary, JSON.stringify(freshSessions))
      assert(freshSummary.name === sessionName, JSON.stringify(freshSummary))
      assert(
        String(freshSummary.createdAt) === String(beforeSummary.createdAt),
        JSON.stringify(freshSummary),
      )

      // Thread 实际改名：version 精确 +1，UUID/head/session 不变；fresh snapshot/summary 持久。
      const threadName = `renamed thread ${suffix}`
      const renamedThread = await renameThread(ctx, threadId, threadName)
      assert(renamedThread.name === threadName, JSON.stringify(renamedThread))
      assert(String(renamedThread.threadId) === threadId, JSON.stringify(renamedThread))
      assert(String(renamedThread.sessionId) === String(beforeThread.sessionId), JSON.stringify(renamedThread))
      assert(String(renamedThread.headEntryId) === String(beforeThread.headEntryId), JSON.stringify(renamedThread))
      assert(
        BigInt(renamedThread.version) === BigInt(beforeThread.version) + 1n,
        JSON.stringify({ before: beforeThread.version, after: renamedThread.version }),
      )
      const afterSnapshot = await getThreadSnapshot(ctx, threadId)
      assert(afterSnapshot.thread.name === threadName, JSON.stringify(afterSnapshot.thread))
      assert(String(afterSnapshot.thread.version) === String(renamedThread.version), JSON.stringify(afterSnapshot.thread))
      // 重命名不得产生 Command/Entry/Invocation/Work：queued 清空、无模型调用、无 tool invocation、
      // entries 与 rename 前逐项一致（这些是 snapshot 直接可观测的 Work 指标）。
      assert(afterSnapshot.queuedCommands.length === 0, JSON.stringify(afterSnapshot.queuedCommands))
      assert(afterSnapshot.activeAssistantOutput === null, JSON.stringify(afterSnapshot.activeAssistantOutput))
      assert(afterSnapshot.toolInvocations.length === 0, JSON.stringify(afterSnapshot.toolInvocations))
      assert(
        JSON.stringify(afterSnapshot.entries) === JSON.stringify(beforeSnapshot.entries),
        'rename must not mutate entries',
      )
      const freshThreads = await listSessionThreads(ctx, sessionId)
      const threadSummary = freshThreads.find((item) => String(item.threadId) === threadId)
      assert(threadSummary, JSON.stringify(freshThreads))
      assert(threadSummary.name === threadName, JSON.stringify(threadSummary))

      // 规范化同名 no-op：空白变体折叠后与当前名相同 => 200 canonical name、version 零触碰。
      const noop = await renameThread(ctx, threadId, `  ${threadName}  `)
      assert(noop.name === threadName, JSON.stringify(noop))
      assert(String(noop.version) === String(renamedThread.version), JSON.stringify(noop))
      const noopSnapshot = await getThreadSnapshot(ctx, threadId)
      assert(String(noopSnapshot.thread.version) === String(renamedThread.version), JSON.stringify(noopSnapshot.thread))
    } finally {
      await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 }).catch(() => {})
      await ctx.call(
        'DELETE',
        `/api/chat/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
      )
    }
  },
})

registerCase({
  id: 'thread.branch_settings_diff_commands',
  level: 'L1',
  title: 'SET_* 命令一个原子 batch 精确 wire 并消费投影',
  docs: '前端固定顺序 SET_AGENT,SET_EXECUTION,SET_ENVIRONMENT,USER_MESSAGE 一个 batch；SET_ENVIRONMENT 省略 environmentName 清除原环境；SET_AGENT 使用 canonical 但不存在的名称，使 Resolver 在调用 Provider 前确定性 PLANNING_FAILED；等 quiescent 后 Thread branchSettings 精确投影、queue 清空、USER entry 与 AssistantError 可见，最终 TURN_END(FAILED, continueModel=false)；未知 command 字段、未知 command type 与非法 environmentName => 400',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    // 隔离 Thread：SET_* 命令消费要求 pre-state 干净（无 queued USER/CUSTOM、IDLE、无 Work）。
    const chat = await createChat(ctx, {
      title: `e2e-set-all-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const threadId = cid()
    await createNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId: cid(),
      threadId,
      rootSettings: {
        ...branchSettingsOf(
          { name: `e2e-set-all-missing-${cid().slice(0, 8)}` },
          modelSelectionOf(ctx),
        ),
        environmentName: 'e2e-previous-environment',
      },
      yoloEnabled: false,
      commands: [userMessageCommand(`set-all materialize ${cid().slice(0, 8)}`, cid())],
    })
    const thread = await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
    assert(thread.branchSettings.environmentName === 'e2e-previous-environment', JSON.stringify(thread))
    const marker = `SET-ALL-${cid()}`
    const modelSelection = modelSelectionOf(ctx)
    const missingAgentName = `missing-agent-${cid().slice(0, 8)}`
    const commands = [
      setAgentCommand(missingAgentName, cid()),
      setExecutionCommand(
        {
          modelName: modelSelection.modelName,
          variant: modelSelection.variant,
        },
        cid(),
      ),
      { type: 'SET_ENVIRONMENT', idempotencyKey: cid() },
      userMessageCommand(`${marker} 消费 SET 后的第一条消息。`, cid()),
    ]
    const accepted = await acceptCommandBatch(ctx, {
      owner: chatOwner(chat.id),
      target: threadTarget({
        threadId,
        expectedHeadEntryId: thread.headEntryId,
        expectedNextCommandSequence: thread.nextCommandSequence,
      }),
      commands,
    })
    assert(accepted.replayed === false, JSON.stringify(accepted))
    const dto = accepted.acceptedCommands
    assert(
      dto.map((command) => command.type).join(',') ===
        'SET_AGENT,SET_EXECUTION,SET_ENVIRONMENT,USER_MESSAGE',
      JSON.stringify(dto),
    )
    for (let i = 1; i < dto.length; i++) {
      assert(
        BigInt(dto[i].sequence) === BigInt(dto[i - 1].sequence) + 1n,
        `sequences must be contiguous: ${JSON.stringify(dto)}`,
      )
    }

    // 等 quiescent：SET_* 被消费并投影到 base settings；USER_MESSAGE 触发一个 turn。
    const finalThread = await waitForQuiescentThread(ctx, threadId, {
      timeoutMs: 60_000,
      intervalMs: 250,
    })
    assert(finalThread.status === 'IDLE', JSON.stringify(finalThread))
    const finalSnapshot = await getThreadSnapshot(ctx, threadId)
    const expectedSettings = {
      agentName: missingAgentName,
      execution: {
        driver: 'builtin',
        settings: {
          modelName: modelSelection.modelName,
          variant: modelSelection.variant,
        },
      },
      environmentName: null,
    }
    assert(
      isDeepStrictEqual(finalSnapshot.thread.branchSettings, expectedSettings),
      JSON.stringify({
        expected: expectedSettings,
        actual: finalSnapshot.thread.branchSettings,
      }),
    )
    // yolo 是直接控制面：batch 不含 SET_YOLO，Thread 保持创建时的 YOLO 开关与执行根（直接更新见 thread.yolo_direct_update）。
    assert(
      finalSnapshot.thread.yoloEnabled === thread.yoloEnabled
        && finalSnapshot.thread.rootThreadId === thread.rootThreadId,
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
      const message = entry.payload.message
      return message?.role === 'USER' && JSON.stringify(message).includes(marker)
    })
    assert(userEntry, `USER entry missing: ${JSON.stringify(finalSnapshot.entries)}`)
    const planningError = finalSnapshot.entries.find((entry) => {
      if (String(entry.entryType || '').toUpperCase() !== 'ASSISTANT_ERROR') return false
      const error = entry.payload.error
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
    const lastTurnEnd = turnEndEntries.at(-1).payload
    assert(
      lastTurnEnd.outcome === 'FAILED' && lastTurnEnd.continueModel === false,
      `expected the final TURN_END to converge: ${JSON.stringify(lastTurnEnd)}`,
    )

    // 不相关字段与未知字段都必须 400；400 不推进 cursor，可复用 quiescent 后最新 cursor。
    const fresh = await getThreadSnapshot(ctx, threadId)
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          owner: chatOwner(chat.id),
          target: threadTarget({
            threadId,
            expectedHeadEntryId: fresh.thread.headEntryId,
            expectedNextCommandSequence: fresh.thread.nextCommandSequence,
          }),
          commands: [{ type: 'RENAME_THREAD', idempotencyKey: cid() }],
        }),
      { status: 400, code: 'validation', messageIncludes: /type/i },
    )
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          owner: chatOwner(chat.id),
          target: threadTarget({
            threadId,
            expectedHeadEntryId: fresh.thread.headEntryId,
            expectedNextCommandSequence: fresh.thread.nextCommandSequence,
          }),
          commands: [
            {
              type: 'SET_AGENT',
              idempotencyKey: cid(),
              agentName: 'x',
              unexpected: true,
            },
          ],
        }),
      { status: 400, code: 'validation' },
    )
    // 真正未知的 command type 由严格 mapper 拒绝：sealed union 的 detail 文本不是稳定契约，只锁定状态与 code。
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          owner: chatOwner(chat.id),
          target: threadTarget({
            threadId,
            expectedHeadEntryId: fresh.thread.headEntryId,
            expectedNextCommandSequence: fresh.thread.nextCommandSequence,
          }),
          commands: [{ type: 'UNKNOWN_COMMAND', idempotencyKey: cid() }],
        }),
      { status: 400, code: 'validation' },
    )
    // SET_ENVIRONMENT 携带非法 canonical name（含 '/'）必须 400 拒绝。
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          owner: chatOwner(chat.id),
          target: threadTarget({
            threadId,
            expectedHeadEntryId: fresh.thread.headEntryId,
            expectedNextCommandSequence: fresh.thread.nextCommandSequence,
          }),
          commands: [{ type: 'SET_ENVIRONMENT', idempotencyKey: cid(), environmentName: 'bad/name' }],
        }),
      { status: 400, code: 'validation', messageIncludes: /environmentName/i },
    )
    // 其他 command type 不允许携带 environmentName 字段：sealed variant 由严格 mapper 拒绝，detail 文本不是稳定契约。
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          owner: chatOwner(chat.id),
          target: threadTarget({
            threadId,
            expectedHeadEntryId: fresh.thread.headEntryId,
            expectedNextCommandSequence: fresh.thread.nextCommandSequence,
          }),
          commands: [
            {
              type: 'SET_AGENT',
              idempotencyKey: cid(),
              agentName: 'x',
              environmentName: 'local',
            },
          ],
        }),
      { status: 400, code: 'validation' },
    )
    // 其他 discriminator 的未知字段也必须被严格 mapper 拒绝。
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          owner: chatOwner(chat.id),
          target: threadTarget({
            threadId,
            expectedHeadEntryId: fresh.thread.headEntryId,
            expectedNextCommandSequence: fresh.thread.nextCommandSequence,
          }),
          commands: [
            {
              type: 'SET_EXECUTION',
              idempotencyKey: cid(),
              execution: {
                driver: 'builtin',
                settings: {
                  modelName: modelSelection.modelName,
                  variant: modelSelection.variant,
                },
              },
              unknownField: null,
            },
          ],
        }),
      { status: 400, code: 'validation' },
    )
    // 严格 wire：未预期的字段必须被拒绝。
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          owner: chatOwner(chat.id),
          target: threadTarget({
            threadId,
            expectedHeadEntryId: fresh.thread.headEntryId,
            expectedNextCommandSequence: fresh.thread.nextCommandSequence,
          }),
          commands: [
            {
              type: 'SET_AGENT',
              idempotencyKey: cid(),
              agentName: 'x',
              unexpected: { value: '.' },
            },
          ],
        }),
      { status: 400, code: 'validation' },
    )
  },
})

registerCase({
  id: 'thread.yolo_direct_update',
  level: 'L1',
  title: 'Thread YOLO 直接控制面（单字段幂等与同值 no-op）',
  docs: 'PUT /api/harness/threads/{id}/yolo {yoloEnabled} => 200 权威 Thread；同值请求 no-op 且 version 零触碰；值变化时 version 精确 +1，不与完整 Thread version 做 CAS；不创建 Command/Entry/Work',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-yolo-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const threadId = cid()
    await createNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId: cid(),
      threadId,
      rootSettings: branchSettingsOf(ctx.vars.agent, modelSelectionOf(ctx)),
      yoloEnabled: false,
      commands: [userMessageCommand(`yolo materialize ${cid().slice(0, 8)}`, cid())],
    })
    const thread = await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
    assertRootYoloEnabled(thread, false)

    // 变化 + 精确 version：version +1，返回权威 Thread。
    const enabled = await setThreadYolo(ctx, threadId, { yoloEnabled: true })
    assertRootYoloEnabled(enabled, true)
    assert(
      BigInt(enabled.version) === BigInt(thread.version) + 1n,
      JSON.stringify({ before: thread.version, after: enabled.version }),
    )
    assert(enabled.headEntryId === thread.headEntryId, JSON.stringify(enabled))

    // 同值 no-op：不读取请求 version，version/head 零触碰。
    const sameValue = await setThreadYolo(ctx, threadId, { yoloEnabled: true })
    assertRootYoloEnabled(sameValue, true)
    assert(String(sameValue.version) === String(enabled.version), JSON.stringify(sameValue))

    // 关闭并精确 +1；快照反映同一权威值，且全程不产生 queued Command / Entry / Work。
    const disabled = await setThreadYolo(ctx, threadId, { yoloEnabled: false })
    assertRootYoloEnabled(disabled, false)
    assert(
      BigInt(disabled.version) === BigInt(enabled.version) + 1n,
      JSON.stringify({ before: enabled.version, after: disabled.version }),
    )
    const fresh = await getThreadSnapshot(ctx, threadId)
    assertRootYoloEnabled(fresh.thread, false)
    assert(String(fresh.thread.version) === String(disabled.version), JSON.stringify(fresh.thread))
    assert(fresh.queuedCommands.length === 0, JSON.stringify(fresh.queuedCommands))
    // setThreadYolo 绝不追加/修改 Entry：快照 entries 与 YOLO 开关前后一致。
    // （创建批中的命令已被消费，entries 含 ROOT + turn 链；此处只证明 YOLO 零副作用。）
    const beforeYoloEntries = fresh.entries
    const afterYoloSnapshot = await getThreadSnapshot(ctx, threadId)
    assert(
      JSON.stringify(afterYoloSnapshot.entries) === JSON.stringify(beforeYoloEntries),
      `YOLO must not mutate entries: ${JSON.stringify({
        before: beforeYoloEntries,
        after: afterYoloSnapshot.entries,
      })}`,
    )
    // 清理：删除 owner Chat（连带其 Session/Thread/Entry 资源）。
    await ctx.call(
      'DELETE',
      `/api/chat/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
    )
  },
})

registerCase({
  id: 'thread_tree.query_contract',
  level: 'L1',
  title: 'Agent 关系树最小只读投影',
  docs: 'GET /api/harness/threads/{id}/tree：节点精确投影 threadId/kind/parentThreadId/name/agentName/execution/status/executionControl/turnCount/toolCallCount/outcome/updatedAt；真实根 kind=USER 与 parentThreadId 可空，未结束 outcome 显式可空；状态、回合与工具数来源于当前 head，查询不推进版本；updatedAt 为只读的 Thread 权威更新时刻且与 Thread 快照一致；缺失 Agent 确定性失败，无真实 Provider 调用',
  async run(ctx) {
    const target = await resolveAnyCatalogTarget(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-tree-${cid().slice(0, 8)}`,
      agentName: target.agent.name,
      yoloEnabled: false,
    })
    const threadId = cid()
    try {
      await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId: cid(),
        threadId,
        rootSettings: branchSettingsOf({ name: `missing-tree-agent-${cid()}` }, target.model),
        yoloEnabled: false,
        commands: [userMessageCommand('关系树投影', cid())],
      })
      await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
      const before = await getThreadSnapshot(ctx, threadId)
      const { json } = await ctx.call('GET', `/api/harness/threads/${threadId}/tree`)
      const nodes = json
      assert(Array.isArray(nodes) && nodes.length === 1, JSON.stringify(nodes))
      const root = nodes[0]
      assertExactFields(
        root,
        ['threadId', 'kind', 'parentThreadId', 'name', 'agentName', 'execution', 'status', 'executionControl', 'turnCount', 'toolCallCount', 'outcome', 'updatedAt'],
        'HarnessThreadTreeNodeDTO',
      )
      assert(
        root.threadId === threadId && root.kind === 'USER' && root.parentThreadId === null,
        JSON.stringify(root),
      )
      assert(root.status === 'IDLE' && root.executionControl === 'RUNNABLE', JSON.stringify(root))
      assert(root.outcome === 'FAILED' && root.turnCount === 1 && root.toolCallCount === 0, JSON.stringify(root))
      assert(isDeepStrictEqual(root.execution, before.thread.branchSettings.execution), JSON.stringify(root))
      // updatedAt 是 Thread.updatedAt 的只读投影：非空有限的 epoch 毫秒，且与权威 Thread 快照精确相同。
      assert(
        typeof root.updatedAt === 'number'
          && Number.isFinite(root.updatedAt)
          && root.updatedAt >= 0
          && root.updatedAt === before.thread.updatedAt,
        JSON.stringify({ root: root.updatedAt, thread: before.thread.updatedAt }),
      )
      const after = await getThreadSnapshot(ctx, threadId)
      assert(after.thread.version === before.thread.version, 'tree query must not change version')
      assert(after.thread.headEntryId === before.thread.headEntryId, 'tree query must not change head')
      assert(after.thread.updatedAt === before.thread.updatedAt, 'tree query must not change updatedAt')
    } finally {
      await ctx.call(
        'DELETE',
        `/api/chat/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
      )
    }
  },
})

registerCase({
  id: 'thread_tree.invalid_and_unknown_thread',
  level: 'L1',
  title: 'Agent 关系树 UUID 与缺失节点校验',
  docs: 'GET /api/harness/threads/{id}/tree：非 canonical UUID 为 400，未知 Thread 为 404',
  async run(ctx) {
    await expectHttpError(
      () => ctx.call('GET', '/api/harness/threads/not-a-uuid/tree'),
      { status: 400, code: 'validation' },
    )
    await expectHttpError(
      () => ctx.call('GET', '/api/harness/threads/00000000-0000-0000-0000-000000000999/tree'),
      { status: 404, code: 'not_found' },
    )
  },
})

registerCase({
  id: 'thread_snapshot.unknown_thread_404',
  level: 'L1',
  title: '未知 Thread snapshot 404',
  docs: 'GET /api/harness/threads/{canonical unknown UUID} => 404 unknown thread',
  async run(ctx) {
    const unknownThreadId = '00000000-0000-0000-0000-000000000999'
    await expectHttpError(
      () => ctx.call('GET', `/api/harness/threads/${unknownThreadId}`),
      { status: 404, code: 'not_found', messageIncludes: new RegExp(`thread ${unknownThreadId} does not exist`) },
    )
  },
})

registerCase({
  id: 'thread_snapshot.incremental_read',
  level: 'L1',
  title: 'Thread 快照 afterEntryId 增量读取与边界回退',
  docs: 'GET /api/harness/threads/{id}?afterEntryId：非法 UUID 返回 400；ROOT 游标只返回其后的 Entry 且 baseEntryId=ROOT；cursor=head 时返回空 delta 且 baseEntryId=head；非祖先游标回退全量快照且 baseEntryId=null；无真实 Provider 调用',
  async run(ctx) {
    const target = await resolveAnyCatalogTarget(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-incr-${cid().slice(0, 8)}`,
      agentName: target.agent.name,
      yoloEnabled: false,
    })
    const threadId = cid()
    try {
      await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId: cid(),
        threadId,
        rootSettings: branchSettingsOf({ name: `missing-incr-agent-${cid()}` }, target.model),
        yoloEnabled: false,
        commands: [userMessageCommand('增量快照验证', cid())],
      })
      await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
      const full = await getThreadSnapshot(ctx, threadId)
      const headId = full.thread.headEntryId
      assert(headId, 'quiescent thread must have a head entry')

      // 1. 非 canonical afterEntryId -> 400
      await expectHttpError(
        () => ctx.call('GET', `/api/harness/threads/${threadId}?afterEntryId=not-a-valid-uuid`),
        { status: 400, code: 'validation' },
      )

      // 2. ROOT 是 head 的祖先 -> 只返回 ROOT 之后的 Entry，顺序与完整历史一致
      const rootId = full.entries[0].entryId
      assert(full.entries.length > 1, 'quiescent thread must have entries after ROOT')
      const { json: afterRootJson } = await ctx.call('GET', `/api/harness/threads/${threadId}?afterEntryId=${rootId}`)
      const afterRootData = afterRootJson
      assert(afterRootData.baseEntryId === rootId, 'ancestor cursor must be echoed as baseEntryId')
      assert(
        isDeepStrictEqual(
          afterRootData.entries.map((entry) => entry.entryId),
          full.entries.slice(1).map((entry) => entry.entryId),
        ),
        'ancestor cursor must return only the entries after it',
      )

      // 3. cursor=head -> 空 delta，baseEntryId=head
      const { json: atHeadJson } = await ctx.call('GET', `/api/harness/threads/${threadId}?afterEntryId=${headId}`)
      const atHeadData = atHeadJson
      assert(atHeadData.baseEntryId === headId, 'cursor at head must return baseEntryId as head')
      assert(atHeadData.entries.length === 0, 'cursor at head must return empty entries list')

      // 4. 非祖先游标 -> 回退全量，baseEntryId=null
      const nonAncestorId = '00000000-0000-0000-0000-000000000999'
      const { json: nonAncestorJson } = await ctx.call('GET', `/api/harness/threads/${threadId}?afterEntryId=${nonAncestorId}`)
      const nonAncestorData = nonAncestorJson
      assert(nonAncestorData.baseEntryId === null, 'non-ancestor cursor must fallback to full snapshot with null baseEntryId')
      assert(nonAncestorData.entries.length === full.entries.length, 'fallback full snapshot must have same entry count')
    } finally {
      await ctx.call(
        'DELETE',
        `/api/chat/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
      )
    }
  },
})

registerCase({
  id: 'frontend.proxy_model_contract',
  level: 'L1',
  title: 'Frontend 代理 model 契约',
  docs: '5173 /api/platform/models 返回 name-based 结构化 config',
  async run(ctx) {
    assert(ctx.vars.frontendUrl, 'frontendUrl required')
    const { json } = await httpJson(
      ctx.vars.frontendUrl,
      'GET',
      '/api/platform/models',
    )
    const model = listResults(json)[0]
    assert(model?.type === 'MODEL' && model?.providerName && model?.name && model?.config?.defaultVariant, JSON.stringify(model))
    assertExactFields(
      model,
      ['name', 'type', 'providerName', 'modelId', 'description', 'config', 'version', 'createdAt', 'updatedAt'],
      'ModelDTO(MODEL)',
    )
    assert(model.group === undefined, 'MODEL wire contract must omit group')
  },
})

function compareModel(left, right) {
  return `${left.provider}/${left.name}`.localeCompare(`${right.provider}/${right.name}`)
}

/**
 * 复刻服务端 Session 自动默认名派生（Names.sessionNameFromUserText）：任意 Unicode 空白折叠为
 * 单空格并去首尾，截前 40 个 Unicode 码点（无省略号）。仅用于 case 期望值，不做服务端实现。
 */
function deriveSessionName(text) {
  const collapsed = String(text).replace(/\s+/gu, ' ').trim()
  return [...collapsed].slice(0, 40).join('')
}

/** 由 seed Agent 的 execution 构造 Builtin 模型选择。 */
function modelSelectionOf(ctx) {
  const agent = ctx.vars.agent
  const { modelName, variant } = agent.execution?.settings || {}
  assert(agent.execution?.driver === 'builtin' && modelName && (variant === null || typeof variant === 'string'), `invalid agent execution: ${JSON.stringify(agent)}`)
  return { modelName, variant }
}

/** 免费控制面 case 只需一个当前 Catalog 可解析的 Agent/Model，不依赖完整 E2E seed。 */
async function resolveAnyCatalogTarget(ctx) {
  const agents = listResults(
    (await ctx.call('GET', '/api/platform/agents')).json,
  )
  const models = listResults(
    (await ctx.call('GET', '/api/platform/models')).json,
  )
  for (const agent of agents) {
    if (agent.execution?.driver !== 'builtin') continue
    const { modelName, variant } = agent.execution.settings || {}
    if (!modelName || (variant !== null && typeof variant !== 'string')) continue
    const model = models.find((candidate) => candidate.name === modelName)
    if (!model) continue
    return {
      agent,
      model: { modelName, variant },
    }
  }
  throw new Error(
    `no resolvable Agent/Model in current Catalog: ${JSON.stringify({ agents, models })}`,
  )
}
