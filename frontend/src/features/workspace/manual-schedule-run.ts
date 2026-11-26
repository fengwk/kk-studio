import { useSyncExternalStore } from 'react'
import { createUuid } from '@/shared/lib/uuid'
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
  try { return localStorage.getItem(scope) ? 'retry' : 'idle' }
  catch { return 'idle' } // Sending still requires working storage.
}

export async function requestManualRun(scope: string, spaceId: string, scheduleId: string) {
  if (running.has(scope)) return 'busy'
  const previous = localStorage.getItem(scope)
  const key = previous ?? createUuid()
  // Persist before sending, so a reload while the request is in flight can recover the receipt.
  if (!previous) localStorage.setItem(scope, key)
  running.add(scope)
  notify()
  try {
    await runScheduleNow(spaceId, scheduleId, key)
    localStorage.removeItem(scope)
    return 'received'
  } catch (error) {
    if (!isManualRunRejected(error)) return 'unknown'
    localStorage.removeItem(scope)
    throw error
  } finally {
    running.delete(scope)
    notify()
  }
}
