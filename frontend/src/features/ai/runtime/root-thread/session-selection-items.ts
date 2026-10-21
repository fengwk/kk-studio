import type {
  RuntimeSessionSummaryDTO,
  RuntimeThreadSummaryDTO,
} from '@/shared/api/contracts/ai-runtime'
import { formatBackendDate } from '@/features/ai/chat/chat-utils'


export function sessionSelectionItem(session: RuntimeSessionSummaryDTO) {
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

export function threadSelectionItem(thread: RuntimeThreadSummaryDTO) {
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
