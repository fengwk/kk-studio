import { ChevronDown } from 'lucide-react'

/** Collapsible thinking trace — pi AssistantMessage thinking block for the web. */
export function ThinkingBlock({
  thinking,
  streaming,
  hasText,
}: {
  thinking: string
  streaming: boolean
  hasText: boolean
}) {
  if (!thinking.trim()) {
    return null
  }
  return (
    <details className="session-thinking" open={streaming && !hasText}>
      <summary>
        <ChevronDown aria-hidden="true" />
        <span>{streaming && !hasText ? 'Thinking…' : '思考过程'}</span>
      </summary>
      <div className="session-thinking-body">{thinking}</div>
    </details>
  )
}
