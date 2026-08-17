import type { CSSProperties } from 'react'

/**
 * Tool 输出默认限制为五行；文本本身已由展示策略裁剪，因此不创建嵌套纵向滚动区，
 * 鼠标滚轮始终交给外层 transcript。
 */
export function ToolOutputViewport({
  text,
  className = '',
  maxLines,
}: {
  text: string
  className?: string
  maxLines?: number | null
}) {
  const resolvedMaxLines = maxLines === undefined ? 5 : maxLines
  const style = resolvedMaxLines == null
    ? undefined
    : { '--thread-tool-output-lines': resolvedMaxLines } as CSSProperties

  return (
    <pre
      className={[
        'thread-tool-pre',
        'thread-tool-output',
        resolvedMaxLines == null ? 'is-expanded' : '',
        className,
      ].filter(Boolean).join(' ')}
      style={style}
    >
      {text}
    </pre>
  )
}
