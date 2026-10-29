import { useCallback, type ReactNode } from 'react'
import {
  ThreadPanel,
  ThreadStatusFooter,
  type ThreadPanelActivityInput,
  type ThreadPanelMainView,
  type ThreadPanelTranscriptInput,
} from '@/features/chat/runtime/thread-panel'
import {
  ResourceBlobUrlContext,
  type ResourceBlobUrls,
} from '@/features/chat/runtime/thread-panel/messages/ResourceBlobUrlContext'
import { useBoundThreadPanelLabels } from '@/features/chat/runtime/useBoundThreadPanelViews'
import { UnconfirmedExecutionsPanel } from '@/features/chat/runtime/UnconfirmedExecutionsPanel'
import type { ThreadProjection } from '@/features/chat/runtime/useThreadProjection'
import { storageService } from '@/shared/api/storage-service'
import type { EnvironmentStatusIdentity } from '@/features/chat/runtime/thread-panel/thread-status-format'
import type { TurnUsage } from '@/features/chat/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'
import { toolExecutionState } from '@/features/chat/runtime/thread-panel/messages/tool-message-view'
import type { EnvironmentCardDTO } from '@/shared/api/generated/contracts'

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
  environments: EnvironmentCardDTO[]
  heading?: ReactNode
  /** 输入与控制区：仅根面板传入；只读身份与导航由宿主单一顶栏呈现。 */
  controls?: ReactNode
  activity: ThreadPaneActivityInput
  views: ThreadPaneViews
}) {
  const labels = useBoundThreadPanelLabels(environments, projection)
  const { t } = useI18n()
  const pendingTool = projection.toolInvocations.find(
    (invocation) => ['READY', 'WAITING_APPROVAL', 'WAITING_INPUT'].includes(invocation.status),
  )
  const executing = projection.toolInvocations.some(
    (invocation) => invocation.status === 'RUNNING',
  ) || projection.activeAssistantOutput?.status === 'RUNNING'
  const pendingToolState = pendingTool ? toolExecutionState({
    invocationStatus: pendingTool.status,
    waitingForEnvironment: pendingTool.waitingForEnvironment,
    phase: 'call',
  }) : null
  // 只有 STOPPED 才允许放弃等待；RUNNABLE 的普通在途执行不是“未确认退出”事实。
  const unconfirmedExecutions = projection.thread?.executionControl === 'STOPPED'
    ? projection.unconfirmedExecutions
    : []

  // 已请求停止但 Thread 仍 RUNNING、且没有真实 RUNNING model/tool：退出尚未确认。
  // 这只是现有 executionControl 与 status 的静态组合，不代表执行即将结束。
  const stopExitUnconfirmed = projection.thread?.executionControl === 'STOPPED'
    && projection.thread.status === 'RUNNING'
    && !executing

  // 事实优先级：
  // 1. 真实 RUNNING model/tool 优先，STOPPED 不可遮在途执行
  // 2. STOPPED+RUNNING 且无真实执行：静态提示退出尚未确认；残留排队/审批不再暗示可继续
  // 3. pendingTool 阻塞：审批 / 问卷 / environment 文案准确且无动画
  // 4. status 三态：RUNNING 显示运行中，WAITING 等待异步结果，绝不把 RUNNING 直接显示生成
  // 5. 其余回退外部 activity
  let resolvedWorking = activity.working
  let resolvedWorkingLabel: string | undefined = activity.workingLabel

  if (executing) {
    resolvedWorking = true
    resolvedWorkingLabel = t('ai.runtime.thread.working')
  } else if (stopExitUnconfirmed) {
    resolvedWorking = true
    resolvedWorkingLabel = t('ai.runtime.thread.stopExitUnconfirmed')
  } else if (pendingToolState) {
    resolvedWorking = true
    resolvedWorkingLabel = t(`ai.runtime.tool.${pendingToolState}`, {
      environment: pendingTool?.requiredEnvironmentName ?? '',
    })
  } else if (projection.thread?.status === 'WAITING') {
    resolvedWorking = true
    resolvedWorkingLabel = t('ai.runtime.thread.status.WAITING')
  } else if (projection.thread?.status === 'RUNNING') {
    resolvedWorking = true
    resolvedWorkingLabel = t('ai.runtime.thread.status.RUNNING')
  }

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
    working: resolvedWorking,
    workingLabel: resolvedWorkingLabel,
    animated: executing,
    widgets: unconfirmedExecutions.length === 0
      ? activity.widgets
      : (
        <>
          <UnconfirmedExecutionsPanel
            key={projection.threadId}
            threadId={projection.threadId}
            rootThreadId={projection.thread?.rootThreadId ?? ''}
            expectedVersion={projection.thread?.version ?? ''}
            executions={unconfirmedExecutions}
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
