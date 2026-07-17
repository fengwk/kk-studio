import { AssistantMessageBlock } from '@/features/ai/session-panel/messages/AssistantMessageBlock'
import { SystemMessageBlock } from '@/features/ai/session-panel/messages/SystemMessageBlock'
import { ToolMessageBlock } from '@/features/ai/session-panel/messages/ToolMessageBlock'
import { UserMessageBlock } from '@/features/ai/session-panel/messages/UserMessageBlock'
import type { DialogueMessage } from '@/features/ai/session-events'

/** Dispatches each dialogue message to a dedicated block component (pi per-message-type). */
export function MessageList({ messages }: { messages: DialogueMessage[] }) {
  return (
    <>
      {messages.map((message) => {
        switch (message.role) {
          case 'user':
            return <UserMessageBlock key={message.id} message={message} />
          case 'assistant':
            return <AssistantMessageBlock key={message.id} message={message} />
          case 'system':
            return <SystemMessageBlock key={message.id} message={message} />
          case 'tool':
            return <ToolMessageBlock key={message.id} message={message} />
          default:
            return null
        }
      })}
    </>
  )
}
