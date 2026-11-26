import { useSyncExternalStore } from 'react'
import { createUuid, isCanonicalUuid } from '@/shared/lib/uuid'
import { isManualRunRejected } from './workspace-action-retry'
import { runScheduleNow } from './workspace-api'

// The schedule panel unmounts when switching tabs; keep active requests locked until they settle.
const running = new Set<string>()
const listeners = new Set<() => void>()
let revision = 0

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

function notify() {
  revision++
  listeners.forEach(listener => listener())
}

export function useManualRunState() {
  useSyncExternalStore(subscribe, () => revision)
}

export function manualRunScope(userId: string, spaceId: string, scheduleId: string) {
  return `workspace-manual-run:${JSON.stringify([userId, spaceId, scheduleId])}`
}

export function manualRunState(scope: string): 'running' | 'retry' | 'idle' {
  if (running.has(scope)) return 'running'
  try { return sessionStorage.getItem(scope) !== null ? 'retry' : 'idle' }
  catch { return 'idle' } // Sending still requires working storage.
}

export async function requestManualRun(scope: string, spaceId: string, scheduleId: string) {
  if (running.has(scope)) return 'busy'
  const previous = sessionStorage.getItem(scope)
  const key = previous ?? createUuid()
  if (!isCanonicalUuid(key)) throw new Error('Invalid manual run record')
  // A reload can replay this tab's pending run. Another tab does not settle or clear it.
  if (previous === null) {
    sessionStorage.setItem(scope, key)
    if (sessionStorage.getItem(scope) !== key) throw new Error('Manual run record was not saved')
  }
  running.add(scope)
  notify()
  try {
    try {
      await runScheduleNow(spaceId, scheduleId, key)
    } catch (error) {
      if (!isManualRunRejected(error)) return 'unknown'
      sessionStorage.removeItem(scope)
      throw error
    }
    sessionStorage.removeItem(scope)
    return 'received'
  } finally {
    running.delete(scope)
    notify()
  }
}
