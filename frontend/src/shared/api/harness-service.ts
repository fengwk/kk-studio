import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  HarnessSessionEntryDTO,
  HarnessSystemPromptPreviewDTO,
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
 * - GET entries（Thread 所属 Session 的完整不可变 Entry Tree）
 * - GET system-prompt（按当前 branch 最新状态现算的系统提示词预览）
 * - POST commands（202 已接受，返回持久的 command 列表）
 * - PUT head / POST stop / POST approval（均基于 revision CAS）
 * - 实时事件经应用级 WebSocket（/api/events/v1，见 shared/app-events）
 */
export function createHarnessService(client: HttpClient = apiClient) {
  return {
    getThreadSnapshot: (threadId: string): Promise<HarnessThreadSnapshotDTO> =>
      client.get(`/ai/runtime/threads/${encodeURIComponent(threadId)}/snapshot`),
    listThreadEntries: (threadId: string): Promise<HarnessSessionEntryDTO[]> =>
      client.get(`/ai/runtime/threads/${encodeURIComponent(threadId)}/entries`),
    getSystemPromptPreview: (threadId: string): Promise<HarnessSystemPromptPreviewDTO> =>
      client.get(`/ai/runtime/threads/${encodeURIComponent(threadId)}/system-prompt`),
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
  }
}

export const harnessService = createHarnessService()
