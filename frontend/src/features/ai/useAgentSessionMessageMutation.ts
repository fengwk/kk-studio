import { useMutation, useQueryClient } from '@tanstack/react-query'
import { invalidateSessionQueries } from '@/features/ai/agent-session-query-support'
import { createWorkspaceSessionApi } from '@/shared/api/agent-service'

export function useAgentSessionMessageMutation(workspaceId: string, sessionId: string, onSubmitted: () => void | Promise<void>) {
  const queryClient = useQueryClient()
  const sessionApi = createWorkspaceSessionApi(workspaceId)
  return useMutation({
    mutationFn: (content: string) => sessionApi.createMessage(sessionId, { content }),
    onSuccess: async () => {
      await onSubmitted()
      await invalidateSessionQueries(queryClient, workspaceId, sessionId)
    },
  })
}
