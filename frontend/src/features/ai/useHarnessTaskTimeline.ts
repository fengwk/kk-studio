import { useQuery, useQueryClient } from '@tanstack/react-query'
import { mergeRootActivityLists } from '@/features/ai/harness-root-activity-stream'
import { loadSubagentTaskTree } from '@/features/ai/subagent-task-tree'
import { harnessService } from '@/shared/api/harness-service'
import type { RootActivityDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

const EMPTY_ACTIVITIES: RootActivityDTO[] = []

/** Root activities/tasks queried by owning sessionId from Thread DTO; poll while enabled. */
export function useHarnessTaskTimeline(sessionId: string, enabled: boolean) {
  const queryClient = useQueryClient()
  const activitiesQuery = useQuery({
    queryKey: queryKeys.sessions.activities(sessionId),
    queryFn: async () => {
      const snapshot = await harnessService.listRootActivities(sessionId)
      const cached =
        queryClient.getQueryData<RootActivityDTO[]>(queryKeys.sessions.activities(sessionId)) ?? []
      return mergeRootActivityLists(snapshot, cached)
    },
    enabled: Boolean(sessionId) && enabled,
    refetchInterval: enabled ? 1500 : false,
  })
  const taskTreeQuery = useQuery({
    queryKey: queryKeys.sessions.taskTree(sessionId),
    queryFn: () => loadSubagentTaskTree(sessionId),
    enabled: Boolean(sessionId) && enabled,
    refetchInterval: enabled ? 2000 : false,
  })
  const activities = activitiesQuery.data ?? EMPTY_ACTIVITIES

  return {
    activities,
    taskTree: taskTreeQuery.data ?? [],
    taskTimelineError: activitiesQuery.error || taskTreeQuery.error,
    taskTimelineLoading: activitiesQuery.isLoading || taskTreeQuery.isLoading,
  }
}
