import type { BackendDateTime } from '@/shared/api/contracts/base'

/**
 * 待处理 Interaction 的产品来源：Chat 或 Issue+Agent。
 */
export interface InteractionOwnerDTO {
  type: 'CHAT' | 'ISSUE_AGENT' | string
  chatId: string | null
  issueId: string | null
  agentName: string | null
}

/**
 * 一个待处理 Interaction（问卷等待或审批等待）的稳定投影。
 */
export interface InteractionDTO {
  interactionId: string
  status: 'WAITING_INPUT' | 'WAITING_APPROVAL' | string
  threadId: string
  sessionId: string
  owner: InteractionOwnerDTO
  toolCallId: string
  toolName: string
  argumentsJson: string
  approvalJson: string | null
  createTime: BackendDateTime
}

/**
 * 待处理 Interaction 的一页稳定分页结果。
 */
export interface InteractionPageDTO {
  items: InteractionDTO[]
  nextCursor: string | null
}

/**
 * 一次人工输入（ask_user 问卷）提交请求体。
 * 客户端由服务端认证上下文提取 principal，严禁携带 actor 字段。
 */
export interface HarnessToolInputDTO {
  threadId: string
  submissionId: string
  declined?: boolean
  answers?: string[][]
}

/**
 * 人工输入提交被接受（或精确重放）后的权威回执视图。
 */
export interface HarnessToolInputResultDTO {
  threadId: string
  interactionId: string
  submissionId: string
  actor: string
  acceptedAt: string
  materialized: boolean
}
