import { useMutation, useQueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import type { BackendLong, HarnessThreadInputDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

/**
 * Submit a user message to the Thread queue.
 * Caller owns draft clearing, clientMessageId retry identity, and the epoch fencing token.
 * Overlapping mutations are allowed for continuous submissions.
 */
export function useAgentThreadMessageMutation(threadId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({
      content,
      clientMessageId,
      expectedExecutionEpoch,
    }: {
      content: string
      clientMessageId: string
      expectedExecutionEpoch: BackendLong
    }): Promise<HarnessThreadInputDTO> =>
      harnessService.submitThreadMessage(threadId, {
        content,
        clientMessageId,
        expectedExecutionEpoch,
      }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.all }),
      ])
    },
  })
}

export function createClientMessageId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `cid-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}
