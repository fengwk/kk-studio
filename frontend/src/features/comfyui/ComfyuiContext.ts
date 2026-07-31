import { createContext, useContext } from 'react'
import type { ComfyuiPageController } from '@/features/comfyui/useComfyuiPageController'

export const ComfyuiContext = createContext<ComfyuiPageController | null>(null)

export function useComfyui() {
  const controller = useContext(ComfyuiContext)
  if (!controller) {
    throw new Error('ComfyuiRuntime is required')
  }
  return controller
}

/** 供全局 ExtensionHost dialog contribution 读取当前 ComfyUI 页面状态。 */
export function useOptionalComfyui() {
  return useContext(ComfyuiContext)
}
