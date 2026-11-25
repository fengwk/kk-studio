import {
  assert,
  cid,
  HttpError,
  sleep,
} from './http.mjs'

/**
 * Harness Runtime 的当前契约辅助函数。
 *
 * 当前端点：
 * - POST /api/harness/threads/{id}/command-batches：Thread 命令批次
 * - GET  /api/harness/threads/{id}：一致快照（含 stopReceipts）
 * - PUT  /api/harness/threads/{id}/yolo；POST /api/harness/threads/{id}/stop
 * - PUT  /api/harness/threads/{id}/tool-invocations/{toolInvocationId}/approval
 * - GET  /api/harness/builtin/sessions/{sessionId}/entries/{entryId}/provider-request：历史请求预览
 * - WS   /ws/events：应用级 Thread/Canvas 事件订阅
 * - GET  /api/platform/environments：只读 Environment 注册表
 * - /api/chat-spaces：工作区及 Session、branch、fork 创建；创建请求使用 canonical UUID Idempotency-Key。
 *
 * 命令批次命令按固定顺序携带 SET_EXECUTION、SET_ENVIRONMENT 前缀及恰一条末尾 USER_MESSAGE；
 * CUSTOM_MESSAGE、NOTIFICATION 在产品 HTTP 面被拒绝。
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

/**
 * 校验 Thread 投影的不可变执行父关系并返回它：根 Thread 为 null，非根必须是 canonical UUID。
 *
 * <p>{@code parentThreadId} 只表达执行父子归属，不进入 Session 历史；委派产生的子 Thread 必须指回
 * 发起它的父 Thread。断言父关系才能证明「结果来自同一执行树」，同 Session 的另一条线程不会混淆。
 */
export function threadParentIdOf(thread) {
  const parentThreadId = thread?.parentThreadId
  if (parentThreadId !== null) {
    canonicalUuid(parentThreadId, 'parentThreadId')
  }
  return parentThreadId
}

/**
 * 严格校验 Thread 投影的执行根与 YOLO 开关：{@code rootThreadId} 必须是 canonical UUID，
 * {@code yoloEnabled} 必须是显式 boolean（缺失字段绝不兜底为 false）。执行根指向自身且可开启
 * YOLO；子代理指向执行根、自身开关恒为 false。返回校验后的 {@code yoloEnabled}。
 */
export function threadYoloOf(thread, label = 'thread') {
  const threadId = canonicalUuid(thread?.threadId, `${label}.threadId`)
  const parentThreadId = threadParentIdOf(thread)
  const rootThreadId = canonicalUuid(thread?.rootThreadId, `${label}.rootThreadId`)
  assert(
    typeof thread.yoloEnabled === 'boolean',
    `${label}.yoloEnabled must be an explicit boolean: ${JSON.stringify(thread)}`,
  )
  if (parentThreadId === null) {
    assert(
      rootThreadId === threadId,
      `${label}.rootThreadId must point to the root thread itself: ${JSON.stringify(thread)}`,
    )
  } else {
    assert(
      rootThreadId !== threadId,
      `${label}.rootThreadId must point to the execution root, not the child: ${JSON.stringify(thread)}`,
    )
    assert(
      thread.yoloEnabled === false,
      `${label}.yoloEnabled must be false for a child thread: ${JSON.stringify(thread)}`,
    )
  }
  return thread.yoloEnabled
}

/** 校验执行根 Thread 的 YOLO 开关精确等于 {@code enabled}，并强制根自指约束。 */
export function assertRootYoloEnabled(thread, enabled, label = 'thread') {
  assert(typeof enabled === 'boolean', `${label} expected YOLO value must be boolean`)
  assert(
    thread?.parentThreadId === null,
    `${label} must be a root thread: ${JSON.stringify(thread)}`,
  )
  const actual = threadYoloOf(thread, label)
  assert(
    actual === enabled,
    `${label}.yoloEnabled ${actual} != ${enabled}: ${JSON.stringify(thread)}`,
  )
  return actual
}

