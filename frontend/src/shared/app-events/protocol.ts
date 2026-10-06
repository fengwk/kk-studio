/**
 * 应用事件 WebSocket 协议（/api/events/v1）。
 *
 * client -> server（JSON 文本，所有帧带 version=1）：
 * - {"version":1,"type":"subscribe","resource":{"kind":"thread"|"canvas"|"tree","id":"<UUID>"}}
 * - {"version":1,"type":"subscribe","resource":{"kind":"projects"|"interactions"|"environments"}}
 * - {"version":1,"type":"unsubscribe","resource":{...}}
 *
 * server -> client（JSON 文本，所有帧带 version=1）：
 * - {"version":1,"type":"subscribed","resource":{...},"cursor":"<canonical>"}
 *   订阅已在 wire 上建立（首次与重连后都会发送）；cursor 是资源当前游标。
 * - {"version":1,"type":"event","resource":{...},"name":"version"|"realtime"|"changed","data":{...},"cursor":"<canonical>"}
 *   - thread version：data {"version":"N"}，cursor 必带且与 data.version 完全相等；服务端通过
 *     PostgreSQL notification 感知持久化变更，浏览器只依赖此 version/cursor 契约。
 *   - thread realtime：data 为 lossy realtime delta envelope 的 JSON 对象（如 MODEL_DELTA），
 *     绝不携带 cursor。
 *   - canvas revision：data {"revision":"N"}，cursor 必带且与 data.revision 完全相等。
 *   - projects changed：data {"projectId":"<UUID>"}，只提示回读 Project Snapshot，不带 cursor。
 *   - tree changed：resource 带真实执行根 id，data 恒为 {}，只提示回读该根的执行树，不带 cursor。
 *   - interactions changed：data {"rootThreadId":"<UUID>"}，只提示回读待处理交互与全局角标，不带 cursor。
 *   - environments changed：data 恒为 {}，只提示回读环境列表，不带 cursor。
 * - {"version":1,"type":"resync","resource":{...}}：需要整体替换为全量快照。
 * - {"version":1,"type":"heartbeat"}：连接级保活；客户端严格解码后静默消费。
 * - {"version":1,"type":"error","resource"?:{...},"code":"<string>","message":"<string>"}
 *
 * 解码是真正严格的：version 必须为 1、每种 type/name 只接受精确字段集、
 * 多余/未知字段一律拒绝；resource 精确只有 kind+id 且 id 必须是 canonical
 * UUID（小写十六进制）；全局 projects/interactions/environments resource 不带 id；
 * resource/name 组合必须合法（thread 仅 version|realtime，canvas 仅 revision，
 * projects|interactions|environments 仅 changed，tree 仅 changed）；cursor 与
 * version data 必须是 canonical 非负十进制字符串，durable 事件的 cursor 必须
 * 存在且与 data 值完全相等，提示型资源（projects/tree/interactions/environments）
 * 的 ack cursor 恒为 '0' 且事件绝不携带 cursor；realtime data 必须是非数组 JSON
 * 对象且不得携带 cursor。畸形消息永远不会到达 listeners。
 */

export type ApplicationEventResourceKind =
  | 'thread'
  | 'canvas'
  | 'tree'
  | 'projects'
  | 'interactions'
  | 'environments'

export type ApplicationEventResource =
  | { kind: 'thread'; id: string }
  | { kind: 'canvas'; id: string }
  | { kind: 'tree'; id: string }
  | { kind: 'projects' }
  | { kind: 'interactions' }
  | { kind: 'environments' }

export type ApplicationEventName = 'version' | 'realtime' | 'changed' | 'revision'

/** canonical 非负十进制字符串：'0' 或非零开头，无前导零、无符号、无空白。 */
export type ApplicationEventCursor = string

export type ApplicationEventClientMessage =
  | { version: 1; type: 'subscribe'; resource: ApplicationEventResource }
  | { version: 1; type: 'unsubscribe'; resource: ApplicationEventResource }

/** thread 的持久 version 事件：cursor 必带且与 data.version 完全相等。 */
type ApplicationEventThreadVersionEvent = {
  type: 'event'
  resource: ApplicationEventResource & { kind: 'thread' }
  name: 'version'
  data: { version: ApplicationEventCursor }
  cursor: ApplicationEventCursor
}

