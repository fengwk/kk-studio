/**
 * interaction.pending_input_contract：免费确定性 L1。
 *
 * 用 case 内自建的宿主 127.0.0.1 OpenAI chat-completions SSE mock 让模型的 `ask_user` ToolCall 在 READY 边界被
 * Runtime 冻结为 WAITING_INPUT（不进入审批、不经 Gateway），随后只通过统一人工交互 API 读取待处理列表并提交答案：
 * 问卷原文来自冻结 ToolCall，答案由 Runtime 校验并物化为 ToolResult。全程不调用真实 Provider。
 */
import { createServer } from 'node:http'

import { assert, assertExactFields, cid, expectHttpError, sleep } from '../lib/http.mjs'
import {
  branchSettingsOf,
  chatOwner,
  createChat,
  createNewSession,
  getThreadSnapshot,
  previewHistoricalRequest,
  stopThread,
  userMessageCommand,
  waitForQuiescentThread,
} from '../lib/harness.mjs'
import { baseModelConfig, encodePathTail } from '../lib/fixtures.mjs'
import { createCaseAdder } from '../lib/registry.mjs'

export const cases = []
const registerCase = createCaseAdder(cases)

const UUID_TEXT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

/** 人工等待条目的精确字段集合：Pane 跳转与回答所需的最小事实（判别联合的一个分支）。 */
const INPUT_FIELDS = [
  'type',
  'toolInvocationId',
  'threadId',
  'sessionId',
  'rootThreadId',
  'owner',
  'toolCallId',
  'toolName',
  'argumentsJson',
  'createdAt',
]

/** 环境等待条目的精确字段集合：只有聚合身份，没有可操作调用主键。 */
const ENVIRONMENT_WAIT_FIELDS = [
  'type',
  'rootThreadId',
  'owner',
  'environmentId',
  'environmentName',
  'waitingCount',
  'createdAt',
]

/** 按判别属性返回该条目必须精确出现的字段。 */
function interactionFields(item) {
  return item.type === 'ENVIRONMENT_WAIT' ? ENVIRONMENT_WAIT_FIELDS : INPUT_FIELDS
}

/**
 * InteractionPageDTO 精确字段集合：total 是同一过滤条件下的真实待处理总数，与当前页长度无关；
 * freshnessAt 是 required-nullable 的权威回读时刻。
 */
const INTERACTION_PAGE_FIELDS = ['items', 'nextCursor', 'total', 'freshnessAt']

const OWNER_FIELDS = ['kind', 'id', 'title', 'link']
const RECEIPT_FIELDS = [
  'threadId',
  'toolInvocationId',
  'submissionId',
  'actor',
  'acceptedAt',
  'materialized',
]

/** 服务端 Instant 是 epoch 毫秒整数；小数或负数都不是合法时刻。 */
function assertInstant(value) {
  assert(
    typeof value === 'number' && Number.isInteger(value) && value >= 0,
    `invalid epoch-millisecond instant: ${JSON.stringify(value)}`,
  )
  return value
}

function parseArguments(interaction) {
  assert(typeof interaction.argumentsJson === 'string', JSON.stringify(interaction))
  return JSON.parse(interaction.argumentsJson)
}

function assertInteraction(
  interaction,
  { threadId, rootThreadId, sessionId, chatId, chatTitle, questionnaire, label },
) {
  assertExactFields(interaction, INPUT_FIELDS, `${label} interaction`)
  assert(
    interaction.type === 'INPUT'
      && UUID_TEXT.test(interaction.toolInvocationId)
      && interaction.threadId === threadId
      && interaction.sessionId === sessionId
      // 来源 Thread 保持原始坐标；本 case 只有一条执行根，rootThreadId 必须指向它。
      && interaction.rootThreadId === rootThreadId
      && interaction.toolName === 'ask_user'
      && typeof interaction.toolCallId === 'string'
      && interaction.toolCallId.trim().length > 0
      && assertInstant(interaction.createdAt) >= 0,
    JSON.stringify(interaction),
  )
  assertExactFields(interaction.owner, OWNER_FIELDS, `${label} interaction owner`)
  assert(
    interaction.owner.kind === 'CHAT'
      && interaction.owner.id === chatId
      && interaction.owner.title === chatTitle
      && interaction.owner.link === `/chats/${chatId}`,
    JSON.stringify(interaction.owner),
  )
  assert(
    JSON.stringify(parseArguments(interaction)) === JSON.stringify(questionnaire),
    `frozen questionnaire must be the assistant ToolCall arguments: ${JSON.stringify(interaction.argumentsJson)}`,
  )
  return interaction
}

