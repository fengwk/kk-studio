import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  AgentCommandBatchRequestDTO,
  AgentCommandBatchResponseDTO,
  HarnessCommandCreateDTO,
  HarnessSessionEntryDTO,
  HarnessSessionDTO,
  HarnessSessionRenameDTO,
  HarnessModelRequestDebugDTO,
  HarnessThreadDTO,
  HarnessThreadRenameDTO,
  HarnessThreadSnapshotDTO,
  HarnessThreadTreeNodeDTO,
  HarnessThreadStopDTO,
  HarnessThreadStopResultDTO,
  HarnessThreadYoloUpdateDTO,
  HarnessToolApprovalDTO,
  ManualCompactionRequestDTO,
  ManualCompactionResponseDTO,
  ProviderRequestPreviewDTO,
  RuntimeThreadSummaryDTO,
  ThreadCommandBatchRequestDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'

/**
 * Harness runtime 客户端调用统一收敛：
 * - POST /harness/command-batches（NEW_SESSION / NEW_THREAD / NEW_FORKED_SESSION 创建）
 * - POST /harness/threads/{id}/command-batches（既有 Thread 的通用写入口）
 * - GET /harness/sessions/{id}/threads
 * - GET /harness/sessions/{id}/entries
 * - PUT /harness/sessions/{id}/name
 * - GET /harness/threads/{id}（Thread snapshot 查询）
 * - GET /harness/threads/{id}/tree（真实 root 的父子 Agent 关系树）
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

    /**
     * 既有 Thread 的通用命令写入口：服务端从 path 解析 Session 与 owner 之外的授权，
     * 客户端只提交 CAS 游标与命令，不再伪造产品 owner。
     */
    acceptThreadCommandBatch: (
      threadId: string,
      data: ThreadCommandBatchRequestDTO,
    ): Promise<AgentCommandBatchResponseDTO> =>
      client.post(
        `/harness/threads/${encodeURIComponent(threadId)}/command-batches`,
        data,
      ),

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

    getThreadTree: (threadId: string): Promise<HarnessThreadTreeNodeDTO[]> =>
      client.get(`/harness/threads/${encodeURIComponent(threadId)}/tree`),

    renameThread: (threadId: string, data: HarnessThreadRenameDTO): Promise<HarnessThreadDTO> =>
      client.put(`/harness/threads/${encodeURIComponent(threadId)}/name`, data),

    compactThread: (
      threadId: string,
      data: ManualCompactionRequestDTO,
    ): Promise<ManualCompactionResponseDTO> =>
      client.post(`/harness/threads/${encodeURIComponent(threadId)}/compact`, data),

    getModelRequestDebug: (threadId: string): Promise<HarnessModelRequestDebugDTO> =>
      client.get(`/harness/threads/${encodeURIComponent(threadId)}/model-request-debug`),

    previewProviderRequest: (
      threadId: string,
      data: ThreadCommandBatchRequestDTO,
    ): Promise<ProviderRequestPreviewDTO> =>
      client.post(`/harness/threads/${encodeURIComponent(threadId)}/provider-request-preview`, data),

    previewBranchRequest: (
      sessionId: string,
      data: { startEntryId: string; commands: HarnessCommandCreateDTO[] },
    ): Promise<ProviderRequestPreviewDTO> =>
      client.post(`/harness/sessions/${encodeURIComponent(sessionId)}/provider-request-preview`, data),

    previewHistoricalRequest: (
      sessionId: string,
      entryId: string,
    ): Promise<ProviderRequestPreviewDTO> =>
      client.get(
        `/harness/sessions/${encodeURIComponent(sessionId)}/entries/${encodeURIComponent(entryId)}/provider-request-preview`,
      ),

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
