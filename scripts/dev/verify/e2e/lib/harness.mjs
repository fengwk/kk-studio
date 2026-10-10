import {
  assert,
  cid,
  envelopeData,
  HttpError,
  sleep,
} from './http.mjs'
import { FramedEventSocket, eventUrl } from './framed-event-socket.mjs'

/**
 * Harness Runtime 单轨契约 helper（创建型 owner-aware batch + owner-free Thread 续写 + Session/Thread 查询）。
 *
 * 端点事实源（web 模块）：
 * - POST /api/harness/command-batches                 创建型产品写入口：owner + NEW_SESSION/NEW_THREAD（202 accepted）
 * - POST /api/harness/threads/{id}/command-batches    既有 Thread 续写：owner-free，body 只带 cursor 与 commands
 * - POST /api/harness/threads/{id}/provider-request-preview  同一 owner-free body，200 返回发送前请求体
 * - GET  /api/ai/chats/{chatId}/sessions       Chat owner 的 Session 摘要（新到旧）
 * - GET  /api/harness/sessions/{sessionId}/threads   Session 下 Thread 摘要
 * - GET  /api/harness/sessions/{sessionId}/entries   完整不可变 Entry Tree
 * - GET  /api/harness/threads/{id}             一致快照（单事务，含 stopReceipts）
 * - PUT  /api/harness/threads/{id}/yolo        {yoloEnabled}（单字段幂等，不与 Thread version 做 CAS）
 * - POST /api/harness/threads/{id}/stop        {stopRequestId,expectedVersion}（同 id 幂等 replay，返回每 Thread 回执）
 * - PUT  /api/harness/threads/{id}/tool-invocations/{toolInvocationId}/approval
 * - PUT  /api/harness/sessions/{id}/name       {name}（200 权威 HarnessSessionDTO）
 * - PUT  /api/harness/threads/{id}/name        {name}（200 权威 HarnessThreadDTO）
 * - WS   /api/events/v1                        应用级 Thread/Canvas 事件订阅
 * - GET  /api/harness/environments             只读 Environment 注册表（Card UUID id = canonical 路由身份）
 *
 * 创建型 owner 只接受 CHAT（ISSUE_AGENT 由 Issue 业务工作流拥有）；已有 Thread 的 Continue/Preview 不再需要 owner：
 * - NEW_SESSION{sessionId,threadId,rootSettings,yoloEnabled}：新建 Session + ROOT + Thread
 * - NEW_THREAD{sessionId,startEntryId,threadId,threadName,yoloEnabled}：在既有 Session 的合法 fork 边界
 *   （ROOT 或已闭合 TURN_END）下开新 Thread；threadName 是必填的分支显示名，创建前按 Names 规则规范化并进入创建请求身份
 * - THREAD{threadId,expectedHeadEntryId,expectedNextCommandSequence}：helper 内部路由为 owner-free body
 * commands 必须是固定顺序 SET_AGENT,SET_MODEL,SET_ENVIRONMENT 前缀 +
 * 恰一条末尾 USER_MESSAGE / GOAL；CUSTOM_MESSAGE、NOTIFICATION、SET_CONTRIBUTOR_STATE 在产品 HTTP 面被拒绝。
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
 * 分支显示名的规范化与校验，镜像后端 {@code Names.normalize}：把任意 Unicode 空白折叠为单个空格、去掉首尾，
 * 结果必须非空且至多 256 个码点（超长是调用方错误，绝不截断）。返回规范化后的名称。
 */
