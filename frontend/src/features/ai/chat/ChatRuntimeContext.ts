import { createContext, useContext } from 'react'
import type { ChatPageController } from '@/features/ai/chat/useChatPageController'

export const ChatRuntimeContext = createContext<ChatPageController | null>(null)

export function useChatRuntime() {
  const controller = useContext(ChatRuntimeContext)
  if (!controller) {
    throw new Error('ChatRuntime is required')
  }
  return controller
}

/** 供全局 ExtensionHost dialog contribution 读取当前 Chat 页面状态。 */
export function useOptionalChatRuntime() {
  return useContext(ChatRuntimeContext)
}
