/**
 * thread.queued_command_batch：免费确定性 L1（sequential harvest）。
 *
 * 本地受控 OpenAI chat-completions SSE hold mock 验证运行中连续两批 USER 按 sequence 逐 Turn 收割：
 * 首请求保持 open 期间连续接受两条 THREAD batch 并验证同时 QUEUED、sequence 连续；releaseFirst 后
 * 等 request count=3 + quiescent，统一断言 request#2 只新增 firstMarker、request#3 新增
 * firstMarker+secondMarker（顺序正确）；最终三个 assistant 文本分别精确等于 initialReply /
 * firstQueuedReply / secondQueuedReply，queuedCommands 清空。不调用真实 Provider；finally 完整清理。
 */
import { createServer } from 'node:http'

import { assert, cid, envelopeData, sleep } from '../lib/http.mjs'
import {
  acceptCommandBatch,
  branchSettingsOf,
  chatOwner,
  createChat,
  getThreadSnapshot,
  createNewSession,
  stopThread,
  threadTarget,
  userMessageCommand,
  waitForQuiescentThread,
} from '../lib/harness.mjs'
import { baseModelConfig } from '../lib/fixtures.mjs'
import { registerCase } from '../lib/registry.mjs'

registerCase({
  id: 'thread.queued_command_batch',
  level: 'L1',
  title: '运行中连续两批 USER 按 sequence 逐 Turn 收割',
  // 本 case 把 Provider baseUrl 指向 case 内自建的宿主 127.0.0.1 mock；App 在
  // distributed 容器内无法回连宿主 loopback，因此必须排除 host-mock capability。
  requires: ['host-mock'],
  docs: '本地受控 OpenAI chat-completions SSE hold mock（免费确定性，不调用真实 Provider）：首个 USER 启动后 mock 保持响应 open；确认 model active 后连续接受两条 THREAD batch，轮询确认两条命令同时 QUEUED 且 sequence 连续；release 首响应后 request#2 只新增 firstMarker（不得有 secondMarker），request#3 新增 secondMarker 且历史顺序 first->second；最终 entry 顺序 initial USER->assistant->first USER->assistant->second USER->assistant、queuedCommands 清空',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const initialMarker = `QUEUE-INITIAL-${suffix}`
    const firstMarker = `QUEUE-FIRST-${suffix}`
    const secondMarker = `QUEUE-SECOND-${suffix}`
    const initialPrompt = `${initialMarker}\n请持续输出，直到收到我的下一条消息前不要结束。`
    const firstPrompt = `${firstMarker}\n这是排队批次的第一条消息。`
    const secondPrompt = `${secondMarker}\n结合前一条消息，只回复单词 BATCHED，不要解释。`
    const initialReply = `E2E_QUEUE_INITIAL_REPLY ${suffix}`
    const firstQueuedReply = `E2E_QUEUE_FIRST_REPLY ${suffix}`
    const secondQueuedReply = `E2E_QUEUE_SECOND_REPLY ${suffix}`
    const mock = new ControlledCompletionsMock({
      initialMarker,
      firstMarker,
      secondMarker,
      initialReply,
      firstQueuedReply,
      secondQueuedReply,
    })
    let provider = null
    let model = null
    let agent = null
    let chat = null
    let threadId = null
    let finalSnapshot = null
    let primaryError = null
    const cleanupErrors = []
    const writeFailureArtifact = () => {
      try {
        ctx.writeArtifact(
          'queued-command-batch-failure.json',
          JSON.stringify(
            {
              error: primaryError ? String(primaryError.message || primaryError) : null,
              markers: { initialMarker, firstMarker, secondMarker },
              threadId,
              finalSnapshot: finalSnapshot || null,
              providerRequests: mock.requests,
            },
            null,
            2,
          ),
        )
      } catch {
        // 诊断写入失败不得遮蔽主错误。
      }
    }
    try {
      await mock.start()
      const providerResponse = await ctx.call('POST', '/api/ai/catalog/providers', {
        name: `e2e-provider-queue-${suffix}`,
        description: 'Local controlled hold mock for queued command batch E2E.',
        providerType: 'openai',
        baseUrl: mock.baseUrl('/v1'),
        credential: `e2e-queue-${suffix}`,
        modelCallTimeoutMillis: 30_000,
        modelCallIdleTimeoutMillis: 10_000,
      })
      provider = envelopeData(providerResponse.json)
      assert(provider?.name && provider?.version, JSON.stringify(providerResponse.json))
      const modelResponse = await ctx.call('POST', '/api/ai/catalog/models', {
        providerName: provider.name,
        name: `e2e-model-queue-${suffix}`,
        modelId: `wire-queue-${suffix}`,
        description: 'Local queued command batch E2E model.',
        config: baseModelConfig({
          limit: { context: 4096, output: 128 },
          abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
          variants: [{ id: 'default' }],
        }),
      })
      model = envelopeData(modelResponse.json)
      assert(
        model?.providerName === provider.name && model?.name,
        JSON.stringify(modelResponse.json),
      )
      const agentResponse = await ctx.call('POST', '/api/ai/catalog/agents', {
        name: `e2e-agent-queue-${suffix}`,
        description: 'Local queued command batch E2E agent.',
        systemPrompt: 'Reply with the exact mock response. Do not call tools.',
        model: `${model.providerName}/${model.name}`,
        variant: 'default',
        config: { toolIds: [], skills: [], subagents: [] },
      })
      agent = envelopeData(agentResponse.json)
      assert(
        agent?.name && agent.model === `${model.providerName}/${model.name}`,
        JSON.stringify(agentResponse.json),
      )
      chat = await createChat(ctx, {
        title: `e2e-queue-batch-${suffix}`,
        agentName: agent.name,
        yoloEnabled: false,
      })
      const sessionId = cid()
      const tid = cid()
      const created = await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId,
        threadId: tid,
        rootSettings: branchSettingsOf(agent, {
          providerName: model.providerName,
          modelName: model.name,
          variant: 'default',
        }),
        yoloEnabled: false,
        commands: [userMessageCommand(initialPrompt, cid())],
      })
      threadId = String(tid)
      assert(
        String(created.thread.threadId) === threadId
          && created.acceptedCommands.length === 1
          && created.acceptedCommands[0].type === 'USER_MESSAGE',
        `initial creation batch: ${JSON.stringify(created)}`,
      )

      // 首个 Provider request 到达后保持 open；确认 model active 后再入队，确保运行中 cursor 有效。
      await mock.waitForRequests(1, { timeoutMs: 30_000 })
      assert(mock.requests[0].state === 'held', JSON.stringify(mock.requests[0]))
      const activeSnapshot = await waitForActiveModel(ctx, threadId, { timeoutMs: 30_000 })
      assert(activeSnapshot.modelInvocation !== null, JSON.stringify(activeSnapshot))

      // 运行中入队两条 THREAD batch：各一条 USER_MESSAGE。cursor 在入队时推进。
      const running = await getThreadSnapshot(ctx, threadId)
      const firstQueued = await acceptCommandBatch(ctx, {
        owner: chatOwner(chat.id),
        target: threadTarget({
          threadId,
          expectedHeadEntryId: running.thread.headEntryId,
          expectedNextCommandSequence: running.thread.nextCommandSequence,
        }),
        commands: [userMessageCommand(firstPrompt, cid())],
      })
      const secondQueued = await acceptCommandBatch(ctx, {
        owner: chatOwner(chat.id),
        target: threadTarget({
          threadId,
          expectedHeadEntryId: firstQueued.thread.headEntryId,
          expectedNextCommandSequence: firstQueued.thread.nextCommandSequence,
        }),
        commands: [userMessageCommand(secondPrompt, cid())],
      })
      const queued = [...firstQueued.acceptedCommands, ...secondQueued.acceptedCommands]
      assert(queued.length === 2, JSON.stringify(queued))
      assert(
        Number(queued[1].sequence) === Number(queued[0].sequence) + 1,
        `queued sequences must be contiguous: ${JSON.stringify(queued)}`,
      )
      // 轮询确认两条命令同时处于 QUEUED（未开始消费、未产生 assistant）。
      const queuedSnapshot = await waitForQueuedCommandCount(ctx, threadId, 2, {
        timeoutMs: 15_000,
      })
      assert(
        (queuedSnapshot.queuedCommands || []).length === 2,
        `expected two queued commands: ${JSON.stringify(queuedSnapshot.queuedCommands)}`,
      )
      assert(
        queuedSnapshot.modelInvocation !== null,
        `first model invocation must still be active while commands are queued: ${JSON.stringify(
          queuedSnapshot,
        )}`,
      )
      assert(
        !queuedSnapshot.entries.some((entry) => entryType(entry) === 'TURN_END'),
        `no turn may end while commands are queued: ${JSON.stringify(queuedSnapshot.entries)}`,
      )

      // release 首响应：request#2/#3 由 mock 自动 SSE success（逐 Turn 收割）。等全部三个请求到达并 quiescent。
      mock.releaseFirst()
      await mock.waitForRequests(3, { timeoutMs: 30_000 })
      const finalThread = await waitForQuiescentThread(ctx, threadId, {
        timeoutMs: 60_000,
        intervalMs: 100,
      })
      assert(finalThread.status === 'IDLE', JSON.stringify(finalThread))
      finalSnapshot = await getThreadSnapshot(ctx, threadId)

      // 统一检查 request#2 只新增 firstMarker、request#3 新增 firstMarker+secondMarker（顺序正确）。
      const secondMessagesText = JSON.stringify(mock.requests[1].body.messages)
      assert(
        secondMessagesText.includes(firstMarker),
        `request#2 must include firstMarker: ${JSON.stringify(mock.requests[1].body.messages)}`,
      )
      assert(
        !secondMessagesText.includes(secondMarker),
        `request#2 must not include secondMarker (sequential harvest): ${JSON.stringify(
          mock.requests[1].body.messages,
        )}`,
      )
      const thirdMessagesText = JSON.stringify(mock.requests[2].body.messages)
      assert(
        thirdMessagesText.includes(firstMarker) && thirdMessagesText.includes(secondMarker),
        `request#3 must include firstMarker and secondMarker: ${JSON.stringify(
          mock.requests[2].body.messages,
        )}`,
      )
      const firstIndex = thirdMessagesText.indexOf(firstMarker)
      const secondIndex = thirdMessagesText.indexOf(secondMarker)
      assert(
        firstIndex !== -1 && secondIndex !== -1 && firstIndex < secondIndex,
        `history order must be first -> second: ${JSON.stringify(mock.requests[2].body.messages)}`,
      )

      // 最终严格断言 entry 顺序与逐 Turn assistant 文本。
      const entries = finalSnapshot.entries || []
      assert(
        (finalSnapshot.queuedCommands || []).length === 0,
        `queued commands must be consumed: ${JSON.stringify(finalSnapshot.queuedCommands)}`,
      )
      const initialUserIndex = findUserEntryIndex(entries, initialMarker)
      const firstUserIndex = findUserEntryIndex(entries, firstMarker)
      const secondUserIndex = findUserEntryIndex(entries, secondMarker)
      const assistants = normalAssistantEntries(entries)
      assert(
        initialUserIndex >= 0 && firstUserIndex >= 0 && secondUserIndex >= 0,
        `USER entries missing: ${JSON.stringify(entries)}`,
      )
      assert(
        initialUserIndex < firstUserIndex && firstUserIndex < secondUserIndex,
        `USER entries must be ordered initial -> first -> second: ${JSON.stringify(entries)}`,
      )
      assert(
        assistants.length === 3,
        `three turns must produce three assistants: ${JSON.stringify(entries)}`,
      )
      const assistantIndices = assistants.map((entry) =>
        entries.findIndex((e) => String(e.entryId) === String(entry.entryId)),
      )
      assert(
        initialUserIndex < assistantIndices[0]
          && assistantIndices[0] < firstUserIndex
          && firstUserIndex < assistantIndices[1]
          && assistantIndices[1] < secondUserIndex
          && secondUserIndex < assistantIndices[2],
        `expected initial USER -> assistant -> first USER -> assistant -> second USER -> assistant: ${JSON.stringify(
          entries,
        )}`,
      )
      const assistantTexts = assistants.map((entry) => messageText(entry))
      assert(
        assistantTexts[0] === initialReply
          && assistantTexts[1] === firstQueuedReply
          && assistantTexts[2] === secondQueuedReply,
        `assistant replies must match per-turn mock replies: ${JSON.stringify(assistantTexts)}`,
      )
      ctx.writeArtifact(
        'queued-command-batch.json',
        JSON.stringify(
          {
            markers: { initialMarker, firstMarker, secondMarker },
            finalThread,
            finalSnapshot,
            providerRequests: mock.requests,
          },
          null,
          2,
        ),
      )
    } catch (error) {
      primaryError = error
      writeFailureArtifact()
    } finally {
      await cleanup('stop active thread', cleanupErrors, async () => {
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
      await cleanup('mock server', cleanupErrors, () => mock.close())
      await cleanup('chat', cleanupErrors, async () => {
        if (chat?.id) {
          await ctx.call(
            'DELETE',
            `/api/ai/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(
              chat.version,
            )}`,
          )
        }
      })
      await cleanup('agent', cleanupErrors, async () => {
        if (agent?.name) {
          await ctx.call(
            'DELETE',
            `/api/ai/catalog/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(
              agent.version,
            )}`,
          )
        }
      })
      await cleanup('model', cleanupErrors, async () => {
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
      await cleanup('provider', cleanupErrors, async () => {
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

/** 轮询快照直到 Thread 处于活跃 model invocation（用于运行中入队前置校验）。 */
async function waitForActiveModel(ctx, threadId, { timeoutMs = 30_000 }) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() <= deadline) {
    last = await getThreadSnapshot(ctx, threadId)
    if (last.modelInvocation !== null) return last
    await sleep(100)
  }
  throw new Error(
    `thread never reached active model invocation: ${JSON.stringify(last?.thread)}`,
  )
}

/** 轮询快照直到 queuedCommands 数量达到期望值（用于确认命令确实处于 QUEUED 而非被立即消费）。 */
async function waitForQueuedCommandCount(ctx, threadId, count, { timeoutMs = 15_000 }) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() <= deadline) {
    last = await getThreadSnapshot(ctx, threadId)
    if ((last.queuedCommands || []).length >= count) return last
    await sleep(100)
  }
  throw new Error(
    `queued commands did not reach ${count}: ${JSON.stringify({
      lastQueued: last?.queuedCommands,
      lastThread: last?.thread,
    })}`,
  )
}

async function cleanup(label, errors, action) {
  try {
    await action()
  } catch (error) {
    errors.push(`${label}: ${error?.message || String(error)}`)
  }
}

function sseFrame(payload) {
  return `data: ${JSON.stringify(payload)}\n\n`
}

function sendCompletionsSuccess(response, body, text, requestIndex) {
  response.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  })
  response.write(
    sseFrame({
      id: `e2e-queue-${requestIndex}`,
      object: 'chat.completion.chunk',
      created: Math.floor(Date.now() / 1000),
      model: body.model || 'e2e-model',
      choices: [
        {
          index: 0,
          delta: { role: 'assistant', content: text },
          finish_reason: null,
        },
      ],
    }),
  )
  response.write(
    sseFrame({
      id: `e2e-queue-final-${requestIndex}`,
      object: 'chat.completion.chunk',
      created: Math.floor(Date.now() / 1000),
      model: body.model || 'e2e-model',
      choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
    }),
  )
  response.end('data: [DONE]\n\n')
}

/**
 * 本地受控 OpenAI chat-completions SSE hold mock（sequential harvest）：
 * - request#1（含 initialMarker）到达后保存 response 并保持 open，releaseFirst 后返回 initialReply；
 * - request#2（含 firstMarker、不含 secondMarker）到达后自动返回 firstQueuedReply；
 * - request#3（含 firstMarker+secondMarker）到达后自动返回 secondQueuedReply；
 * - 第 4 个及后续请求确定性 400。
 */
class ControlledCompletionsMock {
  constructor({ initialMarker, firstMarker, secondMarker, initialReply, firstQueuedReply, secondQueuedReply }) {
    this.initialMarker = initialMarker
    this.firstMarker = firstMarker
    this.secondMarker = secondMarker
    this.initialReply = initialReply
    this.firstQueuedReply = firstQueuedReply
    this.secondQueuedReply = secondQueuedReply
    this.requests = []
    this.firstHeld = null
    this.server = createServer((request, response) => {
      void this.handle(request, response)
    })
    this.sockets = new Set()
    this.listening = false
    this.base = null
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
    let body
    try {
      body = JSON.parse(await readRequestBody(request))
    } catch (error) {
      response.writeHead(400, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ error: { message: `invalid mock JSON: ${error.message}` } }))
      return
    }
    const record = { index: this.requests.length + 1, body, state: 'held' }
    this.requests.push(record)
    const messagesText = JSON.stringify(body.messages || [])
    if (this.requests.length === 1 && messagesText.includes(this.initialMarker)) {
      // request#1：初始 turn，保持 open 等 releaseFirst。
      this.firstHeld = response
      return
    }
    if (this.requests.length === 2 && messagesText.includes(this.firstMarker) && !messagesText.includes(this.secondMarker)) {
      // request#2：只含 firstMarker（sequential harvest），自动成功。
      record.state = 'completed'
      sendCompletionsSuccess(response, body, this.firstQueuedReply, record.index)
      return
    }
    if (this.requests.length === 3 && messagesText.includes(this.firstMarker) && messagesText.includes(this.secondMarker)) {
      // request#3：历史顺序 first -> second，自动成功。
      record.state = 'completed'
      sendCompletionsSuccess(response, body, this.secondQueuedReply, record.index)
      return
    }
    record.state = 'rejected'
    response.writeHead(400, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ error: { message: 'unexpected mock request' } }))
  }

  /** 返回 request#1 的 SSE success（initialReply），触发后续逐 Turn 收割。 */
  releaseFirst() {
    const response = this.firstHeld
    assert(response, 'releaseFirst called before the first request was held')
    this.firstHeld = null
    const record = this.requests[0]
    assert(record, 'first request record missing')
    record.state = 'completed'
    sendCompletionsSuccess(response, record.body, this.initialReply, record.index)
  }

  async waitForRequests(count, { timeoutMs = 30_000 }) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() <= deadline) {
      if (this.requests.length >= count) return
      await sleep(50)
    }
    throw new Error(
      `mock did not receive ${count} request(s) within ${timeoutMs}ms: ${JSON.stringify(
        this.requests,
      )}`,
    )
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

