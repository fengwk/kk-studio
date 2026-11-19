import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApplicationEventManager } from '@/shared/app-events/manager'
import { INVALID_EVENT_PAYLOAD } from '@/shared/app-events/protocol'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
import type { HarnessThreadSnapshotDTO } from '@/shared/api/generated/contracts'

const URL = 'ws://test/ws/events'
const THREAD_A = { kind: 'thread', id: 'aaaaaaaa-0000-4000-8000-000000000001' } as const
const THREAD_B = { kind: 'thread', id: 'bbbbbbbb-0000-4000-8000-000000000002' } as const
const CANVAS_A = { kind: 'canvas', id: 'cccccccc-0000-4000-8000-000000000003' } as const
const TREE_A = { kind: 'thread-tree', id: 'aaaaaaaa-0000-4000-8000-000000000001' } as const
const PROJECTS = { kind: 'projects' } as const
const EVENT_NODE_A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const EVENT_NODE_B = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab'
const CONNECTION_A = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const CONNECTION_B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbc'
const BROWSER_SESSION_A = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'
const BROWSER_SESSION_B = 'dddddddd-dddd-4ddd-8ddd-ddddddddddde'
const ENVIRONMENT_A = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
const VIEW_A = 'ffffffff-ffff-4fff-8fff-ffffffffffff'
const CLAIM_A = '99999999-9999-4999-8999-999999999999'
const CLAIM_B = '99999999-9999-4999-8999-999999999998'
const SNAPSHOT_HEAD = 'eeeeeeee-0000-4000-8000-000000000001'
const SNAPSHOT_SESSION = 'eeeeeeee-0000-4000-8000-000000000002'
const SNAPSHOT_NEXT_HEAD = 'eeeeeeee-0000-4000-8000-000000000003'

function threadSnapshot(threadId: string, version = '1'): HarnessThreadSnapshotDTO {
  return {
    version,
    thread: {
      threadId, name: 'Thread', sessionId: SNAPSHOT_SESSION, rootThreadId: threadId,
      parentThreadId: null, headEntryId: SNAPSHOT_HEAD, version, yoloEnabled: false,
      nextCommandSequence: '1', status: 'IDLE', executionControl: 'RUNNABLE',
      branchSettings: {
        agentName: 'agent', execution: { driver: 'builtin', settings: {} }, environmentName: null,
      },
      createdAt: 0, updatedAt: 0,
    },
    baseEntryId: null,
    entries: [], queuedCommands: [], activeAssistantOutput: null,
    generationFailures: [], toolInvocations: [], stopReceipts: [], unconfirmedExecutions: [],
  }
}

function connectLatest(socket: ReturnType<FakeWebSocketHarness['openLatest']>, node = EVENT_NODE_A, connection = CONNECTION_A) {
  socket.emitServer({
    type: 'connected',
    eventNodeId: node,
    connectionId: connection,
    heartbeatIntervalMillis: '20000',
  })
}

function usage(revision = '1', browserSessionId = BROWSER_SESSION_A, claimId = CLAIM_A) {
  return {
    browserSessionId,
    revision,
    views: [{ environmentId: ENVIRONMENT_A, viewId: VIEW_A, claimId }],
  }
}

function setup() {
  const harness = new FakeWebSocketHarness()
  const manager = new ApplicationEventManager({ url: URL, socketFactory: harness.factory })
  manager.connect()
  return { manager, harness }
}

