import { useEffect, useRef, useState } from 'react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { ThreadInteractionPanel } from './thread-panel/ThreadInteractionPanel'
import { ThreadLink } from './ThreadLink'
import { formatThreadStatusLabel } from './thread-panel/thread-status-format'
import { threadTreeOutcome } from './thread-panel/active-thread-tree'
import './subagent-tree-panel.css'
import type { useActiveThreadTree } from './useActiveThreadTree'

/** 由绑定 thread 控制器传入已有查询；本面板没有写操作、另一个缓存或订阅。 */
export function SubagentTreePanel({ tree, currentThreadId, onClose }: {
  tree: ReturnType<typeof useActiveThreadTree>
  currentThreadId?: string
  onClose?: () => void
}) {
  const { t } = useI18n()
  const panelRef = useRef<HTMLElement>(null)
  const [selectedId, setSelectedId] = useState(currentThreadId)
  const rows = tree.historyRows
  const selected = rows.find((row) => row.node.threadId === selectedId) ?? rows[0]
  const selectedLink = (threadId: string) => Array.from(
    panelRef.current?.querySelectorAll<HTMLAnchorElement>('.subagent-card') ?? [],
  ).find((link) => link.parentElement?.dataset.threadId === threadId)
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
        if (event.defaultPrevented || event.nativeEvent.isComposing || event.keyCode === 229) {
          return
        }
        if (event.key === 'Escape' && !event.repeat) {
          event.preventDefault()
          event.stopPropagation()
          onClose?.()
        } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
          event.preventDefault()
          event.stopPropagation()
          const index = rows.findIndex((row) => row.node.threadId === selected?.node.threadId)
          const next = rows[Math.max(0, Math.min(rows.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1)))]
          if (next) {
            setSelectedId(next.node.threadId)
            selectedLink(next.node.threadId)?.focus()
          }
        } else if (event.key === 'Enter' && event.target === event.currentTarget
          && !event.ctrlKey && !event.metaKey && !event.altKey && !event.shiftKey && !event.repeat) {
          event.preventDefault()
          event.stopPropagation()
          if (selected) {
            selectedLink(selected.node.threadId)?.click()
          }
        } else if (event.key === 'Enter' && event.target instanceof Element
          && event.target.closest('.subagent-card')) {
          // 保留原生链接的键盘激活（含修饰键），但不让外层误处理为提交。
          event.stopPropagation()
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
      <ol className="subagent-card-list" onClick={(event) => {
        if (!event.ctrlKey && !event.metaKey && !event.altKey && !event.shiftKey
          && event.button === 0 && event.target instanceof Element && event.target.closest('a')) {
          onClose?.()
        }
      }}>
        {rows.map(({ node }) => {
          const outcome = threadTreeOutcome(node)
          return <li key={node.threadId} data-thread-id={node.threadId}
            data-selected={selected?.node.threadId === node.threadId}
            aria-current={node.threadId === currentThreadId ? 'true' : undefined}>
            <ThreadLink className="subagent-card" threadId={node.threadId}
              onFocus={() => setSelectedId(node.threadId)}>
              <strong>{node.agentName}</strong>
              {node.name !== 'main' && node.name !== node.agentName ? <span>{node.name}</span> : null}
              <span className="subagent-card-status" data-processing={node.processing} data-outcome={outcome}>
                {outcome ? t(`ai.runtime.agentTree.outcome.${outcome}`) : formatThreadStatusLabel(node.status, t)}
              </span>
              <span>{t('ai.runtime.agentTree.counts', { turns: node.turnCount, tools: node.toolCallCount })}</span>
              <span>{node.model.providerName}/{node.model.modelName}</span>
            </ThreadLink>
          </li>
        })}
      </ol>
    </ThreadInteractionPanel>
  )
}
