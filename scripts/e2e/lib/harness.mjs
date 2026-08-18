import { assert, envelopeData, sleep } from './http.mjs'

/**
 * Harness Runtime 单轨契约 helper（Chat-scoped Thread + 唯一 Runtime 数据面）。
 *
 * 端点事实源（web 模块）：
 * - GET  /api/ai/chat/{chatId}/threads                 Chat-scoped Thread 数组（新到旧）
 * - POST /api/ai/chat/{chatId}/threads                 {title,branchSettings,yoloEnabled} → 201 snapshot
 * - GET  /api/ai/runtime/threads/{id}/snapshot         一致快照（单事务）
 * - GET  /api/ai/runtime/threads/{id}/entries          Session 完整不可变 Entry Tree
 * - POST /api/ai/runtime/threads/{id}/commands         命令 batch（202；clientCommandId 幂等 replay）
 * - PUT  /api/ai/runtime/threads/{id}/head             {targetEntryId,expectedRevision}
 * - POST /api/ai/runtime/threads/{id}/stop             {stopRequestId,expectedRevision}（同 id 幂等 replay）
 * - POST /api/ai/runtime/threads/{id}/tool-invocations/{toolInvocationId}/approval
 * - WS   /api/events/v1                               应用级 Thread/Canvas 事件订阅
 * - GET  /api/ai/environment                           只读 Environment 注册表（name = canonical 路由身份）
 */

function positiveDecimal(value, field) {
  const raw = String(value ?? '')
  assert(/^[1-9]\d*$/.test(raw), `expected positive decimal ${field}: ${JSON.stringify(value)}`)
  return raw
}

