import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { mergeSessionEvent, parseSessionEvent } from '@/features/ai/session-event-stream'
import { createSessionApi } from '@/shared/api/agent-service'
import type { AgentSessionEventDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAgentSessionEventStream(sessionId: string, enabled: boolean) {
  const queryClient = useQueryClient()

  useEffect(() => {
    if (!sessionId || !enabled) {
      return undefined
    }

    const eventSource = createSessionApi().createEventStream(sessionId)
    const handleSessionEvent = (event: MessageEvent<string>) => {
      const sessionEvent = parseSessionEvent(event.data)
      if (!sessionEvent) {
        return
      }
      queryClient.setQueryData<AgentSessionEventDTO[]>(queryKeys.sessions.events(sessionId), (events = []) =>
        mergeSessionEvent(events, sessionEvent),
      )
    }

    eventSource.addEventListener('session_event', handleSessionEvent as EventListener)
    return () => eventSource.close()
  }, [enabled, queryClient, sessionId])
}
