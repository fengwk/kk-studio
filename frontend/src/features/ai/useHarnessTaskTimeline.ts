import { useMemo } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { mergeRootActivityLists } from '@/features/ai/harness-root-activity-stream'
import { parsePayload, getString } from '@/features/ai/session-event-payload'
import { loadSubagentTaskTree } from '@/features/ai/subagent-task-tree'
import { useHarnessRootActivityStream } from '@/features/ai/useHarnessRootActivityStream'
import { harnessService } from '@/shared/api/harness-service'
import type { RootActivityDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

export interface RelayPermission {
  invocationId: string
  sessionId: string
  tool: string
  workdir: string
  arguments: string
}

const EMPTY_ACTIVITIES: RootActivityDTO[] = []

export function useHarnessTaskTimeline(sessionId: string, enabled: boolean) {
  const queryClient = useQueryClient()
  const activitiesQuery = useQuery({
    queryKey: queryKeys.sessions.activities(sessionId),
    queryFn: async () => {
      const snapshot = await harnessService.listRootActivities(sessionId)
      const cached = queryClient.getQueryData<RootActivityDTO[]>(queryKeys.sessions.activities(sessionId)) ?? []
      return mergeRootActivityLists(snapshot, cached)
    },
    enabled: Boolean(sessionId),
  })
  const taskTreeQuery = useQuery({
    queryKey: queryKeys.sessions.taskTree(sessionId),
    queryFn: () => loadSubagentTaskTree(sessionId),
    enabled: Boolean(sessionId),
  })
  useHarnessRootActivityStream(sessionId, enabled && activitiesQuery.isSuccess)
  const decidePermissionMutation = useMutation({
    mutationFn: ({ invocationId, decision }: { invocationId: string; decision: 'allow' | 'deny' }) =>
      harnessService.decideToolInvocation(invocationId, decision),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.sessions.activities(sessionId) })
    },
  })
  const activities = activitiesQuery.data ?? EMPTY_ACTIVITIES
  const relayPermissions = useMemo(() => findRelayPermissions(activities, sessionId), [activities, sessionId])

  return {
    activities,
    taskTree: taskTreeQuery.data ?? [],
    relayPermissions,
    taskTimelineError: activitiesQuery.error || taskTreeQuery.error,
    taskTimelineLoading: activitiesQuery.isLoading || taskTreeQuery.isLoading,
    permissionDecisionPending: decidePermissionMutation.isPending,
    decidePermission: (invocationId: string, decision: 'allow' | 'deny') =>
      decidePermissionMutation.mutate({ invocationId, decision }),
  }
}

function findRelayPermissions(activities: RootActivityDTO[], rootSessionId: string): RelayPermission[] {
  const pending = new Map<string, RelayPermission>()
  for (const activity of activities) {
    const payload = parsePayload(activity.payloadJson)
    const invocationId = getString(payload.invocationId)
    if (!invocationId) {
      continue
    }
    if (activity.type === 'permission_requested' && activity.sessionId !== rootSessionId) {
      pending.set(invocationId, {
        invocationId,
        sessionId: activity.sessionId,
        tool: getString(payload.tool) || 'Tool',
        workdir: getString(payload.workdir),
        arguments: getString(payload.arguments),
      })
    } else if (activity.type === 'permission_resolved') {
      pending.delete(invocationId)
    }
  }
  return [...pending.values()]
}
