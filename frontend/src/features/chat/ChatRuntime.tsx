import type { PropsWithChildren } from 'react'
import { ChatRuntimeContext } from '@/features/chat/ChatRuntimeContext'
import { useChatPageController } from '@/features/chat/useChatPageController'

export function ChatRuntime({ children }: PropsWithChildren) {
  const controller = useChatPageController()
  return (
    <ChatRuntimeContext.Provider value={controller}>
      {children}
    </ChatRuntimeContext.Provider>
  )
}