export function normalizeThreadName(value) {
  assert(typeof value === 'string', `threadName must be a string: ${JSON.stringify(value)}`)
  const collapsed = value.replace(/\s+/gu, ' ').trim()
  assert(collapsed.length > 0, 'threadName must not be blank')
  assert(
    [...collapsed].length <= 256,
    `threadName must not exceed 256 code points: ${JSON.stringify(value)}`,
  )
  return collapsed
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
 * 严格校验 Thread 投影的持久 YOLO policy（取代旧 boolean）：{@code mode} 只能是
 * ENABLE/DISABLE/FOLLOW；非 FOLLOW（执行根）必须显式为 null 的 {@code rootThreadId}，FOLLOW
 * （子代理）必须携带真实执行根的 canonical UUID。返回校验后的 policy。
 */
export function assertThreadYoloPolicy(policy, label = 'yoloPolicy') {
  assert(
    policy != null && typeof policy === 'object',
    `${label} must be an object: ${JSON.stringify(policy)}`,
  )
  assert(
    policy.mode === 'ENABLE' || policy.mode === 'DISABLE' || policy.mode === 'FOLLOW',
    `${label}.mode must be ENABLE|DISABLE|FOLLOW: ${JSON.stringify(policy)}`,
  )
  if (policy.mode === 'FOLLOW') {
    canonicalUuid(policy.rootThreadId, `${label}.rootThreadId`)
  } else {
    assert(
      policy.rootThreadId === null,
      `${label}.rootThreadId must be null for ${policy.mode}: ${JSON.stringify(policy)}`,
    )
  }
  return policy
}

/**
 * 校验执行根 Thread 的 YOLO policy 精确投影：{@code mode} 只由根开关 {@code enabled}
 * 决定，且 {@code rootThreadId} 必须显式为 null。
 */
export function assertRootYoloPolicy(thread, enabled, label = 'thread') {
  const policy = assertThreadYoloPolicy(thread?.yoloPolicy, `${label}.yoloPolicy`)
  assert(
    policy.mode === (enabled ? 'ENABLE' : 'DISABLE'),
    `${label}.yoloPolicy.mode ${policy.mode} != ${enabled ? 'ENABLE' : 'DISABLE'}: ${JSON.stringify(thread)}`,
  )
  return policy
}

/** 严格校验 Thread 投影 DTO 的 canonical UUID 标识字段、执行控制与父关系并返回 threadId。 */
export function threadIdOf(thread) {
  const threadId = canonicalUuid(thread?.threadId, 'threadId')
  canonicalUuid(thread.sessionId, 'sessionId')
  canonicalUuid(thread.headEntryId, 'headEntryId')
  threadParentIdOf(thread)
  assertThreadYoloPolicy(thread.yoloPolicy)
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
  // 执行控制只有 RUNNABLE / STOPPED；status 只描述本 Thread 自身，本地投影不再有 WAITING_CHILDREN。
  assert(
    thread.executionControl === 'RUNNABLE' || thread.executionControl === 'STOPPED',
    `executionControl must be RUNNABLE|STOPPED: ${JSON.stringify(thread)}`,
  )
  assert(
    THREAD_RUNTIME_STATUSES.has(thread.status),
    `unknown ThreadRuntimeStatus: ${JSON.stringify(thread.status)}`,
  )
  assert(typeof thread.processing === 'boolean', `processing must be boolean: ${JSON.stringify(thread)}`)
  return threadId
}

/** Thread 自身本地运行时状态投影全集（无 WAITING_CHILDREN；STOPPED 表示持久执行控制已停止）。 */
const THREAD_RUNTIME_STATUSES = new Set([
  'IDLE',
  'QUEUED',
  'STOPPED',
  'CONTINUATION_DUE',
  'MODEL_READY',
  'MODEL_DISPATCHING',
  'MODEL_RUNNING',
  'APPLYING',
  'TOOL_WAITING_APPROVAL',
  'TOOL_WAITING_INPUT',
  'TOOL_RUNNING',
  'TOOL_DISPATCHING',
  'TOOL_READY',
])

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
  return { type: 'CHAT', chatId: canonicalUuid(chatId, 'chatId') }
}

/** NEW_SESSION target：预分配 sessionId/threadId，携带 root settings 与 initial yolo。 */
export function newSessionTarget({ sessionId, threadId, rootSettings, yoloEnabled = false }) {
  canonicalUuid(sessionId, 'target.sessionId')
  canonicalUuid(threadId, 'target.threadId')
  assert(rootSettings && typeof rootSettings === 'object', `rootSettings required: ${JSON.stringify(rootSettings)}`)
  assert(typeof yoloEnabled === 'boolean', 'target.yoloEnabled must be boolean')
  return { type: 'NEW_SESSION', sessionId, threadId, rootSettings, yoloEnabled }
}

