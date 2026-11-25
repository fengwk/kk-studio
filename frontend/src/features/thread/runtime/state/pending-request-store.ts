import type { HarnessThreadCommandBatchDTO } from '@/shared/api/contracts/ai-runtime'
import type { BranchDraft } from '@/features/thread/thread-settings'
import type { ComposerPart } from '@/features/thread/composer/composer-parts'
import { hasExactKeys, isRecord, nonBlank } from '@/shared/lib/value'
import {
  isBranchDraft,
  isComposerPart,
  isThreadCommandBatchRequest,
} from '@/features/thread/runtime/state/thread-message-validation'

/**
 * 既有 Thread 的一次未决消息写入。它只依赖 Thread 的 CAS 游标，
 * 不携带 owner；发送前持久化，未知结果后保留，刷新/重绑后按 thread 恢复精确重试，
 * 并在确定失败后把内容恢复到 composer。
 */
export interface BoundPendingMessage {
  threadId: string
  request: HarnessThreadCommandBatchDTO
  targetDraft: BranchDraft
  localDraft: ComposerPart[]
  unknownOutcome: boolean
}

const BOUND_PENDING_STORAGE_PREFIX = 'kk-studio.agent-thread-pending.'

function boundPendingStorageKey(threadId: string): string {
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
      'localDraft',
      'unknownOutcome',
    ])
    && nonBlank(value.threadId)
    && typeof value.unknownOutcome === 'boolean'
    && isThreadCommandBatchRequest(value.request)
    && isBranchDraft(value.targetDraft)
    && Array.isArray(value.localDraft)
    && value.localDraft.every(isComposerPart)
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
    let existing: unknown = null
    try {
      existing = JSON.parse(existingRaw)
    } catch {
      // 坏数据忽略，允许覆盖
    }
    if (isBoundPendingMessageValue(existing)) {
      if (!sameBatchRequestIdentity(existing.request, pending.request)) {
        throw new Error('Conflict: another pending message exists for this thread')
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
