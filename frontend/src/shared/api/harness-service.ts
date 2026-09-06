import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  AgentCommandBatchRequestDTO,
  AgentCommandBatchResponseDTO,
  HarnessSessionEntryDTO,
  HarnessSystemPromptPreviewDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
  HarnessThreadStopDTO,
  HarnessThreadStopResultDTO,
  HarnessThreadYoloUpdateDTO,
  HarnessToolApprovalDTO,
  ManualCompactionRequestDTO,
  ManualCompactionResponseDTO,
  RuntimeThreadSummaryDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'

/**
 * Harness runtime 客户端调用统一收敛：
 * - POST /harness/command-batches
 * - GET /harness/sessions/{id}/threads
 * - GET /harness/sessions/{id}/entries
 * - GET /harness/threads/{id}（Thread snapshot 查询）
 * - POST /harness/threads/{id}/compact
 * - GET /harness/threads/{id}/system-prompt
 * - PUT /harness/threads/{id}/yolo
 * - POST /harness/threads/{id}/stop
 * - PUT /harness/threads/{threadId}/tool-invocations/{invocationId}/approval
 */
export function createHarnessService(client: HttpClient = apiClient) {
  return {
    acceptCommandBatch: (
      data: AgentCommandBatchRequestDTO,
    ): Promise<AgentCommandBatchResponseDTO> =>
      client.post('/harness/command-batches', data),

    listSessionThreads: (sessionId: string): Promise<RuntimeThreadSummaryDTO[]> =>
      client.get(`/harness/sessions/${encodeURIComponent(sessionId)}/threads`),

    listSessionEntries: (sessionId: string): Promise<HarnessSessionEntryDTO[]> =>
      client.get(`/harness/sessions/${encodeURIComponent(sessionId)}/entries`),

    getThreadSnapshot: (threadId: string): Promise<HarnessThreadSnapshotDTO> =>
      client.get(`/harness/threads/${encodeURIComponent(threadId)}`),

    compactThread: (
      threadId: string,
      data: ManualCompactionRequestDTO,
    ): Promise<ManualCompactionResponseDTO> =>
      client.post(`/harness/threads/${encodeURIComponent(threadId)}/compact`, data),

    getSystemPromptPreview: (threadId: string): Promise<HarnessSystemPromptPreviewDTO> =>
      client.get(`/harness/threads/${encodeURIComponent(threadId)}/system-prompt`),

    setThreadYolo: (
      threadId: string,
      data: HarnessThreadYoloUpdateDTO,
    ): Promise<HarnessThreadDTO> =>
      client.put(`/harness/threads/${encodeURIComponent(threadId)}/yolo`, data),

    stopThread: (
      threadId: string,
      data: HarnessThreadStopDTO,
    ): Promise<HarnessThreadStopResultDTO> =>
      client.post(`/harness/threads/${encodeURIComponent(threadId)}/stop`, data),

    decideApproval: (
      threadId: string,
      toolInvocationId: string,
      data: HarnessToolApprovalDTO,
    ): Promise<ToolInvocationDTO> =>
      client.put(
        `/harness/threads/${encodeURIComponent(threadId)}/tool-invocations/${encodeURIComponent(toolInvocationId)}/approval`,
        data,
      ),
  }
}

export const harnessService = createHarnessService()
