import { createClientMessageId } from '@/features/ai/runtime'
import type { HarnessThreadDTO, HarnessThreadInputDTO } from '@/shared/api/contracts/ai-runtime'
import { harnessService } from '@/shared/api/harness-service'

export interface FirstSendResult {
  sessionId: string
  thread: HarnessThreadDTO
  userMessageInput: HarnessThreadInputDTO
}

/**
 * Blank pane first send order:
 * 1) create an UNBOUND Thread
 * 2) bootstrap it with the default Agent and yolo, creating Session/ROOT/RUNTIME_CONFIG
 * 3) enqueue USER_MESSAGE against the epoch returned by bootstrap
 */
export async function performBlankPaneFirstSend(options: {
  agentDefinitionId: string
  content: string
  yoloEnabled?: boolean
  title?: string
  createThread?: typeof harnessService.createThread
  bootstrapThread?: typeof harnessService.bootstrapThread
  submitThreadMessage?: typeof harnessService.submitThreadMessage
  createIds?: () => { userMessageId: string }
}): Promise<FirstSendResult> {
  const createThread = options.createThread ?? harnessService.createThread
  const bootstrapThread = options.bootstrapThread ?? harnessService.bootstrapThread
  const submitThreadMessage = options.submitThreadMessage ?? harnessService.submitThreadMessage
  const ids = options.createIds?.() ?? ({ userMessageId: createClientMessageId() } as const)

  const created = await createThread()
  const bootstrapped = await bootstrapThread(created.threadId, {
    title: options.title,
    agentDefinitionId: options.agentDefinitionId,
    yoloEnabled: options.yoloEnabled ?? false,
    expectedExecutionEpoch: created.executionEpoch,
  })
  const userMessageInput = await submitThreadMessage(bootstrapped.thread.threadId, {
    content: options.content,
    clientMessageId: ids.userMessageId,
    expectedExecutionEpoch: bootstrapped.thread.executionEpoch,
  })

  return {
    sessionId: bootstrapped.session.sessionId,
    thread: bootstrapped.thread,
    userMessageInput,
  }
}
