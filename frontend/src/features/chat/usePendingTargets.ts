import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation, useSearchParams } from 'react-router'
import { samePaneTarget, type PaneTarget } from '@/features/thread/runtime/agent-pane'

export interface PendingTargetsController {
  /** 按 pane 路由的待应用目标：deep-link 与分支草稿共用这一队列。 */
  pendingTargets: Record<string, PaneTarget>
  pendingTargetsRef: React.RefObject<Record<string, PaneTarget>>
  setPendingTargets: React.Dispatch<React.SetStateAction<Record<string, PaneTarget>>>
  /** 目标 pane 成功消费后清空该 pane 的待应用目标；命中 deep-link query 时一并清 URL。 */
  handleTargetConsumed: (paneId: string, consumed: PaneTarget) => void
}

/**
 * 待应用目标的唯一队列。
 *
 * 每次导航固定目标 pane：deep-link query 落到导航发生时的焦点 pane，之后焦点移动
 * 不会把同一个请求转移到别的 pane，因此不需要跨 pane 的 storage 旁路。
 */
export function usePendingTargets(focusedPaneId: string): PendingTargetsController {
  const [searchParams, setSearchParams] = useSearchParams()
  const location = useLocation()
  const targetThreadId = searchParams.get('thread')
  const [pendingTargets, setPendingTargets] = useState<Record<string, PaneTarget>>(
    () => (targetThreadId
      ? { [focusedPaneId]: { kind: 'BOUND_THREAD', threadId: targetThreadId } }
      : {}),
  )
  const pendingTargetsRef = useRef(pendingTargets)
  useEffect(() => { pendingTargetsRef.current = pendingTargets }, [pendingTargets])
  const [deepLinkOwner, setDeepLinkOwner] = useState(() => ({
    navigationKey: location.key,
    paneId: focusedPaneId,
  }))

  // 每次导航固定目标 pane，清除 query 前的焦点变化不能转移请求。
  if (deepLinkOwner.navigationKey !== location.key) {
    setDeepLinkOwner({
      navigationKey: location.key,
      paneId: focusedPaneId,
    })
    if (targetThreadId) {
      setPendingTargets((current) => ({
        ...current,
        [focusedPaneId]: { kind: 'BOUND_THREAD', threadId: targetThreadId },
      }))
    }
  }

  const handleTargetConsumed = useCallback((paneId: string, consumed: PaneTarget) => {
    setPendingTargets((current) => {
      const pending = current[paneId]
      if (pending == null || !samePaneTarget(pending, consumed)) {
        return current
      }
      const next = { ...current }
      delete next[paneId]
      return next
    })
    const currentThread = searchParams.get('thread')
    if (currentThread && consumed.kind === 'BOUND_THREAD' && consumed.threadId === currentThread) {
      const nextParams = new URLSearchParams(searchParams)
      nextParams.delete('thread')
      setSearchParams(nextParams, { replace: true })
    }
  }, [searchParams, setSearchParams])

  return { pendingTargets, pendingTargetsRef, setPendingTargets, handleTargetConsumed }
}
