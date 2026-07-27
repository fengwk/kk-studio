import type { RefObject } from 'react'
import { ThreadPanel } from '@/features/ai/thread-panel'
import { ThreadStatusFooter } from '@/features/ai/thread-panel/ThreadStatusFooter'
import type { ThreadCommand } from '@/features/ai/thread-panel/thread-commands'
import type { ThreadTimeline } from '@/features/ai/thread-timeline'
import type { BackendLong, ModelUsageSummaryDTO } from '@/shared/api/contracts'

/**
 * Pane-scoped Thread adapter over ThreadPanel.
 * No permanent Session/Thread sidebar; agent/model labels come from Thread DTO.
 */
export function ChatPanel({
  timeline,
  runtimeLabels,
  working,
  messagesLoading,
  messagesError,
  bodyRef,
  draft,
  pending,
  disabled,
  observability,
  actionError,
  onDismissActionError,
  onDraftChange,
  onSubmit,
  onCommand,
  commands,
  onAgentClick,
  onModelClick,
  onVariantClick,
}: {
  timeline: ThreadTimeline
  runtimeLabels?: {
    agentName: string
    providerName: string
    modelName: string
    variantName: string
    contextWindow?: number
  }
  working: boolean
  messagesLoading: boolean
  messagesError: unknown
  bodyRef: RefObject<HTMLDivElement | null>
  draft: string
  pending: boolean
  disabled: boolean
  observability: {
    yolo?: { enabled: boolean }
    usage?: ModelUsageSummaryDTO
    observabilityError: unknown
    yoloPending: boolean
    setYolo: (enabled: boolean, expectedExecutionEpoch: BackendLong) => void
  }
  actionError?: string | null
  onDismissActionError?: () => void
  onDraftChange: (draft: string) => void
  onSubmit: () => void
  onCommand: (command: ThreadCommand) => void
  commands?: ThreadCommand[]
  onAgentClick?: () => void
  onModelClick?: () => void
  onVariantClick?: () => void
}) {
  return (
    <ThreadPanel
      messages={timeline.messages}
      queuedMessages={timeline.queuedMessages}
      messagesLoading={messagesLoading}
      messagesError={messagesError}
      bodyRef={bodyRef}
      draft={draft}
      composerDisabled={disabled}
      composerPending={pending}
      working={working}
      actionError={actionError}
      onDismissActionError={onDismissActionError}
      onDraftChange={onDraftChange}
      onSubmit={onSubmit}
      onCommand={onCommand}
      commands={commands}
      footer={
        <ThreadStatusFooter
          agentName={runtimeLabels?.agentName}
          providerName={runtimeLabels?.providerName}
          modelName={runtimeLabels?.modelName}
          variantName={runtimeLabels?.variantName}
          contextWindow={runtimeLabels?.contextWindow}
          yoloEnabled={observability.yolo?.enabled}
          usage={observability.usage}
          onAgentClick={onAgentClick}
          onModelClick={onModelClick}
          onVariantClick={onVariantClick}
        />
      }
    />
  )
}
