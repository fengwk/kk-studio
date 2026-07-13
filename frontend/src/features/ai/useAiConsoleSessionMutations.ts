import { toSessionTitleUpdate } from '@/features/ai/ai-console-utils'
import { useInvalidateMutation } from '@/features/ai/useInvalidateMutation'
import { createWorkspaceSessionApi } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAiConsoleSessionMutations({
  workspaceId,
  selectedAgentName,
  sessionTitle,
  onSessionCreated,
  onSessionUpdated,
  onSessionDeleted,
}: {
  workspaceId: string
  selectedAgentName: string
  sessionTitle: string
  onSessionCreated: (session: Awaited<ReturnType<ReturnType<typeof createWorkspaceSessionApi>['create']>>) => void | Promise<void>
  onSessionUpdated: () => void | Promise<void>
  onSessionDeleted: () => void | Promise<void>
}) {
  const sessionApi = createWorkspaceSessionApi(workspaceId)
  const createSessionMutation = useInvalidateMutation({
    mutationFn: () => sessionApi.create({ agentName: selectedAgentName, title: sessionTitle.trim() || undefined }),
    invalidateQueryKeys: [queryKeys.sessions.list(workspaceId)],
    onSuccess: onSessionCreated,
  })
  const updateSessionMutation = useInvalidateMutation({
    mutationFn: ({ sessionId, title }: { sessionId: string; title: string }) => sessionApi.update(sessionId, toSessionTitleUpdate(title)),
    invalidateQueryKeys: [queryKeys.sessions.list(workspaceId)],
    onSuccess: onSessionUpdated,
  })
  const deleteSessionMutation = useInvalidateMutation({
    mutationFn: (sessionId: string) => sessionApi.remove(sessionId),
    invalidateQueryKeys: [queryKeys.sessions.list(workspaceId)],
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
