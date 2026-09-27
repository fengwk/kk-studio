/**
 * interaction.pending_input_contract：免费确定性 L1。
 *
 * 用 case 内自建的宿主 127.0.0.1 OpenAI chat-completions SSE mock 让模型的 `ask_user` ToolCall 在 READY 边界被
 * Runtime 冻结为 WAITING_INPUT（不进入审批、不经 Gateway），随后只通过统一人工交互 API 读取待处理列表并提交答案：
 * 问卷原文来自冻结 ToolCall，答案由 Runtime 校验并物化为 ToolResult。全程不调用真实 Provider。
 */
import { createServer } from 'node:http'

import { assert, assertExactFields, cid, envelopeData, expectHttpError, sleep } from '../lib/http.mjs'
import {
  branchSettingsOf,
  chatOwner,
  createChat,
  createNewSession,
  getThreadSnapshot,
  stopThread,
  userMessageCommand,
  waitForQuiescentThread,
} from '../lib/harness.mjs'
import { baseModelConfig } from '../lib/fixtures.mjs'
import { registerCase } from '../lib/registry.mjs'

const UUID_TEXT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

/** InteractionDTO 精确字段集合：Pane 跳转与回答所需的最小事实。 */
const INTERACTION_FIELDS = [
  'interactionId',
  'status',
  'threadId',
  'sessionId',
  'owner',
  'toolCallId',
  'toolName',
  'argumentsJson',
  'approvalJson',
  'createTime',
]

const OWNER_FIELDS = ['type', 'chatId', 'issueId', 'agentName']
const RECEIPT_FIELDS = ['threadId', 'interactionId', 'submissionId', 'actor', 'acceptedAt', 'materialized']

function instantMillis(value) {
  if (typeof value === 'number') {
    assert(Number.isFinite(value) && value >= 0, `invalid epoch-second instant: ${value}`)
    return value * 1000
  }
  assert(
    typeof value === 'string' && Number.isFinite(Date.parse(value)),
    `invalid ISO instant: ${JSON.stringify(value)}`,
  )
  return Date.parse(value)
}

function parseArguments(interaction) {
  assert(typeof interaction.argumentsJson === 'string', JSON.stringify(interaction))
  return JSON.parse(interaction.argumentsJson)
}

function assertInteraction(interaction, { threadId, sessionId, chatId, questionnaire, label }) {
  assertExactFields(interaction, INTERACTION_FIELDS, `${label} interaction`)
  assert(
    UUID_TEXT.test(interaction.interactionId)
      && interaction.status === 'WAITING_INPUT'
      && interaction.threadId === threadId
      && interaction.sessionId === sessionId
      && interaction.toolName === 'ask_user'
      && typeof interaction.toolCallId === 'string'
      && interaction.toolCallId.trim().length > 0
      && interaction.approvalJson === null
      && instantMillis(interaction.createTime) >= 0,
    JSON.stringify(interaction),
  )
  assertExactFields(interaction.owner, OWNER_FIELDS, `${label} interaction owner`)
  assert(
    interaction.owner.type === 'CHAT'
      && interaction.owner.chatId === chatId
      && interaction.owner.issueId === null
      && interaction.owner.agentName === null,
    JSON.stringify(interaction.owner),
  )
  assert(
    JSON.stringify(parseArguments(interaction)) === JSON.stringify(questionnaire),
    `frozen questionnaire must be the assistant ToolCall arguments: ${JSON.stringify(interaction.argumentsJson)}`,
  )
  return interaction
}

/**
 * 以 keyset 游标遍历待处理 Interaction 全集，同时校验分页不变量：页内按 createTime 升序、跨页 id 唯一、
 * 继续翻页必须给出非空游标（服务端只在源耗尽时返回 null）。
 */
