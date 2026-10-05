import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { RefreshCw } from 'lucide-react'
import { harnessService } from '@/shared/api/harness-service'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { formatThreadStatusLabel } from '@/features/ai/runtime/thread-panel/thread-status-format'
import {
  THREAD_AGENT_TREE_REFETCH_INTERVAL_MS,
  formatThreadModelLabel,
  orderThreadAgentTree,
} from '@/features/ai/runtime/thread-panel/thread-agent-tree'

export function ThreadAgentTreePanel({
  threadId,
  panelId,
}: {
  threadId: string
  panelId: string
}) {
  const { t } = useI18n()
  const treeQuery = useQuery({
    queryKey: queryKeys.threads.tree(threadId),
    queryFn: async () => {
      const nodes = await harnessService.getThreadTree(threadId)
      // 非法树不写入缓存；React Query 保留上一次成功结果，并单独暴露失败。
      orderThreadAgentTree(nodes, threadId)
      return nodes
    },
    enabled: Boolean(threadId),
    refetchInterval: THREAD_AGENT_TREE_REFETCH_INTERVAL_MS,
  })
  const refreshFailed = treeQuery.isError && treeQuery.data != null
  const rows = useMemo(
    () => treeQuery.data == null ? [] : orderThreadAgentTree(treeQuery.data, threadId),
    [threadId, treeQuery.data],
  )

  return (
    <section
      id={panelId}
      className="thread-agent-tree"
      aria-label={t('ai.runtime.agentTree.panel')}
      data-thread-id={threadId}
    >
      <div className="thread-agent-tree-toolbar">
        <h3>{t('ai.runtime.agentTree.panel')}</h3>
        <button
          type="button"
          className="ghost-btn thread-agent-tree-refresh"
          onClick={() => void treeQuery.refetch()}
          disabled={treeQuery.isFetching}
        >
          <RefreshCw aria-hidden="true" />
          {t('ai.runtime.agentTree.refresh')}
        </button>
      </div>
      {treeQuery.isLoading ? (
        <p className="thread-agent-tree-state">{t('ai.runtime.agentTree.loading')}</p>
      ) : null}
      {treeQuery.isError ? (
        <div className="thread-agent-tree-state danger" role="alert">
          <p>{refreshFailed ? t('ai.runtime.agentTree.refreshFailed') : t('ai.runtime.agentTree.error')}</p>
          <button type="button" className="ghost-btn" onClick={() => void treeQuery.refetch()}>
            {t('ai.runtime.agentTree.retry')}
          </button>
        </div>
      ) : null}
      {!treeQuery.isLoading && !treeQuery.isError && rows.length === 0 ? (
        <p className="thread-agent-tree-state">{t('ai.runtime.agentTree.empty')}</p>
      ) : null}
      {rows.length > 0 ? (
        <ol className="thread-agent-tree-list">
          {rows.map((row) => {
            const statusLabel = row.outcome
              ? t(`ai.runtime.agentTree.outcome.${row.outcome}`)
              : formatThreadStatusLabel(row.node.status, t)
            const modelLabel = formatThreadModelLabel(row.node)
            return (
              <li
                key={row.node.threadId}
                className={[
                  'thread-agent-tree-row',
                  `is-${row.role}`,
                  row.node.processing ? 'is-processing' : '',
                  row.outcome ? `is-${row.outcome.toLowerCase()}` : '',
                ].filter(Boolean).join(' ')}
                style={{ paddingInlineStart: `${12 + row.depth * 16}px` }}
                aria-current={row.node.threadId === threadId ? 'true' : undefined}
                data-depth={row.depth}
                data-thread-id={row.node.threadId}
                data-processing={row.node.processing ? 'true' : 'false'}
              >
                <div className="thread-agent-tree-main">
                  <a
                    className="thread-agent-tree-name"
                    href={`/threads/${encodeURIComponent(row.node.threadId)}`}
                    target="_blank"
                    rel="noopener noreferrer"
                    title={row.node.threadId}
                  >
                    {row.node.name}
                  </a>
                  {row.role === 'root' ? (
                    <span className="thread-agent-tree-badge">{t('ai.runtime.agentTree.root')}</span>
                  ) : null}
                  {row.node.threadId === threadId ? (
                    <span className="thread-agent-tree-badge is-current">{t('ai.runtime.agentTree.current')}</span>
                  ) : null}
                </div>
                <p className="thread-agent-tree-secondary">
                  <span>{row.node.agentName}</span>
                  <span className="thread-agent-tree-model" title={modelLabel}>{modelLabel}</span>
                </p>
                <p className="thread-agent-tree-meta">
                  <span
                    className={[
                      'thread-agent-tree-status',
                      row.node.processing ? 'is-processing' : '',
                      row.outcome ? `is-${row.outcome.toLowerCase()}` : '',
                    ].filter(Boolean).join(' ')}
                  >
                    {statusLabel}
                  </span>
                  <span>{t('ai.runtime.agentTree.turns', { count: row.node.turnCount })}</span>
                  <span>{t('ai.runtime.agentTree.toolCalls', { count: row.node.toolCallCount })}</span>
                </p>
              </li>
            )
          })}
        </ol>
      ) : null}
    </section>
  )
}
