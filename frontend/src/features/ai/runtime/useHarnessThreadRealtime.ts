import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import {
  isRealtimeModelDeltaGap,
  isRealtimeToolStreamActive,
  parseRealtimeModelDelta,
  parseRealtimeToolPartial,
  reduceRealtimeModelStream,
  reduceRealtimeToolStream,
  snapshotModelStream,
  snapshotToolStream,
  type RealtimeModelStream,
  type RealtimeToolPartial,
  type RealtimeToolStream,
} from '@/features/ai/runtime/thread-realtime-state'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import type { ModelInvocationDTO, ToolInvocationDTO } from '@/shared/api/contracts/ai-runtime'
import type { ToolAttachment } from '@/features/ai/runtime/thread-timeline-types'

export interface HarnessThreadRealtimeState {
  /** Transient model overlay for the active ModelInvocation attempt (null when none). */
  modelStream: RealtimeModelStream | null
  /** Transient tool-result overlays keyed by ToolInvocation id. */
  toolStreams: ReadonlyMap<string, RealtimeToolStream>
}

/**
 * Snapshot-first realtime subscription.
 *
 * 1) Load an authoritative PostgreSQL snapshot.
 * 2) Listen after its durable revision. Redis-backed {@code realtime} MODEL_DELTA / TOOL_PARTIAL
 *    events render as transient overlays until the durable Entries arrive; PostgreSQL remains
 *    authoritative after reconnects and stream loss. Redis is never durable truth.
 */
