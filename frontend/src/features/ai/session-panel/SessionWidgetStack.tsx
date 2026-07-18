import type { ReactNode } from 'react'
import { SessionWorkingStatus } from '@/features/ai/session-panel/SessionWorkingStatus'

/**
 * Component zone under the dialogue transcript.
 * Max-height stack for working status + lightweight widgets (subagents list, etc.).
 * pi-base style: flat line items, not a heavy multi-column tree panel.
 */
export function SessionWidgetStack({
  activeRun,
  children,
}: {
  activeRun: boolean
  children?: ReactNode
}) {
  const hasChildren = Boolean(children)
  if (!activeRun && !hasChildren) {
    return null
  }
  return (
    <section className="session-widget-zone" aria-label="会话组件区">
      <SessionWorkingStatus active={activeRun} />
      {hasChildren ? <div className="session-widget-stack">{children}</div> : null}
    </section>
  )
}
