import { useMutation, useQueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

export function useHarnessRunControls(sessionId: string) {
  const queryClient = useQueryClient()
  const invalidate = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.sessions.activities(sessionId) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.sessions.runs(sessionId) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.sessions.entries(sessionId) }),
    ])
  const steerMutation = useMutation({
    mutationFn: (content: string) => harnessService.steer(sessionId, content),
    onSuccess: invalidate,
  })
  const followUpMutation = useMutation({
    mutationFn: (content: string) => harnessService.followUp(sessionId, content),
    onSuccess: invalidate,
  })
  const abortMutation = useMutation({
    mutationFn: () => harnessService.abortRun(sessionId),
    onSuccess: invalidate,
  })

  return {
    pending: steerMutation.isPending || followUpMutation.isPending || abortMutation.isPending,
    error: steerMutation.error || followUpMutation.error || abortMutation.error,
    steer: steerMutation,
    followUp: followUpMutation,
    abort: abortMutation,
  }
}
