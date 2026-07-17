import { Wrench } from 'lucide-react'
import { ChatToolMessage } from '@/features/ai/ChatToolMessage'
import type { ToolDialogueMessage } from '@/features/ai/session-events'

export function ToolMessageBlock({ message }: { message: ToolDialogueMessage }) {
  return (
    <article className="msg-wrapper tool session-msg session-msg-tool">
      <div className="msg-avatar">
        <Wrench aria-hidden="true" />
      </div>
      <div className={`msg-bubble tool session-msg-bubble ${message.status === 'error' ? 'error' : ''}`}>
        <ChatToolMessage message={message} />
      </div>
    </article>
  )
}
