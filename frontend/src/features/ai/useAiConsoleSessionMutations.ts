import { toSessionTitleUpdate } from '@/features/ai/ai-console-utils'
import { useInvalidateMutation } from '@/features/ai/useInvalidateMutation'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAiConsoleSessionMutations({
  selectedAgentName,
  sessionTitle,
  onSessionCreated,
  onSessionUpdated,
  onSessionDeleted,
}: {
  selectedAgentName: string
  sessionTitle: string
  onSessionCreated: (session: Awaited<ReturnType<typeof agentService.createSession>>) => void | Promise<void>
  onSessionUpdated: () => void | Promise<void>
  onSessionDeleted: () => void | Promise<void>
}) {
  const createSessionMutation = useInvalidateMutation({
    mutationFn: () => agentService.createSession({ agentName: selectedAgentName, title: sessionTitle.trim() || undefined }),
    invalidateQueryKeys: [queryKeys.sessions.list],
    onSuccess: onSessionCreated,
  })

  const updateSessionMutation = useInvalidateMutation({
    mutationFn: ({ sessionId, title }: { sessionId: string; title: string }) => agentService.updateSession(sessionId, toSessionTitleUpdate(title)),
    invalidateQueryKeys: [queryKeys.sessions.list],
    onSuccess: onSessionUpdated,
  })

  const deleteSessionMutation = useInvalidateMutation({
    mutationFn: (sessionId: string) => agentService.deleteSession(sessionId),
    invalidateQueryKeys: [queryKeys.sessions.list],
    onSuccess: onSessionDeleted,
  })

  return {
    createSession: () => createSessionMutation.mutate(undefined),
    updateSession: (sessionId: string, title: string) => updateSessionMutation.mutate({ sessionId, title }),
    deleteSession: (sessionId: string) => deleteSessionMutation.mutate(sessionId),
    sessionMutationError: createSessionMutation.error || updateSessionMutation.error || deleteSessionMutation.error,
    sessionDeletePending: deleteSessionMutation.isPending,
    createSessionPending: createSessionMutation.isPending,
    updateSessionPending: updateSessionMutation.isPending,
  }
}
