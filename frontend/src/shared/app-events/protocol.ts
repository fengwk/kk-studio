/**
 * 应用事件 WebSocket 协议（/api/events/v1）。
 *
 * client -> server（JSON 文本）：
 * - {"type":"subscribe","resource":{"kind":"thread"|"canvas","id":"..."}}
 * - {"type":"unsubscribe","resource":{...}}
 *
 * server -> client（JSON 文本）：
 * - {"type":"subscribed","resource":{...}}：订阅已在 wire 上建立（首次与重连后都会发送）。
 * - {"type":"event","resource":{...},"name":"revision"|"realtime"|"version","data":<JSON 值>}
 *   - thread：revision（持久 revision 前进）、realtime（Redis delta envelope 的 JSON 文本）。
 *   - canvas：version（{"version":"N"} 对象或其 JSON 文本）。
 * - {"type":"resync","resource":{...}}：需要整体替换为全量快照。
 * - {"type":"error","resource"?:{...},"message"?:string}：订阅/事件处理失败。
 *
 * 解码是严格的：帧形状、type、resource 与 event name 不合法一律丢弃，
 * 畸形消息永远不会到达 listeners。
 */

export type ApplicationEventResourceKind = 'thread' | 'canvas'

export interface ApplicationEventResource {
  kind: ApplicationEventResourceKind
  id: string
}

export type ApplicationEventName = 'revision' | 'realtime' | 'version'

export type ApplicationEventClientMessage =
  | { type: 'subscribe'; resource: ApplicationEventResource }
  | { type: 'unsubscribe'; resource: ApplicationEventResource }

export type ApplicationEventServerMessage =
  | { type: 'subscribed'; resource: ApplicationEventResource }
  | {
      type: 'event'
      resource: ApplicationEventResource
      name: ApplicationEventName
      data: unknown
    }
  | { type: 'resync'; resource: ApplicationEventResource }
  | { type: 'error'; resource?: ApplicationEventResource; message?: string }

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
  if (!isRecord(parsed) || typeof parsed.type !== 'string') {
    return null
  }
  switch (parsed.type) {
    case 'subscribed': {
      const resource = parseResource(parsed.resource)
      if (resource == null) {
        return null
      }
      return { type: 'subscribed', resource }
    }
    case 'event': {
      const resource = parseResource(parsed.resource)
      if (resource == null || !isEventName(parsed.name)) {
        return null
      }
      return { type: 'event', resource, name: parsed.name, data: parsed.data }
    }
    case 'resync': {
      const resource = parseResource(parsed.resource)
      if (resource == null) {
        return null
      }
      return { type: 'resync', resource }
    }
    case 'error': {
      const message = typeof parsed.message === 'string' ? parsed.message : undefined
      if (parsed.resource === undefined) {
        return { type: 'error', message }
      }
      const resource = parseResource(parsed.resource)
      if (resource == null) {
        return null
      }
      return { type: 'error', resource, message }
    }
    default:
      return null
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function parseResource(value: unknown): ApplicationEventResource | null {
  if (!isRecord(value)) {
    return null
  }
  if (value.kind !== 'thread' && value.kind !== 'canvas') {
    return null
  }
  if (typeof value.id !== 'string' || value.id.length === 0) {
    return null
  }
  return { kind: value.kind, id: value.id }
}

function isEventName(value: unknown): value is ApplicationEventName {
  return value === 'revision' || value === 'realtime' || value === 'version'
}
