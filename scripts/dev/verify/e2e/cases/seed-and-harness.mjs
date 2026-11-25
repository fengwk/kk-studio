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
  createWorkspaceBranch,
  createWorkspaceFork,
  createWorkspaceSession,
  createWorkspaceSpace,
  deleteWorkspaceSession,
  getThreadSnapshot,
  waitForQuiescentThread,
} from '../lib/harness.mjs'
import { canonicalSeedCatalog, seedCatalogWithVariants } from '../lib/fixtures.mjs'
import { createCaseAdder } from '../lib/registry.mjs'
import { REPO_ROOT } from '../../../lib/repo-root.mjs'

export const cases = []
const registerCase = createCaseAdder(cases)

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
    const { json: agentsJson } = await ctx.call('GET', '/api/platform/agents')
    const agents = listResults(agentsJson)
    assert(agents.length > 0, 'no agents')
    const agent = agents.find((candidate) => candidate.name === 'default-assistant')
      || agents.find((candidate) => candidate.execution?.driver === 'builtin')
    assert(agent, 'need an Agent with a configured builtin execution')
    assertExactFields(
      agent,
      ['name', 'description', 'systemPrompt', 'execution', 'config', 'version', 'createdAt', 'updatedAt'],
      'AgentDTO',
    )
    assert(agent.name && agent.execution?.driver === 'builtin' && agent.execution.settings?.modelName && agent.config, JSON.stringify(agent))
    ctx.vars.agent = agent

    const { json: providersJson } = await ctx.call('GET', '/api/platform/providers')
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
          'name', 'description', 'providerType', ...(hasBaseUrl ? ['baseUrl'] : []), 'credential',
          'modelCallTimeoutMillis', 'modelCallIdleTimeoutMillis', 'modelHttpRetryStatusCodes',
          'promptCacheRetentions', 'version', 'createdAt', 'updatedAt',
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
      assertExactFields(tool, ['name', 'description', 'environmentSupport', 'provider'], 'Tool catalog entry')
      assertExactFields(tool.provider, ['kind', 'id', 'name'], 'Tool catalog entry provider')
    }
  },
})

registerCase({
  id: 'thread.model_request_debug_draft_settings',
  level: 'L1',
  title: 'Model Request Debug 草稿设置只读投影',
  docs: 'POST /api/harness/builtin/threads/{threadId}/model-request-debug 以 UI 草稿 {model, environmentName}（environmentName 可空，null 表示未选 Environment）在真实 snapshot 上只读现算；响应回显草稿 model/env，tools/skills/subagents 投影符合预览契约；frozenInvocation 与草稿选择相互独立（本 Thread 无活动 Invocation 时显式 null）；缺 model 或非 canonical environmentName => 400，GET => 405；查询不改变 head/version/nextCommandSequence、不入队命令、不启动 Invocation',
  async run(ctx) {
    const target = await resolveAnyCatalogTarget(ctx)
    const space = await createWorkspaceSpace(ctx, {
      title: `e2e-model-debug-${cid().slice(0, 8)}`,
      defaultAgentName: target.agent.name,
    })
    let created
    try {
      created = await createWorkspaceSession(ctx, space.id, {
        idempotencyKey: cid(),
        message: [{ type: 'TEXT', text: 'model request debug' }],
        modelSelection: { modelName: `missing-debug-model-${cid().slice(0, 8)}`, variant: null },
      })
      // 缺失模型使初始 Turn 在调用 Provider 前确定性失败；预览仍使用独立的 UI 草稿选择。
      await waitForQuiescentThread(ctx, created.threadId, { timeoutMs: 60_000, intervalMs: 100 })
      const before = await getThreadSnapshot(ctx, created.threadId)
      const endpoint = `/api/harness/builtin/threads/${encodeURIComponent(created.threadId)}/model-request-debug`
      const { status, json } = await ctx.call('POST', endpoint, {
        model: target.model,
        environmentName: null,
      })
      assert(status === 200, `model-request-debug status ${status}: ${JSON.stringify(json)}`)
      assert(json?.kind === 'NEXT_REQUEST_PREVIEW', JSON.stringify(json))
      assert(isDeepStrictEqual(json.model, target.model), JSON.stringify(json.model))
      assert(json.environmentName === null, JSON.stringify(json))
      assert(Array.isArray(json.tools) && Array.isArray(json.skills) && Array.isArray(json.subagents), JSON.stringify(json))
      assert(json.frozenInvocation === null, JSON.stringify(json.frozenInvocation))
      await expectHttpError(() => ctx.call('POST', endpoint, { environmentName: null }), {
        status: 400,
        code: 'validation',
      })
      await expectHttpError(
        () => ctx.call('POST', endpoint, { model: target.model, environmentName: 'bad/name' }),
        { status: 400, code: 'validation' },
      )
      await expectHttpError(() => ctx.call('GET', endpoint), { status: 405, code: 'validation' })
      const after = await getThreadSnapshot(ctx, created.threadId)
      assert(
        String(after.thread.headEntryId) === String(before.thread.headEntryId)
          && String(after.thread.version) === String(before.thread.version)
          && String(after.thread.nextCommandSequence) === String(before.thread.nextCommandSequence)
          && after.queuedCommands.length === 0
          && after.activeAssistantOutput === null,
        JSON.stringify({ before: before.thread, after: after.thread }),
      )
    } finally {
      if (created) await deleteWorkspaceSession(ctx, space.id, created.sessionId)
    }
  },
})

