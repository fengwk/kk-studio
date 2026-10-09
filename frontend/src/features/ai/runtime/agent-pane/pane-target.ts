import type { ComposerPart } from '@/features/ai/composer/composer-parts'
import type { BranchDraft } from '@/features/ai/chat/branch-draft'
import { isCanonicalThreadName } from '@/features/ai/chat/thread-name'
import type {
  AgentCommandTargetDTO,
  AgentCommandBatchRequestDTO,
  AgentRuntimeOwnerDTO,
  HarnessBranchSettingsDTO,
  HarnessCommandCreateDTO,
  HarnessGoalSettingDTO,
  HarnessModelSelectionDTO,
  ImageInputTier,
  ThreadCommandBatchRequestDTO,
} from '@/shared/api/contracts/ai-runtime'

export type PaneTarget =
  | { kind: 'NEW_SESSION_DRAFT' }
  | { kind: 'NEW_THREAD_DRAFT'; sessionId: string; startEntryId: string; threadName: string }
  | { kind: 'FORK_SESSION_DRAFT'; sessionId: string; sourceThreadId: string; startEntryId: string }
  | { kind: 'BOUND_THREAD'; threadId: string }

export type PaneTargetKind = PaneTarget['kind']

/**
 * 容器创建（NEW_SESSION / NEW_THREAD）的未决接受：只有创建才需要产品 owner。
 * 既有 Thread 的发送、Goal、设置、预览等一律走 per-thread 的
 * {@link BoundPendingMessage}，不再伪造 owner。
 */
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

export function ownerIdentity(owner: AgentRuntimeOwnerDTO): string {
  return owner.type === 'CHAT'
    ? `CHAT:${owner.chatId}`
    : `ISSUE_AGENT:${owner.issueId}:${owner.agentName}`
}

function storageKey(owner: AgentRuntimeOwnerDTO, paneId: string): string {
  return `${TARGET_STORAGE_PREFIX}${ownerIdentity(owner)}:${paneId}`
}

