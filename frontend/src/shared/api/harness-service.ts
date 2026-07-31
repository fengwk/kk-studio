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
} from '@/shared/api/contracts/ai-runtime'

export function createHarnessService(client: HttpClient = apiClient) {
  return {
    listThreads: (): Promise<HarnessThreadDTO[]> => client.get('/ai/runtime/threads'),
    getThreadSnapshot: (threadId: string): Promise<HarnessThreadSnapshotDTO> =>
      client.get(`/ai/runtime/threads/${encodeURIComponent(threadId)}/snapshot`),
    /** Creates an UNBOUND Thread with no head; bind it via bootstrap or updateThreadHead. */
    createThread: (): Promise<HarnessThreadDTO> => client.post('/ai/runtime/threads'),
    bootstrapThread: (
      threadId: string,
      data: HarnessThreadBootstrapDTO,
    ): Promise<HarnessThreadBootstrapResultDTO> =>
      client.post(`/ai/runtime/threads/${encodeURIComponent(threadId)}/bootstrap`, data),
    updateThreadHead: (threadId: string, data: HarnessThreadHeadUpdateDTO): Promise<HarnessThreadDTO> =>
      client.put(`/ai/runtime/threads/${encodeURIComponent(threadId)}/head`, data),
    submitThreadMessage: (threadId: string, data: HarnessThreadMessageCreateDTO): Promise<HarnessThreadInputDTO> =>
      client.post(`/ai/runtime/threads/${encodeURIComponent(threadId)}/messages`, data),
    setThreadYolo: (threadId: string, data: HarnessThreadYoloSetDTO): Promise<HarnessThreadInputDTO> =>
      client.put(`/ai/runtime/threads/${encodeURIComponent(threadId)}/yolo`, data),
    setThreadAgent: (threadId: string, data: HarnessThreadAgentSetDTO): Promise<HarnessThreadInputDTO> =>
      client.put(`/ai/runtime/threads/${encodeURIComponent(threadId)}/agent`, data),
    setThreadModel: (threadId: string, data: HarnessThreadModelSetDTO): Promise<HarnessThreadInputDTO> =>
      client.put(`/ai/runtime/threads/${encodeURIComponent(threadId)}/model`, data),
    stopThread: (threadId: string, data: HarnessThreadStopDTO): Promise<HarnessThreadStopResultDTO> =>
      client.post(`/ai/runtime/threads/${encodeURIComponent(threadId)}/stop`, data),
    getRetryPolicy: (): Promise<HarnessRetryPolicyDTO> => client.get('/ai/runtime/settings/retry-policy'),
    updateRetryPolicy: (data: HarnessRetryPolicyDTO): Promise<HarnessRetryPolicyDTO> =>
      client.put('/ai/runtime/settings/retry-policy', data),
    getRealtimeStreamPolicy: (): Promise<HarnessRealtimeStreamPolicyDTO> =>
      client.get('/ai/runtime/settings/realtime-stream-policy'),
    updateRealtimeStreamPolicy: (
      data: HarnessRealtimeStreamPolicyDTO,
    ): Promise<HarnessRealtimeStreamPolicyDTO> =>
      client.put('/ai/runtime/settings/realtime-stream-policy', data),
    listSessions: (): Promise<HarnessSessionDTO[]> => client.get('/ai/runtime/sessions'),
    listSessionEntries: (sessionId: string): Promise<HarnessSessionEntryDTO[]> =>
      client.get(`/ai/runtime/sessions/${encodeURIComponent(sessionId)}/entries`),
    /** Snapshot-first SSE: durable revisions get ids; lossy Redis deltas deliberately do not. */
    createThreadRealtimeStream: (threadId: string, afterRevision = '0'): EventSource => {
      const query = new URLSearchParams({ afterRevision })
      return new EventSource(`${apiBaseUrl}/ai/runtime/threads/${encodeURIComponent(threadId)}/events/stream?${query}`)
    },
  }
}

export const harnessService = createHarnessService()
