import { useEffect, useRef } from 'react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { ThreadInteractionPanel } from './thread-panel/ThreadInteractionPanel'
import { ThreadTreeRows } from './thread-panel/ThreadTreeRows'
import type { useActiveThreadTree } from './useActiveThreadTree'

/** 由绑定 thread 控制器传入已有查询；本面板没有写操作、另一个缓存或订阅。 */
export function SubagentTreePanel({ tree, currentThreadId, onClose }: {
  tree: ReturnType<typeof useActiveThreadTree>
  currentThreadId?: string
  onClose?: () => void
}) {
  const { t } = useI18n()
  const panelRef = useRef<HTMLElement>(null)
  useEffect(() => {
    const returnFocus = document.activeElement
    panelRef.current?.focus()
    return () => {
      if (returnFocus instanceof HTMLElement && returnFocus.isConnected && !returnFocus.closest('[hidden], [inert]')) {
        returnFocus.focus()
      }
    }
  }, [])
  return (
    <ThreadInteractionPanel className="subagent-tree-panel" title={t('ai.runtime.agentTree.historyTitle')}
      panelRef={panelRef} onClose={() => onClose?.()} onKeyDown={(event) => {
        if (event.key === 'Escape' && !event.defaultPrevented
          && !event.repeat && !event.nativeEvent.isComposing && event.keyCode !== 229) {
          event.preventDefault()
          event.stopPropagation()
          onClose?.()
        }
      }}>
      {tree.isError ? (
        <div role="alert" className="thread-tree-state is-error">
          <p>{t(tree.refreshFailed ? 'ai.runtime.agentTree.refreshFailed' : 'ai.runtime.agentTree.error')}</p>
          <Button variant="ghost" onClick={tree.refetch}>{t('ai.runtime.agentTree.retry')}</Button>
        </div>
      ) : null}
      {tree.isLoading ? <div role="status" className="thread-tree-state">{t('ai.runtime.thread.loading')}</div> : null}
      {!tree.isLoading && !tree.isError && tree.historyRows.length === 0
        ? <div className="thread-tree-state">{t('ai.runtime.agentTree.emptyHistory')}</div> : null}
      <div onClick={(event) => {
        if (event.target instanceof Element && event.target.closest('a')) {
          onClose?.()
        }
      }}>
        <ThreadTreeRows rows={tree.historyRows} currentThreadId={currentThreadId} />
      </div>
    </ThreadInteractionPanel>
  )
}
