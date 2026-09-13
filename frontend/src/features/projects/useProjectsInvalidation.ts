import { useEffect } from 'react'
import type { ProjectsChangedEventPayload } from './types'

type InvalidationListener = (payload?: ProjectsChangedEventPayload) => void

const listeners = new Set<InvalidationListener>()

/**
 * Dispatches a projects invalidation notification to all active listeners.
 * The shared event manager calls this when `project_issue_changed` is received.
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