/** 严格校验 Thread 投影 DTO 的 canonical UUID 标识字段、执行控制与父关系并返回 threadId。 */
export function threadIdOf(thread) {
  const threadId = canonicalUuid(thread?.threadId, 'threadId')
  canonicalUuid(thread.sessionId, 'sessionId')
  canonicalUuid(thread.headEntryId, 'headEntryId')
  threadYoloOf(thread)
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
  // 执行控制只有 RUNNABLE / STOPPED；status 只描述本 Thread 自身，二者正交。
  assert(
    thread.executionControl === 'RUNNABLE' || thread.executionControl === 'STOPPED',
    `executionControl must be RUNNABLE|STOPPED: ${JSON.stringify(thread)}`,
  )
  assert(
    THREAD_RUNTIME_STATUSES.has(thread.status),
    `unknown ThreadRuntimeStatus: ${JSON.stringify(thread.status)}`,
  )
  return threadId
}

/** Thread 自身执行活动、退出等待与空闲状态；STOP 与这些状态正交。 */
const THREAD_RUNTIME_STATUSES = new Set(['RUNNING', 'EXIT_PENDING', 'WAITING', 'IDLE'])

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

/** 由模型名和 variant 构造 Builtin execution。 */
export function builtinExecution(modelName, variant = 'default') {
  assert(modelName, `modelName required: ${modelName}`)
  return {
    driver: 'builtin',
    settings: {
      modelName,
      variant,
    },
  }
}

/** 由 Agent + Execution + Environment 引用构造完整 branchSettings（当前契约精确为 agentName + execution + environmentName）。 */
export function branchSettingsOf(agent, executionOrModel, environmentName = null) {
  assert(agent?.name, `agent name required: ${JSON.stringify(agent)}`)
  const execution = executionOrModel?.driver
    ? executionOrModel
    : builtinExecution(executionOrModel?.modelName, executionOrModel?.variant)
  assert(execution?.driver && execution?.settings, `execution required: ${JSON.stringify(executionOrModel)}`)
  canonicalEnvironmentName(environmentName, 'environmentName')
  return {
    agentName: agent.name,
    execution,
    environmentName,
  }
}

/**
 * THREAD target：既有 Thread 续写的 helper 路由描述符。
 *
 * <p>它不是 HTTP wire：真实请求发往 {@code POST /api/harness/threads/{threadId}/command-batches}，body 只含 {@code commands}
 * 与（非单条纯 USER_MESSAGE 批次必需的）{@code expectedHeadEntryId, expectedNextCommandSequence}，不带 target/type。
 */
export function threadTarget({ threadId, expectedHeadEntryId, expectedNextCommandSequence }) {
  canonicalUuid(threadId, 'target.threadId')
  if (expectedHeadEntryId == null && expectedNextCommandSequence == null) {
    return { type: 'THREAD', threadId }
  }
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

/** 接受一个既有 Thread 的命令批次并严格校验响应形状。 */
export async function acceptCommandBatch(ctx, { target, commands }) {
  assert(target?.type === 'THREAD', `target.type must be THREAD: ${JSON.stringify(target)}`)
  assert(Array.isArray(commands) && commands.length > 0, 'commands required')
  for (const command of commands) {
    assert(
      command && typeof command === 'object' && command.type && command.idempotencyKey,
      `invalid command: ${JSON.stringify(command)}`,
    )
  }

  // 续写请求只带 commands 与（可选）cursor，不发送 helper target。
  const threadId = canonicalUuid(target.threadId, 'target.threadId')
  const body = { commands }
  if (target.expectedHeadEntryId != null || target.expectedNextCommandSequence != null) {
    body.expectedHeadEntryId = canonicalUuid(
      target.expectedHeadEntryId,
      'target.expectedHeadEntryId',
    )
    body.expectedNextCommandSequence = positiveDecimal(
      target.expectedNextCommandSequence,
      'target.expectedNextCommandSequence',
    )
  }
  const { status, json } = await ctx.call(
    'POST',
    `/api/harness/threads/${encodeURIComponent(threadId)}/command-batches`,
    body,
  )
  assert(status === 202, `accept thread command batch status ${status}: ${JSON.stringify(json)}`)
  const accepted = json
  assertAcceptedCommands(accepted)
  assert(
    String(accepted.thread?.threadId) === threadId,
    `accepted threadId ${accepted.thread?.threadId} != target ${threadId}: ${JSON.stringify(accepted)}`,
  )
  assertAcceptedCommandList(accepted, commands, threadId)
  return accepted
}

/** acceptedCommands 与请求 commands 逐项一致（type/idempotencyKey/threadId/positive sequence）。 */
function assertAcceptedCommandList(accepted, commands, threadId) {
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
      String(response.threadId) === String(threadId),
      `accepted command ${i} threadId ${response?.threadId} != target ${threadId}: ${JSON.stringify(accepted)}`,
    )
    assert(
      /^[1-9]\d*$/.test(String(response.sequence)),
      `accepted command ${i} sequence must be positive decimal: ${JSON.stringify(response)}`,
    )
  }
}

