import { createContext } from 'react'

/** Session identity required to resolve private resource://files links. */
export const ResourceSessionContext = createContext<string | null>(null)

/** Optional Chat-owned action; shared markdown UI does not depend on IDE feature internals. */
export interface ResourceOpenInIdeAction {
  open: (sessionId: string, resourceId: string) => Promise<void>
}

export const ResourceOpenInIdeContext = createContext<ResourceOpenInIdeAction | null>(null)
