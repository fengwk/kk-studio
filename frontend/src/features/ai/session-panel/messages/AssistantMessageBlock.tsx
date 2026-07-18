import { ThinkingBlock } from '@/features/ai/session-panel/messages/ThinkingBlock'
import type { TextDialogueMessage } from '@/features/ai/session-events'

/**
 * Assistant turn as ordered full-width blocks:
 * thinking block (if any) + output block (if any).
 * Matches pi: same transcript column, different visual treatment per block type.
 */
export function AssistantMessageBlock({ message }: { message: TextDialogueMessage }) {
  const thinking = message.thinking?.trim() ?? ''
  const text = message.text
  const streaming = message.status === 'streaming'
  const hasText = text.trim().length > 0

  return (
    <div className={`session-turn session-turn-assistant ${message.status === 'error' ? 'error' : ''}`}>
      <ThinkingBlock thinking={thinking} streaming={streaming && !hasText} />
      {hasText ? (
        <section className="session-block session-block-assistant">
          <div className="session-block-label">assistant</div>
          <div className="session-block-body session-assistant-text">{text}</div>
        </section>
      ) : null}
      {streaming && !hasText && !thinking ? (
        <section className="session-block session-block-assistant streaming">
          <div className="session-block-label">assistant</div>
          <div className="session-block-body session-streaming-hint">…</div>
        </section>
      ) : null}
      {!hasText && !thinking && message.status === 'error' ? (
        <section className="session-block session-block-assistant error">
          <div className="session-block-label">error</div>
          <div className="session-block-body">助手回复失败</div>
        </section>
      ) : null}
    </div>
  )
}
