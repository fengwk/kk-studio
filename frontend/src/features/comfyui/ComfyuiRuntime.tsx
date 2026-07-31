import type { PropsWithChildren } from 'react'
import { ComfyuiContext } from '@/features/comfyui/ComfyuiContext'
import { useComfyuiPageController } from '@/features/comfyui/useComfyuiPageController'

export function ComfyuiRuntime({ children }: PropsWithChildren) {
  const controller = useComfyuiPageController()
  return (
    <ComfyuiContext.Provider value={controller}>{children}</ComfyuiContext.Provider>
  )
}
