import { useEffect, useRef } from 'react'
import type { CloudFilesChangedEventPayload } from './types'

type InvalidationListener = (payload?: CloudFilesChangedEventPayload) => void

const listeners = new Set<InvalidationListener>()

/**
 * Dispatches a cloud files invalidation notification to all active listeners.
 * The extension bridge calls this for Cloud Files resync signals.
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
  const listenerRef = useRef(onInvalidate)

  useEffect(() => {
    listenerRef.current = onInvalidate
  }, [onInvalidate])

  useEffect(() => {
    const listener: InvalidationListener = (payload) => {
      listenerRef.current(payload)
    }
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
    }
  }, [])
}