/** thread 的 realtime 事件：data 为 delta envelope JSON 对象，绝不携带 cursor。 */
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

type ApplicationEventProjectChangedEvent = {
  type: 'event'
  resource: ApplicationEventResource & { kind: 'projects' }
  name: 'changed'
  data: { projectId: string }
}

/** tree 的提示型 changed 事件：根 id 已在 resource 上，data 恒为空对象且绝不携带 cursor。 */
type ApplicationEventTreeChangedEvent = {
  type: 'event'
  resource: ApplicationEventResource & { kind: 'tree' }
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
  return typeof rootThreadId === 'string' && CANONICAL_UUID.test(rootThreadId)
    ? rootThreadId
    : null
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

export type ApplicationEventServerMessage =
  | { type: 'heartbeat' }
  | { type: 'subscribed'; resource: ApplicationEventResource; cursor: ApplicationEventCursor }
  | ApplicationEventThreadVersionEvent
  | ApplicationEventThreadRealtimeEvent
  | ApplicationEventCanvasRevisionEvent
  | ApplicationEventProjectChangedEvent
  | ApplicationEventTreeChangedEvent
  | ApplicationEventInteractionsChangedEvent
  | ApplicationEventEnvironmentsChangedEvent
  | { type: 'resync'; resource: ApplicationEventResource }
  | { type: 'error'; resource?: ApplicationEventResource; code: string; message: string }

const CANONICAL_DECIMAL = /^(0|[1-9][0-9]*)$/
const CANONICAL_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const EVENT_FIELDS = ['version', 'type', 'resource', 'name', 'data', 'cursor'] as const

export function encodeClientMessage(message: ApplicationEventClientMessage): string {
  return JSON.stringify(message)
}

export function decodeServerMessage(raw: string): ApplicationEventServerMessage | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }
  if (!isRecord(parsed) || parsed.version !== 1 || typeof parsed.type !== 'string') {
    return null
  }
  switch (parsed.type) {
    case 'heartbeat': {
      if (!hasOnlyFields(parsed, ['version', 'type'])) {
        return null
      }
      return { type: 'heartbeat' }
    }
    case 'subscribed': {
      if (!hasOnlyFields(parsed, ['version', 'type', 'resource', 'cursor'])) {
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
      if (isHintResourceKind(resource.kind) && cursor !== '0') {
        return null
      }
      return { type: 'subscribed', resource, cursor }
    }
    case 'event': {
      if (!hasOnlyFields(parsed, EVENT_FIELDS)) {
        return null
      }
      const resource = parseResource(parsed.resource)
      if (resource == null || !isEventName(parsed.name)) {
        return null
      }
      // resource/name 组合必须合法：thread 仅 version|realtime，canvas 仅 version。
      if (isThreadResource(resource)) {
        if (parsed.name === 'version') {
          const version = parseSingleCursorField(parsed.data, 'version')
          // durable 事件必须携带 canonical cursor，且与 data.version 完全相等。
          if (version == null || parsed.cursor !== version) {
            return null
          }
          const data = { version }
          return { type: 'event', resource, name: 'version', data, cursor: data.version }
        }
        if (parsed.name === 'realtime') {
          // realtime 事件绝不携带 cursor；data 必须是非数组 JSON 对象。
          if (parsed.cursor !== undefined) {
            return null
          }
          const data = isRecord(parsed.data) ? parsed.data : null
          if (data == null) {
            return null
          }
          return { type: 'event', resource, name: 'realtime', data }
        }
        return null
      }
      if (isCanvasResource(resource) && parsed.name === 'revision') {
        const revision = parseSingleCursorField(parsed.data, 'revision')
        // durable 事件必须携带 canonical cursor，且与 data.revision 完全相等。
        if (revision == null || parsed.cursor !== revision) {
          return null
        }
        const data = { revision }
        return { type: 'event', resource, name: 'revision', data, cursor: data.revision }
      }
      if (resource.kind === 'projects' && parsed.name === 'changed') {
        const projectId = parseSingleUuidField(parsed.data, 'projectId')
        if (projectId == null || parsed.cursor !== undefined) {
          return null
        }
        return { type: 'event', resource, name: 'changed', data: { projectId } }
      }
      if (resource.kind === 'tree' && parsed.name === 'changed') {
        // 提示型事件只提示回读：data 恒为空对象且绝不携带 cursor。
        if (!hasExactFields(parsed.data, []) || parsed.cursor !== undefined) {
          return null
        }
        return { type: 'event', resource, name: 'changed', data: {} }
      }
      if (resource.kind === 'interactions' && parsed.name === 'changed') {
        const rootThreadId = parseSingleUuidField(parsed.data, 'rootThreadId')
        if (rootThreadId == null || parsed.cursor !== undefined) {
          return null
        }
        return { type: 'event', resource, name: 'changed', data: { rootThreadId } }
      }
      if (resource.kind === 'environments' && parsed.name === 'changed') {
        if (!hasExactFields(parsed.data, []) || parsed.cursor !== undefined) {
          return null
        }
        return { type: 'event', resource, name: 'changed', data: {} }
      }
      return null
    }
    case 'resync': {
      if (!hasOnlyFields(parsed, ['version', 'type', 'resource'])) {
        return null
      }
      const resource = parseResource(parsed.resource)
      if (resource == null) {
        return null
      }
      return { type: 'resync', resource }
    }
    case 'error': {
      if (!hasOnlyFields(parsed, ['version', 'type', 'resource', 'code', 'message'])) {
        return null
      }
      if (parsed.resource === undefined) {
        if (typeof parsed.code !== 'string' || parsed.code.length === 0) {
          return null
        }
        if (typeof parsed.message !== 'string') {
          return null
        }
        return { type: 'error', code: parsed.code, message: parsed.message }
      }
      const resource = parseResource(parsed.resource)
      if (resource == null || typeof parsed.code !== 'string' || parsed.code.length === 0) {
        return null
      }
      if (typeof parsed.message !== 'string') {
        return null
      }
      return { type: 'error', resource, code: parsed.code, message: parsed.message }
    }
    default:
      return null
  }
}

