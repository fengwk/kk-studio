import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { mergeRunEvent, parseRunEvent } from '@/features/ai/harness-run-event-stream'
import { harnessService } from '@/shared/api/harness-service'
import type { RunEventDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

const ENTRY_MATERIALIZATION_EVENTS = new Set([
  'assistant_completed',
  'tool_requeued',
  'run_completed',
  'run_failed',
  'run_cancelled',
])
const TERMINAL_RUN_EVENTS = new Set(['run_completed', 'run_failed', 'run_cancelled'])

export function useHarnessRunEventStream(sessionId: string, runId: string | null, enabled: boolean) {
  const queryClient = useQueryClient()

  useEffect(() => {
    if (!sessionId || !runId || !enabled) {
      return undefined
    }

    const existing = queryClient.getQueryData<RunEventDTO[]>(queryKeys.runs.events(runId)) ?? []
    const cursor = existing.reduce((maximum, event) => Math.max(maximum, event.sequence), 0)
    const eventSource = harnessService.createRunEventStream(runId, cursor)
    const handleRunEvent = (event: MessageEvent<string>) => {
      const runEvent = parseRunEvent(event.data)
      if (!runEvent || runEvent.runId !== runId) {
        return
      }
      queryClient.setQueryData<RunEventDTO[]>(queryKeys.runs.events(runId), (events = []) =>
        mergeRunEvent(events, runEvent),
      )
      if (ENTRY_MATERIALIZATION_EVENTS.has(runEvent.type)) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.sessions.entries(sessionId) })
      }
      if (TERMINAL_RUN_EVENTS.has(runEvent.type)) {
        void Promise.all([
          queryClient.invalidateQueries({ queryKey: queryKeys.sessions.detail(sessionId) }),
          queryClient.invalidateQueries({ queryKey: queryKeys.sessions.runs(sessionId) }),
          queryClient.invalidateQueries({ queryKey: queryKeys.sessions.list }),
        ])
      }
    }

    eventSource.addEventListener('run_event', handleRunEvent as EventListener)
    return () => eventSource.close()
  }, [enabled, queryClient, runId, sessionId])
}
