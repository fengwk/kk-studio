import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'
import type { PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import { formatBackendDate } from '@/features/ai/chat/chat-utils'
import { translate } from '@/shared/i18n'

/** Derived working state: any status other than IDLE, or processing flag. */
export function isThreadActive(thread: HarnessThreadDTO | undefined): boolean {
  return Boolean(
    thread && (thread.processing || (thread.status != null && thread.status !== 'IDLE')),
  )
}

/** Chat-scoped Thread picker row; the Thread id is the selection value and route identity. */
export function toThreadSelectionItem(
  thread: HarnessThreadDTO,
  sort: PaneSortPreference = 'recent',
) {
  const time = formatBackendDate(sort === 'created' ? thread.createTime : thread.updateTime)
  return {
    id: thread.threadId,
    title: thread.threadId,
    subtitle: time === '-' ? thread.threadId : `${thread.threadId} · ${time}`,
    badge: isThreadActive(thread)
      ? translate('ai.chat.running')
      : thread.status,
  }
}
