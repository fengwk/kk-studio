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
 * 1) Load an authoritative PostgreSQL snapshot.
 * 2) Listen after its durable revision. Redis-backed {@code realtime} text/thinking deltas are
 *    rendered as a transient overlay until the durable Entry arrives; PostgreSQL remains
 *    authoritative after reconnects and stream loss.
 */
export function useHarnessThreadRealtime(
  threadId: string,
  enabled: boolean,
  revision: string | undefined,
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
    if (!threadId || !enabled || revision == null) {
      setModelStream(null)
      return undefined
    }

    const invalidateSnapshot = () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) })
    let eventSource: EventSource | null = null
    let retryTimer: ReturnType<typeof setTimeout> | null = null
    let reconnectAttempts = 0
    let disposed = false
    const connect = () => {
      if (disposed) {
        return
      }
      eventSource?.close()
      eventSource = harnessService.createThreadRealtimeStream(threadId, revision)
      eventSource.addEventListener('revision', invalidateSnapshot as EventListener)
      eventSource.addEventListener('resync', invalidateSnapshot as EventListener)
      eventSource.addEventListener('realtime', handleRealtime as EventListener)
      eventSource.onerror = () => {
        eventSource?.close()
        if (retryTimer != null) {
          return
        }
        if (reconnectAttempts >= 3) {
          void invalidateSnapshot()
          return
        }
        reconnectAttempts += 1
        retryTimer = setTimeout(() => {
          retryTimer = null
          connect()
        }, reconnectAttempts * 250)
      }
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
    }

    setModelStream(null)
    connect()
    return () => {
      disposed = true
      if (retryTimer != null) {
        clearTimeout(retryTimer)
      }
      eventSource?.close()
    }
  }, [enabled, queryClient, revision, threadId])

  return modelStream?.threadId === threadId ? modelStream : null
}