/** NEW_THREAD target：在既有 Session 的合法 fork 边界（ROOT 或已闭合 TURN_END）下开新 Thread（不复制 Entry）。 */
export function newThreadTarget({ sessionId, startEntryId, threadId, threadName, yoloEnabled = false }) {
  canonicalUuid(sessionId, 'target.sessionId')
  canonicalUuid(startEntryId, 'target.startEntryId')
  canonicalUuid(threadId, 'target.threadId')
  assert(typeof yoloEnabled === 'boolean', 'target.yoloEnabled must be boolean')
  return {
    type: 'NEW_THREAD',
    sessionId,
    startEntryId,
    threadId,
    threadName: normalizeThreadName(threadName),
    yoloEnabled,
  }
}

/**
 * THREAD target：既有 Thread 续写的 helper 路由描述符（内部转为 owner-free body）。
 *
 * <p>它不是 HTTP wire：真实请求发往 {@code POST /api/harness/threads/{threadId}/command-batches}，body 只含
 * {@code {expectedHeadEntryId, expectedNextCommandSequence, commands}}，不带 owner/target/type。cursor 由服务端做精确 CAS。
 */
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
 *   target.threadName 经 Names 规则规范化后的名称（名称进入创建请求身份）；replayed=true 的 exact replay
 *   可能发生在该 Thread 已被控制面重命名之后，服务端返回当前权威 name，helper 不做默认名断言；
 * - rootEntry.sessionId/thread.sessionId 必须等于 response session.sessionId；
 * - acceptedCommands 的 count/type/idempotencyKey/order 必须与请求 commands 一致，
 *   且每项 threadId、positive sequence、canonical idempotencyKey 均校验。
 */
export async function acceptCommandBatch(ctx, { owner, target, commands }) {
  assert(target?.type, `target required: ${JSON.stringify(target)}`)
  assert(Array.isArray(commands) && commands.length > 0, 'commands required')
  for (const command of commands) {
    assert(
      command && typeof command === 'object' && command.type && command.idempotencyKey,
      `invalid command: ${JSON.stringify(command)}`,
    )
  }

  let accepted
  if (target.type === 'THREAD') {
    // 既有 Thread 续写面 owner-free：body 只带 cursor 与 commands，绝不发送 owner/target。
    const threadId = canonicalUuid(target.threadId, 'target.threadId')
    const body = {
      expectedHeadEntryId: canonicalUuid(target.expectedHeadEntryId, 'target.expectedHeadEntryId'),
      expectedNextCommandSequence: positiveDecimal(
        target.expectedNextCommandSequence,
        'target.expectedNextCommandSequence',
      ),
      commands,
    }
    const { status, json } = await ctx.call(
      'POST',
      `/api/harness/threads/${encodeURIComponent(threadId)}/command-batches`,
      body,
    )
    assert(status === 202, `accept thread command batch status ${status}: ${JSON.stringify(json)}`)
    accepted = envelopeData(json)
    assertAcceptedCommands(accepted)
    assert(
      String(accepted.thread?.threadId) === threadId,
      `accepted threadId ${accepted.thread?.threadId} != target ${threadId}: ${JSON.stringify(accepted)}`,
    )
    assertAcceptedCommandList(accepted, commands, threadId)
    return accepted
  }

  assert(
    owner?.type === 'CHAT',
    `owner.type must be CHAT: ${JSON.stringify(owner)}`,
  )
  canonicalUuid(owner.chatId, 'owner.chatId')
  assert(
    Object.keys(owner).sort().join(',') === 'chatId,type',
    `owner must contain only type and chatId: ${JSON.stringify(owner)}`,
  )
  const { status, json } = await ctx.call('POST', '/api/harness/command-batches', {
    owner,
    target,
    commands,
  })
  assert(status === 202, `accept command batch status ${status}: ${JSON.stringify(json)}`)
  accepted = envelopeData(json)
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
    // 真正首次创建：分支名由调用方显式给出并规范化，是创建请求身份的一部分。
    // （replayed=true 的 exact replay 可能命中已重命名的 Thread，返回当前权威 name，不在此断言。）
    assert(
      accepted.thread?.name === normalizeThreadName(target.threadName),
      `NEW_THREAD first-creation thread name ${accepted.thread?.name} != normalized target.threadName for ${target.threadId}: ${JSON.stringify(
        accepted.thread,
      )}`,
    )
  }
  assert(
    String(accepted.rootEntry?.sessionId) === String(accepted.session?.sessionId)
      && String(accepted.thread?.sessionId) === String(accepted.session?.sessionId),
    `rootEntry/thread session must equal accepted session: ${JSON.stringify(accepted)}`,
  )
  assertAcceptedCommandList(accepted, commands, target.threadId)
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

