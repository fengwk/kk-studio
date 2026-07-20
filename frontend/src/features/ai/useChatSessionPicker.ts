import { useMemo } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import {
  isSessionRunning,
  sortChatSessionsWithRunningFirst,
  toSessionSelectionItemWithRunning,
} from '@/features/ai/chat-session-picker'
import type { PaneSortPreference } from '@/features/ai/chat-pane-state'
import type { HarnessSessionDTO, HarnessThreadDTO } from '@/shared/api/contracts'
import { chatService } from '@/shared/api/chat-service'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

/**
 * Chat-member Session picker data: loads sessions when open, then threads for
 * every member Session so running-first sort is global (not only the bound session).
 */
export function useChatSessionPicker(chatId: string, open: boolean, sessionSort: PaneSortPreference) {
  const sessionsQuery = useQuery({
    queryKey: queryKeys.chats.sessions(chatId),
    queryFn: () => chatService.listChatSessions(chatId),
    enabled: open && Boolean(chatId),
  })
  const sessions = useMemo(() => sessionsQuery.data ?? [], [sessionsQuery.data])

  // Dynamic list: length follows loaded Chat sessions; only active while picker is open.
  const threadQueries = useQueries({
    queries: sessions.map((session) => ({
      queryKey: queryKeys.sessions.threads(session.sessionId),
      queryFn: () => harnessService.listSessionThreads(session.sessionId),
      enabled: open && Boolean(session.sessionId),
    })),
  })

  const threadsBySessionId = useMemo(() => {
    const map = new Map<string, HarnessThreadDTO[]>()
    sessions.forEach((session, index) => {
      map.set(session.sessionId, threadQueries[index]?.data ?? [])
    })
    return map
  }, [sessions, threadQueries])

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
