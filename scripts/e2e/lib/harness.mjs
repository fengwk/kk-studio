import { assert, envelopeData, sleep } from './http.mjs'

/**
 * Harness Runtime 单轨契约 helper（owner-aware command-batches + Session/Thread 查询）。
 *
 * 端点事实源（web 模块）：
 * - POST /api/ai/runtime/command-batches          唯一产品用户命令写入口（202 accepted）
 * - GET  /api/ai/chat/{chatId}/sessions           Chat owner 的 Session 摘要（新到旧）
 * - GET  /api/ai/canvases/{canvasId}/sessions     Canvas owner 的 Session 摘要（新到旧）
 * - GET  /api/ai/runtime/sessions/{sessionId}/threads   Session 下 Thread 摘要
 * - GET  /api/ai/runtime/sessions/{sessionId}/entries   完整不可变 Entry Tree
 * - GET  /api/ai/runtime/threads/{id}/snapshot    一致快照（单事务）
 * - PUT  /api/ai/runtime/threads/{id}/yolo        {expectedVersion,yoloEnabled}（version CAS）
 * - POST /api/ai/runtime/threads/{id}/stop        {stopRequestId,expectedVersion}（同 id 幂等 replay）
 * - POST /api/ai/runtime/threads/{id}/tool-invocations/{toolInvocationId}/approval
 * - WS   /api/events/v1                           应用级 Thread/Canvas 事件订阅
 * - GET  /api/ai/environment                       只读 Environment 注册表（name = canonical 路由身份）
 *
 * 产品 HTTP 写面只接受三种 sealed target：
 * - NEW_SESSION{sessionId,threadId,rootSettings,yoloEnabled}：新建 Session + ROOT + Thread
 * - ENTRY{sessionId,startEntryId,threadId,yoloEnabled}：在既有 Session 既有 Entry 下开新 Thread
 * - THREAD{threadId,expectedHeadEntryId,expectedNextCommandSequence}：在既有 Thread 上继续
 * commands 必须是固定顺序 SET_ENVIRONMENT,SET_AGENT,SET_MODEL,SET_ACTIVE_TOOLS 前缀 +
 * 恰一条末尾 USER_MESSAGE；CUSTOM_MESSAGE 在产品 HTTP 面被拒绝。
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
  nonNegativeDecimal(thread.version, 'version')
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

// ---------- owner / target 构造 ----------

/** Chat owner 引用（canonical chatId）。 */
export function chatOwner(chatId) {
  return { type: 'CHAT', id: canonicalUuid(chatId, 'chatId') }
}

/** Canvas owner 引用（canonical canvasId）。 */
export function canvasOwner(canvasId) {
  return { type: 'CANVAS', id: canonicalUuid(canvasId, 'canvasId') }
}

/** NEW_SESSION target：预分配 sessionId/threadId，携带 root settings 与 initial yolo。 */
export function newSessionTarget({ sessionId, threadId, rootSettings, yoloEnabled = false }) {
  canonicalUuid(sessionId, 'target.sessionId')
  canonicalUuid(threadId, 'target.threadId')
  assert(rootSettings && typeof rootSettings === 'object', `rootSettings required: ${JSON.stringify(rootSettings)}`)
  assert(typeof yoloEnabled === 'boolean', 'target.yoloEnabled must be boolean')
  return { type: 'NEW_SESSION', sessionId, threadId, rootSettings, yoloEnabled }
}

/** ENTRY target：在既有 Session 的既有 Entry 下开新 Thread（不复制 Entry）。 */
export function entryTarget({ sessionId, startEntryId, threadId, yoloEnabled = false }) {
  canonicalUuid(sessionId, 'target.sessionId')
  canonicalUuid(startEntryId, 'target.startEntryId')
  canonicalUuid(threadId, 'target.threadId')
  assert(typeof yoloEnabled === 'boolean', 'target.yoloEnabled must be boolean')
  return { type: 'ENTRY', sessionId, startEntryId, threadId, yoloEnabled }
}

