/**
 * 应用事件 WebSocket 协议（/ws/events）。
 *
 * client -> server（JSON 文本）：
 * - Thread subscribe 可附带 afterEntryId（canonical UUID；无 head 时省略）。
 * - {"type":"subscribe","resource":{"kind":"interactions"|"environments"}}
 * - {"type":"unsubscribe","resource":{...}}
 * - {"type":"refresh","resource":{"kind":"thread"|"thread-tree","id":"<UUID>"}}（已订阅资源的强制快照）
 * - {"type":"ide-use","browserSessionId":"<UUID>","revision":"<positive decimal>",
 *    "views":[{"environmentId":"<UUID>","viewId":"<UUID>","claimId":"<UUID>","bindingEpoch":"<positive decimal>"}]}
 *
 * server -> client（JSON 文本）：
 * - {"type":"connected","eventNodeId":"<UUID>","connectionId":"<UUID>","heartbeatIntervalMillis":"<positive decimal>"}
 *   连接建立后的第一帧；每次重连都是新的 connectionId。heartbeatIntervalMillis 是服务端
 *   实际使用的文本 heartbeat 间隔，客户端以它推导失联预算，不写死默认值。
 * - {"type":"subscribed","resource":{...},"cursor":"<canonical>"}
 *   订阅已在 wire 上建立（首次与重连后都会发送）；Thread 与提示型资源 cursor 恒为 0。
 * - {"type":"event","resource":{...},"name":"snapshot"|"realtime"|"changed"|"revision","data":{...},"cursor":"<canonical>"}
 *   - thread snapshot：data 为 HarnessThreadSnapshotDTO，不带 cursor；订阅 ACK cursor 恒为 0。
 *   - thread realtime：data 为 lossy realtime delta envelope 的 JSON 对象（如 MODEL_DELTA），
 *     绝不携带 cursor。
 *   - canvas revision：data {"revision":"N"}，cursor 必带且与 data.revision 完全相等。
 *   - projects changed：data {"projectId":"<UUID>"}，只提示回读 Project Snapshot，不带 cursor。
 *   - thread-tree snapshot：resource 带真实执行根 id，data 为完整小树 {nodes:[HarnessThreadTreeNodeDTO]}，不带 Thread.version cursor。
 *   - thread-tree changed：仅旧版内部提示，不用于浏览器数据流。
 *   - interactions changed：data {"rootThreadId":"<UUID>"}，只提示回读待处理交互与全局角标，不带 cursor。
 *   - environments changed：data 恒为 {}，只提示回读环境列表，不带 cursor。
 *   - chat-space tree {treeVersion} / threads {} / space {version}：仅提示回读，不带 cursor。
 * - {"type":"snapshot-ref","resource":{...}[,"version":"<canonical>"]}：该资源快照本身超出单帧预算，
 *   服务端不发送它，客户端必须回读 HTTP 权威事实。Thread 必带顶层真实权威 version（canonical
 *   非负十进制，取自 DTO 而不是合成游标），thread-tree 不带 version。它只是该资源的恢复提示，
 *   不改变物理连接与其他资源的订阅。
 * - {"type":"resync","resource":{...}}：需要整体替换为全量快照。
 * - {"type":"heartbeat"}：连接级文本保活；客户端严格解码后静默消费。
 * - {"type":"ide-use-accepted","revision":"<positive decimal>"}：ide-use 列表已确认。
 * - {"type":"error","resource"?:{...},"code":"<string>","message":"<string>"}
 *
 * 解码是真正严格的：每种 type/name 只接受精确字段集、多余/未知字段一律拒绝；
 * resource 精确只有 kind+id 且 id 必须是 canonical UUID（小写十六进制）；
 * 全局 projects/interactions/environments resource 不带 id；
 * resource/name 组合必须合法（thread 仅 snapshot|realtime，canvas 仅 revision，
 * interactions|environments 仅 changed，thread-tree 仅 snapshot|changed）；canvas revision
 * 与其 durable cursor 必须是 canonical 非负十进制字符串且完全相等；Thread 与提示型资源的
 * ack cursor 恒为 '0'，提示型事件及 snapshot/realtime 事件不携带 cursor；realtime data 必须是非数组 JSON
 * 对象且不得携带 cursor。connected 的两个身份与 heartbeatIntervalMillis（不超过
 * Number.MAX_SAFE_INTEGER 的正毫秒数）、revision 与 bindingEpoch 都是
 * canonical UUID / 正十进制字符串（不超过 9223372036854775807）。畸形消息永远不会
 * 到达 listeners。
 *
 * 只有「连资源都无法定位」的帧才是连接级协议违规：畸形 JSON、未知 type、错误外层字段集、
 * 非法 resource、非法 resource/name 组合，或 cursor 的出现与否与组合不匹配（canvas revision 必带，
 * 其余必不带）。envelope 合法但载荷值不合规（如 Thread snapshot 的 version 非字符串、canvas
 * revision cursor 与 data.revision 不等）时，解码器直接返回携带该资源的稳定
 * {@link INVALID_EVENT_PAYLOAD} 错误；坏载荷与原始值永不进入 listener，物理连接与其他资源
 * 继续工作。
 */

