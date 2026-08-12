import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useCanvasVersionEvents } from '@/features/canvas/canvas-version-events'

const { createCanvasRealtimeStream } = vi.hoisted(() => ({
  createCanvasRealtimeStream: vi.fn(),
}))

vi.mock('@/shared/api/studio-service', () => ({
  createCanvasRealtimeStream,
}))

const { FakeEventSource } = vi.hoisted(() => {
  class FakeEventSource {
    static instances: FakeEventSource[] = []
    readonly url: string
    onerror: ((event: Event) => void) | null = null
    private readonly listeners = new Map<string, EventListener[]>()

    constructor(url: string) {
      this.url = url
      FakeEventSource.instances.push(this)
    }

    addEventListener(type: string, listener: EventListener): void {
      const existing = this.listeners.get(type) ?? []
      existing.push(listener)
      this.listeners.set(type, existing)
    }

    removeEventListener(type: string, listener: EventListener): void {
      const existing = this.listeners.get(type) ?? []
      this.listeners.set(
        type,
        existing.filter((value) => value !== listener),
      )
    }

    emit(type: string, data?: unknown): void {
      const listeners = this.listeners.get(type)
      if (listeners) {
        for (const listener of listeners) {
          listener({ data: data === undefined ? '' : JSON.stringify(data) } as MessageEvent<string>)
        }
      }
    }

    close(): void {
      this.listeners.clear()
    }
  }
  return { FakeEventSource }
})

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'

describe('useCanvasVersionEvents', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    FakeEventSource.instances = []
    createCanvasRealtimeStream.mockImplementation(
      (canvasId: string, afterVersion = 0) =>
        new FakeEventSource(`/api/canvases/${canvasId}/events/stream?afterVersion=${afterVersion}`),
    )
    vi.stubGlobal('EventSource', FakeEventSource)
  })

  it('subscribes after the current version and syncs once on connect', () => {
    renderHook(() => useCanvasVersionEvents({
      canvasId: CANVAS_ID,
      enabled: true,
      version: 7,
      onVersion: vi.fn(),
      onResync: vi.fn(),
    }))

    expect(createCanvasRealtimeStream).toHaveBeenCalledWith(CANVAS_ID, 7)
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(FakeEventSource.instances[0]?.url).toContain('afterVersion=7')
  })

  it('triggers changes sync only for versions newer than the known one', () => {
    const onVersion = vi.fn()
    const { rerender } = renderHook(({ version }) => useCanvasVersionEvents({
      canvasId: CANVAS_ID,
      enabled: true,
      version,
      onVersion,
      onResync: vi.fn(),
    }), { initialProps: { version: 7 } })
    const source = FakeEventSource.instances[0] as FakeEventSource

    act(() => source.emit('version', { version: 6 }))
    expect(onVersion).toHaveBeenCalledTimes(1) // 只有 connect 时的初始同步

    act(() => source.emit('version', { version: 8 }))
    expect(onVersion).toHaveBeenCalledTimes(2)

    // 本地版本前进后，迟到的旧事件不再触发同步。
    rerender({ version: 8 })
    act(() => source.emit('version', { version: 8 }))
    expect(onVersion).toHaveBeenCalledTimes(2)

    // 畸形 payload 被忽略。
    act(() => source.emit('version', { version: '9' }))
    expect(onVersion).toHaveBeenCalledTimes(2)
  })

  it('triggers full resync on the resync event', () => {
    const onResync = vi.fn()
    renderHook(() => useCanvasVersionEvents({
      canvasId: CANVAS_ID,
      enabled: true,
      version: 0,
      onVersion: vi.fn(),
      onResync,
    }))

    act(() => (FakeEventSource.instances[0] as FakeEventSource).emit('resync'))
    expect(onResync).toHaveBeenCalledTimes(1)
  })

  it('reconnects with the last known version after a connection error', async () => {
    const onVersion = vi.fn()
    renderHook(() => useCanvasVersionEvents({
      canvasId: CANVAS_ID,
      enabled: true,
      version: 3,
      onVersion,
      onResync: vi.fn(),
    }))
    const first = FakeEventSource.instances[0] as FakeEventSource

    act(() => first.onerror?.(new Event('error')))

    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(2))
    const second = FakeEventSource.instances[1] as FakeEventSource
    expect(second.url).toContain('afterVersion=3')
    // 重连后再次初始同步，关闭断线窗口内的版本缺口。
    expect(onVersion).toHaveBeenCalledTimes(2)
  })

  it('does not subscribe when disabled and closes the stream on teardown', () => {
    const { unmount, rerender } = renderHook(({ enabled }) => useCanvasVersionEvents({
      canvasId: CANVAS_ID,
      enabled,
      version: 0,
      onVersion: vi.fn(),
      onResync: vi.fn(),
    }), { initialProps: { enabled: false } })
    expect(FakeEventSource.instances).toHaveLength(0)

    rerender({ enabled: true })
    expect(FakeEventSource.instances).toHaveLength(1)
    const source = FakeEventSource.instances[0] as FakeEventSource
    const close = vi.spyOn(source, 'close')

    unmount()
    expect(close).toHaveBeenCalled()
  })
})
