import { formatBackendDate } from '@/shared/lib/date-format'
import { translate } from '@/shared/i18n'
import { formatThreadStatusLabel } from '@/features/thread/runtime/thread-panel/thread-status-format'
import type {
  HarnessSessionSummaryDTO,
  HarnessThreadSummaryDTO,
} from '@/shared/api/generated/contracts'

/** 会话条目里的 Thread 数量：英文区分单复数，中文只有一种量词形式。 */
function threadCountLabel(count: number): string {
  return count === 1
    ? translate('ai.chat.threadCount.one')
    : translate('ai.chat.threadCount.other', { count })
}

export function sessionSelectionItem(session: HarnessSessionSummaryDTO) {
  return {
    id: session.sessionId,
    title: session.name,
    // 条目以可读名称与时间为准：名称是 title，subtitle 先给时间与规模，再给消息预览；
    // 主展示与搜索都以 name 为准，绝不回退为 id。
    subtitle: [
      `${formatBackendDate(session.lastActivityAt)} · ${threadCountLabel(session.threadCount)}`,
      session.firstMessagePreview || null,
    ].filter(Boolean).join(' · '),
    // UUID 只作等宽诊断文本。
    badge: session.sessionId,
  }
}

export function threadSelectionItem(thread: HarnessThreadSummaryDTO) {
  return {
    id: thread.threadId,
    title: thread.name,
    subtitle: [
      `${formatBackendDate(thread.updatedAt)} · ${formatThreadStatusLabel(thread.status)}`,
      thread.headMessagePreview,
    ].filter(Boolean).join(' · '),
    badge: thread.threadId,
  }
}
