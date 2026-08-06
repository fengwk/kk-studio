import { useMutation, useQueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessThreadCommandBatchDTO } from '@/shared/api/contracts/ai-runtime'
import { queryKeys } from '@/shared/lib/query-keys'

/**
 * Atomically enqueues a typed command batch (202 accepted). The caller owns the batch identity:
 * a failed HTTP request must be replayed byte-for-byte (same command IDs/payload/order and the
 * original expected cursors); new semantics produce fresh IDs.
 */
export function useThreadCommandBatchMutation(threadId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (batch: HarnessThreadCommandBatchDTO) =>
      harnessService.enqueueCommands(threadId, batch),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) }),
        // The Chat-scoped Thread list (the only list consumers use) refreshes via chats.all.
        queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
      ])
    },
  })
}
