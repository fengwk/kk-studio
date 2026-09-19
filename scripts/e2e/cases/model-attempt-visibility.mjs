import { createServer } from 'node:http'

import { assert, cid, envelopeData, sleep } from '../lib/http.mjs'
import {
  acceptCommandBatch,
  branchSettingsOf,
  chatOwner,
  createChat,
  getThreadSnapshot,
  createNewSession,
  setAgentCommand,
  setModelCommand,
  stopThread,
  threadTarget,
  userMessageCommand,
  waitForModelTextDeltaAfterEventSubscribed,
  waitForQuiescentThread,
} from '../lib/harness.mjs'
import { baseModelConfig } from '../lib/fixtures.mjs'
import { registerCase } from '../lib/registry.mjs'

const PARTIAL_TEXT = 'E2E_FAILED_ATTEMPT_PARTIAL'
const RECOVERED_TEXT = 'E2E_RECOVERED_ASSISTANT'
const SECOND_TURN_TEXT = 'E2E_SECOND_TURN_ASSISTANT'

registerCase({
  id: 'model.attempt_failure_visibility',
  level: 'L1',
  title: '模型失败 attempt partial 可见、持久恢复且不进入 Provider 上下文',
  // 本 case 把 Provider baseUrl 指向 case 内自建的宿主 127.0.0.1 mock；App 在
  // distributed 容器内无法回连宿主 loopback，因此必须排除 host-mock capability。
  requires: ['host-mock'],
  docs: '本地 node:http OpenAI Chat Completions SSE mock：先建立 /api/events/v1 Thread 订阅并收到 ack，首次 Provider attempt 在 checkpoint 窗口内输出确定性 partial 后断连，未提交尾部只冻结到 modelAttemptFailures、不作为活动 MODEL_DELTA 发布；后续自动重试成功，首条 live text 来自 attempt 2。断言 MODEL_ATTEMPT_FAILURE durable 顺序/精确 payload、失败 partial 不拼入 assistant，第二 turn 的 Provider messages 排除失败 partial/thinking/error',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const initialMarker = `ATTEMPT-VISIBILITY-FIRST-${suffix}`
    const secondMarker = `ATTEMPT-VISIBILITY-SECOND-${suffix}`
    const mock = new OpenAiAttemptMock({
      initialMarker,
      secondMarker,
      partialText: PARTIAL_TEXT,
      recoveredText: RECOVERED_TEXT,
      secondTurnText: SECOND_TURN_TEXT,
    })

    let provider = null
    let model = null
    let agent = null
    let chat = null
    let threadId = null
    let activeSnapshot = null
    let firstFinalSnapshot = null
    let secondFinalSnapshot = null
    let primaryError = null
    const cleanupErrors = []

    try {
      await mock.start()

      const providerResponse = await ctx.call('POST', '/api/ai/catalog/providers', {
        name: `e2e-provider-attempt-${suffix}`,
        description: 'Local OpenAI-compatible SSE mock for model attempt visibility.',
        providerType: 'openai',
        baseUrl: mock.baseUrl('/v1'),
        credential: `e2e-attempt-${suffix}`,
        modelCallTimeoutMillis: 30_000,
        modelCallIdleTimeoutMillis: 10_000,
      })
      provider = envelopeData(providerResponse.json)
      assert(provider?.name && provider?.version, JSON.stringify(providerResponse.json))

      const modelResponse = await ctx.call('POST', '/api/ai/catalog/models', {
        providerName: provider.name,
        name: `e2e-model-attempt-${suffix}`,
        modelId: `wire-attempt-${suffix}`,
        description: 'Local model attempt visibility E2E model.',
        config: baseModelConfig({
          limit: { context: 4096, output: 128 },
          abilities: {
            tools: true,
            reasoning: false,
            inputModalities: ['TEXT'],
          },
          variants: [{ id: 'default' }],
        }),
      })
      model = envelopeData(modelResponse.json)
      assert(model?.providerName === provider.name && model?.name, JSON.stringify(modelResponse.json))

      const agentResponse = await ctx.call('POST', '/api/ai/catalog/agents', {
        name: `e2e-agent-attempt-${suffix}`,
        description: 'Local model attempt visibility E2E agent.',
        systemPrompt: 'Reply with the exact mock response. Do not call tools.',
        model: `${model.providerName}/${model.name}`,
        variant: 'default',
        config: { tools: [], skills: [], subagents: [], inheritParentEnvironment: true },
      })
      agent = envelopeData(agentResponse.json)
      assert(agent?.name && agent.model === `${model.providerName}/${model.name}`, JSON.stringify(agentResponse.json))

      chat = await createChat(ctx, {
        title: `e2e-attempt-visibility-${suffix}`,
        agentName: agent.name,
        yoloEnabled: false,
      })
      // 先用不存在的 Agent 确定性创建空闲 Thread（不触发 Provider），随后在已订阅事件通道后
      // 通过 THREAD batch 一次性 SET_AGENT/SET_MODEL + USER_MESSAGE 启动 mock turn。
      const sessionId = cid()
      const createdThreadId = cid()
      await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId,
        threadId: createdThreadId,
        rootSettings: branchSettingsOf(
          { name: `e2e-attempt-missing-${suffix}` },
          { providerName: model.providerName, modelName: model.name, variant: 'default' },
        ),
        yoloEnabled: false,
        commands: [userMessageCommand(`materialize ${suffix}`, cid())],
      })
      threadId = String(createdThreadId)
      const idleThread = await waitForQuiescentThread(ctx, threadId, {
        timeoutMs: 60_000,
        intervalMs: 100,
      })

      let activeFailurePromise = null
      const { signal: firstPublishedDelta, startResult } =
        await waitForModelTextDeltaAfterEventSubscribed(
          ctx,
          threadId,
          () => {
            activeFailurePromise = waitForActiveAttemptFailure(ctx, threadId, {
              initialMarker,
              partialText: PARTIAL_TEXT,
            })
            return acceptCommandBatch(ctx, {
              owner: chatOwner(chat.id),
              target: threadTarget({
                threadId,
                expectedHeadEntryId: idleThread.headEntryId,
                expectedNextCommandSequence: idleThread.nextCommandSequence,
              }),
              commands: [
                setAgentCommand(agent.name, cid()),
                setModelCommand(
                  { providerName: model.providerName, modelName: model.name, variant: 'default' },
                  cid(),
                ),
                userMessageCommand(initialMarker, cid()),
              ],
            })
          },
          { timeoutMs: 30_000 },
        )
      assert(
        Array.isArray(startResult?.acceptedCommands)
          && startResult.acceptedCommands.length === 3
          && startResult.acceptedCommands.at(-1).type === 'USER_MESSAGE',
        `initial message batch: ${JSON.stringify(startResult)}`,
      )
      assert(
        firstPublishedDelta.attempt === 2
          && firstPublishedDelta.text === RECOVERED_TEXT,
        `failed-attempt uncommitted tail must not publish before recovered attempt: ${JSON.stringify(firstPublishedDelta)}`,
      )

      assert(activeFailurePromise != null, 'active failure observation was not started')
      const activeResult = await activeFailurePromise
      activeSnapshot = activeResult.snapshot
      const activeFailure = activeSnapshot.modelAttemptFailures[0]
      assert(
        firstPublishedDelta.invocationId === activeSnapshot.modelInvocation.id,
        `recovered delta must belong to the retried invocation: ${JSON.stringify({
          firstPublishedDelta,
          modelInvocation: activeSnapshot.modelInvocation,
        })}`,
      )
      assertActiveFailure(activeSnapshot, activeFailure, {
        partialText: PARTIAL_TEXT,
        thinking: '',
      })

      firstFinalSnapshot = await waitForQuiescentSnapshot(ctx, threadId)
      assertFirstTurnDurable(firstFinalSnapshot, {
        initialMarker,
        partialText: PARTIAL_TEXT,
        thinking: activeFailure.thinking,
        failure: activeFailure,
        recoveredText: RECOVERED_TEXT,
      })

      const secondStart = await acceptCommandBatch(ctx, {
        owner: chatOwner(chat.id),
        target: threadTarget({
          threadId,
          expectedHeadEntryId: firstFinalSnapshot.thread.headEntryId,
          expectedNextCommandSequence: firstFinalSnapshot.thread.nextCommandSequence,
        }),
        commands: [userMessageCommand(secondMarker, cid())],
      })
      assert(
        secondStart.acceptedCommands.length === 1
          && secondStart.acceptedCommands[0].type === 'USER_MESSAGE',
        `second message batch: ${JSON.stringify(secondStart)}`,
      )
      secondFinalSnapshot = await waitForQuiescentSnapshot(ctx, threadId)
      assertSecondTurnDurable(secondFinalSnapshot, {
        secondMarker,
        secondTurnText: SECOND_TURN_TEXT,
      })

      const secondRequests = mock.requests.filter((request) =>
        JSON.stringify(request.body?.messages || []).includes(secondMarker),
      )
      assert(
        secondRequests.length > 0,
        `mock did not capture a second-turn Provider request: ${JSON.stringify(mock.requests)}`,
      )
      const secondMessagesJson = JSON.stringify(secondRequests.at(-1).body.messages)
      for (const forbidden of [
        PARTIAL_TEXT,
        activeFailure.thinking,
        activeFailure.errorMessage,
        activeFailure.errorCode,
      ]) {
        if (!forbidden) continue
        assert(
          !secondMessagesJson.includes(forbidden),
          `failed attempt value leaked into second-turn Provider messages: ${JSON.stringify({
            forbidden,
            messages: secondRequests.at(-1).body.messages,
          })}`,
        )
      }

      const firstTurnRequests = mock.requests.filter((request) => {
        const messages = JSON.stringify(request.body?.messages || [])
        return messages.includes(initialMarker) && !messages.includes(secondMarker)
      })
      assert(
        firstTurnRequests.some((request) => request.outcome === 'partial-disconnect'),
        `mock did not perform the partial-then-disconnect path: ${JSON.stringify(mock.requests)}`,
      )
      assert(
        firstTurnRequests.some((request) => request.outcome === 'recovered-success'),
        `mock did not observe a successful retry request: ${JSON.stringify(mock.requests)}`,
      )
      const failedRequest = firstTurnRequests.find(
        (request) => request.outcome === 'partial-disconnect',
      )
      const recoveredRequest = firstTurnRequests.find(
        (request) => request.outcome === 'recovered-success',
      )
      assert(
        JSON.stringify(recoveredRequest.body.messages)
          === JSON.stringify(failedRequest.body.messages),
        `immediate retry must reuse the exact Provider messages: ${JSON.stringify({
          failed: failedRequest.body.messages,
          recovered: recoveredRequest.body.messages,
        })}`,
      )
      for (const forbidden of [
        PARTIAL_TEXT,
        activeFailure.thinking,
        activeFailure.errorMessage,
        activeFailure.errorCode,
      ]) {
        if (!forbidden) continue
        assert(
          !JSON.stringify(recoveredRequest.body.messages).includes(forbidden),
          `failed attempt value leaked into the immediate retry: ${JSON.stringify({
            forbidden,
            messages: recoveredRequest.body.messages,
          })}`,
        )
      }

      ctx.writeArtifact(
        'attempt-visibility.json',
        JSON.stringify(
          {
            markers: { initialMarker, secondMarker },
            activeSnapshot,
            firstFinalSnapshot,
            secondFinalSnapshot,
            providerRequests: mock.requests,
          },
          null,
          2,
        ),
      )
    } catch (error) {
      primaryError = error
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
            `/api/ai/chats/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
          )
        }
      })
      await cleanup('agent', cleanupErrors, async () => {
        if (agent?.name) {
          await ctx.call(
            'DELETE',
            `/api/ai/catalog/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(agent.version)}`,
          )
        }
      })
      await cleanup('model', cleanupErrors, async () => {
        if (model?.providerName && model?.name) {
          await ctx.call(
            'DELETE',
            `/api/ai/catalog/models/${encodeURIComponent(model.providerName)}/${encodeURIComponent(model.name)}?expectedVersion=${encodeURIComponent(model.version)}`,
          )
        }
      })
      await cleanup('provider', cleanupErrors, async () => {
        if (provider?.name) {
          await ctx.call(
            'DELETE',
            `/api/ai/catalog/providers/${encodeURIComponent(provider.name)}?expectedVersion=${encodeURIComponent(provider.version)}`,
          )
        }
      })
      await cleanup('write mock artifact', cleanupErrors, () => {
        ctx.writeArtifact(
          'provider-requests.json',
          JSON.stringify(
            {
              partialText: PARTIAL_TEXT,
              recoveredText: RECOVERED_TEXT,
              secondTurnText: SECOND_TURN_TEXT,
              requests: mock.requests,
            },
            null,
            2,
          ),
        )
      })
      if (cleanupErrors.length > 0) {
        const cleanupMessage = `E2E cleanup failed: ${cleanupErrors.join(' | ')}`
        try {
          ctx.writeArtifact('cleanup-errors.txt', `${cleanupMessage}\n`)
        } catch {
          // 保留原始 cleanup 错误，不能让 artifact 写入遮蔽它。
        }
        if (primaryError == null) primaryError = new Error(cleanupMessage)
      }
    }

    if (primaryError != null) throw primaryError
  },
})

async function waitForQuiescentSnapshot(ctx, threadId) {
  await waitForQuiescentThread(ctx, threadId, {
    timeoutMs: 45_000,
    intervalMs: 100,
  })
  return getThreadSnapshot(ctx, threadId)
}

async function waitForActiveAttemptFailure(
  ctx,
  threadId,
  { initialMarker, partialText, timeoutMs = 5_000 },
) {
  const deadline = Date.now() + timeoutMs
  let lastSnapshot = null
  let polls = 0
  while (Date.now() <= deadline) {
    lastSnapshot = await getThreadSnapshot(ctx, threadId)
    polls += 1
    const failures = lastSnapshot.modelAttemptFailures || []
    if (
      lastSnapshot.modelInvocation !== null
      && failures.length === 1
      && failures[0].attempt === 1
      && failures[0].text === partialText
    ) {
      return { snapshot: lastSnapshot, polls }
    }
    await sleep(25)
  }
  throw new Error(
    `active modelAttemptFailures snapshot was not observed after ${polls} polls; ` +
      JSON.stringify({
        initialMarker,
        lastThread: lastSnapshot?.thread,
        lastModelInvocation: lastSnapshot?.modelInvocation,
        lastModelAttemptFailures: lastSnapshot?.modelAttemptFailures,
      }),
  )
}

function assertActiveFailure(snapshot, failure, { partialText, thinking }) {
  assert(snapshot.modelAttemptFailures.length === 1, JSON.stringify(snapshot.modelAttemptFailures))
  assert(snapshot.modelInvocation !== null, JSON.stringify(snapshot))
  assert(failure.attempt === 1, JSON.stringify(failure))
  assert(
    typeof failure.sequence === 'string'
      && /^(0|[1-9]\d*)$/.test(failure.sequence),
    `sequence must be an HTTP decimal string: ${JSON.stringify(failure)}`,
  )
  assert(failure.text === partialText, JSON.stringify(failure))
  assert(failure.thinking === thinking, JSON.stringify(failure))
  assert(failure.errorCode === 'TRANSIENT', JSON.stringify(failure))
  assert(
    typeof failure.errorMessage === 'string' && failure.errorMessage.trim(),
    JSON.stringify(failure),
  )
  assertInstant(failure.failedAt, 'failedAt')
  assertInstant(failure.retryAt, 'retryAt')
  assert(
    instantEpochMillis(failure.retryAt) >= instantEpochMillis(failure.failedAt),
    `retryAt must not precede failedAt: ${JSON.stringify(failure)}`,
  )
  for (const [field, value] of [
    ['modelInvocationId', failure.modelInvocationId],
    ['turnStartEntryId', failure.turnStartEntryId],
    ['requestHeadEntryId', failure.requestHeadEntryId],
  ]) {
    assertUuid(value, field)
  }
  assert(
    failure.modelInvocationId === snapshot.modelInvocation.id,
    `failure must belong to the active model invocation: ${JSON.stringify({
      failure,
      modelInvocation: snapshot.modelInvocation,
    })}`,
  )
}

function assertFirstTurnDurable(
  snapshot,
  { initialMarker, partialText, thinking, failure, recoveredText },
) {
  assert(snapshot.modelInvocation === null, JSON.stringify(snapshot))
  assert(
    snapshot.modelAttemptFailures.length === 0,
    `materialized failures must be removed from snapshot: ${JSON.stringify(snapshot.modelAttemptFailures)}`,
  )
  assert(snapshot.queuedCommands.length === 0, JSON.stringify(snapshot.queuedCommands))

  const entries = snapshot.entries
  // bootstrap turn（missing Agent PLANNING_FAILED）产生持久 ASSISTANT_ERROR + TURN_END(FAILED)，
  // 属预期；本断言只覆盖 initialMarker 之后的 mock retry turn。
  const userIndex = findUserEntryIndex(entries, initialMarker)
  assert(userIndex >= 0, JSON.stringify(entries))
  const turnEntries = entries.slice(userIndex)
  const failureEntries = turnEntries.filter((entry) => entryType(entry) === 'MODEL_ATTEMPT_FAILURE')
  assert(
    failureEntries.length === 1,
    `expected one MODEL_ATTEMPT_FAILURE after initialMarker: ${JSON.stringify(entries)}`,
  )
  const failureEntry = failureEntries[0]
  const failureIndex = turnEntries.indexOf(failureEntry)
  assert(failureIndex > 0, JSON.stringify(turnEntries))
  const failurePayload = parsePayload(failureEntry)
  assert(
    failurePayload.attempt?.attempt === 1
      && String(failurePayload.attempt.sequence) === failure.sequence
      && failurePayload.attempt.text === partialText
      && failurePayload.attempt.thinking === thinking,
    `durable attempt payload mismatch: ${JSON.stringify({ expected: failure, actual: failurePayload })}`,
  )
  assert(
    failurePayload.error?.code === failure.errorCode
      && failurePayload.error?.message === failure.errorMessage
      && instantEpochMillis(failurePayload.retryAt) === instantEpochMillis(failure.retryAt),
    `durable failure error/retryAt mismatch: ${JSON.stringify({ expected: failure, actual: failurePayload })}`,
  )
  assert(
    instantEpochMillis(failureEntry.createTime) === instantEpochMillis(failure.failedAt),
    `durable failure failedAt mismatch: ${JSON.stringify({
      entryCreateTime: failureEntry.createTime,
      failedAt: failure.failedAt,
    })}`,
  )

  const assistants = turnEntries.filter(
    (entry) => entryType(entry) === 'MESSAGE' && parsePayload(entry).message?.role === 'ASSISTANT',
  )
  assert(assistants.length === 1, `expected one recovered assistant: ${JSON.stringify(entries)}`)
  const assistant = assistants[0]
  const assistantIndex = turnEntries.indexOf(assistant)
  assert(failureIndex < assistantIndex, JSON.stringify(entries))
  assert(messageText(assistant) === recoveredText, JSON.stringify(parsePayload(assistant)))
  assert(
    !messageText(assistant).includes(partialText),
    `recovered assistant must not concatenate failed partial: ${JSON.stringify(parsePayload(assistant))}`,
  )
  assert(
    !turnEntries.some((entry) => entryType(entry) === 'ASSISTANT_ERROR'),
    `retry success must not materialize ASSISTANT_ERROR after initialMarker: ${JSON.stringify(turnEntries)}`,
  )
  const turnEnds = turnEntries.filter((entry) => entryType(entry) === 'TURN_END')
  assert(
    turnEnds.length === 1 && parsePayload(turnEnds[0]).outcome === 'COMPLETED',
    `mock turn must complete exactly once: ${JSON.stringify(turnEnds)}`,
  )
}

function assertSecondTurnDurable(snapshot, { secondMarker, secondTurnText }) {
  assert(snapshot.modelInvocation === null, JSON.stringify(snapshot))
  assert(snapshot.modelAttemptFailures.length === 0, JSON.stringify(snapshot.modelAttemptFailures))
  assert(snapshot.queuedCommands.length === 0, JSON.stringify(snapshot.queuedCommands))
  const secondUserIndex = findUserEntryIndex(snapshot.entries, secondMarker)
  assert(secondUserIndex >= 0, JSON.stringify(snapshot.entries))
  const turnEntries = snapshot.entries.slice(secondUserIndex)
  const assistants = snapshot.entries.filter(
    (entry) => entryType(entry) === 'MESSAGE' && parsePayload(entry).message?.role === 'ASSISTANT',
  )
  assert(assistants.length === 2, `expected two assistant results: ${JSON.stringify(snapshot.entries)}`)
  const secondAssistant = assistants.at(-1)
  assert(
    snapshot.entries.indexOf(secondAssistant) > secondUserIndex,
    `second assistant must follow second user: ${JSON.stringify(snapshot.entries)}`,
  )
  assert(messageText(secondAssistant) === secondTurnText, JSON.stringify(parsePayload(secondAssistant)))
  const turnEnds = turnEntries.filter((entry) => entryType(entry) === 'TURN_END')
  assert(
    turnEnds.length === 1 && parsePayload(turnEnds[0]).outcome === 'COMPLETED',
    `second turn must complete exactly once: ${JSON.stringify(turnEnds)}`,
  )
}

function entryType(entry) {
  return String(entry?.entryType || '').toUpperCase()
}

function findUserEntryIndex(entries, marker) {
  return entries.findIndex((entry) => {
    if (entryType(entry) !== 'MESSAGE') return false
    const message = parsePayload(entry).message
    return (
      message?.role === 'USER'
      && (message.contents || []).some(
        (content) => content?.type === 'text' && String(content.text || '').includes(marker),
      )
    )
  })
}

function messageText(entry) {
  return (parsePayload(entry).message?.contents || [])
    .filter((content) => content?.type === 'text')
    .map((content) => String(content.text || ''))
    .join('')
}

function parsePayload(entry) {
  const payload = JSON.parse(entry?.payloadJson || '{}')
  assert(payload && typeof payload === 'object' && !Array.isArray(payload), JSON.stringify(entry))
  return payload
}

function assertUuid(value, field) {
  assert(
    typeof value === 'string'
      && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value),
    `${field} must be a canonical UUID: ${JSON.stringify(value)}`,
  )
}

function assertInstant(value, field) {
  instantEpochMillis(value, field)
}

function instantEpochMillis(value, field = 'instant') {
  if (typeof value === 'number') {
    assert(
      Number.isFinite(value) && value >= 0,
      `${field} must be a non-negative epoch-second number: ${JSON.stringify(value)}`,
    )
    return Math.round(value * 1000)
  }
  assert(
    typeof value === 'string' && value.trim() && Number.isFinite(Date.parse(value)),
    `${field} must be an ISO instant or epoch-second number: ${JSON.stringify(value)}`,
  )
  return Date.parse(value)
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

class OpenAiAttemptMock {
  constructor({ initialMarker, secondMarker, partialText, recoveredText, secondTurnText }) {
    this.initialMarker = initialMarker
    this.secondMarker = secondMarker
    this.partialText = partialText
    this.recoveredText = recoveredText
    this.secondTurnText = secondTurnText
    this.requests = []
    this.server = createServer((request, response) => {
      void this.handle(request, response)
    })
    this.sockets = new Set()
    this.timers = new Set()
    this.listening = false
    this.partialFailureSent = false
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
    const requestRecord = {
      index: this.requests.length + 1,
      receivedAt: new Date().toISOString(),
      body,
      outcome: null,
    }
    this.requests.push(requestRecord)
    const messagesText = JSON.stringify(body.messages || [])

    if (messagesText.includes(this.secondMarker)) {
      requestRecord.outcome = 'second-turn-success'
      this.sendSuccess(response, body, this.secondTurnText, requestRecord.index)
      return
    }
    if (!messagesText.includes(this.initialMarker)) {
      requestRecord.outcome = 'unexpected-request'
      response.writeHead(400, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ error: { message: 'expected E2E marker in messages' } }))
      return
    }
    if (!this.partialFailureSent) {
      this.partialFailureSent = true
      requestRecord.outcome = 'partial-disconnect'
      this.sendPartialThenDisconnect(response, body, requestRecord.index)
      return
    }
    requestRecord.outcome = 'recovered-success'
    this.sendSuccess(response, body, this.recoveredText, requestRecord.index)
  }

  sendPartialThenDisconnect(response, body, requestIndex) {
    response.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    response.write(
      sseFrame({
        id: `e2e-attempt-${requestIndex}`,
        object: 'chat.completion.chunk',
        created: Math.floor(Date.now() / 1000),
        model: body.model || 'e2e-model',
        choices: [
          {
            index: 0,
            delta: { role: 'assistant', content: this.partialText },
            finish_reason: null,
          },
        ],
      }),
    )
    const timer = setTimeout(() => {
      this.timers.delete(timer)
      if (!response.destroyed) response.destroy()
    }, 75)
    this.timers.add(timer)
  }

  sendSuccess(response, body, text, requestIndex) {
    response.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    response.write(
      sseFrame({
        id: `e2e-success-${requestIndex}`,
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
        id: `e2e-success-final-${requestIndex}`,
        object: 'chat.completion.chunk',
        created: Math.floor(Date.now() / 1000),
        model: body.model || 'e2e-model',
        choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
      }),
    )
    response.end('data: [DONE]\n\n')
  }

  async close() {
    for (const timer of this.timers) clearTimeout(timer)
    this.timers.clear()
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
