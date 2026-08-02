import type { PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import { sortWithRunningFirst } from '@/features/ai/chat/chat-pane-state'
import type { HarnessSessionDTO, HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'
import { formatBackendDate } from '@/features/ai/chat/chat-utils'
import { translate } from '@/shared/i18n'

export function isRunningThread(thread: HarnessThreadDTO): boolean {
  return (
    thread.status === 'RUNNING'
    || thread.status === 'WAITING'
    || thread.status === 'RUNNABLE'
    || Boolean(thread.processing)
  )
}

/** Only logically quiescent Threads accept an external head rebind. */
export function canRebindThread(thread: HarnessThreadDTO | undefined): boolean {
  if (!thread) {
    return false
  }
  return !isRunningThread(thread) && thread.status === 'IDLE'
}

/** True when any Thread currently bound to the Session is running / waiting / runnable / processing. */
export function isSessionRunning(threads: HarnessThreadDTO[] | undefined): boolean {
  return (threads ?? []).some(isRunningThread)
}

/** Groups globally listed Threads by their derived (nullable) Session. */
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

function toSessionSelectionItem(session: HarnessSessionDTO) {
  return {
    id: session.sessionId,
    title: session.title || session.sessionId,
    subtitle: translate('ai.chat.sessionSubtitle', { id: session.sessionId }),
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

/** Global Thread picker row. */
export function toThreadSelectionItem(
  thread: HarnessThreadDTO,
  sort: PaneSortPreference = 'recent',
) {
  const running = isRunningThread(thread)
  const time = formatBackendDate(sort === 'created' ? thread.createTime : thread.updateTime)
  const context =
    thread.sessionTitle
    || thread.sessionId
    || thread.threadId
  return {
    id: thread.threadId,
    title: thread.threadId,
    subtitle: time === '-' ? context : `${context} · ${time}`,
    badge: running ? 'RUNNING' : thread.status,
  }
}
