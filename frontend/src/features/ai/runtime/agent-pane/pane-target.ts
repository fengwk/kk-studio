import type { ComposerPart } from '@/features/ai/composer/composer-parts'
import type { BranchDraft } from '@/features/ai/chat/branch-draft'
import type {
  AgentCommandBatchRequestDTO,
  AgentRuntimeOwnerDTO,
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

export function loadPaneTarget(
  owner: AgentRuntimeOwnerDTO,
  paneId: string,
  storage: Storage = localStorage,
): PaneTarget {
  try {
    const raw = storage.getItem(storageKey(owner, paneId))
    return normalizePaneTarget(raw == null ? null : JSON.parse(raw))
  } catch {
    return { kind: 'NEW_SESSION_DRAFT' }
  }
}

export function savePaneTarget(
  owner: AgentRuntimeOwnerDTO,
  paneId: string,
  target: PaneTarget,
  storage: Storage = localStorage,
): void {
  try {
    storage.setItem(storageKey(owner, paneId), JSON.stringify(normalizePaneTarget(target)))
  } catch {
    // Storage is an optimization; pane navigation remains usable when it is unavailable.
  }
}

export function clearPaneTarget(
  owner: AgentRuntimeOwnerDTO,
  paneId: string,
  storage: Storage = localStorage,
): void {
  try {
    storage.removeItem(storageKey(owner, paneId))
  } catch {
    // Ignore privacy-mode storage failures.
  }
}

export function loadPendingAcceptance(
  owner: AgentRuntimeOwnerDTO,
  paneId: string,
  storage: Storage = localStorage,
): PendingAcceptance | null {
  try {
    const raw = storage.getItem(pendingStorageKey(owner, paneId))
    if (raw == null) {
      return null
    }
    const value: unknown = JSON.parse(raw)
    if (!isRecord(value) || !isPaneTarget(value.target) || !isRecord(value.request)) {
      return null
    }
    if (!Array.isArray(value.composerParts) || !isRecord(value.branchDraft)) {
      return null
    }
    return value as unknown as PendingAcceptance
  } catch {
    return null
  }
}

export function savePendingAcceptance(
  owner: AgentRuntimeOwnerDTO,
  paneId: string,
  pending: PendingAcceptance,
  storage: Storage = localStorage,
): void {
  try {
    storage.setItem(pendingStorageKey(owner, paneId), JSON.stringify(pending))
  } catch {
    // A request still retains its in-memory exact replay when persistence is unavailable.
  }
}

export function clearPendingAcceptance(
  owner: AgentRuntimeOwnerDTO,
  paneId: string,
  storage: Storage = localStorage,
): void {
  try {
    storage.removeItem(pendingStorageKey(owner, paneId))
  } catch {
    // Ignore privacy-mode storage failures.
  }
}

export function targetIdentity(target: PaneTarget): string {
  return JSON.stringify(target)
}

export function samePaneTarget(left: PaneTarget, right: PaneTarget): boolean {
  return targetIdentity(left) === targetIdentity(right)
}

export function isBoundTarget(target: PaneTarget): target is { kind: 'BOUND_THREAD'; threadId: string } {
  return target.kind === 'BOUND_THREAD'
}

export function isEntryTarget(
  target: PaneTarget,
): target is { kind: 'ENTRY_DRAFT'; sessionId: string; startEntryId: string } {
  return target.kind === 'ENTRY_DRAFT'
}

export function isNewSessionTarget(target: PaneTarget): target is { kind: 'NEW_SESSION_DRAFT' } {
  return target.kind === 'NEW_SESSION_DRAFT'
}
