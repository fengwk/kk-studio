import type { ExtensionComponentProps } from '@/platform/extensions/types'
import { ChatCardsPanel } from '@/features/ai/chat/ChatCardsPanel'
import { ChatRuntime } from '@/features/ai/chat/ChatRuntime'
import { useChatRuntime } from '@/features/ai/chat/ChatRuntimeContext'
import { AiConsoleFrame } from '@/features/ai/extensions/AiConsoleFrame'

/** Chat 列表路由的完整运行时；整个模块只在进入 `/chats` 时加载。 */
export default function ChatsRoute({ children }: ExtensionComponentProps) {
  return (
    <ChatRuntime>
      <ChatsFrame>{children}</ChatsFrame>
    </ChatRuntime>
  )
}

function ChatsFrame({ children }: ExtensionComponentProps) {
  const controller = useChatRuntime()
  return (
    <AiConsoleFrame
      search={controller.search}
      onSearchChange={controller.setSearch}
      busy={controller.busy}
      error={controller.error}
      mutationError={controller.mutationError}
      content={<ChatCardsPanel {...controller.chatPanelProps} />}
    >
      {children}
    </AiConsoleFrame>
  )
}
