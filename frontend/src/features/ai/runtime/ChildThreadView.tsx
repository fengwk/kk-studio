import { useLayoutEffect, useState, type ReactNode } from 'react'
import { shouldDeferToBlockingModal } from '@/shared/ui/blocking-overlay'
import { ThreadPane } from '@/features/ai/runtime/ThreadPane'
import {
  useThreadProjection,
  type ThreadProjection,
} from '@/features/ai/runtime/useThreadProjection'
import { useBoundThreadPanelViews } from '@/features/ai/runtime/useBoundThreadPanelViews'
import { useActiveThreadTree } from './useActiveThreadTree'
import { SubagentTreePanel } from './SubagentTreePanel'
import { useThreadPresentation, type ThreadPresentation } from './thread-presentation'
import { ThreadPresentationActions } from './ThreadPresentationActions'
import { Button } from '@/shared/ui/controls/Button'
import { useI18n } from '@/shared/i18n'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import '@/features/ai/runtime/child-thread-view.css'

/**
 * 已绑定 Thread 的视图外壳：只消费投影与视图状态，不挂载草稿、上传或人工执行
 * Hook，因此不存在任何写入口。调用方可以提供已持有的投影（父面板已订阅同一
 * Thread 时不重复查询与订阅），以及查看层的返回入口 `navigation`。
 *
 * workspace 由 onPresentation 接管单顶栏；standalone 在滚动区之外保留唯一紧凑读头。
 * 两者共用原查看状态和 scoped 动作，底部写控制区仍然只属于根面板。
 */
export function BoundThreadView({
  threadId,
  projection: injectedProjection,
  environments,
  navigation,
  readOnly = false,
  selected = true,
  active = true,
  onPresentation,
  onParent,
}: {
  threadId: string
  projection?: ThreadProjection
  environments: EnvironmentCardDTO[]
  /** 查看层/独立地址的返回入口（顶部标题区）。 */
  navigation?: ReactNode
  /** 只读查看（子代理）：标题带只读标识。 */
  readOnly?: boolean
  selected?: boolean
  active?: boolean
  onPresentation?: (report: ThreadPresentation) => void
  onParent?: (parentThreadId: string) => void
}) {
  const { t } = useI18n()
  const ownProjection = useThreadProjection(injectedProjection ? '' : threadId)
  const projection = injectedProjection ?? ownProjection
  // 只读视图自持视图状态（Conversation/Debug 与滚动恢复），不进入根控制面。
  const views = useBoundThreadPanelViews(threadId, projection, {
    viewKey: `thread:${threadId}`,
    historyLoading: projection.messagesLoading,
    historyError: projection.messagesError ? t('ai.chat.history.loadFailed') : null,
    onRetryHistory: () => { void projection.snapshotQuery.refetch() },
  })
  const [treeOpen, setTreeOpen] = useState(false)
  const rootThreadId = projection.thread?.yoloPolicy.rootThreadId
    ?? (projection.thread?.parentThreadId == null ? threadId : null)
  const tree = useActiveThreadTree(treeOpen ? rootThreadId : null)
  const presentation = useThreadPresentation({
    views, projection, threadId, selected, active, onReport: onPresentation,
    onSubagent: () => setTreeOpen(true), onParent,
  })
  useLayoutEffect(() => {
    if (onPresentation == null && presentation.mode === 'conversation') {
      presentation.act(presentation.viewKey, 'restore-focus')
    }
  }, [onPresentation, presentation])
  return (
    <div className="bound-thread-view" onKeyDown={(event) => {
      if (onPresentation == null && presentation.mode === 'debug' && event.key === 'Escape'
        && !event.defaultPrevented && !event.repeat && !event.nativeEvent.isComposing && event.keyCode !== 229
        && !shouldDeferToBlockingModal(event.currentTarget)) {
        event.preventDefault()
        event.stopPropagation()
        presentation.act(presentation.viewKey, 'close-debug')
      }
    }}>
    {onPresentation == null ? <header className="agent-pane-thread-heading">
      {views.mode === 'debug' ? <Button variant="ghost" onClick={() => presentation.act(presentation.viewKey, 'close-debug')}>
        {t('ai.runtime.debug.close')}
      </Button> : navigation}
      {views.mode !== 'debug' ? <>
        {presentation.name ? <h2 className="agent-pane-thread-title" title={presentation.name}>{presentation.name}</h2> : null}
        {readOnly ? <span className="thread-readonly-badge">{t('ai.runtime.childThread.readOnly')}</span> : null}
      </> : null}
      <ThreadPresentationActions view={presentation} />
    </header> : null}
    <ThreadPane
      projection={projection}
      environments={environments}
      activity={{ working: projection.working }}
      views={{
        mainView: views.mainView,
        initialConversationScrollTop: views.initialConversationScrollTop,
      }}
    />
    {treeOpen && views.mode !== 'debug' ? <SubagentTreePanel tree={tree} currentThreadId={threadId}
      onClose={() => setTreeOpen(false)} /> : null}
    </div>
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
