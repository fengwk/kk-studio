import type { ComposerPart } from '@/features/ai/composer/composer-parts'
import type { BranchDraft } from '@/features/ai/chat/branch-draft'
import type {
  AgentCommandTargetDTO,
  AgentCommandBatchRequestDTO,
  AgentRuntimeOwnerDTO,
  HarnessBranchSettingsDTO,
  HarnessCommandCreateDTO,
  HarnessModelSelectionDTO,
} from '@/shared/api/contracts/ai-runtime'

export type PaneTarget =
  | { kind: 'NEW_SESSION_DRAFT' }
  | { kind: 'ENTRY_DRAFT'; sessionId: string; startEntryId: string }
  | { kind: 'BOUND_THREAD'; threadId: string }

export type PaneTargetKind = PaneTarget['kind']

export interface PendingAcceptance {
  owner: AgentRuntimeOwnerDTO
  target: PaneTarget
  request: AgentCommandBatchRequestDTO
  branchDraft: BranchDraft
  composerParts: ComposerPart[]
  generation: number
  unknownOutcome: boolean
}

const TARGET_STORAGE_PREFIX = 'kk-studio.agent-pane-target.'
const PENDING_STORAGE_PREFIX = 'kk-studio.agent-pane-acceptance.'

function storageKey(owner: AgentRuntimeOwnerDTO, paneId: string): string {
  return `${TARGET_STORAGE_PREFIX}${owner.type}:${owner.id}:${paneId}`
}

