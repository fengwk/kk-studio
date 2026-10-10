import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApplicationEventManager } from '@/shared/app-events/manager'
import {
  FakeWebSocketHarness,
} from '@/shared/app-events/__tests__/fake-websocket'
import type { TerminalCommand } from '@/shared/app-events/protocol'

const URL = 'ws://test/api/events/v1'
const THREAD_A = { kind: 'thread', id: 'aaaaaaaa-0000-4000-8000-000000000001' } as const
const THREAD_B = { kind: 'thread', id: 'bbbbbbbb-0000-4000-8000-000000000002' } as const
const CANVAS_A = { kind: 'canvas', id: 'cccccccc-0000-4000-8000-000000000003' } as const
const PROJECTS = { kind: 'projects' } as const
const VIEWER_A = 'dddddddd-0000-4000-8000-000000000004'
const VIEWER_B = 'eeeeeeee-0000-4000-8000-000000000005'
const TERMINAL_IDENTITY = {
  daemonInstanceId: '00000000-0000-0000-0000-000000000001',
  terminalId: '00000000-0000-0000-0000-000000000002',
} as const

const managers: ApplicationEventManager[] = []

function setup() {
  const harness = new FakeWebSocketHarness()
  const manager = new ApplicationEventManager({ url: URL, socketFactory: harness.factory })
  managers.push(manager)
  manager.connect()
  return { manager, harness }
}

