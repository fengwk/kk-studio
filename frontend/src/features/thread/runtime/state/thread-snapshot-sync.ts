import { useCallback, useEffect, useState } from 'react'
import type { QueryClient } from '@tanstack/react-query'
import { useQueryClient } from '@tanstack/react-query'
import { useApplicationEventConnectionState, useApplicationEvents } from '@/shared/app-events'
import type { ApplicationEventManager } from '@/shared/app-events/manager'
import type { HarnessThreadSnapshotDTO } from '@/shared/api/generated/contracts'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { INVALID_EVENT_PAYLOAD, isThreadSnapshot } from '@/shared/app-events/protocol'
import { compareCatalogVersions } from '@/shared/lib/catalog-version'
import { useI18n } from '@/shared/i18n'
import {
  advanceThreadSnapshotEpoch,
  getSnapshotCursor,
  isThreadSnapshotUnavailable,
  markThreadSnapshotUnavailable,
  mergeThreadSnapshotCache,
  requestThreadSnapshotRefresh,
} from '@/features/thread/runtime/state/thread-snapshot-cache'

interface ThreadSyncEntry {
  refs: number
  manager: ApplicationEventManager
  unsubscribe: (() => void) | null
  unsubscribeIdentity: (() => void) | null
  identity: string | null
  queue: Promise<void>
  generation: number
  connectionGeneration: number
  recovering: boolean
  recoveryAttempted: boolean
  attemptedOverflowVersion: string | null
  coveredOverflowVersion: string | null
  recoveryFailed: boolean
  subscriptionError: string | null
  unavailable: boolean
  stopped: boolean
  recoveryAbort: AbortController | null
  error: string | null
  observers: Set<(error: string | null) => void>
}

const syncEntries = new WeakMap<QueryClient, Map<string, ThreadSyncEntry>>()

/** Every consumer of a Thread shares one subscription, one ordered event queue and one cache writer. */
export function useThreadSnapshotSync(
  threadId: string,
  enabled: boolean,
): { error: string | null; unavailable: boolean; retry: () => void } {
  const queryClient = useQueryClient()
  const manager = useApplicationEvents()
  const connection = useApplicationEventConnectionState()
  const { t } = useI18n()
  const terminal = enabled && connection.reason?.kind === 'stopped' && connection.reason.cause !== 'manual'
  const [status, setStatus] = useState<{ threadId: string; error: string | null } | null>(null)
  const query = queryClient.getQueryCache().find({ queryKey: queryKeys.threads.snapshot(threadId), exact: true })
  const unavailable = query != null && isThreadSnapshotUnavailable(query)

  useEffect(() => {
    if (!enabled || !threadId || unavailable) {
      setStatus(null)
      return undefined
    }
    let entries = syncEntries.get(queryClient)
    if (entries == null) {
      entries = new Map()
      syncEntries.set(queryClient, entries)
    }
    let entry = entries.get(threadId)
    if (entry == null) {
      entry = {
        refs: 0,
        manager,
        unsubscribe: null,
        unsubscribeIdentity: null,
        identity: manager.getIdentity()?.connectionId ?? null,
        queue: Promise.resolve(),
        generation: 0,
        connectionGeneration: 0,
        recovering: false,
        recoveryAttempted: false,
        attemptedOverflowVersion: null,
        coveredOverflowVersion: null,
        recoveryFailed: false,
        subscriptionError: null,
        unavailable: false,
        stopped: false,
        recoveryAbort: null,
        error: null,
        observers: new Set(),
      }
      entries.set(threadId, entry)
      listenForReconnect(entry)
      startSubscription(queryClient, threadId, entry)
    }
    entry.refs += 1
    const observe = (error: string | null) => setStatus({ threadId, error })
    entry.observers.add(observe)
    observe(entry.subscriptionError ?? entry.error)
    return () => {
      entry!.observers.delete(observe)
      entry!.refs -= 1
      if (entry!.refs === 0) {
        entry!.stopped = true
        entry!.generation += 1
        entry!.recoveryAbort?.abort()
        entry!.unsubscribe?.()
        entry!.unsubscribe = null
        entry!.unsubscribeIdentity?.()
        entry!.unsubscribeIdentity = null
        entries!.delete(threadId)
      }
    }
  }, [enabled, manager, queryClient, threadId, unavailable])

  const retry = useCallback(() => {
    const currentQuery = queryClient.getQueryCache().find({ queryKey: queryKeys.threads.snapshot(threadId), exact: true })
    if (currentQuery != null && isThreadSnapshotUnavailable(currentQuery)) {
      return
    }
    if (terminal) {
      window.location.reload()
      return
    }
    const entry = syncEntries.get(queryClient)?.get(threadId)
    if (entry == null) {
      void queryClient.refetchQueries({
        queryKey: queryKeys.threads.snapshot(threadId), exact: true,
      })
    } else if (entry.subscriptionError != null) {
      entry.manager.retryThreadSubscription({ kind: 'thread', id: threadId }, true)
    } else if (entry.recoveryFailed) {
      requestRecovery(queryClient, threadId, entry, { explicit: true })
    } else {
      requestWireRefresh(queryClient, threadId, entry)
    }
  }, [queryClient, terminal, threadId])

  return {
    unavailable,
    error: unavailable ? t('ai.runtime.thread.unavailable')
      : terminal ? t('ai.chat.history.liveUpdatesStopped')
        : status?.threadId === threadId ? status.error : null,
    retry,
  }
}

