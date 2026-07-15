import type { QueryClient } from '@tanstack/react-query'
import { mergeSessionEventLists } from '@/features/ai/session-event-stream'
import { createSessionApi } from '@/shared/api/agent-service'
import type { AgentSessionEventDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

export async function loadMergedSessionEvents(queryClient: QueryClient, sessionId: string) {
  const snapshot = await createSessionApi().listEvents(sessionId)
  const cachedEvents = queryClient.getQueryData<AgentSessionEventDTO[]>(queryKeys.sessions.events(sessionId)) ?? []
  return mergeSessionEventLists(snapshot, cachedEvents)
}

export async function invalidateSessionQueries(queryClient: QueryClient, sessionId: string) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: queryKeys.sessions.detail(sessionId) }),
    queryClient.invalidateQueries({ queryKey: queryKeys.sessions.events(sessionId) }),
    queryClient.invalidateQueries({ queryKey: queryKeys.sessions.runs(sessionId) }),
    queryClient.invalidateQueries({ queryKey: queryKeys.sessions.list }),
  ])
}