/** THREAD target：在既有 Thread 上继续，携带精确 cursor 期望。 */
export function threadTarget({ threadId, expectedHeadEntryId, expectedNextCommandSequence }) {
  canonicalUuid(threadId, 'target.threadId')
  canonicalUuid(expectedHeadEntryId, 'target.expectedHeadEntryId')
  positiveDecimal(expectedNextCommandSequence, 'target.expectedNextCommandSequence')
  return {
    type: 'THREAD',
    threadId,
    expectedHeadEntryId,
    expectedNextCommandSequence: String(expectedNextCommandSequence),
  }
}

// ---------- 唯一产品写入口 ----------

/** 校验一个 202 accepted 的 HarnessAcceptedCommandsDTO 的核心形状（具体一致性由 acceptCommandBatch 完成）。 */
function assertAcceptedCommands(accepted) {
  assert(accepted && typeof accepted === 'object', `expected accepted object: ${JSON.stringify(accepted)}`)
  canonicalUuid(accepted.session?.sessionId, 'accepted.session.sessionId')
  canonicalUuid(accepted.rootEntry?.entryId, 'accepted.rootEntry.entryId')
  assert(
    String(accepted.rootEntry?.entryType || '').toUpperCase() === 'ROOT',
    `rootEntry must be ROOT: ${JSON.stringify(accepted.rootEntry)}`,
  )
  threadIdOf(accepted.thread)
  assert(Array.isArray(accepted.acceptedCommands), JSON.stringify(accepted))
  assert(typeof accepted.replayed === 'boolean', JSON.stringify(accepted))
}

/**
 * 唯一产品用户命令写入口：owner-aware command batch（202 accepted）。
 *
 * 自动基于请求 target/commands 严格校验响应形状（无需调用方传 options）：
 * - 返回 threadId 必须等于 target.threadId；
 * - NEW_SESSION/ENTRY 的 sessionId 必须等于 target.sessionId；
 * - rootEntry.sessionId/thread.sessionId 必须等于 response session.sessionId；
 * - acceptedCommands 的 count/type/clientCommandId/order 必须与请求 commands 一致，
 *   且每项 threadId、positive sequence、canonical clientCommandId、64 位小写 hex requestHash 均校验。
 */
export async function acceptCommandBatch(ctx, { owner, target, commands }) {
  assert(owner?.type && owner?.id, `owner required: ${JSON.stringify(owner)}`)
  assert(owner.type === 'CHAT' || owner.type === 'CANVAS', `owner.type must be CHAT|CANVAS: ${JSON.stringify(owner)}`)
  canonicalUuid(owner.id, 'owner.id')
  assert(target?.type, `target required: ${JSON.stringify(target)}`)
  assert(Array.isArray(commands) && commands.length > 0, 'commands required')
  for (const command of commands) {
    assert(
      command && typeof command === 'object' && command.type && command.clientCommandId,
      `invalid command: ${JSON.stringify(command)}`,
    )
  }
  const { status, json } = await ctx.call('POST', '/api/ai/runtime/command-batches', {
    owner,
    target,
    commands,
  })
  assert(status === 202, `accept command batch status ${status}: ${JSON.stringify(json)}`)
  const accepted = envelopeData(json)
  assertAcceptedCommands(accepted)
  // target 一致性：threadId 恒等于 target.threadId。
  assert(
    String(accepted.thread?.threadId) === String(target.threadId),
    `accepted threadId ${accepted.thread?.threadId} != target ${target.threadId}: ${JSON.stringify(accepted)}`,
  )
  if (target.type === 'NEW_SESSION' || target.type === 'ENTRY') {
    assert(
      String(accepted.session?.sessionId) === String(target.sessionId),
      `accepted sessionId ${accepted.session?.sessionId} != target ${target.sessionId}: ${JSON.stringify(accepted)}`,
    )
  }
  assert(
    String(accepted.rootEntry?.sessionId) === String(accepted.session?.sessionId)
      && String(accepted.thread?.sessionId) === String(accepted.session?.sessionId),
    `rootEntry/thread session must equal accepted session: ${JSON.stringify(accepted)}`,
  )
  // acceptedCommands 与请求 commands 逐项一致。
  assert(
    accepted.acceptedCommands.length === commands.length,
    `accepted command count ${accepted.acceptedCommands.length} != ${commands.length}: ${JSON.stringify(accepted)}`,
  )
  for (let i = 0; i < commands.length; i++) {
    const request = commands[i]
    const response = accepted.acceptedCommands[i]
    assert(
      String(response.type) === String(request.type),
      `accepted command ${i} type ${response?.type} != ${request.type}: ${JSON.stringify(accepted)}`,
    )
    assert(
      String(response.clientCommandId) === String(request.clientCommandId),
      `accepted command ${i} clientCommandId ${response?.clientCommandId} != ${request.clientCommandId}: ${JSON.stringify(accepted)}`,
    )
    canonicalUuid(response.clientCommandId, `accepted.commands[${i}].clientCommandId`)
    assert(
      String(response.threadId) === String(target.threadId),
      `accepted command ${i} threadId ${response?.threadId} != target ${target.threadId}: ${JSON.stringify(accepted)}`,
    )
    assert(
      /^[1-9]\d*$/.test(String(response.sequence)),
      `accepted command ${i} sequence must be positive decimal: ${JSON.stringify(response)}`,
    )
    assert(
      /^[0-9a-f]{64}$/.test(String(response.requestHash)),
      `accepted command ${i} requestHash must be 64 lower hex: ${JSON.stringify(response)}`,
    )
  }
  return accepted
}

