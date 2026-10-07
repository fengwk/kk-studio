import { useI18n } from '@/shared/i18n'
import { ThreadLink } from '@/features/ai/runtime/ThreadLink'
import { formatThreadStatusLabel } from './thread-status-format'
import { threadTreeOutcome, type ActiveThreadTreeRow } from './active-thread-tree'
import './thread-tree.css'

/** 活跃与历史共用只读行；所有身份、状态和计数均来自服务端执行树。 */
export function ThreadTreeRows({ rows, currentThreadId }: {
  rows: readonly ActiveThreadTreeRow[]
  currentThreadId?: string
}) {
  const { t } = useI18n()
  return (
    <ol className="thread-tree-list">
      {rows.map((row) => {
        const { node } = row
        const outcome = threadTreeOutcome(node)
        return (
          <li key={node.threadId} className="thread-tree-row"
            data-depth={row.depth} data-thread-id={node.threadId}
            data-processing={node.processing ? 'true' : 'false'}
            aria-current={node.threadId === currentThreadId ? 'true' : undefined}>
            <ThreadLink threadId={node.threadId} className="thread-tree-link" title={node.threadId}>
              <span className="thread-tree-connectors" aria-hidden="true">
                {row.ancestorContinues.map((continues, index) => (
                  <span key={index} className={continues ? 'is-continuing' : ''} />
                ))}
                <span className={`is-branch${row.isLast ? ' is-last' : ''}`} />
              </span>
              <span className="thread-tree-content">
                <strong className="thread-tree-identity">{node.agentName}</strong>
                {node.name !== 'main' && node.name !== node.agentName
                  ? <span className="thread-tree-detail">{node.name}</span> : null}
                <span className={`thread-tree-status${node.processing ? ' is-processing' : ''}${outcome ? ` is-${outcome.toLowerCase()}` : ''}`}>
                  {outcome ? t(`ai.runtime.agentTree.outcome.${outcome}`) : formatThreadStatusLabel(node.status, t)}
                </span>
                <span className="thread-tree-counts">
                  {t('ai.runtime.agentTree.counts', { turns: node.turnCount, tools: node.toolCallCount })}
                </span>
                <span className="thread-tree-detail">{node.model.providerName}/{node.model.modelName}</span>
              </span>
            </ThreadLink>
          </li>
        )
      })}
    </ol>
  )
}
