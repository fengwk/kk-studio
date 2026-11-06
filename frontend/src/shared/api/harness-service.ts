import { request } from '@/shared/api/client'
import type {
  HarnessAcceptedCommandsDTO,
  HarnessCommandBatchDTO,
  HarnessExecutionAbandonDTO,
  HarnessManualCompactionDTO,
  HarnessModelRequestDebugDTO,
  HarnessModelRequestDebugRequestDTO,
  HarnessNameUpdateDTO,
  HarnessProviderRequestPreviewDTO,
  HarnessSessionDTO,
  HarnessSessionEntryPageDTO,
  HarnessThreadCommandBatchDTO,
  HarnessThreadCompactDTO,
  HarnessThreadCompactResultDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
  HarnessThreadStopDTO,
  HarnessThreadStopResultDTO,
  HarnessThreadSummaryDTO,
  HarnessThreadTreeNodeDTO,
  HarnessThreadYoloUpdateDTO,
  HarnessToolApprovalDTO,
  ToolInvocationDTO,
} from '@/shared/api/generated/contracts'

interface GetThreadSnapshotOptions {
  afterEntryId?: string | null
  signal?: AbortSignal
}

interface ListSessionEntriesOptions {
  cursor?: string | null
  limit?: number
  signal?: AbortSignal
}

interface GetCompactionAvailabilityOptions {
  signal?: AbortSignal
}

/** Harness runtime 的 UI 调用入口；路由、参数与响应类型全部来自生成契约。 */
export const harnessService = {
  acceptCommandBatch: (data: HarnessCommandBatchDTO): Promise<HarnessAcceptedCommandsDTO> =>
    request('POST /api/harness/command-batches', { body: data }),

  /**
   * 既有 Thread 的通用命令写入口：服务端从 path 解析 Session 与 owner 之外的授权，
   * 客户端只提交 CAS 游标与命令，不包含产品 owner。
   */
  acceptThreadCommandBatch: (
    threadId: string,
    data: HarnessThreadCommandBatchDTO,
  ): Promise<HarnessAcceptedCommandsDTO> =>
    request('POST /api/harness/threads/{threadId}/command-batches', {
      path: { threadId },
      body: data,
    }),

  listSessionThreads: (sessionId: string): Promise<HarnessThreadSummaryDTO[]> =>
    request('GET /api/harness/sessions/{sessionId}/threads', {
      path: { sessionId },
    }),

  /** 一页 Session Entry；`nextCursor` 原样回传即可继续翻页，null 表示末页。 */
  listSessionEntries: (
    sessionId: string,
    options?: ListSessionEntriesOptions,
  ): Promise<HarnessSessionEntryPageDTO> =>
    request(
      'GET /api/harness/sessions/{sessionId}/entries',
      {
        path: { sessionId },
        query: {
          ...(options?.cursor != null ? { cursor: options.cursor } : {}),
          ...(options?.limit != null ? { limit: options.limit } : {}),
        },
      },
      { signal: options?.signal },
    ),

  renameSession: (sessionId: string, data: HarnessNameUpdateDTO): Promise<HarnessSessionDTO> =>
    request('PUT /api/harness/sessions/{sessionId}/name', {
      path: { sessionId },
      body: data,
    }),

  getThreadSnapshot: (
    threadId: string,
    options?: GetThreadSnapshotOptions,
  ): Promise<HarnessThreadSnapshotDTO> =>
    request(
      'GET /api/harness/threads/{threadId}',
      {
        path: { threadId },
        query: options?.afterEntryId ? { afterEntryId: options.afterEntryId } : {},
      },
      { signal: options?.signal },
    ),

  getCompactionAvailability: (
    threadId: string,
    options?: GetCompactionAvailabilityOptions,
  ): Promise<HarnessManualCompactionDTO> =>
    request(
      'GET /api/harness/threads/{threadId}/compaction-availability',
      { path: { threadId } },
      { signal: options?.signal },
    ),

  getThreadTree: (threadId: string): Promise<HarnessThreadTreeNodeDTO[]> =>
    request('GET /api/harness/threads/{threadId}/tree', { path: { threadId } }),

  renameThread: (threadId: string, data: HarnessNameUpdateDTO): Promise<HarnessThreadDTO> =>
    request('PUT /api/harness/threads/{threadId}/name', {
      path: { threadId },
      body: data,
    }),

  compactThread: (
    threadId: string,
    data: HarnessThreadCompactDTO,
  ): Promise<HarnessThreadCompactResultDTO> =>
    request('POST /api/harness/threads/{threadId}/compact', {
      path: { threadId },
      body: data,
    }),

  getModelRequestDebug: (
    threadId: string,
    data: HarnessModelRequestDebugRequestDTO,
  ): Promise<HarnessModelRequestDebugDTO> =>
    request('POST /api/harness/builtin/threads/{threadId}/model-request-debug', {
      path: { threadId },
      body: data,
    }),

  previewHistoricalRequest: (
    sessionId: string,
    entryId: string,
  ): Promise<HarnessProviderRequestPreviewDTO> =>
    request(
      'GET /api/harness/builtin/sessions/{sessionId}/entries/{entryId}/provider-request',
      { path: { sessionId, entryId } },
    ),

  setThreadYolo: (
    threadId: string,
    data: HarnessThreadYoloUpdateDTO,
  ): Promise<HarnessThreadDTO> =>
    request('PUT /api/harness/threads/{threadId}/yolo', {
      path: { threadId },
      body: data,
    }),

  stopThread: (
    threadId: string,
    data: HarnessThreadStopDTO,
  ): Promise<HarnessThreadStopResultDTO> =>
    request('POST /api/harness/threads/{threadId}/stop', {
      path: { threadId },
      body: data,
    }),

  /**
   * 显式放弃等待一个未确认退出事实。服务端按 `(threadId, executionId, expectedVersion)`
   * CAS；重复放弃或已真实确认的执行安全 replay，返回当前完整快照。
   */
  abandonExecution: (
    threadId: string,
    executionId: string,
    data: HarnessExecutionAbandonDTO,
  ): Promise<HarnessThreadSnapshotDTO> =>
    request('POST /api/harness/threads/{threadId}/executions/{executionId}/abandon', {
      path: { threadId, executionId },
      body: data,
    }),

  decideApproval: (
    threadId: string,
    toolInvocationId: string,
    data: HarnessToolApprovalDTO,
  ): Promise<ToolInvocationDTO> =>
    request('PUT /api/harness/threads/{threadId}/tool-invocations/{toolInvocationId}/approval', {
      path: { threadId, toolInvocationId },
      body: data,
    }),
}
