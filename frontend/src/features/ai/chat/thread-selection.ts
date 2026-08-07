import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'
import type { PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import { formatBackendDate } from '@/features/ai/chat/chat-utils'
import { translate } from '@/shared/i18n'

/** 基于状态派生的工作中状态：任意非 IDLE 状态，或 processing 标志为真。 */
export function isThreadActive(thread: HarnessThreadDTO | undefined): boolean {
  return Boolean(
    thread && (thread.processing || (thread.status != null && thread.status !== 'IDLE')),
  )
}

/** Chat 作用域的 Thread picker 行；Thread id 同时是选中值和路由标识。 */
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
