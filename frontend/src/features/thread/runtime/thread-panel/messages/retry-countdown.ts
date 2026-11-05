import type { DialogueTimestamp } from '@/features/thread/runtime/thread-timeline-types'
import { timestampMillis } from '@/shared/lib/date-format'

export function retryCountdownSeconds(
  retryAt: DialogueTimestamp,
  now = Date.now(),
): number {
  const retryAtMillis = timestampMillis(retryAt)
  if (retryAtMillis == null) {
    return 0
  }
  return Math.max(0, Math.ceil((retryAtMillis - now) / 1000))
}
