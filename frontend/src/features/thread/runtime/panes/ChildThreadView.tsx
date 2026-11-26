import type { ReactNode } from 'react'
import { ThreadPane } from '@/features/thread/runtime/panes/ThreadPane'
import {
  useThreadProjection,
  type ThreadProjection,
} from '@/features/thread/runtime/useThreadProjection'
import { useThreadPresentation } from './thread-presentation'
import { ThreadPresentationActions } from './ThreadPresentationActions'
import { useI18n } from '@/shared/i18n'
import '@/features/thread/runtime/panes/child-thread-view.css'
import type { EnvironmentSummaryDTO } from '@/shared/api/generated/contracts'

interface ThreadViewProps {
  threadId?: string
  /** 纯展示组件消费必填的投影，自身不再拥有或自建订阅 */
  projection: ThreadProjection
  environments: EnvironmentSummaryDTO[]
  /** 查看层返回入口（顶部标题区）。 */
  navigation?: ReactNode
  active?: boolean
  onParent: (parentThreadId: string) => void
}

/**
 * 纯只读展示视图：强约束要求必须传入 projection，只消费投影与视图状态，
 * 不挂载草稿、上传或人工执行 Hook，内部绝不调用 useThreadProjection。
 */
export function ThreadView({
  threadId: explicitThreadId,
  projection,
  environments,
  navigation,
  active = true,
  onParent,
}: ThreadViewProps) {
  const { t } = useI18n()
  const threadId = explicitThreadId ?? projection.threadId
  // 子线程没有自己的环境选择：只读展示当前冻结的环境并标明跟随主对话；执行根自身不显示该行。
  const childEnvironmentName = projection.runtimeLabels.environmentName
  const showChildEnvironment = projection.thread?.parentThreadId != null
  const presentation = useThreadPresentation({
    projection,
    threadId,
    active,
    onParent,
  })
  return (
    <div className="bound-thread-view">
      <header className="agent-pane-thread-heading">
        {presentation.parentThreadId ? <ThreadPresentationActions view={presentation} /> : navigation}
        <div className="agent-pane-thread-caption">
          {presentation.name ? <h2 className="agent-pane-thread-title" title={presentation.name}>{presentation.name}</h2> : null}
          <span className="thread-readonly-badge">{t('ai.runtime.childThread.readOnly')}</span>
        </div>
      </header>
      {showChildEnvironment ? (
        <div className="thread-readonly-environment">
          {childEnvironmentName
            ? t('ai.runtime.childThread.environment', { environment: childEnvironmentName })
            : t('ai.runtime.childThread.environmentNone')}
        </div>
      ) : null}
      <ThreadPane
        projection={projection}
        environments={environments}
        activity={{}}
        views={{ initialConversationScrollTop: null }}
      />
    </div>
  )
}

interface ConnectedThreadViewProps {
  threadId: string
  environments: EnvironmentSummaryDTO[]
  navigation?: ReactNode
  active?: boolean
  onParent: (parentThreadId: string) => void
}

/**
 * 子线程查看层只获取一次投影，向下传递给只读的 ThreadView。
 */
export function ConnectedThreadView({
  threadId,
  environments,
  navigation,
  active = true,
  onParent,
}: ConnectedThreadViewProps) {
  const projection = useThreadProjection(threadId)
  return (
    <ThreadView
      threadId={threadId}
      projection={projection}
      environments={environments}
      navigation={navigation}
      active={active}
      onParent={onParent}
    />
  )
}