function pendingStorageKey(owner: AgentRuntimeOwnerDTO, paneId: string): string {
  return `${PENDING_STORAGE_PREFIX}${ownerIdentity(owner)}:${paneId}`
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function nonBlank(value: unknown): value is string {
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
  return isRecord(value)
    && hasExactKeys(value, ['providerName', 'modelName', 'variant'])
    && nonBlank(value.providerName)
    && nonBlank(value.modelName)
    && nonBlank(value.variant)
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

function isCommandBatchRequest(value: unknown): value is AgentCommandBatchRequestDTO {
  return isRecord(value)
    && isOwner(value.owner)
    && isCommandTarget(value.target)
    && Array.isArray(value.commands)
    && value.commands.length > 0
    && value.commands.every(isCommand)
}

function isThreadCommandBatchRequest(value: unknown): value is ThreadCommandBatchRequestDTO {
  return isRecord(value)
    && hasExactKeys(value, ['expectedHeadEntryId', 'expectedNextCommandSequence', 'commands'])
    && nonBlank(value.expectedHeadEntryId)
    && nonBlank(value.expectedNextCommandSequence)
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
    if (
      parsed.owner.type !== owner.type
      || ownerIdentity(parsed.owner) !== ownerIdentity(owner)
      || ownerIdentity(parsed.request.owner) !== ownerIdentity(owner)
    ) {
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

/**
 * 既有 Thread 的一次未决写入（普通消息或 Goal）。它只依赖 Thread 的 CAS 游标，
 * 不携带 owner；发送前持久化，未知结果后保留，刷新/重绑后按 thread 恢复精确重试，
 * 并按 kind 把内容恢复到对应编辑区（消息 → composer，目标 → Goal 编辑区）。
 */
export interface BoundPendingMessage {
  threadId: string
  request: ThreadCommandBatchRequestDTO
  targetDraft: BranchDraft
  kind: 'MESSAGE' | 'GOAL'
  localDraft: ComposerPart[]
  goalText: string | null
  unknownOutcome: boolean
}

const BOUND_PENDING_STORAGE_PREFIX = 'kk-studio.agent-thread-pending.'

export function boundPendingStorageKey(threadId: string): string {
  return `${BOUND_PENDING_STORAGE_PREFIX}${threadId}`
}

function resolveStorage(
  injected?: Partial<Storage>,
): Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> | null {
  if (injected) {
    return injected as Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
  }
  try {
    if (typeof globalThis !== 'undefined' && globalThis.localStorage) {
      return globalThis.localStorage
    }
  } catch {
    return null
  }
  return null
}

export function isBoundPendingMessageValue(value: unknown): value is BoundPendingMessage {
  return isRecord(value)
    && hasExactKeys(value, [
      'threadId',
      'request',
      'targetDraft',
      'kind',
      'localDraft',
      'goalText',
      'unknownOutcome',
    ])
    && nonBlank(value.threadId)
    && (value.kind === 'MESSAGE' || value.kind === 'GOAL')
    && typeof value.unknownOutcome === 'boolean'
    && isThreadCommandBatchRequest(value.request)
    && isBranchDraft(value.targetDraft)
    && Array.isArray(value.localDraft)
    && value.localDraft.every(isComposerPart)
    && (value.goalText === null || typeof value.goalText === 'string')
}

export function sameBatchRequestIdentity(
  left: ThreadCommandBatchRequestDTO,
  right: ThreadCommandBatchRequestDTO,
): boolean {
  if (left === right) {
    return true
  }
  if (left.commands.length !== right.commands.length) {
    return false
  }
  return left.commands.every(
    (cmd, index) => cmd.idempotencyKey === right.commands[index]?.idempotencyKey,
  )
}

export function loadBoundPendingMessage(
  threadId: string,
  storage?: Pick<Storage, 'getItem' | 'removeItem' | 'setItem'>,
): BoundPendingMessage | null {
  const resolved = resolveStorage(storage)
  if (!resolved || !nonBlank(threadId)) {
    return null
  }
  const key = boundPendingStorageKey(threadId)
  try {
    const raw = resolved.getItem(key)
    if (!raw) {
      return null
    }
    const parsed: unknown = JSON.parse(raw)
    if (!isBoundPendingMessageValue(parsed) || parsed.threadId !== threadId) {
      try {
        resolved.removeItem(key)
      } catch {
        // Ignore storage errors.
      }
      return null
    }
    // 重点：刷新发生 POST 已发但还没有 catch 标 unknown 时，load 必须按未知可重试恢复，
    // 避免因为 unknownOutcome=false 造成界面永远无重试按钮而死锁。
    if (!parsed.unknownOutcome) {
      const normalized: BoundPendingMessage = {
        ...parsed,
        unknownOutcome: true,
      }
      try {
        resolved.setItem(key, JSON.stringify(normalized))
      } catch {
        // best-effort writeback
      }
      return normalized
    }
    return parsed
  } catch {
    return null
  }
}

export function saveBoundPendingMessage(
  threadId: string,
  pending: BoundPendingMessage,
  storage?: Pick<Storage, 'setItem' | 'getItem'>,
): void {
  const resolved = resolveStorage(storage)
  if (!resolved) {
    throw new Error('Storage is unavailable')
  }
  if (!nonBlank(threadId)) {
    throw new Error('threadId is required to persist bound pending message')
  }
  if (!isBoundPendingMessageValue(pending) || pending.threadId !== threadId) {
    throw new Error('Invalid bound pending message shape')
  }
  const key = boundPendingStorageKey(threadId)
  // 检查已有未决状态：若同 thread 已有不同 request 身份的未决记录，拒绝覆盖以保护多 pane / 交错场景
  const existingRaw = resolved.getItem(key)
  if (existingRaw) {
    try {
      const existing = JSON.parse(existingRaw)
      if (isBoundPendingMessageValue(existing)) {
        if (!sameBatchRequestIdentity(existing.request, pending.request)) {
          throw new Error('Conflict: another pending message exists for this thread')
        }
      }
    } catch (err) {
      if (err instanceof Error && err.message.startsWith('Conflict:')) {
        throw err
      }
      // 损坏数据允许覆写修复
    }
  }
  const serialized = JSON.stringify(pending)
  resolved.setItem(key, serialized)
  const stored = resolved.getItem(key)
  if (stored !== serialized) {
    throw new Error('Persistence verification failed for bound pending message')
  }
}

export function clearBoundPendingMessage(
  threadId: string,
  matchingRequest?: ThreadCommandBatchRequestDTO,
  storage?: Pick<Storage, 'getItem' | 'removeItem'>,
): boolean {
  const resolved = resolveStorage(storage)
  if (!resolved || !nonBlank(threadId)) {
    return true
  }
  const key = boundPendingStorageKey(threadId)
  try {
    if (matchingRequest != null) {
      const raw = resolved.getItem(key)
      if (!raw) {
        return true
      }
      try {
        const parsed = JSON.parse(raw) as { request?: unknown }
        if (parsed && isThreadCommandBatchRequest(parsed.request)) {
          if (!sameBatchRequestIdentity(parsed.request, matchingRequest)) {
            // multi-pane 同 thread pending：避免清理属于另一个请求身份的 pending
            return false
          }
        }
      } catch {
        // 如果 JSON 解析失败则为损坏数据，允许删除
      }
    }
    resolved.removeItem(key)
    return true
  } catch {
    return false
  }
}
