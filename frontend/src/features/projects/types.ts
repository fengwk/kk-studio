import type { HarnessModelSelectionDTO } from '@/shared/api/contracts/ai-runtime'

export interface HarnessSessionSummaryDTO {
  sessionId: string
  name: string
  createdAt: string
  lastActivityAt: string
  firstMessagePreview: string | null
  threadCount: number
}

export interface HarnessThreadSummaryDTO {
  threadId: string
  name: string
  createdAt: string
  updatedAt: string
  status: string
  model: HarnessModelSelectionDTO
  headMessagePreview: string | null
}

export interface HarnessAcceptedCommandsDTO {
  session?: unknown
  thread?: unknown
  acceptedCommands?: unknown[]
  replayed?: boolean | null
}

export interface ProjectDTO {
  id: string
  title: string
  description: string
  coordinatorAgentName: string
  nextIssueNumber: string
  version: string
  archivedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateProjectRequest {
  title: string
  description?: string | null
  coordinatorAgentName: string
}

export interface UpdateProjectRequest {
  expectedVersion: string
  title: string
  description?: string | null
  coordinatorAgentName: string
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
  specRevision: string
  inputSequence: string
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
  title: string
  description?: string | null
  assigneeAgentName?: string | null
  reviewerAgentName?: string | null
}

export interface ChangeIssueStatusRequest {
  expectedVersion: string
  status: IssueStatus
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

export type IssueInputKind = 'HUMAN' | 'REVIEW_FEEDBACK' | 'RETRY' | 'SYSTEM'

export interface IssueInputDTO {
  issueId: string
  sequence: string
  kind: IssueInputKind
  body: string
  idempotencyKey: string | null
  createdAt: string
}

export interface AppendIssueInputRequest {
  body: string
  idempotencyKey?: string | null
  kind?: IssueInputKind | null
}

export type IssueRunRole = 'EXECUTOR' | 'REVIEWER'
export type IssueRunActorType = 'AGENT' | 'HUMAN'
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
  actorType: IssueRunActorType
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
  actorType: IssueRunActorType
  agentName: string | null
  submissionRunId: string | null
  status: IssueRunStatus
  outcome: IssueRunOutcome
  observedSpecRevision: string
  observedInputSequence: string
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
  sessionId: string | null
}

export type ReviewDecision = 'APPROVE' | 'REQUEST_CHANGES'

export interface ReviewIssueRequest {
  decision: ReviewDecision
  summary?: string | null
  verification?: string | null
  observedSpecRevision?: string | null
  observedInputSequence?: string | null
  terminalActionId?: string | null
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
  currentOrLatestRun: IssueRunSummaryDTO | null
}

export interface ProjectSnapshotDTO {
  project: ProjectDTO
  issues: ProjectIssueSnapshotDTO[]
  dependencies: IssueDependencyDTO[]
  coordinatorSessionId: string | null
  coordinatorSession: HarnessSessionSummaryDTO | null
  coordinatorThread: HarnessThreadSummaryDTO | null
}

export interface IssueDetailDTO {
  issue: IssueDTO
  blocked: boolean
  dependencies: IssueDependencyDTO[]
  inputs: IssueInputDTO[]
  runs: IssueRunDTO[]
  currentRun: IssueRunDTO | null
  latestRun: IssueRunDTO | null
}

export interface ProjectsChangedEventPayload {
  projectId?: string
}
