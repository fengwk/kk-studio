import { describe, expect, it } from 'vitest'
import {
  decodeServerMessage,
  encodeClientMessage,
  INVALID_EVENT_PAYLOAD,
} from '@/shared/app-events/protocol'

const THREAD_ID = '11111111-2222-4333-8444-555555555555'
const CANVAS_ID = 'cccccccc-0000-4000-8000-000000000001'
const threadResource = { kind: 'thread', id: THREAD_ID } as const
const canvasResource = { kind: 'canvas', id: CANVAS_ID } as const
const treeResource = { kind: 'thread-tree', id: THREAD_ID } as const
const projectsResource = { kind: 'projects' } as const
const interactionsResource = { kind: 'interactions' } as const
const environmentsResource = { kind: 'environments' } as const
const EVENT_NODE_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const CONNECTION_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const BROWSER_SESSION_ID = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'
const ENVIRONMENT_ID = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
const VIEW_ID = 'ffffffff-ffff-4fff-8fff-ffffffffffff'
const CLAIM_ID = '99999999-9999-4999-8999-999999999999'
const HEAD_ID = '11111111-2222-4333-8444-555555555556'
const SESSION_ID = '11111111-2222-4333-8444-555555555557'

function threadSnapshot(version = '43') {
  return {
    version,
    thread: {
      threadId: THREAD_ID, name: 'Thread', sessionId: SESSION_ID, rootThreadId: THREAD_ID,
      parentThreadId: null, headEntryId: HEAD_ID, version, yoloEnabled: false,
      nextCommandSequence: '1', status: 'IDLE', executionControl: 'RUNNABLE',
      branchSettings: {
        agentName: 'agent', execution: { driver: 'builtin', settings: {} }, environmentName: null,
      }, createdAt: 0, updatedAt: 0,
    },
    baseEntryId: null, entries: [], queuedCommands: [], activeAssistantOutput: null,
    generationFailures: [], toolInvocations: [], stopReceipts: [], unconfirmedExecutions: [],
  }
}

/** 期望的资源级坏载荷错误：只带稳定码与该资源/事件名，不回显任何载荷内容。 */
function invalidPayload(
  resource: Record<string, unknown>,
  kind: string,
  name: string,
) {
  return {
    type: 'error',
    resource,
    code: INVALID_EVENT_PAYLOAD,
    message: `received an invalid ${kind} ${name} payload for this resource`,
  }
}

describe('encodeClientMessage', () => {
  it('encodes Thread catch-up cursors and preserves other resource subscribe frames', () => {
    expect(JSON.parse(encodeClientMessage({
      type: 'subscribe', resource: threadResource, afterEntryId: HEAD_ID,
    }))).toEqual({ type: 'subscribe', resource: threadResource, afterEntryId: HEAD_ID })
    expect(
      JSON.parse(encodeClientMessage({ type: 'subscribe', resource: threadResource })),
    ).toEqual({ type: 'subscribe', resource: threadResource })
    expect(
      JSON.parse(
        encodeClientMessage({ type: 'unsubscribe', resource: canvasResource }),
      ),
    ).toEqual({ type: 'unsubscribe', resource: canvasResource })
    expect(
      JSON.parse(
        encodeClientMessage({ type: 'subscribe', resource: projectsResource }),
      ),
    ).toEqual({ type: 'subscribe', resource: projectsResource })
    expect(
      JSON.parse(encodeClientMessage({ type: 'subscribe', resource: treeResource })),
    ).toEqual({ type: 'subscribe', resource: treeResource })
    expect(
      JSON.parse(encodeClientMessage({ type: 'refresh', resource: treeResource })),
    ).toEqual({ type: 'refresh', resource: treeResource })
    expect(
      JSON.parse(
        encodeClientMessage({ type: 'subscribe', resource: interactionsResource }),
      ),
    ).toEqual({ type: 'subscribe', resource: interactionsResource })
    expect(
      JSON.parse(
        encodeClientMessage({ type: 'subscribe', resource: environmentsResource }),
      ),
    ).toEqual({ type: 'subscribe', resource: environmentsResource })
  })
})