/** NEW_THREAD 原子创建：在既有 Session 的合法 fork 边界下开新 Thread（不复制 Entry）。 */
export async function createNewThread(
  ctx,
  { owner, sessionId, startEntryId, threadId, threadName, yoloEnabled = false, commands },
) {
  return acceptCommandBatch(ctx, {
    owner,
    target: newThreadTarget({ sessionId, startEntryId, threadId, threadName, yoloEnabled }),
    commands,
  })
}

/**
 * 预览响应 DTO 的精确 wire 字段集：两种 kind 共用同一形状，null 字段由全局 NON_NULL 省略。
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

/** 草稿预览的固定能力声明（镜像 {@code HarnessProviderRequestPreviewDTO.DRAFT_NOTICE}）。 */
export const DRAFT_PREVIEW_NOTICE =
  'Preview is a click-time snapshot of the request that would be sent now. It does not '
  + 'consume uploads or write anything, so a later send may observe different history, '
  + 'attachments or provider configuration.'

/** 历史预览的固定能力声明（镜像 {@code HarnessProviderRequestPreviewDTO.HISTORICAL_NOTICE}）。 */
export const HISTORICAL_PREVIEW_NOTICE =
  'Preview is reconstructed from the current catalog, provider configuration and model '
  + 'selection against the recorded history before this output. It is not the original '
  + 'request that was sent, and it does not consume uploads or write anything.'