/** 实体标识统一为 canonical UUID 字符串（Chat/Thread/Entry/Session/Invocation/Command 均如此）。 */
export function canonicalUuid(value, field) {
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

function isEnvironmentBindingOrNull(binding) {
  if (binding === null) return true
  if (typeof binding !== 'object' || binding == null || Array.isArray(binding)) return false
  if (Object.keys(binding).sort().join(',') !== 'name,workspacePath') return false
  if (
    typeof binding.name !== 'string'
    || binding.name.length > 64
    || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(binding.name)
  ) {
    return false
  }
  const workspacePath = binding.workspacePath
  if (
    typeof workspacePath !== 'string'
    || workspacePath.length === 0
    || workspacePath.length > 2048
    || workspacePath.includes('\\')
    || workspacePath.startsWith('/')
    || /^[A-Za-z]:/.test(workspacePath)
    || /[\u0000-\u001f\u007f-\u009f]/.test(workspacePath)
  ) {
    return false
  }
  return workspacePath === '.'
    || workspacePath.split('/').every((segment) => segment !== '' && segment !== '.' && segment !== '..')
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

/** 创建 Chat（name-based Agent 引用；可选完整 Environment binding；Thread branchSettings 独立）。 */
export async function createChat(
  ctx,
  { title, agentName, yoloEnabled = false, environment = null },
) {
  const { status, json } = await ctx.call('POST', '/api/ai/chat', {
    title,
    agentName,
    yoloEnabled,
    environment,
  })
  assert(status === 201, `create Chat status ${status}: ${JSON.stringify(json)}`)
  const chat = envelopeData(json)
  assert(chat?.id && chat?.agentName, `invalid Chat: ${JSON.stringify(chat)}`)
  assert(
    isEnvironmentBindingOrNull(chat.environment),
    `Chat environment must be null or a complete binding: ${JSON.stringify(chat)}`,
  )
  assert(!Object.hasOwn(chat, 'environmentName'), `Chat leaked environmentName: ${JSON.stringify(chat)}`)
  return chat
}

/**
 * 以完整 branchSettings 原子创建 Thread（201 返回 HarnessThreadSnapshotDTO）。
 * branchSettings: {environment, agentName, model:{providerName,modelName,variant}, activeTools}
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
  {
    agent,
    model,
    title,
    yoloEnabled = false,
    environment = null,
    activeTools = [],
    branchAgentName = agent?.name,
  } = {},
) {
  assert(agent?.name, `agent required: ${JSON.stringify(agent)}`)
  assert(branchAgentName && typeof branchAgentName === 'string', 'branchAgentName required')
  const chat = await createChat(ctx, {
    title: title ?? `e2e-${Math.random().toString(36).slice(2, 10)}`,
    agentName: agent.name,
    yoloEnabled,
  })
  const snapshot = await createChatThread(ctx, chat.id, {
    title: null,
    yoloEnabled,
    branchSettings: branchSettingsOf({ name: branchAgentName }, model, {
      environment,
      activeTools,
    }),
  })
  return { chat, snapshot }
}

/** 由 Agent + Model 引用构造完整 branchSettings（environment 是完整 binding，可 null）。 */
export function branchSettingsOf(
  agent,
  model,
  { environment = null, activeTools = [] } = {},
) {
  assert(agent?.name, `agent name required: ${JSON.stringify(agent)}`)
  assert(model?.providerName && model?.modelName && model?.variant, `model required: ${JSON.stringify(model)}`)
  assert(
    isEnvironmentBindingOrNull(environment),
    `environment must be null or a complete binding: ${JSON.stringify(environment)}`,
  )
  return {
    environment,
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

/** 读取 Thread 所属 Session 的完整不可变 Entry Tree，包含当前 head 之外的历史分支。 */
export async function getThreadEntries(ctx, threadId) {
  const { status, json } = await ctx.call(
    'GET',
    `/api/ai/runtime/threads/${encodeURIComponent(threadId)}/entries`,
  )
  assert(status === 200, `get thread entries status ${status}: ${JSON.stringify(json)}`)
  const entries = envelopeData(json)
  assert(Array.isArray(entries), `thread entries must be an array: ${JSON.stringify(json)}`)
  return entries
}

/** 等待指定消息进入完整 Session Entry Tree，并同时确认 Thread 已真正 quiescent。 */
export async function waitForDurableMessages(
  ctx,
  threadId,
  expectedTexts,
  { timeoutMs = 60_000, intervalMs = 100 } = {},
) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() <= deadline) {
    const snapshot = await getThreadSnapshot(ctx, threadId)
    const entries = await getThreadEntries(ctx, threadId)
    last = { snapshot, entries }
    const payloads = entries.map((entry) => entry.payloadJson)
    const hasExpectedMessages = expectedTexts.every(
      (text) => payloads.some((payload) => payload.includes(text)),
    )
    if (
      hasExpectedMessages
      && snapshot.thread.status === 'IDLE'
      && !snapshot.thread.processing
      && snapshot.queuedCommands.length === 0
      && snapshot.modelInvocation === null
      && snapshot.toolInvocations.length === 0
    ) {
      return last
    }
    await sleep(intervalMs)
  }
  throw new Error(
    `durable messages did not stabilize: ${JSON.stringify({ expectedTexts, last })}`,
  )
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
  // Strict wire: USER_MESSAGE carries ONLY type/clientCommandId/contents（TEXT/ATTACHMENT，无文本 shorthand）。
  return { type: 'USER_MESSAGE', clientCommandId, contents: [{ type: 'TEXT', text: content }] }
}

export function customMessageCommand(role, content, clientCommandId) {
  assert(role === 'SYSTEM' || role === 'USER', `custom role must be SYSTEM|USER: ${role}`)
  assert(typeof content === 'string' && content.trim(), 'content required')
  assert(clientCommandId && typeof clientCommandId === 'string', 'clientCommandId required')
  return { type: 'CUSTOM_MESSAGE', role, clientCommandId, content }
}

export function setEnvironmentCommand(environment, clientCommandId) {
  assert(clientCommandId && typeof clientCommandId === 'string', 'clientCommandId required')
  assert(
    isEnvironmentBindingOrNull(environment),
    `environment must be null or a complete binding: ${JSON.stringify(environment)}`,
  )
  return { type: 'SET_ENVIRONMENT', clientCommandId, environment }
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

/**
 * 直接更新 Thread YOLO policy（PUT /yolo，revision CAS）：同值请求在任何 CAS 之前即成功 no-op；
 * 值变化且 revision 不匹配 => 409 STALE_REVISION。返回权威 Thread DTO。
 */
export async function setThreadYolo(ctx, threadId, { expectedRevision, yoloEnabled }) {
  assert(typeof yoloEnabled === 'boolean', 'yoloEnabled must be boolean')
  const { status, json } = await ctx.call(
    'PUT',
    `/api/ai/runtime/threads/${encodeURIComponent(threadId)}/yolo`,
    {
      expectedRevision: nonNegativeDecimal(expectedRevision, 'expectedRevision'),
      yoloEnabled,
    },
  )
  assert(status === 200, `set thread yolo status ${status}: ${JSON.stringify(json)}`)
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
 * 先建立应用级 Thread 订阅并收到 subscribed ack，再执行 startWork，随后等待当前 Thread
 * 第一条非空模型文本增量。事件通道只提供调用 /stop 的时机，不是 durable 断言来源。
 */
export async function waitForModelTextDeltaAfterEventSubscribed(
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
  assert(Number.isFinite(timeoutMs) && timeoutMs > 0, `invalid event timeout: ${timeoutMs}`)
  assert(typeof WebSocket === 'function', 'Node runtime must provide WebSocket')

  const deadline = Date.now() + timeoutMs
  const controller = new AbortController()
  const socket = new WebSocket(applicationEventUrl(ctx.baseUrl))
  try {
    await waitForSocketOpen(socket, deadline, controller.signal)
    socket.send(JSON.stringify({
      version: 1,
      type: 'subscribe',
      resource: { kind: 'thread', id: expectedThreadId },
    }))
    await waitForApplicationEvent(
      socket,
      deadline,
      controller.signal,
      (frame) => {
        if (
          frame?.version === 1
          && frame.type === 'subscribed'
          && sameThreadResource(frame.resource, expectedThreadId)
          && /^(0|[1-9]\d*)$/.test(String(frame.cursor ?? ''))
        ) {
          return { matched: true, value: frame }
        }
        return { matched: false }
      },
      `subscribed ack for thread ${expectedThreadId}`,
    )

    const deltaWait = waitForApplicationEvent(
      socket,
      deadline,
      controller.signal,
      (frame) => {
        if (
          frame?.version === 1
          && frame.type === 'event'
          && frame.name === 'realtime'
          && sameThreadResource(frame.resource, expectedThreadId)
        ) {
          const signal = parseModelTextDelta(frame.data, expectedThreadId)
          if (signal != null) {
            return { matched: true, value: signal }
          }
        }
        return { matched: false }
      },
      `MODEL_DELTA/TEXT_DELTA on thread ${expectedThreadId}`,
    )
    let startResult
    try {
      startResult = await startWork()
    } catch (error) {
      controller.abort()
      await deltaWait.catch(() => undefined)
      throw error
    }
    const signal = await deltaWait
    return { signal, startResult }
  } finally {
    controller.abort()
    try {
      socket.close()
    } catch {
      // Connection may already be closed by the remote endpoint.
    }
  }
}

function applicationEventUrl(baseUrl) {
  const url = new URL(baseUrl)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.pathname = '/api/events/v1'
  url.search = ''
  url.hash = ''
  return url.toString()
}

function waitForSocketOpen(socket, deadline, signal) {
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      socket.removeEventListener('open', onOpen)
      socket.removeEventListener('error', onError)
      socket.removeEventListener('close', onClose)
      signal.removeEventListener('abort', onAbort)
      clearTimeout(timer)
    }
    const finish = (callback, value) => {
      cleanup()
      callback(value)
    }
    const onOpen = () => finish(resolve)
    const onError = () => finish(reject, new Error('application event WebSocket failed to open'))
    const onClose = (event) => finish(
      reject,
      new Error(`application event WebSocket closed before open: ${event.code}`),
    )
    const onAbort = () => finish(reject, new Error('application event WebSocket open aborted'))
    const timer = setTimeout(
      () => finish(reject, new Error('timed out opening application event WebSocket')),
      remainingMillis(deadline),
    )
    socket.addEventListener('open', onOpen)
    socket.addEventListener('error', onError)
    socket.addEventListener('close', onClose)
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

function waitForApplicationEvent(socket, deadline, signal, matcher, description) {
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      socket.removeEventListener('message', onMessage)
      socket.removeEventListener('error', onError)
      socket.removeEventListener('close', onClose)
      signal.removeEventListener('abort', onAbort)
      clearTimeout(timer)
    }
    const finish = (callback, value) => {
      cleanup()
      callback(value)
    }
    const onMessage = (event) => {
      let frame
      try {
        frame = JSON.parse(String(event.data))
      } catch {
        finish(reject, new Error(`malformed application event frame: ${String(event.data)}`))
        return
      }
      if (frame?.version === 1 && frame.type === 'error') {
        finish(
          reject,
          new Error(`application event error ${frame.code}: ${frame.message}`),
        )
        return
      }
      const result = matcher(frame)
      if (result.matched) {
        finish(resolve, result.value)
      }
    }
    const onError = () => finish(reject, new Error(`application event WebSocket error before ${description}`))
    const onClose = (event) => finish(
      reject,
      new Error(`application event WebSocket closed before ${description}: ${event.code}`),
    )
    const onAbort = () => finish(reject, new Error(`application event wait aborted: ${description}`))
    const timer = setTimeout(
      () => finish(reject, new Error(`timed out waiting for ${description}`)),
      remainingMillis(deadline),
    )
    socket.addEventListener('message', onMessage)
    socket.addEventListener('error', onError)
    socket.addEventListener('close', onClose)
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

function remainingMillis(deadline) {
  return Math.max(1, deadline - Date.now())
}

function sameThreadResource(resource, expectedThreadId) {
  return resource?.kind === 'thread' && resource.id === expectedThreadId
}

function parseModelTextDelta(envelope, expectedThreadId) {
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
