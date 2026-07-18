import { useInvalidateMutation } from '@/features/ai/useInvalidateMutation'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAiConsoleThreadMutations({
  selectedAgentId,
  title,
  onThreadCreated,
}: {
  selectedAgentId: string
  title: string
  onThreadCreated: (thread: Awaited<ReturnType<typeof harnessService.createThread>>) => void | Promise<void>
}) {
  const createThreadMutation = useInvalidateMutation({
    mutationFn: () =>
      harnessService.createThread({
        agentDefinitionId: selectedAgentId,
        title: title.trim() || undefined,
      }),
    invalidateQueryKeys: [queryKeys.threads.list],
    onSuccess: onThreadCreated,
  })

  return {
    createThread: () => createThreadMutation.mutate(undefined),
    threadMutationError: createThreadMutation.error,
    createThreadPending: createThreadMutation.isPending,
  }
}
