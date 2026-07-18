import type { ReactNode } from 'react'
import { ThreadWorkingStatus } from '@/features/ai/thread-panel/ThreadWorkingStatus'

/**
 * Component zone under the dialogue transcript.
 * Max-height stack for working status + lightweight widgets (subagents list, etc.).
 * pi-base style: flat line items, not a heavy multi-column tree panel.
 */
export function ThreadWidgetStack({
  working,
  children,
}: {
  working: boolean
  children?: ReactNode
}) {
  const hasChildren = Boolean(children)
  if (!working && !hasChildren) {
    return null
  }
  return (
    <section className="thread-widget-zone" aria-label="会话组件区">
      <ThreadWorkingStatus active={working} />
      {hasChildren ? <div className="thread-widget-stack">{children}</div> : null}
    </section>
  )
}
