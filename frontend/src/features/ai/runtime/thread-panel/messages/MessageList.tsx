import { AssistantMessageBlock } from '@/features/ai/runtime/thread-panel/messages/AssistantMessageBlock'
import { EntryMessageBlock } from '@/features/ai/runtime/thread-panel/messages/EntryMessageBlock'
import { MetaMessageBlock } from '@/features/ai/runtime/thread-panel/messages/MetaMessageBlock'
import { SystemMessageBlock } from '@/features/ai/runtime/thread-panel/messages/SystemMessageBlock'
import { ToolMessageBlock } from '@/features/ai/runtime/thread-panel/messages/ToolMessageBlock'
import { UserMessageBlock } from '@/features/ai/runtime/thread-panel/messages/UserMessageBlock'
import type {
  DialogueMessage,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'

/** Dispatches each dialogue message to a dedicated block component (pi per-message-type). */
export function MessageList({
  messages,
  onDecideApproval,
  approvalPending = false,
}: {
  messages: DialogueMessage[]
  onDecideApproval?: (message: ToolDialogueMessage, decision: 'ALLOW' | 'DENY') => void
  /** Global approval request in flight: every undecided approval bar disables its buttons. */
  approvalPending?: boolean
}) {
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
            return (
              <ToolMessageBlock
                key={message.id}
                message={message}
                onDecideApproval={onDecideApproval}
                approvalPending={approvalPending}
              />
            )
          case 'meta':
            return <MetaMessageBlock key={message.id} message={message} />
          case 'entry':
            return <EntryMessageBlock key={message.id} message={message} />
          default:
            return null
        }
      })}
    </>
  )
}
