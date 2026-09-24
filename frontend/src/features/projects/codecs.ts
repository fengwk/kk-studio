import { ApiError } from '@/shared/api/client'
import type {
  IssueActivityActorType,
  IssueActivityDTO,
  IssueActivityKind,
  IssueAgentSessionDTO,
  IssueDTO,
  IssueDependencyDTO,
  IssueDetailDTO,
  IssueRunDTO,
  IssueRunOutcome,
  IssueRunRole,
  IssueRunStatus,
  IssueRunSummaryDTO,
  IssueStatus,
  ProjectDTO,
  ProjectIssueSnapshotDTO,
  ProjectSnapshotDTO,
} from './types'

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const DECIMAL_LONG_REGEX = /^(0|[1-9][0-9]*)$/
const ISSUE_STATUSES: readonly IssueStatus[] = [
  'BACKLOG',
  'TODO',
  'IN_PROGRESS',
  'IN_REVIEW',
  'BLOCKED',
  'DONE',
  'CANCELED',
]
const ISSUE_ACTIVITY_KINDS: readonly IssueActivityKind[] = [
  'SPEC_CHANGE',
  'INSTRUCTION',
  'COMMENT',
  'HUMAN_INPUT',
  'REVIEW_DECISION',
  'RECOVERY',
  'RETRY',
  'SYSTEM',
]
const ISSUE_ACTIVITY_ACTOR_TYPES: readonly IssueActivityActorType[] = [
  'HUMAN',
  'AGENT',
  'SYSTEM',
]
const ISSUE_RUN_ROLES: readonly IssueRunRole[] = ['EXECUTOR', 'REVIEWER']
const ISSUE_RUN_STATUSES: readonly IssueRunStatus[] = [
  'RUNNING',
  'WAITING_HUMAN',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
  'UNKNOWN',
]
const ISSUE_RUN_OUTCOMES: readonly Exclude<IssueRunOutcome, null>[] = [
  'SUBMITTED',
  'APPROVED',
  'CHANGES_REQUESTED',
]

function invalidPayload(detail: string): ApiError {
  return new ApiError(`Project API returned an invalid payload: ${detail}`)
}

export function isCanonicalUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID_REGEX.test(value)
}

export function isDecimalLong(value: unknown): value is string {
  return typeof value === 'string' && DECIMAL_LONG_REGEX.test(value)
}

export function isIssueStatus(value: unknown): value is IssueStatus {
  return typeof value === 'string' && (ISSUE_STATUSES as readonly string[]).includes(value)
}

function requireRecord(value: unknown, path: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw invalidPayload(`${path} must be an object`)
  }
  return value as Record<string, unknown>
}

function requireString(value: unknown, path: string): string {
  if (typeof value !== 'string') {
    throw invalidPayload(`${path} must be a string`)
  }
  return value
}

function requireNullableString(value: unknown, path: string): string | null {
  if (value === undefined) {
    throw invalidPayload(`${path} must not be undefined`)
  }
  if (value === null) {
    return null
  }
  if (typeof value !== 'string') {
    throw invalidPayload(`${path} must be a string or null`)
  }
  return value
}

function requireUuid(value: unknown, path: string): string {
  const str = requireString(value, path)
  if (!UUID_REGEX.test(str)) {
    throw invalidPayload(`${path} must be a canonical UUID string`)
  }
  return str.toLowerCase()
}

function requireNullableUuid(value: unknown, path: string): string | null {
  if (value === undefined) {
    throw invalidPayload(`${path} must not be undefined`)
  }
  if (value === null) {
    return null
  }
  return requireUuid(value, path)
}

function requireDecimalLong(value: unknown, path: string): string {
  const str = requireString(value, path)
  if (!DECIMAL_LONG_REGEX.test(str)) {
    throw invalidPayload(`${path} must be a canonical non-negative decimal string`)
  }
  return str
}

function requireBoolean(value: unknown, path: string): boolean {
  if (typeof value !== 'boolean') {
    throw invalidPayload(`${path} must be a boolean`)
  }
  return value
}

