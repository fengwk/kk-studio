export interface ProjectDTO {
  id: string
  title: string
  description: string
  yoloEnabled: boolean
  maxReviewRejections: string
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
  maxReviewRejections?: number | null
}

export interface UpdateProjectRequest {
  expectedVersion: string
  title?: string | null
  description?: string | null
  yoloEnabled?: boolean | null
  maxReviewRejections?: number | null
}

export interface ProjectArchiveRequest {
  expectedVersion: string
}

export interface ProjectUnarchiveRequest {
  expectedVersion: string
}

export type IssueStatus =
  | 'BACKLOG'
  | 'TODO'
  | 'IN_PROGRESS'
  | 'IN_REVIEW'
  | 'BLOCKED'
  | 'DONE'
  | 'CANCELED'

export interface IssueDTO {
  id: string
  projectId: string
  number: string
  title: string
  description: string
  status: IssueStatus
  assigneeAgentName: string | null
  reviewerAgentName: string | null
  version: string
  archivedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateIssueRequest {
  title: string
  description?: string | null
  assigneeAgentName?: string | null
  reviewerAgentName?: string | null
  initialStatus?: 'BACKLOG' | 'TODO' | null
}

export interface UpdateIssueRequest {
  expectedVersion: string
  title?: string | null
  description?: string | null
  assigneeAgentName?: string | null
  reviewerAgentName?: string | null
}

export interface ChangeIssueStatusRequest {
  expectedVersion: string
  status: IssueStatus
}

export interface BlockIssueRequest {
  expectedVersion: string
  reason: string
}

export interface RecoverIssueRequest {
  expectedVersion: string
  toBacklog?: boolean | null
  comment?: string | null
}

export interface IssueDependencyDTO {
  issueId: string
  dependsOnIssueId: string
  projectId: string
  createdAt: string
}

export interface AddIssueDependencyRequest {
  expectedVersion: string
  dependsOnIssueId: string
}

export type IssueRunRole = 'EXECUTOR' | 'REVIEWER'

export interface IssueAgentSessionDTO {
  id: string
  issueId: string
  agentName: string
  role: IssueRunRole
  sessionId: string
  branchId: string
  createdAt: string
}

export type IssueActivityKind =
  | 'SPEC_CHANGE'
  | 'INSTRUCTION'
  | 'COMMENT'
  | 'HUMAN_INPUT'
  | 'REVIEW_DECISION'
  | 'RECOVERY'
  | 'RETRY'
  | 'SYSTEM'

export type IssueActivityActorType = 'HUMAN' | 'AGENT' | 'SYSTEM'

export interface IssueActivityDTO {
  issueId: string
  sequence: string
  kind: IssueActivityKind
  actorType: IssueActivityActorType
  actorAgentName: string | null
  targetRole: IssueRunRole | null
  runId: string | null
  submissionRunId: string | null
  decision: string | null
  body: string
  idempotencyKey: string | null
  createdAt: string
}

export interface AppendIssueActivityRequest {
  body: string
  kind?: string | null
  targetRole?: IssueRunRole | null
  idempotencyKey?: string | null
}

export type IssueRunStatus =
  | 'RUNNING'
  | 'WAITING_HUMAN'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'UNKNOWN'

export type IssueRunOutcome = 'SUBMITTED' | 'APPROVED' | 'CHANGES_REQUESTED' | null

export interface IssueRunSummaryDTO {
  id: string
  issueId: string
  ordinal: string
  role: IssueRunRole
  agentName: string | null
  submissionRunId: string | null
  status: IssueRunStatus
  outcome: IssueRunOutcome
  waitingReason: string | null
  createdAt: string | null
  completedAt: string | null
}

export interface IssueRunDTO {
  id: string
  issueId: string
  ordinal: string
  role: IssueRunRole
  agentName: string | null
  agentSessionId: string | null
  sessionId: string | null
  submissionRunId: string | null
  status: IssueRunStatus
  outcome: IssueRunOutcome
  observedActivitySequence: string
  continuationCount: number
  maxContinuations: number
  deadline: string | null
  waitingReason: string | null
  result: string | null
  terminalActionId: string | null
  version: string
  createdAt: string
  updatedAt: string
  completedAt: string | null
}

export type ReviewDecision = 'APPROVE' | 'REQUEST_CHANGES'

export interface ReviewIssueRequest {
  decision: ReviewDecision
  reason?: string | null
  idempotencyKey?: string | null
}

export interface CancelIssueRequest {
  expectedVersion: string
  reason?: string | null
}

export interface RetryIssueRequest {
  idempotencyKey?: string | null
}

export interface ArchiveIssueRequest {
  expectedVersion: string
}

export interface UnarchiveIssueRequest {
  expectedVersion: string
}

export interface ProjectIssueSnapshotDTO {
  issue: IssueDTO
  blocked: boolean
  reviewRejectionCount: string
  currentOrLatestRun: IssueRunSummaryDTO | null
}

export interface ProjectSnapshotDTO {
  project: ProjectDTO
  issues: ProjectIssueSnapshotDTO[]
  dependencies: IssueDependencyDTO[]
}

export type IssueEvidenceOrigin = 'EXECUTOR' | 'HUMAN'

export interface IssueEvidenceDTO {
  issueId: string
  blobId: string
  uri: string
  origin: IssueEvidenceOrigin | string
  name: string | null
  runId: string | null
  publishedAt: string
}

export interface AddIssueEvidenceRequest {
  uploadId: string
}

export interface IssueDetailDTO {
  issue: IssueDTO
  blocked: boolean
  dependencies: IssueDependencyDTO[]
  sessions: IssueAgentSessionDTO[]
  activities: IssueActivityDTO[]
  evidence: IssueEvidenceDTO[]
  nextActivityCursor: string | null
  runs: IssueRunDTO[]
  currentRun: IssueRunDTO | null
  latestRun: IssueRunDTO | null
}

export interface ProjectsChangedEventPayload {
  projectId?: string
}
