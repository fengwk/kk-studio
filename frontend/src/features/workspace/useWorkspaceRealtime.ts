import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useApplicationEvents } from '@/shared/app-events'
import { isRecord } from '@/shared/lib/value'
import { workspaceKeys } from './queries'

function isFirstScheduleRunsPage(queryKey: readonly unknown[], spaceId: string, scheduleId?: string): boolean {
  if (queryKey[0] !== 'chat-spaces' || queryKey[1] !== spaceId || queryKey[2] !== 'schedules' || queryKey[4] !== 'runs') {
    return false
  }
  if (scheduleId && queryKey[3] !== scheduleId) return false
  const cursor = queryKey[5]
  return isRecord(cursor) && cursor.before === null
}

export function useWorkspaceRealtime(spaceId: string) {
  const query = useQueryClient()
  const events = useApplicationEvents()
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined
    let tree = false, spaces = false, schedules = false
    const scheduleIds = new Set<string>()
    let allScheduleRuns = false
    function flush() {
      clearTimeout(timer)
      timer = undefined
      if (tree) void query.invalidateQueries({ queryKey: workspaceKeys.tree(spaceId), exact: true })
      if (spaces) void query.invalidateQueries({ queryKey: workspaceKeys.spaces(), exact: true })
      if (schedules) {
        void query.invalidateQueries({ queryKey: workspaceKeys.schedules(spaceId), exact: true })
        if (allScheduleRuns) {
          void query.invalidateQueries({ predicate: q => isFirstScheduleRunsPage(q.queryKey, spaceId) })
        } else {
          for (const id of scheduleIds) {
            void query.invalidateQueries({ queryKey: workspaceKeys.scheduleRuns(spaceId, id, null), exact: true })
          }
        }
      }
      tree = spaces = schedules = allScheduleRuns = false
      scheduleIds.clear()
    }
    function sync() { tree = spaces = schedules = allScheduleRuns = true; flush() }
    const unsubscribe = events.subscribe({ kind: 'chat-space', id: spaceId }, {
      onSubscribed: sync, onResync: sync, onError: sync,
      onEvent: (name, data) => {
        if (name === 'space') spaces = true
        else if (name === 'tree' || name === 'threads') tree = true
        else if (name === 'schedules') {
          schedules = true
          const scheduleId = isRecord(data) && typeof data.scheduleId === 'string' ? data.scheduleId : null
          if (scheduleId) scheduleIds.add(scheduleId)
          else allScheduleRuns = true
        }
        else return
        timer ??= setTimeout(flush, 500)
      },
    })
    return () => { unsubscribe(); clearTimeout(timer) }
  }, [events, query, spaceId])
}