/** Create a Chat Space with an explicit default agent when a case needs isolated model behavior. */
export async function createWorkspaceSpace(ctx, { title, defaultAgentName }) {
  assert(typeof title === 'string' && title.trim(), 'space title must be non-blank')
  assert(typeof defaultAgentName === 'string' && defaultAgentName.trim(), 'defaultAgentName must be non-blank')
  const response = await ctx.request('/api/chat-spaces', {
    method: 'POST',
    headers: { 'Idempotency-Key': cid() },
    body: { title, rootFolderName: 'E2E', defaultAgentName, defaultYoloEnabled: false },
  })
  const space = response.json ?? response
  canonicalUuid(space.id, 'space.id')
  return space
}

/** Create a workspace Session through its public idempotent creation API. */
export async function createWorkspaceSession(
  ctx,
  spaceId,
  { idempotencyKey, folderId = null, message, modelSelection = null, environmentId = null, yoloEnabled = null },
) {
  canonicalUuid(spaceId, 'spaceId')
  canonicalUuid(idempotencyKey, 'idempotencyKey')
  if (folderId !== null) canonicalUuid(folderId, 'folderId')
  assert(Array.isArray(message) && message.length > 0, 'session message must be a non-empty content array')
  const response = await ctx.request(`/api/chat-spaces/${encodeURIComponent(spaceId)}/sessions`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: { folderId, message, modelSelection, environmentId, yoloEnabled },
  })
  const created = response.json ?? response
  canonicalUuid(created.sessionId, 'created.sessionId')
  canonicalUuid(created.threadId, 'created.threadId')
  canonicalUuid(created.commandId, 'created.commandId')
  nonNegativeDecimal(created.treeVersion, 'created.treeVersion')
  return created
}

/** Delete a Session and release its temporary Agent from the owning Chat Space defaults. */
export async function deleteWorkspaceSession(ctx, spaceId, sessionId) {
  canonicalUuid(spaceId, 'spaceId')
  canonicalUuid(sessionId, 'sessionId')
  const tree = (await ctx.call('GET', `/api/chat-spaces/${encodeURIComponent(spaceId)}/tree`)).json
  await ctx.call(
    'DELETE',
    `/api/chat-spaces/${encodeURIComponent(spaceId)}/sessions/${encodeURIComponent(sessionId)}?expectedTreeVersion=${encodeURIComponent(tree.treeVersion)}`,
  )
  const spaces = (await ctx.call('GET', '/api/chat-spaces')).json
  assert(Array.isArray(spaces), `expected Chat Space list: ${JSON.stringify(spaces)}`)
  const space = spaces.find((item) => item.id === spaceId)
  assert(space, `Chat Space ${spaceId} missing from collection`)
  if (space.defaultAgentName != null) {
    await ctx.call('PATCH', `/api/chat-spaces/${encodeURIComponent(spaceId)}`, {
      expectedVersion: space.version,
      defaultAgentName: null,
    })
  }
}

/** Read a Chat Space resource tree for workspace verification and cleanup. */
export async function getWorkspaceTree(ctx, spaceId) {
  canonicalUuid(spaceId, 'spaceId')
  const response = await ctx.call('GET', `/api/chat-spaces/${encodeURIComponent(spaceId)}/tree`)
  assert(response.status === 200, `get workspace tree failed: HTTP ${response.status}`)
  const tree = response.json ?? response
  assert(tree && Array.isArray(tree.folders), `expected workspace tree: ${JSON.stringify(tree)}`)
  return tree
}

