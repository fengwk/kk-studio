import { request } from '@/shared/api/client'
import type {
  HarnessToolInputDTO,
  HarnessToolInputResultDTO,
  InteractionPageDTO,
} from '@/shared/api/generated/contracts'

/** 查询一页待处理 Interaction（问卷等待、审批等待与环境等待合并，按 createdAt 升序）。 */
export function listInteractions(
  rootThreadId: string | null,
  cursor: string | null,
  limit: number,
): Promise<InteractionPageDTO> {
  return request('GET /api/harness/interactions', {
    query: {
      rootThreadId: rootThreadId ?? undefined,
      cursor: cursor ?? undefined,
      limit,
    },
  })
}

/**
 * 提交一次问卷回答（或明确拒答）。
 * 目标 Thread 与 Tool invocation 都在路径中；请求体严禁携带 actor 字段，服务端从认证主体解析。
 */
export function submitToolInput(
  threadId: string,
  toolInvocationId: string,
  body: HarnessToolInputDTO,
): Promise<HarnessToolInputResultDTO> {
  return request('POST /api/harness/threads/{threadId}/tool-invocations/{toolInvocationId}/input', {
    path: { threadId, toolInvocationId },
    body,
  })
}

/** 交互面板与全局待处理页共用的调用入口。 */
export const interactionService = { listInteractions, submitToolInput }
