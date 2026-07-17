import { MessageList } from '@/features/ai/session-panel'
import type { DialogueMessage } from '@/features/ai/session-events'

/** Backward-compatible single-message entry; prefers the modular MessageList path. */
export function ChatMessageBubble({ message }: { message: DialogueMessage }) {
  return <MessageList messages={[message]} />
}
