import { CircleDot, FileWarning } from 'lucide-react'
import { NotificationEntryBlock } from '@/features/ai/runtime/thread-panel/messages/NotificationEntryBlock'
import type { EntryEventDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

/**
 * 可复用的持久 Entry 审计块。它接收的是稳定的 timeline 契约，
 * 而不是 Harness DTO、controller 或 query state。
 */
export function EntryMessageBlock({ message }: { message: EntryEventDialogueMessage }) {
  if (message.kind === 'notification') {
    return (
      <NotificationEntryBlock
        message={message}
        notification={message.notification ?? { kind: '', sourceThreadId: null }}
      />
    )
  }
  const singleLine = message.kind === 'root'
    || message.kind === 'settings_change'
    || message.kind === 'invalid_settings'
  return (
    <section
      className={`thread-block thread-block-entry kind-${message.kind}${singleLine ? ' is-inline' : ''}`}
      data-entry-kind={message.kind}
    >
      <div className="thread-entry-row">
        <span className="thread-entry-icon" aria-hidden="true">
          <EntryIcon kind={message.kind} />
        </span>
        <div className="thread-entry-content">
          <div className="thread-entry-title" title={singleLine ? message.title : undefined}>{message.title}</div>
          {!singleLine && (
            <div className="thread-block-body thread-entry-text">{message.text}</div>
          )}
        </div>
      </div>
    </section>
  )
}

function EntryIcon({ kind }: { kind: EntryEventDialogueMessage['kind'] }) {
  if (kind === 'unsupported_message' || kind === 'unknown_entry') {
    return <FileWarning />
  }
  return <CircleDot />
}
