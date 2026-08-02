export type ThreadMessageKind = 'USER_MESSAGE' | 'CUSTOM_MESSAGE'
export type ThreadMessageRole = 'user' | 'system'

/** Context that makes a message the recovery of a Chat-scoped first send. */
export interface ThreadMessageFirstSendContext {
  chatId: string
}

/**
 * Semantic message fields used to decide whether a clientMessageId can be replayed.
 *
 * expectedExecutionEpoch is intentionally not part of this identity: a refreshed epoch does not
 * change the message being retried.
 */
export interface ThreadMessagePayload {
  kind: ThreadMessageKind
  role: ThreadMessageRole
  content: string
  agentName: string
  environmentName: string | null
  yoloEnabled: boolean
  firstSendContext: ThreadMessageFirstSendContext | null
}

export interface ThreadMessageReplay extends ThreadMessagePayload {
  clientMessageId: string
}

export function sameThreadMessagePayload(
  left: ThreadMessagePayload | null | undefined,
  right: ThreadMessagePayload,
): boolean {
  if (!left) {
    return false
  }
  const leftFirstSendChatId = left.firstSendContext === null
    ? null
    : left.firstSendContext?.chatId
  const rightFirstSendChatId = right.firstSendContext === null
    ? null
    : right.firstSendContext?.chatId
  return (
    left.kind === right.kind
    && left.role === right.role
    && left.content === right.content
    && left.agentName === right.agentName
    && left.environmentName === right.environmentName
    && left.yoloEnabled === right.yoloEnabled
    && leftFirstSendChatId === rightFirstSendChatId
  )
}
