import { useEffect } from 'react'
import type { CloudFilesChangedEventPayload } from './types'

type InvalidationListener = (payload?: CloudFilesChangedEventPayload) => void

const listeners = new Set<InvalidationListener>()

/**
 * Dispatches a cloud files invalidation notification to all active listeners.
 * Can be hooked up by I1 to the shared event manager when `cloud_files_changed` is received.
 */
export function notifyCloudFilesChanged(payload?: CloudFilesChangedEventPayload): void {
  for (const listener of listeners) {
    try {
      listener(payload)
    } catch {
      // Ignore listener errors during broadcast
    }
  }
}

/**
 * Hook for subscribing to cloud files invalidation events.
 * Triggered whenever `notifyCloudFilesChanged` is invoked.
 */
export function useCloudFilesInvalidation(
  onInvalidate: (payload?: CloudFilesChangedEventPayload) => void,
): void {
  useEffect(() => {
    const listener: InvalidationListener = (payload) => {
      onInvalidate(payload)
    }
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
    }
  }, [onInvalidate])
}
