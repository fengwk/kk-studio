import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApplicationEventConnection,
  createApplicationEventUrl,
  type ApplicationEventConnectionState,
} from '@/shared/app-events/connection'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import type { ApplicationEventServerMessage } from '@/shared/app-events/protocol'

const URL = 'ws://test/ws/events'
const THREAD_ID = '11111111-2222-4333-8444-555555555555'
const CANVAS_ID = 'cccccccc-0000-4000-8000-000000000001'
const HEARTBEAT_INTERVAL_MS = 1000
/** connected 帧：20s 服务端间隔由测试统一改为 1s，方便用假定时器推导失联预算。 */
const CONNECTED = {
  type: 'connected',
  eventNodeId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  connectionId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  heartbeatIntervalMillis: String(HEARTBEAT_INTERVAL_MS),
} as const

function openConnection(harness = new FakeWebSocketHarness()) {
  const onOpen = vi.fn()
  const onMessage = vi.fn<(message: ApplicationEventServerMessage) => void>()
  const onClose = vi.fn()
  const connection = new ApplicationEventConnection({
    url: URL,
    socketFactory: harness.factory,
    onOpen,
    onMessage,
    onClose,
  })
  return { connection, harness, onOpen, onMessage, onClose }
}

/** 打开最新 socket 并完成 connected 握手，返回 ready 状态的 socket。 */
function readyLatest(harness: FakeWebSocketHarness) {
  const socket = harness.openLatest()
  socket.emitServer({ ...CONNECTED })
  return socket
}

