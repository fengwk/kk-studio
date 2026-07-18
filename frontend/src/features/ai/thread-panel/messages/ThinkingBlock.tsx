/** Full-width thinking content block — no title, muted surface only. */
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
    <section className={`thread-block thread-block-thinking ${streaming ? 'streaming' : ''}`}>
      <div className="thread-block-body thread-thinking-text">{thinking}</div>
    </section>
  )
}
