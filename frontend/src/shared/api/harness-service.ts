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
 * Harness runtime 线程控制/查询面：
 * - GET snapshot（一致的 PostgreSQL 投影）
 * - POST commands（202 已接受，返回持久的 command 列表）
 * - PUT head / POST stop / POST approval（均基于 revision CAS）
 * - GET events/stream（基于 Redis overlay 的快照优先 SSE 流）
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
    /** 快照优先的 SSE：持久 revision 带 id；有损的 Redis 增量则刻意不带。 */
    createThreadRealtimeStream: (threadId: string, afterRevision = '0'): EventSource => {
      const query = new URLSearchParams({ afterRevision })
      return new EventSource(`${apiBaseUrl}/ai/runtime/threads/${encodeURIComponent(threadId)}/events/stream?${query}`)
    },
  }
}

export const harnessService = createHarnessService()
