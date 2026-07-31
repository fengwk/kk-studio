import type { PropsWithChildren } from 'react'
import { ChatRuntimeContext } from '@/features/ai/chat/ChatRuntimeContext'
import { useChatPageController } from '@/features/ai/chat/useChatPageController'

export function ChatRuntime({ children }: PropsWithChildren) {
  const controller = useChatPageController()
  return (
    <ChatRuntimeContext.Provider value={controller}>
      {children}
    </ChatRuntimeContext.Provider>
  )
}
