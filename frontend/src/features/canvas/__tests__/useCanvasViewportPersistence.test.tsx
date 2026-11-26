import { StrictMode, type PropsWithChildren } from 'react'
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  CANVAS_VIEWPORT_PERSIST_INTERVAL_MS,
  useCanvasViewportPersistence,
} from '@/features/canvas/useCanvasViewportPersistence'
import {
  loadCanvasViewport,
  saveCanvasViewport,
  type StoredCanvasViewport,
} from '@/features/canvas/viewport-storage'

const CANVAS_A = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const CANVAS_B = '1f2e3d4c-5b6a-4978-8a6b-5c4d3e2f1a0b'
const VIEWPORT_KEY_PREFIX = 'kk-studio.canvas.viewport:'

interface ViewportWrite {
  canvasId: string
  viewport: StoredCanvasViewport
}

/** 记录视口键的写入次数与值；写入照常落到真实存储。 */
function recordViewportWrites(): ViewportWrite[] {
  const writes: ViewportWrite[] = []
  const setItem = Storage.prototype.setItem
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function record(
    this: Storage,
    key: string,
    value: string,
  ) {
    if (key.startsWith(VIEWPORT_KEY_PREFIX)) {
      writes.push({
        canvasId: key.slice(VIEWPORT_KEY_PREFIX.length),
        viewport: JSON.parse(value) as StoredCanvasViewport,
      })
    }
    setItem.call(this, key, value)
  })
  return writes
}

/** StrictMode 会重复调用更新函数与 effect：写入不得因此翻倍。 */
function StrictWrapper({ children }: PropsWithChildren) {
  return <StrictMode>{children}</StrictMode>
}

function renderPersistence(canvasId: string, viewport: StoredCanvasViewport) {
  return renderHook(
    (props: { canvasId: string; viewport: StoredCanvasViewport }) => (
      useCanvasViewportPersistence(props.canvasId, props.viewport)
    ),
    { wrapper: StrictWrapper, initialProps: { canvasId, viewport } },
  )
}

describe('useCanvasViewportPersistence', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('coalesces a one-second drag into a bounded number of writes with the latest value', () => {
    const writes = recordViewportWrites()
    const { rerender } = renderPersistence(CANVAS_A, { x: 0, y: 0, zoom: 1 })
    writes.length = 0

    // 拖拽 1 秒：React Flow 每帧回调 onMove，视口逐帧变化，但写入按间隔合并。
    for (let frame = 1; frame <= 60; frame += 1) {
      rerender({ canvasId: CANVAS_A, viewport: { x: frame, y: -frame, zoom: 1 } })
      act(() => {
        vi.advanceTimersByTime(16)
      })
    }
    act(() => {
      vi.advanceTimersByTime(CANVAS_VIEWPORT_PERSIST_INTERVAL_MS)
    })

    // 60 次视口更新实测 6 次写盘：首帧 1 次 + 每 200ms 1 次 + 尾帧 1 次。
    expect(writes.length).toBeLessThanOrEqual(7)
    expect(loadCanvasViewport(CANVAS_A)).toEqual({ x: 60, y: -60, zoom: 1 })
  })

  it('writes once per change under StrictMode and never rewrites the restored viewport', () => {
    const writes = recordViewportWrites()
    const { rerender } = renderPersistence(CANVAS_A, { x: 0, y: 0, zoom: 1 })
    // 打开画布时的视口来自存档，回写同一值没有意义。
    expect(writes).toHaveLength(0)

    rerender({ canvasId: CANVAS_A, viewport: { x: 12, y: 18, zoom: 0.74 } })
    expect(writes).toEqual([{ canvasId: CANVAS_A, viewport: { x: 12, y: 18, zoom: 0.74 } }])
  })

  it('skips writing a viewport that is already persisted', () => {
    const writes = recordViewportWrites()
    const { rerender } = renderPersistence(CANVAS_A, { x: 0, y: 0, zoom: 1 })
    rerender({ canvasId: CANVAS_A, viewport: { x: 1, y: 2, zoom: 1 } })
    act(() => {
      vi.advanceTimersByTime(CANVAS_VIEWPORT_PERSIST_INTERVAL_MS)
    })
    writes.length = 0

    // 相同数值的新对象（视口重建）不产生新的存储写入。
    rerender({ canvasId: CANVAS_A, viewport: { x: 1, y: 2, zoom: 1 } })
    act(() => {
      vi.advanceTimersByTime(CANVAS_VIEWPORT_PERSIST_INTERVAL_MS)
    })
    expect(writes).toHaveLength(0)
  })

  it('hands the pending viewport to the canvas it belongs to before switching', () => {
    saveCanvasViewport(CANVAS_B, { x: 2, y: 2, zoom: 1 })
    const writes = recordViewportWrites()
    const { rerender, unmount } = renderPersistence(CANVAS_A, { x: 0, y: 0, zoom: 1 })
    writes.length = 0

    rerender({ canvasId: CANVAS_A, viewport: { x: 3, y: 3, zoom: 1 } })
    expect(writes).toEqual([{ canvasId: CANVAS_A, viewport: { x: 3, y: 3, zoom: 1 } }])

    // 节流窗口内的最后一次平移还没落盘就切到另一张画布。
    rerender({ canvasId: CANVAS_A, viewport: { x: 9, y: 9, zoom: 1 } })
    expect(writes).toHaveLength(1)
    rerender({ canvasId: CANVAS_B, viewport: loadCanvasViewport(CANVAS_B) })
    expect(writes).toEqual([
      { canvasId: CANVAS_A, viewport: { x: 3, y: 3, zoom: 1 } },
      { canvasId: CANVAS_A, viewport: { x: 9, y: 9, zoom: 1 } },
    ])
    expect(loadCanvasViewport(CANVAS_A)).toEqual({ x: 9, y: 9, zoom: 1 })

    // 新画布的恢复视口不回写，此后的平移才落到新画布。
    act(() => {
      vi.advanceTimersByTime(CANVAS_VIEWPORT_PERSIST_INTERVAL_MS)
    })
    expect(writes).toHaveLength(2)
    rerender({ canvasId: CANVAS_B, viewport: { x: 7, y: 8, zoom: 1 } })
    act(() => {
      vi.advanceTimersByTime(CANVAS_VIEWPORT_PERSIST_INTERVAL_MS)
    })
    expect(loadCanvasViewport(CANVAS_B)).toEqual({ x: 7, y: 8, zoom: 1 })
    unmount()
  })

  it('flushes the pending viewport on unmount', () => {
    const writes = recordViewportWrites()
    const { rerender, unmount } = renderPersistence(CANVAS_A, { x: 0, y: 0, zoom: 1 })

    rerender({ canvasId: CANVAS_A, viewport: { x: 4, y: 5, zoom: 1 } })
    rerender({ canvasId: CANVAS_A, viewport: { x: 40, y: 50, zoom: 1.4 } })
    writes.length = 0

    unmount()
    expect(writes).toEqual([{ canvasId: CANVAS_A, viewport: { x: 40, y: 50, zoom: 1.4 } }])
  })
})