/** Release temporary defaults and archive a test Chat Space when another active space exists. */
export async function archiveWorkspaceSpace(ctx, spaceId) {
  canonicalUuid(spaceId, 'spaceId')
  const spaces = (await ctx.call('GET', '/api/chat-spaces')).json
  assert(Array.isArray(spaces), `expected Chat Space list: ${JSON.stringify(spaces)}`)
  const activeSpaces = spaces.filter((item) => !item.archivedAt)
  let space = activeSpaces.find((item) => item.id === spaceId)
  if (!space) return
  if (space.defaultAgentName != null || space.defaultEnvironmentId != null) {
    const patched = await ctx.call('PATCH', `/api/chat-spaces/${encodeURIComponent(spaceId)}`, {
      expectedVersion: space.version,
      defaultAgentName: null,
      defaultEnvironmentId: null,
    })
    space = patched.json ?? { ...space, version: String(BigInt(space.version) + 1n) }
  }
  if (activeSpaces.length > 1) {
    await ctx.call('PATCH', `/api/chat-spaces/${encodeURIComponent(spaceId)}`, {
      expectedVersion: space.version,
      archived: true,
    })
  }
}

/** Create a same-Session branch at a completed turn boundary. */
export async function createWorkspaceBranch(ctx, spaceId, sessionId, {
  idempotencyKey,
  sourceThreadId,
  turnEndEntryId,
  title,
}) {
  return createWorkspaceTurnCut(ctx, spaceId, sessionId, 'branches', {
    idempotencyKey, sourceThreadId, turnEndEntryId, title,
  })
}

/** Fork a completed turn into a new Session in the same Chat Space. */
export async function createWorkspaceFork(ctx, spaceId, sessionId, {
  idempotencyKey,
  sourceThreadId,
  turnEndEntryId,
  title,
}) {
  return createWorkspaceTurnCut(ctx, spaceId, sessionId, 'forks', {
    idempotencyKey, sourceThreadId, turnEndEntryId, title,
  })
}

async function createWorkspaceTurnCut(ctx, spaceId, sessionId, operation, {
  idempotencyKey, sourceThreadId, turnEndEntryId, title,
}) {
  canonicalUuid(spaceId, 'spaceId')
  canonicalUuid(sessionId, 'sessionId')
  canonicalUuid(idempotencyKey, 'idempotencyKey')
  canonicalUuid(sourceThreadId, 'sourceThreadId')
  canonicalUuid(turnEndEntryId, 'turnEndEntryId')
  assert(typeof title === 'string' && title.trim(), 'title must be non-blank')
  const response = await ctx.request(
    `/api/chat-spaces/${encodeURIComponent(spaceId)}/sessions/${encodeURIComponent(sessionId)}/${operation}`,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: { sourceThreadId, turnEndEntryId, title },
    },
  )
  return response.json ?? response
}

/**
 * 历史请求预览响应 DTO 的精确 wire 字段集；null 字段由全局 NON_NULL 省略。
 *
 * <p>{@code notice} 是固定能力声明而非噪声；历史预览必须声明它按当前 catalog 与 Provider 配置重建，不是当时的原始字节。
 */
const PREVIEW_FIELDS = [
  'kind',
  'generatedAt',
  'providerType',
  'modelName',
  'bodyByteSize',
  'bodyJson',
  'sourceHeadEntryId',
  'notice',
]

/** 历史预览的固定能力声明（镜像 {@code HarnessProviderRequestPreviewDTO.HISTORICAL_NOTICE}）。 */
export const HISTORICAL_PREVIEW_NOTICE =
  'Preview is reconstructed from the current catalog, provider configuration and model '
  + 'selection against the recorded history before this output. It is not the original '
  + 'request that was sent, and it does not consume uploads or write anything.'

