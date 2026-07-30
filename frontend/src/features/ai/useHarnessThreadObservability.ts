import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createClientMessageId } from '@/features/ai/useAgentThreadMessageMutation'
import { harnessService } from '@/shared/api/harness-service'
import type {
  BackendLong,
  HarnessThreadYoloSetDTO,
  ModelUsageSummaryDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

export function useHarnessThreadObservability(
  threadId: string,
  usage: ModelUsageSummaryDTO | undefined,
  toolInvocations: ToolInvocationDTO[],
) {
  const queryClient = useQueryClient()
  const setYoloMutation = useMutation({
    mutationFn: (data: HarnessThreadYoloSetDTO) => harnessService.setThreadYolo(threadId, data),
    retry: 2,
    retryDelay: 0,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) })
    },
  })

  return {
    usage,
    toolInvocations,
    observabilityError: null,
    yoloPending: setYoloMutation.isPending,
    setYolo: (enabled: boolean, expectedExecutionEpoch: BackendLong) =>
      setYoloMutation.mutate({
        yoloEnabled: enabled,
        clientMessageId: createClientMessageId(),
        expectedExecutionEpoch,
      }),
  }
}
