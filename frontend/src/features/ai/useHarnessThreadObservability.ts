import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
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
    mutationFn: (yoloEnabled: boolean) => harnessService.setThreadYolo(threadId, { yoloEnabled }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.inputs(threadId) }),
      ])
    },
  })
  const decideToolMutation = useMutation({
    mutationFn: ({ invocationId, decision }: { invocationId: string; decision: 'allow' | 'deny' }) =>
      harnessService.decideToolInvocation(invocationId, decision),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.toolInvocations(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.events(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(threadId) }),
      ])
    },
  })

  return {
    usage: usageQuery.data,
    toolInvocations: toolInvocationsQuery.data ?? [],
    observabilityError: usageQuery.error || toolInvocationsQuery.error,
    yoloPending: setYoloMutation.isPending,
    decisionPending: decideToolMutation.isPending,
    setYolo: (enabled: boolean) => setYoloMutation.mutate(enabled),
    decideTool: (invocationId: string, decision: 'allow' | 'deny') =>
      decideToolMutation.mutate({ invocationId, decision }),
  }
}
