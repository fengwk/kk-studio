/**
 * 思考块：始终展开的纯文本（暂不做 Markdown / 不做折叠）。
 */
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
