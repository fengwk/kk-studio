import type { RefObject } from 'react'
import {
  ThreadPanel,
  ThreadStatusFooter,
  type ThreadPanelActivityInput,
  type ThreadPanelComposerInput,
  type ThreadPanelTranscriptInput,
} from '@/features/ai/runtime/thread-panel'
import type {
  DialogueMessage,
  QueuedThreadMessage,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'

/** Stable runtime/model identity surfaced in the footer; fields may be absent while a pane is blank. */
export interface ChatPanelLabels {
  agentName?: string
  providerName?: string
  modelName?: string
  variantName?: string
  environmentDisplayName?: string | null
  contextWindow?: number
}

/** Transcript (durable timeline + live mailbox) plus loading/error state. */
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
  /** Global approval request in flight: every undecided approval bar disables its buttons. */
  approvalPending?: boolean
}

/** Composer call sites and forwarded callbacks. */
export type ChatPanelComposerInput = ThreadPanelComposerInput

/** Narrow footer data: runtime identity + click targets. Aggregate usage is not on the snapshot. */
export interface ChatPanelFooterInput {
  yoloEnabled?: boolean
  onAgentClick?: () => void
  onModelClick?: () => void
  onVariantClick?: () => void
  onEnvironmentClick?: () => void
}

/** Work state and dismissible feedback shown above the composer. */
export interface ChatPanelActivityInput {
  working: boolean
  actionError?: string | null
  onDismissActionError?: () => void
}

/**
 * Pane-scoped Thread adapter over ThreadPanel. No permanent Session/Thread sidebar; runtime
 * labels come from the controller but never as backend DTOs.
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
    actionError: activity.actionError ?? null,
    onDismissActionError: activity.onDismissActionError,
  }
  return (
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
            environmentDisplayName={labels.environmentDisplayName}
            yoloEnabled={footer.yoloEnabled}
            onAgentClick={footer.onAgentClick}
            onModelClick={footer.onModelClick}
            onVariantClick={footer.onVariantClick}
            onEnvironmentClick={footer.onEnvironmentClick}
          />
        ),
      }}
    />
  )
}