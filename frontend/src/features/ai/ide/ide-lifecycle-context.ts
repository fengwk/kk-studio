import { createContext, useContext } from 'react'

export interface IdeLifecycleItem {
  viewId: string
  paneId?: string
  active: boolean
  requestClose: () => Promise<boolean>
}

export interface IdeLifecycleContextValue {
  registerIdeView: (item: IdeLifecycleItem) => () => void
  isPaneActive: (paneId: string) => boolean
  hasActiveIdeViews: boolean
}

export const IdeLifecycleContext = createContext<IdeLifecycleContextValue | null>(null)

export function useIdeLifecycle(): IdeLifecycleContextValue {
  const ctx = useContext(IdeLifecycleContext)
  if (!ctx) {
    throw new Error('useIdeLifecycle must be used within an IdeLifecycleProvider')
  }
  return ctx
}