import {
  ENTRY_TYPE_VALUES,
  EXECUTION_EXIT_KIND_VALUES,
  GENERATION_STATUS_VALUES,
  HARNESS_AGENT_MESSAGE_CONTENT_TYPE_VALUES,
  THREAD_COMMAND_STATE_VALUES,
  THREAD_COMMAND_TYPE_VALUES,
  THREAD_EXECUTION_CONTROL_VALUES,
  THREAD_KIND_VALUES,
  THREAD_RUNTIME_STATUS_VALUES,
  TOOL_INVOCATION_STATUS_VALUES,
  TURN_END_OUTCOME_VALUES,
} from '@/shared/api/generated/contracts'
import type {
  HarnessThreadSnapshotDTO,
  HarnessThreadTreeNodeDTO,
} from '@/shared/api/generated/contracts'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import { isRecord } from '@/shared/lib/value'

type ApplicationEventResourceKind =
  | 'thread'
  | 'canvas'
  | 'thread-tree'
  | 'interactions'
  | 'environments'
  | 'chat-space'

export type ApplicationEventResource =
  | { kind: 'thread'; id: string }
  | { kind: 'canvas'; id: string }
  | { kind: 'thread-tree'; id: string }
  | { kind: 'interactions' }
  | { kind: 'environments' }
  | { kind: 'chat-space'; id: string }

export type ApplicationEventName = 'realtime' | 'changed' | 'revision' | 'snapshot' | 'tree' | 'threads' | 'space'

/**
 * 资源快照超出单帧预算的提示；{@link ApplicationEventServerMessage} 中的 snapshot-ref 帧。
 *
 * <p>它是该资源的逻辑投递（订阅仍然有效），但内容不在 wire 上：消费方必须回读权威 HTTP 事实。
 * Thread 携带顶层 version 用于跨 resubscribe 去重同一版溢出快照，执行树不带；两个分支用
 * {@code 'version' in message} 判别。
 */
export type SnapshotRefMessage =
  | { type: 'snapshot-ref'; resource: { kind: 'thread'; id: string }; version: string }
  | { type: 'snapshot-ref'; resource: { kind: 'thread-tree'; id: string } }

/** event 帧的可路由 envelope：只保留已校验的 resource/name。 */
interface EventEnvelope {
  resource: ApplicationEventResource
  name: ApplicationEventName
}

/**
 * 能定位资源的坏载荷的稳定错误码：服务端帧已通过严格 envelope，但 data/cursor 值不符合该资源契约。
 *
 * <p>它按资源上报（经既有 onError），只让该资源失效，不终止物理连接、不影响其他资源。
 * 客户端不得据此合成任何业务数据，只能声明该资源当前不可信并走既有恢复入口。
 */
export const INVALID_EVENT_PAYLOAD = 'INVALID_EVENT_PAYLOAD'

/** canonical 非负十进制字符串：'0' 或非零开头，无前导零、无符号、无空白。 */
type ApplicationEventCursor = string

/** ide-use revision：canonical 正十进制字符串，不超过 9223372036854775807。 */
export type ApplicationEventRevision = string

/** 当前物理事件连接的关联身份；不是认证凭据，每次重连都会变化。 */
export interface ApplicationEventConnectionIdentity {
  eventNodeId: string
  connectionId: string
  /** 服务端实际使用的文本 heartbeat 间隔（canonical 正十进制毫秒）；失联预算按它推导。 */
  heartbeatIntervalMillis: string
}

/** 一项 IDE 使用意图；没有 bindingEpoch 的项不能用于续租。 */
export interface ApplicationEventIdeUseView {
  environmentId: string
  viewId: string
  claimId: string
  bindingEpoch?: string
}

/** 一个浏览器会话的完整 IDE 持有列表；revision 在同一连接内单调递增。 */
export interface ApplicationEventIdeUse {
  browserSessionId: string
  revision: ApplicationEventRevision
  views: ApplicationEventIdeUseView[]
}

export type ApplicationEventClientMessage =
  | { type: 'subscribe'; resource: ApplicationEventResource; afterEntryId?: string }
  | { type: 'unsubscribe'; resource: ApplicationEventResource }
  | { type: 'refresh'; resource: ApplicationEventResource }
  | {
      type: 'ide-use'
      browserSessionId: string
      revision: ApplicationEventRevision
      views: ApplicationEventIdeUseView[]
    }

/** thread 的 realtime 事件：data 为 delta envelope JSON 对象，绝不携带 cursor。 */
type ApplicationEventThreadSnapshotEvent = {
  type: 'event'
  resource: ApplicationEventResource & { kind: 'thread' }
  name: 'snapshot'
  data: HarnessThreadSnapshotDTO
}

type ApplicationEventThreadRealtimeEvent = {
  type: 'event'
  resource: ApplicationEventResource & { kind: 'thread' }
  name: 'realtime'
  data: Record<string, unknown>
}

/** canvas 的持久 revision 事件：cursor 必带且与 data.revision 完全相等。 */
type ApplicationEventCanvasRevisionEvent = {
  type: 'event'
  resource: ApplicationEventResource & { kind: 'canvas' }
  name: 'revision'
  data: { revision: ApplicationEventCursor }
  cursor: ApplicationEventCursor
}

/** thread-tree 的提示型 changed 事件：根 id 已在 resource 上，data 恒为空对象且绝不携带 cursor。 */
type ApplicationEventTreeSnapshotEvent = {
  type: 'event'
  resource: ApplicationEventResource & { kind: 'thread-tree' }
  name: 'snapshot'
  data: { nodes: HarnessThreadTreeNodeDTO[] }
}