function listenForReconnect(entry: ThreadSyncEntry): void {
  entry.unsubscribeIdentity = entry.manager.subscribeIdentity((identity) => {
    const nextIdentity = identity?.connectionId ?? null
    if (entry.identity !== nextIdentity) {
      entry.identity = nextIdentity
      entry.connectionGeneration += 1
      entry.recoveryAttempted = false
      entry.attemptedOverflowVersion = null
      entry.coveredOverflowVersion = null
      entry.recoveryAbort?.abort()
    }
  })
}

function startSubscription(queryClient: QueryClient, threadId: string, entry: ThreadSyncEntry): void {
  if (entry.stopped || entry.unsubscribe != null) {
    return
  }
  entry.unsubscribe = entry.manager.subscribe({ kind: 'thread', id: threadId }, {
    getAfterEntryId: () => {
      const cached = queryClient.getQueryData<HarnessThreadSnapshotDTO>(queryKeys.threads.snapshot(threadId))
      return getSnapshotCursor(cached, threadId)
    },
    onSubscribed: () => {
      if (entry.subscriptionError != null && !entry.unavailable) {
        entry.subscriptionError = null
        notifySyncObservers(entry)
      }
    },
    onEvent: (name, data) => {
      if (name !== 'snapshot') {
        return
      }
      const generation = entry.generation
      const connectionGeneration = entry.connectionGeneration
      enqueue(entry, async () => {
        if (!isCurrent(entry, generation) || entry.connectionGeneration !== connectionGeneration) {
          return
        }
        await applyLiveSnapshot(queryClient, threadId, data, entry, generation, connectionGeneration)
      })
    },
    onResync: () => requestRecovery(queryClient, threadId, entry),
    onSnapshotRef: (message) => {
      if ('version' in message) {
        requestRecovery(queryClient, threadId, entry, { overflowVersion: message.version })
      }
    },
    onError: (code, message) => {
      if (code === 'RESOURCE_NOT_FOUND') {
        markThreadSnapshotUnavailable(queryClient, threadId)
        entry.unavailable = true
        entry.generation += 1
        entry.recoveryAbort?.abort()
        entry.manager.suspendThread({ kind: 'thread', id: threadId })
        void queryClient.cancelQueries({ queryKey: queryKeys.threads.snapshot(threadId), exact: true })
        notifySyncObservers(entry)
      } else if (code === 'RESOURCE_NOT_SUBSCRIBED') {
        entry.subscriptionError = message
        notifySyncObservers(entry)
        entry.manager.retryThreadSubscription({ kind: 'thread', id: threadId }, false)
      } else if (code === INVALID_EVENT_PAYLOAD) {
        setSyncError(entry, message)
        requestRecovery(queryClient, threadId, entry)
      } else if (code === 'THREAD_SNAPSHOT_FAILED') {
        setSyncError(entry, message, entry.recoveryFailed)
      }
    },
  })
}

