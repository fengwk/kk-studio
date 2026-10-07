/**
 * Project 领域前端实体与传输对象定义。
 *
 * <p>基于 dev/spec §4 规范，彻底移除 dependencies、executor-reviewer 与 maxReviewRejections；
 * 支持动态工作流配置、自然状态 token、稳定 AgentThread 绑定、阶段预算、Run 执行事实与区间报告、
 * 明确区分的评论与指令活动事实流、证据上传与预览、Stop 终止、UNKNOWN 门禁人工核查以及项目/Issue 彻底删除。
 */

export interface ProjectWorkflowStateDTO {
  state: string
  name: string
  agent?: string | null
  environment?: string | null
  instructions?: string | null
  maxRuns?: string | null
  enabled?: boolean | null
  next?: string[] | null
}

export interface ProjectWorkflowDTO {
  states: ProjectWorkflowStateDTO[]
}

export interface ProjectDTO {
  id: string
  title: string
  description: string
  workflow: ProjectWorkflowDTO
  yoloEnabled: boolean
  nextIssueNumber: string
  version: string
  archivedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateProjectRequest {
  title: string
  description?: string | null
  yoloEnabled?: boolean | null
}

export interface UpdateProjectRequest {
  expectedVersion: string
  title?: string | null
  description?: string | null
}

export interface UpdateProjectWorkflowRequest {
  expectedVersion: string
  workflow: ProjectWorkflowDTO
}

export interface UpdateProjectYoloRequest {
  expectedVersion: string
  yoloEnabled: boolean
}

export interface ProjectArchiveRequest {
  expectedVersion: string
}

export interface ProjectUnarchiveRequest {
  expectedVersion: string
}

export type IssuePauseReason = 'USER' | 'ERROR' | 'UNKNOWN'

export interface IssueDTO {
  id: string
  projectId: string
  number: string
  title: string
  description: string
  state: string
  blockedFromState: string | null
  blockReason: string | null
  pauseReason: IssuePauseReason | null
  pauseDetail: string | null
  version: string
  archivedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateIssueRequest {
  title: string
  description?: string | null
}

export interface UpdateIssueRequest {
  expectedVersion: string
  title?: string | null
  description?: string | null
}

export interface TransitionIssueRequest {
  expectedVersion: string
  requestKey: string
  toState: string
}

export interface BlockIssueRequest {
  expectedVersion: string
  requestKey: string
  reason: string
}

export interface RecoverIssueRequest {
  expectedVersion: string
  requestKey: string
}

export interface PauseIssueRequest {
  expectedVersion: string
  requestKey: string
  reason: 'USER' | 'ERROR'
  detail?: string | null
}

export interface ResumeIssueRequest {
  expectedVersion: string
  requestKey: string
}

export interface StopIssueRequest {
  expectedVersion: string
  requestKey: string
  detail?: string | null
}

export interface ResolveUnknownIssueRequest {
  expectedVersion: string
  requestKey: string
  verification: string
}

export interface ReopenIssueRequest {
  expectedVersion: string
  requestKey: string
}

export interface ResetStageBudgetRequest {
  expectedVersion: string
  requestKey: string
  state: string
  maxRuns: number
}

export interface ArchiveIssueRequest {
  expectedVersion: string
}

export interface UnarchiveIssueRequest {
  expectedVersion: string
}

export type IssueActivityKind =
  | 'COMMENT'
  | 'INSTRUCTION'
  | 'RUN'
  | 'SPEC_CHANGE'
  | 'STATE_CHANGE'
  | 'CONTROL'
  | string

export type IssueActivityActorType = 'HUMAN' | 'AGENT' | 'SYSTEM' | string

export interface IssueActivityDTO {
  issueId: string
  sequence: string
  kind: IssueActivityKind
  actorType: IssueActivityActorType
  actorAgentName: string | null
  runId: string | null
  body: string | null
  data?: unknown
  createdAt: string
}

export interface AppendIssueActivityRequest {
  expectedVersion: string
  requestKey: string
  kind?: 'COMMENT' | 'INSTRUCTION' | null
  body: string
}

export interface IssueAgentThreadDTO {
  issueId: string
  agentName: string
  threadId: string
}

export interface IssueStageBudgetDTO {
  state: string
  maxRuns: number
  budgetAfterOrdinal: string
  usedRuns: string
  remainingRuns: string
}

export type IssueRunStatus =
  | 'RUNNING'
  | 'WAITING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'UNKNOWN'
  | string

export interface IssueRunSummaryDTO {
  id: string
  issueId: string
  ordinal: string
  state: string
  status: IssueRunStatus
  agentName: string | null
  startedAt: string
  endedAt: string | null
}

export interface IssueRunDTO {
  id: string
  issueId: string
  ordinal: string
  state: string
  agentName: string | null
  sessionId: string
  threadId: string
  status: IssueRunStatus
  startEntryId: string
  endEntryId: string | null
  finalAnswerEntryId: string | null
  nextState: string | null
  observedActivitySequence: string
  remainingExecutionMs: string
  error: string | null
  version: string
  startedAt: string
  endedAt: string | null
}

export interface IssueEvidenceDTO {
  issueId: string
  blobId: string
  uri: string
  name: string | null
  actorAgentName: string | null
  runId: string | null
  createdAt: string
}

export interface AddIssueEvidenceRequest {
  uploadId: string
}

export interface IssueDetailDTO {
  issue: IssueDTO
  activities: IssueActivityDTO[]
  nextActivityCursor: string | null
  runs: IssueRunDTO[]
  currentRun: IssueRunDTO | null
  latestRun: IssueRunDTO | null
  stageBudgets: IssueStageBudgetDTO[]
  agentThreads: IssueAgentThreadDTO[]
}

export interface ProjectIssueSnapshotDTO {
  issue: IssueDTO
  currentOrLatestRun: IssueRunSummaryDTO | null
}

export interface ProjectSnapshotDTO {
  project: ProjectDTO
  issues: ProjectIssueSnapshotDTO[]
  /** 所有当前及归档 Issue 的 state / blockedFromState 引用，去重、确定性排序。 */
  referencedStateCodes: string[]
}

export interface ProjectsChangedEventPayload {
  projectId?: string
}
