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
  const queryMap = queries
  const key = hashKey(queryKey)
  let reconciliation = queryMap.get(key)
  if (reconciliation == null) {
    reconciliation = { running: null, dirty: false }
    queryMap.set(key, reconciliation)
  }
  if (reconciliation.running != null) {
    reconciliation.dirty = true
    return reconciliation.running
  }
  const state = reconciliation
  const run = async (): Promise<void> => {
    let failure: unknown
    let failed = false
    while (true) {
      state.dirty = false
      try {
        await sync()
      } catch (error) {
        if (!failed) {
          failure = error
          failed = true
        }
      }
      if (state.dirty) {
        continue
      }
      state.running = null
      if (queryMap.get(key) === state) {
        queryMap.delete(key)
      }
      if (failed) {
        throw failure
      }
      return
    }
  }
  state.running = run()
  return state.running
}