type ApplicationEventTreeChangedEvent = {
  type: 'event'
  resource: ApplicationEventResource & { kind: 'thread-tree' }
  name: 'changed'
  data: Record<string, never>
}

/**
 * 读取 interactions `changed` 提示事件携带的真实执行根。
 *
 * <p>data 已由 {@link decodeServerMessage} 严格解码，这里只做类型收窄；形状不符（或非该事件）返回 null，
 * 调用方据此退化为整体回读，绝不猜测根。
 */
export function readInteractionsChangedRoot(data: unknown): string | null {
  if (!isRecord(data)) {
    return null
  }
  const rootThreadId = data.rootThreadId
  return isCanonicalUuid(rootThreadId) ? rootThreadId : null
}

/** interactions 的提示型 changed 事件：data 携带本次变化的真实执行根。 */
type ApplicationEventInteractionsChangedEvent = {
  type: 'event'
  resource: ApplicationEventResource & { kind: 'interactions' }
  name: 'changed'
  data: { rootThreadId: string }
}

/** environments 的提示型 changed 事件：data 恒为空对象，权威环境列表由回读决定。 */
type ApplicationEventEnvironmentsChangedEvent = {
  type: 'event'
  resource: ApplicationEventResource & { kind: 'environments' }
  name: 'changed'
  data: Record<string, never>
}

type ApplicationEventChatSpaceEvent = {
  type: 'event'
  resource: Extract<ApplicationEventResource, { kind: 'chat-space' }>
} & ({ name: 'tree'; data: { treeVersion: string } }
  | { name: 'threads'; data: Record<string, never> }
  | { name: 'space'; data: { version: string } })

export type ApplicationEventServerMessage =
  | { type: 'connected'; eventNodeId: string; connectionId: string; heartbeatIntervalMillis: string }
  | { type: 'heartbeat' }
  | { type: 'subscribed'; resource: ApplicationEventResource; cursor: ApplicationEventCursor }
  | ApplicationEventThreadSnapshotEvent
  | ApplicationEventThreadRealtimeEvent
  | ApplicationEventCanvasRevisionEvent
  | ApplicationEventTreeSnapshotEvent
  | ApplicationEventTreeChangedEvent
  | ApplicationEventInteractionsChangedEvent
  | ApplicationEventEnvironmentsChangedEvent
  | ApplicationEventChatSpaceEvent
  | { type: 'resync'; resource: ApplicationEventResource }
  | SnapshotRefMessage
  | { type: 'ide-use-accepted'; revision: ApplicationEventRevision }
  | { type: 'error'; resource?: ApplicationEventResource; code: string; message: string }

const CANONICAL_DECIMAL = /^(0|[1-9][0-9]*)$/
const CANONICAL_POSITIVE_DECIMAL = /^[1-9][0-9]*$/
const MAX_SIGNED_LONG_DECIMAL = '9223372036854775807'
const MAX_SAFE_INTEGER_DECIMAL = '9007199254740991'
const EVENT_FIELDS = ['type', 'resource', 'name', 'data', 'cursor'] as const
const SNAPSHOT_REF_THREAD_FIELDS = ['type', 'resource', 'version'] as const
const SNAPSHOT_REF_TREE_FIELDS = ['type', 'resource'] as const

export function encodeClientMessage(message: ApplicationEventClientMessage): string {
  return JSON.stringify(message)
}

/**
 * 服务端帧严格解码。
 *
 * <p>返回值只有两类：合法消息，或可定位资源的坏载荷经衔接后合成的稳定资源级
 * {@link INVALID_EVENT_PAYLOAD} 错误（携带该帧的 resource/name，不携带、不复述载荷内容）。
 * 返回 null 仅表示连资源都定位不到的帧：畸形 JSON、未知 type、错误外层字段集、非法
 * resource、非法 resource/name 组合——包括在握手完成前到达的这些帧，调用方仍按连接级
 * 协议违规终止。
 */
export function decodeServerMessage(raw: string): ApplicationEventServerMessage | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }
  if (!isRecord(parsed) || typeof parsed.type !== 'string') {
    return null
  }
  return decodeMessage(parsed)
}

/** 按 type 分派；未声明的 type 一律拒绝。各解码器只接受精确字段集。 */
function decodeMessage(parsed: Record<string, unknown>): ApplicationEventServerMessage | null {
  switch (parsed.type) {
    case 'connected':
      return decodeConnected(parsed)
    case 'heartbeat':
      return decodeHeartbeat(parsed)
    case 'ide-use-accepted':
      return decodeIdeUseAccepted(parsed)
    case 'subscribed':
      return decodeSubscribed(parsed)
    case 'event':
      return decodeEvent(parsed)
    case 'resync':
      return decodeResync(parsed)
    case 'snapshot-ref':
      return decodeSnapshotRef(parsed)
    case 'error':
      return decodeError(parsed)
    default:
      return null
  }
}

function decodeConnected(parsed: Record<string, unknown>): ApplicationEventServerMessage | null {
  if (!hasOnlyFields(parsed, ['type', 'eventNodeId', 'connectionId', 'heartbeatIntervalMillis'])) {
    return null
  }
  const eventNodeId = parseUuid(parsed.eventNodeId)
  const connectionId = parseUuid(parsed.connectionId)
  // heartbeat 间隔必须是正整数数量级内的 canonical 正十进制字符串。
  const heartbeatIntervalMillis = parseHeartbeatIntervalMillis(parsed.heartbeatIntervalMillis)
  if (eventNodeId == null || connectionId == null || heartbeatIntervalMillis == null) {
    return null
  }
  return { type: 'connected', eventNodeId, connectionId, heartbeatIntervalMillis }
}

