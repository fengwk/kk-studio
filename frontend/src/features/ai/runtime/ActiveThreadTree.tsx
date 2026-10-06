import { useI18n } from '@/shared/i18n'
import { ThreadLink } from '@/features/ai/runtime/ThreadLink'
import { ThreadWidgetPanel } from '@/features/ai/runtime/thread-panel/ThreadWidgetPanel'
import { formatThreadStatusLabel } from '@/features/ai/runtime/thread-panel/thread-status-format'
import { threadTreeOutcome } from '@/features/ai/runtime/thread-panel/active-thread-tree'
import type { useActiveThreadTree } from '@/features/ai/runtime/useActiveThreadTree'

/**
 * 根面板自动活跃树：读完整执行树，只保留 processing 节点及其祖先，逐行单行整行点击。
 *
 * 查询由根面板通过 `useActiveThreadTree` 单点持有（同一个 key 与轮询），本组件只
 * 负责展示；没有活跃后代时不渲染空壳。查询失败不伪装成最新数据：无数据时给出
 * 错误与重试，有旧数据时保留并标记刷新失败。
 */
export function ActiveThreadTree({
  tree,
  currentThreadId,
}: {
  tree: ReturnType<typeof useActiveThreadTree>
  currentThreadId?: string
}) {
  const { t } = useI18n()
  const { rows, activeCount, isError, refreshFailed, refetch } = tree

  // 无活跃后代且查询正常：不渲染空壳（查询仍在后台继续）。
  if (rows.length === 0 && !isError) {
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
      {isError ? (
        <div className="active-thread-tree-state is-error" role="alert">
          <p>{refreshFailed ? t('ai.runtime.agentTree.refreshFailed') : t('ai.runtime.agentTree.error')}</p>
          <button type="button" className="ghost-btn" onClick={refetch}>
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
