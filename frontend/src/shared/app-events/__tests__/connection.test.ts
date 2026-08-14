import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApplicationEventConnection,
  createApplicationEventUrl,
} from '@/shared/app-events/connection'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import type { ApplicationEventServerMessage } from '@/shared/app-events/protocol'

const URL = 'ws://test/api/events/v1'

function openConnection(harness = new FakeWebSocketHarness()) {
  const onOpen = vi.fn()
  const onMessage = vi.fn<(message: ApplicationEventServerMessage) => void>()
  const connection = new ApplicationEventConnection({
    url: URL,
    socketFactory: harness.factory,
    onOpen,
    onMessage,
  })
  return { connection, harness, onOpen, onMessage }
}

describe('ApplicationEventConnection', () => {
  beforeEach(() => {
    vi.spyOn(Math, 'random').mockReturnValue(0)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('connects to the configured url and transitions connecting -> open', () => {
    const { connection, harness, onOpen } = openConnection()
    expect(connection.getStatus()).toBe('closed')

    connection.connect()
    expect(connection.getStatus()).toBe('connecting')
    expect(harness.sockets).toHaveLength(1)
    expect(harness.sockets[0]?.url).toBe(URL)

    harness.openLatest()
    expect(connection.getStatus()).toBe('open')
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('decodes server frames strictly before delivery and drops malformed frames', () => {
    const { connection, harness, onMessage } = openConnection()
    connection.connect()
    const socket = harness.openLatest()

    socket.emitServer({ type: 'subscribed', resource: { kind: 'thread', id: 't-1' } })
    expect(onMessage).toHaveBeenCalledWith({
      type: 'subscribed',
      resource: { kind: 'thread', id: 't-1' },
    })

    for (const raw of [
      'not json',
      JSON.stringify({ type: 'unknown' }),
      JSON.stringify({ type: 'subscribed' }),
      JSON.stringify({ type: 'event', resource: { kind: 'thread', id: 't' }, name: 'bogus' }),
      JSON.stringify({ type: 'event', resource: { kind: 'nope', id: 't' }, name: 'revision' }),
    ]) {
      socket.onmessage?.({ data: raw })
    }
    expect(onMessage).toHaveBeenCalledTimes(1)
  })

  it('sends encoded client messages only while open', () => {
    const { connection, harness } = openConnection()
    connection.connect()
    expect(connection.send({ type: 'subscribe', resource: { kind: 'thread', id: 't-1' } })).toBe(false)

    const socket = harness.openLatest()
    expect(connection.send({ type: 'unsubscribe', resource: { kind: 'canvas', id: 'c-1' } })).toBe(true)
    expect(socket.sentMessages()).toEqual([
      { type: 'unsubscribe', resource: { kind: 'canvas', id: 'c-1' } },
    ])
  })

  it('reconnects with backoff 250/500/1000/2000/5000 capped at 5000ms plus jitter', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    harness.openLatest()

    const expectedDelays = [250, 500, 1000, 2000, 5000, 5000, 5000]
    for (let attempt = 0; attempt < expectedDelays.length; attempt++) {
      const expected = expectedDelays[attempt] as number
      harness.latest?.fail()
      // 退避时刻之前绝不重连。
      vi.advanceTimersByTime(expected - 1)
      expect(harness.sockets).toHaveLength(attempt + 1)
      // 到达退避时刻（jitter=0）后立即创建下一个 socket。
      vi.advanceTimersByTime(1)
      expect(harness.sockets).toHaveLength(attempt + 2)
    }
  })

  it('applies small jitter on top of the backoff', () => {
    vi.useFakeTimers()
    vi.mocked(Math.random).mockReturnValue(0.5)
    const { connection, harness } = openConnection()
    connection.connect()
    harness.openLatest()

    harness.latest?.fail()
    vi.advanceTimersByTime(249)
    expect(harness.sockets).toHaveLength(1)
    // 0.5 * 250 = 125ms jitter：总延迟 375ms。
    vi.advanceTimersByTime(126)
    expect(harness.sockets).toHaveLength(2)
  })

  it('retries immediately on online and on visibility becoming visible', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    harness.openLatest()

    // 断线后退避定时器 pending：online 事件立即重试（清掉定时器）。
    harness.latest?.fail()
    expect(vi.getTimerCount()).toBe(1)
    window.dispatchEvent(new Event('online'))
    expect(harness.sockets).toHaveLength(2)
    expect(vi.getTimerCount()).toBe(0)

    // 再次断线：visibility visible 立即重试（visibilitychange 派发在 document 上）。
    harness.latest?.fail()
    document.dispatchEvent(new Event('visibilitychange'))
    expect(harness.sockets).toHaveLength(3)

    // open 状态下的事件不重建连接。
    const count = harness.sockets.length
    window.dispatchEvent(new Event('online'))
    document.dispatchEvent(new Event('visibilitychange'))
    expect(harness.sockets).toHaveLength(count)
  })

  it('never lets stale generation callbacks affect the current connection', () => {
    vi.useFakeTimers()
    const { connection, harness, onOpen, onMessage } = openConnection()
    connection.connect()
    const first = harness.openLatest()
    expect(onOpen).toHaveBeenCalledTimes(1)

    // 断线并重连：第二个 socket 是当前 generation。
    first.fail()
    vi.advanceTimersByTime(250)
    const second = harness.openLatest()
    expect(harness.sockets).toHaveLength(2)
    expect(onOpen).toHaveBeenCalledTimes(2)

    // 旧 socket 迟到的事件一律无效：不触发 onOpen/onMessage/重连。
    first.open()
    first.emitServer({ type: 'subscribed', resource: { kind: 'thread', id: 't-1' } })
    first.onerror?.()
    const socketsAfterStale = harness.sockets.length
    vi.advanceTimersByTime(10_000)
    expect(harness.sockets).toHaveLength(socketsAfterStale)
    expect(onOpen).toHaveBeenCalledTimes(2)
    expect(onMessage).not.toHaveBeenCalled()

    // 当前 socket 的消息仍正常送达。
    second.emitServer({ type: 'resync', resource: { kind: 'thread', id: 't-1' } })
    expect(onMessage).toHaveBeenCalledWith({ type: 'resync', resource: { kind: 'thread', id: 't-1' } })
  })

  it('disconnect closes the socket, stops timers and reconnect; connect restarts', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    const first = harness.openLatest()

    first.fail()
    expect(vi.getTimerCount()).toBe(1)
    connection.disconnect()
    expect(vi.getTimerCount()).toBe(0)
    expect(first.closed).toBe(true)
    expect(connection.getStatus()).toBe('closed')

    // 断开的连接不再重连（真实卸载语义），旧 socket 的 close 也不调度重连。
    vi.advanceTimersByTime(10_000)
    expect(harness.sockets).toHaveLength(1)

    // 显式 connect() 可以重启（StrictMode mount->cleanup->mount）。
    connection.connect()
    expect(harness.sockets).toHaveLength(2)
    expect(connection.getStatus()).toBe('connecting')
    harness.openLatest()
    expect(connection.getStatus()).toBe('open')
  })
})

describe('createApplicationEventUrl', () => {
  it('builds ws://<origin>/api/events/v1 from the current location', () => {
    window.history.replaceState({}, '', '/chats/abc')
    expect(createApplicationEventUrl()).toBe(`ws://${window.location.host}/api/events/v1`)
  })
})
