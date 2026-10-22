import { formatBackendDate } from '@/features/chat/chat-utils'
import type {
  HarnessSessionSummaryDTO,
  HarnessThreadSummaryDTO,
} from '@/shared/api/generated/contracts'


export function sessionSelectionItem(session: HarnessSessionSummaryDTO) {
  return {
    id: session.sessionId,
    title: session.name,
    // 预览保持次要（subtitle）；主展示与搜索都以 name 为准，绝不回退为 id。
    subtitle: [
      session.firstMessagePreview || null,
      `${formatBackendDate(session.lastActivityAt)} · ${session.threadCount} Threads`,
    ].filter(Boolean).join(' · '),
    // UUID 只作 badge 诊断。
    badge: session.sessionId,
  }
}

export function threadSelectionItem(thread: HarnessThreadSummaryDTO) {
  return {
    id: thread.threadId,
    title: thread.name,
    subtitle: [
      thread.headMessagePreview,
      `${formatBackendDate(thread.updatedAt)} · ${thread.status}`,
    ].filter(Boolean).join(' · '),
    badge: thread.threadId,
  }
}
