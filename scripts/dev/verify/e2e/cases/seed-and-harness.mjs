import { readFileSync } from 'node:fs'
import { createServer } from 'node:http'
import path from 'node:path'
import { isDeepStrictEqual } from 'node:util'

import {
  assert,
  assertExactFields,
  envelopeData,
  expectHttpError,
  httpJson,
  pageResults,
  cid,
} from '../lib/http.mjs'
import {
  acceptCommandBatch,
  assertRootYoloPolicy,
  branchSettingsOf,
  chatOwner,
  createChat,
  createNewSession,
  createNewThread,
  getThreadSnapshot,
  listChatSessions,
  listSessionEntries,
  listSessionThreads,
  previewHistoricalRequest,
  previewSessionDraftRequest,
  renameSession,
  renameThread,
  setAgentCommand,
  setModelCommand,
  setThreadYolo,
  stopThread,
  threadTarget,
  threadIdOf,
  userMessageCommand,
  waitForDurableMessages,
  waitForQuiescentThread,
} from '../lib/harness.mjs'
import { baseModelConfig, canonicalSeedCatalog } from '../lib/fixtures.mjs'
import { registerCase, getCase } from '../lib/registry.mjs'
import { REPO_ROOT } from '../../../lib/repo-root.mjs'

const PI_MODEL_CATALOG = JSON.parse(
  readFileSync(
    path.join(
      REPO_ROOT,
      'platform/src/test/resources/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/pi-model-catalog.json',
    ),
    'utf8',
  ),
)

registerCase({
  id: 'seed.structured_model_config',
  level: 'L1',
  title: 'Model 公开契约与 Pi 默认目录一致',
  docs: 'GET /api/ai/catalog/models：按 providerName/name 完整匹配 Pi 0.82.1 快照与公开字段集合；种子持久化形态的 variant 省略 protocolOptionsJson，公开契约返回 canonical 字符串（null/空 ≡ "{}"）',
  async run(ctx) {
    const { json } = await ctx.call('GET', '/api/ai/catalog/models?pageNumber=1&pageSize=50')
    const models = pageResults(json)
    const actualCatalog = models
      .map((model) => ({
        provider: model.providerName,
        name: model.name,
        modelId: model.modelId,
        description: model.description,
        config: model.config,
      }))
      .sort(compareModel)
    const expectedCatalog = canonicalSeedCatalog(PI_MODEL_CATALOG).sort(compareModel)
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
      assertExactFields(
        model,
        ['providerName', 'name', 'modelId', 'description', 'config', 'version', 'createTime', 'updateTime'],
        'AgentModelDTO',
      )
      assert(model.providerName && model.name, JSON.stringify(model))
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
  docs: 'seed Agent 以 name/model 引用；Provider 以 name 标识，八种协议映射及 nullable baseUrl wire 形态正确',
  async run(ctx) {
    const { json: agentsJson } = await ctx.call(
      'GET',
      '/api/ai/catalog/agents?pageNumber=1&pageSize=50',
    )
    const agents = pageResults(agentsJson)
    assert(agents.length > 0, 'no agents')
    const agent = agents.find((candidate) => candidate.name === 'default-assistant')
      || agents.find((candidate) => candidate.type === 'USER' && candidate.model)
    assert(agent, 'need a configured USER Agent')
    assertExactFields(
      agent,
      [
        'name',
        'type',
        'description',
        'systemPrompt',
        'model',
        'variant',
        'config',
        'version',
        'createTime',
        'updateTime',
      ],
      'AgentDefinitionDTO',
    )
    assert(agent.name && agent.model && agent.variant && agent.config, JSON.stringify(agent))
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
      ['minimax-anthropic', 'anthropic'],
    ])
    for (const [name, providerType] of expectedProviderTypes) {
      const provider = providers.find((candidate) => candidate.name === name)
      assert(provider?.providerType === providerType, JSON.stringify({ name, providerType, provider }))
      const hasBaseUrl = Object.hasOwn(provider, 'baseUrl')
      assert(
        !hasBaseUrl || (typeof provider.baseUrl === 'string' && provider.baseUrl.length > 0),
        `AgentProviderDTO.baseUrl must be omitted or a non-empty string: ${JSON.stringify(provider)}`,
      )
      assertExactFields(
        provider,
        [
          'name',
          'description',
          'providerType',
          ...(hasBaseUrl ? ['baseUrl'] : []),
          'configured',
          'modelCallTimeoutMillis',
          'modelCallIdleTimeoutMillis',
          'modelHttpRetryStatusCodes',
          'version',
          'createTime',
          'updateTime',
        ],
        'AgentProviderDTO',
      )
    }
    ctx.vars.provider = providers.find((provider) => provider.name === 'minimax')
  },
})