export function useHarnessThreadRealtime(
  threadId: string,
  enabled: boolean,
  revision: string | undefined,
  modelInvocation: ModelInvocationDTO | null,
  toolInvocations: ToolInvocationDTO[],
): HarnessThreadRealtimeState {
  const queryClient = useQueryClient()
  const [modelStream, setModelStream] = useState<RealtimeModelStream | null>(null)
  const [toolStreams, setToolStreams] = useState<ReadonlyMap<string, RealtimeToolStream>>(
    () => new Map(),
  )
  const [subscription, setSubscription] = useState<{
    threadId: string
    revision: string
  } | null>(null)
  const modelStreamRef = useRef<RealtimeModelStream | null>(null)
  const toolStreamsRef = useRef<Map<string, RealtimeToolStream>>(new Map())
  const invocationRef = useRef<ModelInvocationDTO | null>(modelInvocation)
  const toolInvocationsRef = useRef<ToolInvocationDTO[]>(toolInvocations)
  const gapRef = useRef<{
    invocationId: string
    attempt: number
    sequence: number
  } | null>(null)
  // Bounded exact-duplicate fingerprints for TOOL_PARTIAL (Redis may redeliver): keyed by
  // thread:invocation:attempt so attempt changes/terminal snapshots naturally evict them.
  const toolPartialFingerprintsRef = useRef<Map<string, Set<string>>>(new Map())
  const subscriptionReady = enabled && revision != null

  const { requestGapRecovery, clearRecoveryLoop } = useGapRecoveryLoop(
    queryClient,
    modelStreamRef,
    invocationRef,
  )

  useEffect(() => {
    setSubscription((current) => {
      if (!subscriptionReady || revision == null) {
        return null
      }
      return current?.threadId === threadId ? current : { threadId, revision }
    })
  }, [revision, subscriptionReady, threadId])

  useEffect(() => {
    invocationRef.current = modelInvocation
    toolInvocationsRef.current = toolInvocations
    const snapshot = snapshotModelStream(threadId, modelInvocation)
    const current = modelStreamRef.current
    let next = current
    if (snapshot == null) {
      gapRef.current = null
      next = null
    } else if (snapshot.status !== 'streaming') {
      // Durable terminal boundary: the resultJson/errorJson projection unconditionally
      // supersedes any higher-sequence Redis overlay (a streaming seq8 must never beat a
      // durable seq7 result). resultEntryId == null keeps the projection visible until the
      // durable Entry lands; the event handler fence rejects further deltas meanwhile.
      next = snapshot
      const gap = gapRef.current
      if (
        gap != null
        && gap.invocationId === snapshot.invocationId
        && gap.attempt === snapshot.attempt
        && snapshot.sequence >= gap.sequence
      ) {
        gapRef.current = null
      }
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

    // Reconcile tool overlays against the durable snapshot. A terminal invocation
    // (resultJson/errorJson) is projected in full ONLY until its durable result Entry is
    // attached: resultEntryId is the durable-truth cursor, so streams are deleted exactly
    // when it arrives (or when the invocation disappears). A terminal projection replaces
    // the partial fragments; a RUNNING invocation keeps its same-attempt partials.
    const streams = new Map(toolStreamsRef.current)
    const activeIds = new Set<string>()
    for (const invocation of toolInvocations) {
      if (invocation.resultEntryId != null) {
        streams.delete(invocation.id)
        continue
      }
      activeIds.add(invocation.id)
      const existing = streams.get(invocation.id)
      const seeded = snapshotToolStream(invocation, threadId)
      if (seeded == null) {
        if (existing != null && !isRealtimeToolStreamActive(existing, invocation)) {
          streams.delete(invocation.id)
        }
        continue
      }
      if (invocation.resultJson != null || invocation.errorJson != null) {
        // Terminal durable projection wins over partial fragments.
        streams.set(invocation.id, seeded)
      } else if (existing == null) {
        streams.set(invocation.id, seeded)
      } else if (!isRealtimeToolStreamActive(existing, invocation)) {
        streams.delete(invocation.id)
      }
    }
    for (const key of [...streams.keys()]) {
      if (!activeIds.has(key)) {
        streams.delete(key)
      }
    }
    // Exact-duplicate fingerprints are scoped to active same-attempt invocations: attempt
    // changes, terminal snapshots, attached result Entries and vanished invocations evict them.
    const fingerprints = new Map<string, Set<string>>()
    for (const invocation of toolInvocations) {
      if (
        invocation.resultEntryId == null
        && invocation.resultJson == null
        && invocation.errorJson == null
      ) {
        const key = `${threadId}:${invocation.id}:${invocation.attempt}`
        const existing = toolPartialFingerprintsRef.current.get(key)
        if (existing != null) {
          fingerprints.set(key, existing)
        }
      }
    }
    toolPartialFingerprintsRef.current = fingerprints
    // Bail out when the overlay map content is unchanged so this effect never re-triggers
    // renders by itself (stable query-derived inputs make re-runs no-ops).
    if (!sameToolStreamMap(toolStreamsRef.current, streams)) {
      toolStreamsRef.current = streams
      setToolStreams(streams)
    }
  }, [modelInvocation, threadId, toolInvocations])

  useEffect(() => {
    if (
      !threadId
      || !subscriptionReady
      || subscription == null
      || subscription.threadId !== threadId
    ) {
      clearRecoveryLoop()
      modelStreamRef.current = null
      setModelStream(null)
      toolStreamsRef.current = new Map()
      setToolStreams(new Map())
      toolPartialFingerprintsRef.current = new Map()
      return undefined
    }

    const invalidateSnapshot = () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) })
    const handleRealtime = (event: Event) => {
      const raw = (event as MessageEvent<string>).data
      const delta = parseRealtimeModelDelta(raw)
      if (delta != null && delta.threadId === threadId) {
        // Durable terminal fence: a terminal ModelInvocation (result/error/attached result
        // Entry) rejects every late MODEL_DELTA. The frozen checkpoint overlay stays visible
        // until the Entry lands, but terminal output is never appended to.
        const durable = invocationRef.current
        if (
          durable == null
          || durable.resultJson != null
          || durable.errorJson != null
          || durable.resultEntryId != null
        ) {
          return
        }
        const snapshot = snapshotModelStream(threadId, durable)
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
          gapRef.current = {
            invocationId: delta.invocationId,
            attempt: delta.attempt,
            sequence: delta.sequence,
          }
          // Single-flight recovery: one refetch at a time, re-armed with bounded backoff
          // until the checkpoint catches up (never one-shot, never per-delta concurrent).
          requestGapRecovery(threadId, delta.invocationId, delta.attempt, delta.sequence)
          return
        }
        const next = reduceRealtimeModelStream(base, delta)
        modelStreamRef.current = next
        setModelStream(next)
        return
      }
      const partial = parseRealtimeToolPartial(raw)
      if (partial == null || partial.threadId !== threadId) {
        return
      }
      // Snapshot-first: aggregate a partial ONLY while its invocation is still active in the
      // durable snapshot (same attempt, no terminal result, no attached result Entry). Once
      // the terminal resultJson/errorJson (or the result Entry) is authoritative, late or
      // duplicated Redis partials must never append to the complete terminal projection.
      const invocation = toolInvocationsRef.current.find(
        (item) => item.id === partial.invocationId,
      )
      if (
        invocation == null
        || invocation.attempt !== partial.attempt
        || invocation.resultJson != null
        || invocation.errorJson != null
        || invocation.resultEntryId != null
      ) {
        return
      }
      // Exact-duplicate fence: Redis may redeliver the same TOOL_PARTIAL event; a stable
      // fingerprint (canonical payload + createdAt) must not append the chunk twice.
      const fingerprintKey = `${threadId}:${partial.invocationId}:${partial.attempt}`
      let fingerprints = toolPartialFingerprintsRef.current.get(fingerprintKey)
      if (fingerprints == null) {
        fingerprints = new Set()
        toolPartialFingerprintsRef.current.set(fingerprintKey, fingerprints)
      }
      const fingerprint = fingerprintOf(partial)
      if (fingerprints.has(fingerprint)) {
        return
      }
      // Bounded FIFO: evict only the oldest fingerprint so the most recent N stay
      // deduplicated (a full clear would let an immediately redelivered event re-append).
      if (fingerprints.size >= TOOL_PARTIAL_FINGERPRINT_LIMIT) {
        const oldest = fingerprints.values().next().value
        if (oldest !== undefined) {
          fingerprints.delete(oldest)
        }
      }
      fingerprints.add(fingerprint)
      const streams = toolStreamsRef.current
      const current = streams.get(partial.invocationId) ?? null
      const next = reduceRealtimeToolStream(current, partial)
      streams.set(partial.invocationId, next)
      toolStreamsRef.current = streams
      setToolStreams(new Map(streams))
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
      clearRecoveryLoop()
      toolPartialFingerprintsRef.current = new Map()
    }
  }, [
    clearRecoveryLoop,
    queryClient,
    requestGapRecovery,
    subscription,
    subscriptionReady,
    threadId,
  ])

  const visibleModelStream = modelStream?.threadId === threadId ? modelStream : null
  const visibleToolStreams =
    toolStreams.size > 0
      ? new Map(
          [...toolStreams.entries()].filter(
            ([, stream]) => stream.threadId === threadId,
          ),
        )
      : toolStreams
  return {
    modelStream: visibleModelStream,
    toolStreams: visibleToolStreams,
  }
}

