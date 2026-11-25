import { createContext } from 'react'

/** App composition registers IDE exit guards alongside the shell's unsent-input guards. */
export const IdePaneCloseContext = createContext<{
  register: (viewId: string, close: () => Promise<boolean>) => () => void
  remove: (viewId: string) => void
} | null>(null)