registerCase({
  id: 'catalog.internal_tools_hidden',
  level: 'L1',
  title: '内部 HOST Tool 不进入 Agent 可选目录',
  docs: 'GET /api/ai/catalog/tools 只返回 SELECTABLE Tool；load_skill/task 由 skills/subagents 派生激活，不能直接写入 Agent config.tools；Goal contributor 只贡献 get_goal/update_goal（目标正文由用户维护，无创建工具）',
  async run(ctx) {
    const tools = envelopeData((await ctx.call('GET', '/api/ai/catalog/tools')).json)
    assert(Array.isArray(tools), JSON.stringify(tools))
    const names = tools.map((tool) => String(tool.name))
    assert(!names.includes('load_skill'), `load_skill must be internal: ${JSON.stringify(names)}`)
    assert(!names.includes('task'), `task must be internal: ${JSON.stringify(names)}`)
    assert(
      tools.every((tool) => !Object.hasOwn(tool, 'id') && !Object.hasOwn(tool, 'version')),
      `Tool catalog entries must not expose id/version: ${JSON.stringify(tools)}`,
    )
    assert(
      ['get_goal', 'update_goal'].every((name) => names.includes(name)) &&
        !names.includes('create_goal'),
      `Goal contributor must expose get_goal/update_goal and no create_goal: ${JSON.stringify(tools)}`,
    )
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
      Number(thread.version) >= 1,
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
      'activeAgentDefinitionId',
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
      `/api/ai/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
    )
  },
})

registerCase({
  id: 'thread.provider_request_preview_guard',
  level: 'L1',
  title: '发送前请求预览不接受不准确的游标或无法规划的草稿',
  docs: 'POST /api/harness/threads/{threadId}/provider-request-preview 使用 owner-free 的 HarnessThreadCommandBatchDTO wire（{expectedHeadEntryId,expectedNextCommandSequence,commands}，无 owner/target）；陈旧 head/sequence 返回 409；无法解析 Agent 的空闲 Thread 返回 409，且预览不入队、不推进游标。完整 wire body/附件等价性由有 S3 与 Provider 编码器的自动化集成测试覆盖。',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-preview-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const owner = chatOwner(chat.id)
    const threadId = cid()
    await createNewSession(ctx, {
      owner,
      sessionId: cid(),
      threadId,
      rootSettings: branchSettingsOf(
        { name: `e2e-preview-missing-${cid().slice(0, 8)}` },
        modelSelectionOf(ctx),
      ),
      commands: [userMessageCommand('establish history', cid())],
    })
    const thread = await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
    const endpoint = `/api/harness/threads/${encodeURIComponent(threadId)}/provider-request-preview`
    // 预览面 owner-free：body 只带精确 cursor 与有序命令，不携带 owner/target。
    const draft = {
      expectedHeadEntryId: thread.headEntryId,
      expectedNextCommandSequence: thread.nextCommandSequence,
      commands: [userMessageCommand('preview only', cid())],
    }
    const stale = await expectHttpError(() => ctx.call('POST', endpoint, {
      ...draft,
      expectedNextCommandSequence: String(BigInt(thread.nextCommandSequence) + 1n),
    }), { status: 409 })
    assert(JSON.parse(stale.body).errors?.reason === 'PREVIEW_STALE_CURSOR', 'preview stale cursor reason missing or mismatched')
    const planning = await expectHttpError(() => ctx.call('POST', endpoint, draft), { status: 409 })
    assert(JSON.parse(planning.body).errors?.reason === 'PREVIEW_PLANNING_FAILED', 'preview planning reason missing or mismatched')
    const after = await getThreadSnapshot(ctx, threadId)
    assert(after.thread.headEntryId === thread.headEntryId, 'preview advanced thread head')
    assert(after.thread.nextCommandSequence === thread.nextCommandSequence, 'preview reserved command sequence')
    assert(after.queuedCommands.length === 0, 'preview enqueued a command')
  },
})

registerCase({
  id: 'thread.provider_request_preview_readonly',
  level: 'L1',
  // 本 case 把专用 Provider 的 baseUrl 指向 case 内自建的宿主 127.0.0.1 trap；distributed 容器内无法回连宿主 loopback。
  requires: ['host-mock'],
  title: '草稿与历史请求预览只读且边界严格',
  docs: 'POST /api/harness/sessions/{sessionId}/provider-request-preview（body 只有 {startEntryId,commands}，无 cursor）返回 200 DRAFT_REQUEST_PREVIEW；GET /api/harness/sessions/{sessionId}/entries/{entryId}/provider-request-preview 返回 200 HISTORICAL_REQUEST_PREVIEW；两者都是精确 8 字段、固定 notice、bodyByteSize==UTF-8 字节数、bodyJson 不泄漏 endpoint/credential；草稿起点只接受 ROOT 或已闭合 TURN_END（消息/中间 Entry 400），跨 Session 或不属于该 Session 的 Entry 400，Session 缺失 404，非 canonical 路径 400；历史预览只接受携带 assistantMetadata 的模型输出（其它 Entry 400）；全部预览不写任何 durable 状态（Entry Tree/Thread cursor/Thread 列表不变）。本 case 自建专用 Provider（endpoint/凭据均缺省）先覆盖 409 PREVIEW_PROVIDER_UNAVAILABLE，再把 endpoint 指向宿主 127.0.0.1 trap，证明只读预览只编码请求体、绝不触达 transport。历史预览的正向 200 由 interaction.pending_input_contract 在真实模型输出上覆盖。',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const trap = new ProviderPreviewTrap()
    let provider = null
    let model = null
    let agent = null
    let chat = null
    let threadId = null
    let primaryError = null
    const cleanupErrors = []
    const cleanup = async (label, action) => {
      try {
        await action()
      } catch (error) {
        cleanupErrors.push(`${label}: ${error?.message || String(error)}`)
      }
    }
    try {
      // 专用 Provider：endpoint 与凭据都缺省，ProviderDescriptor 要求 endpoint 非空白，因此该配置下预览必定无法解析 Provider。
      const providerResponse = await ctx.call('POST', '/api/ai/catalog/providers', {
        name: `e2e-provider-preview-${suffix}`,
        description: 'Local trap provider for readonly request preview E2E.',
        providerType: 'openai',
        baseUrl: null,
        credential: null,
        modelCallTimeoutMillis: 30_000,
        modelCallIdleTimeoutMillis: 10_000,
      })
      provider = envelopeData(providerResponse.json)
      assert(provider?.name && provider?.version, JSON.stringify(providerResponse.json))

      const modelResponse = await ctx.call('POST', '/api/ai/catalog/models', {
        providerName: provider.name,
        name: `e2e-model-preview-${suffix}`,
        modelId: `wire-preview-${suffix}`,
        description: 'Local readonly preview E2E model.',
        config: baseModelConfig({ limit: { context: 4096, output: 128 } }),
      })
      model = envelopeData(modelResponse.json)
      assert(
        model?.providerName === provider.name && model?.name,
        JSON.stringify(modelResponse.json),
      )

      const agentResponse = await ctx.call('POST', '/api/ai/catalog/agents', {
        name: `e2e-agent-preview-${suffix}`,
        description: 'Local readonly preview E2E agent.',
        systemPrompt: 'Reply with a short deterministic acknowledgement.',
        model: `${model.providerName}/${model.name}`,
        variant: 'default',
        config: { tools: [], skills: [], subagents: [], inheritParentEnvironment: true },
      })
      agent = envelopeData(agentResponse.json)
      assert(
        agent?.name && agent.model === `${model.providerName}/${model.name}`,
        JSON.stringify(agentResponse.json),
      )

      chat = await createChat(ctx, {
        title: `e2e-preview-readonly-${suffix}`,
        agentName: agent.name,
        yoloEnabled: false,
      })
      const sessionId = cid()
      const tid = cid()
      await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId,
        threadId: tid,
        rootSettings: branchSettingsOf(agent, {
          providerName: model.providerName,
          modelName: model.name,
          variant: 'default',
        }),
        yoloEnabled: false,
        commands: [userMessageCommand(`preview readonly ${suffix}`, cid())],
      })
      threadId = String(tid)
      // endpoint 缺省的 Provider 让初始回合确定性失败并收敛为闭合 TURN_END（无真实模型用量）。
      const quiescent = await waitForQuiescentThread(ctx, threadId, {
        timeoutMs: 60_000,
        intervalMs: 100,
      })
      assert(quiescent.status === 'IDLE', JSON.stringify(quiescent))

      const before = await listSessionEntries(ctx, sessionId)
      const threadsBefore = await listSessionThreads(ctx, sessionId)
      const snapshotBefore = await getThreadSnapshot(ctx, threadId)
      // 费用投影与 durable 历史解耦：本回合确定性失败、没有真实模型用量，因此所有 Entry 必须显式 null，
      // 绝不伪装成 0 费用；有价值的正向投影由带真实用量的集成/真实模型用例覆盖。
      assert(
        before.every((entry) => entry.usageCost === null),
        `non-model entries must project usageCost: null rather than a fake zero: ${JSON.stringify(
          before.map((entry) => [entry.entryType, entry.usageCost]),
        )}`,
      )
      const byType = (type) =>
        before.filter((entry) => String(entry.entryType || '').toUpperCase() === type)
      const turnEnd = byType('TURN_END').at(-1)
      const rootEntry = byType('ROOT')[0]
      const midTurn = [...byType('MESSAGE'), ...byType('TURN_START')]
      assert(turnEnd && rootEntry && midTurn.length > 0, JSON.stringify(before))
      assert(
        String(snapshotBefore.thread.headEntryId) === String(turnEnd.entryId),
        `quiescent head must be the closed TURN_END: ${JSON.stringify(snapshotBefore.thread)}`,
      )

      // endpoint 缺省时无法解析 Provider：ROOT 与已闭合 TURN_END 起点都稳定返回 409 PREVIEW_PROVIDER_UNAVAILABLE。
      for (const startEntryId of [String(rootEntry.entryId), String(turnEnd.entryId)]) {
        const unavailable = await expectHttpError(
          () =>
            previewSessionDraftRequest(ctx, sessionId, {
              startEntryId,
              commands: [userMessageCommand(`draft ${suffix}`, cid())],
            }),
          { status: 409 },
        )
        assert(
          JSON.parse(unavailable.body).errors?.reason === 'PREVIEW_PROVIDER_UNAVAILABLE',
          `draft preview reason missing or mismatched: ${unavailable.body}`,
        )
      }

      // 启用宿主 trap，只补 endpoint（凭据维持 null 的匿名编码路径）：预览只编码请求体，绝不应触达 transport。
      await trap.start()
      const providerUpdate = await ctx.call(
        'PUT',
        `/api/ai/catalog/providers/${encodeURIComponent(provider.name)}`,
        {
          expectedVersion: provider.version,
          providerType: 'openai',
          baseUrl: trap.baseUrl('/v1'),
        },
      )
      provider = envelopeData(providerUpdate.json)
      assert(provider?.name && provider?.version, JSON.stringify(providerUpdate.json))

      // 正向：以 ROOT 与已闭合 TURN_END 为起点都能得到只读草稿预览（预览绝不创建 Thread）。
      for (const startEntryId of [String(rootEntry.entryId), String(turnEnd.entryId)]) {
        const preview = await previewSessionDraftRequest(ctx, sessionId, {
          startEntryId,
          commands: [userMessageCommand(`draft ${suffix}`, cid())],
        })
        assert(
          preview.sourceHeadEntryId === startEntryId,
          `draft preview source head must be the start entry: ${JSON.stringify(preview)}`,
        )
        assert(
          typeof JSON.parse(preview.bodyJson) === 'object',
          JSON.stringify(preview.bodyJson),
        )
        assert(
          !/(authorization|api[_-]?key|credential|secret)/i.test(preview.bodyJson),
          `preview body must not leak endpoint/credential material: ${preview.bodyJson}`,
        )
      }

      // 负向：起点不是合法 fork 边界、不属于该 Session、或 Session 缺失都确定性拒绝。
      for (const entry of midTurn) {
        await expectHttpError(
          () =>
            previewSessionDraftRequest(ctx, sessionId, {
              startEntryId: String(entry.entryId),
              commands: [userMessageCommand(`draft ${suffix}`, cid())],
            }),
          { status: 400 },
        )
      }
      await expectHttpError(
        () =>
          previewSessionDraftRequest(ctx, sessionId, {
            startEntryId: cid(),
            commands: [userMessageCommand(`draft ${suffix}`, cid())],
          }),
        { status: 400 },
      )
      await expectHttpError(
        () =>
          previewSessionDraftRequest(ctx, cid(), {
            startEntryId: String(turnEnd.entryId),
            commands: [userMessageCommand(`draft ${suffix}`, cid())],
          }),
        { status: 404 },
      )
      // 历史预览只接受携带 assistantMetadata 的模型输出：非模型输出 Entry 与未知 Entry 都是 400，Session 缺失 404。
      for (const entry of [turnEnd, ...midTurn]) {
        await expectHttpError(() => previewHistoricalRequest(ctx, sessionId, String(entry.entryId)), {
          status: 400,
        })
      }
      await expectHttpError(() => previewHistoricalRequest(ctx, sessionId, cid()), { status: 400 })
      await expectHttpError(() => previewHistoricalRequest(ctx, cid(), String(turnEnd.entryId)), {
        status: 404,
      })
      await expectHttpError(
        () =>
          ctx.call(
            'GET',
            `/api/harness/sessions/${encodeURIComponent(String(sessionId))}/entries/not-a-uuid/provider-request-preview`,
          ),
        { status: 400 },
      )

      // 只读：预览绝不写 Entry、不推进 cursor、不创建 Thread，也不触达 Provider transport。
      assert(
        JSON.stringify(await listSessionEntries(ctx, sessionId)) === JSON.stringify(before),
        'preview must not write any Entry',
      )
      assert(
        JSON.stringify(await listSessionThreads(ctx, sessionId)) === JSON.stringify(threadsBefore),
        'preview must not create a Thread',
      )
      const snapshotAfter = await getThreadSnapshot(ctx, threadId)
      assert(
        String(snapshotAfter.thread.headEntryId) === String(snapshotBefore.thread.headEntryId)
          && String(snapshotAfter.thread.version) === String(snapshotBefore.thread.version)
          && String(snapshotAfter.thread.nextCommandSequence)
            === String(snapshotBefore.thread.nextCommandSequence)
          && snapshotAfter.queuedCommands.length === 0
          && snapshotAfter.modelInvocation === null,
        JSON.stringify({ before: snapshotBefore.thread, after: snapshotAfter.thread }),
      )
      assert(
        trap.requests === 0,
        `request preview must never reach the provider transport: ${trap.requests}`,
      )
    } catch (error) {
      primaryError = error
    } finally {
      await cleanup('stop active thread', async () => {
        if (!threadId) return
        const snapshot = await getThreadSnapshot(ctx, threadId)
        if (
          snapshot.thread.status !== 'IDLE'
          || snapshot.thread.processing
          || snapshot.queuedCommands.length > 0
          || snapshot.modelInvocation !== null
        ) {
          await stopThread(ctx, threadId, {
            stopRequestId: cid(),
            expectedVersion: snapshot.thread.version,
          })
        }
      })
      await cleanup('trap server', () => trap.close())
      await cleanup('chat', async () => {
        if (chat?.id) {
          await ctx.call(
            'DELETE',
            `/api/ai/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(
              chat.version,
            )}`,
          )
        }
      })
      await cleanup('agent', async () => {
        if (agent?.name) {
          await ctx.call(
            'DELETE',
            `/api/ai/catalog/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(
              agent.version,
            )}`,
          )
        }
      })
      await cleanup('model', async () => {
        if (model?.providerName && model?.name) {
          await ctx.call(
            'DELETE',
            `/api/ai/catalog/models/${encodeURIComponent(
              model.providerName,
            )}/${encodeURIComponent(model.name)}?expectedVersion=${encodeURIComponent(
              model.version,
            )}`,
          )
        }
      })
      await cleanup('provider', async () => {
        if (provider?.name) {
          await ctx.call(
            'DELETE',
            `/api/ai/catalog/providers/${encodeURIComponent(
              provider.name,
            )}?expectedVersion=${encodeURIComponent(provider.version)}`,
          )
        }
      })
      if (cleanupErrors.length > 0) {
        const cleanupMessage = `E2E cleanup failed: ${cleanupErrors.join(' | ')}`
        try {
          ctx.writeArtifact('cleanup-errors.txt', `${cleanupMessage}\n`)
        } catch {
          // 保留原始错误。
        }
        if (primaryError == null) primaryError = new Error(cleanupMessage)
      }
    }
    if (primaryError != null) throw primaryError
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
      const endpoint = `/api/harness/threads/${encodeURIComponent(threadId)}/model-request-debug`

      const { status, json } = await ctx.call('POST', endpoint, {
        model: target.model,
        environmentName: null,
      })
      assert(status === 200, `model-request-debug status ${status}: ${JSON.stringify(json)}`)
      const debug = envelopeData(json)
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
      await expectHttpError(() => ctx.call('POST', endpoint, { environmentName: null }), { status: 400 })
      await expectHttpError(
        () => ctx.call('POST', endpoint, { model: target.model, environmentName: 'bad/name' }),
        { status: 400 },
      )
      await expectHttpError(() => ctx.call('GET', endpoint), { status: 405 })

      // 只读：head/version/nextCommandSequence 不变，不入队命令，也不启动 Invocation。
      const after = await getThreadSnapshot(ctx, threadId)
      assert(
        String(after.thread.headEntryId) === String(before.thread.headEntryId)
          && String(after.thread.version) === String(before.thread.version)
          && String(after.thread.nextCommandSequence) === String(before.thread.nextCommandSequence)
          && after.queuedCommands.length === 0
          && after.modelInvocation === null,
        JSON.stringify({ before: before.thread, after: after.thread }),
      )
    } finally {
      await ctx.call(
        'DELETE',
        `/api/ai/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
      )
    }
  },
})

