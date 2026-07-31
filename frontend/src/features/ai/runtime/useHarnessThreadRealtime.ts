import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  isRealtimeModelDeltaGap,
  parseRealtimeModelDelta,
  reduceRealtimeModelStream,
  snapshotModelStream,
  type RealtimeModelStream,
} from '@/features/ai/runtime/thread-realtime-state'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import type { ModelInvocationDTO } from '@/shared/api/contracts/ai-runtime'

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
  modelInvocations: ModelInvocationDTO[],
): RealtimeModelStream | null {
  const queryClient = useQueryClient()
  const [modelStream, setModelStream] = useState<RealtimeModelStream | null>(null)
  const [subscription, setSubscription] = useState<{
    threadId: string
    revision: string
  } | null>(null)
  const modelStreamRef = useRef<RealtimeModelStream | null>(null)
  const invocationsRef = useRef(modelInvocations)
  const gapRef = useRef<{
    invocationId: string
    attempt: number
    sequence: number
  } | null>(null)
  const subscriptionReady = enabled && revision != null

  useEffect(() => {
    setSubscription((current) => {
      if (!subscriptionReady || revision == null) {
        return null
      }
      return current?.threadId === threadId ? current : { threadId, revision }
    })
  }, [revision, subscriptionReady, threadId])

  useEffect(() => {
    invocationsRef.current = modelInvocations
    const snapshot = snapshotModelStream(threadId, modelInvocations)
    const current = modelStreamRef.current
    let next = current
    if (snapshot == null) {
      gapRef.current = null
      next = null
    } else if (
      current == null
      || current.threadId !== snapshot.threadId
      || current.invocationId !== snapshot.invocationId
      || current.attempt !== snapshot.attempt
      || snapshot.sequence >= current.sequence
    ) {
      next = snapshot.sequence > 0 || snapshot.text || snapshot.thinking ? snapshot : null
      const gap = gapRef.current
      if (
        gap != null
        && gap.invocationId === snapshot.invocationId
        && gap.attempt === snapshot.attempt
        && snapshot.sequence >= gap.sequence
      ) {
        gapRef.current = null
      }
    }
    if (current != null && next != null && sameModelStream(current, next)) {
      next = current
    }
    modelStreamRef.current = next
    setModelStream(next)
  }, [modelInvocations, threadId])

  useEffect(() => {
    if (
      !threadId
      || !subscriptionReady
      || subscription == null
      || subscription.threadId !== threadId
    ) {
      modelStreamRef.current = null
      setModelStream(null)
      return undefined
    }

    const invalidateSnapshot = () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) })
    const handleRealtime = (event: Event) => {
      const delta = parseRealtimeModelDelta((event as MessageEvent<string>).data)
      if (delta == null || delta.threadId !== threadId) {
        return
      }
      const snapshot = snapshotModelStream(threadId, invocationsRef.current)
      if (
        snapshot == null
        || snapshot.invocationId !== delta.invocationId
        || snapshot.attempt !== delta.attempt
      ) {
        return
      }
      const current = modelStreamRef.current
      const base =
        current != null
        && current.invocationId === snapshot.invocationId
        && current.attempt === snapshot.attempt
          ? current
          : snapshot
      if (isRealtimeModelDeltaGap(base, delta)) {
        const gap = gapRef.current
        if (
          gap == null
          || gap.invocationId !== delta.invocationId
          || gap.attempt !== delta.attempt
        ) {
          gapRef.current = {
            invocationId: delta.invocationId,
            attempt: delta.attempt,
            sequence: delta.sequence,
          }
          void invalidateSnapshot()
        }
        return
      }
      const next = reduceRealtimeModelStream(base, delta)
      modelStreamRef.current = next
      setModelStream(next)
    }

    const eventSource = harnessService.createThreadRealtimeStream(
      subscription.threadId,
      subscription.revision,
    )
    eventSource.addEventListener('revision', invalidateSnapshot as EventListener)
    eventSource.addEventListener('resync', invalidateSnapshot as EventListener)
    eventSource.addEventListener('realtime', handleRealtime as EventListener)
    // EventSource owns reconnect and keeps the same transport instance until this effect cleans up.
    eventSource.onerror = () => {
      void invalidateSnapshot()
    }
    return () => {
      eventSource.close()
    }
  }, [queryClient, subscription, subscriptionReady, threadId])

  return modelStream?.threadId === threadId ? modelStream : null
}

function sameModelStream(left: RealtimeModelStream, right: RealtimeModelStream): boolean {
  return left.threadId === right.threadId
    && left.invocationId === right.invocationId
    && left.attempt === right.attempt
    && left.text === right.text
    && left.thinking === right.thinking
    && left.sequence === right.sequence
}
