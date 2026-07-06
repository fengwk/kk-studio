import { useQuery } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAiConsoleSessionQueries() {
  const sessionsQuery = useQuery({
    queryKey: queryKeys.sessions.list,
    queryFn: () => agentService.listSessions(),
  })

  return {
    sessionsQuery,
    sessions: sessionsQuery.data?.results ?? [],
  }
}