registerCase({
  id: 'thread.branch_settings_projection',
  level: 'L1',
  title: 'NEW_SESSION rootSettings 完整投影到 Thread 快照',
  docs: 'agentName/model/environmentName 与 Thread YOLO policy（mode/rootThreadId）原样持久化并投影，goal 始终显式投影（未设置时 null）；Chat 默认值独立',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-settings-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const requested = {
      agentName: ctx.vars.agent.name,
      model: modelSelectionOf(ctx),
      environmentName: null,
    }
    // 请求方向不携带 goal（Goal 只由 typed GOAL 命令设置为用户维护的事实）；完整快照语义要求
    // Thread 投影总是显式出现 goal，未设置时为 null。
    const expectedSettings = { ...requested, goal: null }
    const accepted = await createNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId: cid(),
      threadId: cid(),
      rootSettings: requested,
      yoloEnabled: true,
      commands: [userMessageCommand(`settings ${cid().slice(0, 8)}`, cid())],
    })
    assertRootYoloPolicy(accepted.thread, true)
    assert(
      isDeepStrictEqual(accepted.thread.branchSettings, expectedSettings),
      JSON.stringify({ expected: expectedSettings, actual: accepted.thread.branchSettings }),
    )
    // Thread branchSettings 独立于 Chat 默认值：Chat 仍保存自身 defaults。
    assert(chat.yoloEnabled === false, JSON.stringify(chat))
    // reread 同一 Thread：投影稳定。
    const reread = await getThreadSnapshot(ctx, accepted.thread.threadId)
    assert(
      isDeepStrictEqual(reread.thread.branchSettings, expectedSettings),
      JSON.stringify(reread.thread.branchSettings),
    )
  },
})

