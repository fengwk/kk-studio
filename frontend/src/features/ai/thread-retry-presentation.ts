import { getInteger, parsePayload } from '@/features/ai/thread-event-payload'
import type { HarnessThreadDTO, ThreadEventDTO } from '@/shared/api/contracts'

export interface ThreadRetryPresentation {
  workingLabel: string | null
  stoppedNotice: string | null
  queueLabel: string
}

/** Converts durable retry state and its journal metadata into concise Thread UI copy. */
export function deriveThreadRetryPresentation(
  thread: HarnessThreadDTO | undefined,
  events: ThreadEventDTO[],
  now = Date.now(),
): ThreadRetryPresentation {
  if (thread?.status === 'FAILED') {
    return {
      workingLabel: null,
      stoppedNotice: '本次请求已停止；发送新消息可重新开始。',
      queueLabel: '等待重启',
    }
  }
  if (thread?.status !== 'RETRYING') {
    return { workingLabel: null, stoppedNotice: null, queueLabel: 'queued' }
  }

  const payload = latestRetrySchedulePayload(events)
  const attempt = getInteger(payload.retryAttempt) ?? thread.retryAttempt ?? 0
  const maxRetries = getInteger(payload.maxRetries)
  const progress = maxRetries == null || maxRetries <= 0 ? `${attempt}` : `${attempt}/${maxRetries}`
  const retryAt = parseTime(payload.retryAt ?? thread.retryAt)
  if (!thread.processing && retryAt != null && retryAt > now) {
    const seconds = Math.max(1, Math.ceil((retryAt - now) / 1_000))
    return {
      workingLabel: `${seconds} 秒后进行第 ${progress} 次自动重试…`,
      stoppedNotice: null,
      queueLabel: 'queued',
    }
  }
  return {
    workingLabel: `正在进行第 ${progress} 次自动重试…`,
    stoppedNotice: null,
    queueLabel: 'queued',
  }
}

function latestRetrySchedulePayload(events: ThreadEventDTO[]): Record<string, unknown> {
  for (let index = events.length - 1; index >= 0; index -= 1) {
    const event = events[index]
    if (event?.eventType === 'thread_retry_scheduled') {
      return parsePayload(event.payloadJson)
    }
  }
  return {}
}

function parseTime(value: unknown): number | null {
  if (typeof value === 'string' && value.trim()) {
    // Thread DTO LocalDateTime values are rendered by the backend in UTC without an offset.
    // Event payload Instants already carry Z; preserve any explicit offset unchanged.
    const raw = value.trim()
    const timestamp = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(raw) ? raw : `${raw}Z`
    const parsed = Date.parse(timestamp)
    return Number.isFinite(parsed) ? parsed : null
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  if (Array.isArray(value) && value.length >= 6 && value.every((item) => typeof item === 'number')) {
    const [year, month, day, hour, minute, second, milli = 0] = value as number[]
    return Date.UTC(year, month - 1, day, hour, minute, second, milli)
  }
  return null
}
