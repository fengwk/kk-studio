import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  AgentCommandBatchRequestDTO,
  AgentCommandBatchResponseDTO,
  HarnessSessionEntryDTO,
  HarnessSessionDTO,
  HarnessSessionRenameDTO,
  HarnessModelRequestDebugDTO,
  HarnessThreadDTO,
  HarnessThreadRenameDTO,
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
 * - PUT /harness/sessions/{id}/name
 * - GET /harness/threads/{id}（Thread snapshot 查询）
 * - PUT /harness/threads/{id}/name
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

    renameSession: (
      sessionId: string,
      data: HarnessSessionRenameDTO,
    ): Promise<HarnessSessionDTO> =>
      client.put(`/harness/sessions/${encodeURIComponent(sessionId)}/name`, data),

    getThreadSnapshot: (threadId: string): Promise<HarnessThreadSnapshotDTO> =>
      client.get(`/harness/threads/${encodeURIComponent(threadId)}`),

    renameThread: (threadId: string, data: HarnessThreadRenameDTO): Promise<HarnessThreadDTO> =>
      client.put(`/harness/threads/${encodeURIComponent(threadId)}/name`, data),

    compactThread: (
      threadId: string,
      data: ManualCompactionRequestDTO,
    ): Promise<ManualCompactionResponseDTO> =>
      client.post(`/harness/threads/${encodeURIComponent(threadId)}/compact`, data),

    getModelRequestDebug: (threadId: string): Promise<HarnessModelRequestDebugDTO> =>
      client.get(`/harness/threads/${encodeURIComponent(threadId)}/model-request-debug`),

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