describe('connection identity and ide-use', () => {
  it('decodes the connected first frame and rejects unknown fields or malformed identities', () => {
    const connected = {
      type: 'connected',
      eventNodeId: EVENT_NODE_ID,
      connectionId: CONNECTION_ID,
      heartbeatIntervalMillis: '20000',
    }
    expect(decodeServerMessage(JSON.stringify(connected))).toEqual({
      type: 'connected',
      eventNodeId: EVENT_NODE_ID,
      connectionId: CONNECTION_ID,
      heartbeatIntervalMillis: '20000',
    })
    expect(decodeServerMessage(JSON.stringify({ ...connected, version: 2 }))).toBeNull()
    expect(
      decodeServerMessage(JSON.stringify({ ...connected, connectionId: CONNECTION_ID.toUpperCase() })),
    ).toBeNull()
    expect(decodeServerMessage(JSON.stringify({ ...connected, extra: true }))).toBeNull()
  })

  it('decodes heartbeatIntervalMillis as a canonical positive millisecond string within safe integer range', () => {
    const connected = {
      type: 'connected',
      eventNodeId: EVENT_NODE_ID,
      connectionId: CONNECTION_ID,
      heartbeatIntervalMillis: '20000',
    }
    // canonical 正十进制字符串；边界值是 Number.MAX_SAFE_INTEGER。
    expect(
      decodeServerMessage(
        JSON.stringify({ ...connected, heartbeatIntervalMillis: '9007199254740991' }),
      ),
    ).toMatchObject({ heartbeatIntervalMillis: '9007199254740991' })
    for (const bad of ['0', '-1', '01', '1.0', '9007199254740992', 20000, undefined]) {
      expect(
        decodeServerMessage(JSON.stringify({ ...connected, heartbeatIntervalMillis: bad })),
      ).toBeNull()
    }
  })

  it('decodes ide-use-accepted only for a canonical positive signed-64 revision', () => {
    expect(
      decodeServerMessage(
        JSON.stringify({ type: 'ide-use-accepted', revision: '9223372036854775807' }),
      ),
    ).toEqual({ type: 'ide-use-accepted', revision: '9223372036854775807' })
    for (const revision of [0, '0', '01', '-1', '1.0', '9223372036854775808', undefined]) {
      expect(
        decodeServerMessage(JSON.stringify({ type: 'ide-use-accepted', revision })),
      ).toBeNull()
    }
  })

  it('encodes an ide-use frame for the wire', () => {
    const frame = {
      type: 'ide-use' as const,
      browserSessionId: BROWSER_SESSION_ID,
      revision: '1',
      views: [{ environmentId: ENVIRONMENT_ID, viewId: VIEW_ID, claimId: CLAIM_ID }],
    }
    expect(JSON.parse(encodeClientMessage(frame))).toEqual(frame)
  })
})