/**
 * NEW_SESSION 原子物化：创建 Session + ROOT + Thread 并接受本批 commands。
 * rootSettings 通常是 branchSettingsOf(...) 的完整分支草稿；sessionId/threadId 由调用方预分配。
 */
export async function materializeNewSession(
  ctx,
  { owner, sessionId, threadId, rootSettings, yoloEnabled = false, commands },
) {
  return acceptCommandBatch(ctx, {
    owner,
    target: newSessionTarget({ sessionId, threadId, rootSettings, yoloEnabled }),
    commands,
  })
}

/** ENTRY 原子物化：在既有 Session 的既有 Entry 下开新 Thread（不复制 Entry）。 */
export async function materializeEntryThread(
  ctx,
  { owner, sessionId, startEntryId, threadId, yoloEnabled = false, commands },
) {
  return acceptCommandBatch(ctx, {
    owner,
    target: entryTarget({ sessionId, startEntryId, threadId, yoloEnabled }),
    commands,
  })
}

// ---------- Session / Thread 查询 ----------

/** 严格校验 Session 摘要 DTO。 */
function assertSessionSummary(item) {
  canonicalUuid(item?.sessionId, 'session.sessionId')
  assert(
    typeof item.createdAt === 'string' || typeof item.createdAt === 'number',
    JSON.stringify(item),
  )
  assert(
    typeof item.lastActivityAt === 'string' || typeof item.lastActivityAt === 'number',
    JSON.stringify(item),
  )
  assert(typeof item.firstMessagePreview === 'string', JSON.stringify(item))
  assert(Number.isSafeInteger(item.threadCount) && item.threadCount >= 0, JSON.stringify(item))
  return item
}

/** Chat owner 的 Session 摘要数组（归属时间新到旧）。 */
export async function listChatSessions(ctx, chatId) {
  const { json } = await ctx.call('GET', `/api/ai/chat/${encodeURIComponent(chatId)}/sessions`)
  const sessions = envelopeData(json)
  assert(Array.isArray(sessions), `expected Session summary array: ${JSON.stringify(json)}`)
  for (const item of sessions) assertSessionSummary(item)
  return sessions
}

/** Canvas owner 的 Session 摘要数组（归属时间新到旧）。 */
export async function listCanvasSessions(ctx, canvasId) {
  const { json } = await ctx.call('GET', `/api/canvases/${encodeURIComponent(canvasId)}/sessions`)
  const sessions = envelopeData(json)
  assert(Array.isArray(sessions), `expected Session summary array: ${JSON.stringify(json)}`)
  for (const item of sessions) assertSessionSummary(item)
  return sessions
}

