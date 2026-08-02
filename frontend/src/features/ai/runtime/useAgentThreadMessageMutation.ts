import { useMutation, useQueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessThreadInputDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { BackendLong } from '@/shared/api/contracts/base'
import { queryKeys } from '@/shared/lib/query-keys'
import type {
  ThreadMessageKind,
  ThreadMessagePayload,
  ThreadMessageRole,
} from '@/features/ai/runtime/thread-message-retry'

/**
 * Submit a user message to the Thread queue.
 * Caller owns draft clearing, clientMessageId retry identity, and the epoch fencing token.
 * Overlapping mutations are allowed for continuous submissions.
 */
export function useAgentThreadMessageMutation(threadId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({
      kind = 'USER_MESSAGE',
      role = 'user',
      firstSendContext,
      content,
      agentName,
      environmentName,
      yoloEnabled,
      clientMessageId,
      expectedExecutionEpoch,
    }: ThreadMessageMutationInput): Promise<HarnessThreadInputDTO> => {
      void firstSendContext
      if (kind === 'CUSTOM_MESSAGE') {
        return harnessService.submitCustomMessage(threadId, {
          role,
          content,
          agentName,
          environmentName,
          yoloEnabled,
          clientMessageId,
          expectedExecutionEpoch,
        })
      }
      return harnessService.submitThreadMessage(threadId, {
        content,
        agentName,
        environmentName,
        yoloEnabled,
        clientMessageId,
        expectedExecutionEpoch,
      })
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.all }),
      ])
    },
  })
}

export type ThreadMessageMutationInput = Omit<
  ThreadMessagePayload,
  'kind' | 'role' | 'firstSendContext'
> & {
  kind?: ThreadMessageKind
  role?: ThreadMessageRole
  firstSendContext?: ThreadMessagePayload['firstSendContext']
  clientMessageId: string
  expectedExecutionEpoch: BackendLong
}

export function createClientMessageId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `cid-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}
