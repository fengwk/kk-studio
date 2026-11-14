import { useEffect, useRef, useState, type RefObject } from 'react'
import type { DebugInspectorSelection } from '@/features/thread/runtime/thread-panel/ThreadDebugInspector'
import type { DebugViewTab } from '@/features/thread/runtime/thread-panel/thread-debug-view'

/**
 * Debug 视图的详情来源与页签状态：哪一列可见、从哪一列进入详情、关闭后焦点回到哪里。
 *
 * 详情有三种来源（事件行、inspector chip、历史预览），关闭时必须切回来源页签并
 * 把可见焦点还给触发元素；这一状态机与三列的渲染无关，因此独立成一个 hook。
 */
export function useDebugViewNavigation({
  selectedEventId,
  debugSelection,
  onSelectedEventIdChange,
  onSelectInspector,
  onDismissHistoricalPreview,
  layoutMode,
  bodyRef,
}: {
  selectedEventId: string | null
  debugSelection: DebugInspectorSelection | null
  onSelectedEventIdChange: (eventId: string | null) => void
  onSelectInspector?: (selection: DebugInspectorSelection | null) => void
  onDismissHistoricalPreview?: () => void
  layoutMode: 'wide' | 'narrow'
  bodyRef: RefObject<HTMLDivElement | null>
}) {
  const detailCloseBtnRef = useRef<HTMLButtonElement>(null)
  const pendingDetailFocusRef = useRef(false)
  // 记录唤起详情的来源以及触发元素，以便关闭详情时切回并恢复可见焦点
  const lastDetailSourceRef = useRef<'events' | 'preview'>(
    debugSelection != null ? 'preview' : 'events',
  )
  const lastFocusedTriggerRef = useRef<HTMLElement | null>(null)

  // Tab 状态 (默认事件列)
  const [activeTab, setActiveTab] = useState<DebugViewTab>(() => {
    if (debugSelection != null || selectedEventId != null) {
      return 'detail'
    }
    return 'events'
  })

  useEffect(() => {
    pendingDetailFocusRef.current = debugSelection != null || selectedEventId != null
    if (debugSelection != null || selectedEventId != null) {
      setActiveTab('detail')
    }
  }, [debugSelection, selectedEventId])

  useEffect(() => {
    // 异步初选可能挂载在隐藏列中，且初始 listbox 聚焦会覆盖子组件聚焦。
    // 等详情页签真正显示后处理一次；roving 导航不会再从页签抢焦点。
    if (activeTab === 'detail' && pendingDetailFocusRef.current) {
      pendingDetailFocusRef.current = false
      if (layoutMode === 'narrow') {
        detailCloseBtnRef.current?.focus({ preventScroll: true })
      }
    }
  }, [activeTab, layoutMode, selectedEventId, debugSelection])

  function handleCloseDetail() {
    if (debugSelection != null) {
      onSelectInspector?.(null)
    }
    if (selectedEventId != null) {
      onSelectedEventIdChange(null)
    }
    const nextTab = lastDetailSourceRef.current === 'preview' ? 'preview' : 'events'
    setActiveTab(nextTab)

    // 恢复可见焦点到触发元素
    requestAnimationFrame(() => {
      if (nextTab === 'preview') {
        const trigger = lastFocusedTriggerRef.current
        if (trigger && document.body.contains(trigger)) {
          trigger.focus({ preventScroll: true })
          return
        }
      }
      // 默认（以及来自事件时）让 listbox 获得焦点，绝不尝试 focus 没有 tabIndex 的 option div
      bodyRef.current?.focus({ preventScroll: true })
    })
  }

  function handleSelectEvent(id: string) {
    lastDetailSourceRef.current = 'events'
    setActiveTab('detail')
    if (layoutMode === 'wide') {
      // wide 模式下保持 listbox 获焦，便于后续连续按 Arrow 导航
      bodyRef.current?.focus({ preventScroll: true })
    }
    onSelectInspector?.(null)
    // 选中新的历史事件即结束上一次历史请求预览，避免张冠李戴。
    onDismissHistoricalPreview?.()
    onSelectedEventIdChange(id)
  }

  function handleSelectInspector(
    selection: DebugInspectorSelection | null,
    triggerElement: HTMLElement | null,
  ) {
    if (selection != null) {
      lastFocusedTriggerRef.current = triggerElement
      lastDetailSourceRef.current = 'preview'
      setActiveTab('detail')
      onSelectedEventIdChange(null)
    }
    onSelectInspector?.(selection)
  }

  return {
    activeTab,
    setActiveTab,
    detailCloseBtnRef,
    handleCloseDetail,
    handleSelectEvent,
    handleSelectInspector,
  }
}
