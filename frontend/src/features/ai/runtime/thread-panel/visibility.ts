import type { DialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

export function isVisibleDialogueMessage(message: DialogueMessage): boolean {
  if (message.role === 'meta') {
    // 无 usage 的回合结束 footer 没有可见文本，但只要绑定到真实 TURN_END 就必须展示结束信息。
    return Boolean(message.text?.trim()) || message.endEntryId != null
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
