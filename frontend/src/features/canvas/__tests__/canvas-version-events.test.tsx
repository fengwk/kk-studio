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
  const onSnapshot = vi.fn()
  const rendered = renderHook(
    (props: Props) =>
      useCanvasVersionEvents({
        canvasId: CANVAS_ID,
        enabled: props.enabled ?? true,
        version: props.version ?? '0',
        onSnapshot,
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
  return { ...rendered, sockets, onSnapshot }
}

function versionEvent(sockets: FakeWebSocketHarness, version: string) {
  const socket = sockets.latest
  if (socket == null) {
    throw new Error('no socket created')
  }
  // durable version 事件必须携带与 data.version 完全相等的 canonical cursor。
  act(() =>
    socket.emitServer({
      type: 'event',
      resource: canvasResource,
      name: 'version',
      data: { version },
      cursor: version,
    }),
  )
}

describe('useCanvasVersionEvents', () => {
  it('subscribes the canvas resource and refreshes the snapshot on the subscribed ack', () => {
    const { sockets, onSnapshot } = renderEvents({ version: '7' })
    const socket = sockets.openLatest()

    expect(socket.sentMessages()).toEqual([{ version: 1, type: 'subscribe', resource: canvasResource }])
    // 快照 GET 与 wire 建立之间的缺口由 subscribed ack 关闭。
    expect(onSnapshot).not.toHaveBeenCalled()
    act(() => socket.emitServer({ type: 'subscribed', resource: canvasResource, cursor: '0' }))
    expect(onSnapshot).toHaveBeenCalledTimes(1)
  })

  it('refreshes the snapshot only for versions newer than the known one', () => {
    const { sockets, onSnapshot, rerender } = renderEvents({ version: '7' })

    // codec 已拒绝数字/前导零/负数/畸形 data 与缺失/不匹配的 cursor；hook 层
    // 只负责与最后已知版本比较，旧版本事件不触发同步。
    versionEvent(sockets, '7')
    versionEvent(sockets, '6')
    versionEvent(sockets, '8')
    expect(onSnapshot).toHaveBeenCalledTimes(1)

    // 本地版本前进后，迟到的旧事件不再触发同步。
    rerender({ version: '8' })
    versionEvent(sockets, '8')
    expect(onSnapshot).toHaveBeenCalledTimes(1)
  })

  it('compares versions beyond Number.MAX_SAFE_INTEGER without JS number loss', () => {
    const { sockets, onSnapshot, rerender } = renderEvents({ version: '9007199254740992' })

    // MAX_SAFE_INTEGER+1 在 JS number 中无法区分，但十进制字符串必须精确比较。
    versionEvent(sockets, '9007199254740993')
    expect(onSnapshot).toHaveBeenCalledTimes(1)
    versionEvent(sockets, '9007199254740992')
    expect(onSnapshot).toHaveBeenCalledTimes(1)

    rerender({ version: '9007199254740993' })
    versionEvent(sockets, '9007199254740994')
    expect(onSnapshot).toHaveBeenCalledTimes(2)
  })

  it('refreshes the full snapshot on resync events and resource errors', () => {
    const { sockets, onSnapshot } = renderEvents()
    const socket = sockets.openLatest()

    act(() => socket.emitServer({ type: 'resync', resource: canvasResource }))
    expect(onSnapshot).toHaveBeenCalledTimes(1)
    // 订阅/事件处理失败：本地状态不可信，重新读取权威快照。
    act(() =>
      socket.emitServer({
        type: 'error',
        resource: canvasResource,
        code: 'SUBSCRIBE_FAILED',
        message: 'boom',
      }),
    )
    expect(onSnapshot).toHaveBeenCalledTimes(2)
  })

  it('refreshes the snapshot via the subscribed ack after a shared-connection reconnect', async () => {
    const { sockets, onSnapshot } = renderEvents({ version: '3' })
    const first = sockets.openLatest()
    act(() => first.emitServer({ type: 'subscribed', resource: canvasResource, cursor: '0' }))
    expect(onSnapshot).toHaveBeenCalledTimes(1)

    // 断线重连由共享 Connection 负责：新 socket 上重发 subscribe，
    // 重连后的 subscribed ack 再次同步，关闭断线窗口内的版本缺口。
    first.fail()
    await waitFor(() => expect(sockets.sockets.length).toBe(2), { timeout: 2000 })
    const second = sockets.openLatest()
    expect(second.sentMessages()).toEqual([{ version: 1, type: 'subscribe', resource: canvasResource }])
    act(() => second.emitServer({ type: 'subscribed', resource: canvasResource, cursor: '0' }))
    expect(onSnapshot).toHaveBeenCalledTimes(2)
  })

  it('does not subscribe when disabled and unsubscribes when disabled again', () => {
    const { sockets, rerender } = renderEvents({ enabled: false })
    expect(sockets.latest?.sentMessages() ?? []).toHaveLength(0)

    rerender({ enabled: true })
    const socket = sockets.openLatest()
    expect(socket.sentMessages()).toEqual([{ version: 1, type: 'subscribe', resource: canvasResource }])

    // 连接仍存活时禁用订阅：wire 上出现 unsubscribe（refcount 释放路径）。
    rerender({ enabled: false })
    expect(socket.sentMessages().at(-1)).toEqual({ version: 1, type: 'unsubscribe', resource: canvasResource })
  })
})
