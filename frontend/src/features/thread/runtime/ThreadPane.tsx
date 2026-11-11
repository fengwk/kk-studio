import { useCallback, type ReactNode } from 'react'
import {
  ThreadPanel,
  ThreadStatusFooter,
  type ThreadPanelActivityInput,
  type ThreadPanelMainView,
  type ThreadPanelTranscriptInput,
} from '@/features/thread/runtime/thread-panel'
import {
  ResourceBlobUrlContext,
  type ResourceBlobUrls,
} from '@/features/thread/runtime/thread-panel/messages/ResourceBlobUrlContext'
import { useBoundThreadPanelLabels } from '@/features/thread/runtime/useBoundThreadPanelViews'
import { UnconfirmedExecutionsPanel } from '@/features/thread/runtime/UnconfirmedExecutionsPanel'
import type { ThreadProjection } from '@/features/thread/runtime/useThreadProjection'
import { storageService } from '@/shared/api/storage-service'
import type { EnvironmentStatusIdentity } from '@/features/thread/runtime/thread-panel/thread-status-format'
import type { TurnUsage } from '@/features/thread/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'
import { isExitPendingStatus } from '@/features/thread/runtime/thread-panel/thread-status-format'
import type { EnvironmentDTO } from '@/shared/api/generated/contracts'
import { deriveThreadWorkPhase } from '@/features/thread/runtime/thread-work-phase'

/** 只读 Footer facts；缺失字段整段省略。 */
export interface ThreadPaneLabels {
  environment?: EnvironmentStatusIdentity | null
  environmentReady?: boolean
  branchUsage?: TurnUsage | null
  contextWindow?: number
}

/** 只读 Thread 视图的可选交互：仅根面板的 Debug 预检需要。 */
export interface ThreadPaneActivityInput {
  actionError?: string | null
  onDismissActionError?: () => void
  /** EXIT_PENDING + RUNNABLE 时把“停止并处理”接到现有 Stop 流程；只读视图可不提供。 */
  onStop?: () => void
  stopPending?: boolean
  widgets?: ReactNode
}

/**
 * 已绑定 Thread 的数据与视图组装：只读投影 -> timeline/Debug/usage -> ThreadPanel。
 *
 * 该组件只消费传入的投影，不查询、不订阅、也不挂载草稿/上传/人工执行 Hook，
 * 因此同一组件既是根面板的展示层，也是只读子代理视图的全部实现；两者的差别
 * 只在于调用方是否传入根控制区（`RootThreadControlArea`）作为 `controls`。
 * 未确认退出事实的“放弃等待”是唯一维护动作：它不写执行事实，必须随投影在
 * 根与可查看的子代理 Thread 上都可用；其余写入口仍只属于根控制区。
 */
/** 主视图与滚动恢复由调用方持有：根面板复用控制面的视图实例，只读视图自持。 */
export interface ThreadPaneViews {
  mainView?: ThreadPanelMainView
  initialConversationScrollTop: number | null
}

export function ThreadPane({
  projection,
  environments,
  heading,
  controls,
  activity,
  views,
}: {
  projection: ThreadProjection
  environments: EnvironmentDTO[]
  heading?: ReactNode
  /** 输入与控制区：仅根面板传入；只读身份与导航由宿主单一顶栏呈现。 */
  controls?: ReactNode
  activity: ThreadPaneActivityInput
  views: ThreadPaneViews
}) {
  const labels = useBoundThreadPanelLabels(environments, projection)
  const { t } = useI18n()
  const workPhase = deriveThreadWorkPhase({
    thread: projection.thread,
    activeAssistantOutput: projection.activeAssistantOutput,
    entries: projection.entries,
    toolInvocations: projection.toolInvocations,
  })
  // 活跃执行中的 UNCONFIRMED 是正常在途事实，不进入异常/待处理面板；只有真正 EXIT_PENDING 才展示详情。
  const exitPending = isExitPendingStatus(projection.thread?.status)
  const unconfirmedExecutions = exitPending ? projection.unconfirmedExecutions : []

  // 工作条文案与动画都只由这一份阶段事实派生。
  const phaseLabels = {
    'model-running': t('ai.runtime.thread.phase.generating'),
    'tool-running': t('ai.runtime.thread.phase.executingTool'),
    'materializing-result': t('ai.runtime.thread.phase.materializing'),
    'next-step': t('ai.runtime.thread.phase.nextStep'),
    none: '',
  }
  const waitingLabels = {
    environment: t('ai.runtime.tool.environment', {
      environment: workPhase.kind === 'waiting' ? workPhase.environmentName ?? '' : '',
    }),
    approval: t('ai.runtime.tool.approval'),
    input: t('ai.runtime.tool.input'),
    'exit-confirmation': t('ai.runtime.thread.exitPending'),
    'async-result': t('ai.runtime.thread.status.WAITING'),
  }
  const phaseLabel = workPhase.kind === 'waiting'
    ? waitingLabels[workPhase.reason]
    : phaseLabels[workPhase.kind]

  const transcript: ThreadPanelTranscriptInput = {
    messages: projection.timeline.messages,
    queuedMessages: projection.timeline.queuedMessages,
    queuedCustomMessages: projection.queuedCustomMessages,
    loading: projection.messagesLoading,
    error: projection.messagesError,
    bodyRef: projection.bodyRef,
    initialScrollTop: views.initialConversationScrollTop,
    resetKey: projection.threadId,
    eventCount: projection.entries.length + projection.queuedCommands.length,
  }
  const panelActivity: ThreadPanelActivityInput = {
    working: workPhase.kind !== 'none',
    workingLabel: phaseLabel,
    animated: workPhase.kind === 'model-running' || workPhase.kind === 'tool-running',
    widgets: unconfirmedExecutions.length === 0
      ? activity.widgets
      : (
        <>
          <UnconfirmedExecutionsPanel
            key={projection.threadId}
            threadId={projection.threadId}
            rootThreadId={projection.thread?.rootThreadId ?? ''}
            expectedVersion={projection.thread?.version ?? ''}
            executionControl={projection.thread?.executionControl ?? 'RUNNABLE'}
            executions={unconfirmedExecutions}
            onStop={activity.onStop}
            stopPending={activity.stopPending}
          />
          {activity.widgets}
        </>
      ),
    actionError: activity.actionError ?? null,
    onDismissActionError: activity.onDismissActionError,
  }
  return (
    <ResourceBlobUrlContext.Provider value={useBlobUrlResolver()}>
      <ThreadPanel
        heading={heading}
        transcript={transcript}
        mainView={views.mainView}
        controls={controls}
        activity={panelActivity}
        slots={{ footer: <ThreadStatusFooter {...labels} /> }}
      />
    </ResourceBlobUrlContext.Provider>
  )
}

/** 面板保持 API 无关：RESOURCE blob URL 由本适配层在渲染期解析。 */
function useBlobUrlResolver() {
  return useCallback(async (blobId: string): Promise<ResourceBlobUrls | null> => {
    const [original, preview] = await Promise.all([
      storageService.getBlobDownloadUrl(blobId).catch(() => null),
      storageService.getBlobPreviewUrl(blobId).catch(() => null),
    ])
    if (
      original == null
      || !original.url.trim()
      || !original.mediaType?.trim()
    ) {
      return null
    }
    return {
      original: original.url,
      preview: preview?.url?.trim() || null,
      mediaType: original.mediaType,
      sizeBytes: original.sizeBytes,
    }
  }, [])
}
