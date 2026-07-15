import { useMutation, useQueryClient } from '@tanstack/react-query'
import { invalidateSessionQueries } from '@/features/ai/agent-session-query-support'
import { createSessionApi } from '@/shared/api/agent-service'

export function useAgentSessionMessageMutation(sessionId: string, onSubmitted: () => void | Promise<void>) {
  const queryClient = useQueryClient()
  const sessionApi = createSessionApi()
  return useMutation({
    mutationFn: (content: string) => sessionApi.createMessage(sessionId, { content }),
    onSuccess: async () => {
      await onSubmitted()
      await invalidateSessionQueries(queryClient, sessionId)
    },
  })
}