function decodeHeartbeat(parsed: Record<string, unknown>): ApplicationEventServerMessage | null {
  if (!hasOnlyFields(parsed, ['type'])) {
    return null
  }
  return { type: 'heartbeat' }
}

function decodeIdeUseAccepted(
  parsed: Record<string, unknown>,
): ApplicationEventServerMessage | null {
  if (!hasOnlyFields(parsed, ['type', 'revision'])) {
    return null
  }
  const revision = parseRevision(parsed.revision)
  if (revision == null) {
    return null
  }
  return { type: 'ide-use-accepted', revision }
}

function decodeSubscribed(parsed: Record<string, unknown>): ApplicationEventServerMessage | null {
  if (!hasOnlyFields(parsed, ['type', 'resource', 'cursor'])) {
    return null
  }
  const resource = parseResource(parsed.resource)
  if (resource == null) {
    return null
  }
  const cursor = parseCursor(parsed.cursor)
  if (cursor == null) {
    return null
  }
  // Thread 与提示型资源的 ack cursor 恒为 '0'。
  if ((resource.kind === 'thread' || isHintResourceKind(resource.kind)) && cursor !== '0') {
    return null
  }
  return { type: 'subscribed', resource, cursor }
}

function decodeResync(parsed: Record<string, unknown>): ApplicationEventServerMessage | null {
  if (!hasOnlyFields(parsed, ['type', 'resource'])) {
    return null
  }
  const resource = parseResource(parsed.resource)
  if (resource == null) {
    return null
  }
  return { type: 'resync', resource }
}

/** snapshot-ref：只有 thread 与 thread-tree，Thread 的 version 是顶层必带字段。 */
function decodeSnapshotRef(parsed: Record<string, unknown>): ApplicationEventServerMessage | null {
  const resource = parseResource(parsed.resource)
  if (resource == null || (resource.kind !== 'thread' && resource.kind !== 'thread-tree')) {
    return null
  }
  if (resource.kind === 'thread') {
    if (!hasExactFields(parsed, SNAPSHOT_REF_THREAD_FIELDS)) {
      return null
    }
    const version = parseCursor(parsed.version)
    if (version == null) {
      return null
    }
    return { type: 'snapshot-ref', resource, version }
  }
  if (!hasExactFields(parsed, SNAPSHOT_REF_TREE_FIELDS)) {
    return null
  }
  return { type: 'snapshot-ref', resource }
}

function decodeError(parsed: Record<string, unknown>): ApplicationEventServerMessage | null {
  if (!hasOnlyFields(parsed, ['type', 'resource', 'code', 'message'])) {
    return null
  }
  if (typeof parsed.code !== 'string' || parsed.code.length === 0) {
    return null
  }
  if (typeof parsed.message !== 'string') {
    return null
  }
  if (parsed.resource === undefined) {
    return { type: 'error', code: parsed.code, message: parsed.message }
  }
  const resource = parseResource(parsed.resource)
  if (resource == null) {
    return null
  }
  return { type: 'error', resource, code: parsed.code, message: parsed.message }
}

/**
 * event 先校验公共 envelope，再按 resource/name 分派；envelope 合法但 data/cursor 值不符时，
 * 就在解码器内直接衔接为资源局部错误，不让坏载荷进入 listener，也不会二次解析原始帧。
 */
function decodeEvent(parsed: Record<string, unknown>): ApplicationEventServerMessage | null {
  const envelope = readEventEnvelope(parsed)
  if (envelope == null) {
    return null
  }
  const { resource, name } = envelope
  const message = decodeEventData(parsed, resource, name)
  return message ?? invalidEventPayload(resource, name)
}

/** 资源级坏载荷的稳定报错：只引用已校验的 name/kind，不回显任何载荷值。 */
function invalidEventPayload(
  resource: ApplicationEventResource,
  name: ApplicationEventName,
): ApplicationEventServerMessage {
  return {
    type: 'error',
    resource,
    code: INVALID_EVENT_PAYLOAD,
    message: `received an invalid ${resource.kind} ${name} payload for this resource`,
  }
}

function decodeEventData(
  parsed: Record<string, unknown>,
  resource: ApplicationEventResource,
  name: ApplicationEventName,
): ApplicationEventServerMessage | null {
  switch (resource.kind) {
    case 'thread':
      return decodeThreadEvent(parsed, resource, name)
    case 'canvas':
      return decodeCanvasRevisionEvent(parsed, resource)
    case 'thread-tree':
      if (name === 'snapshot') {
        return decodeTreeSnapshotEvent(parsed, resource)
      }
      return decodeTreeChangedEvent(parsed, resource)
    case 'interactions':
      return decodeInteractionsChangedEvent(parsed, resource)
    case 'environments':
      return decodeEnvironmentsChangedEvent(parsed, resource)
    case 'chat-space': {
      const data = parsed.data
      if (!isRecord(data)) return null
      if (name === 'threads' && hasExactFields(data, [])) return { type: 'event', resource, name, data: {} }
      if (name === 'tree' && hasExactFields(data, ['treeVersion'])) {
        const treeVersion = parseCursor(data.treeVersion)
        if (treeVersion !== null) return { type: 'event', resource, name, data: { treeVersion } }
      }
      if (name === 'space' && hasExactFields(data, ['version'])) {
        const version = parseCursor(data.version)
        if (version !== null) return { type: 'event', resource, name, data: { version } }
      }
      return null
    }
  }
}

