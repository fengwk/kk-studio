import { describe, expect, it } from 'vitest'
import {
  decodeServerMessage,
  encodeClientMessage,
} from '@/shared/app-events/protocol'

const THREAD_ID = '11111111-2222-4333-8444-555555555555'
const CANVAS_ID = 'cccccccc-0000-4000-8000-000000000001'
const threadResource = { kind: 'thread', id: THREAD_ID } as const
const canvasResource = { kind: 'canvas', id: CANVAS_ID } as const

describe('encodeClientMessage', () => {
  it('encodes subscribe/unsubscribe frames with version=1 and the resource', () => {
    expect(
      JSON.parse(encodeClientMessage({ version: 1, type: 'subscribe', resource: threadResource })),
    ).toEqual({ version: 1, type: 'subscribe', resource: threadResource })
    expect(
      JSON.parse(
        encodeClientMessage({ version: 1, type: 'unsubscribe', resource: canvasResource }),
      ),
    ).toEqual({ version: 1, type: 'unsubscribe', resource: canvasResource })
  })
})

describe('decodeServerMessage', () => {
  it('decodes the connection-level heartbeat with an exact field set', () => {
    expect(
      decodeServerMessage(JSON.stringify({ version: 1, type: 'heartbeat' })),
    ).toEqual({ type: 'heartbeat' })
    expect(
      decodeServerMessage(JSON.stringify({ version: 1, type: 'heartbeat', extra: true })),
    ).toBeNull()
  })

  it('decodes subscribed frames with the resource and canonical cursor', () => {
    expect(
      decodeServerMessage(
        JSON.stringify({ version: 1, type: 'subscribed', resource: threadResource, cursor: '42' }),
      ),
    ).toEqual({ type: 'subscribed', resource: threadResource, cursor: '42' })
  })

  it('decodes resync frames', () => {
    expect(
      decodeServerMessage(JSON.stringify({ version: 1, type: 'resync', resource: canvasResource })),
    ).toEqual({ type: 'resync', resource: canvasResource })
  })

  it('decodes event frames with strict per-name data shapes and mandatory matching cursor', () => {
    expect(
      decodeServerMessage(
        JSON.stringify({
          version: 1,
          type: 'event',
          resource: threadResource,
          name: 'version',
          data: { version: '43' },
          cursor: '43',
        }),
      ),
    ).toEqual({
      type: 'event',
      resource: threadResource,
      name: 'version',
      data: { version: '43' },
      cursor: '43',
    })
    expect(
      decodeServerMessage(
        JSON.stringify({
          version: 1,
          type: 'event',
          resource: threadResource,
          name: 'realtime',
          data: { type: 'MODEL_DELTA', threadId: THREAD_ID, sequence: 1 },
        }),
      ),
    ).toEqual({
      type: 'event',
      resource: threadResource,
      name: 'realtime',
      data: { type: 'MODEL_DELTA', threadId: THREAD_ID, sequence: 1 },
    })
    expect(
      decodeServerMessage(
        JSON.stringify({
          version: 1,
          type: 'event',
          resource: canvasResource,
          name: 'version',
          data: { version: '3' },
          cursor: '3',
        }),
      ),
    ).toEqual({
      type: 'event',
      resource: canvasResource,
      name: 'version',
      data: { version: '3' },
      cursor: '3',
    })
  })

  it('decodes error frames with code/message, with and without a resource', () => {
    expect(
      decodeServerMessage(
        JSON.stringify({ version: 1, type: 'error', code: 'INTERNAL', message: 'boom' }),
      ),
    ).toEqual({ type: 'error', code: 'INTERNAL', message: 'boom' })
    expect(
      decodeServerMessage(
        JSON.stringify({
          version: 1,
          type: 'error',
          resource: threadResource,
          code: 'RESOURCE_NOT_FOUND',
          message: 'boom',
        }),
      ),
    ).toEqual({
      type: 'error',
      resource: threadResource,
      code: 'RESOURCE_NOT_FOUND',
      message: 'boom',
    })
  })

  it('rejects malformed JSON and non-object frames', () => {
    expect(decodeServerMessage('not json')).toBeNull()
    expect(decodeServerMessage('')).toBeNull()
    expect(decodeServerMessage('null')).toBeNull()
    expect(decodeServerMessage('[]')).toBeNull()
    expect(decodeServerMessage('"string"')).toBeNull()
  })

  it('rejects wrong or missing protocol version', () => {
    const frame = { version: 1, type: 'resync', resource: threadResource }
    expect(decodeServerMessage(JSON.stringify({ ...frame, version: 2 }))).toBeNull()
    expect(decodeServerMessage(JSON.stringify({ ...frame, version: '1' }))).toBeNull()
    expect(
      decodeServerMessage(JSON.stringify({ type: 'resync', resource: threadResource })),
    ).toBeNull()
  })

  it('rejects unknown types and malformed resources', () => {
    expect(decodeServerMessage(JSON.stringify({ version: 1, type: 'unknown' }))).toBeNull()
    expect(decodeServerMessage(JSON.stringify({ version: 1, type: 'subscribed' }))).toBeNull()
    expect(
      decodeServerMessage(JSON.stringify({ version: 1, type: 'subscribed', resource: {} })),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({ version: 1, type: 'subscribed', resource: { kind: 'file', id: THREAD_ID } }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({ version: 1, type: 'subscribed', resource: { kind: 'thread' } }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({
          version: 1,
          type: 'resync',
          resource: { kind: 'canvas', id: 7 },
        }),
      ),
    ).toBeNull()
  })

  it('rejects non-canonical UUID resource ids', () => {
    for (const id of [
      '', // 空
      'thread-1', // 非 UUID
      '11111111-2222-4333-8444-55555555555', // 长度不足
      '11111111-2222-4333-8444-5555555555555', // 长度超长
      '11111111-2222-4333-8444-55555555555G', // 非十六进制
      '11111111-2222-4333-8444-55555555555  ', // 空白
      '11111111-2222-4333-8444-55555555555\n', // 换行
    ]) {
      expect(
        decodeServerMessage(
          JSON.stringify({ version: 1, type: 'resync', resource: { kind: 'thread', id } }),
        ),
      ).toBeNull()
    }
  })

  it('rejects non-canonical cursors', () => {
    const base = { version: 1, type: 'subscribed', resource: threadResource }
    for (const cursor of [0, 1, '01', '-1', '1.5', 'abc', '', ' 1', '1 ']) {
      expect(decodeServerMessage(JSON.stringify({ ...base, cursor }))).toBeNull()
    }
    // durable 事件的 cursor 必须 canonical 且与 data 值完全相等（缺失拒绝）。
    const event = {
      version: 1,
      type: 'event',
      resource: threadResource,
      name: 'version',
      data: { version: '3' },
    }
    expect(decodeServerMessage(JSON.stringify({ ...event, cursor: 'abc' }))).toBeNull()
    expect(decodeServerMessage(JSON.stringify(event))).toBeNull()
  })

  it('rejects event frames with unknown names and malformed data shapes', () => {
    const threadBase = { version: 1, type: 'event', resource: threadResource }
    expect(
      decodeServerMessage(JSON.stringify({ ...threadBase, name: 'snapshot', data: {} })),
    ).toBeNull()
    // version data 必须是精确单字段 {version}。
    expect(
      decodeServerMessage(
        JSON.stringify({
          ...threadBase,
          name: 'version',
          data: { version: '3', extra: true },
          cursor: '3',
        }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({ ...threadBase, name: 'version', data: {}, cursor: '3' }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({ ...threadBase, name: 'unknown_event', data: { version: '3' }, cursor: '3' }),
      ),
    ).toBeNull()
    // version data 必须是精确单字段 {version}。
    const canvasBase = { version: 1, type: 'event', resource: canvasResource }
    expect(
      decodeServerMessage(
        JSON.stringify({ ...canvasBase, name: 'version', data: { version: '01' }, cursor: '01' }),
      ),
    ).toBeNull()
    // realtime data 必须是 JSON 对象（字符串/数组/数字拒绝）。
    expect(
      decodeServerMessage(
        JSON.stringify({ ...threadBase, name: 'realtime', data: '{"type":"MODEL_DELTA"}' }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(JSON.stringify({ ...threadBase, name: 'realtime', data: [1, 2] })),
    ).toBeNull()
    expect(
      decodeServerMessage(JSON.stringify({ ...threadBase, name: 'realtime', data: 7 })),
    ).toBeNull()
  })

  it('rejects unknown or extra fields per frame type', () => {
    const subscribed = {
      version: 1,
      type: 'subscribed',
      resource: threadResource,
      cursor: '1',
      extra: true,
    }
    expect(decodeServerMessage(JSON.stringify(subscribed))).toBeNull()
    const event = {
      version: 1,
      type: 'event',
      resource: threadResource,
      name: 'version',
      data: { version: '1' },
      cursor: '1',
      extra: true,
    }
    expect(decodeServerMessage(JSON.stringify(event))).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({ version: 1, type: 'resync', resource: threadResource, cursor: '1' }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({
          version: 1,
          type: 'error',
          resource: threadResource,
          code: 'X',
          message: 'y',
          extra: true,
        }),
      ),
    ).toBeNull()
  })

  it('rejects resources with extra fields on every frame type', () => {
    const tainted = { kind: 'thread', id: THREAD_ID, extra: true }
    expect(
      decodeServerMessage(
        JSON.stringify({ version: 1, type: 'subscribed', resource: tainted, cursor: '1' }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({
          version: 1,
          type: 'event',
          resource: tainted,
          name: 'version',
          data: { version: '1' },
          cursor: '1',
        }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(JSON.stringify({ version: 1, type: 'resync', resource: tainted })),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({ version: 1, type: 'error', resource: tainted, code: 'X', message: 'y' }),
      ),
    ).toBeNull()
  })

  it('rejects cross-resource illegal names', () => {
    // canvas 上不允许 realtime
    expect(
      decodeServerMessage(
        JSON.stringify({
          version: 1,
          type: 'event',
          resource: canvasResource,
          name: 'realtime',
          data: { type: 'MODEL_DELTA' },
        }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({
          version: 1,
          type: 'event',
          resource: canvasResource,
          name: 'realtime',
          data: { type: 'MODEL_DELTA' },
        }),
      ),
    ).toBeNull()
  })

  it('rejects durable events with a missing or mismatched cursor', () => {
    const threadVersion = {
      version: 1,
      type: 'event',
      resource: threadResource,
      name: 'version',
      data: { version: '3' },
    }
    const canvasVersion = {
      version: 1,
      type: 'event',
      resource: canvasResource,
      name: 'version',
      data: { version: '3' },
    }
    // 缺失 cursor。
    expect(decodeServerMessage(JSON.stringify(threadVersion))).toBeNull()
    expect(decodeServerMessage(JSON.stringify(canvasVersion))).toBeNull()
    // cursor 与 data 值不相等（含非 canonical 表示与非字符串）。
    expect(decodeServerMessage(JSON.stringify({ ...threadVersion, cursor: '4' }))).toBeNull()
    expect(decodeServerMessage(JSON.stringify({ ...canvasVersion, cursor: '4' }))).toBeNull()
    expect(decodeServerMessage(JSON.stringify({ ...threadVersion, cursor: '03' }))).toBeNull()
    expect(decodeServerMessage(JSON.stringify({ ...threadVersion, cursor: 3 }))).toBeNull()
  })

  it('rejects realtime events carrying a cursor', () => {
    expect(
      decodeServerMessage(
        JSON.stringify({
          version: 1,
          type: 'event',
          resource: threadResource,
          name: 'realtime',
          data: { type: 'MODEL_DELTA' },
          cursor: '3',
        }),
      ),
    ).toBeNull()
  })

  it('rejects error frames without code or message', () => {
    expect(
      decodeServerMessage(JSON.stringify({ version: 1, type: 'error', code: 'X' })),
    ).toBeNull()
    expect(
      decodeServerMessage(JSON.stringify({ version: 1, type: 'error', message: 'y' })),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({ version: 1, type: 'error', code: '', message: 'y' }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({ version: 1, type: 'error', resource: threadResource, code: 'X', message: 7 }),
      ),
    ).toBeNull()
  })
})

describe('backend wire samples', () => {
  it('decodes the backend subscribed frame', () => {
    const raw =
      `{"version":1,"type":"subscribed","resource":{"kind":"thread","id":"${THREAD_ID}"},"cursor":"42"}`
    expect(decodeServerMessage(raw)).toEqual({
      type: 'subscribed',
      resource: { kind: 'thread', id: THREAD_ID },
      cursor: '42',
    })
  })

  it('decodes the backend thread version event frame', () => {
    const raw =
      `{"version":1,"type":"event","resource":{"kind":"thread","id":"${THREAD_ID}"},"name":"version","data":{"version":"43"},"cursor":"43"}`
    expect(decodeServerMessage(raw)).toEqual({
      type: 'event',
      resource: { kind: 'thread', id: THREAD_ID },
      name: 'version',
      data: { version: '43' },
      cursor: '43',
    })
  })

  it('decodes the backend thread realtime event frame (MODEL_DELTA object data)', () => {
    const raw =
      `{"version":1,"type":"event","resource":{"kind":"thread","id":"${THREAD_ID}"},"name":"realtime","data":{"threadId":"${THREAD_ID}","subjectKind":"MODEL_INVOCATION","subjectId":"11111111-2222-4333-8444-555555555556","attempt":1,"sequence":2,"type":"MODEL_DELTA","payload":{"kind":"TEXT_DELTA","text":"hello"},"createdAt":"2026-01-01T00:00:00Z"}}`
    expect(decodeServerMessage(raw)).toEqual({
      type: 'event',
      resource: { kind: 'thread', id: THREAD_ID },
      name: 'realtime',
      data: {
        threadId: THREAD_ID,
        subjectKind: 'MODEL_INVOCATION',
        subjectId: '11111111-2222-4333-8444-555555555556',
        attempt: 1,
        sequence: 2,
        type: 'MODEL_DELTA',
        payload: { kind: 'TEXT_DELTA', text: 'hello' },
        createdAt: '2026-01-01T00:00:00Z',
      },
    })
  })

  it('decodes the backend canvas version event frame', () => {
    const raw =
      `{"version":1,"type":"event","resource":{"kind":"canvas","id":"${CANVAS_ID}"},"name":"version","data":{"version":"7"},"cursor":"7"}`
    expect(decodeServerMessage(raw)).toEqual({
      type: 'event',
      resource: { kind: 'canvas', id: CANVAS_ID },
      name: 'version',
      data: { version: '7' },
      cursor: '7',
    })
  })

  it('decodes the backend resync frame', () => {
    const raw =
      `{"version":1,"type":"resync","resource":{"kind":"canvas","id":"${CANVAS_ID}"}}`
    expect(decodeServerMessage(raw)).toEqual({
      type: 'resync',
      resource: { kind: 'canvas', id: CANVAS_ID },
    })
  })

  it('decodes the backend error frames (with and without resource)', () => {
    expect(
      decodeServerMessage(
        `{"version":1,"type":"error","resource":{"kind":"thread","id":"${THREAD_ID}"},"code":"SUBSCRIBE_FAILED","message":"boom"}`,
      ),
    ).toEqual({
      type: 'error',
      resource: { kind: 'thread', id: THREAD_ID },
      code: 'SUBSCRIBE_FAILED',
      message: 'boom',
    })
    expect(
      decodeServerMessage(`{"version":1,"type":"error","code":"INTERNAL","message":"boom"}`),
    ).toEqual({ type: 'error', code: 'INTERNAL', message: 'boom' })
  })
})
