export { buildThreadTimeline } from '@/features/ai/runtime/thread-timeline-builder'
import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'
import type { ThreadTimeline } from '@/features/ai/runtime/thread-timeline-types'

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
