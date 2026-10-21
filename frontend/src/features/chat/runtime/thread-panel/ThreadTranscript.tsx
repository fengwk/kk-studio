import { MessageSquare } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import type { RefObject } from 'react'
import { MessageList } from '@/features/chat/runtime/thread-panel/messages/MessageList'
import { isVisibleDialogueMessage } from '@/features/chat/runtime/thread-panel/visibility'
import type { DialogueMessage } from '@/features/chat/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'

/** 对话区域：满高滚动，以块状方式展示 transcript。 */
export function ThreadTranscript({
  messages,
  loading,
  error,
  errorText,
  onRetry,
  emptyText,
  bodyRef,
}: {
  messages: DialogueMessage[]
  loading: boolean
  error: unknown
  errorText?: string
  onRetry?: () => void
  emptyText?: string
  bodyRef: RefObject<HTMLDivElement | null>
}) {
  const { t } = useI18n()
  const hasError = Boolean(error)
  const visibleMessages = messages.filter(isVisibleDialogueMessage)
  const empty = !loading && !hasError && (visibleMessages.length === 0 || emptyText != null)

  return (
    <div className="thread-dialogue" ref={bodyRef} role="log" aria-label={t('ai.runtime.thread.transcript')} aria-busy={loading}>
      {loading && <div className="thread-state">{t('ai.runtime.thread.loading')}</div>}
      {hasError && (
        <div className="thread-state danger" role="alert">
          {errorText ?? t('ai.runtime.thread.loadFailed')}
          {onRetry ? <Button variant="ghost" onClick={onRetry}>{t('shared.conflict.retry')}</Button> : null}
        </div>
      )}
      {empty && (
        <div className="thread-empty">
          <MessageSquare aria-hidden="true" />
          <p>{emptyText ?? t('ai.runtime.thread.empty')}</p>
          <small>{t('ai.runtime.thread.keyboardHint')}</small>
        </div>
      )}
      <div className="thread-blocks">
        <MessageList messages={visibleMessages} />
      </div>
    </div>
  )
}
