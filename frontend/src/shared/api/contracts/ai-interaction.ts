import type { InstantTimestamp } from '@/shared/api/contracts/base'

/**
 * 待处理 Interaction 的条目类型。
 *
 * - `INPUT` / `APPROVAL`：可操作的人工等待（问卷等待 / 审批等待），携带原始调用主键；
 * - `ENVIRONMENT_WAIT`：按 `(真实执行根, 所需环境)` 聚合的只读环境等待，没有任何允许/拒绝/作答操作。
 */
export type InteractionType = 'INPUT' | 'APPROVAL' | 'ENVIRONMENT_WAIT'

/**
 * 待处理 Interaction 的产品来源：Chat 或 Issue+Agent。
 */
export interface InteractionOwnerDTO {
  type: 'CHAT' | 'ISSUE_AGENT'
  chatId: string | null
  chatTitle: string | null
  issueId: string | null
  issueTitle: string | null
  agentName: string | null
  rootThreadName: string | null
}

/**
 * 一个待处理 Interaction 的稳定投影，由 `type` 区分人工等待与环境等待。
 *
 * 人工等待条目携带原始调用坐标与冻结的 ToolCall；环境等待条目只保留聚合身份
 * （`rootThreadId` + `environmentId` + `waitingCount`），wire 上把 `interactionId` /
 * `status` / `threadId` / `sessionId` / `toolCallId` / `toolName` / `argumentsJson` /
 * `approvalJson` 显式编码为 null，因此消费方必须先按 `type` 判别再取可操作字段。
 */
interface InteractionSource {
  rootThreadId: string
  owner: InteractionOwnerDTO
  createTime: InstantTimestamp
}

interface ManualInteraction extends InteractionSource {
  interactionId: string
  threadId: string
  sessionId: string
  toolCallId: string
  toolName: string
  argumentsJson: string
  environmentId: null
  environmentName: null
  waitingCount: null
}

export interface InputInteractionDTO extends ManualInteraction {
  type: 'INPUT'
  status: 'WAITING_INPUT'
  approvalJson: null
}

export interface ApprovalInteractionDTO extends ManualInteraction {
  type: 'APPROVAL'
  status: 'WAITING_APPROVAL'
  approvalJson: string
}

/** 只有根+环境聚合身份：没有可提交/审批的调用主键。 */
export interface EnvironmentWaitInteractionDTO extends InteractionSource {
  type: 'ENVIRONMENT_WAIT'
  interactionId: null
  status: null
  threadId: null
  sessionId: null
  toolCallId: null
  toolName: null
  argumentsJson: null
  approvalJson: null
  environmentId: string
  environmentName: string
  waitingCount: number
}

export type InteractionDTO =
  | InputInteractionDTO
  | ApprovalInteractionDTO
  | EnvironmentWaitInteractionDTO

/** 可操作的人工等待：有原始调用主键与来源 Thread，可直接回写提交/审批。 */
export type ManualInteractionDTO = InputInteractionDTO | ApprovalInteractionDTO

/**
 * 待处理 Interaction 的一页稳定分页结果。
 *
 * `total` 是同一过滤条件下的真实可见待处理总数（与 `nextCursor` 指向的分页位置无关），
 * 供全局待处理角标直接读取，不需要客户端自行累加或维护台账。
 */
export interface InteractionPageDTO {
  items: InteractionDTO[]
  nextCursor: string | null
  total: number
  /** 时间驱动的最早可能变更时刻，供单次对账；无此类时刻时为 null。 */
  freshnessAt: InstantTimestamp
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
