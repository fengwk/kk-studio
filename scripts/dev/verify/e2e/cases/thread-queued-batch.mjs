/**
 * thread.queued_command_batch：免费确定性 L1（whole-snapshot harvest）。
 *
 * 本地受控 OpenAI chat-completions SSE hold mock 验证 Thread GET -> subscribed ACK cursor 0 -> 初始 snapshot，
 * 并验证同一 INPUT 快照按 sequence 一次性收割两批 USER。WebSocket 连续增量自行追到完成，不靠 HTTP 回读；
 * 完成后与一次 HTTP 增量核对。全程不调用真实 Provider；finally 完整清理。
 */
import { createServer } from 'node:http'

import { assert, cid, sleep } from '../lib/http.mjs'
import {
  assertExactKeys,
  takeFrame,
  waitForSocketOpen,
} from './app-events.mjs'
import {
  acceptCommandBatch,
  getThreadSnapshot,
  createWorkspaceSpace,
  createWorkspaceSession,
  deleteWorkspaceSession,
  stopThread,
  threadTarget,
  userMessageCommand,
} from '../lib/harness.mjs'
import { baseModelConfig, encodePathTail } from '../lib/fixtures.mjs'
import { createCaseAdder } from '../lib/registry.mjs'

export const cases = []
const registerCase = createCaseAdder(cases)

