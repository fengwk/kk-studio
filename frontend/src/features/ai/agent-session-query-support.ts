import type { QueryClient } from '@tanstack/react-query'
import { mergeSessionEventLists } from '@/features/ai/session-event-stream'
import { createWorkspaceSessionApi } from '@/shared/api/agent-service'
import type { AgentSessionEventDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

export async function loadMergedSessionEvents(queryClient: QueryClient, workspaceId: string, sessionId: string) {
  const snapshot = await createWorkspaceSessionApi(workspaceId).listEvents(sessionId)
  const cachedEvents = queryClient.getQueryData<AgentSessionEventDTO[]>(queryKeys.sessions.events(workspaceId, sessionId)) ?? []
  return mergeSessionEventLists(snapshot, cachedEvents)
}

export async function invalidateSessionQueries(queryClient: QueryClient, workspaceId: string, sessionId: string) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: queryKeys.sessions.detail(workspaceId, sessionId) }),
    queryClient.invalidateQueries({ queryKey: queryKeys.sessions.events(workspaceId, sessionId) }),
    queryClient.invalidateQueries({ queryKey: queryKeys.sessions.runs(workspaceId, sessionId) }),
    queryClient.invalidateQueries({ queryKey: queryKeys.sessions.list(workspaceId) }),
  ])
}
