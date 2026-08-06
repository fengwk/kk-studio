import { apiBaseUrl, apiClient, type HttpClient } from '@/shared/api/client'
import type {
  HarnessThreadCommandBatchDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
  HarnessThreadHeadUpdateDTO,
  HarnessThreadSnapshotDTO,
  HarnessThreadStopDTO,
  HarnessThreadStopResultDTO,
  HarnessToolApprovalDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'

/**
 * Harness runtime thread control/query plane:
 * - GET snapshot (coherent PostgreSQL projection)
 * - POST commands (202 accepted, returns the durable command list)
 * - PUT head / POST stop / POST approval (revision CAS)
 * - GET events/stream (snapshot-first SSE tail over the Redis overlay)
 */
export function createHarnessService(client: HttpClient = apiClient) {
  return {
    getThreadSnapshot: (threadId: string): Promise<HarnessThreadSnapshotDTO> =>
      client.get(`/ai/runtime/threads/${encodeURIComponent(threadId)}/snapshot`),
    enqueueCommands: (
      threadId: string,
      data: HarnessThreadCommandBatchDTO,
    ): Promise<HarnessThreadCommandDTO[]> =>
      client.post(`/ai/runtime/threads/${encodeURIComponent(threadId)}/commands`, data),
    updateThreadHead: (
      threadId: string,
      data: HarnessThreadHeadUpdateDTO,
    ): Promise<HarnessThreadDTO> =>
      client.put(`/ai/runtime/threads/${encodeURIComponent(threadId)}/head`, data),
    stopThread: (
      threadId: string,
      data: HarnessThreadStopDTO,
    ): Promise<HarnessThreadStopResultDTO> =>
      client.post(`/ai/runtime/threads/${encodeURIComponent(threadId)}/stop`, data),
    decideApproval: (
      threadId: string,
      toolInvocationId: string,
      data: HarnessToolApprovalDTO,
    ): Promise<ToolInvocationDTO> =>
      client.post(
        `/ai/runtime/threads/${encodeURIComponent(threadId)}/tool-invocations/${encodeURIComponent(toolInvocationId)}/approval`,
        data,
      ),
    /** Snapshot-first SSE: durable revisions get ids; lossy Redis deltas deliberately do not. */
    createThreadRealtimeStream: (threadId: string, afterRevision = '0'): EventSource => {
      const query = new URLSearchParams({ afterRevision })
      return new EventSource(`${apiBaseUrl}/ai/runtime/threads/${encodeURIComponent(threadId)}/events/stream?${query}`)
    },
  }
}

export const harnessService = createHarnessService()