describe('ApplicationEventManager', () => {
  beforeEach(() => {
    vi.spyOn(Math, 'random').mockReturnValue(0)
  })

  afterEach(() => {
    for (const manager of managers.splice(0)) {
      manager.disconnect()
    }
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
      { version: 2, type: 'subscribe', resource: THREAD_A },
      { version: 2, type: 'subscribe', resource: THREAD_B },
    ])

    // 同资源第二消费者不产生新 wire 消息；释放一个仍保持订阅。
    releaseA2()
    expect(socket.sentMessages()).toHaveLength(2)
    releaseA1()
    expect(socket.sentMessages()).toEqual([
      { version: 2, type: 'subscribe', resource: THREAD_A },
      { version: 2, type: 'subscribe', resource: THREAD_B },
      { version: 2, type: 'unsubscribe', resource: THREAD_A },
    ])
    // 幂等释放。
    releaseA1()
    expect(socket.sentMessages()).toHaveLength(3)

    releaseB()
    expect(socket.sentMessages().at(-1)).toEqual({
      version: 2,
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
    expect(socket.sentMessages()).toEqual([{ version: 2, type: 'subscribe', resource: THREAD_A }])

    // 第一个 unsubscribe 不能错误拆 wire：同一 listener 仍有真实 refcount。
    release1()
    expect(socket.sentMessages()).toHaveLength(1)
    socket.emitServer({ type: 'event', resource: THREAD_A, name: 'version', data: { version: '1' }, cursor: '1' })
    expect(listener.onEvent).toHaveBeenCalledTimes(1)

    // 末 ref 才 unsubscribe，之后不再派发。
    release2()
    expect(socket.sentMessages().at(-1)).toEqual({
      version: 2,
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
      { version: 2, type: 'subscribe', resource: THREAD_A },
      { version: 2, type: 'subscribe', resource: CANVAS_A },
    ])

    // 断线重连：新 socket open 后再次重发。
    harness.latest?.fail()
    vi.advanceTimersByTime(250)
    expect(harness.sockets).toHaveLength(2)
    harness.openLatest()
    expect(harness.latest?.sentMessages()).toEqual([
      { version: 2, type: 'subscribe', resource: THREAD_A },
      { version: 2, type: 'subscribe', resource: CANVAS_A },
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

    socket.emitServer({ type: 'event', resource: THREAD_A, name: 'version', data: { version: '6' }, cursor: '6' })
    socket.emitServer({ type: 'event', resource: THREAD_A, name: 'realtime', data: { type: 'MODEL_DELTA' } })
    expect(onEvent.mock.calls).toEqual([
      ['version', { version: '6' }, '6'],
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
      { version: 2, type: 'subscribe', resource: PROJECTS },
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
    socket.emitServer({ type: 'subscribed', resource: THREAD_A, cursor: '1' })
    socket.emitServer({
      type: 'event',
      resource: THREAD_A,
      name: 'version',
      data: { version: '2' },
      cursor: '2',
    })
    socket.emitServer({ type: 'resync', resource: THREAD_A })
    socket.emitServer({ type: 'error', resource: THREAD_A, code: 'SUBSCRIBE_FAILED', message: 'boom' })

    expect(healthy.onSubscribed).toHaveBeenCalledWith('1')
    expect(healthy.onEvent).toHaveBeenCalledWith('version', { version: '2' }, '2')
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
    socket.emitServer({ type: 'event', resource: THREAD_A, name: 'version', data: { version: '1' }, cursor: '1' })
    expect(onEvent).not.toHaveBeenCalled()
    expect(onEvent2).toHaveBeenCalledTimes(1)

    release2()
    socket.emitServer({ type: 'resync', resource: THREAD_A })
    expect(onEvent2).toHaveBeenCalledTimes(1)
    // 最后一个 listener 释放后 wire 上出现 unsubscribe。
    expect(socket.sentMessages().at(-1)).toEqual({
      version: 2,
      type: 'unsubscribe',
      resource: THREAD_A,
    })
  })
})

const TERMINAL_STREAM = '00000000-0000-0000-0000-000000000003'
const TERMINAL_GRANT = {
  epoch: '00000000-0000-0000-0000-000000000004',
  token: '00000000-0000-0000-0000-000000000005',
}

function exitedEvent(viewerId: string) {
  return {
    version: 1,
    requestId: '11111111-1111-1111-1111-111111111111',
    environmentId: '22222222-2222-2222-2222-222222222222',
    viewerId,
    identity: TERMINAL_IDENTITY,
    type: 'EXITED',
    payload: { status: 'EXITED', exitCode: 0 },
  }
}

describe('ApplicationEventManager terminal hooks', () => {
  beforeEach(() => {
    vi.spyOn(Math, 'random').mockReturnValue(0)
  })

  afterEach(() => {
    for (const manager of managers.splice(0)) {
      manager.disconnect()
    }
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('subscribeTerminal immediately notifies the current status and dispatches shell.event by viewer', () => {
    const { manager, harness } = setup()
    harness.openLatest()
    const socket = harness.latest as NonNullable<typeof harness.latest>

    const onStatusChange = vi.fn()
    const onEvent = vi.fn()
    manager.subscribeTerminal(VIEWER_A, { onStatusChange, onEvent })
    // 登记时立即通知当前状态（open）。
    expect(onStatusChange).toHaveBeenCalledWith('open')

    socket.emitServer({ type: 'shell.event', event: exitedEvent(VIEWER_A) } as never)
    expect(onEvent).toHaveBeenCalledTimes(1)
    expect(onEvent).toHaveBeenCalledWith(exitedEvent(VIEWER_A))

    // 无 resource 订阅时 shell.event 仍按 viewer 送达（不建立 resource 订阅）。
    expect(socket.sentMessages()).toEqual([])
  })

  it('isolates viewers: another viewerId never reaches this page listener', () => {
    const { manager, harness } = setup()
    const socket = harness.openLatest()
    const onEventA = vi.fn()
    manager.subscribeTerminal(VIEWER_A, { onEvent: onEventA })

    socket.emitServer({ type: 'shell.event', event: exitedEvent(VIEWER_B) } as never)
    expect(onEventA).not.toHaveBeenCalled()

    socket.emitServer({ type: 'shell.event', event: exitedEvent(VIEWER_A) } as never)
    expect(onEventA).toHaveBeenCalledTimes(1)
  })

  it('isolates a throwing terminal listener with a fixed payload-free log message', () => {
    const { manager, harness } = setup()
    harness.openLatest()
    const socket = harness.latest as NonNullable<typeof harness.latest>
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})

    // 模拟 listener 抛出携带 WriterGrant/输入数据的异常，敏感值绝不允许进入日志。
    const secret = 'writer-grant-token-9f3a2b'
    const failing = {
      onEvent: vi.fn(() => {
        throw new Error(`terminal listener exploded: ${secret}`)
      }),
    }
    const healthy = { onEvent: vi.fn() }
    manager.subscribeTerminal(VIEWER_A, failing)
    manager.subscribeTerminal(VIEWER_A, healthy)

    socket.emitServer({ type: 'shell.event', event: exitedEvent(VIEWER_A) } as never)

    // 抛错 listener 被隔离，另一个 listener 仍收到事件。
    expect(healthy.onEvent).toHaveBeenCalledTimes(1)
    // 日志只有固定去敏消息：不接受异常对象参数，也不含敏感值。
    expect(consoleError).toHaveBeenCalledTimes(1)
    expect(consoleError.mock.calls[0]).toEqual(['application event terminal listener failed'])
    expect(JSON.stringify(consoleError.mock.calls[0])).not.toContain(secret)
  })

  it('isolates a throwing terminal status listener with a fixed payload-free log message', () => {
    const { manager, harness } = setup()
    harness.openLatest()
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})

    const secret = 'input-bytes-deadbeef'
    const statuses: string[] = []
    manager.subscribeTerminal(VIEWER_A, {
      onStatusChange: (status) => {
        // 登记时的 open 通知正常返回；真实状态变更时抛出携带敏感值的异常。
        if (status !== 'open') {
          throw new Error(`status listener exploded: ${secret}`)
        }
      },
    })
    manager.subscribeTerminal(VIEWER_A, { onStatusChange: (status) => statuses.push(status) })
    expect(statuses).toEqual(['open'])

    harness.latest?.fail()

    // 抛错 listener 被隔离，另一个 listener 仍收到状态变更。
    expect(statuses).toEqual(['open', 'backoff'])
    expect(consoleError).toHaveBeenCalledTimes(1)
    expect(consoleError.mock.calls[0]).toEqual([
      'application event terminal status listener failed',
    ])
    expect(JSON.stringify(consoleError.mock.calls[0])).not.toContain(secret)
  })

  it('refcounts terminal listeners and makes cancel idempotent', () => {
    const { manager, harness } = setup()
    const socket = harness.openLatest()
    const onEvent = vi.fn()
    const listener = { onEvent }
    const release1 = manager.subscribeTerminal(VIEWER_A, listener)
    const release2 = manager.subscribeTerminal(VIEWER_A, listener)

    release1()
    socket.emitServer({ type: 'shell.event', event: exitedEvent(VIEWER_A) } as never)
    expect(onEvent).toHaveBeenCalledTimes(1)

    release2()
    release2() // 幂等。
    socket.emitServer({ type: 'shell.event', event: exitedEvent(VIEWER_A) } as never)
    expect(onEvent).toHaveBeenCalledTimes(1)
  })

  it('relays real connection status changes to terminal listeners once', () => {
    vi.useFakeTimers()
    const { manager, harness } = setup()
    harness.openLatest()
    const statuses: string[] = []
    manager.subscribeTerminal(VIEWER_A, { onStatusChange: (status) => statuses.push(status) })
    expect(statuses).toEqual(['open'])

    harness.latest?.fail()
    expect(statuses).toEqual(['open', 'backoff'])
    vi.advanceTimersByTime(250)
    expect(statuses).toEqual(['open', 'backoff', 'connecting'])
    harness.openLatest()
    expect(statuses).toEqual(['open', 'backoff', 'connecting', 'open'])
  })

  it('sendTerminal encodes typed INPUT bytes as canonical Base64 inside the shell.command wrapper', () => {
    const { manager, harness } = setup()
    const socket = harness.openLatest()
    const command = {
      version: 1,
      requestId: '11111111-1111-1111-1111-111111111111',
      environmentId: '22222222-2222-2222-2222-222222222222',
      viewerId: VIEWER_A,
      type: 'INPUT',
      payload: {
        identity: TERMINAL_IDENTITY,
        streamId: TERMINAL_STREAM,
        grant: TERMINAL_GRANT,
        seq: 42,
        inputModeRevision: 1,
        bytes: new Uint8Array([1, 2, 3, 4]),
      },
    } as unknown as TerminalCommand

    expect(manager.sendTerminal(command)).toBe(true)
    const sent = socket.sentMessages()
    expect(sent).toHaveLength(1)
    const frame = sent[0] as { version: number; type: string; command: { payload: { bytes: unknown } } }
    expect(frame.version).toBe(2)
    expect(frame.type).toBe('shell.command')
    expect(frame.command.payload.bytes).toBe('AQIDBA==')
  })

  it('sendTerminal returns false when not open or when the command is invalid', () => {
    const { manager, harness } = setup()
    const keepalive = {
      version: 1,
      requestId: '11111111-1111-1111-1111-111111111111',
      environmentId: '22222222-2222-2222-2222-222222222222',
      viewerId: VIEWER_A,
      type: 'KEEPALIVE',
      payload: { identity: TERMINAL_IDENTITY, streamId: TERMINAL_STREAM, grant: null },
    } as unknown as TerminalCommand
    // 未 open：不发送。
    expect(manager.sendTerminal(keepalive)).toBe(false)

    harness.openLatest()
    const invalid = { ...keepalive, type: 'BOGUS' } as unknown as TerminalCommand
    expect(manager.sendTerminal(invalid)).toBe(false)
    // 非法命令绝不发出任何物理帧。
    expect(harness.latest?.sent).toHaveLength(0)
  })
})
