import { useCallback, useEffect, useState, type Dispatch, type SetStateAction } from 'react'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import { initialPaneState, MAX_PANES, paneIds, type Layout, type PaneState } from './model'

const VERSION = 1
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

function validLayout(value: unknown, seen = new Set<string>()): value is Layout {
  if (!record(value) || seen.size >= MAX_PANES * 2 - 1) return false
  const id = value.paneId ?? value.splitId
  if (!isCanonicalUuid(id) || seen.has(id)) return false
  seen.add(id)
  return 'paneId' in value ? Object.keys(value).length === 1
    : (value.axis === 'X' || value.axis === 'Y') && typeof value.ratio === 'number'
      && value.ratio > 0 && value.ratio < 1 && Object.keys(value).length === 5
      && validLayout(value.first, seen) && validLayout(value.second, seen)
}

export function serializePaneState<T>(state: PaneState<T>): string {
  return JSON.stringify({ version: VERSION, ...state })
}

export function deserializePaneState<T>(raw: string | null, isTarget: (value: unknown) => value is T): PaneState<T> {
  let value: unknown
  try { value = raw === null ? null : JSON.parse(raw) } catch { return initialPaneState<T>() }
  if (!record(value) || value.version !== VERSION || !validLayout(value.layout)
    || typeof value.focusedPaneId !== 'string' || !record(value.bindings)) return initialPaneState<T>()
  const ids = paneIds(value.layout)
  if (!ids.includes(value.focusedPaneId)) return initialPaneState<T>()
  const bindings: PaneState<T>['bindings'] = {}
  for (const id of ids) {
    const target = value.bindings[id]
    if (isTarget(target)) bindings[id] = target
  }
  return { layout: value.layout, focusedPaneId: value.focusedPaneId, bindings }
}

export function usePaneState<T>(userId: string, spaceId: string, isTarget: (value: unknown) => value is T):
  [PaneState<T>, Dispatch<SetStateAction<PaneState<T>>>] {
  const key = `workspace-panes:${JSON.stringify([userId, spaceId])}`
  const load = () => {
    let raw: string | null = null
    try { raw = localStorage.getItem(key) } catch { /* Storage may be unavailable. */ }
    return { key, state: deserializePaneState(raw, isTarget) }
  }
  const [stored, setStored] = useState(load)
  // Reset before committing a render for a different account or space.
  const current = stored.key === key ? stored : load()
  if (current !== stored) setStored(current)
  useEffect(() => {
    const raw = serializePaneState(current.state)
    try { localStorage.setItem(current.key, raw) } catch { /* Keep the layout in memory. */ }
  }, [current])
  const setState = useCallback<Dispatch<SetStateAction<PaneState<T>>>>(action => setStored(previous => {
    if (previous.key !== key) return previous
    const state = typeof action === 'function' ? action(previous.state) : action
    return state === previous.state ? previous : { key, state }
  }), [key])
  return [current.state, setState]
}
