import { useI18n } from '@/shared/i18n'
import { ThreadTreeRows } from '@/features/ai/runtime/thread-panel/ThreadTreeRows'
import { ThreadWidgetPanel } from '@/features/ai/runtime/thread-panel/ThreadWidgetPanel'
import type { useActiveThreadTree } from '@/features/ai/runtime/useActiveThreadTree'

/**
 * 根面板自动活跃树：读完整执行树，只保留 processing 节点及其祖先，逐行单行整行点击。
 *
 * 查询由根面板通过 `useActiveThreadTree` 单点持有（同一个缓存与推送），本组件只
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
        <div className="thread-tree-state is-error" role="alert">
          <p>{refreshFailed ? t('ai.runtime.agentTree.refreshFailed') : t('ai.runtime.agentTree.error')}</p>
          <button type="button" className="ghost-btn" onClick={refetch}>
            {t('ai.runtime.agentTree.retry')}
          </button>
        </div>
      ) : null}
      <ThreadTreeRows rows={rows} currentThreadId={currentThreadId} />
    </ThreadWidgetPanel>
  )
}
