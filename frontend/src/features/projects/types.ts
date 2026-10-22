/**
 * Project 领域前端实体与传输对象定义。
 *
 * <p>基于 dev/spec §4 规范，彻底移除 dependencies、executor-reviewer 与 maxReviewRejections；
 * 支持动态工作流配置、自然状态 token、稳定 AgentThread 绑定、阶段预算、Run 执行事实与区间报告、
 * 明确区分的评论与指令活动事实流、证据上传与预览、Stop 终止、UNKNOWN 门禁人工核查以及项目/Issue 彻底删除。
 */

export type IssuePauseReason = 'USER' | 'ERROR' | 'UNKNOWN'

export type IssueActivityKind =
  | 'COMMENT'
  | 'INSTRUCTION'
  | 'RUN'
  | 'SPEC_CHANGE'
  | 'STATE_CHANGE'
  | 'CONTROL'
  | string

export type IssueActivityActorType = 'HUMAN' | 'AGENT' | 'SYSTEM' | string

export type IssueRunStatus =
  | 'RUNNING'
  | 'WAITING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'UNKNOWN'
  | string

export interface ProjectsChangedEventPayload {
  projectId?: string
}
