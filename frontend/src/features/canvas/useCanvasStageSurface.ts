import { useRef, useState, type RefObject } from 'react'
import type { StageMetrics } from '@/features/canvas/types'

const DEFAULT_STAGE: StageMetrics = { width: 960, height: 640, dockTop: 520 }

/** 画布舞台向 controller 暴露的命令式句柄（fit / 聚焦选区 / 缩放）与尺寸状态。 */
export interface CanvasStageSurface {
  stageMetrics: StageMetrics
  setStageMetrics: (metrics: StageMetrics) => void
  fitViewRef: RefObject<(() => void) | null>
  focusSelectionRef: RefObject<(() => void) | null>
  zoomRef: RefObject<((scale: number) => void) | null>
  stageElementRef: RefObject<HTMLElement | null>
  dockAddRef: RefObject<HTMLButtonElement | null>
  closeContextMenuRef: RefObject<(() => void) | null>
}

/**
 * 舞台相关的命令式引用与尺寸状态。
 *
 * React Flow 的 fit/focus/zoom 只能由持有其上下文的组件注册，controller 只持有这些 ref；
 * 右键菜单的关闭回调同理，由菜单 hook 注册、快捷键路径调用。
 */
export function useCanvasStageSurface(): CanvasStageSurface {
  const [stageMetrics, setStageMetrics] = useState(DEFAULT_STAGE)
  const fitViewRef = useRef<(() => void) | null>(null)
  const focusSelectionRef = useRef<(() => void) | null>(null)
  const zoomRef = useRef<((scale: number) => void) | null>(null)
  const stageElementRef = useRef<HTMLElement | null>(null)
  const dockAddRef = useRef<HTMLButtonElement | null>(null)
  const closeContextMenuRef = useRef<(() => void) | null>(null)

  return {
    stageMetrics,
    setStageMetrics,
    fitViewRef,
    focusSelectionRef,
    zoomRef,
    stageElementRef,
    dockAddRef,
    closeContextMenuRef,
  }
}
