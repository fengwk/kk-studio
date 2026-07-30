import { apiBaseUrl, apiClient, type HttpClient } from '@/shared/api/client'
import type {
  HarnessSessionDTO,
  HarnessSessionEntryDTO,
  HarnessThreadAgentSetDTO,
  HarnessThreadBootstrapDTO,
  HarnessThreadBootstrapResultDTO,
  HarnessThreadDTO,
  HarnessThreadHeadUpdateDTO,
  HarnessThreadInputDTO,
  HarnessThreadMessageCreateDTO,
  HarnessThreadModelSetDTO,
  HarnessRetryPolicyDTO,
  HarnessRealtimeStreamPolicyDTO,
  HarnessThreadStopDTO,
  HarnessThreadStopResultDTO,
  HarnessThreadSnapshotDTO,
  HarnessThreadYoloSetDTO,
} from '@/shared/api/contracts'

export function createHarnessService(client: HttpClient = apiClient) {
  return {
    listThreads: (): Promise<HarnessThreadDTO[]> => client.get('/threads'),
    getThreadSnapshot: (threadId: string): Promise<HarnessThreadSnapshotDTO> =>
      client.get(`/threads/${encodeURIComponent(threadId)}/snapshot`),
    /** Creates an UNBOUND Thread with no head; bind it via bootstrap or updateThreadHead. */
    createThread: (): Promise<HarnessThreadDTO> => client.post('/threads'),
    bootstrapThread: (
      threadId: string,
      data: HarnessThreadBootstrapDTO,
    ): Promise<HarnessThreadBootstrapResultDTO> =>
      client.post(`/threads/${encodeURIComponent(threadId)}/bootstrap`, data),
    updateThreadHead: (threadId: string, data: HarnessThreadHeadUpdateDTO): Promise<HarnessThreadDTO> =>
      client.put(`/threads/${encodeURIComponent(threadId)}/head`, data),
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
    getRealtimeStreamPolicy: (): Promise<HarnessRealtimeStreamPolicyDTO> =>
      client.get('/harness/realtime-stream-policy'),
    updateRealtimeStreamPolicy: (
      data: HarnessRealtimeStreamPolicyDTO,
    ): Promise<HarnessRealtimeStreamPolicyDTO> =>
      client.put('/harness/realtime-stream-policy', data),
    listSessions: (): Promise<HarnessSessionDTO[]> => client.get('/sessions'),
    getSession: (sessionId: string): Promise<HarnessSessionDTO> =>
      client.get(`/sessions/${encodeURIComponent(sessionId)}`),
    listSessionEntries: (sessionId: string): Promise<HarnessSessionEntryDTO[]> =>
      client.get(`/sessions/${encodeURIComponent(sessionId)}/entries`),
    /** Snapshot-first SSE: durable revisions get ids; lossy Redis deltas deliberately do not. */
    createThreadRealtimeStream: (threadId: string, afterRevision = '0'): EventSource => {
      const query = new URLSearchParams({ afterRevision })
      return new EventSource(`${apiBaseUrl}/threads/${encodeURIComponent(threadId)}/events/stream?${query}`)
    },
  }
}

export const harnessService = createHarnessService()
