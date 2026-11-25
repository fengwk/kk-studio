import { createContext, useContext } from 'react'

/** The callbacks receive the visible turn's exact TURN_END entry ID. */
type EntryBranchRequest = ((endEntryId: string) => void) | null

export const EntryBranchContext = createContext<EntryBranchRequest>(null)
export const EntryForkContext = createContext<EntryBranchRequest>(null)

export function useEntryBranchRequest(): EntryBranchRequest {
  return useContext(EntryBranchContext)
}