function requireSafeInteger(value: unknown, path: string, min = 0): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min) {
    throw invalidPayload(`${path} must be a safe integer >= ${min}`)
  }
  return value
}

function requireIssueStatus(value: unknown, path: string): IssueStatus {
  const str = requireString(value, path)
  if (!isIssueStatus(str)) {
    throw invalidPayload(`${path} must be one of ${ISSUE_STATUSES.join(', ')}`)
  }
  return str
}

function requireEnumValue<T extends string>(
  value: unknown,
  path: string,
  allowed: readonly T[],
): T {
  const str = requireString(value, path)
  if (!(allowed as readonly string[]).includes(str)) {
    throw invalidPayload(`${path} has an unknown value`)
  }
  return str as T
}

function requireNullableEnumValue<T extends string>(
  value: unknown,
  path: string,
  allowed: readonly T[],
): T | null {
  if (value === null) {
    return null
  }
  return requireEnumValue(value, path, allowed)
}

function requireArray<T>(
  value: unknown,
  path: string,
  decoder: (item: unknown, itemPath: string) => T,
): T[] {
  if (!Array.isArray(value)) {
    throw invalidPayload(`${path} must be an array`)
  }
  return value.map((item, index) => decoder(item, `${path}[${index}]`))
}

export function decodeProject(raw: unknown, path = 'project'): ProjectDTO {
  const obj = requireRecord(raw, path)
  return {
    id: requireUuid(obj.id, `${path}.id`),
    title: requireString(obj.title, `${path}.title`),
    description: requireString(obj.description, `${path}.description`),
    yoloEnabled: requireBoolean(obj.yoloEnabled, `${path}.yoloEnabled`),
    maxReviewRejections: requireDecimalLong(obj.maxReviewRejections, `${path}.maxReviewRejections`),
    nextIssueNumber: requireDecimalLong(obj.nextIssueNumber, `${path}.nextIssueNumber`),
    version: requireDecimalLong(obj.version, `${path}.version`),
    archivedAt: requireNullableString(obj.archivedAt, `${path}.archivedAt`),
    createdAt: requireString(obj.createdAt, `${path}.createdAt`),
    updatedAt: requireString(obj.updatedAt, `${path}.updatedAt`),
  }
}

export function decodeProjectList(raw: unknown, path = 'projects'): ProjectDTO[] {
  return requireArray(raw, path, decodeProject)
}

export function decodeIssue(raw: unknown, path = 'issue'): IssueDTO {
  const obj = requireRecord(raw, path)
  return {
    id: requireUuid(obj.id, `${path}.id`),
    projectId: requireUuid(obj.projectId, `${path}.projectId`),
    number: requireDecimalLong(obj.number, `${path}.number`),
    title: requireString(obj.title, `${path}.title`),
    description: requireString(obj.description, `${path}.description`),
    status: requireIssueStatus(obj.status, `${path}.status`),
    assigneeAgentName: requireNullableString(
      obj.assigneeAgentName,
      `${path}.assigneeAgentName`,
    ),
    reviewerAgentName: requireNullableString(
      obj.reviewerAgentName,
      `${path}.reviewerAgentName`,
    ),
    version: requireDecimalLong(obj.version, `${path}.version`),
    archivedAt: requireNullableString(obj.archivedAt, `${path}.archivedAt`),
    createdAt: requireString(obj.createdAt, `${path}.createdAt`),
    updatedAt: requireString(obj.updatedAt, `${path}.updatedAt`),
  }
}

export function decodeIssueDependency(raw: unknown, path = 'dependency'): IssueDependencyDTO {
  const obj = requireRecord(raw, path)
  return {
    issueId: requireUuid(obj.issueId, `${path}.issueId`),
    dependsOnIssueId: requireUuid(obj.dependsOnIssueId, `${path}.dependsOnIssueId`),
    projectId: requireUuid(obj.projectId, `${path}.projectId`),
    createdAt: requireString(obj.createdAt, `${path}.createdAt`),
  }
}

