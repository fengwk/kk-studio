import { ApiError } from '@/shared/api/client'
import type {
  IssueActivityDTO,
  IssueAgentThreadDTO,
  IssueDTO,
  IssueDetailDTO,
  IssueEvidenceDTO,
  IssuePauseReason,
  IssueRunDTO,
  IssueRunSummaryDTO,
  IssueStageBudgetDTO,
  ProjectDTO,
  ProjectIssueSnapshotDTO,
  ProjectSnapshotDTO,
  ProjectWorkflowDTO,
  ProjectWorkflowStateDTO,
} from './types'

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const DECIMAL_LONG_REGEX = /^(0|[1-9][0-9]*)$/
const STATE_CODE_REGEX = /^[A-Z][A-Z0-9_]{0,63}$/

function invalidPayload(detail: string): ApiError {
  return new ApiError(`Project API returned an invalid payload: ${detail}`)
}

export function isCanonicalUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID_REGEX.test(value)
}

export function isDecimalLong(value: unknown): value is string {
  return typeof value === 'string' && DECIMAL_LONG_REGEX.test(value)
}

export function isValidStateCode(value: unknown): value is string {
  return typeof value === 'string' && STATE_CODE_REGEX.test(value)
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

function optionalNullableString(value: unknown, path: string): string | null {
  if (value === undefined || value === null) {
    return null
  }
  if (typeof value !== 'string') {
    throw invalidPayload(`${path} must be a string or null`)
  }
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

function requireUuid(value: unknown, path: string): string {
  const str = requireString(value, path)
  if (!UUID_REGEX.test(str)) {
    throw invalidPayload(`${path} must be a canonical UUID string`)
  }
  return str.toLowerCase()
}

function optionalNullableUuid(value: unknown, path: string): string | null {
  if (value === undefined || value === null) {
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

export function decodeProjectWorkflowState(
  raw: unknown,
  path = 'state',
): ProjectWorkflowStateDTO {
  const obj = requireRecord(raw, path)
  const stateCode = requireString(obj.state, `${path}.state`)
  if (!isValidStateCode(stateCode)) {
    throw invalidPayload(`${path}.state must match [A-Z][A-Z0-9_]{0,63}`)
  }

  let next: string[] | null = null
  if (obj.next !== undefined && obj.next !== null) {
    next = requireArray(obj.next, `${path}.next`, (item, itemPath) => {
      const code = requireString(item, itemPath)
      if (!isValidStateCode(code)) {
        throw invalidPayload(`${itemPath} must match [A-Z][A-Z0-9_]{0,63}`)
      }
      return code
    })
  }

  return {
    state: stateCode,
    name: requireString(obj.name, `${path}.name`),
    agent: optionalNullableString(obj.agent, `${path}.agent`),
    environment: optionalNullableString(obj.environment, `${path}.environment`),
    instructions: optionalNullableString(obj.instructions, `${path}.instructions`),
    maxRuns:
      obj.maxRuns !== undefined && obj.maxRuns !== null
        ? String(obj.maxRuns)
        : null,
    enabled: typeof obj.enabled === 'boolean' ? obj.enabled : null,
    next,
  }
}

export function decodeProjectWorkflow(
  raw: unknown,
  path = 'workflow',
): ProjectWorkflowDTO {
  const obj = requireRecord(raw, path)
  return {
    states: requireArray(obj.states, `${path}.states`, decodeProjectWorkflowState),
  }
}

export function decodeProject(raw: unknown, path = 'project'): ProjectDTO {
  const obj = requireRecord(raw, path)
  return {
    id: requireUuid(obj.id, `${path}.id`),
    title: requireString(obj.title, `${path}.title`),
    description: requireString(obj.description, `${path}.description`),
    workflow: decodeProjectWorkflow(obj.workflow, `${path}.workflow`),
    yoloEnabled: requireBoolean(obj.yoloEnabled, `${path}.yoloEnabled`),
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
  let pauseReason: IssuePauseReason | null = null
  if (obj.pauseReason !== undefined && obj.pauseReason !== null) {
    const pr = requireString(obj.pauseReason, `${path}.pauseReason`)
    if (pr !== 'USER' && pr !== 'ERROR' && pr !== 'UNKNOWN') {
      throw invalidPayload(`${path}.pauseReason must be USER, ERROR, or UNKNOWN`)
    }
    pauseReason = pr
  }

  return {
    id: requireUuid(obj.id, `${path}.id`),
    projectId: requireUuid(obj.projectId, `${path}.projectId`),
    number: requireDecimalLong(obj.number, `${path}.number`),
    title: requireString(obj.title, `${path}.title`),
    description: requireString(obj.description, `${path}.description`),
    state: requireString(obj.state, `${path}.state`),
    blockedFromState: optionalNullableString(obj.blockedFromState, `${path}.blockedFromState`),
    blockReason: optionalNullableString(obj.blockReason, `${path}.blockReason`),
    pauseReason,
    pauseDetail: optionalNullableString(obj.pauseDetail, `${path}.pauseDetail`),
    version: requireDecimalLong(obj.version, `${path}.version`),
    archivedAt: requireNullableString(obj.archivedAt, `${path}.archivedAt`),
    createdAt: requireString(obj.createdAt, `${path}.createdAt`),
    updatedAt: requireString(obj.updatedAt, `${path}.updatedAt`),
  }
}

export function decodeIssueRunSummary(
  raw: unknown,
  path = 'runSummary',
): IssueRunSummaryDTO {
  const obj = requireRecord(raw, path)
  return {
    id: requireUuid(obj.id, `${path}.id`),
    issueId: requireUuid(obj.issueId, `${path}.issueId`),
    ordinal: requireDecimalLong(obj.ordinal, `${path}.ordinal`),
    state: requireString(obj.state, `${path}.state`),
    status: requireString(obj.status, `${path}.status`),
    agentName: optionalNullableString(obj.agentName, `${path}.agentName`),
    startedAt: requireString(obj.startedAt, `${path}.startedAt`),
    endedAt: requireNullableString(obj.endedAt, `${path}.endedAt`),
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
  }
}

export function decodeIssueAgentThread(
  raw: unknown,
  path = 'agentThread',
): IssueAgentThreadDTO {
  const obj = requireRecord(raw, path)
  return {
    issueId: requireUuid(obj.issueId, `${path}.issueId`),
    agentName: requireString(obj.agentName, `${path}.agentName`),
    threadId: requireUuid(obj.threadId, `${path}.threadId`),
  }
}

export function decodeIssueStageBudget(
  raw: unknown,
  path = 'stageBudget',
): IssueStageBudgetDTO {
  const obj = requireRecord(raw, path)
  return {
    state: requireString(obj.state, `${path}.state`),
    maxRuns: requireSafeInteger(obj.maxRuns, `${path}.maxRuns`, 1),
    budgetAfterOrdinal: requireDecimalLong(
      obj.budgetAfterOrdinal,
      `${path}.budgetAfterOrdinal`,
    ),
    usedRuns: requireDecimalLong(obj.usedRuns, `${path}.usedRuns`),
    remainingRuns: requireDecimalLong(obj.remainingRuns, `${path}.remainingRuns`),
  }
}

export function decodeIssueRun(raw: unknown, path = 'run'): IssueRunDTO {
  const obj = requireRecord(raw, path)
  return {
    id: requireUuid(obj.id, `${path}.id`),
    issueId: requireUuid(obj.issueId, `${path}.issueId`),
    ordinal: requireDecimalLong(obj.ordinal, `${path}.ordinal`),
    state: requireString(obj.state, `${path}.state`),
    agentName: optionalNullableString(obj.agentName, `${path}.agentName`),
    sessionId: requireUuid(obj.sessionId, `${path}.sessionId`),
    threadId: requireUuid(obj.threadId, `${path}.threadId`),
    status: requireString(obj.status, `${path}.status`),
    startEntryId: requireUuid(obj.startEntryId, `${path}.startEntryId`),
    endEntryId: optionalNullableUuid(obj.endEntryId, `${path}.endEntryId`),
    finalAnswerEntryId: optionalNullableUuid(
      obj.finalAnswerEntryId,
      `${path}.finalAnswerEntryId`,
    ),
    nextState: optionalNullableString(obj.nextState, `${path}.nextState`),
    observedActivitySequence: requireDecimalLong(
      obj.observedActivitySequence,
      `${path}.observedActivitySequence`,
    ),
    remainingExecutionMs: requireDecimalLong(
      obj.remainingExecutionMs,
      `${path}.remainingExecutionMs`,
    ),
    error: optionalNullableString(obj.error, `${path}.error`),
    version: requireDecimalLong(obj.version, `${path}.version`),
    startedAt: requireString(obj.startedAt, `${path}.startedAt`),
    endedAt: requireNullableString(obj.endedAt, `${path}.endedAt`),
  }
}

export function decodeIssueActivity(raw: unknown, path = 'activity'): IssueActivityDTO {
  const obj = requireRecord(raw, path)
  return {
    issueId: requireUuid(obj.issueId, `${path}.issueId`),
    sequence: requireDecimalLong(obj.sequence, `${path}.sequence`),
    kind: requireString(obj.kind, `${path}.kind`),
    actorType: requireString(obj.actorType, `${path}.actorType`),
    actorAgentName: optionalNullableString(obj.actorAgentName, `${path}.actorAgentName`),
    runId: optionalNullableUuid(obj.runId, `${path}.runId`),
    body: optionalNullableString(obj.body, `${path}.body`),
    data: obj.data,
    createdAt: requireString(obj.createdAt, `${path}.createdAt`),
  }
}

export function decodeIssueActivityList(
  raw: unknown,
  path = 'activities',
): IssueActivityDTO[] {
  return requireArray(raw, path, decodeIssueActivity)
}

export function decodeIssueEvidence(raw: unknown, path = 'evidence'): IssueEvidenceDTO {
  const obj = requireRecord(raw, path)
  return {
    issueId: requireUuid(obj.issueId, `${path}.issueId`),
    blobId: requireString(obj.blobId, `${path}.blobId`),
    uri: requireString(obj.uri, `${path}.uri`),
    name: optionalNullableString(obj.name, `${path}.name`),
    actorAgentName: optionalNullableString(obj.actorAgentName, `${path}.actorAgentName`),
    runId: optionalNullableUuid(obj.runId, `${path}.runId`),
    createdAt: requireString(obj.createdAt, `${path}.createdAt`),
  }
}

export function decodeIssueEvidenceList(
  raw: unknown,
  path = 'evidence',
): IssueEvidenceDTO[] {
  return requireArray(raw, path, decodeIssueEvidence)
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
    activities: requireArray(obj.activities ?? [], `${path}.activities`, decodeIssueActivity),
    nextActivityCursor: requireNullableString(
      obj.nextActivityCursor,
      `${path}.nextActivityCursor`,
    ),
    runs: requireArray(obj.runs ?? [], `${path}.runs`, decodeIssueRun),
    currentRun,
    latestRun,
    stageBudgets: requireArray(
      obj.stageBudgets ?? [],
      `${path}.stageBudgets`,
      decodeIssueStageBudget,
    ),
    agentThreads: requireArray(
      obj.agentThreads ?? [],
      `${path}.agentThreads`,
      decodeIssueAgentThread,
    ),
  }
}
