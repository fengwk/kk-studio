import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApplicationEventConnection,
  createApplicationEventUrl,
  type ApplicationEventConnectionStatus,
} from '@/shared/app-events/connection'
import {
  FakeWebSocketHarness,
  SERVER_PUBLISHER,
  type FakeWebSocket,
} from '@/shared/app-events/__tests__/fake-websocket'
import type { ApplicationEventServerMessage } from '@/shared/app-events/protocol'
import {
  NotificationPacket,
  carrierChunk,
  carrierCount,
  decodeNotificationCarrier,
  defaultNotificationLimits,
} from '@/shared/notification/notification.mjs'

const URL = 'ws://test/api/events/v1'
const TOPIC = 'app.events.v2'
const THREAD_ID = '11111111-2222-4333-8444-555555555555'
const CANVAS_ID = 'cccccccc-0000-4000-8000-000000000001'
const OTHER_PUBLISHER = '99999999-9999-4999-8999-999999999999'
const encoder = new TextEncoder()
const LIMITS = defaultNotificationLimits()

/** 追踪本文件创建的全部连接，afterEach 统一 disconnect，避免 expire interval 泄漏。 */
const created: ApplicationEventConnection[] = []

function openConnection(
  harness = new FakeWebSocketHarness(),
  onStatusChange?: (status: ApplicationEventConnectionStatus) => void,
) {
  const onOpen = vi.fn()
  const onMessage = vi.fn<(message: ApplicationEventServerMessage) => void>()
  const connection = new ApplicationEventConnection({
    url: URL,
    socketFactory: harness.factory,
    onOpen,
    onMessage,
    onStatusChange,
  })
  created.push(connection)
  return { connection, harness, onOpen, onMessage }
}

let messageSeq = 0
function nextMessageId(): string {
  messageSeq += 1
  return `00000000-0000-4000-8000-${String(messageSeq).padStart(12, '0')}`
}

/** 以真实共享 carrier 把逻辑 server 消息编码为全部物理分片。 */
function frameServer(
  message: ApplicationEventServerMessage,
  { publisher = SERVER_PUBLISHER, target = null, topic = TOPIC } = {},
): string[] {
  const packet = new NotificationPacket(
    publisher,
    target,
    topic,
    nextMessageId(),
    encoder.encode(JSON.stringify({ version: 2, ...message })),
  )
  const count = carrierCount(packet.byteLength())
  return Array.from({ length: count }, (_, index) => carrierChunk(packet, index).encode())
}

/** 读取 fake 收到的首个物理分片的 publisher（即本连接的随机 endpoint publisher）。 */
function sentPublisher(socket: FakeWebSocket): string {
  const carrier = decodeNotificationCarrier(socket.sent[0] as string, LIMITS, null)
  if (carrier == null) {
    throw new Error('expected an outbound carrier')
  }
  return carrier.publisher()
}

