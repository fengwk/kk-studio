import { useQuery } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAiConsoleThreadQueries() {
  const threadsQuery = useQuery({
    queryKey: queryKeys.threads.list,
    queryFn: () => harnessService.listThreads(),
  })

  return {
    threadsQuery,
    threads: threadsQuery.data ?? [],
  }
}