registerCase({
  id: 'thread.workspace_session_branch_fork',
  level: 'L1',
  title: 'Chat Space Session、分支与分叉使用当前 API',
  docs: '经 Chat Space 创建 Session；Harness Thread 查询继续使用 /api/harness/threads/{id}；从 ROOT 切点经 Workspace branches/forks 创建分支与新 Session，不调用模型',
  async run(ctx) {
    const spaceId = await ctx.createSpace(`e2e-session-${cid().slice(0, 8)}`)
    const created = await createWorkspaceSession(ctx, spaceId, {
      idempotencyKey: cid(),
      message: [{ type: 'TEXT', text: `workspace session ${cid().slice(0, 8)}` }],
      modelSelection: { modelName: `missing-e2e-model-${cid().slice(0, 8)}`, variant: 'default' },
    })
    const snapshot = await getThreadSnapshot(ctx, created.threadId)
    assert(snapshot.thread.sessionId === created.sessionId, JSON.stringify(snapshot.thread))
    await waitForQuiescentThread(ctx, created.threadId, { timeoutMs: 60_000 })
    const settled = await getThreadSnapshot(ctx, created.threadId)
    const turnEnd = settled.entries.filter((entry) => entry.entryType === 'TURN_END').at(-1)
    assert(turnEnd, `Session must settle at a TURN_END before branching: ${JSON.stringify(settled.entries)}`)
    const branch = await createWorkspaceBranch(ctx, spaceId, created.sessionId, {
      idempotencyKey: cid(), sourceThreadId: created.threadId, turnEndEntryId: turnEnd.entryId, title: 'E2E branch',
    })
    assert(branch.sessionId === created.sessionId && branch.threadId, JSON.stringify(branch))
    const fork = await createWorkspaceFork(ctx, spaceId, created.sessionId, {
      idempotencyKey: cid(), sourceThreadId: created.threadId, turnEndEntryId: turnEnd.entryId, title: 'E2E fork',
    })
    assert(fork.sessionId !== created.sessionId && fork.threadId, JSON.stringify(fork))
    let tree = (await ctx.call('GET', `/api/chat-spaces/${spaceId}/tree`)).json
    await ctx.call('DELETE', `/api/chat-spaces/${spaceId}/sessions/${fork.sessionId}?expectedTreeVersion=${encodeURIComponent(tree.treeVersion)}`)
    tree = (await ctx.call('GET', `/api/chat-spaces/${spaceId}/tree`)).json
    await ctx.call('DELETE', `/api/chat-spaces/${spaceId}/sessions/${created.sessionId}?expectedTreeVersion=${encodeURIComponent(tree.treeVersion)}`)
  },
})

registerCase({
  id: 'frontend.proxy_model_contract',
  level: 'L1',
  title: 'Frontend 代理 model 契约',
  docs: '5173 /api/platform/models 返回 name-based 结构化 config',
  async run(ctx) {
    assert(ctx.vars.frontendUrl, 'frontendUrl required')
    const { json } = await httpJson(ctx.vars.frontendUrl, 'GET', '/api/platform/models')
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

/** Free control-plane cases need only one catalog-resolvable builtin Agent and Model. */
async function resolveAnyCatalogTarget(ctx) {
  const agents = listResults((await ctx.call('GET', '/api/platform/agents')).json)
  const models = listResults((await ctx.call('GET', '/api/platform/models')).json)
  for (const agent of agents) {
    if (agent.execution?.driver !== 'builtin') continue
    const { modelName, variant } = agent.execution.settings || {}
    if (!modelName || (variant !== null && typeof variant !== 'string')) continue
    if (models.some((model) => model.name === modelName)) {
      return { agent, model: { modelName, variant } }
    }
  }
  throw new Error(`no resolvable Agent/Model in current Catalog: ${JSON.stringify({ agents, models })}`)
}

function compareModel(left, right) {
  return `${left.provider}/${left.name}`.localeCompare(`${right.provider}/${right.name}`)
}
