export { buildThreadTimeline } from '@/features/ai/runtime/thread-timeline-builder'
import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'
import type { ThreadTimeline } from '@/features/ai/runtime/thread-timeline-types'

/**
 * Working status uses the derived Thread status/processing and pending QUEUED commands.
 * Any status other than IDLE means the runtime is working on the Thread.
 * Realtime SSE keeps entries/commands fresh by invalidating snapshot queries.
 */
export function isThreadWorking(
  thread: HarnessThreadDTO | undefined,
  timeline?: ThreadTimeline,
): boolean {
  if (thread?.processing || (thread?.status != null && thread.status !== 'IDLE')) {
    return true
  }
  return Boolean(timeline?.hasPendingInputs)
}
