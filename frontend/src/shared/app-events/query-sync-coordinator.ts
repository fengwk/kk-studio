import { hashKey } from '@tanstack/react-query'
import type { QueryClient, QueryKey } from '@tanstack/react-query'

interface Reconciliation {
  running: Promise<void> | null
  dirty: boolean
}

const reconciliations = new WeakMap<QueryClient, Map<string, Reconciliation>>()

/** Serialize authoritative reads per QueryClient/query key and run one trailing pass for events received in flight. */
export function coordinateQuerySync(
  queryClient: QueryClient,
  queryKey: QueryKey,
  sync: () => Promise<unknown>,
): Promise<void> {
  let queries = reconciliations.get(queryClient)
  if (queries == null) {
    queries = new Map()
    reconciliations.set(queryClient, queries)
  }
  const key = hashKey(queryKey)
  let reconciliation = queries.get(key)
  if (reconciliation == null) {
    reconciliation = { running: null, dirty: false }
    queries.set(key, reconciliation)
  }
  if (reconciliation.running != null) {
    reconciliation.dirty = true
    return reconciliation.running
  }
  const state = reconciliation
  state.running = (async () => {
    let failure: unknown
    let failed = false
    do {
      state.dirty = false
      try {
        await sync()
      } catch (error) {
        if (!failed) {
          failure = error
          failed = true
        }
      }
    } while (state.dirty)
    if (failed) {
      throw failure
    }
  })().finally(() => {
    state.running = null
    if (queries?.get(key) === state && !state.dirty) {
      queries.delete(key)
    }
  })
  return state.running
}
