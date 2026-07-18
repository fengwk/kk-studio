import { useMutation, useQueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessThreadInputDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

/**
 * Submit a user message to the Thread queue.
 * Caller owns draft clearing and clientMessageId retry identity.
 * Overlapping mutations are allowed for continuous submissions.
 */
export function useAgentThreadMessageMutation(threadId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({
      content,
      clientMessageId,
    }: {
      content: string
      clientMessageId: string
    }): Promise<HarnessThreadInputDTO> =>
      harnessService.submitThreadMessage(threadId, { content, clientMessageId }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.entries(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.inputs(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.list }),
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
