import type { PaneSortPreference } from '@/features/ai/chat-pane-state'
import { sortWithRunningFirst } from '@/features/ai/chat-pane-state'
import type { HarnessSessionDTO, HarnessThreadDTO } from '@/shared/api/contracts'

export function isRunningThread(thread: HarnessThreadDTO): boolean {
  return (
    thread.status === 'RUNNING'
    || thread.status === 'WAITING'
    || thread.status === 'RUNNABLE'
    || Boolean(thread.processing)
  )
}

/** True when any Thread of the Session is running / waiting / runnable / processing. */
export function isSessionRunning(threads: HarnessThreadDTO[] | undefined): boolean {
  return (threads ?? []).some(isRunningThread)
}

/**
 * Sort Chat member Sessions with running-first semantics for EVERY Session.
 * `threadsBySessionId` must cover all candidate sessions (missing lists count as not running).
 */
export function sortChatSessionsWithRunningFirst(
  sessions: HarnessSessionDTO[],
  threadsBySessionId: ReadonlyMap<string, HarnessThreadDTO[]>,
  sort: PaneSortPreference,
): HarnessSessionDTO[] {
  return sortWithRunningFirst(sessions, sort, (session) =>
    isSessionRunning(threadsBySessionId.get(session.sessionId)),
  )
}

export function toSessionSelectionItem(session: HarnessSessionDTO) {
  return {
    id: session.sessionId,
    title: session.title || session.sessionId,
    subtitle: `Main ${session.mainThreadId}`,
    badge: undefined as string | undefined,
  }
}

export function toSessionSelectionItemWithRunning(
  session: HarnessSessionDTO,
  running: boolean,
) {
  return {
    ...toSessionSelectionItem(session),
    badge: running ? 'RUNNING' : undefined,
  }
}
