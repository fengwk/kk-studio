import { request } from '@/shared/api/client'
import type {
  HarnessAcceptedCommandsDTO,
  HarnessExecutionAbandonDTO,
  HarnessModelRequestDebugDTO,
  HarnessModelRequestDebugRequestDTO,
  HarnessProviderRequestPreviewDTO,
  HarnessThreadCommandBatchDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
  HarnessThreadStopDTO,
  HarnessThreadStopResultDTO,
  HarnessThreadTreeNodeDTO,
  HarnessThreadYoloUpdateDTO,
  HarnessToolApprovalDTO,
  ToolInvocationDTO,
} from '@/shared/api/generated/contracts'

interface GetThreadSnapshotOptions {
  afterEntryId?: string | null
  signal?: AbortSignal
}

interface GetThreadTreeOptions {
  signal?: AbortSignal
}

/** Harness runtime 的 UI 调用入口；路由、参数与响应类型全部来自生成契约。 */
export const harnessService = {
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

  getThreadTree: (
    threadId: string,
    options?: GetThreadTreeOptions,
  ): Promise<HarnessThreadTreeNodeDTO[]> =>
    request(
      'GET /api/harness/threads/{threadId}/tree',
      { path: { threadId } },
      { signal: options?.signal },
    ),

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
