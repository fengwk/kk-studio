import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { lastEventIdCursor, mergeThreadEvent, parseThreadEvent } from '@/features/ai/harness-thread-event-stream'
import { harnessService } from '@/shared/api/harness-service'
import type { ThreadEventDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

const MATERIALIZATION_EVENTS = new Set([
  'assistant_completed',
  'assistant_failed',
  'input_applied',
  'tool_results_applied',
  'tool_completed',
  'thread_idle',
  'thread_failed',
  'thread_waiting',
])

const INVALIDATE_ALL_EVENTS = new Set([
  'assistant_completed',
  'assistant_failed',
  'input_applied',
  'tool_results_applied',
  'thread_idle',
  'thread_failed',
  'thread_waiting',
  'permission_resolved',
  'agent_changed',
  'model_changed',
  'yolo_changed',
])

export function useHarnessThreadEventStream(threadId: string, enabled: boolean) {
  const queryClient = useQueryClient()

  useEffect(() => {
    if (!threadId || !enabled) {
      return undefined
    }

    const existing = queryClient.getQueryData<ThreadEventDTO[]>(queryKeys.threads.events(threadId)) ?? []
    const cursor = lastEventIdCursor(existing)
    const eventSource = harnessService.createThreadEventStream(threadId, cursor)
    const handleThreadEvent = (event: MessageEvent<string>) => {
      const threadEvent = parseThreadEvent(event.data)
      if (!threadEvent || threadEvent.threadId !== threadId) {
        return
      }
      queryClient.setQueryData<ThreadEventDTO[]>(queryKeys.threads.events(threadId), (events = []) =>
        mergeThreadEvent(events, threadEvent),
      )
      if (MATERIALIZATION_EVENTS.has(threadEvent.eventType)) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.threads.entries(threadId) })
        void queryClient.invalidateQueries({ queryKey: queryKeys.threads.inputs(threadId) })
      }
      if (INVALIDATE_ALL_EVENTS.has(threadEvent.eventType)) {
        void Promise.all([
          queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(threadId) }),
          queryClient.invalidateQueries({ queryKey: queryKeys.threads.toolInvocations(threadId) }),
          queryClient.invalidateQueries({ queryKey: queryKeys.usage.thread(threadId) }),
          queryClient.invalidateQueries({ queryKey: queryKeys.sessions.all }),
        ])
      }
    }

    eventSource.addEventListener('thread_event', handleThreadEvent as EventListener)
    return () => eventSource.close()
  }, [enabled, queryClient, threadId])
}
