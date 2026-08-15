import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApplicationEventManager } from '@/shared/app-events/manager'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'

const URL = 'ws://test/api/events/v1'
const THREAD_A = { kind: 'thread', id: 'aaaaaaaa-0000-4000-8000-000000000001' } as const
const THREAD_B = { kind: 'thread', id: 'bbbbbbbb-0000-4000-8000-000000000002' } as const
const CANVAS_A = { kind: 'canvas', id: 'cccccccc-0000-4000-8000-000000000003' } as const

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
      { version: 1, type: 'subscribe', resource: THREAD_A },
      { version: 1, type: 'subscribe', resource: THREAD_B },
    ])

    // 同资源第二消费者不产生新 wire 消息；释放一个仍保持订阅。
    releaseA2()
    expect(socket.sentMessages()).toHaveLength(2)
    releaseA1()
    expect(socket.sentMessages()).toEqual([
      { version: 1, type: 'subscribe', resource: THREAD_A },
      { version: 1, type: 'subscribe', resource: THREAD_B },
      { version: 1, type: 'unsubscribe', resource: THREAD_A },
    ])
    // 幂等释放。
    releaseA1()
    expect(socket.sentMessages()).toHaveLength(3)

    releaseB()
    expect(socket.sentMessages().at(-1)).toEqual({
      version: 1,
      type: 'unsubscribe',
      resource: THREAD_B,
    })
  })

  it('refcounts the same listener on one resource: first release keeps the wire subscription', () => {
    const { manager, harness } = setup()
    const socket = harness.openLatest()

    const listener = { onEvent: vi.fn() }
    const release1 = manager.subscribe(THREAD_A, listener)
    const release2 = manager.subscribe(THREAD_A, listener)
    expect(socket.sentMessages()).toEqual([{ version: 1, type: 'subscribe', resource: THREAD_A }])

    // 第一个 unsubscribe 不能错误拆 wire：同一 listener 仍有真实 refcount。
    release1()
    expect(socket.sentMessages()).toHaveLength(1)
    socket.emitServer({ type: 'event', resource: THREAD_A, name: 'revision', data: { revision: '1' }, cursor: '1' })
    expect(listener.onEvent).toHaveBeenCalledTimes(1)

    // 末 ref 才 unsubscribe，之后不再派发。
    release2()
    expect(socket.sentMessages().at(-1)).toEqual({
      version: 1,
      type: 'unsubscribe',
      resource: THREAD_A,
    })
    socket.emitServer({ type: 'resync', resource: THREAD_A })
    expect(listener.onEvent).toHaveBeenCalledTimes(1)
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
      { version: 1, type: 'subscribe', resource: THREAD_A },
      { version: 1, type: 'subscribe', resource: CANVAS_A },
    ])

    // 断线重连：新 socket open 后再次重发。
    harness.latest?.fail()
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(2)
    harness.openLatest()
    expect(harness.latest?.sentMessages()).toEqual([
      { version: 1, type: 'subscribe', resource: THREAD_A },
      { version: 1, type: 'subscribe', resource: CANVAS_A },
    ])
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

    socket.emitServer({ type: 'subscribed', resource: THREAD_A, cursor: '5' })
    expect(onSubscribed).toHaveBeenCalledWith('5')
    expect(otherOnEvent).not.toHaveBeenCalled()

    socket.emitServer({ type: 'event', resource: THREAD_A, name: 'revision', data: { revision: '6' }, cursor: '6' })
    socket.emitServer({ type: 'event', resource: THREAD_A, name: 'realtime', data: { type: 'MODEL_DELTA' } })
    expect(onEvent.mock.calls).toEqual([
      ['revision', { revision: '6' }, '6'],
      ['realtime', { type: 'MODEL_DELTA' }, undefined],
    ])

    socket.emitServer({ type: 'resync', resource: THREAD_A })
    expect(onResync).toHaveBeenCalledTimes(1)

    socket.emitServer({ type: 'error', resource: THREAD_A, code: 'SUBSCRIBE_FAILED', message: 'boom' })
    expect(onError).toHaveBeenCalledWith('SUBSCRIBE_FAILED', 'boom')

    // 其他资源的事件不派发给 threadA。
    socket.emitServer({ type: 'event', resource: CANVAS_A, name: 'version', data: { version: '2' }, cursor: '2' })
    expect(onEvent).toHaveBeenCalledTimes(2)
    expect(otherOnEvent).toHaveBeenCalledWith('version', { version: '2' }, '2')
    // 未订阅资源与无资源 error 一律丢弃。
    socket.emitServer({ type: 'subscribed', resource: THREAD_B, cursor: '1' })
    socket.emitServer({ type: 'error', code: 'INTERNAL', message: 'orphan' })
    expect(onSubscribed).toHaveBeenCalledTimes(1)
    expect(onError).toHaveBeenCalledTimes(1)
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
    socket.emitServer({ type: 'subscribed', resource: THREAD_A, cursor: '1' })
    socket.emitServer({
      type: 'event',
      resource: THREAD_A,
      name: 'revision',
      data: { revision: '2' },
      cursor: '2',
    })
    socket.emitServer({ type: 'resync', resource: THREAD_A })
    socket.emitServer({ type: 'error', resource: THREAD_A, code: 'SUBSCRIBE_FAILED', message: 'boom' })

    expect(healthy.onSubscribed).toHaveBeenCalledWith('1')
    expect(healthy.onEvent).toHaveBeenCalledWith('revision', { revision: '2' }, '2')
    expect(healthy.onResync).toHaveBeenCalledTimes(1)
    expect(healthy.onError).toHaveBeenCalledWith('SUBSCRIBE_FAILED', 'boom')
    expect(failing.onSubscribed).toHaveBeenCalledTimes(1)
    expect(failing.onEvent).toHaveBeenCalledTimes(1)
    expect(failing.onResync).toHaveBeenCalledTimes(1)
    expect(failing.onError).toHaveBeenCalledTimes(1)
    expect(consoleError).toHaveBeenCalledTimes(4)
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
    socket.emitServer({ type: 'event', resource: THREAD_A, name: 'revision', data: { revision: '1' }, cursor: '1' })
    expect(onEvent).not.toHaveBeenCalled()
    expect(onEvent2).toHaveBeenCalledTimes(1)

    release2()
    socket.emitServer({ type: 'resync', resource: THREAD_A })
    expect(onEvent2).toHaveBeenCalledTimes(1)
    // 最后一个 listener 释放后 wire 上出现 unsubscribe。
    expect(socket.sentMessages().at(-1)).toEqual({
      version: 1,
      type: 'unsubscribe',
      resource: THREAD_A,
    })
  })
})
