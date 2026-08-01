import { apiBaseUrl, apiClient, type HttpClient } from '@/shared/api/client'
import type {
  HarnessSessionDTO,
  HarnessSessionEntryDTO,
  HarnessThreadAgentSetDTO,
  HarnessThreadBootstrapDTO,
  HarnessThreadBootstrapResultDTO,
  HarnessThreadDTO,
  HarnessThreadEnvironmentSetDTO,
  HarnessThreadPage,
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
  ThreadListSort,
} from '@/shared/api/contracts/ai-runtime'

export interface ThreadListOptions {
  sort?: ThreadListSort
  cursor?: string
  limit?: number
}

export function createHarnessService(client: HttpClient = apiClient) {
  return {
    listThreads: (options: ThreadListOptions = {}): Promise<HarnessThreadPage> => {
      const params: Record<string, unknown> = {
        sort: options.sort ?? 'recent',
        limit: options.limit ?? 20,
      }
      if (options.cursor) {
        params.cursor = options.cursor
      }
      return client.get('/ai/runtime/threads', { params })
    },
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
    setThreadEnvironment: (
      threadId: string,
      data: HarnessThreadEnvironmentSetDTO,
    ): Promise<HarnessThreadInputDTO> =>
      client.put(`/ai/runtime/threads/${encodeURIComponent(threadId)}/environment`, data),
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
