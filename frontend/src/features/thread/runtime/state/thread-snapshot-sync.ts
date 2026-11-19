import { useCallback, useEffect, useState } from 'react'
import type { QueryClient } from '@tanstack/react-query'
import { useQueryClient } from '@tanstack/react-query'
import { useApplicationEvents } from '@/shared/app-events'
import type { ApplicationEventManager } from '@/shared/app-events/manager'
import type { HarnessThreadSnapshotDTO } from '@/shared/api/generated/contracts'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { isThreadSnapshot } from '@/shared/app-events/protocol'
import {
  advanceThreadSnapshotEpoch,
  getSnapshotCursor,
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
  recoveryFailed: boolean
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
): { error: string | null; retry: () => void } {
  const queryClient = useQueryClient()
  const manager = useApplicationEvents()
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!enabled || !threadId) {
      setError(null)
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
        recoveryFailed: false,
        stopped: false,
        recoveryAbort: null,
        error: null,
        observers: new Set(),
      }
      entries.set(threadId, entry)
      listenForReconnect(threadId, entry)
      startSubscription(queryClient, threadId, entry)
    }
    entry.refs += 1
    entry.observers.add(setError)
    setError(entry.error)
    return () => {
      entry!.observers.delete(setError)
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
  }, [enabled, manager, queryClient, threadId])

  const retry = useCallback(() => {
    const entry = syncEntries.get(queryClient)?.get(threadId)
    if (entry == null) {
      void queryClient.refetchQueries({
        queryKey: queryKeys.threads.snapshot(threadId), exact: true,
      })
    } else if (entry.recoveryFailed) {
      requestRecovery(queryClient, threadId, entry, true)
    } else {
      requestWireRefresh(queryClient, threadId, entry)
    }
  }, [queryClient, threadId])

  return { error, retry }
}

function listenForReconnect(threadId: string, entry: ThreadSyncEntry): void {
  entry.unsubscribeIdentity = entry.manager.subscribeIdentity((identity) => {
    const nextIdentity = identity?.connectionId ?? null
    if (entry.identity !== nextIdentity) {
      entry.identity = nextIdentity
      entry.connectionGeneration += 1
    }
    if (nextIdentity != null && entry.error != null && entry.recoveryFailed) {
      // Reconnect rebuilds the subscription/catch-up; it is not an HTTP retry.
      entry.manager.resumeThread({ kind: 'thread', id: threadId })
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
    onError: (code, message) => {
      if (code === 'THREAD_SNAPSHOT_FAILED') {
        setSyncError(entry, message, false)
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
  entry.recoveryAttempted = false
  setSyncError(entry, null, false)
}

function requestRecovery(
  queryClient: QueryClient,
  threadId: string,
  entry: ThreadSyncEntry,
  explicit = false,
): void {
  if (entry.recovering || entry.stopped) {
    return
  }
  if (entry.recoveryAttempted && !explicit) {
    // An unchanged oversized frame or broken link would otherwise start an HTTP loop after every ACK.
    entry.generation += 1
    entry.manager.suspendThread({ kind: 'thread', id: threadId })
    setSyncError(entry, entry.error ?? 'Thread updates could not resume after recovery', true)
    return
  }
  entry.recoveryAttempted = true
  entry.recovering = true
  entry.generation += 1
  const generation = entry.generation
  entry.recoveryAbort?.abort()
  const controller = new AbortController()
  entry.recoveryAbort = controller
  entry.manager.suspendThread({ kind: 'thread', id: threadId })
  enqueue(entry, async () => {
    if (!isCurrent(entry, generation)) {
      return
    }
    const key = queryKeys.threads.snapshot(threadId)
    try {
      await queryClient.cancelQueries({ queryKey: key, exact: true })
      const restored = await harnessService.getThreadSnapshot(threadId, { signal: controller.signal })
      if (!isCurrent(entry, generation)) {
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
      advanceThreadSnapshotEpoch(queryClient, threadId)
      queryClient.setQueryData(key, merged)
      entry.recovering = false
      entry.recoveryAbort = null
      setSyncError(entry, null, false)
      entry.manager.resumeThread({ kind: 'thread', id: threadId })
    } catch (error) {
      if (isCurrent(entry, generation)) {
        entry.recovering = false
        entry.recoveryAbort = null
        setSyncError(entry, error instanceof Error ? error.message : String(error), true)
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
  return !entry.stopped && entry.generation === generation
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
  for (const observer of [...entry.observers]) {
    observer(error)
  }
}
