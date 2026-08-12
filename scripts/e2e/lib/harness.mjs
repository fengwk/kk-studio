import { assert, envelopeData, sleep } from './http.mjs'

/**
 * Harness Runtime 单轨契约 helper（Chat-scoped Thread + 唯一 Runtime 数据面）。
 *
 * 端点事实源（web 模块）：
 * - GET  /api/ai/chat/{chatId}/threads                 Chat-scoped Thread 数组（新到旧）
 * - POST /api/ai/chat/{chatId}/threads                 {title,branchSettings,yoloEnabled} → 201 snapshot
 * - GET  /api/ai/runtime/threads/{id}/snapshot         一致快照（单事务）
 * - POST /api/ai/runtime/threads/{id}/commands         命令 batch（202；clientCommandId 幂等 replay）
 * - PUT  /api/ai/runtime/threads/{id}/head             {targetEntryId,expectedRevision}
 * - POST /api/ai/runtime/threads/{id}/stop             {stopRequestId,expectedRevision}（同 id 幂等 replay）
 * - POST /api/ai/runtime/threads/{id}/tool-invocations/{toolInvocationId}/approval
 * - GET  /api/ai/runtime/threads/{id}/events/stream    快照优先 SSE
 * - GET  /api/ai/environment                           只读 Environment 注册表（name = canonical 路由身份）
 */

function positiveDecimal(value, field) {
  const raw = String(value ?? '')
  assert(/^[1-9]\d*$/.test(raw), `expected positive decimal ${field}: ${JSON.stringify(value)}`)
  return raw
}

/** 实体标识统一为 canonical UUID 字符串（Chat/Thread/Entry/Session/Invocation/Command 均如此）。 */
function canonicalUuid(value, field) {
  const raw = String(value ?? '')
  assert(
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(raw),
    `expected canonical UUID ${field}: ${JSON.stringify(value)}`,
  )
  return raw
}

function nonNegativeDecimal(value, field) {
  const raw = String(value ?? '')
  assert(/^(0|[1-9]\d*)$/.test(raw), `expected non-negative decimal ${field}: ${JSON.stringify(value)}`)
  return raw
}

/** 严格校验 Thread 投影 DTO 的 canonical UUID 标识字段并返回 threadId。 */
export function threadIdOf(thread) {
  const threadId = canonicalUuid(thread?.threadId, 'threadId')
  canonicalUuid(thread.sessionId, 'sessionId')
  canonicalUuid(thread.headEntryId, 'headEntryId')
  assert(
    thread.nextCommandSequence && /^[1-9]\d*$/.test(String(thread.nextCommandSequence)),
    `nextCommandSequence starts at 1: ${JSON.stringify(thread)}`,
  )
  nonNegativeDecimal(thread.revision, 'revision')
  return threadId
}

/** 创建 Chat（name-based Agent 引用；可选默认 Environment 名称；Thread 的 branchSettings 与 Chat 默认值独立）。 */
export async function createChat(ctx, { title, agentName, yoloEnabled = false, environmentName = null }) {
  const { status, json } = await ctx.call('POST', '/api/ai/chat', {
    title,
    agentName,
    yoloEnabled,
    environmentName,
  })
  assert(status === 201, `create Chat status ${status}: ${JSON.stringify(json)}`)
  const chat = envelopeData(json)
  assert(chat?.id && chat?.agentName, `invalid Chat: ${JSON.stringify(chat)}`)
  assert(
    chat.environmentName === null || typeof chat.environmentName === 'string',
    `Chat environmentName must be null or string: ${JSON.stringify(chat)}`,
  )
  return chat
}

/**
 * 以完整 branchSettings 原子创建 Thread（201 返回 HarnessThreadSnapshotDTO）。
 * branchSettings: {environmentName, agentName, model:{providerName,modelName,variant}, activeTools}
 */