function sameToolStreamMap(
  left: ReadonlyMap<string, RealtimeToolStream>,
  right: ReadonlyMap<string, RealtimeToolStream>,
): boolean {
  if (left.size !== right.size) {
    return false
  }
  for (const [key, stream] of left) {
    const other = right.get(key)
    if (other == null || !sameToolStream(stream, other)) {
      return false
    }
  }
  return true
}

function sameToolStream(left: RealtimeToolStream, right: RealtimeToolStream): boolean {
  return left.threadId === right.threadId
    && left.invocationId === right.invocationId
    && left.attempt === right.attempt
    && left.toolCallId === right.toolCallId
    && left.text === right.text
    && left.error === right.error
    && left.errorText === right.errorText
    && sameAttachments(left.attachments, right.attachments)
}

function sameAttachments(
  left: ToolAttachment[] | undefined,
  right: ToolAttachment[] | undefined,
): boolean {
  if (left == null && right == null) {
    return true
  }
  if (left == null || right == null || left.length !== right.length) {
    return false
  }
  return left.every(
    (item, index) =>
      item.data === right[index]?.data
      && item.mime === right[index]?.mime
      && item.name === right[index]?.name
      && item.type === right[index]?.type,
  )
}

function sameModelStream(left: RealtimeModelStream, right: RealtimeModelStream): boolean {
  return left.threadId === right.threadId
    && left.invocationId === right.invocationId
    && left.attempt === right.attempt
    && left.text === right.text
    && left.thinking === right.thinking
    && left.sequence === right.sequence
    && left.status === right.status
    && left.errorText === right.errorText
}

const RECOVERY_BACKOFF_BASE_MS = 200
const RECOVERY_BACKOFF_MAX_MS = 2000
const RECOVERY_MAX_ATTEMPTS = 8
const TOOL_PARTIAL_FINGERPRINT_LIMIT = 256

interface GapRecovery {
  threadId: string
  invocationId: string
  attempt: number
  gapSequence: number
  attempts: number
}

/**
 * Single-flight sequence-gap recovery loop (KISS): one snapshot refetch at a time with bounded
 * backoff until the checkpoint catches up, the invocation terminalizes, or the attempt changes.
 * Never spawns concurrent refetches per delta and never freezes after one stale refetch; the
 * loop is cancelled on unmount/Thread switch.
 *
 * Stable callbacks: latest-function ref forwarding breaks the schedule -> run -> schedule
 * cycle, so the subscription effect never re-registers on re-render.
 */