registerCase({
  id: 'thread.queued_command_batch',
  level: 'L1',
  title: '运行中连续两批 USER 由同一 INPUT 快照按 sequence 收割',
  // 本 case 把 Provider baseUrl 指向 case 内自建的宿主 127.0.0.1 mock；App 在
  // distributed 容器内无法回连宿主 loopback，因此必须排除 host-mock capability。
  requires: ['host-mock'],
  docs: '本地受控 OpenAI chat-completions SSE hold mock（免费确定性，不调用真实 Provider）：GET Thread 初始快照后订阅 thread 资源，验证 ACK cursor=0 与紧随其后的 snapshot；首个 USER 启动后 mock 保持响应 open，连续接受两条 THREAD batch 并确认同时 QUEUED 且 sequence 连续；release 首响应后 INPUT 一次性消费冻结 cutoff 内的完整排队快照，request#2 同时携带 first 与 second 且历史顺序 first->second；保留 realtime，连续 snapshot 从初始 head 自行追到闭合 TURN_END，无 HTTP 追更；最终用初始 afterEntryId 增量核对收到的 Entry；最终 entry 顺序 initial USER->assistant->first USER->second USER->assistant，两条 USER 同属一个 TURN_START、head 收敛到闭合 TURN_END、queuedCommands 清空',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const initialMarker = `QUEUE-INITIAL-${suffix}`
    const firstMarker = `QUEUE-FIRST-${suffix}`
    const secondMarker = `QUEUE-SECOND-${suffix}`
    const initialPrompt = `${initialMarker}\n请持续输出，直到收到我的下一条消息前不要结束。`
    const firstPrompt = `${firstMarker}\n这是排队批次的第一条消息。`
    const secondPrompt = `${secondMarker}\n结合前一条消息，只回复单词 BATCHED，不要解释。`
    const initialReply = `E2E_QUEUE_INITIAL_REPLY ${suffix}`
    const batchedReply = `E2E_QUEUE_BATCHED_REPLY ${suffix}`
    const mock = new ControlledCompletionsMock({
      initialMarker,
      firstMarker,
      secondMarker,
      initialReply,
      batchedReply,
    })
    let provider = null
    let model = null
    let agent = null
    let space = null
    let sessionId = null
    let threadId = null
    let socket = null
    let socketListener = null
    const socketFrames = []
    let initialHeadEntryId = null
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
      const providerResponse = await ctx.call('POST', '/api/platform/providers', {
        name: `e2e-provider-queue-${suffix}`,
        description: 'Local controlled hold mock for queued command batch E2E.',
        providerType: 'openai',
        baseUrl: mock.baseUrl('/v1'),
        credential: { operation: 'SET', value: `e2e-queue-${suffix}` },
        modelCallTimeoutMillis: '30000',
        modelCallIdleTimeoutMillis: '10000',
      })
      provider = providerResponse.json
      assert(provider?.name && provider?.version, JSON.stringify(providerResponse.json))
      const modelResponse = await ctx.call('POST', '/api/platform/models', {
        type: 'MODEL',
        providerName: provider.name,
        name: `e2e-model-queue-${suffix}`,
        modelId: `wire-queue-${suffix}`,
        description: 'Local queued command batch E2E model.',
        config: baseModelConfig({
          limit: { context: 4096, output: 128 },
          abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
          variants: [{ id: 'default', promptCacheRetention: 'NONE' }],
        }),
      })
      model = modelResponse.json
      assert(
        model?.providerName === provider.name && model?.name,
        JSON.stringify(modelResponse.json),
      )
      const agentResponse = await ctx.call('POST', '/api/platform/agents', {
        name: `e2e-agent-queue-${suffix}`,
        description: 'Local queued command batch E2E agent.',
        systemPrompt: 'Reply with the exact mock response. Do not call tools.',
        execution: { driver: 'builtin', settings: { modelName: model.name, variant: 'default' } },
        config: { tools: [], skills: [], subagents: [] },
      })
      agent = agentResponse.json
      assert(
        agent?.name && agent.execution?.driver === 'builtin' && agent.execution.settings?.modelName === model.name,
        JSON.stringify(agentResponse.json),
      )
      space = await createWorkspaceSpace(ctx, {
        title: `e2e-queue-batch-${suffix}`,
        defaultAgentName: agent.name,
      })
      const created = await createWorkspaceSession(ctx, space.id, {
        idempotencyKey: cid(),
        message: [{ type: 'TEXT', text: initialPrompt }],
        modelSelection: agent.execution.settings,
        yoloEnabled: false,
      })
      sessionId = created.sessionId
      threadId = String(created.threadId)
      assert(threadId && sessionId, `workspace Session was not created: ${JSON.stringify(created)}`)

      const initialSnapshot = await getThreadSnapshot(ctx, threadId)
      initialHeadEntryId = initialSnapshot.thread.headEntryId
      socket = ctx.openWebSocket('/ws/events')
      socketListener = (event) => {
        try {
          socketFrames.push(JSON.parse(String(event.data)))
        } catch {
          // Ignore non-JSON frames; protocol frames are asserted below.
        }
      }
      socket.addEventListener('message', socketListener)
      await waitForSocketOpen(socket, 5_000)
      const isThisThread = (frame) => frame?.resource?.kind === 'thread' && frame?.resource?.id === threadId
      const isSnapshot = (frame) => isThisThread(frame) && frame.type === 'event' && frame.name === 'snapshot'
      socket.send(JSON.stringify({
        type: 'subscribe',
        resource: { kind: 'thread', id: threadId },
        afterEntryId: initialHeadEntryId,
      }))
      const subscribed = await takeFrame(
        socketFrames,
        (frame) => isThisThread(frame) && ['subscribed', 'event', 'error', 'resync'].includes(frame.type),
        5_000,
      )
      assertExactKeys(subscribed, ['type', 'resource', 'cursor'], 'thread subscribed frame')
      assertExactKeys(subscribed.resource, ['kind', 'id'], 'thread subscribed resource')
      assert(subscribed.type === 'subscribed' && subscribed.resource.id === threadId && subscribed.cursor === '0',
        'Thread must acknowledge the subscription with cursor 0 before sending events')
      const pushedEntries = []
      const initialPush = await takeFrame(socketFrames, isSnapshot, 10_000)
      let pushedHead = appendSnapshotDelta(initialPush, threadId, initialHeadEntryId, pushedEntries)

      // 首个 Provider request 到达后保持 open；确认 model active 后再入队，确保运行中 cursor 有效。
      await mock.waitForRequests(1, { timeoutMs: 30_000 })
      assert(mock.requests[0].state === 'held', JSON.stringify(mock.requests[0]))
      const activeSnapshot = await waitForActiveModel(ctx, threadId, { timeoutMs: 30_000 })
      assert(activeSnapshot.activeAssistantOutput !== null, JSON.stringify(activeSnapshot))

      // 运行中入队两条 THREAD batch：各一条 USER_MESSAGE。cursor 在入队时推进。
      const firstQueued = await acceptCommandBatch(ctx, {
        target: threadTarget({
          threadId,
        }),
        commands: [userMessageCommand(firstPrompt, cid())],
      })
      const secondQueued = await acceptCommandBatch(ctx, {
        target: threadTarget({
          threadId,
        }),
        commands: [userMessageCommand(secondPrompt, cid())],
      })
      const queued = [...firstQueued.acceptedCommands, ...secondQueued.acceptedCommands]
      assert(queued.length === 2, JSON.stringify(queued))
      assert(
        BigInt(queued[1].sequence) === BigInt(queued[0].sequence) + 1n,
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
        queuedSnapshot.activeAssistantOutput !== null,
        `first model invocation must still be active while commands are queued: ${JSON.stringify(
          queuedSnapshot,
        )}`,
      )
      assert(
        !queuedSnapshot.entries.some((entry) => entryType(entry) === 'TURN_END'),
        `no turn may end while commands are queued: ${JSON.stringify(queuedSnapshot.entries)}`,
      )

      // release 后只消费推送，不轮询 HTTP 追到完成。无论合并多少个 dirty 信号，每个增量都必须衔接上一个 head。
      mock.releaseFirst()
      await mock.waitForRequests(2, { timeoutMs: 30_000 })
      const deadline = Date.now() + 30_000
      while (true) {
        const push = await takeFrame(socketFrames, isSnapshot, Math.max(1, deadline - Date.now()))
        pushedHead = appendSnapshotDelta(push, threadId, pushedHead, pushedEntries)
        if (push.data.thread.status === 'IDLE'
          && push.data.activeAssistantOutput === null
          && push.data.queuedCommands.length === 0
          && entryType(pushedEntries.at(-1)) === 'TURN_END'
          && findUserEntryIndex(pushedEntries, secondMarker) >= 0) break
      }
      assert(
        mock.requests.length === 2,
        `exactly two model requests must be issued for the whole snapshot: ${JSON.stringify(
          mock.requests.map((request) => request.state),
        )}`,
      )
      const realtime = await takeFrame(
        socketFrames,
        (frame) => isThisThread(frame) && frame.type === 'event' && frame.name === 'realtime',
        10_000,
      )
      assert(realtime.data && typeof realtime.data === 'object', JSON.stringify(realtime))
      const deltaResponse = await ctx.call(
        'GET',
        `/api/harness/threads/${encodeURIComponent(threadId)}?afterEntryId=${encodeURIComponent(initialHeadEntryId)}`,
      )
      const delta = deltaResponse.json
      assert(
        deltaResponse.status === 200
          && delta.baseEntryId === initialHeadEntryId
          && delta.thread.headEntryId === pushedHead
          && Array.isArray(delta.entries)
          && findUserEntryIndex(pushedEntries, firstMarker) >= 0
          && JSON.stringify(delta.entries.map((entry) => entry.entryId))
            === JSON.stringify(pushedEntries.map((entry) => entry.entryId)),
        'the push-only path must match the persisted incremental chain and include both queued messages',
      )
      finalSnapshot = await getThreadSnapshot(ctx, threadId)
      const baseIndex = finalSnapshot.entries.findIndex(
        (entry) => String(entry.entryId) === String(initialHeadEntryId),
      )
      assert(baseIndex >= 0, `initial head disappeared from current chain: ${initialHeadEntryId}`)
      assert(
        JSON.stringify(delta.entries.map((entry) => entry.entryId))
          === JSON.stringify(finalSnapshot.entries.slice(baseIndex + 1).map((entry) => entry.entryId)),
        'incremental entries must exactly continue the initial head through the current head',
      )

      // 严格断言 request#2 同时携带两条排队标记且历史顺序 first -> second：证明冻结 cutoff 内的完整快照被一次性消费。
      // 首条后截断、顺序错误或多余请求，均由 mock 拒绝。
      const secondMessagesText = JSON.stringify(mock.requests[1].body.messages)
      assert(
        secondMessagesText.includes(firstMarker) && secondMessagesText.includes(secondMarker),
        `request#2 must include both queued markers (whole-snapshot harvest): ${JSON.stringify(
          mock.requests[1].body.messages,
        )}`,
      )
      const firstIndex = secondMessagesText.indexOf(firstMarker)
      const secondIndex = secondMessagesText.indexOf(secondMarker)
      assert(
        firstIndex !== -1 && secondIndex !== -1 && firstIndex < secondIndex,
        `history order must be first -> second: ${JSON.stringify(mock.requests[1].body.messages)}`,
      )

      // 最终严格断言 entry 顺序与每个 Turn 的 assistant 文本。
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
        assistants.length === 2,
        `two INPUT turns must produce two assistants: ${JSON.stringify(entries)}`,
      )
      const assistantIndices = assistants.map((entry) =>
        entries.findIndex((e) => String(e.entryId) === String(entry.entryId)),
      )
      assert(
        initialUserIndex < assistantIndices[0]
          && assistantIndices[0] < firstUserIndex
          && firstUserIndex < secondUserIndex
          && secondUserIndex < assistantIndices[1],
        `expected initial USER -> assistant -> first USER -> second USER -> assistant: ${JSON.stringify(
          entries,
        )}`,
      )
      const assistantTexts = assistants.map((entry) => messageText(entry))
      assert(
        assistantTexts[0] === initialReply && assistantTexts[1] === batchedReply,
        `assistant replies must match per-turn mock replies: ${JSON.stringify(assistantTexts)}`,
      )
      // 两条排队 USER 必须由同一个 INPUT 快照消费：各自最近的 TURN_START 祖先都是第二个 TURN_START，且 head 收敛到闭合 TURN_END。
      const turnStarts = entries.filter((entry) => entryType(entry) === 'TURN_START')
      assert(
        turnStarts.length === 2,
        `two INPUT turns must produce two TURN_START entries: ${JSON.stringify(entries)}`,
      )
      const secondTurnStartId = String(turnStarts[1].entryId)
      assert(
        nearestTurnStartEntryId(entries, entries[firstUserIndex]) === secondTurnStartId
          && nearestTurnStartEntryId(entries, entries[secondUserIndex]) === secondTurnStartId,
        `both queued USER entries must be harvested under the same TURN_START: ${JSON.stringify(
          entries,
        )}`,
      )
      const finalHead = entries.find(
        (entry) => String(entry.entryId) === String(finalSnapshot.thread.headEntryId),
      )
      assert(
        finalHead && entryType(finalHead) === 'TURN_END',
        `closed turn must converge the head to TURN_END: ${JSON.stringify(finalSnapshot.thread)}`,
      )
      ctx.writeArtifact(
        'queued-command-batch.json',
        JSON.stringify(
          {
            markers: { initialMarker, firstMarker, secondMarker },
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
      if (socket) {
        if (socketListener) socket.removeEventListener('message', socketListener)
        socket.close(1000, 'E2E complete')
      }
      await cleanup('stop active thread', cleanupErrors, async () => {
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
      await cleanup('mock server', cleanupErrors, () => mock.close())
      await cleanup('workspace Session', cleanupErrors, async () => {
        if (space?.id && sessionId) {
          await deleteWorkspaceSession(ctx, space.id, sessionId)
        }
      })
      await cleanup('agent', cleanupErrors, async () => {
        if (agent?.name) {
          await ctx.call(
            'DELETE',
            `/api/platform/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(
              agent.version,
            )}`,
          )
        }
      })
      await cleanup('model', cleanupErrors, async () => {
        if (model?.name) {
          await ctx.call(
            'DELETE',
            `/api/platform/models/${encodePathTail(model.name)}?expectedVersion=${encodeURIComponent(
              model.version,
            )}`,
          )
        }
      })
      await cleanup('provider', cleanupErrors, async () => {
        if (provider?.name) {
          await ctx.call(
            'DELETE',
            `/api/platform/providers/${encodeURIComponent(
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
    if (last.activeAssistantOutput !== null) return last
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
  response.write(
    sseFrame({
      id: `e2e-queue-usage-${requestIndex}`,
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

/**
 * 本地受控 OpenAI chat-completions SSE hold mock（whole-snapshot harvest）：
 * - request#1（含 initialMarker）到达后保存 response 并保持 open，releaseFirst 后返回 initialReply；
 * - request#2（含 firstMarker 与 secondMarker，且顺序 first -> second）到达后自动返回 batchedReply；
 * - 其它请求（含第一条后截断、顺序颠倒或第 3 个请求）确定性 400，用来暴露错误收割语义。
 */
export class ControlledCompletionsMock {
  constructor({ initialMarker, firstMarker, secondMarker, initialReply, batchedReply }) {
    this.initialMarker = initialMarker
    this.firstMarker = firstMarker
    this.secondMarker = secondMarker
    this.initialReply = initialReply
    this.batchedReply = batchedReply
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
    if (this.requests.length === 2 && messagesText.includes(this.firstMarker) && messagesText.includes(this.secondMarker)) {
      // request#2：同一 INPUT 快照携带两条排队标记，历史顺序 first -> second。
      const firstIndex = messagesText.indexOf(this.firstMarker)
      const secondIndex = messagesText.indexOf(this.secondMarker)
      if (firstIndex < secondIndex) {
        record.state = 'completed'
        sendCompletionsSuccess(response, body, this.batchedReply, record.index)
        return
      }
    }
    record.state = 'rejected'
    response.writeHead(400, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ error: { message: 'unexpected mock request' } }))
  }

  /** 返回 request#1 的 SSE success（initialReply），触发对排队快照的收割。 */
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

function appendSnapshotDelta(frame, threadId, expectedBase, entries) {
  assertExactKeys(frame, ['type', 'resource', 'name', 'data'], 'thread snapshot frame')
  assertExactKeys(frame.resource, ['kind', 'id'], 'thread snapshot resource')
  const snapshot = frame.data
  assert(snapshot?.thread?.threadId === threadId && snapshot.baseEntryId === expectedBase,
    `snapshot must continue from ${expectedBase}; got base ${snapshot?.baseEntryId}`)
  let head = expectedBase
  for (const entry of snapshot.entries) {
    assert(entry.parentEntryId === head, `Entry ${entry.entryId} must extend ${head}`)
    entries.push(entry)
    head = entry.entryId
  }
  assert(snapshot.thread.headEntryId === head, 'snapshot entries must end at the advertised head')
  return head
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

/** 沿 parentEntryId 链回溯，返回该 Entry 最近的 TURN_START 祖先 id；无则 null。 */
function nearestTurnStartEntryId(entries, entry) {
  const byId = new Map(entries.map((candidate) => [String(candidate.entryId), candidate]))
  let current = entry
  while (current) {
    if (entryType(current) === 'TURN_START') return String(current.entryId)
    const parentId = current.parentEntryId
    current = parentId == null ? null : byId.get(String(parentId)) ?? null
  }
  return null
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
    const payload = entry?.payload ?? {}
    assert(
      payload && typeof payload === 'object' && !Array.isArray(payload),
      `expected entry payload object: ${JSON.stringify(entry)}`,
    )
    return payload
  } catch (error) {
    throw new Error(`invalid entry payload for ${entry?.entryId}: ${error.message}`)
  }
}
