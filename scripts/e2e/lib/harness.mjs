import {
  assert,
  cid,
  envelopeData,
  HttpError,
  sleep,
} from './http.mjs'

/**
 * Harness Runtime 单轨契约 helper（owner-aware command-batches + Session/Thread 查询）。
 *
 * 端点事实源（web 模块）：
 * - POST /api/harness/command-batches          唯一产品用户命令写入口（202 accepted）
 * - GET  /api/ai/chats/{chatId}/sessions       Chat owner 的 Session 摘要（新到旧）
 * - GET  /api/canvases/{canvasId}/sessions     Canvas owner 的 Session 摘要（新到旧）
 * - GET  /api/harness/sessions/{sessionId}/threads   Session 下 Thread 摘要
 * - GET  /api/harness/sessions/{sessionId}/entries   完整不可变 Entry Tree
 * - GET  /api/harness/threads/{id}             一致快照（单事务）
 * - PUT  /api/harness/threads/{id}/yolo        {expectedVersion,yoloEnabled}（version CAS）
 * - POST /api/harness/threads/{id}/stop        {stopRequestId,expectedVersion}（同 id 幂等 replay）
 * - PUT  /api/harness/threads/{id}/tool-invocations/{toolInvocationId}/approval
 * - PUT  /api/harness/sessions/{id}/name       {name}（200 权威 HarnessSessionDTO）
 * - PUT  /api/harness/threads/{id}/name        {name}（200 权威 HarnessThreadDTO）
 * - WS   /api/events/v1                        应用级 Thread/Canvas 事件订阅
 * - GET  /api/harness/environments             只读 Environment 注册表（Card UUID id = canonical 路由身份）
 *
 * 产品 HTTP 写面只接受三种 sealed target：
 * - NEW_SESSION{sessionId,threadId,rootSettings,yoloEnabled}：新建 Session + ROOT + Thread
 * - NEW_THREAD{sessionId,startEntryId,threadId,yoloEnabled}：在既有 Session 既有 Entry 下开新 Thread
 * - THREAD{threadId,expectedHeadEntryId,expectedNextCommandSequence}：在既有 Thread 上继续
 * commands 必须是固定顺序 SET_AGENT,SET_MODEL,SET_ENVIRONMENT 前缀 +
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

/** 严格校验 Thread 投影 DTO 的 canonical UUID 标识字段并返回 threadId。 */
export function threadIdOf(thread) {
  const threadId = canonicalUuid(thread?.threadId, 'threadId')
  canonicalUuid(thread.sessionId, 'sessionId')
  canonicalUuid(thread.headEntryId, 'headEntryId')
  // name 是 Thread 的必需非空展示名称（服务端派生/控制面重命名，绝不回退为 id）。
  assert(
    typeof thread.name === 'string' && thread.name.trim().length > 0,
    `Thread name must be non-blank: ${JSON.stringify(thread)}`,
  )
  assert(
    thread.nextCommandSequence && /^[1-9]\d*$/.test(String(thread.nextCommandSequence)),
    `nextCommandSequence starts at 1: ${JSON.stringify(thread)}`,
  )
  nonNegativeDecimal(thread.version, 'version')
  return threadId
}

/** 创建 Chat（name-based Agent 引用；环境由 Agent 拥有，Chat 不携带任何 workspace/environment 状态）。 */
export async function createChat(
  ctx,
  { title, agentName, yoloEnabled = false },
) {
  const { status, json } = await ctx.call('POST', '/api/ai/chats', {
    title,
    agentName,
    yoloEnabled,
  })
  assert(status === 201, `create Chat status ${status}: ${JSON.stringify(json)}`)
  const chat = envelopeData(json)
  assert(chat?.id && chat?.agentName, `invalid Chat: ${JSON.stringify(chat)}`)
  assert(!Object.hasOwn(chat, 'workspacePath'), `Chat leaked workspacePath: ${JSON.stringify(chat)}`)
  assert(!Object.hasOwn(chat, 'environment'), `Chat leaked environment: ${JSON.stringify(chat)}`)
  assert(!Object.hasOwn(chat, 'environmentName'), `Chat leaked environmentName: ${JSON.stringify(chat)}`)
  assert(!Object.hasOwn(chat, 'environmentId'), `Chat leaked environmentId: ${JSON.stringify(chat)}`)
  return chat
}

