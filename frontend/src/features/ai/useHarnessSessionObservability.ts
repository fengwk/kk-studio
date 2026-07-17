import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessRunDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

export function useHarnessSessionObservability(sessionId: string, activeRun: HarnessRunDTO | undefined) {
  const queryClient = useQueryClient()
  const yoloQuery = useQuery({
    queryKey: queryKeys.sessions.yolo(sessionId),
    queryFn: () => harnessService.getYolo(sessionId),
    enabled: Boolean(sessionId),
  })
  const usageQuery = useQuery({
    queryKey: queryKeys.usage.session(sessionId),
    queryFn: () => harnessService.getSessionUsage(sessionId),
    enabled: Boolean(sessionId),
  })
  const toolInvocationsQuery = useQuery({
    queryKey: queryKeys.runs.toolInvocations(activeRun?.runId ?? ''),
    queryFn: () => harnessService.listToolInvocations(activeRun!.runId),
    enabled: Boolean(activeRun),
    refetchInterval: activeRun ? 1200 : false,
  })
  const setYoloMutation = useMutation({
    mutationFn: (enabled: boolean) => harnessService.setYolo(sessionId, enabled),
    onSuccess: (yolo) => queryClient.setQueryData(queryKeys.sessions.yolo(sessionId), yolo),
  })
  const decideToolMutation = useMutation({
    mutationFn: ({ invocationId, decision }: { invocationId: string; decision: 'allow' | 'deny' }) =>
      harnessService.decideToolInvocation(invocationId, decision),
    onSuccess: async () => {
      if (!activeRun) {
        return
      }
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.runs.toolInvocations(activeRun.runId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.runs(sessionId) }),
      ])
    },
  })

  return {
    yolo: yoloQuery.data,
    usage: usageQuery.data,
    toolInvocations: toolInvocationsQuery.data ?? [],
    observabilityError: yoloQuery.error || usageQuery.error || toolInvocationsQuery.error,
    yoloPending: setYoloMutation.isPending,
    decisionPending: decideToolMutation.isPending,
    setYolo: (enabled: boolean) => setYoloMutation.mutate(enabled),
    decideTool: (invocationId: string, decision: 'allow' | 'deny') => decideToolMutation.mutate({ invocationId, decision }),
  }
}
