import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  lastEventIdCursor,
  loadThreadEventHistory,
  mergeThreadEventLists,
} from '@/features/ai/harness-thread-event-stream'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'
import type { ThreadEventDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAgentThreadQueries(threadId: string) {
  const queryClient = useQueryClient()
  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
  })
  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
  })
  const providersQuery = useQuery({
    queryKey: queryKeys.providers.list,
    queryFn: () => agentService.listProviders(),
  })
  const threadsQuery = useQuery({
    queryKey: queryKeys.threads.list,
    queryFn: () => harnessService.listThreads(),
  })
  const threadQuery = useQuery({
    queryKey: queryKeys.threads.detail(threadId),
    queryFn: () => harnessService.getThread(threadId),
    enabled: Boolean(threadId),
    refetchInterval: (query) => {
      const thread = query.state.data
      return thread?.processing ? 1200 : false
    },
  })
  const thread = threadQuery.data
  const inputsQuery = useQuery({
    queryKey: queryKeys.threads.inputs(threadId),
    queryFn: () => harnessService.listThreadInputs(threadId),
    enabled: Boolean(threadId),
    refetchInterval: (query) => {
      const inputs = query.state.data ?? []
      // Raw input DTO null appliedEntryId remains an internal polling hint only.
      return inputs.some((input) => !input.appliedEntryId) || thread?.processing ? 1000 : false
    },
  })
  const inputs = inputsQuery.data ?? []
  // Poll entries while processor is active or raw inputs look unapplied.
  const workingHint = Boolean(thread?.processing) || inputs.some((input) => !input.appliedEntryId)

  const entriesQuery = useQuery({
    queryKey: queryKeys.threads.entries(threadId),
    queryFn: () => harnessService.listThreadEntries(threadId),
    enabled: Boolean(threadId),
    refetchInterval: workingHint ? 1000 : false,
  })

  const eventsQuery = useQuery({
    queryKey: queryKeys.threads.events(threadId),
    queryFn: async () => {
      if (!threadId) {
        return []
      }
      const cached = queryClient.getQueryData<ThreadEventDTO[]>(queryKeys.threads.events(threadId)) ?? []
      // The cache preserves backend journal order, so resume after its last event.
      const afterEventId = lastEventIdCursor(cached)
      const page = await loadThreadEventHistory(
        (cursor, limit) => harnessService.listThreadEvents(threadId, cursor, limit),
        200,
        afterEventId,
      )
      return mergeThreadEventLists(cached, page)
    },
    enabled: Boolean(threadId),
  })

  return {
    agentsQuery,
    modelsQuery,
    providersQuery,
    threadsQuery,
    threadQuery,
    entriesQuery,
    inputsQuery,
    eventsQuery,
    agents: agentsQuery.data?.results ?? [],
    models: modelsQuery.data?.results ?? [],
    providers: providersQuery.data?.results ?? [],
    threads: threadsQuery.data ?? [],
    thread,
    entries: entriesQuery.data ?? [],
    inputs,
    events: eventsQuery.data ?? [],
  }
}
