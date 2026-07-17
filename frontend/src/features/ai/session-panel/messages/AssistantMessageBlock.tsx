import { ThinkingBlock } from '@/features/ai/session-panel/messages/ThinkingBlock'
import type { TextDialogueMessage } from '@/features/ai/session-events'

/**
 * Ordered assistant content blocks (thinking → text), pi-style.
 * Uses canvas-thread visual language instead of heavy avatar bubbles.
 */
export function AssistantMessageBlock({ message }: { message: TextDialogueMessage }) {
  const thinking = message.thinking?.trim() ?? ''
  const text = message.text
  const streaming = message.status === 'streaming'
  const hasText = text.trim().length > 0
  const showStreamingHint = streaming && !hasText

  return (
    <article className={`session-row assistant ${message.status === 'error' ? 'error' : ''}`}>
      <div className="session-bubble assistant">
        <ThinkingBlock thinking={thinking} streaming={streaming} hasText={hasText} />
        {hasText ? <div className="session-assistant-text">{text}</div> : null}
        {showStreamingHint ? (
          <div className="session-streaming-hint" aria-live="polite">
            <span className="session-pulse" />
            {thinking ? '继续生成…' : '正在思考并生成…'}
          </div>
        ) : null}
        {!hasText && !thinking && message.status === 'error' ? (
          <div className="session-assistant-text error">助手回复失败</div>
        ) : null}
      </div>
    </article>
  )
}
