import type { DialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

export function isVisibleDialogueMessage(message: DialogueMessage): boolean {
  if (message.role === 'meta') {
    return Boolean(message.text?.trim())
  }
  if (message.role === 'entry') {
    return true
  }
  if (message.role !== 'assistant') {
    return true
  }
  if (message.status === 'error' || message.status === 'streaming') {
    return true
  }
  if (message.text.length > 0) {
    return true
  }
  return Boolean(message.thinking && message.thinking.length > 0)
}
