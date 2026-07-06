import { useMutation, useQueryClient } from '@tanstack/react-query'
import { invalidateSessionQueries } from '@/features/ai/agent-session-query-support'
import { agentService } from '@/shared/api/agent-service'

export function useAgentSessionMessageMutation(sessionId: string, onSubmitted: () => void | Promise<void>) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (content: string) => agentService.createMessage(sessionId, { content }),
    onSuccess: async () => {
      await onSubmitted()
      await invalidateSessionQueries(queryClient, sessionId)
    },
  })
}
