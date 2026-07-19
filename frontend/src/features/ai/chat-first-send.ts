import { createClientMessageId } from '@/features/ai/useAgentThreadMessageMutation'
import type { HarnessSessionDTO, HarnessThreadInputDTO } from '@/shared/api/contracts'
import { chatService } from '@/shared/api/chat-service'
import { harnessService } from '@/shared/api/harness-service'

export interface FirstSendResult {
  sessionId: string
  threadId: string
  setAgentInput: HarnessThreadInputDTO
  userMessageInput: HarnessThreadInputDTO
}

/**
 * Blank pane first send order:
 * 1) create agentless Session
 * 2) attach Session to Chat
 * 3) enqueue SET_AGENT
 * 4) enqueue USER_MESSAGE with a fresh clientMessageId
 */
export async function performBlankPaneFirstSend(options: {
  chatId: string
  agentDefinitionId: string
  content: string
  createSession?: typeof harnessService.createSession
  attachChatSession?: typeof chatService.attachChatSession
  setThreadAgent?: typeof harnessService.setThreadAgent
  submitThreadMessage?: typeof harnessService.submitThreadMessage
  createIds?: () => { setAgentId: string; userMessageId: string }
}): Promise<FirstSendResult> {
  const createSession = options.createSession ?? harnessService.createSession
  const attachChatSession = options.attachChatSession ?? chatService.attachChatSession
  const setThreadAgent = options.setThreadAgent ?? harnessService.setThreadAgent
  const submitThreadMessage = options.submitThreadMessage ?? harnessService.submitThreadMessage
  const ids =
    options.createIds?.() ??
    ({
      setAgentId: createClientMessageId(),
      userMessageId: createClientMessageId(),
    } as const)

  const session: HarnessSessionDTO = await createSession({})
  await attachChatSession(options.chatId, { sessionId: session.sessionId })
  const setAgentInput = await setThreadAgent(session.mainThreadId, {
    agentDefinitionId: options.agentDefinitionId,
    clientMessageId: ids.setAgentId,
  })
  const userMessageInput = await submitThreadMessage(session.mainThreadId, {
    content: options.content,
    clientMessageId: ids.userMessageId,
  })

  return {
    sessionId: session.sessionId,
    threadId: session.mainThreadId,
    setAgentInput,
    userMessageInput,
  }
}
