import type { InteractionOwnerDTO } from '@/shared/api/generated/contracts'

/**
 * 待处理 Interaction 的条目类型。
 *
 * - `INPUT` / `APPROVAL`：可操作的人工等待（问卷等待 / 审批等待），携带原始调用主键；
 * - `ENVIRONMENT_WAIT`：按 `(真实执行根, 所需环境)` 聚合的只读环境等待，没有任何允许/拒绝/作答操作。
 */
export type InteractionType = 'INPUT' | 'APPROVAL' | 'ENVIRONMENT_WAIT'

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
  createTime: number
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

/** 一页待处理 Interaction：条目为上方的判别联合。 */
export interface InteractionPageDTO {
  items: InteractionDTO[]
  nextCursor: string | null
  total: number
  freshnessAt: number | null
}
