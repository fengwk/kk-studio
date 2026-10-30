import { useLayoutEffect, type ReactNode } from 'react'
import { shouldDeferToBlockingModal } from '@/shared/ui/blocking-overlay'
import { ThreadPane } from '@/features/chat/runtime/ThreadPane'
import {
  useThreadProjection,
  type ThreadProjection,
} from '@/features/chat/runtime/useThreadProjection'
import { useBoundThreadPanelViews } from '@/features/chat/runtime/useBoundThreadPanelViews'
import { useThreadPresentation, type ThreadPresentation } from './thread-presentation'
import { ThreadPresentationActions } from './ThreadPresentationActions'
import { Button } from '@/shared/ui/controls/Button'
import { useI18n } from '@/shared/i18n'
import '@/features/chat/runtime/child-thread-view.css'
import type { EnvironmentCardDTO } from '@/shared/api/generated/contracts'

export interface ThreadViewProps {
  threadId?: string
  /** 纯展示组件消费必填的投影，自身不再拥有或自建订阅 */
  projection: ThreadProjection
  environments: EnvironmentCardDTO[]
  /** 查看层/独立地址的返回入口（顶部标题区）。 */
  navigation?: ReactNode
  /** 只读查看（子代理）：标题带只读标识。 */
  readOnly?: boolean
  selected?: boolean
  active?: boolean
  onPresentation?: (report: ThreadPresentation) => void
  onParent?: (parentThreadId: string) => void
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
  readOnly = false,
  selected = true,
  active = true,
  onPresentation,
  onParent,
}: ThreadViewProps) {
  const { t } = useI18n()
  const threadId = explicitThreadId ?? projection.threadId
  // 只读视图自持视图状态（Conversation/Debug 与滚动恢复），不进入根控制面。
  const views = useBoundThreadPanelViews(threadId, projection, {
    viewKey: `thread:${threadId}`,
    historyLoading: projection.messagesLoading,
    historyError: projection.messagesError ? t('ai.chat.history.loadFailed') : null,
    onRetryHistory: () => { void projection.snapshotQuery.refetch() },
  })
  const presentation = useThreadPresentation({
    views,
    projection,
    threadId,
    selected,
    active,
    onReport: onPresentation,
    onParent,
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
        </Button> : presentation.parentThreadId ? <ThreadPresentationActions view={presentation} /> : navigation}
        {views.mode !== 'debug' ? <>
          {presentation.name ? <h2 className="agent-pane-thread-title" title={presentation.name}>{presentation.name}</h2> : null}
          {readOnly ? <span className="thread-readonly-badge">{t('ai.runtime.childThread.readOnly')}</span> : null}
        </> : null}
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
    </div>
  )
}

export interface ConnectedThreadViewProps {
  threadId: string
  environments: EnvironmentCardDTO[]
  navigation?: ReactNode
  readOnly?: boolean
  selected?: boolean
  active?: boolean
  onPresentation?: (report: ThreadPresentation) => void
  onParent?: (parentThreadId: string) => void
}

/**
 * 独立/子代理视图连接入口：只在自己的组件入口获取一次投影，向下传递给 ThreadView，
 * 避免外层父组件重复订阅。默认只读。
 */
export function ConnectedThreadView({
  threadId,
  environments,
  navigation,
  readOnly = true,
  selected = true,
  active = true,
  onPresentation,
  onParent,
}: ConnectedThreadViewProps) {
  const projection = useThreadProjection(threadId)
  return (
    <ThreadView
      threadId={threadId}
      projection={projection}
      environments={environments}
      navigation={navigation}
      readOnly={readOnly}
      selected={selected}
      active={active}
      onPresentation={onPresentation}
      onParent={onParent}
    />
  )
}
