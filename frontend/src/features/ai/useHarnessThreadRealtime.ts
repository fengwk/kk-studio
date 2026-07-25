import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

/**
 * Snapshot-first realtime subscription.
 *
 * 1) Refresh authoritative PostgreSQL snapshot queries.
 * 2) Tail Redis-backed SSE (`realtime` events on `/events/stream`). Lossy deltas only
 *    invalidate caches; authoritative state is always reloaded from REST.
 */
export function useHarnessThreadRealtime(threadId: string, enabled: boolean) {
  const queryClient = useQueryClient()

  useEffect(() => {
    if (!threadId || !enabled) {
      return undefined
    }

    void Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(threadId) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.threads.entries(threadId) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.threads.inputs(threadId) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.threads.toolInvocations(threadId) }),
    ])

    const eventSource = harnessService.createThreadRealtimeStream(threadId, '0-0')
    const handleRealtime = () => {
      void Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.entries(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.inputs(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.toolInvocations(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.usage.thread(threadId) }),
      ])
    }

    eventSource.addEventListener('realtime', handleRealtime as EventListener)
    eventSource.onerror = () => {
      // Redis/SSE is best-effort; snapshot queries remain authoritative.
    }
    return () => eventSource.close()
  }, [enabled, queryClient, threadId])
}
