import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  groupThreadsBySessionId,
  isSessionRunning,
  sortChatSessionsWithRunningFirst,
  toSessionSelectionItemWithRunning,
} from '@/features/ai/chat-session-picker'
import type { PaneSortPreference } from '@/features/ai/chat-pane-state'
import type { HarnessSessionDTO } from '@/shared/api/contracts'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

/**
 * Session picker data for `/session`: loads all Sessions plus all Threads when open, so
 * running-first sort covers every Session without per-Session Thread requests.
 */
export function useChatSessionPicker(open: boolean, sessionSort: PaneSortPreference) {
  const sessionsQuery = useQuery({
    queryKey: queryKeys.sessions.list,
    queryFn: () => harnessService.listSessions(),
    enabled: open,
  })
  const threadsQuery = useQuery({
    queryKey: queryKeys.threads.list,
    queryFn: () => harnessService.listThreads(),
    enabled: open,
  })
  const sessions = useMemo(() => sessionsQuery.data ?? [], [sessionsQuery.data])

  const threadsBySessionId = useMemo(
    () => groupThreadsBySessionId(threadsQuery.data ?? []),
    [threadsQuery.data],
  )

  const sessionItems = useMemo(() => {
    const sorted = sortChatSessionsWithRunningFirst(sessions, threadsBySessionId, sessionSort)
    return sorted.map((session) =>
      toSessionSelectionItemWithRunning(session, isSessionRunning(threadsBySessionId.get(session.sessionId))),
    )
  }, [sessionSort, sessions, threadsBySessionId])

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