/**
 * 可路由的 event envelope：外层字段集精确且包含 data、resource 合法、resource/name 组合
 * 合法，且 cursor 的**出现与否**符合该组合（canvas revision 必带，其余必不带）。
 *
 * <p>这些条件不成立就定位不到合法负载，仍是连接级协议违规；cursor 的值形式与 data 的形状
 * 属于载荷，由各资源解码器继续校，失败只让该资源失效。
 */
function readEventEnvelope(parsed: Record<string, unknown>): EventEnvelope | null {
  if (!hasOnlyFields(parsed, EVENT_FIELDS) || parsed.data === undefined) {
    return null
  }
  const resource = parseResource(parsed.resource)
  if (resource == null || !isEventName(parsed.name) || !isEventNameFor(resource, parsed.name)) {
    return null
  }
  const hasCursor = parsed.cursor !== undefined
  if (hasCursor !== eventCarriesCursor(resource.kind)) {
    return null
  }
  return { resource, name: parsed.name }
}

/** 只有 canvas 的 revision 事件是带持久 cursor 的前进事件；其余组合一律不带 cursor。 */
function eventCarriesCursor(kind: ApplicationEventResource['kind']): boolean {
  return kind === 'canvas'
}

/** thread 仅 snapshot|realtime，canvas 仅 revision，其余提示型资源仅 changed。 */
function isEventNameFor(
  resource: ApplicationEventResource,
  name: ApplicationEventName,
): boolean {
  switch (resource.kind) {
    case 'thread':
      return name === 'snapshot' || name === 'realtime'
    case 'canvas':
      return name === 'revision'
    case 'thread-tree':
      return name === 'snapshot' || name === 'changed'
    case 'chat-space':
      return name === 'tree' || name === 'threads' || name === 'space'
    default:
      return name === 'changed'
  }
}

export function isThreadSnapshot(value: unknown, threadId: string): value is HarnessThreadSnapshotDTO {
  if (!hasExactFields(value, [
    'version', 'thread', 'baseEntryId', 'entries', 'queuedCommands',
    'activeAssistantOutput', 'generationFailures', 'toolInvocations', 'stopReceipts',
    'unconfirmedExecutions',
  ]) || !isRecord(value)) {
    return false
  }
  const snapshot = value as unknown as HarnessThreadSnapshotDTO
  return typeof snapshot.version === 'string' && CANONICAL_DECIMAL.test(snapshot.version)
    && isThreadDTO(snapshot.thread, threadId, snapshot.version)
    && (snapshot.baseEntryId === null || isCanonicalUuid(snapshot.baseEntryId))
    && Array.isArray(snapshot.entries)
    && snapshot.entries.every(isThreadSnapshotEntry)
    && Array.isArray(snapshot.queuedCommands)
    && snapshot.queuedCommands.every(isThreadCommand)
    && Array.isArray(snapshot.generationFailures)
    && snapshot.generationFailures.every(isGenerationFailure)
    && Array.isArray(snapshot.toolInvocations)
    && snapshot.toolInvocations.every(isToolInvocation)
    && Array.isArray(snapshot.stopReceipts)
    && snapshot.stopReceipts.every(isStopReceipt)
    && Array.isArray(snapshot.unconfirmedExecutions)
    && snapshot.unconfirmedExecutions.every(isExecutionExit)
    && (snapshot.activeAssistantOutput === null || isActiveAssistantOutput(snapshot.activeAssistantOutput))
}

function isThreadDTO(value: unknown, threadId: string, version: string): boolean {
  const fields = [
    'threadId', 'parentThreadId', 'name', 'sessionId', 'headEntryId', 'rootThreadId',
    'yoloEnabled', 'nextCommandSequence', 'version', 'status', 'executionControl',
    'branchSettings', 'createdAt', 'updatedAt',
  ]
  if (!hasExactFields(value, fields) || !isRecord(value)) {
    return false
  }
  const settings = value.branchSettings
  return value.threadId === threadId
    && value.version === version
    && isCanonicalUuid(value.threadId)
    && (value.parentThreadId === null || isCanonicalUuid(value.parentThreadId))
    && isCanonicalUuid(value.sessionId)
    && isCanonicalUuid(value.headEntryId)
    && isCanonicalUuid(value.rootThreadId)
    && typeof value.name === 'string'
    && typeof value.yoloEnabled === 'boolean'
    && typeof value.nextCommandSequence === 'string'
    && CANONICAL_DECIMAL.test(value.nextCommandSequence)
    && THREAD_RUNTIME_STATUS_VALUES.includes(value.status as (typeof THREAD_RUNTIME_STATUS_VALUES)[number])
    && THREAD_EXECUTION_CONTROL_VALUES.includes(value.executionControl as (typeof THREAD_EXECUTION_CONTROL_VALUES)[number])
    && isRecord(settings)
    && hasExactFields(settings, ['agentName', 'execution', 'environmentName'])
    && typeof settings.agentName === 'string'
    && (settings.environmentName === null || typeof settings.environmentName === 'string')
    && isRecord(settings.execution)
    && hasExactFields(settings.execution, ['driver', 'settings'])
    && typeof settings.execution.driver === 'string'
    && finiteNumber(value.createdAt)
    && finiteNumber(value.updatedAt)
}

