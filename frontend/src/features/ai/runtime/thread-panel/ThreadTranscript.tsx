import { MessageSquare } from 'lucide-react'
import type { RefObject } from 'react'
import { MessageList } from '@/features/ai/runtime/thread-panel/messages/MessageList'
import { isVisibleDialogueMessage } from '@/features/ai/runtime/thread-panel/visibility'
import type {
  DialogueMessage,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'

/** Dialogue zone: full-height scroll, block transcript. */
export function ThreadTranscript({
  messages,
  loading,
  error,
  bodyRef,
  onDecideApproval,
  approvalPending = false,
}: {
  messages: DialogueMessage[]
  loading: boolean
  error: unknown
  bodyRef: RefObject<HTMLDivElement | null>
  onDecideApproval?: (message: ToolDialogueMessage, decision: 'ALLOW' | 'DENY') => void
  /** Global approval request in flight: every undecided approval bar disables its buttons. */
  approvalPending?: boolean
}) {
  const { t } = useI18n()
  const hasError = Boolean(error)
  const visibleMessages = messages.filter(isVisibleDialogueMessage)
  const empty = !loading && !hasError && visibleMessages.length === 0

  return (
    <div className="thread-dialogue" ref={bodyRef} role="log" aria-label={t('ai.runtime.thread.transcript')} aria-busy={loading}>
      {loading && <div className="thread-state">{t('ai.runtime.thread.loading')}</div>}
      {hasError && <div className="thread-state danger">{t('ai.runtime.thread.loadFailed')}</div>}
      {empty && (
        <div className="thread-empty">
          <MessageSquare aria-hidden="true" />
          <p>{t('ai.runtime.thread.empty')}</p>
          <small>{t('ai.runtime.thread.keyboardHint')}</small>
        </div>
      )}
      <div className="thread-blocks">
        <MessageList
          messages={visibleMessages}
          onDecideApproval={onDecideApproval}
          approvalPending={approvalPending}
        />
      </div>
    </div>
  )
}
