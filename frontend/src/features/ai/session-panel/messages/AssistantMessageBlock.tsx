import { Bot, ChevronDown } from 'lucide-react'
import type { TextDialogueMessage } from '@/features/ai/session-events'

/**
 * Renders assistant content as ordered blocks (thinking then text), mirroring pi's
 * AssistantMessageComponent content loop — not a single opaque bubble string.
 */
export function AssistantMessageBlock({ message }: { message: TextDialogueMessage }) {
  const thinking = message.thinking?.trim() ?? ''
  const text = message.text
  const streaming = message.status === 'streaming'
  const hasText = text.trim().length > 0
  const showPlaceholder = streaming && !hasText && !thinking

  return (
    <article className="msg-wrapper bot session-msg session-msg-assistant">
      <div className="msg-avatar">
        <Bot aria-hidden="true" />
      </div>
      <div className={`session-assistant-stack ${message.status === 'error' ? 'error' : ''}`}>
        {thinking ? (
          <details className="msg-thinking session-thinking-block" open={streaming && !hasText}>
            <summary>
              <ChevronDown aria-hidden="true" />
              <span>{streaming && !hasText ? '正在思考' : '思考过程'}</span>
            </summary>
            <pre className="msg-thinking-body">{thinking}</pre>
          </details>
        ) : null}
        {hasText ? <p className="session-msg-text session-assistant-text">{text}</p> : null}
        {showPlaceholder ? <p className="session-msg-text session-assistant-placeholder">...</p> : null}
        {!hasText && !thinking && message.status === 'error' ? (
          <p className="session-msg-text session-assistant-text">助手回复失败</p>
        ) : null}
      </div>
    </article>
  )
}
