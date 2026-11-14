import { useCallback, useEffect, useRef, type RefObject } from 'react'
import type { CanvasFlowNode } from '@/features/canvas/projection'
import type { StageMetrics } from '@/features/canvas/types'
import { preserveCanvasWorldCenter } from '@/features/canvas/viewport-framing'
import type { StoredCanvasViewport } from '@/features/canvas/viewport-storage'

const INITIAL_FIT = { padding: 0.18, maxZoom: 1.6, duration: 0 }
const FOCUS_SELECTION_FIT = { padding: 0.22, maxZoom: 1.8, duration: 0 }

/** React Flow 的 fitView 只按 options 求值；这里只需要它能接收这些字段并返回是否成功。 */
type FitView = (options?: {
  padding?: number
  maxZoom?: number
  duration?: number
  nodes?: CanvasFlowNode[]
}) => Promise<boolean>

export interface CanvasStageViewportOptions {
  canvasId: string | null
  viewport: StoredCanvasViewport
  stageWidth: StageMetrics['width']
  containerRef: RefObject<HTMLElement | null>
  fitView: FitView
  getViewport: () => StoredCanvasViewport
  zoomTo: (zoom: number) => Promise<boolean>
  setFlowViewport: (viewport: StoredCanvasViewport) => Promise<unknown>
  setViewport: (viewport: StoredCanvasViewport) => void
  nodes: CanvasFlowNode[]
  nodesInitialized: boolean
  initialFitPending: boolean
  completeInitialFit: () => void
  fitViewRef: RefObject<(() => void) | null>
  focusSelectionRef: RefObject<(() => void) | null>
  zoomRef: RefObject<((scale: number) => void) | null>
}

export interface CanvasStageViewportActions {
  /** React Flow 拖动/缩放时把视口写回运行时状态。 */
  emitViewport: (viewport: StoredCanvasViewport) => void
}

/**
 * Stage 与 React Flow 的视口接线：外部视口变化下发到 flow、容器变宽时保持 world center、
 * 首帧 fit、以及工具栏/键盘用的 fit/focus/zoom 回调注册。
 *
 * 所有 ref 与 flow 的写入顺序保持与内联实现一致：先更新 lastViewport，再下发 flow，最后写状态。
 */
export function useCanvasStageViewport({
  canvasId,
  viewport,
  stageWidth,
  containerRef,
  fitView,
  getViewport,
  zoomTo,
  setFlowViewport,
  setViewport,
  nodes,
  nodesInitialized,
  initialFitPending,
  completeInitialFit,
  fitViewRef,
  focusSelectionRef,
  zoomRef,
}: CanvasStageViewportOptions): CanvasStageViewportActions {
  const lastViewportRef = useRef(viewport)
  const initialFitStartedRef = useRef(false)
  const mountedRef = useRef(true)
  const flowWidthRef = useRef<number | null>(null)

  useEffect(() => {
    const next = viewport
    const previous = lastViewportRef.current
    if (
      Math.abs(next.x - previous.x) < 0.001
      && Math.abs(next.y - previous.y) < 0.001
      && Math.abs(next.zoom - previous.zoom) < 0.001
    ) {
      return
    }
    lastViewportRef.current = next
    void setFlowViewport(next)
  }, [setFlowViewport, viewport])

  // 面板/拖拽调宽平移 viewport 时保持 world center；zoom 不得隐式改变。
  // （宽度不变或首帧时跳过。）
  useEffect(() => {
    const width = containerRef.current?.getBoundingClientRect().width ?? stageWidth
    const previousWidth = flowWidthRef.current
    flowWidthRef.current = width
    if (previousWidth === null) {
      return
    }
    if (Math.abs(width - previousWidth) < 1) {
      return
    }
    const next = preserveCanvasWorldCenter(viewport, previousWidth, width)
    lastViewportRef.current = next
    void setFlowViewport(next)
    setViewport(next)
  }, [containerRef, setFlowViewport, setViewport, stageWidth, viewport])

  const readFlowViewport = useCallback((): StoredCanvasViewport => {
    const current = getViewport()
    return { x: current.x, y: current.y, zoom: current.zoom }
  }, [getViewport])

  const syncViewport = useCallback(() => {
    const next = readFlowViewport()
    lastViewportRef.current = next
    setViewport(next)
  }, [readFlowViewport, setViewport])

  const emitViewport = useCallback((next: StoredCanvasViewport) => {
    const copied = { x: next.x, y: next.y, zoom: next.zoom }
    lastViewportRef.current = copied
    setViewport(copied)
  }, [setViewport])

  useEffect(() => {
    initialFitStartedRef.current = false
  }, [canvasId])

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  useEffect(() => {
    if (
      !initialFitPending
      || !nodesInitialized
      || nodes.length === 0
      || initialFitStartedRef.current
    ) {
      return
    }
    initialFitStartedRef.current = true
    void fitView(INITIAL_FIT).then((fitted) => {
      if (!mountedRef.current) {
        return
      }
      if (!fitted) {
        initialFitStartedRef.current = false
        return
      }
      const next = readFlowViewport()
      lastViewportRef.current = next
      void setFlowViewport(next)
      setViewport(next)
      completeInitialFit()
    })
  }, [
    completeInitialFit,
    fitView,
    initialFitPending,
    nodes.length,
    nodesInitialized,
    readFlowViewport,
    setFlowViewport,
    setViewport,
  ])

  useEffect(() => {
    fitViewRef.current = () => {
      void fitView(INITIAL_FIT).then((fitted) => {
        if (!fitted) {
          return
        }
        const next = readFlowViewport()
        lastViewportRef.current = next
        void setFlowViewport(next)
        setViewport(next)
      })
    }
    focusSelectionRef.current = () => {
      const selected = nodes.filter((node) => node.selected)
      void fitView({
        ...FOCUS_SELECTION_FIT,
        nodes: selected.length > 0 ? selected : undefined,
      }).then(syncViewport)
    }
    zoomRef.current = (zoom) => {
      void zoomTo(zoom).then(syncViewport)
    }
    return () => {
      fitViewRef.current = null
      focusSelectionRef.current = null
      zoomRef.current = null
    }
  }, [
    fitView,
    fitViewRef,
    focusSelectionRef,
    nodes,
    readFlowViewport,
    setFlowViewport,
    setViewport,
    syncViewport,
    zoomRef,
    zoomTo,
  ])

  return { emitViewport }
}
