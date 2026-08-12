import { useCallback } from 'react'
import type { RefObject, ReactNode } from 'react'
import {
  ThreadPanel,
  ThreadStatusFooter,
  type ThreadPanelActivityInput,
  type ThreadPanelComposerInput,
  type ThreadPanelTranscriptInput,
} from '@/features/ai/runtime/thread-panel'
import {
  ResourceBlobUrlContext,
  type ResourceBlobUrls,
} from '@/features/ai/runtime/thread-panel/messages/ResourceBlobUrlContext'
import { storageService } from '@/shared/api/storage-service'
import type {
  DialogueMessage,
  QueuedThreadMessage,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'

/** 在 footer 中展示的稳定 runtime/model 标识；当面板为空时部分字段可能缺失。 */
export interface ChatPanelLabels {
  agentName?: string
  providerName?: string
  modelName?: string
  variantName?: string
  environmentName?: string | null
  environmentReady?: boolean
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
  loading: boolean
  error: unknown
  onDecideApproval?: (message: ToolDialogueMessage, decision: 'ALLOW' | 'DENY') => void
  /** 进行中的全局审批请求：所有未决的审批条都会禁用其按钮。 */
  approvalPending?: boolean
}

/** Composer 的调用点与转发的回调函数。 */
export type ChatPanelComposerInput = ThreadPanelComposerInput

/** 精简的 footer 数据：runtime 标识 + 可点击目标。snapshot 中不包含汇总用量。 */
export interface ChatPanelFooterInput {
  yoloEnabled?: boolean
  onAgentClick?: () => void
  onModelClick?: () => void
  onVariantClick?: () => void
  onEnvironmentClick?: () => void
  taskStatusEnabled?: boolean
  notificationsEnabled?: boolean
  notificationPermission?: 'default' | 'denied' | 'granted' | 'unsupported'
  onTaskStatusToggle?: () => void
  onNotificationsToggle?: () => void
}

/** Composer 上方展示的工作状态与可关闭的反馈信息。 */
export interface ChatPanelActivityInput {
  working: boolean
  /** 追加到 ThreadWidgetStack.children 的自定义 widget（如子任务状态）。 */
  widgets?: ReactNode
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
  composer,
  footer,
  activity,
}: {
  labels: ChatPanelLabels
  transcript: ChatPanelTranscriptInput
  composer: ChatPanelComposerInput
  footer: ChatPanelFooterInput
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
    onDecideApproval: transcript.onDecideApproval,
    approvalPending: transcript.approvalPending,
  }
  const panelActivity: ThreadPanelActivityInput = {
    working: activity.working,
    widgets: activity.widgets,
    actionError: activity.actionError ?? null,
    onDismissActionError: activity.onDismissActionError,
  }
  return (
    <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
      <ThreadPanel
        transcript={threadPanelTranscript}
        composer={composer}
        activity={panelActivity}
        slots={{
          footer: (
            <ThreadStatusFooter
              agentName={labels.agentName}
              providerName={labels.providerName}
              modelName={labels.modelName}
              variantName={labels.variantName}
              environmentName={labels.environmentName}
              environmentReady={labels.environmentReady}
              yoloEnabled={footer.yoloEnabled}
              onAgentClick={footer.onAgentClick}
              onModelClick={footer.onModelClick}
              onVariantClick={footer.onVariantClick}
              onEnvironmentClick={footer.onEnvironmentClick}
              taskStatusEnabled={footer.taskStatusEnabled}
              notificationsEnabled={footer.notificationsEnabled}
              notificationPermission={footer.notificationPermission}
              onTaskStatusToggle={footer.onTaskStatusToggle}
              onNotificationsToggle={footer.onNotificationsToggle}
            />
          ),
        }}
      />
    </ResourceBlobUrlContext.Provider>
  )
}