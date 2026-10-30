import type {
  HarnessCommandBatchDTO,
  HarnessThreadCommandBatchDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { AgentRuntimeOwnerDTO } from '@/shared/api/contracts/ai-runtime'
import type { BranchDraft } from '@/features/chat/branch-draft'
import type { ComposerPart } from '@/features/chat/composer/composer-parts'
import type { PaneTarget } from '@/features/chat/runtime/agent-pane/pane-target'
import {
  isBranchDraft,
  isComposerPart,
  isPendingAcceptanceValue,
  isThreadCommandBatchRequest,
  nonBlank,
  ownerIdentity,
  samePaneTarget,
} from '@/features/chat/runtime/agent-pane/pane-target'

/**
 * 容器创建（NEW_SESSION / NEW_THREAD / FORK_SESSION）的未决接受请求。
 * 只有容器创建才携带产品 owner。
 */
export interface PendingAcceptance {
  owner: AgentRuntimeOwnerDTO
  target: PaneTarget
  request: HarnessCommandBatchDTO
  branchDraft: BranchDraft
  composerParts: ComposerPart[]
  generation: number
  unknownOutcome: boolean
}

/**
 * 既有 Thread 的一次未决写入（普通消息或 Goal）。它只依赖 Thread 的 CAS 游标，
 * 不携带 owner；发送前持久化，未知结果后保留，刷新/重绑后按 thread 恢复精确重试，
 * 并按 kind 把内容恢复到对应编辑区（消息 → composer，目标 → Goal 编辑区）。
 */
export interface BoundPendingMessage {
  threadId: string
  request: HarnessThreadCommandBatchDTO
  targetDraft: BranchDraft
  kind: 'MESSAGE' | 'GOAL'
  localDraft: ComposerPart[]
  goalText: string | null
  unknownOutcome: boolean
}

/**
 * 统一待决请求的可区分变体（discriminated variant）：
 * - acceptance: 未绑定目标的新建/分叉请求，由 (owner, paneId) 槽位寻址；
 * - bound_message: 既有 Thread 上的消息或目标请求，由 threadId 槽位寻址。
 * 两者代表不同的精确重放事实，禁止跨槽位混淆或重复拥有。
 */
export type PendingRequest =
  | ({ variant: 'acceptance' } & PendingAcceptance)
  | ({ variant: 'bound_message' } & BoundPendingMessage)

export type PendingAcceptanceSlot =
  | { status: 'empty' }
  | { status: 'occupied'; pending: PendingAcceptance }
  | { status: 'invalid' }
  | { status: 'unavailable' }

const ACCEPTANCE_STORAGE_PREFIX = 'kk-studio.agent-pane-acceptance.'
const BOUND_PENDING_STORAGE_PREFIX = 'kk-studio.agent-thread-pending.'

export function acceptanceStorageKey(owner: AgentRuntimeOwnerDTO, paneId: string): string {
  return `${ACCEPTANCE_STORAGE_PREFIX}${ownerIdentity(owner)}:${paneId}`
}

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

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function hasExactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const ownKeys = Object.keys(value)
  return ownKeys.length === keys.length && keys.every((key) => ownKeys.includes(key))
}

export { isPendingAcceptanceValue }

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

export function samePendingAcceptanceIdentity(
  left: PendingAcceptance,
  right: PendingAcceptance,
): boolean {
  return left.owner.type === right.owner.type
    && ownerIdentity(left.owner) === ownerIdentity(right.owner)
    && ownerIdentity(left.request.owner) === ownerIdentity(right.request.owner)
    && samePaneTarget(left.target, right.target)
    && sameBatchRequestIdentity(left.request, right.request)
}

