import { MessageSquare } from 'lucide-react'
import type { RefObject } from 'react'
import { MessageList } from '@/features/ai/runtime/thread-panel/messages/MessageList'
import { isVisibleDialogueMessage } from '@/features/ai/runtime/thread-panel/visibility'
import type {
  DialogueMessage,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'

/** 对话区域：满高滚动，以块状方式展示 transcript。 */
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
  onDecideApproval?: (
    message: ToolDialogueMessage,
    decision: 'ALLOW' | 'DENY',
  ) => void | Promise<void>
  /** 进行中的全局审批请求：所有未决的审批条都会禁用其按钮。 */
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