function isThreadSnapshotEntry(value: unknown): boolean {
  return hasExactFields(value, [
    'entryId', 'sessionId', 'parentEntryId', 'entryType', 'payload', 'createdAt', 'usageCost',
  ])
    && isRecord(value)
    && isCanonicalUuid(value.entryId)
    && isCanonicalUuid(value.sessionId)
    && (value.parentEntryId === null || isCanonicalUuid(value.parentEntryId))
    && ENTRY_TYPE_VALUES.includes(value.entryType as (typeof ENTRY_TYPE_VALUES)[number])
    && finiteNumber(value.createdAt)
    && (value.usageCost === null
      || (hasExactFields(value.usageCost, ['currency', 'amount'])
        && isRecord(value.usageCost)
        && typeof value.usageCost.currency === 'string'
        && typeof value.usageCost.amount === 'string'))
}

function isThreadCommand(value: unknown): boolean {
  return hasExactFields(value, [
    'threadId', 'sequence', 'type', 'state', 'idempotencyKey', 'payload', 'cancelledAt', 'createdAt',
  ]) && isRecord(value)
    && isCanonicalUuid(value.threadId)
    && typeof value.sequence === 'string' && CANONICAL_DECIMAL.test(value.sequence)
    && THREAD_COMMAND_TYPE_VALUES.includes(value.type as (typeof THREAD_COMMAND_TYPE_VALUES)[number])
    && THREAD_COMMAND_STATE_VALUES.includes(value.state as (typeof THREAD_COMMAND_STATE_VALUES)[number])
    && typeof value.idempotencyKey === 'string'
    && (value.cancelledAt === null || finiteNumber(value.cancelledAt))
    && finiteNumber(value.createdAt)
}

function isGenerationFailure(value: unknown): boolean {
  return hasExactFields(value, [
    'generationId', 'turnStartEntryId', 'requestHeadEntryId', 'attempt', 'sequence', 'text',
    'thinking', 'errorCode', 'errorMessage', 'failedAt', 'retryAt',
  ]) && isRecord(value)
    && isCanonicalUuid(value.generationId)
    && isCanonicalUuid(value.turnStartEntryId)
    && isCanonicalUuid(value.requestHeadEntryId)
    && Number.isSafeInteger(value.attempt) && Number(value.attempt) > 0
    && typeof value.sequence === 'string' && CANONICAL_DECIMAL.test(value.sequence)
    && ['text', 'thinking', 'errorCode', 'errorMessage'].every((key) => typeof value[key] === 'string')
    && finiteNumber(value.failedAt) && finiteNumber(value.retryAt)
}

function isToolInvocation(value: unknown): boolean {
  const fields = [
    'id', 'threadId', 'childThreadId', 'turnStartEntryId', 'assistantEntryId', 'callIndex', 'status',
    'attempt', 'toolCallId', 'queuedBehindToolCallId', 'toolName', 'rendererKey', 'environmentId',
    'requiredEnvironmentId', 'requiredEnvironmentName', 'environmentWaitFreshnessAt',
    'waitingForEnvironment', 'argumentsJson', 'approval', 'result', 'error', 'createdAt', 'updatedAt',
  ]
  return hasExactFields(value, fields) && isRecord(value)
    && isCanonicalUuid(value.id) && isCanonicalUuid(value.threadId)
    && nullableUuid(value.childThreadId) && isCanonicalUuid(value.turnStartEntryId)
    && isCanonicalUuid(value.assistantEntryId) && Number.isSafeInteger(value.callIndex)
    && TOOL_INVOCATION_STATUS_VALUES.includes(value.status as (typeof TOOL_INVOCATION_STATUS_VALUES)[number])
    && Number.isSafeInteger(value.attempt) && Number(value.attempt) >= 0
    && typeof value.toolCallId === 'string' && nullableString(value.queuedBehindToolCallId)
    && typeof value.toolName === 'string' && typeof value.rendererKey === 'string'
    && nullableUuid(value.environmentId) && nullableUuid(value.requiredEnvironmentId)
    && nullableString(value.requiredEnvironmentName)
    && (value.environmentWaitFreshnessAt === null || finiteNumber(value.environmentWaitFreshnessAt))
    && typeof value.waitingForEnvironment === 'boolean' && typeof value.argumentsJson === 'string'
    && finiteNumber(value.createdAt) && finiteNumber(value.updatedAt)
}

function isStopReceipt(value: unknown): boolean {
  return hasExactFields(value, [
    'threadId', 'stopRequestId', 'stoppedTurnEndEntryId', 'cancelledCommandCount', 'cancelledInputs',
  ]) && isRecord(value)
    && isCanonicalUuid(value.threadId) && isCanonicalUuid(value.stopRequestId)
    && nullableUuid(value.stoppedTurnEndEntryId)
    && Number.isSafeInteger(value.cancelledCommandCount) && Array.isArray(value.cancelledInputs)
    && value.cancelledInputs.every(isCancelledInput)
}

function isCancelledInput(value: unknown): boolean {
  return hasExactFields(value, ['sequence', 'idempotencyKey', 'type', 'payload'])
    && isRecord(value)
    && typeof value.sequence === 'string' && CANONICAL_DECIMAL.test(value.sequence)
    && typeof value.idempotencyKey === 'string'
    && THREAD_COMMAND_TYPE_VALUES.includes(value.type as (typeof THREAD_COMMAND_TYPE_VALUES)[number])
}

