import { createClientMessageId } from '@/features/ai/runtime'
import type { HarnessThreadDTO, HarnessThreadInputDTO } from '@/shared/api/contracts/ai-runtime'
import { chatService } from '@/shared/api/chat-service'
import { harnessService } from '@/shared/api/harness-service'
import { translate } from '@/shared/i18n'

export interface FirstSendResult {
  sessionId: string
  thread: HarnessThreadDTO
  userMessageInput: HarnessThreadInputDTO
}

/**
 * Blank pane first send order:
 * 1) create a fully bound Thread atomically associated with the Chat
 * 2) enqueue USER_MESSAGE against the returned Thread epoch
 */
export async function performBlankPaneFirstSend(options: {
  chatId: string
  content: string
  createChatThread?: typeof chatService.createChatThread
  submitThreadMessage?: typeof harnessService.submitThreadMessage
  createIds?: () => { userMessageId: string }
}): Promise<FirstSendResult> {
  const createChatThread = options.createChatThread ?? chatService.createChatThread
  const submitThreadMessage = options.submitThreadMessage ?? harnessService.submitThreadMessage
  const ids = options.createIds?.() ?? ({ userMessageId: createClientMessageId() } as const)

  const created = await createChatThread(options.chatId)
  if (!created.sessionId) {
    throw new Error(translate('ai.runtime.action.firstSendMissingSession'))
  }
  const userMessageInput = await submitThreadMessage(created.threadId, {
    content: options.content,
    clientMessageId: ids.userMessageId,
    expectedExecutionEpoch: created.executionEpoch,
  })

  return {
    sessionId: created.sessionId,
    thread: created,
    userMessageInput,
  }
}
