/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, type PropsWithChildren } from 'react'
import { useCanvasController, type CanvasController } from '@/features/canvas/useCanvasController'

export const CanvasRuntimeContext = createContext<CanvasController | null>(null)

export function CanvasRuntimeProvider({ children }: PropsWithChildren) {
  const controller = useCanvasController()
  return (
    <CanvasRuntimeContext.Provider value={controller}>
      {children}
    </CanvasRuntimeContext.Provider>
  )
}

export function useCanvasRuntime(): CanvasController {
  const value = useContext(CanvasRuntimeContext)
  if (!value) {
    throw new Error('CanvasRuntimeProvider is required')
  }
  return value
}
