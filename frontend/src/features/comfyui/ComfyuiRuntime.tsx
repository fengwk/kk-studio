/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, type PropsWithChildren } from 'react'
import { useComfyuiPageController } from '@/features/comfyui/useComfyuiPageController'

type ComfyuiPageController = ReturnType<typeof useComfyuiPageController>

const ComfyuiContext = createContext<ComfyuiPageController | null>(null)

export function ComfyuiRuntime({ children }: PropsWithChildren) {
  const controller = useComfyuiPageController()
  return (
    <ComfyuiContext.Provider value={controller}>{children}</ComfyuiContext.Provider>
  )
}

export function useOptionalComfyui() {
  return useContext(ComfyuiContext)
}
