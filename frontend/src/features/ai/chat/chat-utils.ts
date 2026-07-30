import { includesSearch } from '@/features/ai/shared/search-utils'
import type {
  BackendDateTime,
  ChatDTO,
  InstantTimestamp,
} from '@/shared/api/contracts'

function backendTimeValue(value: BackendDateTime | unknown): number {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  if (typeof value === 'string' && value.trim()) {
    const parsed = Date.parse(value)
    return Number.isFinite(parsed) ? parsed : 0
  }
  if (Array.isArray(value) && value.length >= 3) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = value.map((part) =>
      Number(part),
    )
    const time = Date.UTC(year, (month || 1) - 1, day || 1, hour, minute, second)
    return Number.isFinite(time) ? time : 0
  }
  return 0
}

export function filterChats(chats: ChatDTO[], search: string): ChatDTO[] {
  return chats
    .filter((chat) => includesSearch(chat.title ?? '', search))
    .sort((left, right) => {
      const delta = backendTimeValue(right.createTime) - backendTimeValue(left.createTime)
      if (delta !== 0) {
        return delta
      }
      return String(right.id).localeCompare(String(left.id), undefined, { numeric: true })
    })
}

export function formatBackendDate(value: BackendDateTime | InstantTimestamp): string {
  if (!value) {
    return '-'
  }
  if (Array.isArray(value)) {
    const [year, month = 1, day = 1, hour = 0, minute = 0] = value
    if (!Number.isFinite(year)) {
      return '-'
    }
    return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(
      day,
    ).padStart(2, '0')} ${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
  }
  if (typeof value === 'number') {
    const milliseconds = Math.abs(value) < 100_000_000_000 ? value * 1000 : value
    return new Date(milliseconds).toISOString().replace('T', ' ').slice(0, 16)
  }
  return value.replace('T', ' ').slice(0, 16)
}