export async function createChatThread(ctx, chatId, { title = null, branchSettings, yoloEnabled = false }) {
  const { status, json } = await ctx.call(
    'POST',
    `/api/ai/chat/${encodeURIComponent(chatId)}/threads`,
    { title, branchSettings, yoloEnabled },
  )
  assert(status === 201, `create Chat thread status ${status}: ${JSON.stringify(json)}`)
  const snapshot = envelopeData(json)
  assert(snapshot?.thread, `expected Thread snapshot: ${JSON.stringify(json)}`)
  threadIdOf(snapshot.thread)
  assert(Array.isArray(snapshot.entries), JSON.stringify(snapshot))
  assert(Array.isArray(snapshot.queuedCommands), JSON.stringify(snapshot))
  assert(
    snapshot.modelInvocation === null || typeof snapshot.modelInvocation === 'object',
    JSON.stringify(snapshot),
  )
  assert(Array.isArray(snapshot.toolInvocations), JSON.stringify(snapshot))
  return snapshot
}

/** 创建 Chat + Thread，返回 {chat, snapshot}（snapshot.thread 即新 Thread 投影）。 */
export async function createConfiguredChatThread(
  ctx,
  { agent, model, title, yoloEnabled = false, environmentName = null, activeTools = [] } = {},
) {
  assert(agent?.name, `agent required: ${JSON.stringify(agent)}`)
  const chat = await createChat(ctx, {
    title: title ?? `e2e-${Math.random().toString(36).slice(2, 10)}`,
    agentName: agent.name,
    yoloEnabled,
  })
  const snapshot = await createChatThread(ctx, chat.id, {
    title: null,
    yoloEnabled,
    branchSettings: branchSettingsOf(agent, model, {
      environmentName,
      activeTools,
    }),
  })
  return { chat, snapshot }
}

/** 由 Agent + Model 引用构造完整 branchSettings（environmentName 是 canonical 路由名称，可 null）。 */
export function branchSettingsOf(
  agent,
  model,
  { environmentName = null, activeTools = [] } = {},
) {
  assert(agent?.name, `agent name required: ${JSON.stringify(agent)}`)
  assert(model?.providerName && model?.modelName && model?.variant, `model required: ${JSON.stringify(model)}`)
  return {
    environmentName: environmentName ?? null,
    agentName: agent.name,
    model: {
      providerName: model.providerName,
      modelName: model.modelName,
      variant: model.variant,
    },
    activeTools: [...(activeTools ?? [])],
  }
}

export async function getThreadSnapshot(ctx, threadId) {
  const { json } = await ctx.call(
    'GET',
    `/api/ai/runtime/threads/${encodeURIComponent(threadId)}/snapshot`,
  )
  const snapshot = envelopeData(json)
  assert(snapshot?.thread, `expected Thread snapshot: ${JSON.stringify(json)}`)
  threadIdOf(snapshot.thread)
  return snapshot
}

export async function getThread(ctx, threadId) {
  return (await getThreadSnapshot(ctx, threadId)).thread
}

export async function snapshotEntries(ctx, threadId) {
  return (await getThreadSnapshot(ctx, threadId)).entries || []
}

/** Chat-scoped Thread 数组（关联时间新到旧）。 */
export async function listChatThreads(ctx, chatId) {
  const { json } = await ctx.call('GET', `/api/ai/chat/${encodeURIComponent(chatId)}/threads`)
  const threads = envelopeData(json)
  assert(Array.isArray(threads), `expected Chat Thread array: ${JSON.stringify(json)}`)
  for (const thread of threads) {
    threadIdOf(thread)
  }
  return threads
}

/** 只读 Environment 注册表；name 是 canonical 路由身份（唯一键），ready 是统一可用性标记。 */
export async function listEnvironments(ctx) {
  const { json } = await ctx.call('GET', '/api/ai/environment')
  const environments = envelopeData(json)
  assert(Array.isArray(environments), `expected Environment array: ${JSON.stringify(json)}`)
  for (const environment of environments) {
    assert(
      !Object.hasOwn(environment, 'id'),
      `Environment must not expose id (name is the only route identity): ${JSON.stringify(environment)}`,
    )
    assert(
      /^[a-z0-9]+(-[a-z0-9]+)*$/.test(String(environment.name || '')),
      `Environment name must be a canonical bounded lowercase route name: ${JSON.stringify(environment)}`,
    )
    assert(typeof environment.ready === 'boolean', JSON.stringify(environment))
    assert(typeof environment.status === 'string', JSON.stringify(environment))
  }
  return environments
}

