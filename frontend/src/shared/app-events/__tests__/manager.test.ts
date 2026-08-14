import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApplicationEventManager } from '@/shared/app-events/manager'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'

const URL = 'ws://test/api/events/v1'
const threadA = { kind: 'thread', id: 't-a' } as const
const threadB = { kind: 'thread', id: 't-b' } as const
const canvasA = { kind: 'canvas', id: 'c-a' } as const

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

    const releaseA1 = manager.subscribe(threadA, {})
    const releaseA2 = manager.subscribe(threadA, {})
    const releaseB = manager.subscribe(threadB, {})

    expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: threadA },
      { type: 'subscribe', resource: threadB },
    ])

    // 同资源第二消费者不产生新 wire 消息；释放一个仍保持订阅。
    releaseA2()
    expect(socket.sentMessages()).toHaveLength(2)
    releaseA1()
    expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: threadA },
      { type: 'subscribe', resource: threadB },
      { type: 'unsubscribe', resource: threadA },
    ])
    // 幂等释放。
    releaseA1()
    expect(socket.sentMessages()).toHaveLength(3)

    releaseB()
    expect(socket.sentMessages().at(-1)).toEqual({ type: 'unsubscribe', resource: threadB })
  })

  it('holds subscribes until open and re-subscribes every active resource on reconnect', () => {
    vi.useFakeTimers()
    const { manager, harness } = setup()
    manager.subscribe(threadA, {})
    manager.subscribe(canvasA, {})
    // 未 open：wire 上没有任何消息。
    expect(harness.latest?.sentMessages() ?? []).toHaveLength(0)

    // 首次 open：重发全部 active subscriptions。
    harness.openLatest()
    expect(harness.latest?.sentMessages()).toEqual([
      { type: 'subscribe', resource: threadA },
      { type: 'subscribe', resource: canvasA },
    ])

    // 断线重连：新 socket open 后再次重发。
    harness.latest?.fail()
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(2)
    harness.openLatest()
    expect(harness.latest?.sentMessages()).toEqual([
      { type: 'subscribe', resource: threadA },
      { type: 'subscribe', resource: canvasA },
    ])
  })

  it('dispatches subscribed/event/resync/error to the resource listeners only', () => {
    const { manager, harness } = setup()
    harness.openLatest()
    const socket = harness.latest as NonNullable<typeof harness.latest>

    const onSubscribed = vi.fn()
    const onEvent = vi.fn<(name: string, data: unknown) => void>()
    const onResync = vi.fn()
    const onError = vi.fn<(message: string | undefined) => void>()
    const otherOnEvent = vi.fn()
    manager.subscribe(threadA, { onSubscribed, onEvent, onResync, onError })
    manager.subscribe(canvasA, { onEvent: otherOnEvent })

    socket.emitServer({ type: 'subscribed', resource: threadA })
    expect(onSubscribed).toHaveBeenCalledTimes(1)
    expect(otherOnEvent).not.toHaveBeenCalled()

    socket.emitServer({ type: 'event', resource: threadA, name: 'revision' })
    socket.emitServer({ type: 'event', resource: threadA, name: 'realtime', data: { raw: true } })
    expect(onEvent.mock.calls).toEqual([
      ['revision', undefined],
      ['realtime', { raw: true }],
    ])

    socket.emitServer({ type: 'resync', resource: threadA })
    expect(onResync).toHaveBeenCalledTimes(1)

    socket.emitServer({ type: 'error', resource: threadA, message: 'boom' })
    expect(onError).toHaveBeenCalledWith('boom')

    // 其他资源的事件不派发给 threadA。
    socket.emitServer({ type: 'event', resource: canvasA, name: 'version', data: { version: '2' } })
    expect(onEvent).toHaveBeenCalledTimes(2)
    expect(otherOnEvent).toHaveBeenCalledWith('version', { version: '2' })
    // 未订阅资源与无资源 error 一律丢弃。
    socket.emitServer({ type: 'subscribed', resource: threadB })
    socket.emitServer({ type: 'error', message: 'orphan' })
    expect(onSubscribed).toHaveBeenCalledTimes(1)
    expect(onError).toHaveBeenCalledTimes(1)
  })

  it('cleans up: released listeners no longer receive dispatch and the entry is removed', () => {
    const { manager, harness } = setup()
    harness.openLatest()
    const socket = harness.latest as NonNullable<typeof harness.latest>

    const onEvent = vi.fn()
    const onEvent2 = vi.fn()
    const release = manager.subscribe(threadA, { onEvent })
    const release2 = manager.subscribe(threadA, { onEvent: onEvent2 })

    release()
    socket.emitServer({ type: 'event', resource: threadA, name: 'revision' })
    expect(onEvent).not.toHaveBeenCalled()
    expect(onEvent2).toHaveBeenCalledTimes(1)

    release2()
    socket.emitServer({ type: 'resync', resource: threadA })
    expect(onEvent2).toHaveBeenCalledTimes(1)
    // 最后一个 listener 释放后 wire 上出现 unsubscribe。
    expect(socket.sentMessages().at(-1)).toEqual({ type: 'unsubscribe', resource: threadA })
  })
})