export function sameBatchRequestIdentity(
  left: { commands: readonly { idempotencyKey: string }[] },
  right: { commands: readonly { idempotencyKey: string }[] },
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

/**
 * 按 owner/pane 槽位读取原始 pending acceptance，不按当前展示目标过滤。
 * 槽位被损坏/不可读取时返回 invalid/unavailable，调用方阻塞新提交以保护现场。
 */
export function inspectPendingAcceptanceSlot(
  owner: AgentRuntimeOwnerDTO,
  paneId: string,
  storage?: Pick<Storage, 'getItem'>,
): PendingAcceptanceSlot {
  const resolved = resolveStorage(storage)
  if (resolved == null) {
    return { status: 'unavailable' }
  }
  try {
    const raw = resolved.getItem(acceptanceStorageKey(owner, paneId))
    if (!raw) {
      return { status: 'empty' }
    }
    let parsed: unknown
    try {
      parsed = JSON.parse(raw)
    } catch {
      return { status: 'invalid' }
    }
    if (
      !isPendingAcceptanceValue(parsed)
      || parsed.owner.type !== owner.type
      || ownerIdentity(parsed.owner) !== ownerIdentity(owner)
      || ownerIdentity(parsed.request.owner) !== ownerIdentity(owner)
    ) {
      return { status: 'invalid' }
    }
    return { status: 'occupied', pending: parsed }
  } catch {
    return { status: 'unavailable' }
  }
}

export function loadPendingAcceptance(
  owner: AgentRuntimeOwnerDTO,
  paneId: string,
  storage?: Pick<Storage, 'getItem'>,
  expectedTarget?: PaneTarget,
): PendingAcceptance | null {
  const slot = inspectPendingAcceptanceSlot(owner, paneId, storage)
  if (slot.status !== 'occupied') {
    return null
  }
  if (expectedTarget != null && !samePaneTarget(slot.pending.target, expectedTarget)) {
    return null
  }
  return slot.pending
}

/**
 * 写入 pending acceptance：防止同一槽位被不同请求身份覆盖（防重复拥有/重复持久化）。
 */
export function savePendingAcceptance(
  owner: AgentRuntimeOwnerDTO,
  paneId: string,
  pending: PendingAcceptance,
  storage: Pick<Storage, 'getItem' | 'setItem'> = globalThis.localStorage,
): boolean {
  const resolved = resolveStorage(storage)
  if (
    resolved == null
    || ownerIdentity(pending.owner) !== ownerIdentity(owner)
    || ownerIdentity(pending.request.owner) !== ownerIdentity(owner)
  ) {
    return false
  }
  const slot = inspectPendingAcceptanceSlot(owner, paneId, resolved)
  if (slot.status === 'invalid' || slot.status === 'unavailable') {
    return false
  }
  if (slot.status === 'occupied' && !samePendingAcceptanceIdentity(slot.pending, pending)) {
    return false
  }
  try {
    resolved.setItem(acceptanceStorageKey(owner, paneId), JSON.stringify(pending))
    return true
  } catch {
    return false
  }
}

/**
 * 仅清除与 expected 严格匹配的 pending acceptance，避免误删属于其他操作的对账记录。
 */
export function clearPendingAcceptance(
  owner: AgentRuntimeOwnerDTO,
  paneId: string,
  expected: PendingAcceptance,
  storage: Pick<Storage, 'getItem' | 'removeItem'> = globalThis.localStorage,
): boolean {
  const resolved = resolveStorage(storage)
  if (resolved == null) {
    return false
  }
  const slot = inspectPendingAcceptanceSlot(owner, paneId, resolved)
  if (slot.status !== 'occupied' || !samePendingAcceptanceIdentity(slot.pending, expected)) {
    return false
  }
  try {
    resolved.removeItem(acceptanceStorageKey(owner, paneId))
    return true
  } catch {
    return false
  }
}

/**
 * 读取既有 Thread 的未决消息。
 * 若刷新发生在请求在途中（unknownOutcome 尚未置 true），按未知结果恢复以允许重试。
 */
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
        // ignore storage clean error
      }
      return null
    }
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

/**
 * 持久化既有 Thread 的未决消息：
 * 严格保护同一 Thread 的单一所有权，若已有属于不同 request 身份的记录，抛出 Conflict 错误；
 * 写入后执行回读验证，失败抛错。
 */
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
    }
  }
  const serialized = JSON.stringify(pending)
  resolved.setItem(key, serialized)
  const stored = resolved.getItem(key)
  if (stored !== serialized) {
    throw new Error('Persistence verification failed for bound pending message')
  }
}

/**
 * 清除既有 Thread 的未决消息。
 * 若提供了 matchingRequest，仅在请求身份严格匹配时清除，保护多 pane / 交错场景。
 */
export function clearBoundPendingMessage(
  threadId: string,
  matchingRequest?: HarnessThreadCommandBatchDTO,
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
            return false
          }
        }
      } catch {
        // malformed data can be removed
      }
    }
    resolved.removeItem(key)
    return true
  } catch {
    return false
  }
}
