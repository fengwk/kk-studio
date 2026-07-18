import { apiBaseUrl, apiClient, type HttpClient } from '@/shared/api/client'
import type {
  HarnessSessionDTO,
  HarnessSessionEntryDTO,
  HarnessThreadAgentSetDTO,
  HarnessThreadCreateDTO,
  HarnessThreadDTO,
  HarnessThreadInputDTO,
  HarnessThreadMessageCreateDTO,
  HarnessThreadYoloSetDTO,
  ModelUsageSummaryDTO,
  RootActivityDTO,
  SubagentTaskDTO,
  ThreadEventDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts'

export function createHarnessService(client: HttpClient = apiClient) {
  return {
    listThreads: (): Promise<HarnessThreadDTO[]> => client.get('/threads'),
    createThread: (data: HarnessThreadCreateDTO): Promise<HarnessThreadDTO> => client.post('/threads', data),
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
    getSession: (sessionId: string): Promise<HarnessSessionDTO> =>
      client.get(`/sessions/${encodeURIComponent(sessionId)}`),
    listSessionEntries: (sessionId: string): Promise<HarnessSessionEntryDTO[]> =>
      client.get(`/sessions/${encodeURIComponent(sessionId)}/entries`),
    listRootActivities: (sessionId: string, afterEventId = '0'): Promise<RootActivityDTO[]> =>
      client.get(`/sessions/${encodeURIComponent(sessionId)}/activities`, { params: { afterEventId } }),
    listSessionTasks: (sessionId: string): Promise<SubagentTaskDTO[]> =>
      client.get(`/sessions/${encodeURIComponent(sessionId)}/tasks`),
    decideToolInvocation: (invocationId: string, decision: 'allow' | 'deny'): Promise<ToolInvocationDTO> =>
      client.post(`/tool-invocations/${encodeURIComponent(invocationId)}/decision`, { decision }),
    createThreadEventStream: (threadId: string, afterEventId = '0'): EventSource => {
      const query = new URLSearchParams({ afterEventId })
      return new EventSource(`${apiBaseUrl}/threads/${encodeURIComponent(threadId)}/events/stream?${query}`)
    },
  }
}

export const harnessService = createHarnessService()
