import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  AgentCommandBatchRequestDTO,
  AgentCommandBatchResponseDTO,
  HarnessSessionEntryDTO,
  HarnessSessionDTO,
  HarnessSessionRenameDTO,
  HarnessModelRequestDebugDTO,
  HarnessModelRequestDebugRequestDTO,
  HarnessThreadDTO,
  HarnessThreadRenameDTO,
  HarnessThreadSnapshotDTO,
  HarnessThreadTreeNodeDTO,
  HarnessThreadStopDTO,
  HarnessStreamCatchupRequestDTO,
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
 * - POST /command-batches（NEW_SESSION / NEW_THREAD / NEW_FORKED_SESSION 创建）
 * - POST /threads/{id}/command-batches（既有 Thread 的通用写入口）
 * - GET /sessions/{id}/threads
 * - GET /sessions/{id}/entries
 * - PUT /sessions/{id}/name
 * - GET /threads/{id}（Thread snapshot 查询）
 * - POST /threads/{id}/stream-catchup（RUNNING model attempt 的 best-effort 追帧）
 * - GET /threads/{id}/tree（真实 root 的父子 Agent 关系树）
 * - PUT /threads/{id}/name
 * - POST /threads/{id}/compact
 * - GET /threads/{id}/system-prompt
 * - PUT /threads/{id}/yolo
 * - POST /threads/{id}/stop
 * - PUT /threads/{threadId}/tool-invocations/{invocationId}/approval
 */
export function createHarnessService(client: HttpClient = apiClient) {
  return {
    acceptCommandBatch: (
      data: AgentCommandBatchRequestDTO,
    ): Promise<AgentCommandBatchResponseDTO> =>
      client.post('/command-batches', data),

    /**
     * 既有 Thread 的通用命令写入口：服务端从 path 解析 Session 与 owner 之外的授权，
     * 客户端只提交 CAS 游标与命令，不再伪造产品 owner。
     */
    acceptThreadCommandBatch: (
      threadId: string,
      data: ThreadCommandBatchRequestDTO,
    ): Promise<AgentCommandBatchResponseDTO> =>
      client.post(
        `/threads/${encodeURIComponent(threadId)}/command-batches`,
        data,
      ),

    listSessionThreads: (sessionId: string): Promise<RuntimeThreadSummaryDTO[]> =>
      client.get(`/sessions/${encodeURIComponent(sessionId)}/threads`),

    listSessionEntries: (sessionId: string): Promise<HarnessSessionEntryDTO[]> =>
      client.get(`/sessions/${encodeURIComponent(sessionId)}/entries`),

    renameSession: (
      sessionId: string,
      data: HarnessSessionRenameDTO,
    ): Promise<HarnessSessionDTO> =>
      client.put(`/sessions/${encodeURIComponent(sessionId)}/name`, data),

    getThreadSnapshot: (threadId: string): Promise<HarnessThreadSnapshotDTO> =>
      client.get(`/threads/${encodeURIComponent(threadId)}`),

    /** 202 只表示 best-effort 请求被接受；客户端不得据此推断 owner 在线或 checkpoint 已推进。 */
    requestStreamCatchup: (
      threadId: string,
      data: HarnessStreamCatchupRequestDTO,
    ): Promise<void> =>
      client.post(`/threads/${encodeURIComponent(threadId)}/stream-catchup`, data),

    getThreadTree: (threadId: string): Promise<HarnessThreadTreeNodeDTO[]> =>
      client.get(`/threads/${encodeURIComponent(threadId)}/tree`),

    renameThread: (threadId: string, data: HarnessThreadRenameDTO): Promise<HarnessThreadDTO> =>
      client.put(`/threads/${encodeURIComponent(threadId)}/name`, data),

    compactThread: (
      threadId: string,
      data: ManualCompactionRequestDTO,
    ): Promise<ManualCompactionResponseDTO> =>
      client.post(`/threads/${encodeURIComponent(threadId)}/compact`, data),

    getModelRequestDebug: (
      threadId: string,
      data: HarnessModelRequestDebugRequestDTO,
    ): Promise<HarnessModelRequestDebugDTO> =>
      client.post(`/threads/${encodeURIComponent(threadId)}/model-request-debug`, data),

    previewHistoricalRequest: (
      sessionId: string,
      entryId: string,
    ): Promise<ProviderRequestPreviewDTO> =>
      client.get(
        `/sessions/${encodeURIComponent(sessionId)}/entries/${encodeURIComponent(entryId)}/provider-request-preview`,
      ),

    setThreadYolo: (
      threadId: string,
      data: HarnessThreadYoloUpdateDTO,
    ): Promise<HarnessThreadDTO> =>
      client.put(`/threads/${encodeURIComponent(threadId)}/yolo`, data),

    stopThread: (
      threadId: string,
      data: HarnessThreadStopDTO,
    ): Promise<HarnessThreadStopResultDTO> =>
      client.post(`/threads/${encodeURIComponent(threadId)}/stop`, data),

    decideApproval: (
      threadId: string,
      toolInvocationId: string,
      data: HarnessToolApprovalDTO,
    ): Promise<ToolInvocationDTO> =>
      client.put(
        `/threads/${encodeURIComponent(threadId)}/tool-invocations/${encodeURIComponent(toolInvocationId)}/approval`,
        data,
      ),
  }
}

export const harnessService = createHarnessService()
