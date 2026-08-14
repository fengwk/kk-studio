import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { useCanvasVersionEvents } from '@/features/canvas/canvas-version-events'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const canvasResource = { kind: 'canvas', id: CANVAS_ID } as const

interface Props {
  enabled?: boolean
  version?: string
}

function renderEvents(initialProps: Props = {}) {
  const sockets = new FakeWebSocketHarness()
  const onVersion = vi.fn()
  const onResync = vi.fn()
  const rendered = renderHook(
    (props: Props) =>
      useCanvasVersionEvents({
        canvasId: CANVAS_ID,
        enabled: props.enabled ?? true,
        version: props.version ?? '0',
        onVersion,
        onResync,
      }),
    {
      initialProps,
      wrapper: ({ children }: { children: ReactNode }) => (
        <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
          {children}
        </ApplicationEventProvider>
      ),
    },
  )
  return { ...rendered, sockets, onVersion, onResync }
}

function versionEvent(sockets: FakeWebSocketHarness, data: unknown) {
  const socket = sockets.latest
  if (socket == null) {
    throw new Error('no socket created')
  }
  act(() =>
    socket.emitServer({ type: 'event', resource: canvasResource, name: 'version', data }),
  )
}

describe('useCanvasVersionEvents', () => {
  it('subscribes the canvas resource and syncs changes once on the subscribed ack', () => {
    const { sockets, onVersion } = renderEvents({ version: '7' })
    const socket = sockets.openLatest()

    expect(socket.sentMessages()).toEqual([{ type: 'subscribe', resource: canvasResource }])
    // 快照 GET 与 wire 建立之间的缺口由 subscribed ack 关闭。
    expect(onVersion).not.toHaveBeenCalled()
    act(() => socket.emitServer({ type: 'subscribed', resource: canvasResource }))
    expect(onVersion).toHaveBeenCalledTimes(1)
  })

  it('triggers changes sync only for canonical string versions newer than the known one', () => {
    const { sockets, onVersion, rerender } = renderEvents({ version: '7' })

    // 数字/前导零/负数/畸形 payload 一律忽略；字符串旧版本也忽略。
    versionEvent(sockets, 6)
    versionEvent(sockets, '6')
    versionEvent(sockets, '01')
    versionEvent(sockets, '-1')
    versionEvent(sockets, 'not-json')
    versionEvent(sockets, '{"version":"6"}')
    expect(onVersion).toHaveBeenCalledTimes(0)

    // 对象与 JSON 文本两种 data 形式都接受。
    versionEvent(sockets, { version: '8' })
    expect(onVersion).toHaveBeenCalledTimes(1)

    // 本地版本前进后，迟到的旧事件不再触发同步。
    rerender({ version: '8' })
    versionEvent(sockets, { version: '8' })
    expect(onVersion).toHaveBeenCalledTimes(1)
  })

  it('compares versions beyond Number.MAX_SAFE_INTEGER without JS number loss', () => {
    const { sockets, onVersion, rerender } = renderEvents({ version: '9007199254740992' })

    // MAX_SAFE_INTEGER+1 在 JS number 中无法区分，但十进制字符串必须精确比较。
    versionEvent(sockets, { version: '9007199254740993' })
    expect(onVersion).toHaveBeenCalledTimes(1)
    versionEvent(sockets, { version: '9007199254740992' })
    expect(onVersion).toHaveBeenCalledTimes(1)

    rerender({ version: '9007199254740993' })
    versionEvent(sockets, { version: '9007199254740994' })
    expect(onVersion).toHaveBeenCalledTimes(2)
  })

  it('triggers full resync on resync events and on resource errors', () => {
    const { sockets, onResync } = renderEvents()
    const socket = sockets.openLatest()

    act(() => socket.emitServer({ type: 'resync', resource: canvasResource }))
    expect(onResync).toHaveBeenCalledTimes(1)
    // 订阅/事件处理失败：增量状态不可信，回退全量快照。
    act(() => socket.emitServer({ type: 'error', resource: canvasResource, message: 'boom' }))
    expect(onResync).toHaveBeenCalledTimes(2)
  })

  it('re-syncs via the subscribed ack after a shared-connection reconnect', async () => {
    const { sockets, onVersion } = renderEvents({ version: '3' })
    const first = sockets.openLatest()
    act(() => first.emitServer({ type: 'subscribed', resource: canvasResource }))
    expect(onVersion).toHaveBeenCalledTimes(1)

    // 断线重连由共享 Connection 负责：新 socket 上重发 subscribe，
    // 重连后的 subscribed ack 再次同步，关闭断线窗口内的版本缺口。
    first.fail()
    await waitFor(() => expect(sockets.sockets.length).toBe(2), { timeout: 2000 })
    const second = sockets.openLatest()
    expect(second.sentMessages()).toEqual([{ type: 'subscribe', resource: canvasResource }])
    act(() => second.emitServer({ type: 'subscribed', resource: canvasResource }))
    expect(onVersion).toHaveBeenCalledTimes(2)
  })

  it('does not subscribe when disabled and unsubscribes when disabled again', () => {
    const { sockets, rerender } = renderEvents({ enabled: false })
    expect(sockets.latest?.sentMessages() ?? []).toHaveLength(0)

    rerender({ enabled: true })
    const socket = sockets.openLatest()
    expect(socket.sentMessages()).toEqual([{ type: 'subscribe', resource: canvasResource }])

    // 连接仍存活时禁用订阅：wire 上出现 unsubscribe（refcount 释放路径）。
    rerender({ enabled: false })
    expect(socket.sentMessages().at(-1)).toEqual({ type: 'unsubscribe', resource: canvasResource })
  })
})
