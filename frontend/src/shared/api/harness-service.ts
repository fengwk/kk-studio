import { apiBaseUrl, apiClient, type HttpClient } from '@/shared/api/client'
import type {
  HarnessRunDTO,
  HarnessSessionCreateDTO,
  HarnessSessionDTO,
  HarnessSessionEntryDTO,
  HarnessSessionMessageCreateDTO,
  ModelUsageSummaryDTO,
  RootActivityDTO,
  RunEventDTO,
  SessionYoloDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts'

export function createHarnessService(client: HttpClient = apiClient) {
  return {
    listSessions: (): Promise<HarnessSessionDTO[]> => client.get('/sessions'),
    createSession: (data: HarnessSessionCreateDTO): Promise<HarnessSessionDTO> => client.post('/sessions', data),
    getSession: (sessionId: string): Promise<HarnessSessionDTO> => client.get(`/sessions/${encodeURIComponent(sessionId)}`),
    listEntries: (sessionId: string): Promise<HarnessSessionEntryDTO[]> => client.get(`/sessions/${encodeURIComponent(sessionId)}/entries`),
    createMessage: (sessionId: string, data: HarnessSessionMessageCreateDTO): Promise<HarnessSessionEntryDTO> =>
      client.post(`/sessions/${encodeURIComponent(sessionId)}/messages`, data),
    listRuns: (sessionId: string): Promise<HarnessRunDTO[]> => client.get(`/sessions/${encodeURIComponent(sessionId)}/runs`),
    listRunEvents: (runId: string, afterSequence = 0): Promise<RunEventDTO[]> =>
      client.get(`/runs/${encodeURIComponent(runId)}/events`, { params: { afterSequence } }),
    listRootActivities: (sessionId: string, afterEventId = 0): Promise<RootActivityDTO[]> =>
      client.get(`/sessions/${encodeURIComponent(sessionId)}/activities`, { params: { afterEventId } }),
    listToolInvocations: (runId: string): Promise<ToolInvocationDTO[]> =>
      client.get(`/runs/${encodeURIComponent(runId)}/tool-invocations`),
    getSessionUsage: (sessionId: string): Promise<ModelUsageSummaryDTO> =>
      client.get(`/usage/sessions/${encodeURIComponent(sessionId)}`),
    getYolo: (sessionId: string): Promise<SessionYoloDTO> => client.get(`/sessions/${encodeURIComponent(sessionId)}/yolo`),
    setYolo: (sessionId: string, enabled: boolean): Promise<SessionYoloDTO> =>
      client.put(`/sessions/${encodeURIComponent(sessionId)}/yolo`, { enabled }),
    decideToolInvocation: (invocationId: string, decision: 'allow' | 'deny'): Promise<ToolInvocationDTO> =>
      client.post(`/tool-invocations/${encodeURIComponent(invocationId)}/decision`, { decision }),
    abortRun: (sessionId: string): Promise<void> => client.post(`/sessions/${encodeURIComponent(sessionId)}/abort`),
    createRunEventStream: (runId: string, afterSequence: number): EventSource => {
      const query = new URLSearchParams({ afterSequence: String(afterSequence) })
      return new EventSource(`${apiBaseUrl}/runs/${encodeURIComponent(runId)}/events/stream?${query}`)
    },
    createRootActivityStream: (sessionId: string, afterEventId: number): EventSource => {
      const query = new URLSearchParams({ afterEventId: String(afterEventId) })
      return new EventSource(`${apiBaseUrl}/sessions/${encodeURIComponent(sessionId)}/activities/stream?${query}`)
    },
  }
}

export const harnessService = createHarnessService()