export function userMessageCommand(content, clientCommandId) {
  assert(typeof content === 'string' && content.trim(), 'content required')
  assert(clientCommandId && typeof clientCommandId === 'string', 'clientCommandId required')
  // Strict wire: USER_MESSAGE carries ONLY type/clientCommandId/content.
  return { type: 'USER_MESSAGE', clientCommandId, content }
}

export function customMessageCommand(role, content, clientCommandId) {
  assert(role === 'SYSTEM' || role === 'USER', `custom role must be SYSTEM|USER: ${role}`)
  assert(typeof content === 'string' && content.trim(), 'content required')
  assert(clientCommandId && typeof clientCommandId === 'string', 'clientCommandId required')
  return { type: 'CUSTOM_MESSAGE', role, clientCommandId, content }
}

export function setEnvironmentCommand(environmentName, clientCommandId) {
  assert(clientCommandId && typeof clientCommandId === 'string', 'clientCommandId required')
  return { type: 'SET_ENVIRONMENT', clientCommandId, environmentName }
}

export function setAgentCommand(agentName, clientCommandId) {
  assert(agentName && typeof agentName === 'string', 'agentName required')
  assert(clientCommandId && typeof clientCommandId === 'string', 'clientCommandId required')
  return { type: 'SET_AGENT', clientCommandId, agentName }
}

export function setModelCommand(model, clientCommandId) {
  assert(model?.providerName && model?.modelName && model?.variant, `model required: ${JSON.stringify(model)}`)
  assert(clientCommandId && typeof clientCommandId === 'string', 'clientCommandId required')
  return { type: 'SET_MODEL', clientCommandId, model }
}

export function setActiveToolsCommand(activeTools, clientCommandId) {
  assert(Array.isArray(activeTools), 'activeTools must be an array')
  assert(clientCommandId && typeof clientCommandId === 'string', 'clientCommandId required')
  return { type: 'SET_ACTIVE_TOOLS', clientCommandId, activeTools }
}

export function setYoloCommand(yoloEnabled, clientCommandId) {
  assert(typeof yoloEnabled === 'boolean', 'yoloEnabled must be boolean')
  assert(clientCommandId && typeof clientCommandId === 'string', 'clientCommandId required')
  return { type: 'SET_YOLO', clientCommandId, yoloEnabled }
}

/**
 * 原子入队命令 batch（202 accepted）。clientCommandId 幂等：整批已存在则 replay 返回既有命令，
 * 部分存在 => 409 PARTIAL_COMMAND_REPLAY。
 */
export async function enqueueCommands(
  ctx,
  threadId,
  { expectedHeadEntryId, expectedNextCommandSequence, commands },
) {
  assert(Array.isArray(commands) && commands.length > 0, 'commands required')
  const { status, json } = await ctx.call(
    'POST',
    `/api/ai/runtime/threads/${encodeURIComponent(threadId)}/commands`,
    {
      expectedHeadEntryId: canonicalUuid(expectedHeadEntryId, 'expectedHeadEntryId'),
      expectedNextCommandSequence: positiveDecimal(
        expectedNextCommandSequence,
        'expectedNextCommandSequence',
      ),
      commands,
    },
  )
  assert(status === 202, `enqueue commands status ${status}: ${JSON.stringify(json)}`)
  const commandsDto = envelopeData(json)
  assert(Array.isArray(commandsDto) && commandsDto.length === commands.length, JSON.stringify(json))
  return commandsDto
}

/** 同步重定位 head（revision CAS；同 target no-op 不 bump；跨 Session target 409）。 */
export async function updateThreadHead(ctx, threadId, { targetEntryId, expectedRevision }) {
  const { status, json } = await ctx.call(
    'PUT',
    `/api/ai/runtime/threads/${encodeURIComponent(threadId)}/head`,
    {
      targetEntryId: canonicalUuid(targetEntryId, 'targetEntryId'),
      expectedRevision: nonNegativeDecimal(expectedRevision, 'expectedRevision'),
    },
  )
  assert(status === 200, `update head status ${status}: ${JSON.stringify(json)}`)
  const updated = envelopeData(json)
  threadIdOf(updated)
  return updated
}

