import { createClientMessageId } from '@/features/ai/runtime'
import type {
  ThreadMessagePayload,
  ThreadMessageReplay,
} from '@/features/ai/runtime/thread-message-retry'
import type { HarnessThreadDTO, HarnessThreadInputDTO } from '@/shared/api/contracts/ai-runtime'
import { chatService } from '@/shared/api/chat-service'
import { harnessService } from '@/shared/api/harness-service'
import { translate } from '@/shared/i18n'

export interface FirstSendResult {
  sessionId: string
  thread: HarnessThreadDTO
  userMessageInput: HarnessThreadInputDTO
}

export interface FirstSendReplay extends ThreadMessageReplay {
  threadId: string
}

/** Carries the atomically created Thread across a failed first-message request. */
export class FirstSendMessageError extends Error {
  readonly replay: FirstSendReplay
  readonly thread: HarnessThreadDTO
  readonly cause: unknown

  constructor(
    thread: HarnessThreadDTO,
    payload: ThreadMessagePayload,
    clientMessageId: string,
    cause: unknown,
  ) {
    super(cause instanceof Error ? cause.message : String(cause))
    this.name = 'FirstSendMessageError'
    this.replay = {
      threadId: thread.threadId,
      ...payload,
      clientMessageId,
    }
    this.thread = thread
    this.cause = cause
  }
}

/**
 * Blank pane first send order:
 * 1) create a fully bound Thread atomically associated with the Chat
 * 2) enqueue USER_MESSAGE against the returned Thread epoch
 */
export async function performBlankPaneFirstSend(options: {
  chatId: string
  content: string
  agentName: string
  environmentName: string | null
  yoloEnabled: boolean
  createChatThread?: typeof chatService.createChatThread
  submitThreadMessage?: typeof harnessService.submitThreadMessage
  createIds?: () => { userMessageId: string }
}): Promise<FirstSendResult> {
  const createChatThread = options.createChatThread ?? chatService.createChatThread
  const submitThreadMessage = options.submitThreadMessage ?? harnessService.submitThreadMessage
  const ids = options.createIds?.() ?? ({ userMessageId: createClientMessageId() } as const)
  const payload: ThreadMessagePayload = {
    kind: 'USER_MESSAGE',
    role: 'user',
    content: options.content,
    agentName: options.agentName,
    environmentName: options.environmentName,
    yoloEnabled: options.yoloEnabled,
    firstSendContext: { chatId: options.chatId },
  }

  const created = await createChatThread(options.chatId)
  if (!created.sessionId) {
    throw new Error(translate('ai.runtime.action.firstSendMissingSession'))
  }
  let userMessageInput: HarnessThreadInputDTO
  try {
    userMessageInput = await submitThreadMessage(created.threadId, {
      content: options.content,
      agentName: options.agentName,
      environmentName: options.environmentName,
      yoloEnabled: options.yoloEnabled,
      clientMessageId: ids.userMessageId,
      expectedExecutionEpoch: created.executionEpoch,
    })
  } catch (error) {
    throw new FirstSendMessageError(
      created,
      payload,
      ids.userMessageId,
      error,
    )
  }

  return {
    sessionId: created.sessionId,
    thread: created,
    userMessageInput,
  }
}
