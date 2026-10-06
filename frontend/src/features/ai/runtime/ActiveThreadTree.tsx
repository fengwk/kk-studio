import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { harnessService } from '@/shared/api/harness-service'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { ThreadLink } from '@/features/ai/runtime/ThreadLink'
import { ThreadWidgetPanel } from '@/features/ai/runtime/thread-panel/ThreadWidgetPanel'
import { formatThreadStatusLabel } from '@/features/ai/runtime/thread-panel/thread-status-format'
import {
  ACTIVE_THREAD_TREE_REFETCH_INTERVAL_MS,
  projectActiveThreadTree,
  threadTreeOutcome,
} from '@/features/ai/runtime/thread-panel/active-thread-tree'

/**
 * 根面板自动活跃树：读完整执行树，只保留 processing 节点及其祖先，逐行单行整行点击。
 *
 * 组件保持挂载并持续轮询（根本地空闲也继续查询后代）；没有活跃后代时不渲染空壳。
 * 查询失败不伪装成最新数据：无数据时给出错误与重试，有旧数据时保留并标记刷新失败。
 */
export function ActiveThreadTree({
  rootThreadId,
  currentThreadId,
}: {
  rootThreadId: string
  currentThreadId?: string
}) {
  const { t } = useI18n()
  const treeQuery = useQuery({
    queryKey: queryKeys.threads.tree(rootThreadId),
    queryFn: async () => {
      const nodes = await harnessService.getThreadTree(rootThreadId)
      // 非法树不写入缓存；React Query 保留上一次成功结果，并单独暴露失败。
      projectActiveThreadTree(nodes)
      return nodes
    },
    enabled: Boolean(rootThreadId),
    refetchInterval: ACTIVE_THREAD_TREE_REFETCH_INTERVAL_MS,
  })
  const rows = useMemo(
    () => (treeQuery.data == null ? [] : projectActiveThreadTree(treeQuery.data)),
    [treeQuery.data],
  )
  const activeCount = useMemo(
    () => rows.reduce((count, row) => (row.node.processing ? count + 1 : count), 0),
    [rows],
  )
  const refreshFailed = treeQuery.isError && treeQuery.data != null

  // 无活跃后代且查询正常：不渲染空壳（查询仍在后台继续）。
  if (rows.length === 0 && !treeQuery.isError) {
    return null
  }

  return (
    <ThreadWidgetPanel
      className="active-thread-tree"
      title={t('ai.runtime.agentTree.panel')}
      actions={rows.length > 0
        ? <span className="active-thread-tree-count">{t('ai.runtime.agentTree.activeCount', { count: activeCount })}</span>
        : null}
    >
      {treeQuery.isError ? (
        <div className="active-thread-tree-state is-error" role="alert">
          <p>{refreshFailed ? t('ai.runtime.agentTree.refreshFailed') : t('ai.runtime.agentTree.error')}</p>
          <button type="button" className="ghost-btn" onClick={() => void treeQuery.refetch()}>
            {t('ai.runtime.agentTree.retry')}
          </button>
        </div>
      ) : null}
      {rows.length > 0 ? (
        <ol className="active-thread-tree-list">
          {rows.map((row) => {
            const outcome = threadTreeOutcome(row.node)
            const statusLabel = outcome
              ? t(`ai.runtime.agentTree.outcome.${outcome}`)
              : formatThreadStatusLabel(row.node.status, t)
            return (
              <li
                key={row.node.threadId}
                className="active-thread-tree-row"
                data-depth={row.depth}
                data-thread-id={row.node.threadId}
                data-processing={row.node.processing ? 'true' : 'false'}
                aria-current={row.node.threadId === currentThreadId ? 'true' : undefined}
              >
                <ThreadLink
                  threadId={row.node.threadId}
                  className="active-thread-tree-link"
                  title={row.node.threadId}
                  style={{ paddingInlineStart: `${10 + row.depth * 14}px` }}
                >
                  <span className="active-thread-tree-name">{row.node.name}</span>
                  <span className="active-thread-tree-agent">{row.node.agentName}</span>
                  <span
                    className={[
                      'active-thread-tree-status',
                      row.node.processing ? 'is-processing' : '',
                      outcome ? `is-${outcome.toLowerCase()}` : '',
                    ].filter(Boolean).join(' ')}
                  >
                    {statusLabel}
                  </span>
                </ThreadLink>
              </li>
            )
          })}
        </ol>
      ) : null}
    </ThreadWidgetPanel>
  )
}
