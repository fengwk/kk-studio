export { buildThreadTimeline } from '@/features/ai/thread-timeline-builder'
export type {
  DialogueMessage,
  DialogueRole,
  DialogueStatus,
  MetaDialogueMessage,
  MetaMessageKind,
  QueuedThreadMessage,
  TextDialogueMessage,
  ThreadTimeline,
  ToolAttachment,
  ToolAttachmentType,
  ToolDialogueMessage,
} from '@/features/ai/thread-event-types'
import type { HarnessThreadDTO } from '@/shared/api/contracts'
import type { ThreadTimeline } from '@/features/ai/thread-event-types'

/**
 * Working status uses rendered projection, not raw input DTO appliedEntryId nulls:
 * durable actor status, pending input overlays, or live stream projection.
 */
export function isThreadWorking(
  thread: HarnessThreadDTO | undefined,
  timeline?: ThreadTimeline,
): boolean {
  if (thread?.status === 'RUNNING' || thread?.status === 'WAITING' || thread?.status === 'RETRYING' || thread?.processing) {
    return true
  }
  return Boolean(timeline?.hasPendingInputs || timeline?.hasLiveProjection)
}