function entryType(entry) {
  return String(entry?.entryType || '').toUpperCase()
}

function normalAssistantEntries(entries) {
  return entries.filter((entry) => {
    if (entryType(entry) !== 'MESSAGE') return false
    return parseEntryPayload(entry).message?.role === 'ASSISTANT'
  })
}

function findUserEntryIndex(entries, marker) {
  return entries.findIndex((entry) => {
    if (entryType(entry) !== 'MESSAGE') return false
    const message = parseEntryPayload(entry).message
    if (message?.role !== 'USER' || !Array.isArray(message.contents)) return false
    return message.contents.some(
      (content) => content?.type === 'text' && String(content.text || '').includes(marker),
    )
  })
}

function messageText(entry) {
  const payload = parseEntryPayload(entry)
  return (payload.message?.contents || [])
    .filter((content) => content?.type === 'text')
    .map((content) => String(content.text || ''))
    .join('\n')
}

function parseEntryPayload(entry) {
  try {
    const payload = JSON.parse(entry?.payloadJson || '{}')
    assert(
      payload && typeof payload === 'object' && !Array.isArray(payload),
      `expected entry payload object: ${JSON.stringify(entry)}`,
    )
    return payload
  } catch (error) {
    throw new Error(`invalid entry payload for ${entry?.entryId}: ${error.message}`)
  }
}