/** 严格校验一份预览响应：精确字段、kind、固定 notice、来源 head 与请求体自洽。 */
export function assertProviderPreview(preview) {
  assert(preview && typeof preview === 'object' && !Array.isArray(preview), JSON.stringify(preview))
  const actual = Object.keys(preview).sort()
  assert(
    actual.length === PREVIEW_FIELDS.length
      && actual.every((field, index) => field === [...PREVIEW_FIELDS].sort()[index]),
    `preview fields must be ${[...PREVIEW_FIELDS].sort().join(',')}, got ${actual.join(',')}`,
  )
  assert(preview.kind === 'HISTORICAL_REQUEST_PREVIEW', `preview kind ${preview.kind} is not historical: ${JSON.stringify(preview)}`)
  assert(
    typeof preview.generatedAt === 'number' && Number.isSafeInteger(preview.generatedAt),
    `preview generatedAt must be an epoch-millisecond integer: ${JSON.stringify(preview.generatedAt)}`,
  )
  assert(
    typeof preview.providerType === 'string' && preview.providerType.trim().length > 0
      && typeof preview.modelName === 'string' && preview.modelName.trim().length > 0,
    `preview must identify the provider/model it was planned for: ${JSON.stringify(preview)}`,
  )
  assert(
    Number.isSafeInteger(preview.bodyByteSize) && preview.bodyByteSize >= 0,
    `preview bodyByteSize must be a non-negative integer: ${JSON.stringify(preview)}`,
  )
  assert(
    typeof preview.bodyJson === 'string' && preview.bodyJson.length > 0,
    `preview bodyJson must be a non-empty string: ${JSON.stringify(preview)}`,
  )
  assert(
    Buffer.byteLength(preview.bodyJson, 'utf8') === preview.bodyByteSize,
    `preview bodyByteSize must be the UTF-8 byte length of the untruncated bodyJson: ${JSON.stringify({
      bodyByteSize: preview.bodyByteSize,
      actual: Buffer.byteLength(preview.bodyJson, 'utf8'),
    })}`,
  )
  canonicalUuid(preview.sourceHeadEntryId, 'preview.sourceHeadEntryId')
  assert(
    preview.notice === HISTORICAL_PREVIEW_NOTICE,
    `preview notice must be the fixed historical capability statement: ${JSON.stringify(preview.notice)}`,
  )
  return preview
}

/**
 * 历史请求预览：GET /api/harness/builtin/sessions/{sessionId}/entries/{entryId}/provider-request。
 *
 * <p>按当前 catalog 与 Provider 配置重建该模型输出之前的请求前缀；响应 MUST 是 HISTORICAL_REQUEST_PREVIEW，
 * 不得冒充当时的原始发送字节。
 */
export async function previewHistoricalRequest(ctx, sessionId, entryId) {
  const id = canonicalUuid(sessionId, 'sessionId')
  const entry = canonicalUuid(entryId, 'entryId')
  const { status, json } = await ctx.call(
    'GET',
    `/api/harness/builtin/sessions/${encodeURIComponent(id)}/entries/${encodeURIComponent(entry)}/provider-request`,
  )
  assert(status === 200, `historical preview status ${status}: ${JSON.stringify(json)}`)
  return assertProviderPreview(json)
}

/**
 * Session Entry 的读取时费用投影：`usageCost` 永远存在（NON_NULL 全局省略只对 null 生效，DTO 声明 ALWAYS），
 * 未计价/非模型输出时为 null，否则是 {@code {currency, amount}} 且 amount 是精确十进制文本（不做展示舍入）。
 * 费用只是查询投影，绝不进入 payload —— 这里同时锁住这两条事实。
 */
export function assertEntryUsageCost(entry) {
  assert(Object.hasOwn(entry, 'usageCost'), `entry must expose usageCost: ${JSON.stringify(entry)}`)
  const cost = entry.usageCost
  if (cost == null) {
    return null
  }
  assert(cost && typeof cost === 'object' && !Array.isArray(cost), JSON.stringify(cost))
  assert(Object.keys(cost).sort().join(',') === 'amount,currency', JSON.stringify(cost))
  assert(typeof cost.currency === 'string' && cost.currency.trim().length > 0, JSON.stringify(cost))
  assert(
    typeof cost.amount === 'string' && /^\d+(\.\d+)?$/.test(cost.amount),
    `usageCost.amount must be a non-negative decimal string (never a number): ${JSON.stringify(cost)}`,
  )
  assert(
    entry.payload != null && typeof entry.payload === 'object' && !Array.isArray(entry.payload)
      && !JSON.stringify(entry.payload).includes('usageCost'),
    `usageCost must not be persisted inside payload: ${JSON.stringify(entry)}`,
  )
  return cost
}

