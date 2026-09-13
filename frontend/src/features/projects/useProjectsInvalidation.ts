import { useEffect, useRef } from 'react'
import type { ProjectsChangedEventPayload } from './types'

type InvalidationListener = (payload?: ProjectsChangedEventPayload) => void

const listeners = new Set<InvalidationListener>()

/**
 * Dispatches a projects invalidation notification to all active listeners.
 * The extension bridge calls this for Project change and resync signals.
 */
export function notifyProjectsChanged(payload?: ProjectsChangedEventPayload): void {
  for (const listener of listeners) {
    try {
      listener(payload)
    } catch {
      // Ignore listener errors during broadcast
    }
  }
}

/**
 * Hook for subscribing to project invalidation events.
 * Triggered whenever `notifyProjectsChanged` is invoked.
 */
export function useProjectsInvalidation(
  onInvalidate: (payload?: ProjectsChangedEventPayload) => void,
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
