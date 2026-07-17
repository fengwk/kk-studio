import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { compareRootActivityIds, mergeRootActivity, parseRootActivity } from '@/features/ai/harness-root-activity-stream'
import { harnessService } from '@/shared/api/harness-service'
import type { RootActivityDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

const TASK_ACTIVITY_TYPES = new Set([
  'subagent_started',
  'subagent_resumed',
  'subagent_completed',
  'subagent_cancel_requested',
])

export function useHarnessRootActivityStream(sessionId: string, enabled: boolean) {
  const queryClient = useQueryClient()

  useEffect(() => {
    if (!sessionId || !enabled) {
      return undefined
    }

    const existing = queryClient.getQueryData<RootActivityDTO[]>(queryKeys.sessions.activities(sessionId)) ?? []
    const cursor = existing.reduce((maximum, activity) => (compareRootActivityIds(activity.eventId, maximum) > 0 ? activity.eventId : maximum), '0')
    const eventSource = harnessService.createRootActivityStream(sessionId, cursor)
    const handleActivity = (event: MessageEvent<string>) => {
      const activity = parseRootActivity(event.data)
      if (!activity) {
        return
      }
      queryClient.setQueryData<RootActivityDTO[]>(queryKeys.sessions.activities(sessionId), (activities = []) =>
        mergeRootActivity(activities, activity),
      )
      if (TASK_ACTIVITY_TYPES.has(activity.type)) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.sessions.taskTree(sessionId) })
      }
    }

    eventSource.addEventListener('root_activity', handleActivity as EventListener)
    return () => eventSource.close()
  }, [enabled, queryClient, sessionId])
}