/** 校验一条 Session Entry 的核心只读形状（同一 DTO 同时用于 entries 列表与 Thread snapshot.entries）。 */
export function assertSessionEntry(entry, { sessionId } = {}) {
  canonicalUuid(entry?.entryId, 'entry.entryId')
  canonicalUuid(entry.sessionId, 'entry.sessionId')
  if (sessionId != null) {
    assert(
      String(entry.sessionId) === String(sessionId),
      `entry.sessionId ${entry.sessionId} != requested sessionId ${sessionId}: ${JSON.stringify(entry)}`,
    )
  }
  if (entry.parentEntryId != null) canonicalUuid(entry.parentEntryId, 'entry.parentEntryId')
  assert(typeof entry.entryType === 'string' && entry.entryType, JSON.stringify(entry))
  assert(
    entry.payload != null && typeof entry.payload === 'object' && !Array.isArray(entry.payload),
    JSON.stringify(entry),
  )
  assertEntryUsageCost(entry)
  return entry
}

export async function getThreadSnapshot(ctx, threadId) {
  const { json } = await ctx.call(
    'GET',
    `/api/harness/threads/${encodeURIComponent(threadId)}`,
  )
  const snapshot = json
  assert(snapshot?.thread, `expected Thread snapshot: ${JSON.stringify(json)}`)
  threadIdOf(snapshot.thread)
  return snapshot
}

export async function getThread(ctx, threadId) {
  return (await getThreadSnapshot(ctx, threadId)).thread
}

/** Thread 快照的 root-to-head entries；与 Session Entry Tree 共用同一只读形状（含 usageCost 投影）。 */
export async function snapshotEntries(ctx, threadId) {
  const entries = (await getThreadSnapshot(ctx, threadId)).entries || []
  for (const entry of entries) {
    assertSessionEntry(entry)
  }
  return entries
}

/**
 * 只读 Environment 注册表；Environment UUID id 是 canonical 路由身份，name 是 display name，ready 是统一可用性标记。
 *
 * {@code statusExpiresAt} 是当前状态的只读时间投影（有效连接取 min(lease, lastSeen+heartbeat)）：
 * 无连接或失效时显式为 null，只提示浏览器按权威数据回读一次，不是固定轮询。
 */
export async function listEnvironments(ctx) {
  const { json } = await ctx.call('GET', '/api/platform/environments')
  const environments = json
  assert(Array.isArray(environments), `expected Environment array: ${JSON.stringify(json)}`)
  for (const environment of environments) {
    canonicalUuid(environment.id, 'environment.id')
    assert(
      typeof environment.name === 'string' && environment.name.trim().length > 0,
      `Environment name must be non-empty string: ${JSON.stringify(environment)}`,
    )
    if (environment.daemonVersion != null) {
      assert(
        typeof environment.daemonVersion === 'string' && environment.daemonVersion.trim().length > 0,
        `environment.daemonVersion must be a non-empty string when present: ${JSON.stringify(environment)}`,
      )
    }
    assert(typeof environment.ready === 'boolean', JSON.stringify(environment))
    assert(typeof environment.status === 'string', JSON.stringify(environment))
    assert(Object.hasOwn(environment, 'statusExpiresAt'), 'environment must expose statusExpiresAt')
    const expiresAt = environment.statusExpiresAt
    if (expiresAt != null) {
      assert(
        typeof expiresAt === 'number' && Number.isSafeInteger(expiresAt),
        `statusExpiresAt must be an epoch-millisecond integer: ${JSON.stringify(environment)}`,
      )
      assert(
        environment.status !== 'OFFLINE',
        `OFFLINE environment must not advertise a live statusExpiresAt: ${JSON.stringify(environment)}`,
      )
    }
  }
  return environments
}

export function userMessageCommand(content, idempotencyKey) {
  assert(typeof content === 'string' && content.trim(), 'content required')
  assert(idempotencyKey && typeof idempotencyKey === 'string', 'idempotencyKey required')
  // Strict wire: USER_MESSAGE carries ONLY type/idempotencyKey/contents（TEXT/ATTACHMENT，无文本 shorthand）。
  return { type: 'USER_MESSAGE', idempotencyKey, contents: [{ type: 'TEXT', text: content }] }
}

