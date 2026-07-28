export { buildThreadTimeline } from '@/features/ai/thread-timeline-builder'
export type {
  DialogueMessage,
  DialogueRole,
  DialogueStatus,
  DialogueTimestamp,
  EntryEventDialogueMessage,
  EntryEventKind,
  MetaDialogueMessage,
  MetaMessageKind,
  QueuedThreadMessage,
  TextDialogueMessage,
  ThreadTimeline,
  ToolAttachment,
  ToolAttachmentType,
  ToolDialogueMessage,
} from '@/features/ai/thread-timeline-types'
import type { HarnessThreadDTO } from '@/shared/api/contracts'
import type { ThreadTimeline } from '@/features/ai/thread-timeline-types'

/**
 * Working status uses derived Thread status/processing and pending QUEUED inputs.
 * Active statuses: RUNNING > WAITING > RUNNABLE (IDLE is not working).
 * Realtime SSE keeps entries/inputs fresh by invalidating snapshot queries.
 */
export function isThreadWorking(
  thread: HarnessThreadDTO | undefined,
  timeline?: ThreadTimeline,
): boolean {
  if (
    thread?.status === 'RUNNING'
    || thread?.status === 'WAITING'
    || thread?.status === 'RUNNABLE'
    || thread?.processing
  ) {
    return true
  }
  return Boolean(timeline?.hasPendingInputs)
}