function isExecutionExit(value: unknown): boolean {
  return hasExactFields(value, ['executionId', 'invocationId', 'attempt', 'kind', 'registeredAt'])
    && isRecord(value) && isCanonicalUuid(value.executionId) && isCanonicalUuid(value.invocationId)
    && Number.isSafeInteger(value.attempt) && Number(value.attempt) > 0
    && EXECUTION_EXIT_KIND_VALUES.includes(value.kind as (typeof EXECUTION_EXIT_KIND_VALUES)[number])
    && finiteNumber(value.registeredAt)
}

function isActiveAssistantOutput(value: unknown): boolean {
  const fields = [
    'generationId', 'turnStartEntryId', 'requestHeadEntryId', 'status', 'attempt', 'sequence',
    'contents', 'error', 'resultEntryId', 'createdAt', 'updatedAt',
  ]
  return hasExactFields(value, fields) && isRecord(value)
    && isCanonicalUuid(value.generationId) && isCanonicalUuid(value.turnStartEntryId)
    && isCanonicalUuid(value.requestHeadEntryId)
    && GENERATION_STATUS_VALUES.includes(value.status as (typeof GENERATION_STATUS_VALUES)[number])
    && Number.isSafeInteger(value.attempt) && Number(value.attempt) >= 0
    && typeof value.sequence === 'string' && CANONICAL_DECIMAL.test(value.sequence)
    && Array.isArray(value.contents) && value.contents.every(isActiveContent)
    && (value.error === null || (hasExactFields(value.error, ['code', 'message'])
      && isRecord(value.error) && typeof value.error.code === 'string' && typeof value.error.message === 'string'))
    && (value.resultEntryId === null || isCanonicalUuid(value.resultEntryId))
    && finiteNumber(value.createdAt) && finiteNumber(value.updatedAt)
}

function isActiveContent(value: unknown): boolean {
  if (!isRecord(value) || !hasOnlyFields(value, ['type', 'text']) || !('type' in value)) {
    return false
  }
  return HARNESS_AGENT_MESSAGE_CONTENT_TYPE_VALUES.includes(
    value.type as (typeof HARNESS_AGENT_MESSAGE_CONTENT_TYPE_VALUES)[number],
  ) && (value.text === undefined || value.text === null || typeof value.text === 'string')
}

function finiteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function nullableString(value: unknown): boolean {
  return value === null || typeof value === 'string'
}

function nullableUuid(value: unknown): boolean {
  return value === null || isCanonicalUuid(value)
}

function decodeThreadEvent(
  parsed: Record<string, unknown>,
  resource: ApplicationEventResource & { kind: 'thread' },
  name: ApplicationEventName,
): ApplicationEventServerMessage | null {
  if (name === 'snapshot') {
    if (!isThreadSnapshot(parsed.data, resource.id)) {
      return null
    }
    return { type: 'event', resource, name: 'snapshot', data: parsed.data }
  }
  if (name === 'realtime') {
    // realtime 的 data 必须是非数组 JSON 对象（envelope 已保证不带 cursor）。
    if (!isRecord(parsed.data)) {
      return null
    }
    return { type: 'event', resource, name: 'realtime', data: parsed.data }
  }
  return null
}

function decodeCanvasRevisionEvent(
  parsed: Record<string, unknown>,
  resource: ApplicationEventResource & { kind: 'canvas' },
): ApplicationEventServerMessage | null {
  const revision = parseSingleCursorField(parsed.data, 'revision')
  // durable 事件必带 canonical cursor（envelope 已保证出现），且与 data.revision 完全相等。
  if (revision == null || parsed.cursor !== revision) {
    return null
  }
  return { type: 'event', resource, name: 'revision', data: { revision }, cursor: revision }
}

/** thread-tree 的提示型事件：根 id 在 resource 上，data 恒为空对象且绝不携带 cursor。 */
function decodeTreeSnapshotEvent(
  parsed: Record<string, unknown>,
  resource: ApplicationEventResource & { kind: 'thread-tree' },
): ApplicationEventServerMessage | null {
  if (!hasExactFields(parsed.data, ['nodes'])) {
    return null
  }
  const data = parsed.data as Record<string, unknown>
  if (!Array.isArray(data.nodes) || !data.nodes.every(isThreadTreeNode)) {
    return null
  }
  return {
    type: 'event',
    resource,
    name: 'snapshot',
    data: { nodes: data.nodes as HarnessThreadTreeNodeDTO[] },
  }
}

function isThreadTreeNode(value: unknown): value is HarnessThreadTreeNodeDTO {
  if (!isRecord(value)) {
    return false
  }
  const requiredFields = [
    'threadId', 'kind', 'parentThreadId', 'name', 'agentName', 'execution', 'status',
    'executionControl', 'turnCount', 'toolCallCount', 'outcome', 'updatedAt',
  ]
  return hasExactFields(value, requiredFields)
    && isCanonicalUuid(value.threadId)
    && (value.parentThreadId === null || isCanonicalUuid(value.parentThreadId))
    && THREAD_KIND_VALUES.includes(value.kind as HarnessThreadTreeNodeDTO['kind'])
    && typeof value.name === 'string'
    && typeof value.agentName === 'string'
    && isRecord(value.execution)
    && hasExactFields(value.execution, ['driver', 'settings'])
    && typeof value.execution.driver === 'string'
    && THREAD_RUNTIME_STATUS_VALUES.includes(value.status as HarnessThreadTreeNodeDTO['status'])
    && THREAD_EXECUTION_CONTROL_VALUES.includes(
      value.executionControl as HarnessThreadTreeNodeDTO['executionControl'],
    )
    && Number.isSafeInteger(value.turnCount)
    && Number.isSafeInteger(value.toolCallCount)
    && (value.outcome === null
      || TURN_END_OUTCOME_VALUES.includes(
        value.outcome as NonNullable<HarnessThreadTreeNodeDTO['outcome']>,
      ))
    && typeof value.updatedAt === 'number'
    && Number.isFinite(value.updatedAt)
}