/** 原子 stop（stopRequestId 幂等 replay；revision CAS）。 */
export async function stopThread(ctx, threadId, { stopRequestId, expectedRevision }) {
  assert(stopRequestId && typeof stopRequestId === 'string', 'stopRequestId required')
  const { status, json } = await ctx.call(
    'POST',
    `/api/ai/runtime/threads/${encodeURIComponent(threadId)}/stop`,
    {
      stopRequestId,
      expectedRevision: nonNegativeDecimal(expectedRevision, 'expectedRevision'),
    },
  )
  assert(status === 200, `stop status ${status}: ${JSON.stringify(json)}`)
  const stop = envelopeData(json)
  assert(stop?.thread, `expected stop result thread: ${JSON.stringify(json)}`)
  assert(typeof stop.status === 'string', JSON.stringify(stop))
  if (stop.stoppedTurnEndEntryId != null) {
    canonicalUuid(stop.stoppedTurnEndEntryId, 'stoppedTurnEndEntryId')
  }
  assert(
    Number.isSafeInteger(stop.cancelledCommandCount) && stop.cancelledCommandCount >= 0,
    JSON.stringify(stop),
  )
  return stop
}

/**
 * 决定一次 Tool approval（decisionId 幂等；冲突 decision 409）。返回当前 ToolInvocationDTO。
 * Java 事实：HarnessToolApprovalDTO {decision: ALLOW|DENY, decisionId, actor, reason}；
 * ALLOWED 恢复为 READY 并请求 TOOL Work，DENIED 终止为 FAILED；Thread revision touch 一次。
 */
export async function approveToolInvocation(
  ctx,
  threadId,
  toolInvocationId,
  { decision, decisionId, actor = 'web', reason = null },
) {
  assert(decision === 'ALLOW' || decision === 'DENY', `decision must be ALLOW|DENY: ${decision}`)
  assert(decisionId && typeof decisionId === 'string', 'decisionId required')
  assert(actor && typeof actor === 'string', 'actor required')
  assert(reason == null || typeof reason === 'string', 'reason must be string|null')
  const { status, json } = await ctx.call(
    'POST',
    `/api/ai/runtime/threads/${encodeURIComponent(threadId)}/tool-invocations/${encodeURIComponent(toolInvocationId)}/approval`,
    { decision, decisionId, actor, reason },
  )
  assert(status === 200, `approval status ${status}: ${JSON.stringify(json)}`)
  const invocation = envelopeData(json)
  assert(invocation?.id && invocation.status, `invalid ToolInvocationDTO: ${JSON.stringify(json)}`)
  canonicalUuid(invocation.id, 'invocation.id')
  return invocation
}

/**
 * 轮询完整快照直到 Thread 真正 quiescent 并返回最终 Thread 投影。
 *
 * 判定基于完整 snapshot 而不是单字段：status=IDLE、processing=false、queuedCommands 为空、
 * modelInvocation=null、toolInvocations 为空。单看 status 会在刚入队、命令尚未被 claim 时立即
 * 返回 IDLE，导致 final assertions 抢跑。
 */
export async function waitForQuiescentThread(
  ctx,
  threadId,
  { timeoutMs = 30_000, intervalMs = 250 } = {},
) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() <= deadline) {
    const snapshot = await getThreadSnapshot(ctx, threadId)
    last = snapshot.thread
    if (
      last.status === 'IDLE'
      && !last.processing
      && (snapshot.queuedCommands || []).length === 0
      && snapshot.modelInvocation === null
      && (snapshot.toolInvocations || []).length === 0
    ) {
      return last
    }
    await sleep(intervalMs)
  }
  throw new Error(`thread did not become quiescent: ${JSON.stringify(last)}`)
}

/**
 * 先建立 Thread realtime SSE 连接，再执行 startWork，并等待当前 Thread 第一条非空模型文本增量。
 *
 * SSE 只提供调用 /stop 的时机，不是 durable 断言来源。无论成功、失败或超时，都会取消 reader 并中止连接。
 */
