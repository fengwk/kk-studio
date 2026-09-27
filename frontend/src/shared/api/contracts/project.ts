/**
 * Project 领域权威 API 契约定义。
 *
 * <p>对齐后端 share/project 及新增生命周期：
 * - 移除 dependencies / executor-reviewer / maxReviewRejections 旧概念；
 * - 引入 ProjectWorkflow 配置、自然状态 token、稳定 AgentThread 绑定、阶段预算、
 *   Run 执行事实与区间报告、Activity (COMMENT / INSTRUCTION)、Evidence 上传、
 *   Stop 终止、UNKNOWN 人工核查以及 Project/Issue 彻底删除。
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

export interface CreateProjectRequestDTO {
  title: string
  description?: string | null
  yoloEnabled?: boolean | null
}

export interface UpdateProjectRequestDTO {
  expectedVersion: string
  title?: string | null
  description?: string | null
}

export interface UpdateProjectWorkflowRequestDTO {
  expectedVersion: string
  workflow: ProjectWorkflowDTO
}

export interface UpdateProjectYoloRequestDTO {
  expectedVersion: string
  yoloEnabled: boolean
}

export interface ProjectVersionRequestDTO {
  expectedVersion: string
}

export interface IssueDTO {
  id: string
  projectId: string
  number: string
  title: string
  description: string
  state: string
  blockedFromState: string | null
  blockReason: string | null
  pauseReason: 'USER' | 'ERROR' | 'UNKNOWN' | null
  pauseDetail: string | null
  version: string
  archivedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateIssueRequestDTO {
  title: string
  description?: string | null
}

export interface UpdateIssueRequestDTO {
  expectedVersion: string
  title?: string | null
  description?: string | null
}

export interface TransitionIssueRequestDTO {
  expectedVersion: string
  requestKey: string
  toState: string
}

export interface BlockIssueRequestDTO {
  expectedVersion: string
  requestKey: string
  reason: string
}

export interface RecoverIssueRequestDTO {
  expectedVersion: string
  requestKey: string
}

export interface PauseIssueRequestDTO {
  expectedVersion: string
  requestKey: string
  reason: 'USER' | 'ERROR'
  detail?: string | null
}

export interface ResumeIssueRequestDTO {
  expectedVersion: string
  requestKey: string
}

export interface StopIssueRequestDTO {
  expectedVersion: string
  requestKey: string
  detail?: string | null
}

export interface ResolveUnknownIssueRequestDTO {
  expectedVersion: string
  requestKey: string
  verification: string
}

export interface ReopenIssueRequestDTO {
  expectedVersion: string
  requestKey: string
}

export interface ResetStageBudgetRequestDTO {
  expectedVersion: string
  requestKey: string
  state: string
  maxRuns: number
}

export interface ArchiveIssueRequestDTO {
  expectedVersion: string
}

export interface UnarchiveIssueRequestDTO {
  expectedVersion: string
}

export interface AppendIssueActivityRequestDTO {
  expectedVersion: string
  requestKey: string
  kind?: 'COMMENT' | 'INSTRUCTION' | null
  body: string
}

export interface AddIssueEvidenceRequestDTO {
  uploadId: string
}

export interface IssueActivityDTO {
  issueId: string
  sequence: string
  kind: 'COMMENT' | 'INSTRUCTION' | 'RUN' | 'SPEC_CHANGE' | 'STATE_CHANGE' | 'CONTROL' | string
  actorType: 'HUMAN' | 'AGENT' | 'SYSTEM' | string
  actorAgentName: string | null
  runId: string | null
  body: string | null
  data?: unknown
  createdAt: string
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

export interface IssueRunSummaryDTO {
  id: string
  issueId: string
  ordinal: string
  state: string
  status: 'RUNNING' | 'WAITING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'UNKNOWN' | string
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
  status: 'RUNNING' | 'WAITING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'UNKNOWN' | string
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
}
