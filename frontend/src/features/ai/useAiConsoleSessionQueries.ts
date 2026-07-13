import { useQuery } from '@tanstack/react-query'
import { createWorkspaceSessionApi } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAiConsoleSessionQueries(workspaceId: string) {
  const sessionApi = createWorkspaceSessionApi(workspaceId)
  const sessionsQuery = useQuery({
    queryKey: queryKeys.sessions.list(workspaceId),
    queryFn: sessionApi.list,
  })

  return {
    sessionsQuery,
    sessions: sessionsQuery.data?.results ?? [],
  }
}
