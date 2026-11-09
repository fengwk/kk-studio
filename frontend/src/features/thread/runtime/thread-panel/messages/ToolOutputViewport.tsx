import type { ReactNode } from 'react'

/**
 * 工具正文的唯一只读文本容器。
 *
 * 对话记录里没有内部滚动：展开后完整显示，长卡片由外层 transcript 滚动。
 * 审批卡片显式传入 `bounded`，保留独立的有界滚动区。
 */
export function ToolOutputViewport({
  children,
  className = '',
  ariaLabel,
  bounded,
}: {
  children: ReactNode
  className?: string
  ariaLabel?: string
  /** 仅审批卡片等交互区开启有界滚动；对话记录必须为 false。 */
  bounded: boolean
}) {
  return (
    <pre
      className={[
        'thread-tool-pre',
        'thread-tool-output',
        bounded ? 'is-bounded' : '',
        className,
      ].filter(Boolean).join(' ')}
      tabIndex={bounded ? 0 : undefined}
      aria-label={ariaLabel}
    >
      {children}
    </pre>
  )
}