function useGapRecoveryLoop(
  queryClient: QueryClient,
  modelStreamRef: { current: RealtimeModelStream | null },
  invocationRef: { current: ModelInvocationDTO | null },
): {
  requestGapRecovery: (
    recoveryThreadId: string,
    invocationId: string,
    attempt: number,
    gapSequence: number,
  ) => void
  clearRecoveryLoop: () => void
} {
  const recoveryRef = useRef<GapRecovery | null>(null)
  const recoveryBusyRef = useRef(false)
  const recoveryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const scheduleRecoveryTickRef = useRef<() => void>(() => undefined)
  const runRecoveryTickRef = useRef<() => Promise<void>>(async () => undefined)

  const scheduleRecoveryTick = useCallback(() => {
    if (recoveryBusyRef.current || recoveryTimerRef.current != null) {
      return
    }
    const recovery = recoveryRef.current
    if (recovery == null) {
      return
    }
    const delay = Math.min(
      RECOVERY_BACKOFF_BASE_MS * 2 ** recovery.attempts,
      RECOVERY_BACKOFF_MAX_MS,
    )
    recoveryTimerRef.current = setTimeout(() => {
      recoveryTimerRef.current = null
      void runRecoveryTickRef.current()
    }, delay)
  }, [])

  const runRecoveryTick = useCallback(async () => {
    const recovery = recoveryRef.current
    if (recovery == null) {
      return
    }
    recoveryBusyRef.current = true
    try {
      let refetchFailed = false
      try {
        // Use the CAPTURED recovery thread: the hook closure threadId may have moved to
        // another Thread while this tick was in flight.
        await queryClient.invalidateQueries({
          queryKey: queryKeys.threads.snapshot(recovery.threadId),
        })
      } catch {
        // A failed refetch must not freeze the loop: no timer is armed when the exception
        // escapes, so count the attempt and keep backing off below.
        refetchFailed = true
      }
      if (recoveryRef.current === recovery) {
        // Generation fence passed: only the captured recovery may be mutated/cleared. An old
        // tick that lost the fence falls through to the shared re-arm exit, which schedules
        // whatever recovery is CURRENT (possibly installed while this tick was busy).
        let terminal = false
        if (!refetchFailed) {
          // Caught up: the reconcile effect refreshed the overlay past the highest gap.
          const stream = modelStreamRef.current
          if (
            stream != null
            && stream.threadId === recovery.threadId
            && stream.invocationId === recovery.invocationId
            && stream.attempt === recovery.attempt
            && stream.sequence >= recovery.gapSequence
          ) {
            terminal = true
          } else {
            // Stale (Thread/attempt changed) or durable terminal: stop and clean up.
            const invocation = invocationRef.current
            if (
              invocation == null
              || invocation.threadId !== recovery.threadId
              || invocation.attempt !== recovery.attempt
              || invocation.resultJson != null
              || invocation.errorJson != null
              || invocation.resultEntryId != null
            ) {
              terminal = true
            }
          }
        }
        if (terminal) {
          recoveryRef.current = null
        } else {
          recovery.attempts += 1
          if (recovery.attempts > RECOVERY_MAX_ATTEMPTS) {
            // Give up this gap; a future delta or snapshot refresh re-arms recovery.
            recoveryRef.current = null
          }
        }
      }
    } finally {
      recoveryBusyRef.current = false
    }
    // Single re-arm exit. Re-arm only after the busy flag cleared (scheduleRecoveryTick would
    // otherwise early-return); a newer recovery installed while this tick was busy is armed
    // here too.
    if (recoveryRef.current != null) {
      scheduleRecoveryTickRef.current()
    }
  }, [queryClient, modelStreamRef, invocationRef])

  const requestGapRecovery = useCallback(
    (
      recoveryThreadId: string,
      invocationId: string,
      attempt: number,
      gapSequence: number,
    ) => {
      const recovery = recoveryRef.current
      if (
        recovery != null
        && recovery.threadId === recoveryThreadId
        && recovery.invocationId === invocationId
        && recovery.attempt === attempt
      ) {
        recovery.gapSequence = Math.max(recovery.gapSequence, gapSequence)
        return
      }
      recoveryRef.current = {
        threadId: recoveryThreadId,
        invocationId,
        attempt,
        gapSequence,
        attempts: 0,
      }
      scheduleRecoveryTickRef.current()
    },
    [],
  )

  const clearRecoveryLoop = useCallback(() => {
    if (recoveryTimerRef.current != null) {
      clearTimeout(recoveryTimerRef.current)
      recoveryTimerRef.current = null
    }
    recoveryRef.current = null
  }, [])

  // Latest-function forwarding (after commit): timers only fire after the layout/effect
  // pass, so the refs always hold the current tick implementations.
  useEffect(() => {
    scheduleRecoveryTickRef.current = scheduleRecoveryTick
    runRecoveryTickRef.current = runRecoveryTick
  }, [runRecoveryTick, scheduleRecoveryTick])

  return { requestGapRecovery, clearRecoveryLoop }
}

function fingerprintOf(partial: RealtimeToolPartial): string {
  return `${partial.createdAt}|${JSON.stringify(partial.payload)}`
}