function pendingStorageKey(owner: AgentRuntimeOwnerDTO, paneId: string): string {
  return `${PENDING_STORAGE_PREFIX}${owner.type}:${owner.id}:${paneId}`
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function nonBlank(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isOwner(value: unknown): value is AgentRuntimeOwnerDTO {
  return isRecord(value)
    && (value.type === 'CHAT' || value.type === 'CANVAS')
    && nonBlank(value.id)
}

function isModelSelection(value: unknown): value is HarnessModelSelectionDTO {
  return isRecord(value)
    && nonBlank(value.providerName)
    && nonBlank(value.modelName)
    && nonBlank(value.variant)
}

function isBranchSettings(value: unknown): value is HarnessBranchSettingsDTO {
  if (!isRecord(value)) {
    return false
  }
  const keys = Object.keys(value)
  if (
    keys.length !== 3
    || !keys.every((key) => key === 'agentName' || key === 'model' || key === 'workspacePath')
  ) {
    return false
  }
  return (value.workspacePath === null || typeof value.workspacePath === 'string')
    && nonBlank(value.agentName)
    && isModelSelection(value.model)
}

function isCommandTarget(value: unknown): value is AgentCommandTargetDTO {
  if (!isRecord(value) || typeof value.type !== 'string') {
    return false
  }
  if (Object.prototype.hasOwnProperty.call(value, 'kind')) {
    return false
  }
  if (value.type === 'NEW_SESSION') {
    return nonBlank(value.sessionId)
      && nonBlank(value.threadId)
      && isBranchSettings(value.rootSettings)
      && typeof value.yoloEnabled === 'boolean'
  }
  if (value.type === 'ENTRY') {
    return nonBlank(value.sessionId)
      && nonBlank(value.startEntryId)
      && nonBlank(value.threadId)
      && typeof value.yoloEnabled === 'boolean'
  }
  if (value.type === 'THREAD') {
    return nonBlank(value.threadId)
      && nonBlank(value.expectedHeadEntryId)
      && nonBlank(value.expectedNextCommandSequence)
  }
  return false
}

function isCommandContent(value: unknown): boolean {
  if (!isRecord(value) || typeof value.type !== 'string') {
    return false
  }
  if (value.type === 'TEXT') {
    return typeof value.text === 'string'
  }
  if (value.type === 'ATTACHMENT') {
    return nonBlank(value.uploadId)
  }
  return value.type === 'RESOURCE'
    && nonBlank(value.blobId)
    && nonBlank(value.name)
    && (value.preview == null || typeof value.preview === 'string')
}

function isCommand(value: unknown): value is HarnessCommandCreateDTO {
  if (!isRecord(value) || !nonBlank(value.type) || !nonBlank(value.idempotencyKey)) {
    return false
  }
  switch (value.type) {
    case 'USER_MESSAGE':
      return Array.isArray(value.contents)
        && value.contents.length > 0
        && value.contents.every(isCommandContent)
    case 'SET_AGENT':
      return nonBlank(value.agentName)
    case 'SET_MODEL':
      return isModelSelection(value.model)
    case 'SET_ENVIRONMENT':
      return Object.prototype.hasOwnProperty.call(value, 'workspacePath')
        && !Object.prototype.hasOwnProperty.call(value, 'environment')
        && (value.workspacePath === null || typeof value.workspacePath === 'string')
    default:
      return false
  }
}

function isCommandBatchRequest(value: unknown): value is AgentCommandBatchRequestDTO {
  return isRecord(value)
    && isOwner(value.owner)
    && isCommandTarget(value.target)
    && Array.isArray(value.commands)
    && value.commands.length > 0
    && value.commands.every(isCommand)
}

function isBranchDraft(value: unknown): value is BranchDraft {
  if (!isRecord(value)) {
    return false
  }
  const keys = Object.keys(value)
  if (
    keys.length !== 4
    || !keys.every((key) => key === 'agentName' || key === 'model' || key === 'workspacePath' || key === 'yoloEnabled')
  ) {
    return false
  }
  return (value.workspacePath === null || typeof value.workspacePath === 'string')
    && nonBlank(value.agentName)
    && isModelSelection(value.model)
    && typeof value.yoloEnabled === 'boolean'
}

function isComposerPart(value: unknown): value is ComposerPart {
  if (!isRecord(value) || !nonBlank(value.partId)) {
    return false
  }
  if (value.type === 'text') {
    return typeof value.text === 'string'
  }
  if (value.type === 'attachment') {
    return nonBlank(value.uploadId) && typeof value.filename === 'string'
  }
  return value.type === 'resource'
    && nonBlank(value.blobId)
    && nonBlank(value.name)
    && (value.preview === undefined || typeof value.preview === 'string')
}

function isPendingAcceptanceValue(value: unknown): value is PendingAcceptance {
  return isRecord(value)
    && isOwner(value.owner)
    && isPaneTarget(value.target)
    && isCommandBatchRequest(value.request)
    && isBranchDraft(value.branchDraft)
    && Array.isArray(value.composerParts)
    && value.composerParts.every(isComposerPart)
    && typeof value.generation === 'number'
    && Number.isInteger(value.generation)
    && value.generation >= 0
    && typeof value.unknownOutcome === 'boolean'
}

export function isPaneTarget(value: unknown): value is PaneTarget {
  if (!isRecord(value) || typeof value.kind !== 'string') {
    return false
  }
  if (value.kind === 'NEW_SESSION_DRAFT') {
    return true
  }
  if (value.kind === 'ENTRY_DRAFT') {
    return nonBlank(value.sessionId) && nonBlank(value.startEntryId)
  }
  if (value.kind === 'BOUND_THREAD') {
    return nonBlank(value.threadId)
  }
  return false
}

export function normalizePaneTarget(value: unknown): PaneTarget {
  if (!isPaneTarget(value)) {
    return { kind: 'NEW_SESSION_DRAFT' }
  }
  if (value.kind === 'NEW_SESSION_DRAFT') {
    return value
  }
  if (value.kind === 'ENTRY_DRAFT') {
    return {
      kind: value.kind,
      sessionId: value.sessionId.trim(),
      startEntryId: value.startEntryId.trim(),
    }
  }
  return { kind: value.kind, threadId: value.threadId.trim() }
}

export function samePaneTarget(left: PaneTarget, right: PaneTarget): boolean {
  if (left.kind !== right.kind) {
    return false
  }
  if (left.kind === 'NEW_SESSION_DRAFT' && right.kind === 'NEW_SESSION_DRAFT') {
    return true
  }
  if (left.kind === 'ENTRY_DRAFT' && right.kind === 'ENTRY_DRAFT') {
    return left.sessionId === right.sessionId && left.startEntryId === right.startEntryId
  }
  if (left.kind === 'BOUND_THREAD' && right.kind === 'BOUND_THREAD') {
    return left.threadId === right.threadId
  }
  return false
}

export function isNewSessionTarget(target: PaneTarget): target is { kind: 'NEW_SESSION_DRAFT' } {
  return target.kind === 'NEW_SESSION_DRAFT'
}

export function isEntryTarget(
  target: PaneTarget,
): target is { kind: 'ENTRY_DRAFT'; sessionId: string; startEntryId: string } {
  return target.kind === 'ENTRY_DRAFT'
}

export function isBoundTarget(
  target: PaneTarget,
): target is { kind: 'BOUND_THREAD'; threadId: string } {
  return target.kind === 'BOUND_THREAD'
}

export function targetIdentity(target: PaneTarget): string {
  if (target.kind === 'NEW_SESSION_DRAFT') {
    return 'new-session'
  }
  if (target.kind === 'ENTRY_DRAFT') {
    return `entry:${target.sessionId}:${target.startEntryId}`
  }
  return `thread:${target.threadId}`
}

export function loadPaneTarget(
  owner: AgentRuntimeOwnerDTO,
  paneId: string,
  storage: Pick<Storage, 'getItem'> = globalThis.localStorage,
): PaneTarget {
  try {
    const raw = storage.getItem(storageKey(owner, paneId))
    if (!raw) {
      return { kind: 'NEW_SESSION_DRAFT' }
    }
    return normalizePaneTarget(JSON.parse(raw))
  } catch {
    return { kind: 'NEW_SESSION_DRAFT' }
  }
}

export function savePaneTarget(
  owner: AgentRuntimeOwnerDTO,
  paneId: string,
  target: PaneTarget,
  storage: Pick<Storage, 'setItem'> = globalThis.localStorage,
): void {
  try {
    storage.setItem(storageKey(owner, paneId), JSON.stringify(target))
  } catch {
    // Ignore quota or cross-origin storage errors.
  }
}

export function clearPaneTarget(
  owner: AgentRuntimeOwnerDTO,
  paneId: string,
  storage: Pick<Storage, 'removeItem'> = globalThis.localStorage,
): void {
  try {
    storage.removeItem(storageKey(owner, paneId))
  } catch {
    // Ignore storage errors.
  }
}

export function loadPendingAcceptance(
  owner: AgentRuntimeOwnerDTO,
  paneId: string,
  storage: Pick<Storage, 'getItem'> = globalThis.localStorage,
): PendingAcceptance | null {
  try {
    const raw = storage.getItem(pendingStorageKey(owner, paneId))
    if (!raw) {
      return null
    }
    const parsed: unknown = JSON.parse(raw)
    if (!isPendingAcceptanceValue(parsed)) {
      return null
    }
    if (parsed.owner.type !== owner.type || parsed.owner.id !== owner.id) {
      return null
    }
    return parsed
  } catch {
    return null
  }
}

export function savePendingAcceptance(
  owner: AgentRuntimeOwnerDTO,
  paneId: string,
  pending: PendingAcceptance,
  storage: Pick<Storage, 'setItem'> = globalThis.localStorage,
): void {
  try {
    storage.setItem(pendingStorageKey(owner, paneId), JSON.stringify(pending))
  } catch {
    // Ignore quota errors.
  }
}

export function clearPendingAcceptance(
  owner: AgentRuntimeOwnerDTO,
  paneId: string,
  storage: Pick<Storage, 'removeItem'> = globalThis.localStorage,
): void {
  try {
    storage.removeItem(pendingStorageKey(owner, paneId))
  } catch {
    // Ignore storage errors.
  }
}
