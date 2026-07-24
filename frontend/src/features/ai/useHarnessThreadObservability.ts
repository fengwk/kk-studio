import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createClientMessageId } from '@/features/ai/useAgentThreadMessageMutation'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessThreadYoloSetDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

export function useHarnessThreadObservability(threadId: string, working: boolean) {
  const queryClient = useQueryClient()
  const usageQuery = useQuery({
    queryKey: queryKeys.usage.thread(threadId),
    queryFn: () => harnessService.getThreadUsage(threadId),
    enabled: Boolean(threadId),
    refetchInterval: working ? 2000 : false,
  })
  const toolInvocationsQuery = useQuery({
    queryKey: queryKeys.threads.toolInvocations(threadId),
    queryFn: () => harnessService.listThreadToolInvocations(threadId),
    enabled: Boolean(threadId),
    refetchInterval: working ? 1200 : false,
  })
  const setYoloMutation = useMutation({
    mutationFn: (data: HarnessThreadYoloSetDTO) => harnessService.setThreadYolo(threadId, data),
    retry: 2,
    retryDelay: 0,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.inputs(threadId) }),
      ])
    },
  })

  return {
    usage: usageQuery.data,
    toolInvocations: toolInvocationsQuery.data ?? [],
    observabilityError: usageQuery.error || toolInvocationsQuery.error,
    yoloPending: setYoloMutation.isPending,
    setYolo: (enabled: boolean) =>
      setYoloMutation.mutate({ yoloEnabled: enabled, clientMessageId: createClientMessageId() }),
  }
}