registerCase({
  id: 'thread.goal_typed_persistence',
  level: 'L1',
  title: 'typed GOAL 在 PostgreSQL 持久接受、设置与清除',
  docs: 'Chat owner 的 GOAL 命令可在真实 PostgreSQL 接受并被消费为 branchSettings.goal（id/text），单独清除后 goal:null；使用不存在的 Agent 让模型规划确定性失败，不调用付费 Provider',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-goal-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const owner = chatOwner(chat.id)
    const threadId = cid()
    const text = `ship goal ${cid()}`
    const accepted = await createNewSession(ctx, {
      owner,
      sessionId: cid(),
      threadId,
      rootSettings: branchSettingsOf(
        { name: `missing-goal-agent-${cid().slice(0, 8)}` },
        modelSelectionOf(ctx),
      ),
      commands: [{ type: 'GOAL', text, idempotencyKey: cid() }],
    })
    assert(accepted.acceptedCommands[0].type === 'GOAL', JSON.stringify(accepted.acceptedCommands))
    const afterSet = await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
    const goal = afterSet.branchSettings.goal
    assert(goal?.text === text && /^[0-9a-f-]{36}$/.test(goal?.id), JSON.stringify(goal))

    const cleared = await acceptCommandBatch(ctx, {
      owner,
      target: threadTarget({
        threadId,
        expectedHeadEntryId: afterSet.headEntryId,
        expectedNextCommandSequence: afterSet.nextCommandSequence,
      }),
      commands: [{ type: 'GOAL', text: null, idempotencyKey: cid() }],
    })
    assert(cleared.acceptedCommands[0].type === 'GOAL', JSON.stringify(cleared.acceptedCommands))
    const afterClear = await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
    assert(afterClear.branchSettings.goal === null, JSON.stringify(afterClear.branchSettings))
  },
})

