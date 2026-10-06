import { ArrowLeft } from 'lucide-react'
import { Link } from 'react-router'
import type { ReactNode } from 'react'
import { ThreadPane } from '@/features/ai/runtime/ThreadPane'
import { useThreadProjection } from '@/features/ai/runtime/useThreadProjection'
import { useBoundThreadPanelViews } from '@/features/ai/runtime/useBoundThreadPanelViews'
import { useI18n } from '@/shared/i18n'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import '@/features/ai/runtime/child-thread-view.css'

/**
 * 只读子代理视图：只挂载 Thread 投影与视图状态，不挂载草稿、上传或人工执行
 * Hook，因此不存在任何写入口。查看入口（逐层返回 / 返回执行根）由调用方以
 * `controls` 提供，视图本身不猜测导航上下文。
 */
export function ChildThreadView({
  threadId,
  environments,
  controls,
}: {
  threadId: string
  environments: EnvironmentCardDTO[]
  controls: ReactNode
}) {
  const { t } = useI18n()
  const projection = useThreadProjection(threadId)
  // 只读视图自持视图状态（Conversation/Debug 与滚动恢复），不进入根控制面。
  const views = useBoundThreadPanelViews(threadId, projection)
  const name = projection.thread?.name ?? t('ai.runtime.rename.loadingName')
  return (
    <ThreadPane
      projection={projection}
      environments={environments}
      heading={(
        <header className="agent-pane-thread-heading">
          <h2 className="agent-pane-thread-title" title={name}>{name}</h2>
          <span className="thread-readonly-badge">{t('ai.runtime.childThread.readOnly')}</span>
        </header>
      )}
      controls={controls}
      activity={{ working: projection.working }}
      views={{
        mainView: views.mainView,
        initialConversationScrollTop: views.initialConversationScrollTop,
      }}
    />
  )
}

/** 同 pane 层内返回：回到上一层查看路径。 */
export function ChildThreadBackBar({ onBack }: { onBack: () => void }) {
  const { t } = useI18n()
  return (
    <div className="thread-child-return-bar">
      <button type="button" className="ghost-btn" onClick={onBack}>
        <ArrowLeft aria-hidden="true" size={14} />
        <span>{t('ai.runtime.childThread.back')}</span>
      </button>
    </div>
  )
}

/** 独立子线程地址的返回执行根入口。 */
export function ChildThreadRootLink({ rootThreadId }: { rootThreadId: string }) {
  const { t } = useI18n()
  return (
    <div className="thread-child-return-bar">
      <Link className="ghost-btn" to={`/threads/${encodeURIComponent(rootThreadId)}`}>
        <ArrowLeft aria-hidden="true" size={14} />
        <span>{t('ai.runtime.childThread.backToRoot')}</span>
      </Link>
    </div>
  )
}
