import type { ReactNode } from 'react'
import { ThreadWorkingStatus } from '@/features/ai/thread-panel/ThreadWorkingStatus'
import type { QueuedThreadMessage } from '@/features/ai/thread-events'

/**
 * Component zone under the dialogue transcript.
 * Max-height stack for working status + lightweight widgets (subagents list, etc.).
 * pi-base style: flat line items, not a heavy multi-column tree panel.
 */
export function ThreadWidgetStack({
  working,
  queuedMessages = [],
  children,
}: {
  working: boolean
  queuedMessages?: QueuedThreadMessage[]
  children?: ReactNode
}) {
  const hasChildren = Boolean(children)
  const hasQueuedMessages = queuedMessages.length > 0
  if (!working && !hasQueuedMessages && !hasChildren) {
    return null
  }
  return (
    <section className="thread-widget-zone" aria-label="会话组件区">
      <ThreadWorkingStatus active={working || hasQueuedMessages} />
      {hasQueuedMessages ? (
        <ol className="thread-input-queue" aria-label="等待处理的消息">
          {queuedMessages.map((message) => (
            <li key={message.inputId} className="thread-input-queue-item">
              <span className="thread-input-queue-label">queued</span>
              <span className="thread-input-queue-content">{message.text}</span>
            </li>
          ))}
        </ol>
      ) : null}
      {hasChildren ? <div className="thread-widget-stack">{children}</div> : null}
    </section>
  )
}