async function collectInteractions(ctx, { pageSize = 100, maxPages = 20 } = {}) {
  const items = []
  const seen = new Set()
  let cursor = ''
  for (let page = 0; page < maxPages; page++) {
    const query = `?limit=${pageSize}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`
    const page$ = envelopeData((await ctx.call('GET', `/api/interactions${query}`)).json)
    assert(Array.isArray(page$.items), JSON.stringify(page$))
    assert(page$.items.length <= pageSize, JSON.stringify(page$))
    for (let index = 0; index < page$.items.length; index++) {
      const interaction = page$.items[index]
      assertExactFields(interaction, INTERACTION_FIELDS, 'interaction')
      assert(!seen.has(interaction.interactionId), `duplicate interaction across pages: ${interaction.interactionId}`)
      seen.add(interaction.interactionId)
      if (index > 0) {
        const previous = page$.items[index - 1]
        const order = instantMillis(previous.createTime) - instantMillis(interaction.createTime)
        assert(
          order <= 0
            || (order === 0 && previous.interactionId <= interaction.interactionId),
          `interactions must be ordered by (createTime, interactionId): ${JSON.stringify([previous, interaction])}`,
        )
      }
      items.push(interaction)
    }
    if (page$.nextCursor === null) {
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

async function waitForInteractionGone(ctx, interactionId, { timeoutMs = 30_000 } = {}) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() <= deadline) {
    last = await collectInteractions(ctx)
    if (!last.some((interaction) => interaction.interactionId === interactionId)) {
      return
    }
    await sleep(200)
  }
  throw new Error(`interaction ${interactionId} stayed pending: ${JSON.stringify(last)}`)
}

registerCase({
  id: 'interaction.pending_input_contract',
  level: 'L1',
  // 本 case 把 Provider baseUrl 指向 case 内自建的宿主 127.0.0.1 mock；distributed 容器内无法回连宿主 loopback。
  requires: ['host-mock'],
  title: '统一人工交互 API：ask_user 待处理列表、答案物化与门禁',
  docs: '本地 OpenAI SSE mock 触发内置 ask_user（Runtime 按 provenance 冻结为 WAITING_INPUT，不进审批）：GET /api/interactions 返回仅含 Chat/Issue+Agent 归属的 (createTime,interactionId) 升序 keyset 分页（limit 默认 50、[1,100]、非法参数 400）；InteractionDTO 只暴露冻结问卷原文与归属，approvalJson 为 null；POST /api/interactions/{id}/input 严格校验 threadId/submissionId/declined/answers（缺失、未知字段、拒答携带答案 400；答案不满足冻结问卷 409；未知目标 409），接受后同 submissionId 精确重放 materialized=true，换 submissionId 409；回答物化为 ToolResult 后交互消失、Thread 以答案收敛',
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
      const providerResponse = await ctx.call('POST', '/api/ai/catalog/providers', {
        name: `e2e-provider-interaction-${suffix}`,
        description: 'Local OpenAI-compatible SSE mock for the pending input contract.',
        providerType: 'openai',
        baseUrl: mock.baseUrl('/v1'),
        credential: `e2e-interaction-${suffix}`,
        modelCallTimeoutMillis: 30_000,
        modelCallIdleTimeoutMillis: 10_000,
      })
      provider = envelopeData(providerResponse.json)
      assert(provider?.name && provider?.version, JSON.stringify(providerResponse.json))

      const modelResponse = await ctx.call('POST', '/api/ai/catalog/models', {
        providerName: provider.name,
        name: `e2e-model-interaction-${suffix}`,
        modelId: `wire-interaction-${suffix}`,
        description: 'Local pending input E2E model.',
        config: baseModelConfig({
          limit: { context: 4096, output: 256 },
          abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
          variants: [{ id: 'default' }],
        }),
      })
      model = envelopeData(modelResponse.json)
      assert(model?.providerName === provider.name, JSON.stringify(modelResponse.json))

      const agentResponse = await ctx.call('POST', '/api/ai/catalog/agents', {
        name: `e2e-agent-interaction-${suffix}`,
        description: 'Local pending input E2E agent.',
        systemPrompt: 'Ask the user with ask_user, then report the accepted answer.',
        model: `${model.providerName}/${model.name}`,
        variant: 'default',
        // ask_user 是内置 SELECTABLE 工具：只有显式声明的 Agent 才把问卷工具暴露给模型。
        config: { tools: ['ask_user'], skills: [], subagents: [], inheritParentEnvironment: true },
      })
      agent = envelopeData(agentResponse.json)
      assert(agent?.name, JSON.stringify(agentResponse.json))

      chat = await createChat(ctx, {
        title: `e2e-interaction-${suffix}`,
        agentName: agent.name,
        yoloEnabled: false,
      })

      // 分页参数边界：limit 必须落在 [1,100]，cursor 必须是服务端给出的 keyset。
      for (const query of ['?limit=0', '?limit=101', '?limit=abc', '?cursor=not-a-cursor']) {
        await expectHttpError(() => ctx.call('GET', `/api/interactions${query}`), { status: 400 })
      }

      const sessionId = cid()
      const createdThreadId = cid()
      await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId,
        threadId: createdThreadId,
        rootSettings: branchSettingsOf(agent, {
          providerName: model.providerName,
          modelName: model.name,
          variant: 'default',
        }),
        yoloEnabled: false,
        commands: [userMessageCommand(marker, cid())],
      })
      threadId = String(createdThreadId)

      const pending = assertInteraction(
        await waitForPendingInteraction(ctx, { threadId }),
        { threadId, sessionId: String(sessionId), chatId: chat.id, questionnaire, label: 'pending' },
      )
      assert(
        mock.requests.some((request) => request.outcome === 'tool-call'),
        `mock did not emit the ask_user ToolCall: ${JSON.stringify(mock.requests)}`,
      )

      // 提交请求形状严格校验：缺失/未知字段、拒答携带答案都在触达领域前 400。
      const submissionId = cid()
      const inputPath = `/api/interactions/${pending.interactionId}/input`
      for (const body of [
        { submissionId, declined: false, answers: [[chosenAnswer]] },
        { threadId, submissionId, declined: false, answers: [[chosenAnswer]], actor: 'forged' },
        { threadId, submissionId, answers: [[chosenAnswer]] },
        { threadId, submissionId, declined: true, answers: [[chosenAnswer]] },
      ]) {
        await expectHttpError(() => ctx.call('POST', inputPath, body), { status: 400 })
      }
      // 未知目标与不满足冻结问卷的答案由 Runtime 判定 409，且不写入任何事实。
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/interactions/${cid()}/input`, {
            threadId,
            submissionId: cid(),
            declined: false,
            answers: [[chosenAnswer]],
          }),
        { status: 409 },
      )
      await expectHttpError(
        () =>
          ctx.call('POST', inputPath, {
            threadId,
            submissionId: cid(),
            declined: false,
            answers: [['720P', '1080P']],
          }),
        { status: 409 },
      )
      const stillPending = await waitForPendingInteraction(ctx, { threadId })
      assert(stillPending.interactionId === pending.interactionId, JSON.stringify(stillPending))

      // 接受答案：回执只报告服务端事实，操作者身份来自部署边界。
      const accepted = await ctx.call('POST', inputPath, {
        threadId,
        submissionId,
        declined: false,
        answers: [[chosenAnswer]],
      })
      assert(accepted.status === 200, `submit input status ${accepted.status}`)
      const receipt = envelopeData(accepted.json)
      assertExactFields(receipt, RECEIPT_FIELDS, 'input receipt')
      assert(
        receipt.threadId === threadId
          && receipt.interactionId === pending.interactionId
          && receipt.submissionId === submissionId
          && typeof receipt.actor === 'string'
          && receipt.actor.trim().length > 0
          && instantMillis(receipt.acceptedAt) >= 0
          && receipt.materialized === false,
        JSON.stringify(receipt),
      )

      // 回答先物化为 ToolResult，再检查已物化事实的同 submissionId 精确重放。
      // 立即重放可能仍处于 accepted-but-not-materialized，不用时序猜测代替门禁。
      await waitForInteractionGone(ctx, pending.interactionId)
      const replayed = envelopeData(
        (
          await ctx.call('POST', inputPath, {
            threadId,
            submissionId,
            declined: false,
            answers: [[chosenAnswer]],
          })
        ).json,
      )
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
            threadId,
            submissionId: cid(),
            declined: false,
            answers: [[chosenAnswer]],
          }),
        { status: 409 },
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
        const payload = JSON.parse(entry.payloadJson || '{}')
        return JSON.stringify(payload).includes(pending.interactionId)
      })
      assert(
        resultEntry,
        `materialized ToolResult for ${pending.interactionId} not found: ${JSON.stringify(entries.map((entry) => entry.entryType))}`,
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
      await cleanup('mock server', () => mock.close())
      await cleanup('chat', async () => {
        if (chat?.id) {
          await ctx.call(
            'DELETE',
            `/api/ai/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
          )
        }
      })
      await cleanup('agent', async () => {
        if (agent?.name) {
          await ctx.call(
            'DELETE',
            `/api/ai/catalog/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(agent.version)}`,
          )
        }
      })
      await cleanup('model', async () => {
        if (model?.providerName && model?.name) {
          await ctx.call(
            'DELETE',
            `/api/ai/catalog/models/${encodeURIComponent(model.providerName)}/${encodeURIComponent(model.name)}?expectedVersion=${encodeURIComponent(model.version)}`,
          )
        }
      })
      await cleanup('provider', async () => {
        if (provider?.name) {
          await ctx.call(
            'DELETE',
            `/api/ai/catalog/providers/${encodeURIComponent(provider.name)}?expectedVersion=${encodeURIComponent(provider.version)}`,
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
