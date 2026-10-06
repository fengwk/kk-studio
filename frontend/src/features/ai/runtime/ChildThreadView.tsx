import { ArrowLeft } from 'lucide-react'
import { Link } from 'react-router'
import type { ReactNode } from 'react'
import { ThreadPane } from '@/features/ai/runtime/ThreadPane'
import {
  useThreadProjection,
  type ThreadProjection,
} from '@/features/ai/runtime/useThreadProjection'
import { useBoundThreadPanelViews } from '@/features/ai/runtime/useBoundThreadPanelViews'
import { useI18n } from '@/shared/i18n'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import '@/features/ai/runtime/child-thread-view.css'

/**
 * 已绑定 Thread 的视图外壳：只消费投影与视图状态，不挂载草稿、上传或人工执行
 * Hook，因此不存在任何写入口。调用方可以提供已持有的投影（父面板已订阅同一
 * Thread 时不重复查询与订阅），以及查看层的返回入口 `navigation`。
 *
 * `navigation` 渲染在顶部标题区（名称/只读标识旁、滚动区之外），因此查看子代理
 * 时的返回入口始终在顶部，不需要额外的插槽协议；底部控制区仍然只属于根面板。
 */
export function BoundThreadView({
  threadId,
  projection: injectedProjection,
  environments,
  navigation,
  readOnly = false,
}: {
  threadId: string
  projection?: ThreadProjection
  environments: EnvironmentCardDTO[]
  /** 查看层/独立地址的返回入口（顶部标题区）。 */
  navigation?: ReactNode
  /** 只读查看（子代理）：标题带只读标识。 */
  readOnly?: boolean
}) {
  const { t } = useI18n()
  const ownProjection = useThreadProjection(injectedProjection ? '' : threadId)
  const projection = injectedProjection ?? ownProjection
  // 只读视图自持视图状态（Conversation/Debug 与滚动恢复），不进入根控制面。
  const views = useBoundThreadPanelViews(threadId, projection)
  const name = projection.thread?.name ?? t('ai.runtime.rename.loadingName')
  return (
    <ThreadPane
      projection={projection}
      environments={environments}
      heading={(
        <header className="agent-pane-thread-heading">
          {navigation}
          <h2 className="agent-pane-thread-title" title={name}>{name}</h2>
          {readOnly ? (
            <span className="thread-readonly-badge">{t('ai.runtime.childThread.readOnly')}</span>
          ) : null}
        </header>
      )}
      activity={{ working: projection.working }}
      views={{
        mainView: views.mainView,
        initialConversationScrollTop: views.initialConversationScrollTop,
      }}
    />
  )
}

/** 只读子代理视图：投影 + 只读标识 + 顶部返回入口，没有任何写入口。 */
export function ChildThreadView(props: {
  threadId: string
  projection?: ThreadProjection
  environments: EnvironmentCardDTO[]
  navigation?: ReactNode
}) {
  return <BoundThreadView {...props} readOnly />
}

/** 同 pane 层内返回：回到上一层查看路径（渲染在查看层顶部标题区）。 */
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

/** 独立子线程地址的返回执行根入口（渲染在地址顶部标题区）。 */
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
