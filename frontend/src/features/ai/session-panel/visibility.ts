import type { DialogueMessage } from '@/features/ai/session-events'

export function isVisibleDialogueMessage(message: DialogueMessage): boolean {
  if (message.role !== 'assistant') {
    return true
  }
  if (message.status === 'error' || message.status === 'streaming') {
    return true
  }
  if (message.text.trim().length > 0) {
    return true
  }
  return Boolean(message.thinking?.trim())
}
