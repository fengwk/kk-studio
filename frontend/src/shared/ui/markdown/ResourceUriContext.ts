import { createContext } from 'react'

/** Session identity required to resolve private resource://files links. */
export const ResourceSessionContext = createContext<string | null>(null)
