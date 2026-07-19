import { useQuery } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAiConsoleSessionQueries() {
  const sessionsQuery = useQuery({
    queryKey: queryKeys.sessions.list,
    queryFn: () => harnessService.listSessions(),
  })

  return {
    sessionsQuery,
    sessions: sessionsQuery.data ?? [],
  }
}