describe('ApplicationEventConnection', () => {
  beforeEach(() => {
    vi.spyOn(Math, 'random').mockReturnValue(0)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('starts idle and transitions idle -> connecting -> ready on the connected frame', () => {
    const { connection, harness, onOpen } = openConnection()
    expect(connection.getStatus()).toBe('idle')

    connection.connect()
    expect(connection.getStatus()).toBe('connecting')
    expect(harness.sockets).toHaveLength(1)
    expect(harness.sockets[0]?.url).toBe(URL)

    // transport open 只是握手进行中：connected 未到前不算就绪。
    harness.openLatest()
    expect(connection.getStatus()).toBe('connecting')
    expect(connection.getState().reason).toEqual({ kind: 'connecting', transportOpen: true })

    const socket = harness.latest as NonNullable<typeof harness.latest>
    socket.emitServer({ ...CONNECTED })
    expect(connection.getStatus()).toBe('ready')
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('decodes server frames strictly; a protocol violation terminates the connection', () => {
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

    // decoder 返回 null = 服务端协议违规：本连接 terminal stop（无 code 安全关闭）。
    socket.onmessage?.({ data: 'not json' })
    expect(socket.closed).toBe(true)
    expect(connection.getStatus()).toBe('stopped')
    expect(connection.getState().reason).toEqual({ kind: 'stopped', cause: 'protocol' })

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

  it('sends encoded client messages only while the transport is open', () => {
    const { connection, harness } = openConnection()
    connection.connect()
    expect(
      connection.send({ type: 'subscribe', resource: { kind: 'thread', id: THREAD_ID } }),
    ).toBe(false)

    // transport open（尚未 connected）即允许发送：订阅重发必须先于握手完成。
    const socket = harness.openLatest()
    expect(
      connection.send({ type: 'unsubscribe', resource: { kind: 'canvas', id: CANVAS_ID } }),
    ).toBe(true)
    expect(socket.sentMessages()).toEqual([
      { type: 'unsubscribe', resource: { kind: 'canvas', id: CANVAS_ID } },
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
      connection.send({ type: 'subscribe', resource: { kind: 'thread', id: THREAD_ID } }),
    ).not.toThrow()
    expect(
      connection.send({ type: 'subscribe', resource: { kind: 'thread', id: THREAD_ID } }),
    ).toBe(false)
    // close 是权威清理点：触发统一重连路径。
    expect(socket.closed).toBe(true)
    expect(connection.getStatus()).toBe('backoff')
    expect(vi.getTimerCount()).toBe(1)
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(2)
  })

  it('reconnects with backoff 250/500/1000/2000/5000/10000 capped at 10s', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    readyLatest(harness)

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
    readyLatest(harness)

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
      readyLatest(harness)
    }
  })

  it('retries immediately on close code 1012 via a 0-delay timer', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    readyLatest(harness)

    // 服务端主动重启（无 error 事件）：立即重试，不推进退避序列。
    harness.latest?.closeWith(1012)
    expect(connection.getStatus()).toBe('backoff')
    expect(connection.getState().reason).toMatchObject({ cause: 'server-restart', retryAt: Date.now() })
    expect(vi.getTimerCount()).toBe(1)
    vi.advanceTimersByTime(0)
    expect(harness.sockets).toHaveLength(2)
    expect(connection.getStatus()).toBe('connecting')
  })

  it('treats close codes 1002/1008 as terminal: no reconnect, lifecycle listeners unbound', () => {
    vi.useFakeTimers()
    for (const [code, cause] of [
      [1002, 'protocol'],
      [1008, 'policy'],
    ] as const) {
      const { connection, harness } = openConnection()
      connection.connect()
      readyLatest(harness)

      harness.latest?.closeWith(code)
      expect(connection.getStatus()).toBe('stopped')
      // 终止原因随 close code 区分（1002 协议错误、1008 策略违规），UI 不显示持续重连。
      expect(connection.getState().reason).toEqual({ kind: 'stopped', cause })

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
    readyLatest(harness)

    // 1013（try again later）→ 正常退避。
    harness.latest?.closeWith(1013)
    expect(connection.getStatus()).toBe('backoff')
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(2)
    readyLatest(harness)

    // 网络断线（1006）→ 正常退避。
    harness.latest?.closeWith(1006)
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(3)
  })

  it('retries immediately on online and on visibility becoming visible', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    readyLatest(harness)

    // 断线后退避定时器 pending：online 事件立即重试（清掉定时器）。
    harness.latest?.fail()
    expect(vi.getTimerCount()).toBe(1)
    window.dispatchEvent(new Event('online'))
    expect(harness.sockets).toHaveLength(2)
    // 新连接自带 15s 握手预算定时器，因此 pending 的 timer 数为 1 而不是 0。
    expect(vi.getTimerCount()).toBe(1)

    // 再次断线：visibility visible 立即重试（visibilitychange 派发在 document 上）。
    harness.latest?.fail()
    document.dispatchEvent(new Event('visibilitychange'))
    expect(harness.sockets).toHaveLength(3)

    // ready 状态下的事件不重建连接。
    readyLatest(harness)
    const count = harness.sockets.length
    window.dispatchEvent(new Event('online'))
    document.dispatchEvent(new Event('visibilitychange'))
    expect(harness.sockets).toHaveLength(count)
  })

  it('keeps a healthy connection on visibility return and recovers a stale one', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    readyLatest(harness)

    // 失联预算未到：healthy 连接在 visible 时保留，不重建。
    vi.advanceTimersByTime(HEARTBEAT_INTERVAL_MS * 3 - 1)
    document.dispatchEvent(new Event('visibilitychange'))
    expect(harness.sockets).toHaveLength(1)

    // 越过预算：visible 时立即按失联恢复。
    vi.advanceTimersByTime(1)
    document.dispatchEvent(new Event('visibilitychange'))
    expect(harness.sockets).toHaveLength(2)
    expect(connection.getStatus()).toBe('connecting')
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

  it('recovers when socket.close() throws synchronously: runtime failures reconnect, disconnect stays stopped', () => {
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
        type: 'subscribe',
        resource: { kind: 'thread', id: THREAD_ID },
      })
    }).not.toThrow()
    expect(sent).toBe(false)
    expect(connection.getStatus()).toBe('backoff')
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(3)
    const third = harness.openLatest()

    // disconnect 路径：close 抛错不逃逸、不重连，状态直接 stopped。
    third.closeThrows = true
    expect(() => connection.disconnect()).not.toThrow()
    expect(connection.getStatus()).toBe('stopped')
    expect(connection.getState().reason).toEqual({ kind: 'stopped', cause: 'manual' })
    vi.advanceTimersByTime(60_000)
    expect(harness.sockets).toHaveLength(3)
  })

  it('never lets stale generation callbacks affect the current connection', () => {
    vi.useFakeTimers()
    const { connection, harness, onOpen, onMessage } = openConnection()
    connection.connect()
    const first = readyLatest(harness)
    expect(onOpen).toHaveBeenCalledTimes(1)

    // 断线并重连：第二个 socket 是当前 generation。
    first.fail()
    vi.advanceTimersByTime(250)
    const second = readyLatest(harness)
    expect(harness.sockets).toHaveLength(2)
    expect(onOpen).toHaveBeenCalledTimes(2)

    // 旧 socket 迟到的 open/message/close 一律无效：不触发 onOpen/onMessage/重连。
    first.open()
    first.closeWith(1006)
    first.onerror?.()
    const socketsAfterStale = harness.sockets.length
    const messagesBeforeStale = onMessage.mock.calls.length
    first.emitServer({ type: 'subscribed', resource: { kind: 'thread', id: THREAD_ID }, cursor: '1' })
    // 只推进到新连接活性预算之内：失联 fence 的行为由专项用例覆盖。
    vi.advanceTimersByTime(HEARTBEAT_INTERVAL_MS * 3 - 1)
    expect(harness.sockets).toHaveLength(socketsAfterStale)
    expect(onOpen).toHaveBeenCalledTimes(2)
    // 旧 socket 的迟到帧不会送达 listeners。
    expect(onMessage).toHaveBeenCalledTimes(messagesBeforeStale)

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
    readyLatest(harness)

    harness.latest?.fail()
    expect(vi.getTimerCount()).toBe(1)
    connection.disconnect()
    expect(vi.getTimerCount()).toBe(0)
    expect(harness.latest?.closed).toBe(true)
    expect(connection.getStatus()).toBe('stopped')

    // 断开的连接不再重连（真实卸载语义），旧 socket 的 close 也不调度重连。
    vi.advanceTimersByTime(10_000)
    expect(harness.sockets).toHaveLength(1)

    // 显式 connect() 可以重启（StrictMode mount->cleanup->mount）。
    connection.connect()
    expect(harness.sockets).toHaveLength(2)
    expect(connection.getStatus()).toBe('connecting')
    readyLatest(harness)
    expect(connection.getStatus()).toBe('ready')
  })

  it('terminates with a 15s handshake budget when the server never sends connected', () => {
    vi.useFakeTimers()
    const { connection, harness, onClose } = openConnection()
    connection.connect()
    harness.openLatest()

    // transport open 后预算继续计时：预算内不动作。
    vi.advanceTimersByTime(15_000 - 1)
    expect(harness.sockets).toHaveLength(1)
    expect(connection.getStatus()).toBe('connecting')

    // 超时立即 fence：旧 socket 被 close、onClose 已通知，随后按退避重连。
    vi.advanceTimersByTime(1)
    expect(harness.latest?.closed).toBe(true)
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(connection.getStatus()).toBe('backoff')
    expect(connection.getState().reason).toMatchObject({ cause: 'handshake-timeout' })
    expect(vi.getTimerCount()).toBe(1)
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(2)
  })

  it('terminates with the handshake budget even when the transport never opens', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    // 从不 open：预算同样覆盖建连阶段。

    vi.advanceTimersByTime(15_000)
    expect(harness.latest?.closed).toBe(true)
    expect(connection.getStatus()).toBe('backoff')
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(2)
  })

  it('fences the old generation after 3 missed heartbeat cycles and reconnects', () => {
    vi.useFakeTimers()
    const { connection, harness, onClose, onMessage } = openConnection()
    connection.connect()
    const socket = readyLatest(harness)

    // connected 帧本身刷新活性基线；其后 3 个整周期（3s）内没有合法帧即在边界失联。
    vi.advanceTimersByTime(HEARTBEAT_INTERVAL_MS * 3 - 1)
    expect(connection.getStatus()).toBe('ready')
    expect(harness.sockets).toHaveLength(1)

    vi.advanceTimersByTime(1)
    expect(connection.getStatus()).toBe('backoff')
    expect(connection.getState().reason).toMatchObject({ cause: 'liveness-timeout' })
    // 立即 fence：旧 socket 已被关闭，不等浏览器 close 回调。
    expect(socket.closed).toBe(true)
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(1)
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(2)

    // 旧 socket 迟到的帧不再触发任何回调（connected 帧已计数，onMessage 只收到它一次）。
    const messagesBefore = onMessage.mock.calls.length
    socket.emitServer({ type: 'heartbeat' })
    expect(onMessage).toHaveBeenCalledTimes(messagesBefore)
  })

  it('refreshes the liveness baseline on every legal server frame', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    const socket = readyLatest(harness)

    // 每条合法帧（heartbeat/subscribed/event 均可）都重置 3×interval 预算。
    for (let cycle = 1; cycle <= 5; cycle++) {
      vi.advanceTimersByTime(HEARTBEAT_INTERVAL_MS)
      socket.emitServer({ type: 'heartbeat' })
    }
    expect(connection.getStatus()).toBe('ready')
    expect(harness.sockets).toHaveLength(1)

    // 最后一帧之后 3 个周期无帧才失联。
    vi.advanceTimersByTime(HEARTBEAT_INTERVAL_MS * 3)
    expect(connection.getStatus()).toBe('backoff')
  })

  it('derives the liveness budget from the connected frame interval, not a hardcoded default', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    const socket = harness.openLatest()
    socket.emitServer({ ...CONNECTED, heartbeatIntervalMillis: '15000' })

    // 服务端声明 15s：预算 45s。20s（旧写死的默认间隔）不足以触发。
    vi.advanceTimersByTime(20_000)
    expect(connection.getStatus()).toBe('ready')
    // 45s 整准时触发。
    vi.advanceTimersByTime(25_000)
    expect(connection.getStatus()).toBe('backoff')
    expect(connection.getState().reason).toMatchObject({ cause: 'liveness-timeout' })
  })

  it('publishes read-only state snapshots to subscribers with the structured reason', () => {
    vi.useFakeTimers()
    const harness = new FakeWebSocketHarness()
    const connection = new ApplicationEventConnection({
      url: URL,
      socketFactory: harness.factory,
    })
    const states: ApplicationEventConnectionState[] = []
    const unsubscribe = connection.subscribeState((state) => states.push(state))
    // 订阅立即回调当前值。
    expect(states).toEqual([{ status: 'idle', reason: null }])

    connection.connect()
    readyLatest(harness)
    harness.latest?.fail()
    const backoffAt = Date.now()
    connection.disconnect()

    expect(states.map((state) => state.status)).toEqual([
      'idle',
      'connecting',
      'connecting',
      'ready',
      'backoff',
      'stopped',
    ])
    expect(states[1]?.reason).toEqual({ kind: 'connecting', transportOpen: false })
    expect(states[2]?.reason).toEqual({ kind: 'connecting', transportOpen: true })
    expect(states[3]?.reason).toBeNull()
    expect(states[4]?.reason).toEqual({
      kind: 'backoff',
      cause: 'transport-lost',
      retryAt: backoffAt + 250,
    })
    expect(states[5]?.reason).toEqual({ kind: 'stopped', cause: 'manual' })
    unsubscribe()
    // 取消后不再收到通知。
    connection.connect()
    expect(states).toHaveLength(6)
    connection.disconnect()
  })

  it('clears watchdog timers when a connection ends so no timer leak schedules reconnects', () => {
    vi.useFakeTimers()
    const { connection, harness } = openConnection()
    connection.connect()
    readyLatest(harness)

    // 失联 fence 携带的唯一 timer 是退避；旧 socket 的迟到 close 不得再叠加定时器。
    vi.advanceTimersByTime(HEARTBEAT_INTERVAL_MS * 3)
    expect(vi.getTimerCount()).toBe(1)
    const stale = harness.sockets[0] as NonNullable<typeof harness.sockets[0]>
    stale.onclose?.({ code: 1006 })
    expect(vi.getTimerCount()).toBe(1)
  })
})

describe('createApplicationEventUrl', () => {
  it('builds ws://<origin>/ws/events and clears search/hash', () => {
    window.history.replaceState({}, '', '/chats/abc?session=1#top')
    const url = createApplicationEventUrl()
    expect(url).toBe(`ws://${window.location.host}/ws/events`)
    expect(url).not.toContain('?')
    expect(url).not.toContain('#')
  })
})