describe('ApplicationEventManager', () => {
  beforeEach(() => {
    vi.spyOn(Math, 'random').mockReturnValue(0)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('refcounts listeners: one wire subscription per resource, unsubscribed on last release', () => {
    const { manager, harness } = setup()
    harness.openLatest()
    const socket = harness.latest as NonNullable<typeof harness.latest>

    const releaseA1 = manager.subscribe(THREAD_A, {})
    const releaseA2 = manager.subscribe(THREAD_A, {})
    const releaseB = manager.subscribe(THREAD_B, {})

    expect(socket.sentMessages()).toEqual([
      {  type: 'subscribe', resource: THREAD_A },
      {  type: 'subscribe', resource: THREAD_B },
    ])

    // 同资源第二消费者不产生新 wire 消息；释放一个仍保持订阅。
    releaseA2()
    expect(socket.sentMessages()).toHaveLength(2)
    releaseA1()
    expect(socket.sentMessages()).toEqual([
      {  type: 'subscribe', resource: THREAD_A },
      {  type: 'subscribe', resource: THREAD_B },
      {  type: 'unsubscribe', resource: THREAD_A },
    ])
    // 幂等释放。
    releaseA1()
    expect(socket.sentMessages()).toHaveLength(3)

    releaseB()
    expect(socket.sentMessages().at(-1)).toEqual({
      
      type: 'unsubscribe',
      resource: THREAD_B,
    })
  })

  it('refreshes a tree for a new listener without changing wire refcounts', () => {
    const { manager, harness } = setup()
    const socket = harness.openLatest()
    const releaseFirst = manager.subscribe(TREE_A, {})
    const releaseSecond = manager.subscribe(TREE_A, {})

    expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: TREE_A },
      { type: 'refresh', resource: TREE_A },
    ])
    expect(manager.requestResourceRefresh(TREE_A)).toBe(true)
    expect(socket.sentMessages().at(-1)).toEqual({ type: 'refresh', resource: TREE_A })

    releaseFirst()
    expect(socket.sentMessages()).toHaveLength(3)
    releaseSecond()
    expect(socket.sentMessages().at(-1)).toEqual({ type: 'unsubscribe', resource: TREE_A })
    expect(manager.requestResourceRefresh(TREE_A)).toBe(false)
  })

  it('refcounts the same listener on one resource: first release keeps the wire subscription', () => {
    const { manager, harness } = setup()
    const socket = harness.openLatest()

    const listener = { onEvent: vi.fn() }
    const release1 = manager.subscribe(THREAD_A, listener)
    const release2 = manager.subscribe(THREAD_A, listener)
    expect(socket.sentMessages()).toEqual([{  type: 'subscribe', resource: THREAD_A }])

    // 第一个 unsubscribe 不能错误拆 wire：同一 listener 仍有真实 refcount。
    release1()
    expect(socket.sentMessages()).toHaveLength(1)
    socket.emitServer({ type: 'event', resource: THREAD_A, name: 'snapshot', data: threadSnapshot(THREAD_A.id) })
    expect(listener.onEvent).toHaveBeenCalledTimes(1)

    // 末 ref 才 unsubscribe，之后不再派发。
    release2()
    expect(socket.sentMessages().at(-1)).toEqual({
      
      type: 'unsubscribe',
      resource: THREAD_A,
    })
    socket.emitServer({ type: 'resync', resource: THREAD_A })
    expect(listener.onEvent).toHaveBeenCalledTimes(1)
  })

  it('suspends a shared Thread wire lease and resumes from the newest cache head', () => {
    const { manager, harness } = setup()
    const socket = harness.openLatest()
    let head = SNAPSHOT_HEAD
    const onEvent = vi.fn()
    const releaseSnapshot = manager.subscribe(THREAD_A, {
      getAfterEntryId: () => head,
      onEvent,
    })
    const releaseRealtime = manager.subscribe(THREAD_A, { onEvent })
    expect(socket.sentMessages()).toEqual([{
      type: 'subscribe', resource: THREAD_A, afterEntryId: SNAPSHOT_HEAD,
    }])

    expect(manager.suspendThread(THREAD_A)).toBe(true)
    expect(manager.requestThreadRefresh(THREAD_A)).toBe(false)
    head = SNAPSHOT_NEXT_HEAD
    socket.emitServer({
      type: 'event', resource: THREAD_A, name: 'realtime', data: { type: 'MODEL_DELTA' },
    })
    expect(onEvent).not.toHaveBeenCalled()
    expect(manager.resumeThread(THREAD_A)).toBe(true)
    expect(socket.sentMessages().slice(-2)).toEqual([
      { type: 'unsubscribe', resource: THREAD_A },
      { type: 'subscribe', resource: THREAD_A, afterEntryId: SNAPSHOT_NEXT_HEAD },
    ])

    releaseSnapshot()
    releaseRealtime()
  })

  it('registers the listener before sending the first subscribe', () => {
    const { manager, harness } = setup()
    const socket = harness.openLatest()

    // send() 同步派发 subscribed ack：若 listener 未先登记，ack 会因无监听者而丢失。
    socket.onSendResponse = { type: 'subscribed', resource: THREAD_A, cursor: '0' }
    const onSubscribed = vi.fn()
    manager.subscribe(THREAD_A, { onSubscribed })
    expect(onSubscribed).toHaveBeenCalledWith('0')
  })

  it('holds subscribes until open and re-subscribes every active resource on reconnect', () => {
    vi.useFakeTimers()
    const { manager, harness } = setup()
    manager.subscribe(THREAD_A, {})
    manager.subscribe(CANVAS_A, {})
    // 未 open：wire 上没有任何消息。
    expect(harness.latest?.sentMessages() ?? []).toHaveLength(0)

    // 首次 open：重发全部 active subscriptions。
    harness.openLatest()
    expect(harness.latest?.sentMessages()).toEqual([
      {  type: 'subscribe', resource: THREAD_A },
      {  type: 'subscribe', resource: CANVAS_A },
    ])

    // 断线重连：新 socket open 后再次重发。
    harness.latest?.fail()
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(2)
    harness.openLatest()
    expect(harness.latest?.sentMessages()).toEqual([
      {  type: 'subscribe', resource: THREAD_A },
      {  type: 'subscribe', resource: CANVAS_A },
    ])
  })

  it('after a liveness fence the manager resubscribes and the listener refetches the snapshot', () => {
    vi.useFakeTimers()
    const { manager, harness } = setup()
    const socket = harness.openLatest()
    connectLatest(socket, EVENT_NODE_A, CONNECTION_A)

    // 订阅建立（subscribed ack）即回读权威快照：sync 是 useResourceSync 对账的入口。
    const sync = vi.fn()
    const onSubscribed = vi.fn<(cursor: string) => void>()
    socket.onSendResponse = { type: 'subscribed', resource: THREAD_A, cursor: '0' }
    manager.subscribe(THREAD_A, {
      onSubscribed: (cursor) => {
        onSubscribed(cursor)
        sync()
      },
    })
    expect(onSubscribed).toHaveBeenCalledWith('0')
    expect(sync).toHaveBeenCalledTimes(1)

    // 失联 fence：最后一条合法帧之后连续 3 个 heartbeat 周期（3 × 20s）无帧，旧连接被 fence；
    // 首次退避 250ms（jitter 已固定为 0）后建立新连接。
    vi.advanceTimersByTime(60_000)
    expect(manager.getConnectionState().reason).toMatchObject({ cause: 'liveness-timeout' })
    expect(harness.sockets).toHaveLength(1)
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(2)

    // 新连接：Manager 立即重发订阅；重订阅 ack 携带新游标并再次回读快照，恢复数据而不是重放副作用。
    const reconnected = harness.openLatest()
    connectLatest(reconnected, EVENT_NODE_B, CONNECTION_B)
    expect(reconnected.sentMessages()).toEqual([
      { type: 'subscribe', resource: THREAD_A },
    ])
    reconnected.emitServer({ type: 'subscribed', resource: THREAD_A, cursor: '0' })
    expect(onSubscribed).toHaveBeenNthCalledWith(2, '0')
    expect(sync).toHaveBeenCalledTimes(2)
  })

  it('publishes a fresh connection identity after every reconnect and clears it on close', () => {
    vi.useFakeTimers()
    const { manager, harness } = setup()
    const socket = harness.openLatest()
    expect(manager.getIdentity()).toBeNull()
    const identities: unknown[] = []
    const unsubscribe = manager.subscribeIdentity((identity) => identities.push(identity))

    connectLatest(socket)
    expect(manager.getIdentity()).toEqual({
      eventNodeId: EVENT_NODE_A,
      connectionId: CONNECTION_A,
      heartbeatIntervalMillis: '20000',
    })

    socket.fail()
    expect(manager.getIdentity()).toBeNull()
    vi.advanceTimersByTime(250)
    const reconnected = harness.openLatest()
    expect(manager.getIdentity()).toBeNull()
    connectLatest(reconnected, EVENT_NODE_B, CONNECTION_B)
    expect(manager.getIdentity()).toEqual({
      eventNodeId: EVENT_NODE_B,
      connectionId: CONNECTION_B,
      heartbeatIntervalMillis: '20000',
    })
    expect(identities).toEqual([
      null,
      { eventNodeId: EVENT_NODE_A, connectionId: CONNECTION_A, heartbeatIntervalMillis: '20000' },
      null,
      { eventNodeId: EVENT_NODE_B, connectionId: CONNECTION_B, heartbeatIntervalMillis: '20000' },
    ])
    unsubscribe()
  })

  it('registerUsage waits for the ack, resends an identical pending revision and rejects conflicts', async () => {
    const { manager, harness } = setup()
    const socket = harness.openLatest()
    connectLatest(socket)

    const first = manager.registerUsage(usage())
    const duplicate = manager.registerUsage(usage())
    expect(duplicate).toBe(first)
    expect(socket.sentMessages()).toEqual([
      {
        
        type: 'ide-use',
        browserSessionId: BROWSER_SESSION_A,
        revision: '1',
        views: [{ environmentId: ENVIRONMENT_A, viewId: VIEW_A, claimId: CLAIM_A }],
      },
      {
        
        type: 'ide-use',
        browserSessionId: BROWSER_SESSION_A,
        revision: '1',
        views: [{ environmentId: ENVIRONMENT_A, viewId: VIEW_A, claimId: CLAIM_A }],
      },
    ])
    await expect(manager.registerUsage(usage('1', BROWSER_SESSION_A, CLAIM_B))).rejects.toThrow(
      'already pending with different content',
    )

    socket.emitServer({ type: 'ide-use-accepted', revision: '1' })
    await expect(first).resolves.toEqual({ revision: '1' })

    // 服务端已接受后，同一物理连接仍只能绑定一个浏览器会话。
    await expect(manager.registerUsage(usage('2', BROWSER_SESSION_B))).rejects.toThrow(
      'browserSessionId is fixed for this connection',
    )
  })

  it('rejects a pending usage on connection loss and never replays it after reconnect', async () => {
    vi.useFakeTimers()
    const { manager, harness } = setup()
    const socket = harness.openLatest()
    connectLatest(socket)
    const pending = manager.registerUsage(usage())
    const rejected = expect(pending).rejects.toThrow('connection closed')

    socket.fail()
    await rejected
    vi.advanceTimersByTime(250)
    const reconnected = harness.openLatest()
    connectLatest(reconnected, EVENT_NODE_B, CONNECTION_B)
    expect(reconnected.sentMessages()).toEqual([])
  })

  it('rejects pending usage on a connection-level server error', async () => {
    const { manager, harness } = setup()
    const socket = harness.openLatest()
    connectLatest(socket)
    const pending = manager.registerUsage(usage())
    socket.emitServer({ type: 'error', code: 'IDE_USE_REJECTED', message: 'conflict' })
    await expect(pending).rejects.toThrow('IDE_USE_REJECTED: conflict')
  })

  it('dispatches subscribed/event/resync/error to the resource listeners and exposes cursors', () => {
    const { manager, harness } = setup()
    harness.openLatest()
    const socket = harness.latest as NonNullable<typeof harness.latest>

    const onSubscribed = vi.fn<(cursor: string) => void>()
    const onEvent = vi.fn<(name: string, data: unknown, cursor: string | undefined) => void>()
    const onResync = vi.fn()
    const onError = vi.fn<(code: string, message: string) => void>()
    const otherOnEvent = vi.fn()
    manager.subscribe(THREAD_A, { onSubscribed, onEvent, onResync, onError })
    manager.subscribe(CANVAS_A, { onEvent: otherOnEvent })

    socket.emitServer({ type: 'subscribed', resource: THREAD_A, cursor: '0' })
    expect(onSubscribed).toHaveBeenCalledWith('0')
    expect(otherOnEvent).not.toHaveBeenCalled()

    socket.emitServer({ type: 'event', resource: THREAD_A, name: 'snapshot', data: threadSnapshot(THREAD_A.id, '6') })
    socket.emitServer({ type: 'event', resource: THREAD_A, name: 'realtime', data: { type: 'MODEL_DELTA' } })
    expect(onEvent.mock.calls).toEqual([
      ['snapshot', threadSnapshot(THREAD_A.id, '6'), undefined],
      ['realtime', { type: 'MODEL_DELTA' }, undefined],
    ])

    socket.emitServer({ type: 'resync', resource: THREAD_A })
    expect(onResync).toHaveBeenCalledTimes(1)

    socket.emitServer({ type: 'error', resource: THREAD_A, code: 'SUBSCRIBE_FAILED', message: 'boom' })
    expect(onError).toHaveBeenCalledWith('SUBSCRIBE_FAILED', 'boom')

    // 其他资源的事件不派发给 threadA。
    socket.emitServer({ type: 'event', resource: CANVAS_A, name: 'revision', data: { revision: '2' }, cursor: '2' })
    expect(onEvent).toHaveBeenCalledTimes(2)
    expect(otherOnEvent).toHaveBeenCalledWith('revision', { revision: '2' }, '2')
    // 未订阅资源与无资源 error 一律丢弃。
    socket.emitServer({ type: 'subscribed', resource: THREAD_B, cursor: '1' })
    socket.emitServer({ type: 'error', code: 'INTERNAL', message: 'orphan' })
    expect(onSubscribed).toHaveBeenCalledTimes(1)
    expect(onError).toHaveBeenCalledTimes(1)
  })

  it('subscribes and dispatches the global project resource without a synthetic id', () => {
    const { manager, harness } = setup()
    const socket = harness.openLatest()
    const projectChanged = vi.fn()
    manager.subscribe(PROJECTS, { onEvent: projectChanged })

    expect(socket.sentMessages()).toEqual([
      {  type: 'subscribe', resource: PROJECTS },
    ])

    socket.emitServer({
      type: 'event',
      resource: PROJECTS,
      name: 'changed',
      data: { projectId: THREAD_A.id },
    })
    expect(projectChanged).toHaveBeenCalledWith(
      'changed',
      { projectId: THREAD_A.id },
      undefined,
    )
  })

  it('isolates listener callback exceptions so one consumer cannot block the others', () => {
    const { manager, harness } = setup()
    harness.openLatest()
    const socket = harness.latest as NonNullable<typeof harness.latest>
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})

    const failing = {
      onSubscribed: vi.fn(() => {
        throw new Error('subscribed boom')
      }),
      onEvent: vi.fn(() => {
        throw new Error('event boom')
      }),
      onResync: vi.fn(() => {
        throw new Error('resync boom')
      }),
      onError: vi.fn(() => {
        throw new Error('error boom')
      }),
    }
    const healthy = {
      onSubscribed: vi.fn(),
      onEvent: vi.fn(),
      onResync: vi.fn(),
      onError: vi.fn(),
    }
    manager.subscribe(THREAD_A, failing)
    manager.subscribe(THREAD_A, healthy)

    // 四类派发共用同一隔离路径：任一 listener 抛错后同资源其他 listener 仍能收到。
    socket.emitServer({ type: 'subscribed', resource: THREAD_A, cursor: '0' })
    socket.emitServer({
      type: 'event',
      resource: THREAD_A,
      name: 'snapshot',
      data: threadSnapshot(THREAD_A.id, '2'),
    })
    socket.emitServer({ type: 'resync', resource: THREAD_A })
    socket.emitServer({ type: 'error', resource: THREAD_A, code: 'SUBSCRIBE_FAILED', message: 'boom' })

    expect(healthy.onSubscribed).toHaveBeenCalledWith('0')
    expect(healthy.onEvent).toHaveBeenCalledWith('snapshot', threadSnapshot(THREAD_A.id, '2'), undefined)
    expect(healthy.onResync).toHaveBeenCalledTimes(1)
    expect(healthy.onError).toHaveBeenCalledWith('SUBSCRIBE_FAILED', 'boom')
    expect(failing.onSubscribed).toHaveBeenCalledTimes(1)
    expect(failing.onEvent).toHaveBeenCalledTimes(1)
    expect(failing.onResync).toHaveBeenCalledTimes(1)
    expect(failing.onError).toHaveBeenCalledTimes(1)
    expect(consoleError).toHaveBeenCalledTimes(4)
  })

  it('forwards a full snapshot-ref message and the scoped invalid-payload error', () => {
    const { manager, harness } = setup()
    const socket = harness.openLatest()
    const onSnapshotRef = vi.fn()
    const onEvent = vi.fn()
    const onError = vi.fn<(code: string, message: string) => void>()
    manager.subscribe(THREAD_A, { onSnapshotRef, onEvent, onError })
    manager.subscribe(TREE_A, { onSnapshotRef })

    // Thread 的 version 是帧顶层字段，完整消息原样转发。
    socket.emitServer({
      type: 'snapshot-ref',
      resource: THREAD_A,
      version: '9223372036854775807',
    })
    expect(onSnapshotRef).toHaveBeenLastCalledWith({
      type: 'snapshot-ref',
      resource: THREAD_A,
      version: '9223372036854775807',
    })
    // 执行树没有 version，可用 'version' in message 判别。
    socket.emitServer({ type: 'snapshot-ref', resource: TREE_A })
    expect(onSnapshotRef).toHaveBeenLastCalledWith({ type: 'snapshot-ref', resource: TREE_A })
    const [treeMessage] = onSnapshotRef.mock.calls[1] ?? []
    expect(treeMessage != null && 'version' in treeMessage).toBe(false)
    // snapshot-ref 不是资源事件，也不当成错误。
    expect(onEvent).not.toHaveBeenCalled()
    expect(onError).not.toHaveBeenCalled()

    // 解码器合成的资源级坏载荷经既有 onError 分发，不带原始载荷。
    socket.onmessage?.({
      data: JSON.stringify({
        type: 'event', resource: THREAD_A, name: 'snapshot', data: { version: 987654321 },
      }),
    })
    expect(onError).toHaveBeenCalledWith(
      INVALID_EVENT_PAYLOAD,
      'received an invalid thread snapshot payload for this resource',
    )
    expect(onEvent).not.toHaveBeenCalled()
    // 订阅仍有效：后续合法快照照常到达同一 listener。
    socket.emitServer({ type: 'event', resource: THREAD_A, name: 'snapshot', data: threadSnapshot(THREAD_A.id) })
    expect(onEvent).toHaveBeenCalledTimes(1)
  })

  it('does not deliver resource hints to a suspended subscription', () => {
    const { manager, harness } = setup()
    const socket = harness.openLatest()
    const onSnapshotRef = vi.fn()
    const onError = vi.fn()
    manager.subscribe(THREAD_A, { onSnapshotRef, onError })
    expect(manager.suspendThread(THREAD_A)).toBe(true)

    socket.emitServer({ type: 'snapshot-ref', resource: THREAD_A, version: '1' })
    socket.onmessage?.({
      data: JSON.stringify({
        type: 'event', resource: THREAD_A, name: 'snapshot', data: { version: 987654321 },
      }),
    })
    expect(onSnapshotRef).not.toHaveBeenCalled()
    expect(onError).not.toHaveBeenCalled()
  })

  it('cleans up: released listeners no longer receive dispatch and the entry is removed', () => {
    const { manager, harness } = setup()
    harness.openLatest()
    const socket = harness.latest as NonNullable<typeof harness.latest>

    const onEvent = vi.fn()
    const onEvent2 = vi.fn()
    const release = manager.subscribe(THREAD_A, { onEvent })
    const release2 = manager.subscribe(THREAD_A, { onEvent: onEvent2 })

    release()
    socket.emitServer({ type: 'event', resource: THREAD_A, name: 'snapshot', data: threadSnapshot(THREAD_A.id) })
    expect(onEvent).not.toHaveBeenCalled()
    expect(onEvent2).toHaveBeenCalledTimes(1)

    release2()
    socket.emitServer({ type: 'resync', resource: THREAD_A })
    expect(onEvent2).toHaveBeenCalledTimes(1)
    // 最后一个 listener 释放后 wire 上出现 unsubscribe。
    expect(socket.sentMessages().at(-1)).toEqual({
      
      type: 'unsubscribe',
      resource: THREAD_A,
    })
  })
})
