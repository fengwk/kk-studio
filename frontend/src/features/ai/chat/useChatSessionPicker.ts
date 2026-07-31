import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  groupThreadsBySessionId,
  isSessionRunning,
  sortChatSessionsWithRunningFirst,
  toSessionSelectionItemWithRunning,
} from '@/features/ai/chat/chat-session-picker'
import type { PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import type { HarnessSessionDTO, HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'

/**
 * Session picker data for `/session`: loads all Sessions plus all Threads when open, so
 * running-first sort covers every Session without per-Session Thread requests.
 */
export function useChatSessionPicker(open: boolean, sessionSort: PaneSortPreference) {
  const { locale } = useI18n()
  const sessionsQuery = useQuery({
    queryKey: queryKeys.sessions.list,
    queryFn: () => harnessService.listSessions(),
    enabled: open,
  })
  const threadsQuery = useQuery({
    queryKey: queryKeys.sessions.threadIndex(sessionSort),
    queryFn: () => listAllThreads(sessionSort),
    enabled: open,
  })
  const sessions = useMemo(() => sessionsQuery.data ?? [], [sessionsQuery.data])

  const threadsBySessionId = useMemo(
    () => groupThreadsBySessionId(threadsQuery.data ?? []),
    [threadsQuery.data],
  )

  const sessionItems = useMemo(() => {
    void locale
    const sorted = sortChatSessionsWithRunningFirst(sessions, threadsBySessionId, sessionSort)
    return sorted.map((session) =>
      toSessionSelectionItemWithRunning(session, isSessionRunning(threadsBySessionId.get(session.sessionId))),
    )
  }, [locale, sessionSort, sessions, threadsBySessionId])

  function findSession(sessionId: string): HarnessSessionDTO | undefined {
    return sessions.find((session) => session.sessionId === sessionId)
  }

  return {
    sessionsQuery,
    sessions,
    sessionItems,
    threadsBySessionId,
    findSession,
  }
}

export async function listAllThreads(sort: PaneSortPreference) {
  const threads: HarnessThreadDTO[] = []
  let cursor: string | undefined
  const seenCursors = new Set<string>()
  while (true) {
    const cursorKey = cursor ?? ''
    if (seenCursors.has(cursorKey)) {
      throw new Error('Thread pagination cursor loop detected')
    }
    seenCursors.add(cursorKey)
    const page = await harnessService.listThreads({ sort, cursor, limit: 100 })
    threads.push(...page.items)
    if (!page.nextCursor) {
      return threads
    }
    cursor = page.nextCursor
  }
}
