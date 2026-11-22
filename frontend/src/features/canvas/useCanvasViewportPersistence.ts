import { useCallback, useEffect, useRef } from 'react'
import { saveCanvasViewport, type StoredCanvasViewport } from '@/features/canvas/viewport-storage'

/** 视口落盘的最小间隔：拖拽/缩放期间视口每帧都在变，写入合并到该间隔最多一次。 */
export const CANVAS_VIEWPORT_PERSIST_INTERVAL_MS = 200

interface PendingViewport {
  canvasId: string
  viewport: StoredCanvasViewport
}

function sameViewport(left: StoredCanvasViewport, right: StoredCanvasViewport): boolean {
  return left.x === right.x && left.y === right.y && left.zoom === right.zoom
}

/**
 * 把当前画布视口合并落盘到 localStorage。
 *
 * 写入发生在提交后的 effect 里（状态更新函数保持纯函数），并按 {@link CANVAS_VIEWPORT_PERSIST_INTERVAL_MS}
 * 合并连续变化：拖拽期间状态仍逐帧更新，存储只在窗口结束时写一次；切换画布与卸载会立即落盘待写值，
 * 因此「最后一次平移」不会丢。打开画布时的视口来自存档，视为已落盘，不回写。
 */
export function useCanvasViewportPersistence(
  canvasId: string | null,
  viewport: StoredCanvasViewport,
): void {
  const pendingRef = useRef<PendingViewport | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const lastFlushAtRef = useRef(0)
  /** 已经在该画布存储里的视口：用于跳过无意义的重复写入。 */
  const persistedRef = useRef<{ canvasId: string | null; viewport: StoredCanvasViewport | null }>({
    canvasId: null,
    viewport: null,
  })

  const flush = useCallback(() => {
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current)
      timerRef.current = null
    }
    const pending = pendingRef.current
    pendingRef.current = null
    if (!pending) {
      return
    }
    const persisted = persistedRef.current
    if (
      persisted.canvasId === pending.canvasId
      && persisted.viewport
      && sameViewport(persisted.viewport, pending.viewport)
    ) {
      return
    }
    saveCanvasViewport(pending.canvasId, pending.viewport)
    persistedRef.current = { canvasId: pending.canvasId, viewport: pending.viewport }
    lastFlushAtRef.current = Date.now()
  }, [])

  useEffect(() => {
    if (persistedRef.current.canvasId !== canvasId) {
      // 进入/切换画布：先把上一个画布的待写值落盘，再把当前视口当作既有存档。
      flush()
      persistedRef.current = { canvasId, viewport }
      return
    }
    if (!canvasId) {
      return
    }
    pendingRef.current = { canvasId, viewport }
    const elapsed = Date.now() - lastFlushAtRef.current
    if (elapsed >= CANVAS_VIEWPORT_PERSIST_INTERVAL_MS) {
      flush()
      return
    }
    if (timerRef.current === null) {
      timerRef.current = setTimeout(flush, CANVAS_VIEWPORT_PERSIST_INTERVAL_MS - elapsed)
    }
  }, [canvasId, flush, viewport])

  // 卸载时把最后一个待写值落盘。
  useEffect(() => () => {
    flush()
  }, [flush])
}