registerCase({
  id: 'thread.user_message_strict_wire',
  level: 'L1',
  title: 'USER_MESSAGE 严格结构化 contents 与 exact replay',
  docs: 'USER_MESSAGE 只接受一个非空有序 contents 列表（TEXT/ATTACHMENT/RESOURCE）；本免费 case 覆盖 TEXT 正向与 text/content shorthand、role、未知字段、空 contents、IMAGE/AUDIO/VIDEO、非 canonical uploadId、TEXT 携带 imageTier 与非法 imageTier 名等非法 shape；RESOURCE 正向与 Session ownership 由 chat.attachment_upload_contract 覆盖。202 payloadJson 是 canonical AgentMessage；同 batch exact replay 返回既有命令（sequence/payloadJson 稳定）',
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
    const batchFor = (cursorThread, commands) => ({
      owner: chatOwner(chat.id),
      target: threadTarget({
        threadId,
        expectedHeadEntryId: cursorThread.headEntryId,
        expectedNextCommandSequence: cursorThread.nextCommandSequence,
      }),
      commands,
    })
    const acceptedList = []
    let cursorBefore = await waitForQuiescentThread(ctx, threadId, {
      timeoutMs: 60_000,
      intervalMs: 100,
    })
    for (const single of commandsOf()) {
      const acceptedOnce = await acceptCommandBatch(ctx, batchFor(cursorBefore, [single]))
      acceptedList.push(acceptedOnce.acceptedCommands[0])
      // Exact replay：必须继续使用原首次接受前 cursor（该命令的 sequence）——
      // THREAD replay 要求存储命令起始 sequence == expectedNextCommandSequence。
      const replayAccepted = await acceptCommandBatch(ctx, batchFor(cursorBefore, [single]))
      assert(replayAccepted.replayed === true, JSON.stringify(replayAccepted))
      assert(
        String(replayAccepted.acceptedCommands[0].sequence)
          === String(acceptedList.at(-1).sequence)
          && String(replayAccepted.acceptedCommands[0].payloadJson)
            === String(acceptedList.at(-1).payloadJson),
        `replay must return the existing command: ${JSON.stringify({
          original: acceptedList.at(-1),
          replay: replayAccepted.acceptedCommands[0],
        })}`,
      )
      cursorBefore = await waitForQuiescentThread(ctx, threadId, {
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
    const validationThread = cursorBefore

    const expectInvalidUserCommand = (command, options = { status: 400 }) =>
      expectHttpError(
        () =>
          acceptCommandBatch(ctx, {
            owner: chatOwner(chat.id),
            target: threadTarget({
              threadId,
              expectedHeadEntryId: validationThread.headEntryId,
              expectedNextCommandSequence: validationThread.nextCommandSequence,
            }),
            commands: [command],
          }),
        options,
      )

    // 文本 shorthand（text / content）已从 USER_MESSAGE wire 移除。
    // 未知字段在 Jackson 反序列化层即拒绝（detail 为通用 "Failed to read request"）。
    await expectInvalidUserCommand({ type: 'USER_MESSAGE', idempotencyKey: cid(), text: 'x' })
    await expectInvalidUserCommand(
      { type: 'USER_MESSAGE', idempotencyKey: cid(), content: 'x' },
      { status: 400 },
    )
    // 多余字段（role 等）与缺少 contents 字段均确定性 400。
    await expectInvalidUserCommand(
      {
        type: 'USER_MESSAGE',
        idempotencyKey: cid(),
        contents: [{ type: 'TEXT', text: 'x' }],
        role: 'USER',
      },
      { status: 400 },
    )
    await expectInvalidUserCommand(
      { type: 'USER_MESSAGE', idempotencyKey: cid() },
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
      { status: 400, messageIncludes: /uploadId/i },
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
      { status: 400 },
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
  docs: 'CUSTOM_MESSAGE（SYSTEM/USER 均不可）在产品 HTTP command-batches 面确定性 400；同一 batch 的 user-like（USER_MESSAGE 或 typed GOAL）必须恰一条且在末尾，故两条 USER_MESSAGE、非末尾 user-like、乱序 SET、未知类型 => 400；同 batch 非法 shape 不推进 cursor',
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

    // CUSTOM_MESSAGE 产品 HTTP 面被拒绝（SYSTEM/USER 均不可）。未知枚举在 Jackson
    // 反序列化层即失败，detail 是通用 "Failed to read request"（不携带命令名）。
    for (const role of ['SYSTEM', 'USER']) {
      await expectHttpError(
        () =>
          acceptCommandBatch(ctx, {
            ...cursor(),
            commands: [
              { type: 'CUSTOM_MESSAGE', role, idempotencyKey: cid(), content: 'x' },
            ],
          }),
        { status: 400 },
      )
    }
    // 两条 USER_MESSAGE 同一 batch => 400：typed GOAL 与 USER_MESSAGE 都是 user-like 终止输入，
    // HTTP 面只允许恰一条且必须在末尾。
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          ...cursor(),
          commands: [
            userMessageCommand('first', cid()),
            userMessageCommand('second', cid()),
          ],
        }),
      { status: 400, messageIncludes: /terminal user-like command|USER_MESSAGE/i },
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
      { status: 400, messageIncludes: /order|final|USER_MESSAGE/i },
    )
    // 未知命令类型 => 400。
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          ...cursor(),
          commands: [{ type: 'RENAME_THREAD', idempotencyKey: cid() }],
        }),
      { status: 400, messageIncludes: /type/i },
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
        expectedHeadEntryId: thread.headEntryId,
        expectedNextCommandSequence: thread.nextCommandSequence,
      }),
      commands: [userMessageCommand('idempotent replay', idempotencyKey)],
    })
    const first = await acceptCommandBatch(ctx, batch())
    assert(first.replayed === false, JSON.stringify(first))
    const replay = await acceptCommandBatch(ctx, batch())
    assert(replay.replayed === true, JSON.stringify(replay))
    assert(
      String(replay.acceptedCommands[0].sequence) === String(first.acceptedCommands[0].sequence)
        && String(replay.acceptedCommands[0].payloadJson) === String(first.acceptedCommands[0].payloadJson),
      `replay must return the existing command: ${JSON.stringify({ first, replay })}`,
    )
    // 部分重放：HTTP 面 shape 限制一个 batch 恰一条 USER_MESSAGE，因此用
    // SET_AGENT(新) + USER_MESSAGE(已存在) 构造 partial：SET 是新 id、USER 已存在 =>
    // present=1/2 => 409 PARTIAL_COMMAND_REPLAY（Runtime 检测先于命令 admission）。
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          ...batch(),
          commands: [
            setAgentCommand('x', cid()),
            userMessageCommand('replayed again', idempotencyKey),
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
  docs: 'expectedHeadEntryId/expectedNextCommandSequence 与快照不符 => 409 + errors.reason=STALE_COMMAND_CURSOR；隔离 Thread 上精确断言 nextCommandSequence/version/head 前后不变',
  async run(ctx) {
    const { agent, model } = await resolveAnyCatalogTarget(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-stale-cas-${cid().slice(0, 8)}`,
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
      commands: [userMessageCommand(`stale cas materialize ${cid().slice(0, 8)}`, cid())],
    })
    await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
    const before = await getThreadSnapshot(ctx, threadId)
    const thread = before.thread
    const staleHead = await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          owner: chatOwner(chat.id),
          target: threadTarget({
            threadId,
            expectedHeadEntryId: cid(),
            expectedNextCommandSequence: thread.nextCommandSequence,
          }),
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
        acceptCommandBatch(ctx, {
          owner: chatOwner(chat.id),
          target: threadTarget({
            threadId,
            expectedHeadEntryId: thread.headEntryId,
            expectedNextCommandSequence: '999999999',
          }),
          commands: [userMessageCommand('stale sequence', cid())],
        }),
      { status: 409, messageIncludes: /sequence|conflict|stale/i },
    )
    const staleSequenceBody = JSON.parse(staleSequence.body)
    assert(
      staleSequenceBody.errors?.reason === 'STALE_COMMAND_CURSOR',
      `stale sequence conflict reason: ${staleSequence.body}`,
    )
    const after = await getThreadSnapshot(ctx, threadId)
    assert(
      String(after.thread.nextCommandSequence) === String(before.thread.nextCommandSequence)
        && String(after.thread.version) === String(before.thread.version)
        && String(after.thread.headEntryId) === String(before.thread.headEntryId),
      `stale batches must not mutate the Thread: ${JSON.stringify({
        before: before.thread,
        after: after.thread,
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
      // NEW_THREAD 独立 fork 新执行根：根开关从原根 mode 派生，不读取已删除的 boolean 字段。
      yoloEnabled: thread.yoloPolicy.mode === 'ENABLE',
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
        const message = JSON.parse(entry.payloadJson).message
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
    const forkPayload = JSON.parse(forkEntry.payloadJson)
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
        { status: 400 },
      )
      // 拒绝必须是零写入：预分配 Thread 未创建、Session Entry Tree 与源 Thread head 一字不动。
      await expectHttpError(() => ctx.call('GET', `/api/harness/threads/${rejectedThreadId}`), {
        status: 404,
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
      { status: 404 },
    )
    // 清理：删除 owner Chat（连带其 Session/Thread/Entry 资源）。
    await ctx.call(
      'DELETE',
      `/api/ai/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
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
        expectedHeadEntryId: trunk.thread.headEntryId,
        expectedNextCommandSequence: trunk.thread.nextCommandSequence,
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
    const alternateForkPayload = alternateFork ? JSON.parse(alternateFork.payloadJson) : null
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
      `/api/ai/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
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
      { status: 400, messageIncludes: /session|entry/i },
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
      { status: 404 },
    )
    // 清理：删除 owner Chat（连带两个 Session/Thread/Entry 资源）。
    await ctx.call(
      'DELETE',
      `/api/ai/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
    )
  },
})

registerCase({
  id: 'thread.stop_idle_boundary',
  level: 'L1',
  title: 'IDLE stop 写入持久 STOP barrier 且同 stopRequestId exact replay',
  docs: 'POST /stop body={stopRequestId,expectedVersion}；本线程无 open Turn 时写入完整 STOP barrier Turn（TURN_START(STOP) → ASSISTANT_ERROR(CANCELLED) → TURN_END(STOPPED, continueModel=false, USER_STOP, closeRequestId)）=> status=STOPPED、thread.executionControl=STOPPED、stoppedThreads[] 含目标 Thread 回执（threadId/stopRequestId 为请求值、stoppedTurnEndEntryId=该 TURN_END 且成为新 head、cancelledCommandCount=0、cancelledInputs=[]）、version 恰好 +1；同 stopRequestId + 原 expectedVersion 再次调用 => status=REPLAYED、同回执 stoppedTurnEndEntryId、version/head/Entry 集合不变（不写第二条 barrier）；未使用过的 stopRequestId 配 stale version => 409。真实 live Turn 的 STOPPED/REPLAYED 由 L2 real.stop_partial_continue 覆盖',
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
      thread.processing === false && thread.status === 'IDLE',
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
      Number(first.thread.version) === Number(thread.version) + 1,
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
    const barrierEndPayload = JSON.parse(barrierEnd.payloadJson)
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
    const barrierStartPayload = JSON.parse(barrierStart.payloadJson)
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
        && JSON.parse(barrierCancel.payloadJson).error?.code === 'CANCELLED',
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
      { status: 409, messageIncludes: /version/i },
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
        `/api/ai/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
      )
    }
  },
})

registerCase({
  id: 'thread.session_thread_rename_persistence',
  level: 'L1',
  title: 'Session/Thread 重命名持久化：UUID 不变、Thread version 精确 +1、规范同名 no-op',
  docs: 'PUT /api/harness/sessions/{id}/name {name} 与 PUT /api/harness/threads/{id}/name {name} 返回权威 DTO：Session（sessionId/name/createdAt）UUID/createdAt 不变、owner 摘要 fresh 持久；Thread 本体 UUID/head/session 不变、实际改名 version 精确 +1，snapshot 可观测的 queuedCommands/modelInvocation/toolInvocations 与 entries 均不变、Thread 摘要 fresh 持久；规范化同名（空白变体）no-op：返回 canonical name 且 version 零触碰；不依赖真实 Provider（缺失 Agent 确定性 PLANNING_FAILED）',
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
        String(Number(renamedThread.version)) === String(Number(beforeThread.version) + 1),
        JSON.stringify({ before: beforeThread.version, after: renamedThread.version }),
      )
      const afterSnapshot = await getThreadSnapshot(ctx, threadId)
      assert(afterSnapshot.thread.name === threadName, JSON.stringify(afterSnapshot.thread))
      assert(String(afterSnapshot.thread.version) === String(renamedThread.version), JSON.stringify(afterSnapshot.thread))
      // 重命名不得产生 Command/Entry/Invocation/Work：queued 清空、无模型调用、无 tool invocation、
      // entries 与 rename 前逐项一致（这些是 snapshot 直接可观测的 Work 指标）。
      assert(afterSnapshot.queuedCommands.length === 0, JSON.stringify(afterSnapshot.queuedCommands))
      assert(afterSnapshot.modelInvocation === null, JSON.stringify(afterSnapshot.modelInvocation))
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
        `/api/ai/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
      )
    }
  },
})

registerCase({
  id: 'thread.branch_settings_diff_commands',
  level: 'L1',
  title: 'SET_* 命令一个原子 batch 精确 wire 并消费投影',
  docs: '前端固定顺序 SET_AGENT,SET_MODEL,USER_MESSAGE 一个 batch（yolo 走直接控制面，绝不进入 mailbox）；SET_AGENT 使用 canonical 但不存在的名称，使 Resolver 在调用 Provider 前确定性 PLANNING_FAILED；等 quiescent 后 Thread branchSettings 精确投影、queue 清空、USER entry 与 AssistantError 可见，最终 TURN_END(FAILED, continueModel=false)；缺失 environmentName 的 SET_ENVIRONMENT 与 workspacePath 字段在任何 command type 上 => 400',
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
      rootSettings: branchSettingsOf(
        { name: `e2e-set-all-missing-${cid().slice(0, 8)}` },
        modelSelectionOf(ctx),
      ),
      yoloEnabled: false,
      commands: [userMessageCommand(`set-all materialize ${cid().slice(0, 8)}`, cid())],
    })
    const thread = await waitForQuiescentThread(ctx, threadId, { timeoutMs: 60_000, intervalMs: 100 })
    const marker = `SET-ALL-${cid()}`
    const modelSelection = modelSelectionOf(ctx)
    const missingAgentName = `missing-agent-${cid().slice(0, 8)}`
    const commands = [
      setAgentCommand(missingAgentName, cid()),
      setModelCommand(
        {
          providerName: modelSelection.providerName,
          modelName: modelSelection.modelName,
          variant: modelSelection.variant,
        },
        cid(),
      ),
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
        'SET_AGENT,SET_MODEL,USER_MESSAGE',
      JSON.stringify(dto),
    )
    for (let i = 1; i < dto.length; i++) {
      assert(
        Number(dto[i].sequence) === Number(dto[i - 1].sequence) + 1,
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
      model: {
        providerName: modelSelection.providerName,
        modelName: modelSelection.modelName,
        variant: modelSelection.variant,
      },
      environmentName: null,
      // SET_* 不触碰 goal：未设置 Goal 的 branch 投影始终显式携带 goal:null。
      goal: null,
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
      isDeepStrictEqual(finalSnapshot.thread.yoloPolicy, thread.yoloPolicy),
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
      { status: 400, messageIncludes: /type/i },
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
      { status: 400 },
    )
    // SET_ENVIRONMENT 必须显式携带 nullable environmentName 字段；缺失该字段必须 400 拒绝
    // （detail 包含 explicit nullable environmentName 提示）。
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          owner: chatOwner(chat.id),
          target: threadTarget({
            threadId,
            expectedHeadEntryId: fresh.thread.headEntryId,
            expectedNextCommandSequence: fresh.thread.nextCommandSequence,
          }),
          commands: [{ type: 'SET_ENVIRONMENT', idempotencyKey: cid() }],
        }),
      {
        status: 400,
        messageIncludes: /SET_ENVIRONMENT requires an explicit nullable environmentName field/i,
      },
    )
    // 真正未知的 command type 仍按 unknown command type 拒绝。
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
      { status: 400, messageIncludes: /unknown command type/i },
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
      { status: 400, messageIncludes: /environmentName/i },
    )
    // 其他 command type 不允许携带 environmentName 字段。
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
      { status: 400, messageIncludes: /environmentName/i },
    )
    // 其他 discriminator 即使显式传 workspacePath 也必须按 unknown HTTP command field 拒绝。
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
              type: 'SET_MODEL',
              idempotencyKey: cid(),
              model: {
                providerName: modelSelection.providerName,
                modelName: modelSelection.modelName,
                variant: modelSelection.variant,
              },
              workspacePath: null,
            },
          ],
        }),
      { status: 400, messageIncludes: /workspacePath/i },
    )
    // 严格 wire：未预期的字段必须被拒绝（400 unknown HTTP command field: unexpected）。
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
              unexpected: { workspacePath: '.' },
            },
          ],
        }),
      { status: 400, messageIncludes: /unexpected/i },
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
    assertRootYoloPolicy(thread, false)

    // 变化 + 精确 version：version +1，返回权威 Thread。
    const enabled = await setThreadYolo(ctx, threadId, { yoloEnabled: true })
    assertRootYoloPolicy(enabled, true)
    assert(
      String(Number(enabled.version)) === String(Number(thread.version) + 1),
      JSON.stringify({ before: thread.version, after: enabled.version }),
    )
    assert(enabled.headEntryId === thread.headEntryId, JSON.stringify(enabled))

    // 同值 no-op：不读取请求 version，version/head 零触碰。
    const sameValue = await setThreadYolo(ctx, threadId, { yoloEnabled: true })
    assertRootYoloPolicy(sameValue, true)
    assert(String(sameValue.version) === String(enabled.version), JSON.stringify(sameValue))

    // 关闭并精确 +1；快照反映同一权威值，且全程不产生 queued Command / Entry / Work。
    const disabled = await setThreadYolo(ctx, threadId, { yoloEnabled: false })
    assertRootYoloPolicy(disabled, false)
    assert(
      String(Number(disabled.version)) === String(Number(enabled.version) + 1),
      JSON.stringify({ before: enabled.version, after: disabled.version }),
    )
    const fresh = await getThreadSnapshot(ctx, threadId)
    assertRootYoloPolicy(fresh.thread, false)
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
      `/api/ai/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
    )
  },
})

