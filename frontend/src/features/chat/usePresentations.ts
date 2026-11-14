import { useCallback, useLayoutEffect, useState, type RefObject } from 'react'
import type { ChatPaneState } from '@/features/chat/chat-pane-state'
import { visibleChatPanes } from '@/features/chat/chat-pane-state'
import type { ThreadPresentation } from '@/features/thread/runtime/thread-presentation'

export interface DebugSource {
  paneId: string
  viewKey: string
}

export interface PresentationsController {
  /** 顶栏与主体共同使用的当前视图；debug 模式下取 debug 目标 pane 的视图。 */
  currentView: ThreadPresentation | null
  /** 非 null 表示当前只显示该 pane 的 debug 视图。 */
  debugSource: DebugSource | null
  /** pane 呈现报告的唯一入口：更新该 pane 呈现，并维护 debug 视图来源。 */
  handlePresentation: (paneId: string, report: ThreadPresentation | null) => void
  closeDebug: () => void
}

/**
 * pane 呈现状态与 debug 视图。
 *
 * debug 视图只在目标 pane 仍是焦点、仍可见、且报告确实是该 debug view 时保持；
 * 焦点/可见性判定读的是提交时的 pane 状态镜像，而不是本帧渲染参数。
 */
export function usePresentations(
  focusedPaneId: string,
  paneStateRef: RefObject<ChatPaneState>,
  focusedPaneVisible: boolean,
): PresentationsController {
  const [presentations, setPresentations] = useState<Record<string, ThreadPresentation | null>>({})
  const [debugSource, setDebugSource] = useState<DebugSource | null>(null)
  const currentView = presentations[debugSource?.paneId ?? focusedPaneId] ?? null

  const handlePresentation = useCallback((paneId: string, report: ThreadPresentation | null) => {
    setPresentations((current) => (
      current[paneId] === report ? current : { ...current, [paneId]: report }
    ))
    setDebugSource((current) => {
      if (current != null) {
        if (current.paneId !== paneId) {
          return current
        }
        const debugView = debugViewOf(report)
        return debugView != null && debugView === current.viewKey ? current : null
      }
      const debugView = debugViewOf(report)
      return debugView != null
        && paneStateRef.current.focusedPaneId === paneId
        && visibleChatPanes(paneStateRef.current).some((pane) => pane.id === paneId)
        ? { paneId, viewKey: debugView } : null
    })
  }, [paneStateRef])

  const closeDebug = () => {
    currentView?.act(debugSource?.viewKey ?? currentView.viewKey, 'close-debug')
  }

  useLayoutEffect(() => {
    if (debugSource == null && currentView?.mode === 'conversation' && focusedPaneVisible) {
      currentView.act(currentView.viewKey, 'restore-focus')
    }
  }, [currentView, debugSource, focusedPaneVisible])

  return { currentView, debugSource, handlePresentation, closeDebug }
}

/**
 * 可作为 debug 覆盖层打开的 viewKey；不是 debug 模式、或指向无法定位的 thread 时返回 null
 * （`thread:` 前缀表示 thread 视图，此时 threadId 必须存在）。
 */
function debugViewOf(report: ThreadPresentation | null): string | null {
  if (report?.mode !== 'debug') {
    return null
  }
  if (report.viewKey.startsWith('thread:') && report.threadId == null) {
    return null
  }
  return report.viewKey
}
