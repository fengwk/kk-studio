import { apiBaseUrl, apiClient, type HttpClient } from '@/shared/api/client'
import type {
  HarnessSessionDTO,
  HarnessSessionCreateDTO,
  HarnessSessionEntryDTO,
  HarnessThreadAgentSetDTO,
  HarnessThreadCreateDTO,
  HarnessThreadDTO,
  HarnessThreadInputDTO,
  HarnessThreadMessageCreateDTO,
  HarnessThreadModelSetDTO,
  HarnessRetryPolicyDTO,
  HarnessThreadStopDTO,
  HarnessThreadStopResultDTO,
  HarnessThreadYoloSetDTO,
  ModelUsageSummaryDTO,
  RootActivityDTO,
  SubagentTaskDTO,
  ThreadEventDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts'

export function createHarnessService(client: HttpClient = apiClient) {
  return {
    getThread: (threadId: string): Promise<HarnessThreadDTO> =>
      client.get(`/threads/${encodeURIComponent(threadId)}`),
    listSessionThreads: (sessionId: string): Promise<HarnessThreadDTO[]> =>
      client.get(`/sessions/${encodeURIComponent(sessionId)}/threads`),
    submitThreadMessage: (threadId: string, data: HarnessThreadMessageCreateDTO): Promise<HarnessThreadInputDTO> =>
      client.post(`/threads/${encodeURIComponent(threadId)}/messages`, data),
    setThreadYolo: (threadId: string, data: HarnessThreadYoloSetDTO): Promise<HarnessThreadInputDTO> =>
      client.put(`/threads/${encodeURIComponent(threadId)}/yolo`, data),
    setThreadAgent: (threadId: string, data: HarnessThreadAgentSetDTO): Promise<HarnessThreadInputDTO> =>
      client.put(`/threads/${encodeURIComponent(threadId)}/agent`, data),
    setThreadModel: (threadId: string, data: HarnessThreadModelSetDTO): Promise<HarnessThreadInputDTO> =>
      client.put(`/threads/${encodeURIComponent(threadId)}/model`, data),
    stopThread: (threadId: string, data: HarnessThreadStopDTO): Promise<HarnessThreadStopResultDTO> =>
      client.post(`/threads/${encodeURIComponent(threadId)}/stop`, data),
    getRetryPolicy: (): Promise<HarnessRetryPolicyDTO> => client.get('/harness/retry-policy'),
    updateRetryPolicy: (data: HarnessRetryPolicyDTO): Promise<HarnessRetryPolicyDTO> =>
      client.put('/harness/retry-policy', data),
    listThreadEntries: (threadId: string): Promise<HarnessSessionEntryDTO[]> =>
      client.get(`/threads/${encodeURIComponent(threadId)}/entries`),
    listThreadInputs: (threadId: string): Promise<HarnessThreadInputDTO[]> =>
      client.get(`/threads/${encodeURIComponent(threadId)}/inputs`),
    listThreadEvents: (threadId: string, afterEventId = '0', limit?: number): Promise<ThreadEventDTO[]> =>
      client.get(`/threads/${encodeURIComponent(threadId)}/events`, {
        params: { afterEventId, ...(limit === undefined ? {} : { limit }) },
      }),
    listThreadToolInvocations: (threadId: string): Promise<ToolInvocationDTO[]> =>
      client.get(`/threads/${encodeURIComponent(threadId)}/tool-invocations`),
    getThreadUsage: (threadId: string): Promise<ModelUsageSummaryDTO> =>
      client.get(`/usage/threads/${encodeURIComponent(threadId)}`),
    listSessions: (): Promise<HarnessSessionDTO[]> => client.get('/sessions'),
    createSession: (data: HarnessSessionCreateDTO = {}): Promise<HarnessSessionDTO> =>
      client.post('/sessions', data),
    getSession: (sessionId: string): Promise<HarnessSessionDTO> =>
      client.get(`/sessions/${encodeURIComponent(sessionId)}`),
    listSessionEntries: (sessionId: string): Promise<HarnessSessionEntryDTO[]> =>
      client.get(`/sessions/${encodeURIComponent(sessionId)}/entries`),
    createSessionThread: (sessionId: string, data: HarnessThreadCreateDTO): Promise<HarnessThreadDTO> =>
      client.post(`/sessions/${encodeURIComponent(sessionId)}/threads`, data),
    listRootActivities: (sessionId: string, afterEventId = '0'): Promise<RootActivityDTO[]> =>
      client.get(`/sessions/${encodeURIComponent(sessionId)}/activities`, { params: { afterEventId } }),
    listSessionTasks: (sessionId: string): Promise<SubagentTaskDTO[]> =>
      client.get(`/sessions/${encodeURIComponent(sessionId)}/tasks`),
    decideToolInvocation: (invocationId: string, decision: 'allow' | 'deny'): Promise<ToolInvocationDTO> =>
      client.post(`/tool-invocations/${encodeURIComponent(invocationId)}/decision`, { decision }),
    /** @deprecated Use createThreadRealtimeStream; ThreadEvent history is no longer durable truth. */
    createThreadEventStream: (threadId: string, afterEventId = '0-0'): EventSource => {
      const query = new URLSearchParams({ afterEventId })
      return new EventSource(`${apiBaseUrl}/threads/${encodeURIComponent(threadId)}/events/stream?${query}`)
    },
    /** Redis-backed realtime SSE tail. Cursor is a Redis stream id (`ms-seq`). */
    createThreadRealtimeStream: (threadId: string, afterStreamId = '0-0'): EventSource => {
      const query = new URLSearchParams({ afterEventId: afterStreamId })
      return new EventSource(`${apiBaseUrl}/threads/${encodeURIComponent(threadId)}/events/stream?${query}`)
    },
  }
}

export const harnessService = createHarnessService()
