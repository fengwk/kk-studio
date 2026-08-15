/**
 * 应用事件 WebSocket 协议（/api/events/v1）。
 *
 * client -> server（JSON 文本，所有帧带 version=1）：
 * - {"version":1,"type":"subscribe","resource":{"kind":"thread"|"canvas","id":"<UUID>"}}
 * - {"version":1,"type":"unsubscribe","resource":{...}}
 *
 * server -> client（JSON 文本，所有帧带 version=1）：
 * - {"version":1,"type":"subscribed","resource":{...},"cursor":"<canonical>"}
 *   订阅已在 wire 上建立（首次与重连后都会发送）；cursor 是资源当前游标。
 * - {"version":1,"type":"event","resource":{...},"name":"revision"|"realtime"|"version","data":{...},"cursor":"<canonical>"}
 *   - thread revision：data {"revision":"N"}，cursor 必带且与 data.revision 完全相等。
 *   - thread realtime：data 为 Redis delta envelope 的 JSON 对象（如 MODEL_DELTA），绝不携带 cursor。
 *   - canvas version：data {"version":"N"}，cursor 必带且与 data.version 完全相等。
 * - {"version":1,"type":"resync","resource":{...}}：需要整体替换为全量快照。
 * - {"version":1,"type":"error","resource"?:{...},"code":"<string>","message":"<string>"}
 *
 * 解码是真正严格的：version 必须为 1、每种 type/name 只接受精确字段集、
 * 多余/未知字段一律拒绝；resource 精确只有 kind+id 且 id 必须是 canonical
 * UUID（小写十六进制）；resource/name 组合必须合法（thread 仅 revision|realtime，
 * canvas 仅 version）；cursor 与 revision/version data 必须是 canonical 非负
 * 十进制字符串，durable 事件的 cursor 必须存在且与 data 值完全相等；realtime
 * data 必须是非数组 JSON 对象且不得携带 cursor。畸形消息永远不会到达 listeners。
 */

export type ApplicationEventResourceKind = 'thread' | 'canvas'

export interface ApplicationEventResource {
  kind: ApplicationEventResourceKind
  id: string
}

export type ApplicationEventName = 'revision' | 'realtime' | 'version'

/** canonical 非负十进制字符串：'0' 或非零开头，无前导零、无符号、无空白。 */
export type ApplicationEventCursor = string

export type ApplicationEventClientMessage =
  | { version: 1; type: 'subscribe'; resource: ApplicationEventResource }
  | { version: 1; type: 'unsubscribe'; resource: ApplicationEventResource }

/** thread 的持久 revision 事件：cursor 必带且与 data.revision 完全相等。 */
type ApplicationEventThreadRevisionEvent = {
  type: 'event'
  resource: ApplicationEventResource & { kind: 'thread' }
  name: 'revision'
  data: { revision: ApplicationEventCursor }
  cursor: ApplicationEventCursor
}

/** thread 的 realtime 事件：data 为 delta envelope JSON 对象，绝不携带 cursor。 */
type ApplicationEventThreadRealtimeEvent = {
  type: 'event'
  resource: ApplicationEventResource & { kind: 'thread' }
  name: 'realtime'
  data: Record<string, unknown>
}

/** canvas 的持久 version 事件：cursor 必带且与 data.version 完全相等。 */
type ApplicationEventCanvasVersionEvent = {
  type: 'event'
  resource: ApplicationEventResource & { kind: 'canvas' }
  name: 'version'
  data: { version: ApplicationEventCursor }
  cursor: ApplicationEventCursor
}

export type ApplicationEventServerMessage =
  | { type: 'subscribed'; resource: ApplicationEventResource; cursor: ApplicationEventCursor }
  | ApplicationEventThreadRevisionEvent
  | ApplicationEventThreadRealtimeEvent
  | ApplicationEventCanvasVersionEvent
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
      // resource/name 组合必须合法：thread 仅 revision|realtime，canvas 仅 version。
      if (isThreadResource(resource)) {
        if (parsed.name === 'revision') {
          const revision = parseSingleCursorField(parsed.data, 'revision')
          // durable 事件必须携带 canonical cursor，且与 data.revision 完全相等。
          if (revision == null || parsed.cursor !== revision) {
            return null
          }
          const data = { revision }
          return { type: 'event', resource, name: 'revision', data, cursor: data.revision }
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
      if (isCanvasResource(resource) && parsed.name === 'version') {
        const version = parseSingleCursorField(parsed.data, 'version')
        // durable 事件必须携带 canonical cursor，且与 data.version 完全相等。
        if (version == null || parsed.cursor !== version) {
          return null
        }
        const data = { version }
        return { type: 'event', resource, name: 'version', data, cursor: data.version }
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
  if (!isRecord(value) || !hasOnlyFields(value, ['kind', 'id']) || typeof value.id !== 'string') {
    return null
  }
  if (value.kind !== 'thread' && value.kind !== 'canvas') {
    return null
  }
  if (!CANONICAL_UUID.test(value.id)) {
    return null
  }
  return { kind: value.kind, id: value.id }
}

function isEventName(value: unknown): value is ApplicationEventName {
  return value === 'revision' || value === 'realtime' || value === 'version'
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

/** durable（revision/version）事件的精确 data：单字段对象且字段值为 canonical 十进制字符串。 */
function parseSingleCursorField(
  data: unknown,
  key: 'revision' | 'version',
): ApplicationEventCursor | null {
  if (!isRecord(data) || Object.keys(data).length !== 1) {
    return null
  }
  return parseCursor(data[key])
}