/** Session 下的 Thread 摘要数组（runtime 确定性顺序）。 */
export async function listSessionThreads(ctx, sessionId) {
  const { json } = await ctx.call(
    'GET',
    `/api/ai/runtime/sessions/${encodeURIComponent(sessionId)}/threads`,
  )
  const threads = envelopeData(json)
  assert(Array.isArray(threads), `expected Thread summary array: ${JSON.stringify(json)}`)
  for (const item of threads) {
    canonicalUuid(item?.threadId, 'thread.threadId')
    assert(
      typeof item.createdAt === 'string' || typeof item.createdAt === 'number',
      JSON.stringify(item),
    )
    assert(
      typeof item.updatedAt === 'string' || typeof item.updatedAt === 'number',
      JSON.stringify(item),
    )
    assert(typeof item.status === 'string', JSON.stringify(item))
    assert(item.model?.providerName && item.model?.modelName && item.model?.variant, JSON.stringify(item))
    assert(
      item.headMessagePreview === null || typeof item.headMessagePreview === 'string',
      JSON.stringify(item),
    )
  }
  return threads
}

/** Session 的完整不可变 Entry Tree（parentEntryId 连接父节点；含非当前 head 历史分支）。 */
export async function listSessionEntries(ctx, sessionId) {
  const { json } = await ctx.call(
    'GET',
    `/api/ai/runtime/sessions/${encodeURIComponent(sessionId)}/entries`,
  )
  const entries = envelopeData(json)
  assert(Array.isArray(entries), `expected Session Entry array: ${JSON.stringify(json)}`)
  for (const entry of entries) {
    canonicalUuid(entry?.entryId, 'entry.entryId')
    canonicalUuid(entry.sessionId, 'entry.sessionId')
    assert(
      String(entry.sessionId) === String(sessionId),
      `entry.sessionId ${entry.sessionId} != requested sessionId ${sessionId}: ${JSON.stringify(entry)}`,
    )
    if (entry.parentEntryId != null) canonicalUuid(entry.parentEntryId, 'entry.parentEntryId')
    assert(typeof entry.entryType === 'string' && entry.entryType, JSON.stringify(entry))
    assert(typeof entry.payloadJson === 'string', JSON.stringify(entry))
  }
  return entries
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
    const entries = await listSessionEntries(ctx, snapshot.thread.sessionId)
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
 * 直接更新 Thread YOLO policy（PUT /yolo，version CAS）：同值请求在任何 CAS 之前即成功 no-op；
 * 值变化且 version 不匹配 => 409 STALE_VERSION。返回权威 Thread DTO。
 */
export async function setThreadYolo(ctx, threadId, { expectedVersion, yoloEnabled }) {
  assert(typeof yoloEnabled === 'boolean', 'yoloEnabled must be boolean')
  const { status, json } = await ctx.call(
    'PUT',
    `/api/ai/runtime/threads/${encodeURIComponent(threadId)}/yolo`,
    {
      expectedVersion: nonNegativeDecimal(expectedVersion, 'expectedVersion'),
      yoloEnabled,
    },
  )
  assert(status === 200, `set thread yolo status ${status}: ${JSON.stringify(json)}`)
  const updated = envelopeData(json)
  threadIdOf(updated)
  return updated
}

/** 原子 stop（stopRequestId 幂等 replay；version CAS）。 */
export async function stopThread(ctx, threadId, { stopRequestId, expectedVersion }) {
  assert(stopRequestId && typeof stopRequestId === 'string', 'stopRequestId required')
  const { status, json } = await ctx.call(
    'POST',
    `/api/ai/runtime/threads/${encodeURIComponent(threadId)}/stop`,
    {
      stopRequestId,
      expectedVersion: nonNegativeDecimal(expectedVersion, 'expectedVersion'),
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
 * ALLOWED 恢复为 READY 并请求 TOOL Work，DENIED 终止为 FAILED；Thread version touch 一次。
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

// ---------- 应用事件 WebSocket 辅助 ----------

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
