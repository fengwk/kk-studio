import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  isRealtimeModelStreamCommitted,
  parseRealtimeModelDelta,
  reduceRealtimeModelStream,
  type RealtimeModelStream,
} from '@/features/ai/thread-realtime-state'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import type { HarnessSessionEntryDTO } from '@/shared/api/contracts'

/**
 * Snapshot-first realtime subscription.
 *
 * 1) Refresh authoritative PostgreSQL snapshot queries.
 * 2) Tail Redis-backed SSE (`realtime` events on `/events/stream`). Text/thinking deltas are
 *    rendered as a transient overlay until the durable Entry arrives; PostgreSQL remains
 *    authoritative after reconnects and stream loss.
 */
export function useHarnessThreadRealtime(
  threadId: string,
  enabled: boolean,
  entries: HarnessSessionEntryDTO[],
): RealtimeModelStream | null {
  const queryClient = useQueryClient()
  const [modelStream, setModelStream] = useState<RealtimeModelStream | null>(null)
  const entriesRef = useRef(entries)

  useEffect(() => {
    entriesRef.current = entries
    setModelStream((current) =>
      current != null && isRealtimeModelStreamCommitted(current, entries) ? null : current,
    )
  }, [entries])

  useEffect(() => {
    if (!threadId || !enabled) {
      setModelStream(null)
      return undefined
    }

    const invalidateSnapshots = () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.entries(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.inputs(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.toolInvocations(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.usage.thread(threadId) }),
      ])
    void invalidateSnapshots()

    const eventSource = harnessService.createThreadRealtimeStream(threadId, '0-0')
    let refreshTimer: ReturnType<typeof setTimeout> | null = null
    const scheduleSnapshotRefresh = () => {
      if (refreshTimer != null) {
        return
      }
      refreshTimer = setTimeout(() => {
        refreshTimer = null
        void invalidateSnapshots()
      }, 250)
    }
    const handleRealtime = (event: Event) => {
      const delta = parseRealtimeModelDelta((event as MessageEvent<string>).data)
      if (
        delta != null
        && delta.threadId === threadId
        && !isRealtimeModelStreamCommitted(
          {
            threadId: delta.threadId,
            invocationId: delta.invocationId,
            attempt: delta.attempt,
            text: '',
            thinking: '',
            createdAt: delta.createdAt,
          },
          entriesRef.current,
        )
      ) {
        setModelStream((current) => reduceRealtimeModelStream(current, delta))
      }
      // Redis is a lossy projection. Coalesce REST reloads so token-sized deltas do not trigger
      // one HTTP round trip each while still converging statuses, usage, and durable entries.
      scheduleSnapshotRefresh()
    }

    setModelStream(null)
    eventSource.addEventListener('realtime', handleRealtime as EventListener)
    eventSource.onerror = () => {
      // Redis/SSE is best-effort; snapshot queries remain authoritative.
    }
    return () => {
      if (refreshTimer != null) {
        clearTimeout(refreshTimer)
      }
      eventSource.close()
    }
  }, [enabled, queryClient, threadId])

  return modelStream?.threadId === threadId ? modelStream : null
}
