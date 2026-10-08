import { useCallback, useEffect, useLayoutEffect, useMemo, useRef } from 'react'
import type { ThreadProjection } from './useThreadProjection'
import type { useBoundThreadPanelViews } from './useBoundThreadPanelViews'
import type { PaneTarget } from './agent-pane'

export function paneTargetViewKey(target: PaneTarget): string {
  return target.kind === 'BOUND_THREAD' ? `thread:${target.threadId}`
    : target.kind === 'NEW_THREAD_DRAFT'
      ? `branch:${target.sessionId}:${target.startEntryId}:${target.threadName}` : ''
}

export type PresentationAction = 'debug' | 'close-debug' | 'restore-focus' | 'subagent' | 'parent'

/** 临时查看层报告；与根的路由/草稿/busy 摘要分离，不缓存 controller 或节点。 */
export interface ThreadPresentation {
  viewKey: string
  threadId: string | null
  mode: 'conversation' | 'debug'
  name: string | null
  parentThreadId: string | null
  parentHref: string | null
  identity: string | null
  act: (viewKey: string, action: PresentationAction, trigger?: HTMLElement) => void
}

export function threadIdentity(projection: Pick<ThreadProjection, 'thread' | 'models'>): string | null {
  const settings = projection.thread?.branchSettings
  if (!settings) {
    return null
  }
  const model = settings.model
  const config = projection.models.find((item) =>
    item.providerName === model?.providerName && item.name === model?.modelName)?.config
  const effort = config?.abilities.reasoning
    ? config.variants.find((item) => item.id === model?.variant)?.reasoningEffort
    : null
  return [settings.agentName, model ? `${model.providerName}/${model.modelName}` : null, effort]
    .filter(Boolean).join(' · ')
}

/** 动作稳定但只使用 commit 后最新查看层；过期身份和隐藏层不能执行。 */
export function useThreadPresentation({
  views, projection, threadId, selected, active, onReport, onSubagent, onParent, onRestoreFocus,
}: {
  views: Pick<ReturnType<typeof useBoundThreadPanelViews>, 'viewKey' | 'mode' | 'switchMode'>
  projection: Pick<ThreadProjection, 'thread' | 'models'>
  threadId: string
  selected: boolean
  active: boolean
  onReport?: (report: ThreadPresentation) => void
  onSubagent: () => void
  onParent?: (parentThreadId: string) => void
  onRestoreFocus?: () => void
}) {
  const latest = useRef({ views, projection, threadId, selected, active, onSubagent, onParent, onRestoreFocus })
  const returnFocus = useRef<HTMLElement | null>(null)
  const returnFocusKey = useRef<string | null>(null)
  const restoreFocus = useRef(false)
  const mounted = useRef(false)
  const lastKey = useRef(views.viewKey)
  useLayoutEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])
  useLayoutEffect(() => {
    if (lastKey.current !== views.viewKey) {
      lastKey.current = views.viewKey
      restoreFocus.current = false
      returnFocus.current = null
      returnFocusKey.current = null
    }
    latest.current = { views, projection, threadId, selected, active, onSubagent, onParent, onRestoreFocus }
  })
  const act = useCallback((viewKey: string, action: PresentationAction, trigger?: HTMLElement) => {
    const current = latest.current
    if (!mounted.current || !current.active || !current.selected || current.views.viewKey !== viewKey) {
      return
    }
    const thread = current.projection.thread?.threadId === current.threadId
      ? current.projection.thread : null
    if (action === 'close-debug') {
      restoreFocus.current = true
      current.views.switchMode('conversation')
    } else if (action === 'restore-focus') {
      if (current.views.mode !== 'conversation' || !restoreFocus.current) {
        return
      }
      restoreFocus.current = false
      if (current.onRestoreFocus) {
        current.onRestoreFocus()
      } else {
        const element = returnFocus.current
        if (returnFocusKey.current === viewKey && element?.isConnected && !element.closest('[hidden], [inert]')) {
          element.focus()
        }
      }
    } else if (action === 'debug') {
      if (thread || current.threadId === '') {
        returnFocus.current = trigger ?? (document.activeElement instanceof HTMLElement ? document.activeElement : null)
        returnFocusKey.current = viewKey
        current.views.switchMode('debug')
      }
    } else if (thread && action === 'subagent') {
      current.onSubagent()
    } else if (thread?.parentThreadId && action === 'parent') {
      current.onParent?.(thread.parentThreadId)
    }
  }, [])
  useEffect(() => {
    if (!active || !selected) {
      restoreFocus.current = false
    }
  }, [views.viewKey, active, selected])
  const thread = projection.thread?.threadId === threadId ? projection.thread : null
  const identity = thread ? threadIdentity(projection) : null
  const parentHref = thread?.parentThreadId && !onParent
    ? `/threads/${encodeURIComponent(thread.parentThreadId)}` : null
  const report = useMemo<ThreadPresentation>(() => ({
    viewKey: views.viewKey, threadId: thread?.threadId ?? null, mode: views.mode,
    name: thread?.parentThreadId && thread.name === 'main' ? null : thread?.name ?? null,
    parentThreadId: thread?.parentThreadId ?? null, parentHref, identity, act,
  }), [views.viewKey, views.mode, thread?.threadId, thread?.name, thread?.parentThreadId, parentHref, identity, act])
  const callback = useRef(onReport)
  useLayoutEffect(() => { callback.current = onReport })
  useEffect(() => {
    if (selected) {
      callback.current?.(report)
    }
  }, [selected, report])
  return report
}
