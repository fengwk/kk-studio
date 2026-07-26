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

/** Only logically quiescent Threads accept an external head rebind; ACTIVE ones are rejected. */
export function canRebindThread(thread: HarnessThreadDTO | undefined): boolean {
  if (!thread) {
    return false
  }
  return !isRunningThread(thread) && (thread.status === 'UNBOUND' || thread.status === 'IDLE')
}

/** True when any Thread currently bound to the Session is running / waiting / runnable / processing. */
export function isSessionRunning(threads: HarnessThreadDTO[] | undefined): boolean {
  return (threads ?? []).some(isRunningThread)
}

/** Groups globally listed Threads by their derived (nullable) Session; UNBOUND Threads are skipped. */
export function groupThreadsBySessionId(
  threads: HarnessThreadDTO[],
): Map<string, HarnessThreadDTO[]> {
  const map = new Map<string, HarnessThreadDTO[]>()
  for (const thread of threads) {
    if (!thread.sessionId) {
      continue
    }
    const bucket = map.get(thread.sessionId) ?? []
    bucket.push(thread)
    map.set(thread.sessionId, bucket)
  }
  return map
}

/**
 * Sort Sessions with running-first semantics for EVERY Session.
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
    subtitle: `Session ${session.sessionId}`,
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

/** Global Thread picker row; UNBOUND Threads stay selectable so they can be bound later. */
export function toThreadSelectionItem(thread: HarnessThreadDTO) {
  const running = isRunningThread(thread)
  return {
    id: thread.threadId,
    title: thread.threadId,
    subtitle: thread.activeAgentName || thread.sessionTitle || thread.sessionId || '未绑定 Session',
    badge: running ? 'RUNNING' : thread.status,
  }
}
