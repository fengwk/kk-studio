import { ToolMessageBlock } from '@/features/ai/session-panel'
import type { ToolDialogueMessage } from '@/features/ai/session-events'

/** Backward-compatible wrapper over modular ToolMessageBlock. */
export function ChatToolMessage({ message }: { message: ToolDialogueMessage }) {
  return <ToolMessageBlock message={message} />
}
