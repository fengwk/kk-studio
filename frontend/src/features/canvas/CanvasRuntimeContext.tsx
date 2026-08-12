/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, type PropsWithChildren } from 'react'
import { useCanvasController, type CanvasController } from '@/features/canvas/useCanvasController'
import type { UUIDString } from '@/shared/api/contracts/studio'

export const CanvasRuntimeContext = createContext<CanvasController | null>(null)

interface CanvasRuntimeProviderProps extends PropsWithChildren {
  initialCanvasId?: UUIDString
}

export function CanvasRuntimeProvider({
  children,
  initialCanvasId,
}: CanvasRuntimeProviderProps) {
  const controller = useCanvasController(initialCanvasId)
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
