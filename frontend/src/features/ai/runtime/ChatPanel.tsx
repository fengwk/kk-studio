import { useCallback } from 'react'
import type { RefObject, ReactNode } from 'react'
import {
  ThreadPanel,
  ThreadStatusFooter,
  type ThreadPanelActivityInput,
  type ThreadPanelComposerInput,
  type ThreadPanelMainView,
  type ThreadPanelTranscriptInput,
} from '@/features/ai/runtime/thread-panel'
import {
  ResourceBlobUrlContext,
  type ResourceBlobUrls,
} from '@/features/ai/runtime/thread-panel/messages/ResourceBlobUrlContext'
import { storageService } from '@/shared/api/storage-service'
import type { EnvironmentBindingDTO } from '@/shared/api/contracts/ai-environment'
import type {
  DialogueMessage,
  QueuedThreadMessage,
  ToolDialogueMessage,
  TurnUsage,
} from '@/features/ai/runtime/thread-timeline-types'
import {
  TaskStatusWidget,
  type TaskApprovalDecision,
} from '@/features/ai/runtime/thread-panel/TaskStatusWidget'

/** Bound ChatPanel 的只读 Footer facts；缺失字段整段省略。 */
export interface ChatPanelLabels {
  /** 完整 Environment binding（name + workspacePath）；null 表示未绑定。 */
  environment?: EnvironmentBindingDTO | null
  environmentReady?: boolean
  gitBranch?: string | null
  /** 当前 root-to-head branch 的已关闭 Turn 累计 usage。 */
  branchUsage?: TurnUsage | null
  contextWindow?: number
}

/** 时间线（持久 timeline + 实时 mailbox）以及加载/错误状态。 */
export interface ChatPanelTranscriptInput {
  timeline: {
    messages: DialogueMessage[]
    queuedMessages: QueuedThreadMessage[]
    hasPendingInputs: boolean
  }
  bodyRef: RefObject<HTMLDivElement | null>
  /** 重新进入 conversation 视图时恢复的 scrollTop；null 表示首次进入（贴底）。 */
  initialScrollTop?: number | null
  /** 变化时重置 stick 并重新贴底（Thread 重绑后新线程首次进入）。 */
  resetKey?: string | number | null
  /** 非消息内容的变化计数（控制 Entry/queued 等），用于贴底再评估。 */
  eventCount?: number
  loading: boolean
  error: unknown
  onDecideApproval?: (message: ToolDialogueMessage, decision: 'ALLOW' | 'DENY') => void
  /** 进行中的全局审批请求：所有未决的审批条都会禁用其按钮。 */
  approvalPending?: boolean
}

/** Composer 的调用点与转发的回调函数。 */
export type ChatPanelComposerInput = ThreadPanelComposerInput

/** Composer 上方展示的工作状态、永久 TaskStatus 与可关闭反馈。 */
export interface ChatPanelActivityInput {
  working: boolean
  /** 追加到 ThreadWidgetStack.children 的自定义 widget（如子任务状态）。 */
  widgets?: ReactNode
  /** TaskStatusWidget 的子 Thread 审批转发；所有 Bound ChatPanel 永久挂载该 widget。 */
  onDecideTaskApproval?: (
    threadId: string,
    invocationId: string,
    decision: TaskApprovalDecision,
  ) => void
  actionError?: string | null
  onDismissActionError?: () => void
}

/**
 * 面板级 Thread 适配器，基于 ThreadPanel 封装。不持有常驻的 Session/Thread 侧边栏；runtime
 * 标签来自 controller，但绝不以后端 DTO 形式直接传递。
 */
export function ChatPanel({
  labels,
  transcript,
  mainView,
  composer,
  activity,
}: {
  labels: ChatPanelLabels
  transcript: ChatPanelTranscriptInput
  /** 互斥主视图：传入 debug 时替换 transcript（Debug view）。 */
  mainView?: ThreadPanelMainView
  composer: ChatPanelComposerInput
  activity: ChatPanelActivityInput
}) {
  // 面板保持 API 无关：RESOURCE blob URL 由本适配层在渲染期解析。
  const resolveBlobUrls = useCallback(async (blobId: string): Promise<ResourceBlobUrls | null> => {
    const [original, preview] = await Promise.all([
      storageService.getBlobOriginalUrl(blobId).catch(() => null),
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
  const threadPanelTranscript: ThreadPanelTranscriptInput = {
    messages: transcript.timeline.messages,
    queuedMessages: transcript.timeline.queuedMessages,
    loading: transcript.loading,
    error: transcript.error,
    bodyRef: transcript.bodyRef,
    initialScrollTop: transcript.initialScrollTop,
    resetKey: transcript.resetKey,
    eventCount: transcript.eventCount,
    onDecideApproval: transcript.onDecideApproval,
    approvalPending: transcript.approvalPending,
  }
  const panelActivity: ThreadPanelActivityInput = {
    working: activity.working,
    widgets: (
      <>
        {activity.widgets}
        <TaskStatusWidget
          messages={transcript.timeline.messages}
          approvalPending={transcript.approvalPending}
          onDecideApproval={activity.onDecideTaskApproval}
        />
      </>
    ),
    actionError: activity.actionError ?? null,
    onDismissActionError: activity.onDismissActionError,
  }
  return (
    <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
      <ThreadPanel
        transcript={threadPanelTranscript}
        mainView={mainView}
        composer={composer}
        activity={panelActivity}
        slots={{
          footer: (
            <ThreadStatusFooter
              environment={labels.environment}
              environmentReady={labels.environmentReady}
              gitBranch={labels.gitBranch}
              branchUsage={labels.branchUsage}
              contextWindow={labels.contextWindow}
            />
          ),
        }}
      />
    </ResourceBlobUrlContext.Provider>
  )
}