/**
 * 以 keyset 游标遍历待处理 Interaction 全集，同时校验分页不变量：页内按 createdAt 升序、跨页主键唯一、
 * 继续翻页必须给出非空游标（服务端只在源耗尽时返回 null）。
 */
async function collectInteractions(ctx, { pageSize = 100, maxPages = 20, rootThreadId = null } = {}) {
  const items = []
  const seen = new Set()
  let cursor = ''
  let total = null
  for (let page = 0; page < maxPages; page++) {
    const filter = rootThreadId ? `&rootThreadId=${encodeURIComponent(rootThreadId)}` : ''
    const query = `?limit=${pageSize}${filter}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`
    const page$ = (await ctx.call('GET', `/api/harness/interactions${query}`)).json
    // nextCursor 与 freshnessAt 是可空的可选字段：NON_NULL 序列化在空值时省略，按「省略即 null」归一后再做精确字段校验。
    assertExactFields(
      { nextCursor: null, freshnessAt: null, ...page$ },
      INTERACTION_PAGE_FIELDS,
      'interaction page',
    )
    // freshnessAt 是可选的服务端权威回读时刻：省略表示没有时间驱动的变更。
    if (page$.freshnessAt != null) {
      assertInstant(page$.freshnessAt)
    }
    assert(Array.isArray(page$.items), JSON.stringify(page$))
    assert(page$.items.length <= pageSize, JSON.stringify(page$))
    // total 是同一过滤条件下的真实待处理总数，不是首页长度：页内、跨页都必须稳定且不小于已返回条数。
    assert(Number.isInteger(page$.total) && page$.total >= page$.items.length, JSON.stringify(page$))
    assert(
      total === null || page$.total === total,
      `interaction total must stay stable across pages: ${JSON.stringify({ total, pageTotal: page$.total })}`,
    )
    if (total === null && rootThreadId === null) {
      // 未过滤时 total 与遍历出的全集必须一致（遍历上限内）。
      assert(
        page$.total >= items.length + page$.items.length,
        JSON.stringify({ total: page$.total, seen: items.length, page: page$.items.length }),
      )
    }
    total = page$.total
    for (let index = 0; index < page$.items.length; index++) {
      const interaction = page$.items[index]
      assertExactFields(interaction, interactionFields(interaction), 'interaction')
      const identity = interaction.type === 'ENVIRONMENT_WAIT'
        ? `environment:${interaction.rootThreadId}:${interaction.environmentId}`
        : `invocation:${interaction.toolInvocationId}`
      assert(!seen.has(identity), `duplicate interaction across pages: ${identity}`)
      seen.add(identity)
      if (index > 0) {
        // keyset 只保证 createdAt 不降序；同时刻的条目按代表调用 id 排序，但不是本 case 需要断言的事实。
        const order =
          assertInstant(page$.items[index - 1].createdAt) - assertInstant(interaction.createdAt)
        assert(
          order <= 0,
          `interactions must be ordered by createdAt: ${JSON.stringify([page$.items[index - 1], interaction])}`,
        )
      }
      items.push(interaction)
    }
    if (page$.nextCursor == null) {
      assert(page$.items.length < pageSize, `exhausted page must not be full: ${JSON.stringify(page$)}`)
      return items
    }
    assert(typeof page$.nextCursor === 'string' && page$.nextCursor.trim(), JSON.stringify(page$))
    cursor = page$.nextCursor
  }
  throw new Error(`interaction paging did not terminate within ${maxPages} pages`)
}