function enqueue(entry: ThreadSyncEntry, operation: () => Promise<void>): void {
  entry.queue = entry.queue
    .then(operation)
    .catch((error: unknown) => {
      if (!entry.stopped) {
        setSyncError(entry, error instanceof Error ? error.message : String(error), false)
      }
    })
}

async function applyLiveSnapshot(
  queryClient: QueryClient,
  threadId: string,
  incoming: unknown,
  entry: ThreadSyncEntry,
  generation: number,
  connectionGeneration: number,
): Promise<void> {
  if (!isCurrentEvent(entry, generation, connectionGeneration)) {
    return
  }
  if (!isThreadSnapshot(incoming, threadId)) {
    setSyncError(entry, 'Received an invalid Thread snapshot')
    return
  }
  if (!hasContinuousSnapshotPath(incoming)) {
    requestRecovery(queryClient, threadId, entry)
    return
  }
  const key = queryKeys.threads.snapshot(threadId)
  await queryClient.cancelQueries({ queryKey: key, exact: true })
  if (!isCurrentEvent(entry, generation, connectionGeneration)) {
    return
  }
  const cached = queryClient.getQueryData<HarnessThreadSnapshotDTO>(key)
  if (incoming.baseEntryId !== null && incoming.baseEntryId !== cached?.thread.headEntryId) {
    requestRecovery(queryClient, threadId, entry)
    return
  }
  const merged = mergeThreadSnapshotCache(cached, incoming)
  if (merged == null) {
    requestRecovery(queryClient, threadId, entry)
    return
  }
  advanceThreadSnapshotEpoch(queryClient, threadId)
  queryClient.setQueryData(key, merged)
  if (entry.attemptedOverflowVersion != null) {
    if (compareCatalogVersions(incoming.version, entry.attemptedOverflowVersion) < 0) {
      // A sendable older snapshot does not resolve the known overflow gap.
      return
    }
    entry.attemptedOverflowVersion = null
  }
  if (entry.coveredOverflowVersion != null
    && compareCatalogVersions(incoming.version, entry.coveredOverflowVersion) >= 0) {
    entry.coveredOverflowVersion = null
  }
  entry.recoveryAttempted = false
  setSyncError(entry, null, false)
}

