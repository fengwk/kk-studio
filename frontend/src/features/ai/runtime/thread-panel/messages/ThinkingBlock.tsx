import { normalizeThinkingText } from '@/features/ai/runtime/thread-panel/messages/thinking-text'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'

/**
 * 思考块：始终展开的 Markdown 思考内容（tone="muted"）。
 */
export function ThinkingBlock({
  thinking,
  streaming,
}: {
  thinking: string
  streaming: boolean
}) {
  const normalized = normalizeThinkingText(thinking)
  if (!normalized) {
    return null
  }
  return (
    <section className={`thread-block thread-block-thinking ${streaming ? 'streaming' : ''}`}>
      <div className="thread-block-body thread-thinking-text">
        <MarkdownRenderer content={normalized} tone="muted" />
      </div>
    </section>
  )
}