describe('ApplicationEventConnection', () => {
  beforeEach(() => {
    vi.spyOn(Math, 'random').mockReturnValue(0)
  })

  afterEach(() => {
    for (const connection of created.splice(0)) {
      connection.disconnect()
    }
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('starts idle and transitions idle -> connecting -> open', () => {
    const { connection, harness, onOpen } = openConnection()
    expect(connection.getStatus()).toBe('idle')

    connection.connect()
    expect(connection.getStatus()).toBe('connecting')
    expect(harness.sockets).toHaveLength(1)
    expect(harness.sockets[0]?.url).toBe(URL)

    harness.openLatest()
    expect(connection.getStatus()).toBe('open')
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('notifies onStatusChange once per actual status transition', () => {
    vi.useFakeTimers()
    const statuses: ApplicationEventConnectionStatus[] = []
    const { connection, harness } = openConnection(new FakeWebSocketHarness(), (status) =>
      statuses.push(status),
    )

    connection.connect()
    connection.connect() // 幂等：不重复通知 connecting。
    harness.openLatest()
    harness.latest?.fail()
    vi.advanceTimersByTime(250)
    harness.openLatest()
    connection.disconnect()

    expect(statuses).toEqual(['connecting', 'open', 'backoff', 'connecting', 'open', 'closed'])
  })

  it('decodes framed server frames strictly; a protocol violation terminates the connection', () => {
    vi.useFakeTimers()
    const { connection, harness, onMessage } = openConnection()
    connection.connect()
    const socket = harness.openLatest()

    socket.emitServer({
      type: 'subscribed',
      resource: { kind: 'thread', id: THREAD_ID },
      cursor: '3',
    })
    expect(onMessage).toHaveBeenCalledWith({
      type: 'subscribed',
      resource: { kind: 'thread', id: THREAD_ID },
      cursor: '3',
    })

    // 逻辑协议违规（重组后不是合法 v2 帧）：本连接 terminal stop（无 code 安全关闭）。
    socket.emitRaw(frameServer({ type: 'nope' } as unknown as ApplicationEventServerMessage)[0])
    expect(socket.closed).toBe(true)
    expect(connection.getStatus()).toBe('closed')

    // 不再继续消费。
    socket.emitServer({ type: 'resync', resource: { kind: 'thread', id: THREAD_ID } })
    expect(onMessage).toHaveBeenCalledTimes(1)

    // 不无限重连：退避定时器不存在，online 事件与显式 connect() 都无效。
    vi.advanceTimersByTime(60_000)
    expect(harness.sockets).toHaveLength(1)
    window.dispatchEvent(new Event('online'))
    expect(harness.sockets).toHaveLength(1)
    connection.connect()
    expect(harness.sockets).toHaveLength(1)
  })

  it('reassembles a multi-fragment logical frame and rejects a raw (non-carrier) frame', () => {
    vi.useFakeTimers()
    const { connection, harness, onMessage } = openConnection()
    connection.connect()
    const socket = harness.openLatest()

    // 大 realtime 事件：逻辑体远超单个分片，必须经共享 reassembler 重组后一次交付。
    const bigData = { blob: 'x'.repeat(20_000) }
    const frames = frameServer({
      type: 'event',
      resource: { kind: 'thread', id: THREAD_ID },
      name: 'realtime',
      data: bigData,
    })
    expect(frames.length).toBeGreaterThan(1)
    for (const frame of frames) {
      socket.emitRaw(frame)
    }
    expect(onMessage).toHaveBeenCalledTimes(1)
    expect(onMessage).toHaveBeenCalledWith({
      type: 'event',
      resource: { kind: 'thread', id: THREAD_ID },
      name: 'realtime',
      data: bigData,
    })

    // raw JSON（非 carrier）是物理协议违规：terminal。
    socket.emitRaw(JSON.stringify({ version: 2, type: 'heartbeat' }))
    expect(connection.getStatus()).toBe('closed')
  })

  it('drops own echoes, freezes the peer and rejects wrong topic/target/binary frames', () => {
    vi.useFakeTimers()
    const { connection, harness, onMessage } = openConnection()
    connection.connect()
    const socket = harness.openLatest()
    expect(connection.send({ version: 2, type: 'subscribe', resource: { kind: 'thread', id: THREAD_ID } })).toBe(true)
    const self = sentPublisher(socket)

    // 自身回声：publisher === 本连接 endpoint publisher，Base64 解码前丢弃，连接保持 open。
    const echo = frameServer({ type: 'heartbeat' }, { publisher: self })
    socket.emitRaw(echo[0])
    expect(onMessage).not.toHaveBeenCalled()
    expect(connection.getStatus()).toBe('open')

    // 冻结 peer = SERVER_PUBLISHER 后，来自其它 publisher 的帧是致命违规。
    socket.emitServer({ type: 'heartbeat' })
    expect(connection.getStatus()).toBe('open')
    socket.emitRaw(frameServer({ type: 'heartbeat' }, { publisher: OTHER_PUBLISHER })[0])
    expect(connection.getStatus()).toBe('closed')
  })

  it('terminates on wrong topic, foreign target and binary frames', () => {
    vi.useFakeTimers()
    const wrongTopic = openConnection()
    wrongTopic.connection.connect()
    const topicSocket = wrongTopic.harness.openLatest()
    topicSocket.emitRaw(frameServer({ type: 'heartbeat' }, { topic: 'other.topic' })[0])
    expect(wrongTopic.connection.getStatus()).toBe('closed')

    const wrongTarget = openConnection()
    wrongTarget.connection.connect()
    const targetSocket = wrongTarget.harness.openLatest()
    targetSocket.emitRaw(frameServer({ type: 'heartbeat' }, { target: OTHER_PUBLISHER })[0])
    expect(wrongTarget.connection.getStatus()).toBe('closed')

    const binary = openConnection()
    binary.connection.connect()
    const binarySocket = binary.harness.openLatest()
    binarySocket.emitRaw(new Uint8Array([1, 2, 3]).buffer)
    expect(binary.connection.getStatus()).toBe('closed')
  })

  it('sends encoded client messages only while open', () => {
    const { connection, harness } = openConnection()
    connection.connect()
    expect(
      connection.send({ version: 2, type: 'subscribe', resource: { kind: 'thread', id: THREAD_ID } }),
    ).toBe(false)

    const socket = harness.openLatest()
    expect(
      connection.send({ version: 2, type: 'unsubscribe', resource: { kind: 'canvas', id: CANVAS_ID } }),
    ).toBe(true)
    expect(socket.sentMessages()).toEqual([
      { version: 2, type: 'unsubscribe', resource: { kind: 'canvas', id: CANVAS_ID } },
    ])
  })

  it('send never throws: closes the socket and reconnects on a synchronous send failure', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    const socket = harness.openLatest()
    socket.sendThrows = true

    // 异常绝不逃逸到调用方（React effect）。
    expect(() =>
      connection.send({ version: 2, type: 'subscribe', resource: { kind: 'thread', id: THREAD_ID } }),
    ).not.toThrow()
    expect(
      connection.send({ version: 2, type: 'subscribe', resource: { kind: 'thread', id: THREAD_ID } }),
    ).toBe(false)
    // close 是权威清理点：触发统一重连路径。
    expect(socket.closed).toBe(true)
    expect(connection.getStatus()).toBe('backoff')
    expect(vi.getTimerCount()).toBe(1)
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(2)
  })

  it('defers the drain while bufferedAmount is at the bound and resumes on the retry tick', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    const socket = harness.openLatest()

    socket.bufferedAmount = 8 * 1024 * 1024
    expect(
      connection.send({ version: 2, type: 'subscribe', resource: { kind: 'thread', id: THREAD_ID } }),
    ).toBe(true)
    // 未同步发送，但已排入 outbox 并调度下一次 drain。
    expect(socket.sent).toHaveLength(0)

    socket.bufferedAmount = 0
    vi.advanceTimersByTime(16)
    expect(socket.sentMessages()).toEqual([
      { version: 2, type: 'subscribe', resource: { kind: 'thread', id: THREAD_ID } },
    ])
  })

  it('reconnects with backoff 250/500/1000/2000/5000/10000 capped at 10s', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    harness.openLatest()

    const expectedDelays = [250, 500, 1000, 2000, 5000, 10000, 10000, 10000]
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

  it('applies deterministic jitter of at most 20% of the current base', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    harness.openLatest()

    // (random, 总延迟) 矩阵：base=250，jitter = floor(random * 250 * 0.2)。
    const cases: Array<[number, number]> = [
      [0, 250],
      [0.5, 275],
      [1, 300],
    ]
    let expectedSockets = 1
    for (const [random, total] of cases) {
      vi.mocked(Math.random).mockReturnValue(random)
      harness.latest?.fail()
      // 退避时刻之前绝不重连。
      vi.advanceTimersByTime(total - 1)
      expect(harness.sockets).toHaveLength(expectedSockets)
      // 到达退避时刻后立即创建下一个 socket。
      vi.advanceTimersByTime(1)
      expectedSockets += 1
      expect(harness.sockets).toHaveLength(expectedSockets)
      // open 新 socket 以重置退避序列（每次用例都从 base=250 开始）。
      harness.openLatest()
    }
  })

  it('retries immediately on close code 1012 via a 0-delay timer', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    harness.openLatest()

    // 服务端主动重启（无 error 事件）：立即重试，不推进退避序列。
    harness.latest?.closeWith(1012)
    expect(connection.getStatus()).toBe('backoff')
    expect(vi.getTimerCount()).toBe(1)
    vi.advanceTimersByTime(0)
    expect(harness.sockets).toHaveLength(2)
    expect(connection.getStatus()).toBe('connecting')
  })

  it('treats close codes 1002/1008 as terminal: no reconnect, lifecycle listeners unbound', () => {
    vi.useFakeTimers()
    for (const code of [1002, 1008]) {
      const { connection, harness } = openConnection()
      connection.connect()
      harness.openLatest()

      harness.latest?.closeWith(code)
      expect(connection.getStatus()).toBe('closed')

      // 不无限重试。
      vi.advanceTimersByTime(60_000)
      expect(harness.sockets).toHaveLength(1)
      // lifecycle listeners 已解绑：online/visible 不再触发重试，显式 connect() 也无效。
      window.dispatchEvent(new Event('online'))
      document.dispatchEvent(new Event('visibilitychange'))
      expect(harness.sockets).toHaveLength(1)
      connection.connect()
      expect(harness.sockets).toHaveLength(1)
    }
  })

  it('backs off normally on close code 1013 and network disconnects', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    harness.openLatest()

    // 1013（try again later）→ 正常退避。
    harness.latest?.closeWith(1013)
    expect(connection.getStatus()).toBe('backoff')
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(2)
    harness.openLatest()

    // 网络断线（1006）→ 正常退避。
    harness.latest?.closeWith(1006)
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(3)
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

  it('enters backoff when the socket factory throws synchronously and retries', () => {
    vi.useFakeTimers()
    const harness = new FakeWebSocketHarness()
    let calls = 0
    const connection = new ApplicationEventConnection({
      url: URL,
      socketFactory: (url) => {
        calls += 1
        if (calls === 1) {
          throw new Error('factory boom')
        }
        return harness.factory(url)
      },
    })
    created.push(connection)
    // 同步抛错绝不逃逸到调用方（Provider effect）：进入 backoff。
    expect(() => connection.connect()).not.toThrow()
    expect(connection.getStatus()).toBe('backoff')
    expect(harness.sockets).toHaveLength(0)
    expect(vi.getTimerCount()).toBe(1)

    // 退避到期后重试：factory 第二次成功。
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(1)
    expect(connection.getStatus()).toBe('connecting')
  })

  it('recovers when socket.close() throws synchronously: runtime failures reconnect, disconnect stays closed', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    const socket = harness.openLatest()
    socket.closeThrows = true

    // onerror 路径：close 同步抛错不逃逸；fence 掉当前 socket 进入 backoff 并重连。
    expect(() => socket.onerror?.()).not.toThrow()
    expect(socket.closed).toBe(false)
    expect(connection.getStatus()).toBe('backoff')
    expect(vi.getTimerCount()).toBe(1)
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(2)
    const second = harness.openLatest()

    // send 失败路径：send 与 close 都同步抛错；同样进入 backoff 并重连。
    second.sendThrows = true
    second.closeThrows = true
    let sent: boolean | undefined
    expect(() => {
      sent = connection.send({
        version: 2,
        type: 'subscribe',
        resource: { kind: 'thread', id: THREAD_ID },
      })
    }).not.toThrow()
    expect(sent).toBe(false)
    expect(connection.getStatus()).toBe('backoff')
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(3)
    const third = harness.openLatest()

    // disconnect 路径：close 抛错不逃逸、不重连，状态直接 closed。
    third.closeThrows = true
    expect(() => connection.disconnect()).not.toThrow()
    expect(connection.getStatus()).toBe('closed')
    vi.advanceTimersByTime(60_000)
    expect(harness.sockets).toHaveLength(3)
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
    first.emitServer({ type: 'subscribed', resource: { kind: 'thread', id: THREAD_ID }, cursor: '1' })
    first.onerror?.()
    const socketsAfterStale = harness.sockets.length
    vi.advanceTimersByTime(10_000)
    expect(harness.sockets).toHaveLength(socketsAfterStale)
    expect(onOpen).toHaveBeenCalledTimes(2)
    expect(onMessage).not.toHaveBeenCalled()

    // 当前 socket 的消息仍正常送达。
    second.emitServer({ type: 'resync', resource: { kind: 'thread', id: THREAD_ID } })
    expect(onMessage).toHaveBeenCalledWith({
      type: 'resync',
      resource: { kind: 'thread', id: THREAD_ID },
    })
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
  it('builds ws://<origin>/api/events/v1 and clears search/hash', () => {
    window.history.replaceState({}, '', '/chats/abc?session=1#top')
    const url = createApplicationEventUrl()
    expect(url).toBe(`ws://${window.location.host}/api/events/v1`)
    expect(url).not.toContain('?')
    expect(url).not.toContain('#')
  })
})
