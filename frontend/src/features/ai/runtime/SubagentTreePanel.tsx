import { useI18n } from '@/shared/i18n'
import { ThreadWidgetPanel } from './thread-panel/ThreadWidgetPanel'
import { ThreadTreeRows } from './thread-panel/ThreadTreeRows'
import type { useActiveThreadTree } from './useActiveThreadTree'

/** 由绑定 thread 控制器传入已有查询；本面板没有写操作、另一个缓存或订阅。 */
export function SubagentTreePanel({ tree, currentThreadId, onClose }: {
  tree: ReturnType<typeof useActiveThreadTree>
  currentThreadId?: string
  onClose?: () => void
}) {
  const { t } = useI18n()
  return (
    <ThreadWidgetPanel className="subagent-tree-panel" title={t('ai.runtime.agentTree.historyTitle')}
      actions={onClose ? <button type="button" className="ghost-btn" onClick={onClose}>{t('shared.close')}</button> : null}>
      {tree.isError ? (
        <div role="alert" className="thread-tree-state is-error">
          <p>{t(tree.refreshFailed ? 'ai.runtime.agentTree.refreshFailed' : 'ai.runtime.agentTree.error')}</p>
          <button type="button" className="ghost-btn" onClick={tree.refetch}>{t('ai.runtime.agentTree.retry')}</button>
        </div>
      ) : null}
      {tree.isLoading ? <div role="status" className="thread-tree-state">{t('ai.runtime.thread.loading')}</div> : null}
      {!tree.isLoading && !tree.isError && tree.historyRows.length === 0
        ? <div className="thread-tree-state">{t('ai.runtime.agentTree.emptyHistory')}</div> : null}
      <ThreadTreeRows rows={tree.historyRows} currentThreadId={currentThreadId} />
    </ThreadWidgetPanel>
  )
}