export function decodeIssueDependencyList(
  raw: unknown,
  path = 'dependencies',
): IssueDependencyDTO[] {
  return requireArray(raw, path, decodeIssueDependency)
}

export function decodeIssueAgentSession(
  raw: unknown,
  path = 'agentSession',
): IssueAgentSessionDTO {
  const obj = requireRecord(raw, path)
  return {
    id: requireUuid(obj.id, `${path}.id`),
    issueId: requireUuid(obj.issueId, `${path}.issueId`),
    agentName: requireString(obj.agentName, `${path}.agentName`),
    role: requireEnumValue(obj.role, `${path}.role`, ISSUE_RUN_ROLES),
    sessionId: requireUuid(obj.sessionId, `${path}.sessionId`),
    branchId: requireUuid(obj.branchId, `${path}.branchId`),
    createdAt: requireString(obj.createdAt, `${path}.createdAt`),
  }
}

export function decodeIssueActivity(raw: unknown, path = 'activity'): IssueActivityDTO {
  const obj = requireRecord(raw, path)
  return {
    issueId: requireUuid(obj.issueId, `${path}.issueId`),
    sequence: requireDecimalLong(obj.sequence, `${path}.sequence`),
    kind: requireEnumValue(obj.kind, `${path}.kind`, ISSUE_ACTIVITY_KINDS),
    actorType: requireEnumValue(obj.actorType, `${path}.actorType`, ISSUE_ACTIVITY_ACTOR_TYPES),
    actorAgentName: requireNullableString(obj.actorAgentName, `${path}.actorAgentName`),
    targetRole: requireNullableEnumValue(obj.targetRole, `${path}.targetRole`, ISSUE_RUN_ROLES),
    runId: requireNullableUuid(obj.runId, `${path}.runId`),
    submissionRunId: requireNullableUuid(obj.submissionRunId, `${path}.submissionRunId`),
    decision: requireNullableString(obj.decision, `${path}.decision`),
    body: requireString(obj.body, `${path}.body`),
    idempotencyKey: requireNullableString(obj.idempotencyKey, `${path}.idempotencyKey`),
    createdAt: requireString(obj.createdAt, `${path}.createdAt`),
  }
}

export function decodeIssueActivityList(
  raw: unknown,
  path = 'activities',
): IssueActivityDTO[] {
  return requireArray(raw, path, decodeIssueActivity)
}

export function decodeIssueRunSummary(raw: unknown, path = 'runSummary'): IssueRunSummaryDTO {
  const obj = requireRecord(raw, path)
  return {
    id: requireUuid(obj.id, `${path}.id`),
    issueId: requireUuid(obj.issueId, `${path}.issueId`),
    ordinal: requireDecimalLong(obj.ordinal, `${path}.ordinal`),
    role: requireEnumValue(obj.role, `${path}.role`, ISSUE_RUN_ROLES),
    agentName: requireNullableString(obj.agentName, `${path}.agentName`),
    submissionRunId: requireNullableUuid(obj.submissionRunId, `${path}.submissionRunId`),
    status: requireEnumValue(obj.status, `${path}.status`, ISSUE_RUN_STATUSES),
    outcome: requireNullableEnumValue(obj.outcome, `${path}.outcome`, ISSUE_RUN_OUTCOMES),
    waitingReason: requireNullableString(obj.waitingReason, `${path}.waitingReason`),
    createdAt: requireNullableString(obj.createdAt, `${path}.createdAt`),
    completedAt: requireNullableString(obj.completedAt, `${path}.completedAt`),
  }
}