/** 校验 nullable canonical Environment 名称：null 表示未选择；非 null 必须非 blank、无首尾空白、不含 '/' 且不超过 64 字符。 */
export function canonicalEnvironmentName(value, field = 'environmentName') {
  if (value === null) return null
  assert(
    typeof value === 'string',
    `expected string or null for ${field}: ${JSON.stringify(value)}`,
  )
  assert(value.trim().length > 0, `${field} must not be blank`)
  assert(value === value.trim(), `${field} must not contain surrounding whitespace`)
  assert(!value.includes('/'), `${field} must not contain '/'`)
  assert(value.length <= 64, `${field} must be <= 64 characters`)
  return value
}

/** 由 Agent + Model + Environment 引用构造完整 branchSettings（当前契约精确为 agentName + model + environmentName）。 */
export function branchSettingsOf(agent, model, environmentName = null) {
  assert(agent?.name, `agent name required: ${JSON.stringify(agent)}`)
  assert(model?.providerName && model?.modelName && model?.variant, `model required: ${JSON.stringify(model)}`)
  canonicalEnvironmentName(environmentName, 'environmentName')
  return {
    agentName: agent.name,
    model: {
      providerName: model.providerName,
      modelName: model.modelName,
      variant: model.variant,
    },
    environmentName,
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

/** NEW_THREAD target：在既有 Session 的既有 Entry 下开新 Thread（不复制 Entry）。 */
export function newThreadTarget({ sessionId, startEntryId, threadId, yoloEnabled = false }) {
  canonicalUuid(sessionId, 'target.sessionId')
  canonicalUuid(startEntryId, 'target.startEntryId')
  canonicalUuid(threadId, 'target.threadId')
  assert(typeof yoloEnabled === 'boolean', 'target.yoloEnabled must be boolean')
  return { type: 'NEW_THREAD', sessionId, startEntryId, threadId, yoloEnabled }
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
  // Session DTO 的 name 是服务端派生的必填非空展示名。
  assert(
    typeof accepted.session?.name === 'string' && accepted.session.name.trim().length > 0,
    `accepted session name must be non-blank: ${JSON.stringify(accepted.session)}`,
  )
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
 * - NEW_SESSION/NEW_THREAD 的 sessionId 必须等于 target.sessionId；
 * - NEW_THREAD 且 accepted.replayed=false 时（真正首次创建）：accepted.thread.name 必须等于
 *   服务端派生的默认名 branch-<threadId 前 8 位>；replayed=true 的 exact replay 可能发生在该
 *   Thread 已被控制面重命名之后，服务端返回当前权威 name，helper 不做默认名断言；
 * - rootEntry.sessionId/thread.sessionId 必须等于 response session.sessionId；
 * - acceptedCommands 的 count/type/idempotencyKey/order 必须与请求 commands 一致，
 *   且每项 threadId、positive sequence、canonical idempotencyKey 均校验。
 */
export async function acceptCommandBatch(ctx, { owner, target, commands }) {
  assert(owner?.type && owner?.id, `owner required: ${JSON.stringify(owner)}`)
  assert(owner.type === 'CHAT' || owner.type === 'CANVAS', `owner.type must be CHAT|CANVAS: ${JSON.stringify(owner)}`)
  canonicalUuid(owner.id, 'owner.id')
  assert(target?.type, `target required: ${JSON.stringify(target)}`)
  assert(Array.isArray(commands) && commands.length > 0, 'commands required')
  for (const command of commands) {
    assert(
      command && typeof command === 'object' && command.type && command.idempotencyKey,
      `invalid command: ${JSON.stringify(command)}`,
    )
  }
  const { status, json } = await ctx.call('POST', '/api/harness/command-batches', {
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
  if (target.type === 'NEW_SESSION' || target.type === 'NEW_THREAD') {
    assert(
      String(accepted.session?.sessionId) === String(target.sessionId),
      `accepted sessionId ${accepted.session?.sessionId} != target ${target.sessionId}: ${JSON.stringify(accepted)}`,
    )
  }
  if (target.type === 'NEW_THREAD' && accepted.replayed === false) {
    // 真正首次创建：NEW_THREAD 不接受 name 输入，服务端派生默认名 branch-<threadId 前 8 位>。
    // （replayed=true 的 exact replay 可能命中已重命名的 Thread，返回当前权威 name，不在此断言。）
    assert(
      accepted.thread?.name === `branch-${String(target.threadId).slice(0, 8)}`,
      `NEW_THREAD first-creation thread name ${accepted.thread?.name} != branch-<threadId 前 8 位> for ${target.threadId}: ${JSON.stringify(
        accepted.thread,
      )}`,
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
      String(response.idempotencyKey) === String(request.idempotencyKey),
      `accepted command ${i} idempotencyKey ${response?.idempotencyKey} != ${request.idempotencyKey}: ${JSON.stringify(accepted)}`,
    )
    canonicalUuid(response.idempotencyKey, `accepted.commands[${i}].idempotencyKey`)
    assert(
      String(response.threadId) === String(target.threadId),
      `accepted command ${i} threadId ${response?.threadId} != target ${target.threadId}: ${JSON.stringify(accepted)}`,
    )
    assert(
      /^[1-9]\d*$/.test(String(response.sequence)),
      `accepted command ${i} sequence must be positive decimal: ${JSON.stringify(response)}`,
    )
  }
  return accepted
}

/**
 * NEW_SESSION 原子创建：创建 Session + ROOT + Thread 并接受本批 commands。
 * rootSettings 通常是 branchSettingsOf(...) 的完整分支草稿；sessionId/threadId 由调用方预分配。
 */
export async function createNewSession(
  ctx,
  { owner, sessionId, threadId, rootSettings, yoloEnabled = false, commands },
) {
  return acceptCommandBatch(ctx, {
    owner,
    target: newSessionTarget({ sessionId, threadId, rootSettings, yoloEnabled }),
    commands,
  })
}

/** NEW_THREAD 原子创建：在既有 Session 的既有 Entry 下开新 Thread（不复制 Entry）。 */
export async function createNewThread(
  ctx,
  { owner, sessionId, startEntryId, threadId, yoloEnabled = false, commands },
) {
  return acceptCommandBatch(ctx, {
    owner,
    target: newThreadTarget({ sessionId, startEntryId, threadId, yoloEnabled }),
    commands,
  })
}

// ---------- Session / Thread 查询 ----------

/** 严格校验 Session 摘要 DTO（name 是服务端派生的必填非空展示名）。 */
function assertSessionSummary(item) {
  canonicalUuid(item?.sessionId, 'session.sessionId')
  assert(
    typeof item.name === 'string' && item.name.trim().length > 0,
    `Session summary name must be non-blank: ${JSON.stringify(item)}`,
  )
  assert(
    typeof item.createdAt === 'string' || typeof item.createdAt === 'number',
    JSON.stringify(item),
  )
  assert(
    typeof item.lastActivityAt === 'string' || typeof item.lastActivityAt === 'number',
    JSON.stringify(item),
  )
  // firstMessagePreview 与名称独立：最近消息预览可为 null，绝不回退为 name/id。
  assert(
    item.firstMessagePreview === null || typeof item.firstMessagePreview === 'string',
    JSON.stringify(item),
  )
  assert(Number.isSafeInteger(item.threadCount) && item.threadCount >= 0, JSON.stringify(item))
  return item
}

/** Chat owner 的 Session 摘要数组（归属时间新到旧）。 */
export async function listChatSessions(ctx, chatId) {
  const { json } = await ctx.call('GET', `/api/ai/chats/${encodeURIComponent(chatId)}/sessions`)
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
    `/api/harness/sessions/${encodeURIComponent(sessionId)}/threads`,
  )
  const threads = envelopeData(json)
  assert(Array.isArray(threads), `expected Thread summary array: ${JSON.stringify(json)}`)
  for (const item of threads) {
    canonicalUuid(item?.threadId, 'thread.threadId')
    // name 是 Thread 的必需非空展示名称（主展示文本，绝不回退为 id）。
    assert(
      typeof item.name === 'string' && item.name.trim().length > 0,
      `Thread summary name must be non-blank: ${JSON.stringify(item)}`,
    )
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
    `/api/harness/sessions/${encodeURIComponent(sessionId)}/entries`,
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
    `/api/harness/threads/${encodeURIComponent(threadId)}`,
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
  let quiescentKey = null
  while (Date.now() <= deadline) {
    const snapshot = await getThreadSnapshot(ctx, threadId)
    const entries = await listSessionEntries(ctx, snapshot.thread.sessionId)
    last = { snapshot, entries }
    const payloads = entries.map((entry) => entry.payloadJson)
    const hasExpectedMessages = expectedTexts.every(
      (text) => payloads.some((payload) => payload.includes(text)),
    )
    const observation = observeQuiescentSnapshot(quiescentKey, snapshot)
    if (hasExpectedMessages && observation.stable) {
      return last
    }
    quiescentKey = hasExpectedMessages ? observation.key : null
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

/** 只读 Environment 注册表；Card UUID id 是 canonical 路由身份，name 是 display name，ready 是统一可用性标记。 */
export async function listEnvironments(ctx) {
  const { json } = await ctx.call('GET', '/api/harness/environments')
  const environments = envelopeData(json)
  assert(Array.isArray(environments), `expected Environment array: ${JSON.stringify(json)}`)
  for (const environment of environments) {
    canonicalUuid(environment.id, 'environment.id')
    assert(
      typeof environment.name === 'string' && environment.name.trim().length > 0,
      `Environment name must be non-empty string: ${JSON.stringify(environment)}`,
    )
    assert(typeof environment.ready === 'boolean', JSON.stringify(environment))
    assert(typeof environment.status === 'string', JSON.stringify(environment))
  }
  return environments
}

export function userMessageCommand(content, idempotencyKey) {
  assert(typeof content === 'string' && content.trim(), 'content required')
  assert(idempotencyKey && typeof idempotencyKey === 'string', 'idempotencyKey required')
  // Strict wire: USER_MESSAGE carries ONLY type/idempotencyKey/contents（TEXT/ATTACHMENT，无文本 shorthand）。
  return { type: 'USER_MESSAGE', idempotencyKey, contents: [{ type: 'TEXT', text: content }] }
}

export function setAgentCommand(agentName, idempotencyKey) {
  assert(agentName && typeof agentName === 'string', 'agentName required')
  assert(idempotencyKey && typeof idempotencyKey === 'string', 'idempotencyKey required')
  return { type: 'SET_AGENT', idempotencyKey, agentName }
}

export function setModelCommand(model, idempotencyKey) {
  assert(model?.providerName && model?.modelName && model?.variant, `model required: ${JSON.stringify(model)}`)
  assert(idempotencyKey && typeof idempotencyKey === 'string', 'idempotencyKey required')
  return { type: 'SET_MODEL', idempotencyKey, model }
}

export function setEnvironmentCommand(environmentName, idempotencyKey) {
  assert(
    arguments.length >= 2,
    'setEnvironmentCommand requires explicit environmentName (string or null) and idempotencyKey',
  )
  canonicalEnvironmentName(environmentName, 'environmentName')
  assert(idempotencyKey && typeof idempotencyKey === 'string', 'idempotencyKey required')
  return { type: 'SET_ENVIRONMENT', idempotencyKey, environmentName }
}

/**
 * 直接更新 Thread YOLO policy（PUT /yolo，version CAS）：同值请求在任何 CAS 之前即成功 no-op；
 * 值变化且 version 不匹配 => 409 STALE_VERSION。返回权威 Thread DTO。
 */
export async function setThreadYolo(ctx, threadId, { expectedVersion, yoloEnabled }) {
  assert(typeof yoloEnabled === 'boolean', 'yoloEnabled must be boolean')
  const { status, json } = await ctx.call(
    'PUT',
    `/api/harness/threads/${encodeURIComponent(threadId)}/yolo`,
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

/**
 * 重命名 Session（PUT /sessions/{id}/name，body={name}）：返回权威 HarnessSessionDTO
 * （sessionId/name/createdAt）。name 规范化由服务端权威处理，helper 只保证请求名非空白。
 */
export async function renameSession(ctx, sessionId, name) {
  assert(typeof name === 'string' && name.trim().length > 0, 'rename session name must be non-blank')
  canonicalUuid(sessionId, 'sessionId')
  const { status, json } = await ctx.call(
    'PUT',
    `/api/harness/sessions/${encodeURIComponent(sessionId)}/name`,
    { name },
  )
  assert(status === 200, `rename session status ${status}: ${JSON.stringify(json)}`)
  const renamed = envelopeData(json)
  canonicalUuid(renamed?.sessionId, 'renamed.sessionId')
  assert(String(renamed.sessionId) === String(sessionId), JSON.stringify(renamed))
  assert(
    typeof renamed.name === 'string' && renamed.name.trim().length > 0,
    `renamed session name must be non-blank: ${JSON.stringify(renamed)}`,
  )
  return renamed
}

/**
 * 重命名 Thread（PUT /threads/{id}/name，body={name}）：返回权威 HarnessThreadDTO。
 * 实际名称变化使 version 精确 +1；规范化同名是 no-op（version 零触碰）——具体断言由 case 负责。
 */
export async function renameThread(ctx, threadId, name) {
  assert(typeof name === 'string' && name.trim().length > 0, 'rename thread name must be non-blank')
  canonicalUuid(threadId, 'threadId')
  const { status, json } = await ctx.call(
    'PUT',
    `/api/harness/threads/${encodeURIComponent(threadId)}/name`,
    { name },
  )
  assert(status === 200, `rename thread status ${status}: ${JSON.stringify(json)}`)
  const renamed = envelopeData(json)
  threadIdOf(renamed)
  assert(String(renamed.threadId) === String(threadId), JSON.stringify(renamed))
  return renamed
}

/** 原子 stop（stopRequestId 幂等 replay；version CAS）。 */
export async function stopThread(ctx, threadId, { stopRequestId, expectedVersion }) {
  assert(stopRequestId && typeof stopRequestId === 'string', 'stopRequestId required')
  const { status, json } = await ctx.call(
    'POST',
    `/api/harness/threads/${encodeURIComponent(threadId)}/stop`,
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
 * Cleanup 专用 stop：snapshot/version CAS 之间若发生推进，复用同一 stopRequestId
 * 重读最新 version 重试；这只处理 STALE_VERSION，不吞掉其他 HTTP 错误。
 */
export async function stopThreadForCleanup(
  ctx,
  threadId,
  { maxAttempts = 8, retryDelayMs = 100 } = {},
) {
  assert(threadId && typeof threadId === 'string', 'threadId required')
  const stopRequestId = cid()
  let lastError = null
  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    const snapshot = await getThreadSnapshot(ctx, threadId)
    const active =
      snapshot.thread.status !== 'IDLE'
      || snapshot.thread.processing
      || snapshot.queuedCommands.length > 0
      || snapshot.modelInvocation !== null
      || snapshot.toolInvocations.length > 0
    if (!active) {
      return
    }
    try {
      await stopThread(ctx, threadId, {
        stopRequestId,
        expectedVersion: snapshot.thread.version,
      })
      return
    } catch (error) {
      if (
        !(error instanceof HttpError)
        || error.status !== 409
        || !String(error.body).includes('STALE_VERSION')
      ) {
        throw error
      }
      lastError = error
      await sleep(retryDelayMs)
    }
  }
  throw lastError || new Error(`thread cleanup stop exhausted: ${threadId}`)
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
    'PUT',
    `/api/harness/threads/${encodeURIComponent(threadId)}/tool-invocations/${encodeURIComponent(toolInvocationId)}/approval`,
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
 * modelInvocation=null、toolInvocations 为空，且同一 cursor/version 必须连续观测两次。
 * 单次 quiescent 可能落在异步 Work 已移除队列、尚未提交 Thread 尾部状态的瞬时窗口，不能作为
 * 后续 CAS 写入的稳定 cursor。
 */
export async function waitForQuiescentThread(
  ctx,
  threadId,
  { timeoutMs = 30_000, intervalMs = 250 } = {},
) {
  const deadline = Date.now() + timeoutMs
  let last = null
  let quiescentKey = null
  while (Date.now() <= deadline) {
    const snapshot = await getThreadSnapshot(ctx, threadId)
    last = snapshot.thread
    const observation = observeQuiescentSnapshot(quiescentKey, snapshot)
    if (observation.stable) {
      return last
    }
    quiescentKey = observation.key
    await sleep(intervalMs)
  }
  throw new Error(`thread did not become quiescent: ${JSON.stringify(last)}`)
}

/**
 * 将完整快照推进为稳定 quiescent 观测。
 *
 * <p>返回的 key 只在快照当前满足完整 quiescent 条件时存在；stable 仅在前一轮 key 与当前
 * thread/cursor/version 完全一致时为 true。任何 active 状态或 cursor 变化都会清空稳定候选。
 */
export function observeQuiescentSnapshot(previousKey, snapshot) {
  const thread = snapshot?.thread
  if (
    thread?.status !== 'IDLE'
    || thread.processing
    || (snapshot?.queuedCommands || []).length !== 0
    || snapshot?.modelInvocation !== null
    || (snapshot?.toolInvocations || []).length !== 0
  ) {
    return { key: null, stable: false }
  }
  const key = [
    thread.threadId,
    thread.headEntryId,
    thread.nextCommandSequence,
    thread.version,
  ].map(String).join('\u0000')
  return { key, stable: key === previousKey }
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
  return waitForModelDeltaAfterEventSubscribed(
    ctx,
    threadId,
    startWork,
    timeoutMs,
    parseModelTextDelta,
    'MODEL_DELTA/TEXT_DELTA',
  )
}

/**
 * 等待第一条非空模型内容增量。模型可能先流出 thinking，再流出 text；stop/partial
 * 契约允许两者进入 durable ASSISTANT_ABORTED，因此取消时机不应绑定到 text。
 */
export async function waitForModelContentDeltaAfterEventSubscribed(
  ctx,
  threadId,
  startWork,
  { timeoutMs = 90_000 } = {},
) {
  return waitForModelDeltaAfterEventSubscribed(
    ctx,
    threadId,
    startWork,
    timeoutMs,
    parseModelContentDelta,
    'MODEL_DELTA/TEXT_DELTA or THINKING_DELTA',
  )
}

async function waitForModelDeltaAfterEventSubscribed(
  ctx,
  threadId,
  startWork,
  timeoutMs,
  parseDelta,
  description,
) {
  const expectedThreadId = canonicalUuid(threadId, 'threadId')
  assert(typeof startWork === 'function', 'startWork must be a function')
  assert(typeof parseDelta === 'function', 'parseDelta must be a function')
  assert(typeof description === 'string' && description, 'description must be non-blank')
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
          const signal = parseDelta(frame.data, expectedThreadId)
          if (signal != null) {
            return { matched: true, value: signal }
          }
        }
        return { matched: false }
      },
      `${description} on thread ${expectedThreadId}`,
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
  const signal = parseModelContentDelta(envelope, expectedThreadId)
  if (signal?.kind !== 'TEXT_DELTA') return null
  return {
    threadId: signal.threadId,
    invocationId: signal.invocationId,
    attempt: signal.attempt,
    text: signal.text,
    createdAt: signal.createdAt,
  }
}

function parseModelContentDelta(envelope, expectedThreadId) {
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
    || (envelope.payload.kind !== 'TEXT_DELTA' && envelope.payload.kind !== 'THINKING_DELTA')
    || typeof envelope.payload.text !== 'string'
    || !envelope.payload.text.trim()
  ) {
    return null
  }
  return {
    threadId: envelope.threadId,
    invocationId: envelope.subjectId,
    attempt: envelope.attempt,
    kind: envelope.payload.kind,
    text: envelope.payload.text,
    createdAt: envelope.createdAt,
  }
}

function isRecord(value) {
  return typeof value === 'object' && value != null && !Array.isArray(value)
}
