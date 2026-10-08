import { normalizeThreadName } from '@/features/ai/chat/thread-name'
import type { PaneTarget } from '@/features/ai/runtime/agent-pane'
import type { RuntimeThreadSummaryDTO } from '@/shared/api/contracts/ai-runtime'

/** Only execution roots and uncommitted drafts occupy the user Thread namespace. */
export function hasThreadNameConflict(
  name: string,
  sessionId: string,
  destinationPaneId: string,
  threads: RuntimeThreadSummaryDTO[],
  localTargets: Record<string, PaneTarget>,
): boolean {
  const normalized = normalizeThreadName(name)
  return threads.some((thread) =>
    thread.parentThreadId === null && normalizeThreadName(thread.name) === normalized,
  ) || Object.entries(localTargets).some(([paneId, target]) =>
    paneId !== destinationPaneId
    && target.kind === 'NEW_THREAD_DRAFT'
    && target.sessionId === sessionId
    && normalizeThreadName(target.threadName) === normalized,
  )
}