export async function waitForModelTextDeltaAfterSseConnected(
  ctx,
  threadId,
  startWork,
  { timeoutMs = 90_000 } = {},
) {
  const expectedThreadId = canonicalUuid(threadId, 'threadId')
  assert(typeof startWork === 'function', 'startWork must be a function')
  assert(
    typeof ctx?.baseUrl === 'string' && ctx.baseUrl.trim(),
    'E2E context must expose a non-blank baseUrl',
  )
  assert(Number.isFinite(timeoutMs) && timeoutMs > 0, `invalid SSE timeout: ${timeoutMs}`)

  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)
  let reader = null
  try {
    const baseUrl = ctx.baseUrl.replace(/\/$/, '')
    const requestPath =
      `/api/ai/runtime/threads/${encodeURIComponent(expectedThreadId)}/events/stream?afterRevision=0`
    const response = await fetch(`${baseUrl}${requestPath}`, {
      method: 'GET',
      headers: {
        Accept: 'text/event-stream',
        'Cache-Control': 'no-cache',
      },
      signal: controller.signal,
    })
    assert(response.ok, `SSE open status ${response.status} for ${requestPath}`)
    assert(
      String(response.headers.get('content-type') || '').toLowerCase().includes('text/event-stream'),
      `expected text/event-stream, got ${response.headers.get('content-type')}`,
    )
    assert(response.body, 'SSE response has no readable body')

    reader = response.body.getReader()
    const startResult = await startWork()
    const signal = await readModelTextDelta(reader, expectedThreadId)
    return { signal, startResult }
  } catch (error) {
    if (timedOut || error?.name === 'AbortError') {
      throw new Error(
        `timed out after ${timeoutMs}ms waiting for MODEL_DELTA/TEXT_DELTA on thread ${expectedThreadId}`,
      )
    }
    throw error
  } finally {
    clearTimeout(timer)
    if (reader != null) {
      try {
        await reader.cancel()
      } catch {
        // The timeout/remote close path may have already released the reader.
      }
    }
    controller.abort()
  }
}

async function readModelTextDelta(reader, expectedThreadId) {
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      throw new Error(
        `SSE ended before MODEL_DELTA/TEXT_DELTA arrived for thread ${expectedThreadId}`,
      )
    }
    buffer += decoder.decode(value, { stream: true })
    while (true) {
      const boundary = /\r?\n\r?\n/.exec(buffer)
      if (boundary == null) break
      const frame = buffer.slice(0, boundary.index)
      buffer = buffer.slice(boundary.index + boundary[0].length)
      const record = parseSseFrame(frame)
      const signal = parseModelTextDelta(record, expectedThreadId)
      if (signal != null) return signal
    }
  }
}

function parseSseFrame(frame) {
  let id = null
  let event = 'message'
  const data = []
  for (const line of frame.split(/\r?\n/)) {
    if (!line || line.startsWith(':')) continue
    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    let value = separator < 0 ? '' : line.slice(separator + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'id') id = value
    else if (field === 'event') event = value
    else if (field === 'data') data.push(value)
  }
  return { id, event, data: data.join('\n') }
}

function parseModelTextDelta(record, expectedThreadId) {
  if (record.event !== 'realtime' || !record.data) return null
  let envelope
  try {
    envelope = JSON.parse(record.data)
  } catch {
    return null
  }
  if (!isRecord(envelope) || !isRecord(envelope.payload)) return null
  if (
    envelope.type !== 'MODEL_DELTA'
    || envelope.subjectKind !== 'MODEL_INVOCATION'
    || envelope.threadId !== expectedThreadId
    || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
      String(envelope.subjectId || ''),
    )
    || !Number.isSafeInteger(envelope.attempt)
    || envelope.attempt <= 0
    || typeof envelope.createdAt !== 'string'
    || !envelope.createdAt.trim()
    || !Number.isFinite(Date.parse(envelope.createdAt))
    || envelope.payload.kind !== 'TEXT_DELTA'
    || typeof envelope.payload.text !== 'string'
    || !envelope.payload.text.trim()
  ) {
    return null
  }
  return {
    eventId: record.id,
    threadId: envelope.threadId,
    invocationId: envelope.subjectId,
    attempt: envelope.attempt,
    text: envelope.payload.text,
    createdAt: envelope.createdAt,
  }
}

function isRecord(value) {
  return typeof value === 'object' && value != null && !Array.isArray(value)
}
