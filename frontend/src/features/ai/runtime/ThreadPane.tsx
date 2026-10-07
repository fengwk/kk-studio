import { useCallback, type ReactNode } from 'react'
import {
  ThreadPanel,
  ThreadStatusFooter,
  type ThreadPanelActivityInput,
  type ThreadPanelMainView,
  type ThreadPanelTranscriptInput,
} from '@/features/ai/runtime/thread-panel'
import {
  ResourceBlobUrlContext,
  type ResourceBlobUrls,
} from '@/features/ai/runtime/thread-panel/messages/ResourceBlobUrlContext'
import { useBoundThreadPanelLabels } from '@/features/ai/runtime/useBoundThreadPanelViews'
import type { ThreadProjection } from '@/features/ai/runtime/useThreadProjection'
import { storageService } from '@/shared/api/storage-service'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import type { EnvironmentStatusIdentity } from '@/features/ai/runtime/thread-panel/thread-status-format'
import type { TurnUsage } from '@/features/ai/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'
import { toolExecutionState } from '@/features/ai/runtime/thread-panel/messages/tool-message-view'

/** 只读 Footer facts；缺失字段整段省略。 */
export interface ThreadPaneLabels {
  environment?: EnvironmentStatusIdentity | null
  environmentReady?: boolean
  branchUsage?: TurnUsage | null
  contextWindow?: number
}

/** 只读 Thread 视图的可选交互：仅根面板的 Debug 预检需要。 */
export interface ThreadPaneActivityInput {
  working: boolean
  workingLabel?: string
  actionError?: string | null
  onDismissActionError?: () => void
  widgets?: ReactNode
}

/**
 * 已绑定 Thread 的数据与视图组装：只读投影 -> timeline/Debug/usage -> ThreadPanel。
 *
 * 该组件只消费传入的投影，不查询、不订阅、也不挂载草稿/上传/人工执行 Hook，
 * 因此同一组件既是根面板的展示层，也是只读子代理视图的全部实现；两者的差别
 * 只在于调用方是否传入根控制区（`RootThreadControlArea`）作为 `controls`。
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
  environments: EnvironmentCardDTO[]
  heading?: ReactNode
  /** 输入与控制区：只有根面板传入根控制区；只读视图的返回入口在标题区（`navigation`）。 */
  controls?: ReactNode
  activity: ThreadPaneActivityInput
  views: ThreadPaneViews
}) {
  const labels = useBoundThreadPanelLabels(environments, projection)
  const { t } = useI18n()
  const pendingTool = projection.toolInvocations.find(
    (invocation) => ['READY', 'WAITING_APPROVAL', 'WAITING_INPUT', 'DISPATCHING'].includes(invocation.status),
  )
  const executing = projection.toolInvocations.some(
    (invocation) => invocation.status === 'RUNNING',
  ) || projection.modelInvocation?.status === 'RUNNING'
  const pendingToolState = pendingTool ? toolExecutionState({
    invocationStatus: pendingTool.status,
    waitingForEnvironment: pendingTool.waitingForEnvironment,
    phase: 'call',
  }) : null
  const transcript: ThreadPanelTranscriptInput = {
    messages: projection.timeline.messages,
    queuedMessages: projection.timeline.queuedMessages,
    loading: projection.messagesLoading,
    error: projection.messagesError,
    bodyRef: projection.bodyRef,
    initialScrollTop: views.initialConversationScrollTop,
    resetKey: projection.threadId,
    eventCount: projection.entries.length + projection.queuedCommands.length,
  }
  const panelActivity: ThreadPanelActivityInput = {
    working: activity.working,
    workingLabel: pendingToolState && !executing
      ? t(`ai.runtime.tool.${pendingToolState}`, { environment: pendingTool?.requiredEnvironmentName ?? '' })
      : activity.workingLabel,
    widgets: activity.widgets,
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
      || typeof original.sizeBytes !== 'number'
      || !Number.isSafeInteger(original.sizeBytes)
      || original.sizeBytes < 0
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