function decodeTreeChangedEvent(
  parsed: Record<string, unknown>,
  resource: ApplicationEventResource & { kind: 'thread-tree' },
): ApplicationEventServerMessage | null {
  if (!hasExactFields(parsed.data, [])) {
    return null
  }
  return { type: 'event', resource, name: 'changed', data: {} }
}

/** environments 的提示型事件：data 恒为空对象且绝不携带 cursor。 */
function decodeEnvironmentsChangedEvent(
  parsed: Record<string, unknown>,
  resource: ApplicationEventResource & { kind: 'environments' },
): ApplicationEventServerMessage | null {
  if (!hasExactFields(parsed.data, [])) {
    return null
  }
  return { type: 'event', resource, name: 'changed', data: {} }
}

function decodeInteractionsChangedEvent(
  parsed: Record<string, unknown>,
  resource: ApplicationEventResource & { kind: 'interactions' },
): ApplicationEventServerMessage | null {
  const rootThreadId = parseSingleUuidField(parsed.data, 'rootThreadId')
  if (rootThreadId == null) {
    return null
  }
  return { type: 'event', resource, name: 'changed', data: { rootThreadId } }
}

/** 只允许声明过的字段；多余/未知字段拒绝。 */
function hasOnlyFields(
  value: Record<string, unknown>,
  fields: readonly string[],
): boolean {
  return Object.keys(value).every((key) => fields.includes(key))
}

function parseResource(value: unknown): ApplicationEventResource | null {
  if (!isRecord(value) || typeof value.kind !== 'string') {
    return null
  }
  if (value.kind === 'interactions' || value.kind === 'environments') {
    return hasExactFields(value, ['kind']) ? { kind: value.kind } : null
  }
  if (
    (value.kind !== 'thread' && value.kind !== 'canvas' && value.kind !== 'thread-tree' && value.kind !== 'chat-space')
    || !hasExactFields(value, ['kind', 'id'])
    || !isCanonicalUuid(value.id)
  ) {
    return null
  }
  return { kind: value.kind, id: value.id }
}

/** 提示型资源没有持久游标：ack 恒为 '0'，事件绝不携带 cursor。 */
function isHintResourceKind(kind: ApplicationEventResourceKind): boolean {
  return (
    kind === 'thread-tree' || kind === 'interactions' || kind === 'environments' || kind === 'chat-space'
  )
}

function isEventName(value: unknown): value is ApplicationEventName {
  return value === 'realtime'
    || value === 'changed'
    || value === 'revision'
    || value === 'snapshot'
    || value === 'tree'
    || value === 'threads'
    || value === 'space'
}

function parseCursor(value: unknown): ApplicationEventCursor | null {
  return typeof value === 'string' && CANONICAL_DECIMAL.test(value) ? value : null
}

/** revision 只接受正 canonical 十进制，且必须不超过有符号 64 位上限。 */
function parseRevision(value: unknown): ApplicationEventRevision | null {
  if (typeof value !== 'string' || !CANONICAL_POSITIVE_DECIMAL.test(value)) {
    return null
  }
  if (
    value.length > MAX_SIGNED_LONG_DECIMAL.length
    || (value.length === MAX_SIGNED_LONG_DECIMAL.length && value > MAX_SIGNED_LONG_DECIMAL)
  ) {
    return null
  }
  return value
}

/**
 * connected 帧的 heartbeat 间隔：canonical 正十进制字符串，且必须是可用毫秒数
 * （不超过 {@code Number.MAX_SAFE_INTEGER}），保证失联预算计算不丢精度。
 */
function parseHeartbeatIntervalMillis(value: unknown): string | null {
  const text = parseRevision(value)
  if (text == null || text.length > MAX_SAFE_INTEGER_DECIMAL.length) {
    return null
  }
  if (text.length === MAX_SAFE_INTEGER_DECIMAL.length && text > MAX_SAFE_INTEGER_DECIMAL) {
    return null
  }
  return text
}

/** durable version 事件的精确 data：单字段对象且字段值为 canonical 十进制字符串。 */
function parseSingleCursorField(
  data: unknown,
  key: 'version' | 'revision',
): ApplicationEventCursor | null {
  if (!isRecord(data) || Object.keys(data).length !== 1) {
    return null
  }
  return parseCursor(data[key])
}

function parseSingleUuidField(data: unknown, key: 'projectId' | 'rootThreadId'): string | null {
  if (!hasExactFields(data, [key])) {
    return null
  }
  const value = (data as Record<string, unknown>)[key]
  return parseUuid(value)
}

function parseUuid(value: unknown): string | null {
  return isCanonicalUuid(value) ? value : null
}

function hasExactFields(value: unknown, fields: readonly string[]): boolean {
  return (
    isRecord(value) && Object.keys(value).length === fields.length && hasOnlyFields(value, fields)
  )
}