async function waitForPendingInteraction(ctx, { threadId, timeoutMs = 60_000 }) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() <= deadline) {
    const items = await collectInteractions(ctx)
    last = items
    const found = items.find((interaction) => interaction.threadId === threadId)
    if (found) {
      return found
    }
    await sleep(200)
  }
  throw new Error(`pending interaction never appeared for thread ${threadId}: ${JSON.stringify(last)}`)
}

async function waitForInteractionGone(ctx, toolInvocationId, { timeoutMs = 30_000 } = {}) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() <= deadline) {
    last = await collectInteractions(ctx)
    if (!last.some((interaction) => interaction.toolInvocationId === toolInvocationId)) {
      return
    }
    await sleep(200)
  }
  throw new Error(`interaction ${toolInvocationId} stayed pending: ${JSON.stringify(last)}`)
}

registerCase({
  id: 'interaction.pending_input_contract',
  level: 'L1',
  // 本 case 把 Provider baseUrl 指向 case 内自建的宿主 127.0.0.1 mock；distributed 容器内无法回连宿主 loopback。
  requires: ['host-mock'],
  title: '统一人工交互 API：ask_user 待处理列表、答案物化与门禁',
  docs: '本地 OpenAI SSE mock 触发内置 ask_user（Runtime 按 provenance 冻结为 WAITING_INPUT，不进审批）：GET /api/harness/interactions 返回仅含 Chat/Issue+Agent 归属的 (createdAt, invocation) 升序 keyset 分页（limit 默认 50、[1,100]、非法参数 400），页内含与分页位置无关的真实可见待处理总数 total，并携带可省略的权威回读时刻 freshnessAt（本 case 无环境 Tool 等待时省略）；可选 rootThreadId 按执行根过滤（返回该根及其后代，响应项保留来源 threadId/sessionId 并给出 rootThreadId；不存在的根 404、非法 canonical 400），非法 rootThreadId 或缺省过滤下的 total 与可见集一致；INPUT 条目是判别联合分支，只携带冻结问卷原文、调用坐标与归属（没有审批或环境聚合字段），owner 展示名取自 Chat 与执行根 Thread 的权威数据；POST /api/harness/threads/{threadId}/tool-invocations/{toolInvocationId}/input 的请求体是 ANSWER / DECLINE 联合（未知字段、缺失 submissionId/answers、DECLINE 携带答案 400；答案不满足冻结问卷 409；未知目标 409），接受后同 submissionId 精确重放 materialized=true，换 submissionId 409；回答物化为 ToolResult 后交互消失、Thread 以答案收敛',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const marker = `INTERACTION-PENDING-${suffix}`
    const questionnaire = {
      questions: [
        {
          question: 'Which resolution should the export use?',
          options: [
            { label: '720P', description: 'Faster download' },
            { label: '1080P', recommended: true },
          ],
        },
      ],
    }
    const chosenAnswer = '1080P'
    const finalText = `E2E_INTERACTION_ANSWERED ${suffix}`
    const mock = new AskUserMock({ marker, questionnaire, finalText })

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
      await mock.start()
      const providerResponse = await ctx.call('POST', '/api/platform/providers', {
        name: `e2e-provider-interaction-${suffix}`,
        description: 'Local OpenAI-compatible SSE mock for the pending input contract.',
        providerType: 'openai',
        baseUrl: mock.baseUrl('/v1'),
        credential: { operation: 'SET', value: `e2e-interaction-${suffix}` },
        modelCallTimeoutMillis: 30_000,
        modelCallIdleTimeoutMillis: 10_000,
      })
      provider = providerResponse.json
      assert(provider?.name && provider?.version, JSON.stringify(providerResponse.json))

      const modelResponse = await ctx.call('POST', '/api/platform/models', {
        type: 'MODEL',
        providerName: provider.name,
        name: `e2e-model-interaction-${suffix}`,
        modelId: `wire-interaction-${suffix}`,
        description: 'Local pending input E2E model.',
        config: baseModelConfig({
          limit: { context: 4096, output: 256 },
          abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
          variants: [{ id: 'default', promptCacheRetention: 'SHORT' }],
        }),
      })
      model = modelResponse.json
      assert(model?.providerName === provider.name, JSON.stringify(modelResponse.json))

      const agentResponse = await ctx.call('POST', '/api/platform/agents', {
        name: `e2e-agent-interaction-${suffix}`,
        description: 'Local pending input E2E agent.',
        systemPrompt: 'Ask the user with ask_user, then report the accepted answer.',
        execution: { driver: 'builtin', settings: { modelName: model.name, variant: 'default' } },
        // ask_user 是内置 SELECTABLE 工具：只有显式声明的 Agent 才把问卷工具暴露给模型。
        config: { tools: ['ask_user'], skills: [], subagents: [] },
      })
      agent = agentResponse.json
      assert(agent?.name, JSON.stringify(agentResponse.json))

      chat = await createChat(ctx, {
        title: `e2e-interaction-${suffix}`,
        agentName: agent.name,
        yoloEnabled: false,
      })

      // 分页参数边界：limit 必须落在 [1,100]，cursor 必须是服务端给出的 keyset。
      for (const query of ['?limit=0', '?limit=101', '?limit=abc', '?cursor=not-a-cursor']) {
        await expectHttpError(() => ctx.call('GET', `/api/harness/interactions${query}`), {
          status: 400,
          code: 'validation',
        })
      }

      const sessionId = cid()
      const createdThreadId = cid()
      await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId,
        threadId: createdThreadId,
        rootSettings: branchSettingsOf(agent, {
          ...agent.execution.settings,
        }),
        yoloEnabled: false,
        commands: [userMessageCommand(marker, cid())],
      })
      threadId = String(createdThreadId)

      const pending = assertInteraction(
        await waitForPendingInteraction(ctx, { threadId }),
        {
          threadId,
          rootThreadId: threadId,
          sessionId: String(sessionId),
          chatId: chat.id,
          chatTitle: chat.title,
          questionnaire,
          label: 'pending',
        },
      )
      assert(
        mock.requests.some((request) => request.outcome === 'tool-call'),
        `mock did not emit the ask_user ToolCall: ${JSON.stringify(mock.requests)}`,
      )

      // rootThreadId 过滤：按执行根返回该根（含后代）的待办，响应项仍保留来源 threadId/sessionId。
      const filtered = (
          await ctx.call(
            'GET',
            `/api/harness/interactions?limit=100&rootThreadId=${encodeURIComponent(threadId)}`,
          )
        ).json
      assertExactFields(
        { nextCursor: null, freshnessAt: null, ...filtered },
        INTERACTION_PAGE_FIELDS,
        'root-filtered interaction page',
      )
      // 本 case 的等待全部来自本地 ask_user，没有任何 Environment Tool 等待，因此不存在时间驱动的环境等待变更时刻。
      assert(filtered.freshnessAt == null, JSON.stringify(filtered.freshnessAt))
      assert(
        filtered.items.length === 1
          && filtered.items[0].toolInvocationId === pending.toolInvocationId
          && filtered.items[0].threadId === threadId
          && filtered.items[0].rootThreadId === threadId
          && filtered.total === 1,
        `root filter must return exactly the pending interaction of that root: ${JSON.stringify(filtered)}`,
      )
      // 过滤器身份必须是已存在的执行根：不存在的 Thread 404，非法 canonical 与非根 400。
      await expectHttpError(
        () => ctx.call('GET', `/api/harness/interactions?rootThreadId=${cid()}`),
        { status: 404, code: 'not_found' },
      )
      await expectHttpError(
        () => ctx.call('GET', '/api/harness/interactions?rootThreadId=not-a-uuid'),
        { status: 400, code: 'validation' },
      )
      // 过滤器只缩小可见集，不改变未过滤视图：无过滤时仍能看到该待办。
      assert(
        (await collectInteractions(ctx)).some(
          (interaction) => interaction.toolInvocationId === pending.toolInvocationId,
        ),
        'unfiltered view must still expose the pending interaction',
      )

      // 提交请求形状严格校验：答案/拒答联合缺失判别、未知字段、拒答携带答案都在触达领域前 400。
      const submissionId = cid()
      const inputPath =
        `/api/harness/threads/${threadId}/tool-invocations/${pending.toolInvocationId}/input`
      for (const body of [
        { submissionId, answers: [[chosenAnswer]] },
        { type: 'ANSWER', submissionId, answers: [[chosenAnswer]], actor: 'forged' },
        { type: 'DECLINE', submissionId, answers: [[chosenAnswer]] },
        { type: 'ANSWER', submissionId },
        { type: 'ANSWER', submissionId: 'not-a-uuid', answers: [[chosenAnswer]] },
      ]) {
        await expectHttpError(() => ctx.call('POST', inputPath, body), { status: 400, code: 'validation' })
      }
      // 未知目标与不满足冻结问卷的答案由 Runtime 判定 409，且不写入任何事实。
      await expectHttpError(
        () =>
          ctx.call(
            'POST',
            `/api/harness/threads/${threadId}/tool-invocations/${cid()}/input`,
            { type: 'ANSWER', submissionId: cid(), answers: [[chosenAnswer]] },
          ),
        { status: 409, code: 'conflict', reason: 'INPUT_SUBMISSION_NOT_APPLICABLE' },
      )
      await expectHttpError(
        () =>
          ctx.call('POST', inputPath, {
            type: 'ANSWER',
            submissionId: cid(),
            answers: [['720P', '1080P']],
          }),
        { status: 409, code: 'conflict', reason: 'INPUT_SUBMISSION_INVALID' },
      )
      const stillPending = await waitForPendingInteraction(ctx, { threadId })
      assert(
        stillPending.toolInvocationId === pending.toolInvocationId,
        JSON.stringify(stillPending),
      )

      // 接受答案：回执只报告服务端事实，操作者身份来自部署边界。
      const accepted = await ctx.call('POST', inputPath, {
        type: 'ANSWER',
        submissionId,
        answers: [[chosenAnswer]],
      })
      assert(accepted.status === 200, `submit input status ${accepted.status}`)
      const receipt = accepted.json
      assertExactFields(receipt, RECEIPT_FIELDS, 'input receipt')
      assert(
        receipt.threadId === threadId
          && receipt.toolInvocationId === pending.toolInvocationId
          && receipt.submissionId === submissionId
          && typeof receipt.actor === 'string'
          && receipt.actor.trim().length > 0
          && assertInstant(receipt.acceptedAt) >= 0
          && receipt.materialized === false,
        JSON.stringify(receipt),
      )

      // 回答先物化为 ToolResult，再检查已物化事实的同 submissionId 精确重放。
      // 立即重放可能仍处于 accepted-but-not-materialized，不用时序猜测代替门禁。
      await waitForInteractionGone(ctx, pending.toolInvocationId)
      // 接受答案即移出待办；必须等 Thread 收敛，才可断言 ToolResult 已持久化。
      await waitForQuiescentThread(ctx, threadId, {
        timeoutMs: 60_000,
        intervalMs: 200,
      })
      const replayed = (
          await ctx.call('POST', inputPath, {
            type: 'ANSWER',
            submissionId,
            answers: [[chosenAnswer]],
          })
        ).json
      assertExactFields(replayed, RECEIPT_FIELDS, 'replayed receipt')
      assert(
        replayed.submissionId === submissionId
          && replayed.acceptedAt === receipt.acceptedAt
          && replayed.materialized === true,
        JSON.stringify(replayed),
      )
      // 另一个提交身份不得改写已接受事实。
      await expectHttpError(
        () =>
          ctx.call('POST', inputPath, {
            type: 'ANSWER',
            submissionId: cid(),
            answers: [[chosenAnswer]],
          }),
        { status: 409, code: 'conflict', reason: 'INPUT_SUBMISSION_MISMATCH' },
      )

      // 回答物化后 Thread 以 ToolResult 收敛到终态。
      const thread = await waitForQuiescentThread(ctx, threadId, {
        timeoutMs: 60_000,
        intervalMs: 200,
      })
      assert(thread.status === 'IDLE', JSON.stringify(thread))
      const snapshot = await getThreadSnapshot(ctx, threadId)
      const entries = snapshot.entries || []
      const resultEntry = entries.find((entry) => {
        if (String(entry?.entryType || '').toUpperCase() !== 'MESSAGE') {
          return false
        }
        const payload = entry.payload ?? {}
        return JSON.stringify(payload).includes(pending.toolInvocationId)
      })
      assert(
        resultEntry,
        `materialized ToolResult for ${pending.toolInvocationId} not found: ${JSON.stringify(entries.map((entry) => entry.entryType))}`,
      )
      // 历史请求预览：以真实模型输出为锚点复现「该输出之前的请求前缀」，必须恒为只读且声明是重建而非原始字节。
      const assistantOutput = entries
        .filter((entry) => String(entry?.entryType || '').toUpperCase() === 'MESSAGE')
        .map((entry) => ({ entry, payload: entry.payload ?? {} }))
        .find(({ payload }) => payload?.message?.role === 'ASSISTANT' && payload?.assistantMetadata)
      assert(
        assistantOutput,
        `expected a durable assistant model output: ${JSON.stringify(entries.map((entry) => entry.entryType))}`,
      )
      const historical = await previewHistoricalRequest(ctx, sessionId, String(assistantOutput.entry.entryId))
      assert(
        historical.sourceHeadEntryId === String(assistantOutput.entry.parentEntryId),
        `historical preview prefix head must be the output parent: ${JSON.stringify(historical)}`,
      )
      // 历史预览是只读面：不写 Entry、不推进 cursor。
      assert(
        JSON.stringify((await getThreadSnapshot(ctx, threadId)).entries) === JSON.stringify(entries),
        'historical preview must not write any Entry',
      )

      const finalRequest = mock.requests.filter((request) => request.outcome === 'final').at(-1)
      assert(
        finalRequest && JSON.stringify(finalRequest.body.messages).includes(chosenAnswer),
        `accepted answer must be delivered to the Provider: ${JSON.stringify(mock.requests)}`,
      )
      assert(
        JSON.stringify(entries).includes(finalText),
        `final assistant text not durable: ${JSON.stringify(entries.length)}`,
      )
      ctx.writeArtifact(
        'interaction-pending-input.json',
        JSON.stringify({ pending, receipt, replayed, entries, providerRequests: mock.requests }, null, 2),
      )
    } catch (error) {
      primaryError = error
    } finally {
      await cleanup('stop thread', async () => {
        if (!threadId) return
        const snapshot = await getThreadSnapshot(ctx, threadId)
        if (
          snapshot.thread.status !== 'IDLE'
          || snapshot.queuedCommands.length > 0
          || snapshot.activeAssistantOutput !== null
        ) {
          await stopThread(ctx, threadId, {
            stopRequestId: cid(),
            expectedVersion: snapshot.thread.version,
          })
        }
      })
      await cleanup('mock server', () => mock.close())
      await cleanup('chat', async () => {
        if (chat?.id) {
          await ctx.call(
            'DELETE',
            `/api/chat/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
          )
        }
      })
      await cleanup('agent', async () => {
        if (agent?.name) {
          await ctx.call(
            'DELETE',
            `/api/platform/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(agent.version)}`,
          )
        }
      })
      await cleanup('model', async () => {
        if (model?.name) {
          await ctx.call(
            'DELETE',
            `/api/platform/models/${encodePathTail(model.name)}?expectedVersion=${encodeURIComponent(model.version)}`,
          )
        }
      })
      await cleanup('provider', async () => {
        if (provider?.name) {
          await ctx.call(
            'DELETE',
            `/api/platform/providers/${encodeURIComponent(provider.name)}?expectedVersion=${encodeURIComponent(provider.version)}`,
          )
        }
      })
      if (cleanupErrors.length > 0) {
        const message = `E2E cleanup failed: ${cleanupErrors.join(' | ')}`
        try {
          ctx.writeArtifact('cleanup-errors.txt', `${message}\n`)
        } catch {
          // 诊断写入失败不得遮蔽主错误。
        }
        if (primaryError == null) primaryError = new Error(message)
      }
    }

    if (primaryError != null) throw primaryError
  },
})

function sseFrame(payload) {
  return `data: ${JSON.stringify(payload)}\n\n`
}

/** 只在本 case 生命周期内监听 127.0.0.1 的 OpenAI chat-completions SSE mock：先回 ask_user ToolCall，再回终态文本。 */
class AskUserMock {
  constructor({ marker, questionnaire, finalText }) {
    this.marker = marker
    this.questionnaire = questionnaire
    this.finalText = finalText
    this.requests = []
    this.sockets = new Set()
    this.server = createServer((request, response) => {
      void this.handle(request, response)
    })
    this.server.on('connection', (socket) => {
      this.sockets.add(socket)
      socket.once('close', () => this.sockets.delete(socket))
    })
    this.listening = false
    this.base = null
    this.toolCallSent = false
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
    assert(address && typeof address === 'object', 'mock server did not bind an address')
    this.base = `http://127.0.0.1:${address.port}`
    this.listening = true
  }

  baseUrl(suffix = '') {
    assert(this.base, 'mock server is not started')
    return `${this.base}${suffix}`
  }

  async handle(request, response) {
    if (request.method !== 'POST' || request.url !== '/v1/chat/completions') {
      response.writeHead(request.method === 'POST' ? 404 : 405, { Connection: 'close' })
      response.end()
      return
    }
    const body = JSON.parse(await readRequestBody(request))
    const messages = JSON.stringify(body.messages || [])
    const record = { index: this.requests.length + 1, body, outcome: null }
    this.requests.push(record)
    if (!messages.includes(this.marker)) {
      record.outcome = 'unexpected-request'
      response.writeHead(400, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ error: { message: 'expected E2E marker in messages' } }))
      return
    }
    if (!this.toolCallSent) {
      this.toolCallSent = true
      record.outcome = 'tool-call'
      this.sendToolCall(response, body, record.index)
      return
    }
    record.outcome = 'final'
    this.sendText(response, body, this.finalText, record.index)
  }

  sendToolCall(response, body, requestIndex) {
    response.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    response.write(
      sseFrame({
        id: `e2e-ask-${requestIndex}`,
        object: 'chat.completion.chunk',
        created: Math.floor(Date.now() / 1000),
        model: body.model || 'e2e-model',
        choices: [
          {
            index: 0,
            delta: {
              role: 'assistant',
              tool_calls: [
                {
                  index: 0,
                  id: `call_${requestIndex}`,
                  type: 'function',
                  function: {
                    name: 'ask_user',
                    arguments: JSON.stringify(this.questionnaire),
                  },
                },
              ],
            },
            finish_reason: null,
          },
        ],
      }),
    )
    response.write(
      sseFrame({
        id: `e2e-ask-final-${requestIndex}`,
        object: 'chat.completion.chunk',
        created: Math.floor(Date.now() / 1000),
        model: body.model || 'e2e-model',
        choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }],
      }),
    )
    response.write(
      sseFrame({
        id: `e2e-ask-usage-${requestIndex}`,
        object: 'chat.completion.chunk',
        created: Math.floor(Date.now() / 1000),
        model: body.model || 'e2e-model',
        usage: {
          prompt_tokens: 10,
          completion_tokens: 5,
          total_tokens: 15,
        },
      }),
    )
    response.end('data: [DONE]\n\n')
  }

  sendText(response, body, text, requestIndex) {
    response.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    response.write(
      sseFrame({
        id: `e2e-answer-${requestIndex}`,
        object: 'chat.completion.chunk',
        created: Math.floor(Date.now() / 1000),
        model: body.model || 'e2e-model',
        choices: [{ index: 0, delta: { role: 'assistant', content: text }, finish_reason: null }],
      }),
    )
    response.write(
      sseFrame({
        id: `e2e-answer-final-${requestIndex}`,
        object: 'chat.completion.chunk',
        created: Math.floor(Date.now() / 1000),
        model: body.model || 'e2e-model',
        choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
      }),
    )
    response.write(
      sseFrame({
        id: `e2e-answer-usage-${requestIndex}`,
        object: 'chat.completion.chunk',
        created: Math.floor(Date.now() / 1000),
        model: body.model || 'e2e-model',
        usage: {
          prompt_tokens: 10,
          completion_tokens: 5,
          total_tokens: 15,
        },
      }),
    )
    response.end('data: [DONE]\n\n')
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

async function readRequestBody(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  return Buffer.concat(chunks).toString('utf8')
}
