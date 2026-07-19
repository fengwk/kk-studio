import { useInvalidateMutation } from '@/features/ai/useInvalidateMutation'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAiConsoleSessionMutations({
  selectedAgentId,
  title,
  onSessionCreated,
}: {
  selectedAgentId: string
  title: string
  onSessionCreated: (session: Awaited<ReturnType<typeof harnessService.createSession>>) => void | Promise<void>
}) {
  const createSessionMutation = useInvalidateMutation({
    mutationFn: () =>
      harnessService.createSession({
        agentDefinitionId: selectedAgentId,
        title: title.trim() || undefined,
      }),
    invalidateQueryKeys: [queryKeys.sessions.list],
    onSuccess: onSessionCreated,
  })

  return {
    createSession: () => createSessionMutation.mutate(undefined),
    sessionMutationError: createSessionMutation.error,
    createSessionPending: createSessionMutation.isPending,
  }
}
