import type { ComposerPart } from '@/features/chat/composer/composer-parts'
import type { BranchDraft } from '@/features/chat/branch-draft'
import type { PendingAcceptance } from '@/features/chat/runtime/pending-request-store'
import { isCanonicalThreadName } from '@/features/chat/thread-name'
import type {
  AgentCommandTargetDTO,
  AgentRuntimeOwnerDTO,
  HarnessCommandBatchDTO,
  HarnessCommandCreateDTO,
  HarnessThreadCommandBatchDTO,
  ImageInputTier,
} from '@/shared/api/contracts/ai-runtime'
import type {
  HarnessBranchSettingsDTO,
  HarnessGoalSettingDTO,
  HarnessModelSelectionDTO,
} from '@/shared/api/generated/contracts'

export type PaneTarget =
  | { kind: 'NEW_SESSION_DRAFT' }
  | { kind: 'NEW_THREAD_DRAFT'; sessionId: string; startEntryId: string; threadName: string }
  | { kind: 'FORK_SESSION_DRAFT'; sessionId: string; sourceThreadId: string; startEntryId: string }
  | { kind: 'BOUND_THREAD'; threadId: string }

export type PaneTargetKind = PaneTarget['kind']

export function ownerIdentity(owner: AgentRuntimeOwnerDTO): string {
  return owner.type === 'CHAT'
    ? `CHAT:${owner.chatId}`
    : `ISSUE_AGENT:${owner.issueId}:${owner.agentName}`
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

export function nonBlank(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function hasExactKeys(value: Record<string, unknown>, expected: readonly string[]): boolean {
  const keys = Object.keys(value)
  return keys.length === expected.length && keys.every((key) => expected.includes(key))
}

function isEnvironmentName(value: unknown): value is string | null {
  return value === null
    || (
      typeof value === 'string'
      && value.length > 0
      && value.length <= 64
      && value === value.trim()
      && !value.includes('/')
    )
}

function isOwner(value: unknown): value is AgentRuntimeOwnerDTO {
  return isRecord(value) && (
    (value.type === 'CHAT' && hasExactKeys(value, ['type', 'chatId']) && nonBlank(value.chatId))
    || (value.type === 'ISSUE_AGENT'
      && hasExactKeys(value, ['type', 'issueId', 'agentName'])
      && nonBlank(value.issueId) && nonBlank(value.agentName))
  )
}

function isModelSelection(value: unknown): value is HarnessModelSelectionDTO {
  if (!isRecord(value) || !nonBlank(value.modelName)) {
    return false
  }
  const keys = Object.keys(value)
  if (!keys.every((key) => key === 'modelName' || key === 'variant')) {
    return false
  }
  // 组显式使用 null/缺省 variant；单 Model 的 variant 若出现必须是非空白字符串。
  return value.variant === undefined || value.variant === null || nonBlank(value.variant)
}

function isGoalSetting(value: unknown): value is HarnessGoalSettingDTO {
  return isRecord(value)
    && hasExactKeys(value, ['id', 'text'])
    && nonBlank(value.id)
    && nonBlank(value.text)
}

export function isCanonicalGoalText(text: unknown): text is string {
  if (typeof text !== 'string') return false
  if (text.trim().length === 0) return false
  if (text !== text.trim()) return false
  return Array.from(text).length <= 2000
}

function isBranchSettings(value: unknown): value is HarnessBranchSettingsDTO {
  if (!isRecord(value)) {
    return false
  }
  const keys = Object.keys(value)
  if (
    keys.length !== 4
    || !keys.every((key) => key === 'agentName' || key === 'model' || key === 'environmentName' || key === 'goal')
  ) {
    return false
  }
  return nonBlank(value.agentName)
    && isModelSelection(value.model)
    && isEnvironmentName(value.environmentName)
    && (value.goal === null || isGoalSetting(value.goal))
}

function isCommandTarget(value: unknown): value is AgentCommandTargetDTO {
  if (!isRecord(value) || typeof value.type !== 'string') {
    return false
  }
  // exact own-key 校验（也覆盖 rootSettings/contents 等嵌套对象）。
  const keys = Object.keys(value)
  if (value.type === 'NEW_SESSION') {
    return keys.length === 5
      && keys.every((key) => key === 'type' || key === 'sessionId' || key === 'threadId'
        || key === 'rootSettings' || key === 'yoloEnabled')
      && nonBlank(value.sessionId)
      && nonBlank(value.threadId)
      && isBranchSettings(value.rootSettings)
      && typeof value.yoloEnabled === 'boolean'
  }
  if (value.type === 'NEW_THREAD') {
    return keys.length === 6
      && keys.every((key) => key === 'type' || key === 'sessionId' || key === 'startEntryId'
        || key === 'threadId' || key === 'threadName' || key === 'yoloEnabled')
      && nonBlank(value.sessionId)
      && nonBlank(value.startEntryId)
      && nonBlank(value.threadId)
      && isCanonicalThreadName(value.threadName)
      && typeof value.yoloEnabled === 'boolean'
  }
  if (value.type === 'NEW_FORKED_SESSION') {
    return keys.length === 6
      && keys.every((key) => key === 'type' || key === 'sourceThreadId' || key === 'startEntryId'
        || key === 'sessionId' || key === 'threadId' || key === 'yoloEnabled')
      && nonBlank(value.sourceThreadId)
      && nonBlank(value.startEntryId)
      && nonBlank(value.sessionId)
      && nonBlank(value.threadId)
      && typeof value.yoloEnabled === 'boolean'
  }
  return false
}

export function isImageTier(value: unknown): value is ImageInputTier {
  return value === '720P' || value === '1080P' || value === 'ORIGINAL'
}

export function isCommandContent(value: unknown): boolean {
  if (!isRecord(value) || typeof value.type !== 'string') {
    return false
  }
  if (value.type === 'TEXT') {
    return hasExactKeys(value, ['type', 'text'])
      && typeof value.text === 'string'
  }
  if (value.type === 'ATTACHMENT') {
    const validKeys =
      hasExactKeys(value, ['type', 'uploadId'])
      || (hasExactKeys(value, ['type', 'uploadId', 'imageTier']) && isImageTier(value.imageTier))
    return validKeys && nonBlank(value.uploadId)
  }
  if (value.type === 'RESOURCE') {
    const validKeys =
      hasExactKeys(value, ['type', 'blobId', 'name'])
      || hasExactKeys(value, ['type', 'blobId', 'name', 'preview'])
      || (hasExactKeys(value, ['type', 'blobId', 'name', 'imageTier']) && isImageTier(value.imageTier))
      || (hasExactKeys(value, ['type', 'blobId', 'name', 'preview', 'imageTier']) && isImageTier(value.imageTier))
    return validKeys
      && nonBlank(value.blobId)
      && nonBlank(value.name)
      && (value.preview == null || typeof value.preview === 'string')
  }
  return false
}

function isCommand(value: unknown): value is HarnessCommandCreateDTO {
  if (!isRecord(value) || !nonBlank(value.type) || !nonBlank(value.idempotencyKey)) {
    return false
  }
  switch (value.type) {
    case 'USER_MESSAGE':
      return hasExactKeys(value, ['type', 'idempotencyKey', 'contents'])
        && Array.isArray(value.contents)
        && value.contents.length > 0
        && value.contents.every(isCommandContent)
    case 'GOAL':
      return hasExactKeys(value, ['type', 'idempotencyKey', 'text'])
        && (value.text === null || isCanonicalGoalText(value.text))
    case 'SET_AGENT':
      return hasExactKeys(value, ['type', 'idempotencyKey', 'agentName'])
        && nonBlank(value.agentName)
    case 'SET_MODEL':
      return hasExactKeys(value, ['type', 'idempotencyKey', 'model'])
        && isModelSelection(value.model)
    case 'SET_ENVIRONMENT':
      return hasExactKeys(value, ['type', 'idempotencyKey', 'environmentName'])
        && isEnvironmentName(value.environmentName)
    default:
      return false
  }
}

function isCommandBatchRequest(value: unknown): value is HarnessCommandBatchDTO {
  return isRecord(value)
    && isOwner(value.owner)
    && isCommandTarget(value.target)
    && Array.isArray(value.commands)
    && value.commands.length > 0
    && value.commands.every(isCommand)
}

function isThreadCommandBatchRequest(value: unknown): value is HarnessThreadCommandBatchDTO {
  if (!isRecord(value) || !Array.isArray(value.commands) || value.commands.length === 0) {
    return false
  }
  if (!value.commands.every(isCommand)) {
    return false
  }
  const keys = Object.keys(value)
  const cursorFree = value.commands.length === 1 && value.commands[0]?.type === 'USER_MESSAGE'
  if (keys.length === 1 && keys[0] === 'commands') {
    return cursorFree
  }
  return !cursorFree
    && hasExactKeys(value, ['expectedHeadEntryId', 'expectedNextCommandSequence', 'commands'])
    && nonBlank(value.expectedHeadEntryId)
    && nonBlank(value.expectedNextCommandSequence)
}

function isBranchDraft(value: unknown): value is BranchDraft {
  if (!isRecord(value)) {
    return false
  }
  const keys = Object.keys(value)
  if (
    keys.length !== 4
    || !keys.every((key) => key === 'agentName' || key === 'model' || key === 'environmentName' || key === 'yoloEnabled')
  ) {
    return false
  }
  return nonBlank(value.agentName)
    && isModelSelection(value.model)
    && isEnvironmentName(value.environmentName)
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
    return nonBlank(value.uploadId)
      && typeof value.filename === 'string'
      && (value.imageTier === undefined || isImageTier(value.imageTier))
  }
  return value.type === 'resource'
    && nonBlank(value.blobId)
    && nonBlank(value.name)
    && (value.preview === undefined || typeof value.preview === 'string')
    && (value.imageTier === undefined || isImageTier(value.imageTier))
}

export function isPendingAcceptanceValue(value: unknown): value is PendingAcceptance {
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

export {
  isCommandBatchRequest,
  isThreadCommandBatchRequest,
  isBranchDraft,
  isComposerPart,
}

export function isPaneTarget(value: unknown): value is PaneTarget {
  if (!isRecord(value) || typeof value.kind !== 'string') {
    return false
  }
  // exact own-key 校验：持久化形状只接受恰好这些 key；名称字段或未知字段一律拒绝。
  const keys = Object.keys(value)
  if (value.kind === 'NEW_SESSION_DRAFT') {
    return keys.length === 1 && keys[0] === 'kind'
  }
  if (value.kind === 'NEW_THREAD_DRAFT') {
    return keys.length === 4
      && keys.every((key) => key === 'kind' || key === 'sessionId' || key === 'startEntryId'
        || key === 'threadName')
      && nonBlank(value.sessionId)
      && nonBlank(value.startEntryId)
      && isCanonicalThreadName(value.threadName)
  }
  if (value.kind === 'FORK_SESSION_DRAFT') {
    return keys.length === 4
      && keys.every((key) => key === 'kind' || key === 'sessionId' || key === 'sourceThreadId'
        || key === 'startEntryId')
      && nonBlank(value.sessionId)
      && nonBlank(value.sourceThreadId)
      && nonBlank(value.startEntryId)
  }
  if (value.kind === 'BOUND_THREAD') {
    return keys.length === 2
      && keys.every((key) => key === 'kind' || key === 'threadId')
      && nonBlank(value.threadId)
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
  if (value.kind === 'NEW_THREAD_DRAFT') {
    return {
      kind: value.kind,
      sessionId: value.sessionId.trim(),
      startEntryId: value.startEntryId.trim(),
      threadName: value.threadName,
    }
  }
  if (value.kind === 'FORK_SESSION_DRAFT') {
    return {
      kind: value.kind,
      sessionId: value.sessionId.trim(),
      sourceThreadId: value.sourceThreadId.trim(),
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
  if (left.kind === 'NEW_THREAD_DRAFT' && right.kind === 'NEW_THREAD_DRAFT') {
    return left.sessionId === right.sessionId
      && left.startEntryId === right.startEntryId
      && left.threadName === right.threadName
  }
  if (left.kind === 'FORK_SESSION_DRAFT' && right.kind === 'FORK_SESSION_DRAFT') {
    return left.sessionId === right.sessionId
      && left.sourceThreadId === right.sourceThreadId
      && left.startEntryId === right.startEntryId
  }
  if (left.kind === 'BOUND_THREAD' && right.kind === 'BOUND_THREAD') {
    return left.threadId === right.threadId
  }
  return false
}

export function isNewSessionTarget(target: PaneTarget): target is { kind: 'NEW_SESSION_DRAFT' } {
  return target.kind === 'NEW_SESSION_DRAFT'
}

export function isNewThreadTarget(
  target: PaneTarget,
): target is { kind: 'NEW_THREAD_DRAFT'; sessionId: string; startEntryId: string; threadName: string } {
  return target.kind === 'NEW_THREAD_DRAFT'
}

export function isForkSessionTarget(
  target: PaneTarget,
): target is { kind: 'FORK_SESSION_DRAFT'; sessionId: string; sourceThreadId: string; startEntryId: string } {
  return target.kind === 'FORK_SESSION_DRAFT'
}

/**
 * 承载历史路径的草稿目标：新建 Thread 分支（同 Session）与会话 fork（新 Session）都从
 * 选定切点读取有效历史并据此初始化草稿，因此共用同一套 entries 读取与路径校验。
 */
export function isDraftHistoryTarget(
  target: PaneTarget,
): target is
  | { kind: 'NEW_THREAD_DRAFT'; sessionId: string; startEntryId: string; threadName: string }
  | { kind: 'FORK_SESSION_DRAFT'; sessionId: string; sourceThreadId: string; startEntryId: string } {
  return target.kind === 'NEW_THREAD_DRAFT' || target.kind === 'FORK_SESSION_DRAFT'
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
  if (target.kind === 'NEW_THREAD_DRAFT') {
    return `thread-draft:${target.sessionId}:${target.startEntryId}:${target.threadName}`
  }
  if (target.kind === 'FORK_SESSION_DRAFT') {
    return `fork-session-draft:${target.sessionId}:${target.sourceThreadId}:${target.startEntryId}`
  }
  return `thread:${target.threadId}`
}