function requestRecovery(
  queryClient: QueryClient,
  threadId: string,
  entry: ThreadSyncEntry,
  { explicit = false, overflowVersion }: { explicit?: boolean; overflowVersion?: string } = {},
): void {
  if (entry.recovering || entry.stopped || entry.unavailable) {
    return
  }
  if (overflowVersion != null) {
    if ((entry.attemptedOverflowVersion != null
      && compareCatalogVersions(overflowVersion, entry.attemptedOverflowVersion) <= 0)
      || (entry.coveredOverflowVersion != null
        && compareCatalogVersions(overflowVersion, entry.coveredOverflowVersion) <= 0)) {
      return
    }
    // Record before reading: a failed rebase still resumes from the old head and can repeat this hint.
    entry.attemptedOverflowVersion = overflowVersion
  } else if (entry.recoveryAttempted && !explicit) {
    // Stop automatic reads, not the live subscription. A viable snapshot can still heal it.
    setSyncError(entry, entry.error ?? 'Thread updates could not resume after recovery', true)
    return
  }
  entry.recoveryAttempted = overflowVersion == null
  entry.recovering = true
  entry.generation += 1
  const generation = entry.generation
  const connectionGeneration = entry.connectionGeneration
  const controller = new AbortController()
  entry.recoveryAbort = controller
  entry.manager.suspendThread({ kind: 'thread', id: threadId })
  enqueue(entry, async () => {
    const key = queryKeys.threads.snapshot(threadId)
    const isCurrentRecovery = () => isCurrentEvent(entry, generation, connectionGeneration)
    try {
      if (!isCurrentRecovery()) {
        return
      }
      await queryClient.cancelQueries({ queryKey: key, exact: true })
      if (!isCurrentRecovery()) {
        return
      }
      const restored = await harnessService.getThreadSnapshot(threadId, { signal: controller.signal })
      if (!isCurrentRecovery()) {
        return
      }
      if (!isThreadSnapshot(restored, threadId) || restored.baseEntryId !== null
        || !hasContinuousFullPath(restored)) {
        throw new Error('The restored Thread snapshot is not a complete path to its head')
      }
      const cached = queryClient.getQueryData<HarnessThreadSnapshotDTO>(key)
      const merged = mergeThreadSnapshotCache(cached, restored)
      if (merged == null) {
        throw new Error('The restored Thread snapshot could not be merged')
      }
      if (entry.attemptedOverflowVersion != null) {
        if (compareCatalogVersions(merged.version, entry.attemptedOverflowVersion) < 0) {
          throw new Error('The restored Thread snapshot does not cover the missing version')
        }
        entry.coveredOverflowVersion = merged.version
      }
      advanceThreadSnapshotEpoch(queryClient, threadId)
      queryClient.setQueryData(key, merged)
      setSyncError(entry, null, false)
    } catch (error) {
      if (isCurrentRecovery()) {
        setSyncError(entry, error instanceof Error ? error.message : String(error), true)
      }
    } finally {
      if (isCurrent(entry, generation)) {
        entry.recovering = false
        entry.recoveryAbort = null
        // Even after failure, keep listening from the cached head. The attempt guards bound automatic reads.
        entry.manager.resumeThread({ kind: 'thread', id: threadId })
      }
    }
  })
}

function hasContinuousSnapshotPath(snapshot: HarnessThreadSnapshotDTO): boolean {
  if (snapshot.entries.length === 0) {
    return snapshot.baseEntryId !== null && snapshot.thread.headEntryId === snapshot.baseEntryId
  }
  if (snapshot.entries.at(-1)?.entryId !== snapshot.thread.headEntryId) {
    return false
  }
  if (snapshot.baseEntryId === null && snapshot.entries[0]?.parentEntryId !== null) {
    return false
  }
  if (snapshot.baseEntryId !== null && snapshot.entries[0]?.parentEntryId !== snapshot.baseEntryId) {
    return false
  }
  for (let index = 1; index < snapshot.entries.length; index += 1) {
    if (snapshot.entries[index]?.parentEntryId !== snapshot.entries[index - 1]?.entryId) {
      return false
    }
  }
  return true
}

function hasContinuousFullPath(snapshot: HarnessThreadSnapshotDTO): boolean {
  return snapshot.baseEntryId === null
    && snapshot.entries.length > 0
    && hasContinuousSnapshotPath(snapshot)
}

function isCurrentEvent(
  entry: ThreadSyncEntry,
  generation: number,
  connectionGeneration: number,
): boolean {
  return isCurrent(entry, generation) && entry.connectionGeneration === connectionGeneration
}

function isCurrent(entry: ThreadSyncEntry, generation: number): boolean {
  return !entry.stopped && !entry.unavailable && entry.generation === generation
}

function requestWireRefresh(queryClient: QueryClient, threadId: string, entry: ThreadSyncEntry): void {
  void requestThreadSnapshotRefresh(queryClient, entry.manager, threadId)
}

function setSyncError(
  entry: ThreadSyncEntry,
  error: string | null,
  recoveryFailed = false,
): void {
  if (entry.error === error && entry.recoveryFailed === recoveryFailed) {
    return
  }
  entry.error = error
  entry.recoveryFailed = recoveryFailed
  notifySyncObservers(entry)
}

function notifySyncObservers(entry: ThreadSyncEntry): void {
  for (const observer of [...entry.observers]) {
    observer(entry.subscriptionError ?? entry.error)
  }
}
