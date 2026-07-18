/** Full-width thinking content block. */
export function ThinkingBlock({
  thinking,
  streaming,
}: {
  thinking: string
  streaming: boolean
}) {
  if (!thinking.trim()) {
    return null
  }
  return (
    <section className={`session-block session-block-thinking ${streaming ? 'streaming' : ''}`}>
      <div className="session-block-label">{streaming ? 'thinking…' : 'thinking'}</div>
      <div className="session-block-body session-thinking-text">{thinking}</div>
    </section>
  )
}
