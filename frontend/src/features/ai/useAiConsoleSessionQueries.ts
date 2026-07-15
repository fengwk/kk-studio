import { useQuery } from '@tanstack/react-query'
import { createSessionApi } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAiConsoleSessionQueries() {
  const sessionApi = createSessionApi()
  const sessionsQuery = useQuery({
    queryKey: queryKeys.sessions.list,
    queryFn: sessionApi.list,
  })

  return {
    sessionsQuery,
    sessions: sessionsQuery.data?.results ?? [],
  }
}
