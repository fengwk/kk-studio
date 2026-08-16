/**
 * Events 顶部只读系统提示词：固定 10 行，超出独立滚动，不占用下方 events 列表滚动。
 */
export function ThreadSystemPromptPreview({
  text,
  label,
}: {
  text: string
  label: string
}) {
  return (
    <section className="thread-system-prompt" aria-label={label}>
      <pre className="thread-system-prompt-body">{text}</pre>
    </section>
  )
}
