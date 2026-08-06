import { useQuery } from '@tanstack/react-query'
import type { BackendDateTime } from '@/shared/api/contracts/base'
import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'
import { chatService } from '@/shared/api/chat-service'
import { queryKeys } from '@/shared/lib/query-keys'
import type { PaneSortPreference } from '@/features/ai/chat/chat-pane-state'

/**
 * Thread picker is strictly Chat-scoped: no global Thread list, no pagination/cursor. The Chat
 * returns the full Thread array and the client applies the stable sort preference.
 */
export function useChatThreadPicker(
  chatId: string,
  open: boolean,
  sort: PaneSortPreference,
) {
  const query = useQuery({
    queryKey: queryKeys.chats.threads(chatId),
    queryFn: () => chatService.listChatThreads(chatId),
    enabled: open && Boolean(chatId),
  })

  const items: HarnessThreadDTO[] = sortThreads(query.data ?? [], sort)

  return {
    items,
    error: query.error,
    isLoading: query.isLoading,
  }
}

export function sortThreads(
  threads: HarnessThreadDTO[],
  sort: PaneSortPreference,
): HarnessThreadDTO[] {
  // Both client sorts stay newest-first (mirrors the removed server-side list ordering).
  const sorted = [...threads]
  if (sort === 'created') {
    sorted.sort((left, right) => compareTimes(right.createTime, left.createTime))
  } else {
    sorted.sort((left, right) => compareTimes(right.updateTime, left.updateTime))
  }
  return sorted
}

function compareTimes(left: BackendDateTime, right: BackendDateTime): number {
  const l = backendTimeValue(left)
  const r = backendTimeValue(right)
  return l < r ? -1 : l > r ? 1 : 0
}

function backendTimeValue(value: BackendDateTime): number {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  if (typeof value === 'string' && value.trim()) {
    const parsed = Date.parse(value)
    return Number.isFinite(parsed) ? parsed : 0
  }
  if (Array.isArray(value) && value.length >= 3) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = value.map((part) => Number(part))
    return Date.UTC(year, (month || 1) - 1, day || 1, hour, minute, second)
  }
  return 0
}