export function decodeIssueRun(raw: unknown, path = 'run'): IssueRunDTO {
  const obj = requireRecord(raw, path)
  return {
    id: requireUuid(obj.id, `${path}.id`),
    issueId: requireUuid(obj.issueId, `${path}.issueId`),
    ordinal: requireDecimalLong(obj.ordinal, `${path}.ordinal`),
    role: requireEnumValue(obj.role, `${path}.role`, ISSUE_RUN_ROLES),
    agentName: requireNullableString(obj.agentName, `${path}.agentName`),
    agentSessionId: requireNullableUuid(obj.agentSessionId, `${path}.agentSessionId`),
    sessionId: requireNullableUuid(obj.sessionId, `${path}.sessionId`),
    submissionRunId: requireNullableUuid(obj.submissionRunId, `${path}.submissionRunId`),
    status: requireEnumValue(obj.status, `${path}.status`, ISSUE_RUN_STATUSES),
    outcome: requireNullableEnumValue(obj.outcome, `${path}.outcome`, ISSUE_RUN_OUTCOMES),
    observedActivitySequence: requireDecimalLong(
      obj.observedActivitySequence,
      `${path}.observedActivitySequence`,
    ),
    continuationCount: requireSafeInteger(obj.continuationCount, `${path}.continuationCount`, 0),
    maxContinuations: requireSafeInteger(obj.maxContinuations, `${path}.maxContinuations`, 0),
    deadline: requireNullableString(obj.deadline, `${path}.deadline`),
    waitingReason: requireNullableString(obj.waitingReason, `${path}.waitingReason`),
    result: requireNullableString(obj.result, `${path}.result`),
    terminalActionId: requireNullableString(obj.terminalActionId, `${path}.terminalActionId`),
    version: requireDecimalLong(obj.version, `${path}.version`),
    createdAt: requireString(obj.createdAt, `${path}.createdAt`),
    updatedAt: requireString(obj.updatedAt, `${path}.updatedAt`),
    completedAt: requireNullableString(obj.completedAt, `${path}.completedAt`),
  }
}

export function decodeProjectIssueSnapshot(
  raw: unknown,
  path = 'issueSnapshot',
): ProjectIssueSnapshotDTO {
  const obj = requireRecord(raw, path)
  const currentOrLatestRun =
    obj.currentOrLatestRun === null || obj.currentOrLatestRun === undefined
      ? null
      : decodeIssueRunSummary(obj.currentOrLatestRun, `${path}.currentOrLatestRun`)

  return {
    issue: decodeIssue(obj.issue, `${path}.issue`),
    blocked: requireBoolean(obj.blocked, `${path}.blocked`),
    reviewRejectionCount: requireDecimalLong(
      obj.reviewRejectionCount,
      `${path}.reviewRejectionCount`,
    ),
    currentOrLatestRun,
  }
}

export function decodeProjectSnapshot(
  raw: unknown,
  path = 'projectSnapshot',
): ProjectSnapshotDTO {
  const obj = requireRecord(raw, path)
  return {
    project: decodeProject(obj.project, `${path}.project`),
    issues: requireArray(obj.issues, `${path}.issues`, decodeProjectIssueSnapshot),
    dependencies: decodeIssueDependencyList(obj.dependencies, `${path}.dependencies`),
  }
}

export function decodeIssueDetail(raw: unknown, path = 'issueDetail'): IssueDetailDTO {
  const obj = requireRecord(raw, path)
  const currentRun =
    obj.currentRun === null || obj.currentRun === undefined
      ? null
      : decodeIssueRun(obj.currentRun, `${path}.currentRun`)
  const latestRun =
    obj.latestRun === null || obj.latestRun === undefined
      ? null
      : decodeIssueRun(obj.latestRun, `${path}.latestRun`)

  return {
    issue: decodeIssue(obj.issue, `${path}.issue`),
    blocked: requireBoolean(obj.blocked, `${path}.blocked`),
    dependencies: decodeIssueDependencyList(obj.dependencies, `${path}.dependencies`),
    sessions: requireArray(obj.sessions ?? [], `${path}.sessions`, decodeIssueAgentSession),
    activities: requireArray(obj.activities ?? [], `${path}.activities`, decodeIssueActivity),
    nextActivityCursor: requireNullableString(
      obj.nextActivityCursor,
      `${path}.nextActivityCursor`,
    ),
    runs: requireArray(obj.runs, `${path}.runs`, decodeIssueRun),
    currentRun,
    latestRun,
  }
}
