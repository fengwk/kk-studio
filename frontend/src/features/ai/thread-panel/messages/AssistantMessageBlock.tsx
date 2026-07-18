import { ThinkingBlock } from '@/features/ai/thread-panel/messages/ThinkingBlock'
import type { TextDialogueMessage } from '@/features/ai/thread-events'

/**
 * Assistant turn as ordered full-width blocks:
 * thinking block (if any) + output block (if any).
 * No role titles — surface style distinguishes blocks.
 */
export function AssistantMessageBlock({ message }: { message: TextDialogueMessage }) {
  const thinking = message.thinking?.trim() ?? ''
  // Provider boundary whitespace separates reasoning from the final answer, but it is not
  // visible answer content. Preserve internal line breaks while removing that outer padding.
  const text = message.text.trim()
  const streaming = message.status === 'streaming'
  const hasText = text.length > 0

  return (
    <div className={`thread-turn thread-turn-assistant ${message.status === 'error' ? 'error' : ''}`}>
      <ThinkingBlock thinking={thinking} streaming={streaming && !hasText} />
      {hasText ? (
        <section className="thread-block thread-block-assistant">
          <div className="thread-block-body thread-assistant-text">{text}</div>
        </section>
      ) : null}
      {streaming && !hasText && !thinking ? (
        <section className="thread-block thread-block-assistant streaming">
          <div className="thread-block-body thread-streaming-hint">…</div>
        </section>
      ) : null}
      {!hasText && !thinking && message.status === 'error' ? (
        <section className="thread-block thread-block-assistant error">
          <div className="thread-block-body">助手回复失败</div>
        </section>
      ) : null}
    </div>
  )
}