registerCase({
  id: 'thread_tree.query_contract',
  level: 'L1',
  title: 'Agent 关系树最小只读投影',
  docs: 'GET /api/harness/threads/{id}/tree：真实根 parentThreadId 与未结束 outcome 显式可空；状态、回合与工具数来源于当前 head，查询不推进版本；缺失 Agent 确定性失败，无真实 Provider 调用',
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
      const nodes = envelopeData(json)
      assert(Array.isArray(nodes) && nodes.length === 1, JSON.stringify(nodes))
      const root = nodes[0]
      assertExactFields(
        root,
        ['threadId', 'parentThreadId', 'name', 'agentName', 'model', 'status', 'processing', 'turnCount', 'toolCallCount', 'outcome'],
        'HarnessThreadTreeNodeDTO',
      )
      assert(root.threadId === threadId && root.parentThreadId === null, JSON.stringify(root))
      assert(root.status === 'IDLE' && root.processing === false, JSON.stringify(root))
      assert(root.outcome === 'FAILED' && root.turnCount === 1 && root.toolCallCount === 0, JSON.stringify(root))
      assert(isDeepStrictEqual(root.model, before.thread.branchSettings.model), JSON.stringify(root))
      const after = await getThreadSnapshot(ctx, threadId)
      assert(after.thread.version === before.thread.version, 'tree query must not change version')
      assert(after.thread.headEntryId === before.thread.headEntryId, 'tree query must not change head')
    } finally {
      await ctx.call(
        'DELETE',
        `/api/ai/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
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
      () => ctx.call('GET', '/api/harness/threads/1-1-1-1-1/tree'),
      { status: 400 },
    )
    await expectHttpError(
      () => ctx.call('GET', '/api/harness/threads/00000000-0000-0000-0000-000000000999/tree'),
      { status: 404 },
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
    assertExactFields(
      model,
      ['providerName', 'name', 'modelId', 'description', 'config', 'version', 'createTime', 'updateTime'],
      'AgentModelDTO',
    )
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

/**
 * 只读请求预览的宿主 trap：监听 127.0.0.1 随机端口，只累计被调用次数，不记录 header/token/body；任何调用都返回 500。
 * 预览只做请求体编码、绝不打开 Provider transport，因此期望计数始终为 0。
 */
export class ProviderPreviewTrap {
  constructor() {
    this.requests = 0
    this.sockets = new Set()
    this.listening = false
    this.base = null
    this.server = createServer((request, response) => {
      this.requests += 1
      response.writeHead(500, { 'Content-Type': 'application/json', Connection: 'close' })
      response.end(
        JSON.stringify({ error: { message: 'preview must not reach the provider transport' } }),
      )
    })
    this.server.on('connection', (socket) => {
      this.sockets.add(socket)
      socket.once('close', () => this.sockets.delete(socket))
    })
  }

  async start() {
    await new Promise((resolve, reject) => {
      this.server.once('error', reject)
      this.server.listen(0, '127.0.0.1', () => {
        this.server.removeListener('error', reject)
        resolve()
      })
    })
    const address = this.server.address()
    assert(address && typeof address === 'object', 'trap server did not bind an address')
    this.base = `http://127.0.0.1:${address.port}`
    this.listening = true
  }

  baseUrl(suffix = '') {
    assert(this.base, 'trap server is not started')
    return `${this.base}${suffix}`
  }

  async close() {
    for (const socket of this.sockets) socket.destroy()
    this.sockets.clear()
    if (!this.listening) return
    await new Promise((resolve, reject) => {
      this.server.close((error) => (error ? reject(error) : resolve()))
    })
    this.listening = false
  }
}