export function setExecutionCommand(executionOrModel, idempotencyKey) {
  assert(idempotencyKey && typeof idempotencyKey === 'string', 'idempotencyKey required')
  const execution = executionOrModel?.driver
    ? executionOrModel
    : builtinExecution(executionOrModel?.modelName, executionOrModel?.variant)
  assert(execution?.driver && execution?.settings, `execution required: ${JSON.stringify(executionOrModel)}`)
  return { type: 'SET_EXECUTION', idempotencyKey, execution }
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
 * 直接更新 Thread YOLO 开关（PUT /yolo）：只提交目标开关，不携带 Thread version。
 * 同值请求是 no-op；值变化时 version 精确 +1。返回权威 Thread DTO。
 */
export async function setThreadYolo(ctx, threadId, { yoloEnabled }) {
  assert(typeof yoloEnabled === 'boolean', 'yoloEnabled must be boolean')
  const { status, json } = await ctx.call(
    'PUT',
    `/api/harness/threads/${encodeURIComponent(threadId)}/yolo`,
    { yoloEnabled },
  )
  assert(status === 200, `set thread yolo status ${status}: ${JSON.stringify(json)}`)
  const updated = json
  threadIdOf(updated)
  return updated
}

/** 校验一条 StoppedThreadReceiptDTO：身份、边界、计数与仅含 USER_MESSAGE 的 cancelledInputs。 */
function assertStoppedThreadReceipt(receipt) {
  canonicalUuid(receipt?.threadId, 'receipt.threadId')
  canonicalUuid(receipt.stopRequestId, 'receipt.stopRequestId')
  if (receipt.stoppedTurnEndEntryId != null) {
    canonicalUuid(receipt.stoppedTurnEndEntryId, 'receipt.stoppedTurnEndEntryId')
  }
  assert(
    Number.isSafeInteger(receipt.cancelledCommandCount) && receipt.cancelledCommandCount >= 0,
    `cancelledCommandCount must be non-negative integer: ${JSON.stringify(receipt)}`,
  )
  assert(Array.isArray(receipt.cancelledInputs), JSON.stringify(receipt))
  assert(
    receipt.cancelledInputs.length <= receipt.cancelledCommandCount,
    `cancelledInputs must not exceed cancelledCommandCount: ${JSON.stringify(receipt)}`,
  )
  let previous = 0n
  for (const input of receipt.cancelledInputs) {
    assert(/^[1-9]\d*$/.test(String(input?.sequence)), JSON.stringify(input))
    const sequence = BigInt(input.sequence)
    assert(sequence > previous, `cancelledInputs must be ascending: ${JSON.stringify(receipt)}`)
    previous = sequence
    canonicalUuid(input.idempotencyKey, 'cancelledInput.idempotencyKey')
    assert(
      input.type === 'USER_MESSAGE',
      `cancelled input must be USER_MESSAGE: ${JSON.stringify(input)}`,
    )
    assert(
      input.payload != null && typeof input.payload === 'object' && !Array.isArray(input.payload),
      `cancelled input must carry payload: ${JSON.stringify(input)}`,
    )
  }
  return receipt
}

/** 原子 stop（stopRequestId 幂等 replay；version CAS）。返回 {status, thread, stoppedThreads[]}。 */
export async function stopThread(ctx, threadId, { stopRequestId, expectedVersion }) {
  const targetThreadId = canonicalUuid(threadId, 'threadId')
  canonicalUuid(stopRequestId, 'stopRequestId')
  const { status, json } = await ctx.call(
    'POST',
    `/api/harness/threads/${encodeURIComponent(targetThreadId)}/stop`,
    {
      stopRequestId,
      expectedVersion: nonNegativeDecimal(expectedVersion, 'expectedVersion'),
    },
  )
  assert(status === 202, `stop status ${status}: ${JSON.stringify(json)}`)
  const stop = json
  assert(stop?.thread, `expected stop result thread: ${JSON.stringify(json)}`)
  threadIdOf(stop.thread)
  assert(
    stop.status === 'STOPPED' || stop.status === 'REPLAYED',
    `stop status must be STOPPED|REPLAYED: ${JSON.stringify(stop)}`,
  )
  assert(
    Array.isArray(stop.stoppedThreads),
    `stop must return stoppedThreads[] receipts: ${JSON.stringify(stop)}`,
  )
  for (const receipt of stop.stoppedThreads) {
    assertStoppedThreadReceipt(receipt)
  }
  const targetReceipt = stop.stoppedThreads.find((receipt) => receipt.threadId === targetThreadId)
  assert(targetReceipt, `stoppedThreads must contain the target Thread: ${JSON.stringify(stop)}`)
  assert(
    targetReceipt.stopRequestId === stopRequestId,
    `target receipt stopRequestId must equal the request: ${JSON.stringify(stop)}`,
  )
  return stop
}

/**
 * Cleanup 专用 stop：只确保停止已被请求，不等待物理退出。executionControl=STOPPED 表示请求已生效；
 * 否则若仍有本线程活动就发一次 stop。snapshot/version CAS 之间若发生推进，复用同一 stopRequestId
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
    // STOPPED 只表示停止已请求；cleanup 不把请求当作物理退出，也不再重复发 stop。
    const stopRequested = snapshot.thread.executionControl === 'STOPPED'
    const active =
      !stopRequested
      && (
        snapshot.thread.status !== 'IDLE'
        || snapshot.queuedCommands.length > 0
        || snapshot.activeAssistantOutput !== null
        || snapshot.toolInvocations.length > 0
      )
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
      if (!(error instanceof HttpError) || error.status !== 409) {
        throw error
      }
      // 只有 Thread version CAS 过期（conflict + STALE_VERSION）可以重读重试，其它冲突原样抛出。
      let problem = null
      try {
        problem = JSON.parse(error.body)
      } catch {
        problem = null
      }
      if (problem?.code !== 'conflict' || problem?.reason !== 'STALE_VERSION') {
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
 * Java 事实：HarnessToolApprovalDTO {decision: ALLOW|DENY, decisionId, reason}；actor 由服务端从认证主体解析，
 * 请求体绝不允许携带身份字段（携带即未知字段 → 400）。
 * ALLOWED 恢复为 READY 并请求 TOOL Work，DENIED 终止为 FAILED；Thread version touch 一次。
 */
export async function approveToolInvocation(
  ctx,
  threadId,
  toolInvocationId,
  { decision, decisionId, reason = null },
) {
  assert(decision === 'ALLOW' || decision === 'DENY', `decision must be ALLOW|DENY: ${decision}`)
  assert(decisionId && typeof decisionId === 'string', 'decisionId required')
  assert(reason == null || typeof reason === 'string', 'reason must be string|null')
  const { status, json } = await ctx.call(
    'PUT',
    `/api/harness/threads/${encodeURIComponent(threadId)}/tool-invocations/${encodeURIComponent(toolInvocationId)}/approval`,
    { decision, decisionId, reason },
  )
  assert(status === 200, `approval status ${status}: ${JSON.stringify(json)}`)
  const invocation = json
  assert(invocation?.id && invocation.status, `invalid ToolInvocationDTO: ${JSON.stringify(json)}`)
  canonicalUuid(invocation.id, 'invocation.id')
  return invocation
}

/**
 * 轮询完整快照直到 Thread 真正 quiescent 并返回最终 Thread 投影。
 *
 * 判定基于完整 snapshot 而不是单字段：status=IDLE、queuedCommands 为空、
 * activeAssistantOutput=null、toolInvocations 为空，且同一 cursor/version 必须连续观测两次。
 * executionControl=STOPPED 只表示停止已请求，不能替代活动状态判定。
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
  assert(
    snapshot && typeof snapshot === 'object' && 'activeAssistantOutput' in snapshot,
    'snapshot must declare activeAssistantOutput',
  )
  if (
    thread?.status !== 'IDLE'
    || (snapshot?.queuedCommands || []).length !== 0
    || snapshot.activeAssistantOutput !== null
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
  assert(typeof ctx?.openWebSocket === 'function', 'E2E context must expose openWebSocket(path)')
  assert(Number.isFinite(timeoutMs) && timeoutMs > 0, `invalid event timeout: ${timeoutMs}`)

  const deadline = Date.now() + timeoutMs
  const controller = new AbortController()
  const socket = ctx.openWebSocket('/ws/events')
  try {
    await waitForSocketOpen(socket, deadline, controller.signal)
    socket.send(JSON.stringify({
      type: 'subscribe',
      resource: { kind: 'thread', id: expectedThreadId },
    }))
    await waitForApplicationEvent(
      socket,
      deadline,
      controller.signal,
      (frame) => {
        if (
          frame?.type === 'subscribed'
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
          frame?.type === 'event'
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
      if (frame?.type === 'error') {
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
