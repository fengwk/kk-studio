import type { DialogueTimestamp } from '@/features/ai/runtime/thread-timeline-types'

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

function timestampMillis(value: DialogueTimestamp): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return Math.abs(value) < 100_000_000_000 ? value * 1000 : value
  }
  if (typeof value === 'string' && value.trim()) {
    const parsed = Date.parse(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  if (Array.isArray(value) && value.length >= 3) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = value.map(Number)
    const parsed = Date.UTC(year, month - 1, day, hour, minute, second)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}
