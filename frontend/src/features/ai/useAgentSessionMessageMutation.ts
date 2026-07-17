import { useMutation, useQueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAgentSessionMessageMutation(
  sessionId: string,
  expectedLeafEntryId: string | null,
  onSubmitted: () => void | Promise<void>,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (content: string) => {
      if (!expectedLeafEntryId) {
        throw new Error('会话尚未准备好接收消息')
      }
      return harnessService.createMessage(sessionId, { content, expectedLeafEntryId })
    },
    onSuccess: async () => {
      await onSubmitted()
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.detail(sessionId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.entries(sessionId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.runs(sessionId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.list }),
      ])
    },
  })
}