/** 严格校验一份预览响应：精确字段、kind、固定 notice、来源 head 与请求体自洽。 */
export function assertProviderPreview(preview, expectedKind) {
  assert(preview && typeof preview === 'object' && !Array.isArray(preview), JSON.stringify(preview))
  const actual = Object.keys(preview).sort()
  assert(
    actual.length === PREVIEW_FIELDS.length
      && actual.every((field, index) => field === [...PREVIEW_FIELDS].sort()[index]),
    `preview fields must be ${[...PREVIEW_FIELDS].sort().join(',')}, got ${actual.join(',')}`,
  )
  assert(preview.kind === expectedKind, `preview kind ${preview.kind} != ${expectedKind}: ${JSON.stringify(preview)}`)
  assert(
    (typeof preview.generatedAt === 'number' && Number.isFinite(preview.generatedAt))
      || (typeof preview.generatedAt === 'string' && Number.isFinite(Date.parse(preview.generatedAt))),
    `preview generatedAt invalid: ${JSON.stringify(preview.generatedAt)}`,
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
  const expectedNotice =
    expectedKind === 'DRAFT_REQUEST_PREVIEW' ? DRAFT_PREVIEW_NOTICE : HISTORICAL_PREVIEW_NOTICE
  assert(
    preview.notice === expectedNotice,
    `preview notice must be the fixed ${expectedKind} capability statement: ${JSON.stringify(preview.notice)}`,
  )
  return preview
}

/**
 * 发送前请求预览：owner-free POST /api/harness/threads/{threadId}/provider-request-preview。
 *
 * <p>body 与既有 Thread 续写面同形（{@link HarnessThreadCommandBatchDTO} 的
 * {@code {expectedHeadEntryId, expectedNextCommandSequence, commands}}），不写 durable 状态、不消费 upload。
 * 返回 200 的 {@code HarnessProviderRequestPreviewDTO} 或抛 {@link HttpError}（409 表示该快照下不可精确预览）。
 */
export async function previewProviderRequest(
  ctx,
  threadId,
  { expectedHeadEntryId, expectedNextCommandSequence, commands },
) {
  const id = canonicalUuid(threadId, 'threadId')
  assert(Array.isArray(commands) && commands.length > 0, 'commands required')
  const { status, json } = await ctx.call(
    'POST',
    `/api/harness/threads/${encodeURIComponent(id)}/provider-request-preview`,
    {
      expectedHeadEntryId: canonicalUuid(expectedHeadEntryId, 'expectedHeadEntryId'),
      expectedNextCommandSequence: positiveDecimal(
        expectedNextCommandSequence,
        'expectedNextCommandSequence',
      ),
      commands,
    },
  )
  assert(status === 200, `provider request preview status ${status}: ${JSON.stringify(json)}`)
  return assertProviderPreview(envelopeData(json), 'DRAFT_REQUEST_PREVIEW')
}

/**
 * 本地分支草稿预览：POST /api/harness/sessions/{sessionId}/provider-request-preview，body 只有
 * {@code {startEntryId, commands}}。草稿尚未落库，因此不携带任何 cursor；同样不写 durable 状态、不消费 upload。
 */
export async function previewSessionDraftRequest(ctx, sessionId, { startEntryId, commands }) {
  const id = canonicalUuid(sessionId, 'sessionId')
  assert(Array.isArray(commands) && commands.length > 0, 'commands required')
  const { status, json } = await ctx.call(
    'POST',
    `/api/harness/sessions/${encodeURIComponent(id)}/provider-request-preview`,
    { startEntryId: canonicalUuid(startEntryId, 'startEntryId'), commands },
  )
  assert(status === 200, `session draft preview status ${status}: ${JSON.stringify(json)}`)
  return assertProviderPreview(envelopeData(json), 'DRAFT_REQUEST_PREVIEW')
}

/**
 * 历史请求预览：GET /api/harness/sessions/{sessionId}/entries/{entryId}/provider-request-preview。
 *
 * <p>按当前 catalog 与 Provider 配置重建该模型输出之前的请求前缀；响应 MUST 是 HISTORICAL_REQUEST_PREVIEW，
 * 不得冒充当时的原始发送字节。
 */
export async function previewHistoricalRequest(ctx, sessionId, entryId) {
  const id = canonicalUuid(sessionId, 'sessionId')
  const entry = canonicalUuid(entryId, 'entryId')
  const { status, json } = await ctx.call(
    'GET',
    `/api/harness/sessions/${encodeURIComponent(id)}/entries/${encodeURIComponent(entry)}/provider-request-preview`,
  )
  assert(status === 200, `historical preview status ${status}: ${JSON.stringify(json)}`)
  return assertProviderPreview(envelopeData(json), 'HISTORICAL_REQUEST_PREVIEW')
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
    assert(Object.hasOwn(item, 'parentThreadId'), 'Thread summary parentThreadId is required')
    if (item.parentThreadId !== null) {
      canonicalUuid(item.parentThreadId, 'thread.parentThreadId')
    }
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

/**
 * Session Entry 的读取时费用投影：`usageCost` 永远存在（NON_NULL 全局省略只对 null 生效，DTO 声明 ALWAYS），
 * 未计价/非模型输出时为 null，否则是 {@code {currency, amount}} 且 amount 是精确十进制文本（不做展示舍入）。
 * 费用只是查询投影，绝不进入 payloadJson —— 这里同时锁住这两条事实。
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
    typeof entry.payloadJson === 'string' && !entry.payloadJson.includes('usageCost'),
    `usageCost must not be persisted inside payloadJson: ${JSON.stringify(entry)}`,
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
  assert(typeof entry.payloadJson === 'string', JSON.stringify(entry))
  assertEntryUsageCost(entry)
  return entry
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
    assertSessionEntry(entry, { sessionId })
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

/** Thread 快照的 root-to-head entries；与 Session Entry Tree 共用同一只读形状（含 usageCost 投影）。 */
export async function snapshotEntries(ctx, threadId) {
  const entries = (await getThreadSnapshot(ctx, threadId)).entries || []
  for (const entry of entries) {
    assertSessionEntry(entry)
  }
  return entries
}

/**
 * 只读 Environment 注册表；Card UUID id 是 canonical 路由身份，name 是 display name，ready 是统一可用性标记。
 *
 * {@code statusExpiresAt} 是当前状态的只读时间投影（有效连接取 min(lease, lastSeen+heartbeat)）：
 * 无连接或失效时显式为 null，只提示浏览器按权威数据回读一次，不是固定轮询。
 */
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
    assert(Object.hasOwn(environment, 'statusExpiresAt'), 'environment must expose statusExpiresAt')
    const expiresAt = environment.statusExpiresAt
    if (expiresAt != null) {
      assert(
        (typeof expiresAt === 'number' && Number.isFinite(expiresAt))
          || (typeof expiresAt === 'string' && Number.isFinite(Date.parse(expiresAt))),
        `statusExpiresAt must be an instant: ${JSON.stringify(environment)}`,
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
 * 直接更新 Thread YOLO policy（PUT /yolo）：只提交目标策略，不携带 Thread version。
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

/** 校验一条 StoppedThreadReceiptDTO：身份、边界、计数与仅含人类输入的 cancelledInputs。 */
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
      input.type === 'USER_MESSAGE' || input.type === 'GOAL',
      `cancelled input must be human USER_MESSAGE|GOAL: ${JSON.stringify(input)}`,
    )
    assert(
      typeof input.payloadJson === 'string' && input.payloadJson.length > 0,
      `cancelled input must carry payloadJson: ${JSON.stringify(input)}`,
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
  assert(status === 200, `stop status ${status}: ${JSON.stringify(json)}`)
  const stop = envelopeData(json)
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
    // STOPPED 的 Thread 已无本线程工作：它不再是 cleanup 目标，也不因持久停止状态被误判为 active。
    const active =
      (snapshot.thread.status !== 'IDLE' && snapshot.thread.status !== 'STOPPED')
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
  const framed = new FramedEventSocket(eventUrl(ctx.baseUrl))
  const socket = framed.socket
  try {
    await waitForSocketOpen(socket, deadline, controller.signal)
    framed.subscribe({ kind: 'thread', id: expectedThreadId })
    await waitForApplicationFrame(
      framed,
      deadline,
      controller.signal,
      (frame) => {
        if (
          frame?.version === 2
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

    const deltaWait = waitForApplicationFrame(
      framed,
      deadline,
      controller.signal,
      (frame) => {
        if (
          frame?.version === 2
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
    framed.close()
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

function waitForApplicationFrame(framed, deadline, signal, matcher, description) {
  return new Promise((resolve, reject) => {
    let settled = false
    const cleanup = () => {
      framed.hooks.delete(onFrame)
      framed.socket.removeEventListener('error', onError)
      framed.socket.removeEventListener('close', onClose)
      signal.removeEventListener('abort', onAbort)
      clearTimeout(timer)
    }
    const finish = (callback, value) => {
      if (settled) return
      settled = true
      cleanup()
      callback(value)
    }
    const inspect = (frame) => {
      if (frame?.version === 2 && frame.type === 'error') {
        finish(reject, new Error(`application event error ${frame.code}: ${frame.message}`))
        return
      }
      const result = matcher(frame)
      if (result.matched) {
        finish(resolve, result.value)
      }
    }
    const onFrame = (frame) => inspect(frame)
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
    // 先注册同步 hook 与监听，再回放已到达帧（订阅 ack 可能先于本次等待到达）。
    framed.hooks.add(onFrame)
    framed.socket.addEventListener('error', onError)
    framed.socket.addEventListener('close', onClose)
    signal.addEventListener('abort', onAbort, { once: true })
    for (const frame of framed.frames) inspect(frame)
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
