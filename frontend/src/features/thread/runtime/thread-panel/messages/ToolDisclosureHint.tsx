import type { ReactNode } from 'react'
import { CircleX } from 'lucide-react'
import { disclosureHint } from '@/features/thread/runtime/thread-panel/messages/disclosure-policy'
import type {
  DisclosureEdge,
  TextDisclosure,
} from '@/features/thread/runtime/thread-panel/messages/disclosure-policy'

/**
 * 可点击提示行：节点本身完成展开回调；未截断时不渲染任何东西。
 *
 * `placement` 决定提示行相对内容的位置：bash 的窗口保留尾部，提示在上方（earlier），
 * 其余工具提示在内容之后（more）。
 */
export function DisclosureHint({
  disclosure,
  edge,
  onExpand,
  placement,
  children,
}: {
  disclosure: TextDisclosure | null
  edge: DisclosureEdge
  onExpand: (element: HTMLElement) => void
  placement: 'before' | 'after'
  children?: ReactNode
}) {
  const hint = disclosure == null ? null : disclosureHint(disclosure, edge)
  const button = hint == null
    ? null
    : (
      <button
        type="button"
        className="thread-disclosure-hint"
        onClick={(event) => onExpand(event.currentTarget)}
      >
        {hint}
      </button>
    )
  if (children == null) {
    return button
  }
  return placement === 'before' ? <>{button}{children}</> : <>{children}{button}</>
}

/** 固定可见的失败摘要块：正文已含同一文本时不渲染（由调用方判断）。 */
export function ToolErrorBlock({
  errorWindow,
  onExpand,
}: {
  errorWindow: TextDisclosure
  onExpand: (element: HTMLElement) => void
}) {
  return (
    <div className="thread-disclosure-error thread-tool-error">
      <CircleX className="thread-disclosure-error-icon" size={12} aria-hidden="true" />
      <div>
        <p className="thread-disclosure-error-text">{errorWindow.text}</p>
        <DisclosureHint
          disclosure={errorWindow}
          edge="start"
          onExpand={onExpand}
          placement="after"
        />
      </div>
    </div>
  )
}