/** 只允许声明过的字段；多余/未知字段拒绝。 */
function hasOnlyFields(
  value: Record<string, unknown>,
  fields: readonly string[],
): boolean {
  return Object.keys(value).every((key) => fields.includes(key))
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function parseResource(value: unknown): ApplicationEventResource | null {
  if (!isRecord(value) || typeof value.kind !== 'string') {
    return null
  }
  if (value.kind === 'projects' || value.kind === 'interactions' || value.kind === 'environments') {
    return hasExactFields(value, ['kind']) ? { kind: value.kind } : null
  }
  if (
    (value.kind !== 'thread' && value.kind !== 'canvas' && value.kind !== 'tree')
    || !hasExactFields(value, ['kind', 'id'])
    || typeof value.id !== 'string'
    || !CANONICAL_UUID.test(value.id)
  ) {
    return null
  }
  return { kind: value.kind, id: value.id }
}

/** 提示型资源没有持久游标：ack 恒为 '0'，事件绝不携带 cursor。 */
function isHintResourceKind(kind: ApplicationEventResourceKind): boolean {
  return (
    kind === 'projects' || kind === 'tree' || kind === 'interactions' || kind === 'environments'
  )
}

function isEventName(value: unknown): value is ApplicationEventName {
  return value === 'version' || value === 'realtime' || value === 'changed' || value === 'revision'
}

/** resource.kind 判别守卫：保证 thread/canvas 各自的事件组合在类型层面也合法。 */
function isThreadResource(
  resource: ApplicationEventResource,
): resource is ApplicationEventResource & { kind: 'thread' } {
  return resource.kind === 'thread'
}

function isCanvasResource(
  resource: ApplicationEventResource,
): resource is ApplicationEventResource & { kind: 'canvas' } {
  return resource.kind === 'canvas'
}

function parseCursor(value: unknown): ApplicationEventCursor | null {
  return typeof value === 'string' && CANONICAL_DECIMAL.test(value) ? value : null
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
  return typeof value === 'string' && CANONICAL_UUID.test(value) ? value : null
}

function hasExactFields(value: unknown, fields: readonly string[]): boolean {
  return (
    isRecord(value) && Object.keys(value).length === fields.length && hasOnlyFields(value, fields)
  )
}