describe('decodeServerMessage', () => {
  it('decodes the connection-level heartbeat with an exact field set', () => {
    expect(
      decodeServerMessage(JSON.stringify({  type: 'heartbeat' })),
    ).toEqual({ type: 'heartbeat' })
    expect(
      decodeServerMessage(JSON.stringify({  type: 'heartbeat', extra: true })),
    ).toBeNull()
  })

  it('requires the Thread subscribed ack cursor to be zero', () => {
    expect(
      decodeServerMessage(
        JSON.stringify({ type: 'subscribed', resource: threadResource, cursor: '0' }),
      ),
    ).toEqual({ type: 'subscribed', resource: threadResource, cursor: '0' })
    expect(decodeServerMessage(JSON.stringify({ type: 'subscribed', resource: threadResource, cursor: '42' }))).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({
          
          type: 'subscribed',
          resource: projectsResource,
          cursor: '0',
        }),
      ),
    ).toEqual({ type: 'subscribed', resource: projectsResource, cursor: '0' })
  })

  it('decodes hint-resource subscribed frames with the mandatory zero cursor', () => {
    for (const resource of [treeResource, interactionsResource, environmentsResource]) {
      expect(
        decodeServerMessage(
          JSON.stringify({  type: 'subscribed', resource, cursor: '0' }),
        ),
      ).toEqual({ type: 'subscribed', resource, cursor: '0' })
      // 提示型资源没有持久游标：非零 ack 一律拒绝。
      expect(
        decodeServerMessage(
          JSON.stringify({  type: 'subscribed', resource, cursor: '1' }),
        ),
      ).toBeNull()
    }
  })

  it('decodes authoritative thread-tree snapshots without a cursor', () => {
    const node = {
      threadId: THREAD_ID,
      kind: 'USER',
      parentThreadId: null,
      name: 'root',
      agentName: 'agent',
      execution: { driver: 'builtin', settings: {} },
      status: 'IDLE',
      executionControl: 'RUNNABLE',
      turnCount: 0,
      toolCallCount: 0,
      outcome: null,
      updatedAt: 0,
    }
    expect(decodeServerMessage(JSON.stringify({
      type: 'event', resource: treeResource, name: 'snapshot', data: { nodes: [node] },
    }))).toEqual({
      type: 'event', resource: treeResource, name: 'snapshot', data: { nodes: [node] },
    })
    expect(decodeServerMessage(JSON.stringify({
      type: 'event', resource: treeResource, name: 'snapshot', data: { nodes: [node] }, cursor: '1',
    }))).toBeNull()
    expect(decodeServerMessage(JSON.stringify({
      type: 'event', resource: treeResource, name: 'snapshot', data: { nodes: [{ ...node, extra: true }] },
    }))).toEqual(invalidPayload(treeResource, 'thread-tree', 'snapshot'))
  })

  it('decodes hint-resource changed events without a cursor', () => {
    expect(
      decodeServerMessage(
        JSON.stringify({
          
          type: 'event',
          resource: treeResource,
          name: 'changed',
          data: {},
        }),
      ),
    ).toEqual({ type: 'event', resource: treeResource, name: 'changed', data: {} })
    expect(
      decodeServerMessage(
        JSON.stringify({
          
          type: 'event',
          resource: interactionsResource,
          name: 'changed',
          data: { rootThreadId: THREAD_ID },
        }),
      ),
    ).toEqual({
      type: 'event',
      resource: interactionsResource,
      name: 'changed',
      data: { rootThreadId: THREAD_ID },
    })
    expect(
      decodeServerMessage(
        JSON.stringify({
          
          type: 'event',
          resource: environmentsResource,
          name: 'changed',
          data: {},
        }),
      ),
    ).toEqual({ type: 'event', resource: environmentsResource, name: 'changed', data: {} })
  })

  it('decodes resync frames', () => {
    expect(
      decodeServerMessage(JSON.stringify({  type: 'resync', resource: canvasResource })),
    ).toEqual({ type: 'resync', resource: canvasResource })
    expect(
      decodeServerMessage(JSON.stringify({  type: 'resync', resource: treeResource })),
    ).toEqual({ type: 'resync', resource: treeResource })
  })

  it('decodes snapshot-ref frames: Thread carries a top-level version, the tree does not', () => {
    expect(
      decodeServerMessage(
        JSON.stringify({
          type: 'snapshot-ref',
          resource: threadResource,
          version: '9223372036854775807',
        }),
      ),
    ).toEqual({ type: 'snapshot-ref', resource: threadResource, version: '9223372036854775807' })
    expect(
      decodeServerMessage(JSON.stringify({ type: 'snapshot-ref', resource: treeResource })),
    ).toEqual({ type: 'snapshot-ref', resource: treeResource })
  })

  it('rejects snapshot-ref frames without the resource-specific top-level version', () => {
    // Thread 的 version 必带（顶层）、必须是 canonical 非负十进制字符串。
    for (const frame of [
      { type: 'snapshot-ref', resource: threadResource },
      { type: 'snapshot-ref', resource: threadResource, version: 43 },
      { type: 'snapshot-ref', resource: threadResource, version: '01' },
      { type: 'snapshot-ref', resource: threadResource, version: '' },
      // version 不能藏在 resource 里。
      { type: 'snapshot-ref', resource: { ...threadResource, version: '1' } },
      // 执行树没有 version。
      { type: 'snapshot-ref', resource: treeResource, version: '1' },
      // 其他资源不会以整帧溢出形式出现。
      { type: 'snapshot-ref', resource: canvasResource },
      { type: 'snapshot-ref', resource: projectsResource },
      { type: 'snapshot-ref', resource: { kind: 'thread', id: 'not-a-uuid' }, version: '1' },
      // 字段集精确：不得混入 event 的字段。
      { type: 'snapshot-ref', resource: threadResource, version: '1', name: 'snapshot' },
      { type: 'snapshot-ref', resource: threadResource, version: '1', cursor: '1' },
    ]) {
      expect(decodeServerMessage(JSON.stringify(frame))).toBeNull()
    }
  })

  it('synthesizes a sanitized resource error for a routable but corrupt payload', () => {
    // 坏载荷可定位资源：解码器直接返回该资源的稳定错误，不回显任何载荷值。
    const message = decodeServerMessage(JSON.stringify({
      type: 'event',
      resource: threadResource,
      name: 'snapshot',
      data: { ...threadSnapshot(), version: 987654321 },
    }))
    expect(message).toEqual({
      type: 'error',
      resource: threadResource,
      code: INVALID_EVENT_PAYLOAD,
      message: 'received an invalid thread snapshot payload for this resource',
    })
    // 载荷值（错误值 987654321 与其它业务字段）不会进入报文。
    expect(JSON.stringify(message)).not.toContain('987654321')
    // cursor 值不合法、data 形状不合法同样属于资源级错误。
    expect(decodeServerMessage(JSON.stringify({
      type: 'event', resource: canvasResource, name: 'revision', data: { revision: '3' }, cursor: '4',
    }))).toEqual({
      type: 'error', resource: canvasResource, code: INVALID_EVENT_PAYLOAD,
      message: 'received an invalid canvas revision payload for this resource',
    })
    expect(decodeServerMessage(JSON.stringify({
      type: 'event', resource: canvasResource, name: 'revision', data: { revision: '03' }, cursor: '03',
    }))).toEqual({
      type: 'error', resource: canvasResource, code: INVALID_EVENT_PAYLOAD,
      message: 'received an invalid canvas revision payload for this resource',
    })
  })

  it('still fails strictly when the frame cannot locate a resource', () => {
    for (const raw of [
      'not json',
      '',
      'null',
      '[]',
      JSON.stringify({ type: 'unknown' }),
      JSON.stringify({ type: 'event' }),
      // 外层字段集不精确。
      JSON.stringify({ type: 'event', resource: threadResource, name: 'snapshot', data: {}, extra: 1 }),
      JSON.stringify({ type: 'event', resource: threadResource, name: 'snapshot' }),
      // 非法 resource。
      JSON.stringify({ type: 'event', resource: { kind: 'thread' }, name: 'snapshot', data: {} }),
      JSON.stringify({ type: 'event', resource: { kind: 'file', id: THREAD_ID }, name: 'snapshot', data: {} }),
      // 非法 resource/name 组合。
      JSON.stringify({ type: 'event', resource: threadResource, name: 'revision', data: { revision: '1' } }),
      JSON.stringify({ type: 'event', resource: canvasResource, name: 'snapshot', data: {} }),
      JSON.stringify({ type: 'event', resource: projectsResource, name: 'snapshot', data: {} }),
      JSON.stringify({ type: 'event', resource: threadResource, name: 'unknown', data: {} }),
      // cursor 出现与否与组合不符：属于连接级违规，不得被降级为资源错误。
      JSON.stringify({ type: 'event', resource: threadResource, name: 'snapshot', data: threadSnapshot(), cursor: '43' }),
      JSON.stringify({ type: 'event', resource: treeResource, name: 'snapshot', data: { nodes: [] }, cursor: '1' }),
      JSON.stringify({ type: 'event', resource: canvasResource, name: 'revision', data: { revision: '3' } }),
    ]) {
      expect(decodeServerMessage(raw)).toBeNull()
    }
  })

  it('uses a stable sanitized code for resource-local payload failures', () => {
    expect(INVALID_EVENT_PAYLOAD).toBe('INVALID_EVENT_PAYLOAD')
  })

  it('decodes thread snapshot and realtime frames with their distinct cursor rules', () => {
    expect(decodeServerMessage(JSON.stringify({
      type: 'event', resource: threadResource, name: 'snapshot', data: threadSnapshot(),
    }))).toEqual({
      type: 'event', resource: threadResource, name: 'snapshot', data: threadSnapshot(),
    })
    expect(decodeServerMessage(JSON.stringify({
      type: 'event', resource: threadResource, name: 'snapshot', data: threadSnapshot(), cursor: '43',
    }))).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({
          
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
          
          type: 'event',
          resource: canvasResource,
          name: 'revision',
          data: { revision: '3' },
          cursor: '3',
        }),
      ),
    ).toEqual({
      type: 'event',
      resource: canvasResource,
      name: 'revision',
      data: { revision: '3' },
      cursor: '3',
    })
    expect(
      decodeServerMessage(
        JSON.stringify({
          
          type: 'event',
          resource: projectsResource,
          name: 'changed',
          data: { projectId: THREAD_ID },
        }),
      ),
    ).toEqual({
      type: 'event',
      resource: projectsResource,
      name: 'changed',
      data: { projectId: THREAD_ID },
    })
  })

  it('decodes the resource-scoped thread-tree snapshot failure signal', () => {
    expect(decodeServerMessage(JSON.stringify({
      type: 'error',
      resource: treeResource,
      code: 'TREE_SNAPSHOT_FAILED',
      message: 'authoritative thread-tree snapshot failed',
    }))).toEqual({
      type: 'error',
      resource: treeResource,
      code: 'TREE_SNAPSHOT_FAILED',
      message: 'authoritative thread-tree snapshot failed',
    })
  })

  it('decodes error frames with code/message, with and without a resource', () => {
    expect(
      decodeServerMessage(
        JSON.stringify({  type: 'error', code: 'INTERNAL', message: 'boom' }),
      ),
    ).toEqual({ type: 'error', code: 'INTERNAL', message: 'boom' })
    expect(
      decodeServerMessage(
        JSON.stringify({
          
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

  it('rejects frames with unknown fields including version', () => {
    const frame = { type: 'resync', resource: threadResource }
    expect(decodeServerMessage(JSON.stringify({ ...frame, version: 2 }))).toBeNull()
    expect(decodeServerMessage(JSON.stringify({ ...frame, version: 1 }))).toBeNull()
    expect(decodeServerMessage(JSON.stringify({ ...frame, version: '1' }))).toBeNull()
    expect(decodeServerMessage(JSON.stringify(frame))).toEqual(frame)
  })

  it('rejects unknown types and malformed resources', () => {
    expect(decodeServerMessage(JSON.stringify({  type: 'unknown' }))).toBeNull()
    expect(decodeServerMessage(JSON.stringify({  type: 'subscribed' }))).toBeNull()
    expect(
      decodeServerMessage(JSON.stringify({  type: 'subscribed', resource: {} })),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({  type: 'subscribed', resource: { kind: 'file', id: THREAD_ID } }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({  type: 'subscribed', resource: { kind: 'thread' } }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({
          
          type: 'resync',
          resource: { kind: 'canvas', id: 7 },
        }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({
          
          type: 'subscribed',
          resource: { kind: 'projects', id: THREAD_ID },
          cursor: '0',
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
          JSON.stringify({  type: 'resync', resource: { kind: 'thread', id } }),
        ),
      ).toBeNull()
    }
  })

  it('rejects non-canonical cursors', () => {
    const base = {  type: 'subscribed', resource: threadResource }
    for (const cursor of [0, 1, '01', '-1', '1.5', 'abc', '', ' 1', '1 ']) {
      expect(decodeServerMessage(JSON.stringify({ ...base, cursor }))).toBeNull()
    }
    // durable 事件的 cursor 必须 canonical 且与 data 值完全相等（缺失拒绝）。
    const event = {
      
      type: 'event',
      resource: threadResource,
      name: 'version',
      data: { version: '3' },
    }
    expect(decodeServerMessage(JSON.stringify({ ...event, cursor: 'abc' }))).toBeNull()
    expect(decodeServerMessage(JSON.stringify(event))).toBeNull()
  })

  it('rejects event frames with unknown names and malformed data shapes', () => {
    const threadBase = {  type: 'event', resource: threadResource }
    expect(
      decodeServerMessage(JSON.stringify({ ...threadBase, name: 'snapshot', data: {} })),
    ).toEqual(invalidPayload(threadResource, 'thread', 'snapshot'))
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
    // revision data 必须是精确单字段 {revision}。
    const canvasBase = {  type: 'event', resource: canvasResource }
    expect(
      decodeServerMessage(
        JSON.stringify({ ...canvasBase, name: 'revision', data: { revision: '01' }, cursor: '01' }),
      ),
    ).toEqual(invalidPayload(canvasResource, 'canvas', 'revision'))
    // realtime data 必须是 JSON 对象（字符串/数组/数字拒绝）。
    expect(
      decodeServerMessage(
        JSON.stringify({ ...threadBase, name: 'realtime', data: '{"type":"MODEL_DELTA"}' }),
      ),
    ).toEqual(invalidPayload(threadResource, 'thread', 'realtime'))
    expect(
      decodeServerMessage(JSON.stringify({ ...threadBase, name: 'realtime', data: [1, 2] })),
    ).toEqual(invalidPayload(threadResource, 'thread', 'realtime'))
    expect(
      decodeServerMessage(JSON.stringify({ ...threadBase, name: 'realtime', data: 7 })),
    ).toEqual(invalidPayload(threadResource, 'thread', 'realtime'))
  })

  it('rejects unknown or extra fields per frame type', () => {
    const subscribed = {
      
      type: 'subscribed',
      resource: threadResource,
      cursor: '1',
      extra: true,
    }
    expect(decodeServerMessage(JSON.stringify(subscribed))).toBeNull()
    const event = {
      
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
        JSON.stringify({  type: 'resync', resource: threadResource, cursor: '1' }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({
          
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
        JSON.stringify({  type: 'subscribed', resource: tainted, cursor: '1' }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({
          
          type: 'event',
          resource: tainted,
          name: 'version',
          data: { version: '1' },
          cursor: '1',
        }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(JSON.stringify({  type: 'resync', resource: tainted })),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({  type: 'error', resource: tainted, code: 'X', message: 'y' }),
      ),
    ).toBeNull()
  })

  it('rejects cross-resource illegal names', () => {
    // canvas 上不允许 realtime
    expect(
      decodeServerMessage(
        JSON.stringify({
          
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
      
      type: 'event',
      resource: threadResource,
      name: 'version',
      data: { version: '3' },
    }
    const canvasVersion = {
      
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
          
          type: 'event',
          resource: threadResource,
          name: 'realtime',
          data: { type: 'MODEL_DELTA' },
          cursor: '3',
        }),
      ),
    ).toBeNull()
  })

  it('rejects unknown resources and invalid project payloads', () => {
    const projectChanged = {
      
      type: 'event',
      resource: projectsResource,
      name: 'changed',
      data: { projectId: THREAD_ID },
    }
    // cursor 出现本身与提示型组合不符：连接级违规。
    expect(
      decodeServerMessage(JSON.stringify({ ...projectChanged, cursor: '1' })),
    ).toBeNull()
    // 载荷值非法：只影响 projects 资源。
    expect(
      decodeServerMessage(
        JSON.stringify({ ...projectChanged, data: { projectId: 'not-a-uuid' } }),
      ),
    ).toEqual(invalidPayload(projectsResource, 'projects', 'changed'))
    expect(
      decodeServerMessage(
        JSON.stringify({ ...projectChanged, data: { projectId: THREAD_ID, extra: true } }),
      ),
    ).toEqual(invalidPayload(projectsResource, 'projects', 'changed'))
    expect(
      decodeServerMessage(
        JSON.stringify({ ...projectChanged, resource: { kind: 'unknown' } }),
      ),
    ).toBeNull()
  })

  it('rejects malformed hint-resource frames', () => {
    // tree 必须携带 canonical id；提示型全局 resource 不得携带 id。
    expect(
      decodeServerMessage(
        JSON.stringify({  type: 'subscribed', resource: { kind: 'thread-tree' }, cursor: '0' }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({
          
          type: 'subscribed',
          resource: { kind: 'interactions', id: THREAD_ID },
          cursor: '0',
        }),
      ),
    ).toBeNull()
    // data 形状必须精确：tree/environments 为空对象，interactions 只接受 canonical rootThreadId。
    const treeChanged = {
      
      type: 'event',
      resource: treeResource,
      name: 'changed',
      data: {},
    }
    expect(
      decodeServerMessage(JSON.stringify({ ...treeChanged, cursor: '0' })),
    ).toBeNull()
    expect(
      decodeServerMessage(JSON.stringify({ ...treeChanged, data: { rootThreadId: THREAD_ID } })),
    ).toEqual(invalidPayload(treeResource, 'thread-tree', 'changed'))
    const interactionsChanged = {
      
      type: 'event',
      resource: interactionsResource,
      name: 'changed',
      data: { rootThreadId: THREAD_ID },
    }
    expect(
      decodeServerMessage(JSON.stringify({ ...interactionsChanged, data: {} })),
    ).toEqual(invalidPayload(interactionsResource, 'interactions', 'changed'))
    expect(
      decodeServerMessage(
        JSON.stringify({ ...interactionsChanged, data: { rootThreadId: 'not-a-uuid' } }),
      ),
    ).toEqual(invalidPayload(interactionsResource, 'interactions', 'changed'))
    expect(
      decodeServerMessage(
        JSON.stringify({
          ...interactionsChanged,
          data: { rootThreadId: THREAD_ID, extra: true },
        }),
      ),
    ).toEqual(invalidPayload(interactionsResource, 'interactions', 'changed'))
    expect(
      decodeServerMessage(
        JSON.stringify({ ...interactionsChanged, cursor: '0' }),
      ),
    ).toBeNull()
    // resource/name 组合仍然严格：tree 只接受 changed。
    expect(
      decodeServerMessage(
        JSON.stringify({ ...treeChanged, name: 'version', data: { version: '1' }, cursor: '1' }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({ ...treeChanged, resource: { kind: 'thread-tree', id: THREAD_ID, extra: 1 } }),
      ),
    ).toBeNull()
  })

  it('rejects error frames without code or message', () => {
    expect(
      decodeServerMessage(JSON.stringify({  type: 'error', code: 'X' })),
    ).toBeNull()
    expect(
      decodeServerMessage(JSON.stringify({  type: 'error', message: 'y' })),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({  type: 'error', code: '', message: 'y' }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({  type: 'error', resource: threadResource, code: 'X', message: 7 }),
      ),
    ).toBeNull()
  })
})

describe('backend wire samples', () => {
  it('reports numeric Thread snapshot versions and next command sequences as scoped errors', () => {
    const data = threadSnapshot()
    // 匹配的数字 version 不能绕过校验（RegExp 会把数字强转为字符串）；
    // 但它是可定位资源的坏载荷：只让该 Thread 失效，不拖垮整条连接。
    for (const invalid of [
      { ...data, version: 43, thread: { ...data.thread, version: 43 } },
      { ...data, thread: { ...data.thread, version: 43 } },
      { ...data, thread: { ...data.thread, nextCommandSequence: 1 } },
    ]) {
      expect(decodeServerMessage(JSON.stringify({
        type: 'event', resource: threadResource, name: 'snapshot', data: invalid,
      }))).toEqual(invalidPayload(threadResource, 'thread', 'snapshot'))
    }
  })

  it('decodes the backend subscribed and snapshot frames', () => {
    const ack = `{"type":"subscribed","resource":{"kind":"thread","id":"${THREAD_ID}"},"cursor":"0"}`
    expect(decodeServerMessage(ack)).toEqual({
      type: 'subscribed', resource: { kind: 'thread', id: THREAD_ID }, cursor: '0',
    })
    const data = threadSnapshot()
    const event = JSON.stringify({
      type: 'event', resource: { kind: 'thread', id: THREAD_ID }, name: 'snapshot', data,
    })
    expect(decodeServerMessage(event)).toEqual({
      type: 'event', resource: { kind: 'thread', id: THREAD_ID }, name: 'snapshot', data,
    })
    expect(decodeServerMessage(JSON.stringify({
      type: 'event', resource: { kind: 'thread', id: THREAD_ID }, name: 'version',
      data: { version: '43' }, cursor: '43',
    }))).toBeNull()
  })

  it('decodes the backend thread realtime event frame (MODEL_DELTA object data)', () => {
    const raw =
      `{"type":"event","resource":{"kind":"thread","id":"${THREAD_ID}"},"name":"realtime","data":{"threadId":"${THREAD_ID}","subjectKind":"MODEL_INVOCATION","subjectId":"11111111-2222-4333-8444-555555555556","attempt":1,"sequence":2,"type":"MODEL_DELTA","payload":{"kind":"TEXT_DELTA","text":"hello"},"createdAt":"2026-01-01T00:00:00Z"}}`
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

  it('decodes the backend canvas revision event frame', () => {
    const raw =
      `{"type":"event","resource":{"kind":"canvas","id":"${CANVAS_ID}"},"name":"revision","data":{"revision":"7"},"cursor":"7"}`
    expect(decodeServerMessage(raw)).toEqual({
      type: 'event',
      resource: { kind: 'canvas', id: CANVAS_ID },
      name: 'revision',
      data: { revision: '7' },
      cursor: '7',
    })
  })

  it('decodes the backend resync frame', () => {
    const raw =
      `{"type":"resync","resource":{"kind":"canvas","id":"${CANVAS_ID}"}}`
    expect(decodeServerMessage(raw)).toEqual({
      type: 'resync',
      resource: { kind: 'canvas', id: CANVAS_ID },
    })
  })

  it('decodes the backend hint-resource frames', () => {
    expect(
      decodeServerMessage(
        `{"type":"subscribed","resource":{"kind":"thread-tree","id":"${THREAD_ID}"},"cursor":"0"}`,
      ),
    ).toEqual({ type: 'subscribed', resource: { kind: 'thread-tree', id: THREAD_ID }, cursor: '0' })
    expect(
      decodeServerMessage(
        `{"type":"event","resource":{"kind":"thread-tree","id":"${THREAD_ID}"},"name":"changed","data":{}}`,
      ),
    ).toEqual({
      type: 'event',
      resource: { kind: 'thread-tree', id: THREAD_ID },
      name: 'changed',
      data: {},
    })
    // 旧资源名不再被客户端接受。
    expect(decodeServerMessage(
      `{"type":"event","resource":{"kind":"tree","id":"${THREAD_ID}"},"name":"changed","data":{}}`,
    )).toBeNull()
    expect(
      decodeServerMessage(
        `{"type":"event","resource":{"kind":"interactions"},"name":"changed","data":{"rootThreadId":"${THREAD_ID}"}}`,
      ),
    ).toEqual({
      type: 'event',
      resource: { kind: 'interactions' },
      name: 'changed',
      data: { rootThreadId: THREAD_ID },
    })
    expect(
      decodeServerMessage(
        `{"type":"event","resource":{"kind":"environments"},"name":"changed","data":{}}`,
      ),
    ).toEqual({ type: 'event', resource: { kind: 'environments' }, name: 'changed', data: {} })
  })

  it('decodes the backend error frames (with and without resource)', () => {
    expect(
      decodeServerMessage(
        `{"type":"error","resource":{"kind":"thread","id":"${THREAD_ID}"},"code":"SUBSCRIBE_FAILED","message":"boom"}`,
      ),
    ).toEqual({
      type: 'error',
      resource: { kind: 'thread', id: THREAD_ID },
      code: 'SUBSCRIBE_FAILED',
      message: 'boom',
    })
    expect(
      decodeServerMessage(`{"type":"error","code":"INTERNAL","message":"boom"}`),
    ).toEqual({ type: 'error', code: 'INTERNAL', message: 'boom' })
  })
})
