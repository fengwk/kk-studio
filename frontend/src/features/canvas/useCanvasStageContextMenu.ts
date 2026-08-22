import { useCallback, useEffect, useRef, useState, type MouseEvent } from 'react'
import {
  buildContextMenuTarget,
  contextMenuTargetKey,
  sameIdList,
  type ContextMenuTarget,
} from '@/features/canvas/canvas-stage-model'
import type { CanvasSnapshot } from '@/features/canvas/domain'
import type { CanvasFlowNode } from '@/features/canvas/projection'
import type { CanvasFunctionModelDTO } from '@/shared/api/contracts/studio'

export interface CanvasContextMenuState {
  x: number
  y: number
  target: ContextMenuTarget
}

export interface CanvasStageContextMenuApi {
  contextMenu: CanvasContextMenuState | null
  contextMenuKey: string | null
  closeContextMenu: () => void
  openNodeContextMenu: (event: MouseEvent, flowNode: CanvasFlowNode) => void
  openSelectionContextMenu: (event: MouseEvent, selectedNodes: CanvasFlowNode[]) => void
  handleSelectionChange: (nodeIds: string[]) => void
}

export interface CanvasStageContextMenuOptions {
  snapshot: CanvasSnapshot | null
  models: readonly CanvasFunctionModelDTO[]
  selectedIds: readonly string[]
  setSelection: (nodeIds: string[]) => void
  closeContextMenuRef: React.MutableRefObject<(() => void) | null>
}

/**
 * CanvasStage 右键菜单的编排 hook：独占菜单 state、打开时选区快照、
 * 全局关闭回调注册、Escape 关闭与选区变化时 stale 菜单关闭。
 * 纯构建逻辑在 canvas-stage-model，本 hook 只做状态与副作用接线。
 */
export function useCanvasStageContextMenu({
  snapshot,
  models,
  selectedIds,
  setSelection,
  closeContextMenuRef,
}: CanvasStageContextMenuOptions): CanvasStageContextMenuApi {
  const [contextMenu, setContextMenu] = useState<CanvasContextMenuState | null>(null)
  const contextMenuSelectionRef = useRef<string[] | null>(null)

  const closeContextMenu = useCallback(() => {
    contextMenuSelectionRef.current = null
    setContextMenu(null)
  }, [])

  // Canvas surface 的全局 Escape（capture 相位消费）通过 controller 关闭全部
  // overlay；菜单状态在本 hook，注册关闭回调供 closeOverlays 调用。
  useEffect(() => {
    closeContextMenuRef.current = closeContextMenu
    return () => {
      closeContextMenuRef.current = null
    }
  }, [closeContextMenu, closeContextMenuRef])

  const openContextMenu = useCallback((event: MouseEvent, nodeIds: string[]) => {
    event.preventDefault()
    if (!snapshot) {
      return
    }
    const target = buildContextMenuTarget(snapshot, nodeIds, models)
    if (!target) {
      return
    }
    contextMenuSelectionRef.current = nodeIds
    setSelection(nodeIds)
    setContextMenu({ x: event.clientX, y: event.clientY, target })
  }, [models, setSelection, snapshot])

  const openNodeContextMenu = useCallback((event: MouseEvent, flowNode: CanvasFlowNode) => {
    const isGroup = flowNode.data.kind === 'group'
    const nodeIds = !isGroup && flowNode.selected && selectedIds.includes(flowNode.id)
      ? [...selectedIds]
      : [flowNode.id]
    openContextMenu(event, nodeIds)
  }, [openContextMenu, selectedIds])

  const openSelectionContextMenu = useCallback((event: MouseEvent, selectedNodes: CanvasFlowNode[]) => {
    const nodeIds = selectedNodes.map((node) => node.id)
    if (nodeIds.length === 0) {
      setContextMenu(null)
      return
    }
    openContextMenu(event, nodeIds)
  }, [openContextMenu])

  // 打开期间 Escape 关闭菜单（与 CanvasContextMenu 内部的菜单级 Escape 分工：
  // 这里兜底任何仍持有焦点的窗口级 Escape）。
  useEffect(() => {
    if (!contextMenu) {
      return
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setContextMenu(null)
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [contextMenu])

  const handleSelectionChange = useCallback((nodeIds: string[]) => {
    // 选区被外部改变（点击/框选/删除）时关闭右键菜单；右键打开时自身触发的
    // onSelectionChange 与快照一致，不关闭。
    const openedSelection = contextMenuSelectionRef.current
    if (
      contextMenuSelectionRef.current !== null
      && openedSelection
      && !sameIdList(openedSelection, nodeIds)
    ) {
      contextMenuSelectionRef.current = null
      setContextMenu(null)
    }
  }, [])

  const contextMenuKey = contextMenu ? contextMenuTargetKey(contextMenu.target) : null

  return {
    contextMenu,
    contextMenuKey,
    closeContextMenu,
    openNodeContextMenu,
    openSelectionContextMenu,
    handleSelectionChange,
  }
}
