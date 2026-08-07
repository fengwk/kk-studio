import { useMutation, useQueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessThreadCommandBatchDTO } from '@/shared/api/contracts/ai-runtime'
import { queryKeys } from '@/shared/lib/query-keys'

/**
 * 原子地入队一个类型化 command batch（202 已接受）。batch 身份由调用方持有：
 * 失败的 HTTP 请求必须逐字节重放（相同的 command IDs/payload/顺序以及
 * 原始期望 cursors）；新语义产生新 IDs。
 */
export function useThreadCommandBatchMutation(threadId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (batch: HarnessThreadCommandBatchDTO) =>
      harnessService.enqueueCommands(threadId, batch),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) }),
        // Chat 作用域的 Thread 列表（唯一被使用的列表消费者）通过 chats.all 刷新。
        queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
      ])
    },
  })
}
