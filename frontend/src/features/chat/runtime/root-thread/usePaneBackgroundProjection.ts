import { useEffect, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useApplicationEvents } from '@/shared/app-events'
import { queryKeys } from '@/shared/lib/query-keys'
import type { HarnessThreadSnapshotDTO } from '@/shared/api/generated/contracts'

/**
 * Retain at most one inactive running Thread projection. The active Thread
 * owns its realtime subscription in useHarnessThreadRealtime; an inactive
 * terminal Thread never gets a background listener.
 */
export function usePaneBackgroundProjection(boundThreadId: string): void {
  const queryClient = useQueryClient()
  const applicationEvents = useApplicationEvents()
  const previousBoundThreadRef = useRef<string | null>(null)
  const backgroundSubscriptionRef = useRef<{
    threadId: string
    unsubscribe: () => void
  } | null>(null)

  useEffect(() => {
    const previous = previousBoundThreadRef.current
    if (previous == null || previous === boundThreadId) {
      previousBoundThreadRef.current = boundThreadId || null
      return
    }
    const currentBackground = backgroundSubscriptionRef.current
    if (currentBackground != null && currentBackground.threadId !== previous) {
      currentBackground.unsubscribe()
      backgroundSubscriptionRef.current = null
    }
    if (backgroundSubscriptionRef.current == null) {
      const snapshot = queryClient.getQueryData<HarnessThreadSnapshotDTO>(
        queryKeys.threads.snapshot(previous),
      )
      if (needsBackgroundProjection(snapshot)) {
        const threadId = previous
        let unsubscribe: () => void = () => undefined
        const refresh = () => {
          const refreshPromise = queryClient.invalidateQueries({
            queryKey: queryKeys.threads.snapshot(threadId),
          })
          void refreshPromise.then(() => {
            const latest = queryClient.getQueryData<HarnessThreadSnapshotDTO>(
              queryKeys.threads.snapshot(threadId),
            )
            if (!needsBackgroundProjection(latest)
              && backgroundSubscriptionRef.current?.threadId === threadId) {
              unsubscribe()
              backgroundSubscriptionRef.current = null
            }
          })
        }
        if (applicationEvents != null) {
          unsubscribe = applicationEvents.subscribe(
            { kind: 'thread', id: threadId },
            {
              onSubscribed: refresh,
              onEvent: (name) => {
                if (name === 'version') {
                  refresh()
                }
              },
              onResync: refresh,
              onError: refresh,
            },
          )
          backgroundSubscriptionRef.current = { threadId, unsubscribe }
        }
      }
    }
    previousBoundThreadRef.current = boundThreadId || null
  }, [applicationEvents, boundThreadId, queryClient])

  useEffect(() => () => {
    backgroundSubscriptionRef.current?.unsubscribe()
    backgroundSubscriptionRef.current = null
  }, [])
}

function needsBackgroundProjection(snapshot: HarnessThreadSnapshotDTO | undefined): boolean {
  return snapshot != null
    && (snapshot.thread.processing || snapshot.thread.status !== 'IDLE')
}
