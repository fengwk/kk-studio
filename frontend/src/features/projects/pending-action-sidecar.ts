import { ApiError } from '@/shared/api/client'

const STORAGE_PREFIX = 'kkstudio.projects.pending-action.v1:'

export type IssueActionKind =
  | 'TRANSITION'
  | 'RECOVER'
  | 'REOPEN'
  | 'BLOCK'
  | 'RESOLVE_UNKNOWN'
  | 'STOP'
  | 'RESET_BUDGET'
  | 'ACTIVITY'

// 1. 严格 discriminated union 定义 8 种操作的精确 payload 与结构
export interface TransitionPendingAction {
  issueId: string
  kind: 'TRANSITION'
  requestKey: string
  expectedVersion: string
  payload: { toState: string }
  createdAt: string
  isUnknown: boolean
}

export interface RecoverPendingAction {
  issueId: string
  kind: 'RECOVER'
  requestKey: string
  expectedVersion: string
  payload: Record<string, never>
  createdAt: string
  isUnknown: boolean
}

export interface ReopenPendingAction {
  issueId: string
  kind: 'REOPEN'
  requestKey: string
  expectedVersion: string
  payload: Record<string, never>
  createdAt: string
  isUnknown: boolean
}

export interface BlockPendingAction {
  issueId: string
  kind: 'BLOCK'
  requestKey: string
  expectedVersion: string
  payload: { reason: string }
  createdAt: string
  isUnknown: boolean
}

export interface ResolveUnknownPendingAction {
  issueId: string
  kind: 'RESOLVE_UNKNOWN'
  requestKey: string
  expectedVersion: string
  payload: { verification: string }
  createdAt: string
  isUnknown: boolean
}

export interface StopPendingAction {
  issueId: string
  kind: 'STOP'
  requestKey: string
  expectedVersion: string
  payload: { detail: string | null }
  createdAt: string
  isUnknown: boolean
}

export interface ResetBudgetPendingAction {
  issueId: string
  kind: 'RESET_BUDGET'
  requestKey: string
  expectedVersion: string
  payload: { state: string; maxRuns: number }
  createdAt: string
  isUnknown: boolean
}

export interface ActivityPendingAction {
  issueId: string
  kind: 'ACTIVITY'
  requestKey: string
  expectedVersion: string
  payload: { kind: 'COMMENT' | 'INSTRUCTION'; body: string }
  createdAt: string
  isUnknown: boolean
}

export type PendingIssueAction =
  | TransitionPendingAction
  | RecoverPendingAction
  | ReopenPendingAction
  | BlockPendingAction
  | ResolveUnknownPendingAction
  | StopPendingAction
  | ResetBudgetPendingAction
  | ActivityPendingAction

export type LoadPendingActionResult =
  | { type: 'NONE' }
  | { type: 'VALID'; action: PendingIssueAction }
  | { type: 'CORRUPT'; raw: string; error: string }
  | { type: 'STORAGE_ERROR'; error: string }

export function pendingActionStorageKey(issueId: string): string {
  return `${STORAGE_PREFIX}${issueId}`
}

function getSafeStorage(storage?: Storage): Storage {
  if (storage) {
    return storage
  }
  if (typeof window === 'undefined' || !window.localStorage) {
    throw new Error('localStorage is not available in the current environment')
  }
  return window.localStorage
}

/**
 * 发送前持久化到侧车。Storage 写入失败时必须 fail-closed（抛出异常），禁止无侧车保护直接发送网络请求。
 * 包含：
 * 1. 严格 schema 校验；
 * 2. 检查现有存储：若存在不同 requestKey 的动作或损坏记录，拒绝覆盖（防止 other pane pending 冲突）；
 * 3. 若同 requestKey 但 payload/version 不一致，拒绝覆盖；
 * 4. 写后回读验证（readback），验证序列化一致性。
 */
export function storePendingAction(
  issueId: string,
  action: PendingIssueAction,
  storage?: Storage,
): void {
  if (!issueId || !validAction(action, issueId)) {
    throw new Error('Invalid pending action payload or issueId')
  }

  let targetStorage: Storage
  try {
    targetStorage = getSafeStorage(storage)
  } catch (err) {
    throw new Error(
      `Storage unavailable: ${err instanceof Error ? err.message : String(err)}`,
      { cause: err },
    )
  }

  const key = pendingActionStorageKey(issueId)

  // 1. 读取并核查现有存储中的挂起状态
  let existingRaw: string | null
  try {
    existingRaw = targetStorage.getItem(key)
  } catch (err) {
    throw new Error(
      `Failed to read existing pending action before store: ${
        err instanceof Error ? err.message : String(err)
      }`,
      { cause: err },
    )
  }

  if (existingRaw != null) {
    let existingParsed: unknown
    try {
      existingParsed = JSON.parse(existingRaw)
    } catch {
      // 存储存在损坏数据，不得静默覆盖丢副作用，需用户显式放弃
      throw new Error(
        `Cannot overwrite corrupt pending action for issue ${issueId} before it is explicitly discarded`,
      )
    }

    if (!validAction(existingParsed, issueId)) {
      throw new Error(
        `Cannot overwrite invalid/foreign pending action for issue ${issueId} before it is explicitly discarded`,
      )
    }

    // 存在现有合法动作：校验 identity
    if (existingParsed.requestKey !== action.requestKey) {
      throw new Error(
        `Cannot overwrite existing pending action for issue ${issueId} with different identity (${existingParsed.requestKey} vs ${action.requestKey})`,
      )
    }

    // 相同 requestKey：校验 full body（kind, expectedVersion, payload 必须深度一致，禁止偷换）
    const isSameKind = existingParsed.kind === action.kind
    const isSameVersion = existingParsed.expectedVersion === action.expectedVersion
    const isSamePayload = isPayloadEqual(existingParsed.payload, action.payload)
    if (!isSameKind || !isSameVersion || !isSamePayload) {
      throw new Error(
        `Cannot overwrite pending action under same requestKey (${action.requestKey}) with different payload, kind, or version`,
      )
    }
  }

  // 2. 序列化写入
  const serialized = JSON.stringify(action)
  try {
    targetStorage.setItem(key, serialized)
  } catch (err) {
    throw new Error(
      `Failed to persist pending action to sidecar storage: ${
        err instanceof Error ? err.message : String(err)
      }`,
      { cause: err },
    )
  }

  // 3. 写后回读（Readback）双重校验
  try {
    const readback = targetStorage.getItem(key)
    if (readback !== serialized) {
      throw new Error(
        `Readback verification failed: written data does not match stored content`,
      )
    }
  } catch (err) {
    throw new Error(
      `Readback verification failed after storing pending action: ${
        err instanceof Error ? err.message : String(err)
      }`,
      { cause: err },
    )
  }
}

/**
 * 从侧车加载挂起动作。
 * - 存储读取异常时不得等同于“不存在”而继续发请求，返回 STORAGE_ERROR 显式阻止；
 * - 损坏或不合法数据不得静默 delete 导致丢身份，返回 CORRUPT 保留记录供用户人工裁决或明确放弃；
 * - 恢复成功时一律标记 isUnknown: true。
 */
export function loadPendingAction(
  issueId: string,
  storage?: Storage,
): LoadPendingActionResult {
  if (!issueId) {
    return { type: 'NONE' }
  }

  let targetStorage: Storage
  try {
    targetStorage = getSafeStorage(storage)
  } catch (err) {
    return {
      type: 'STORAGE_ERROR',
      error: `Storage unavailable: ${err instanceof Error ? err.message : String(err)}`,
    }
  }

  const key = pendingActionStorageKey(issueId)
  let raw: string | null
  try {
    raw = targetStorage.getItem(key)
  } catch (err) {
    return {
      type: 'STORAGE_ERROR',
      error: `Failed to read storage: ${err instanceof Error ? err.message : String(err)}`,
    }
  }

  if (raw == null) {
    return { type: 'NONE' }
  }

  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch (err) {
    // 保留原记录，不静默 delete
    return {
      type: 'CORRUPT',
      raw,
      error: `JSON parse error: ${err instanceof Error ? err.message : String(err)}`,
    }
  }

  if (!validAction(parsed, issueId)) {
    // 垃圾 kind、foreign issueId 或不合法 schema：保留原记录，不静默 delete
    return {
      type: 'CORRUPT',
      raw,
      error: 'Invalid pending action schema or foreign issueId',
    }
  }

  return {
    type: 'VALID',
    action: {
      ...parsed,
      isUnknown: true,
    },
  }
}

/**
 * 清除侧车挂起动作。
 * - 异步成功只能清除自己的 identity（matchingRequestKey 校验）；
 * - 若存储中为 corrupt 数据，仅在 forceDiscardCorrupt 为 true 时才允许清除。
 */
export function clearPendingAction(
  issueId: string,
  matchingRequestKey?: string,
  storage?: Storage,
  forceDiscardCorrupt: boolean = false,
): boolean {
  if (!issueId) {
    return false
  }

  let targetStorage: Storage
  try {
    targetStorage = getSafeStorage(storage)
  } catch {
    return false
  }

  const key = pendingActionStorageKey(issueId)
  try {
    const raw = targetStorage.getItem(key)
    if (raw == null) {
      return true
    }

    if (matchingRequestKey) {
      try {
        const parsed: unknown = JSON.parse(raw)
        if (isRecord(parsed) && parsed.requestKey !== matchingRequestKey) {
          // 请求 key 不匹配，拒绝清除他人挂起的动作
          return false
        }
      } catch {
        if (!forceDiscardCorrupt) {
          // 损坏记录在没有显式 force 放弃时不可清除
          return false
        }
      }
    } else {
      // 未指定 key：如果当前数据已损坏且未显式 forceDiscardCorrupt，不可误清
      try {
        const parsed: unknown = JSON.parse(raw)
        if (!validAction(parsed, issueId) && !forceDiscardCorrupt) {
          return false
        }
      } catch {
        if (!forceDiscardCorrupt) {
          return false
        }
      }
    }

    targetStorage.removeItem(key)
    return true
  } catch {
    return false
  }
}

/**
 * 判断请求异常是否为未知网络结果。
 * 408 (Request Timeout) 与 429 (Too Many Requests) 算未知；
 * 其余明确 4xx 业务/冲突错误（400, 401, 403, 404, 409, 422 等）确属服务端解析并明确拒绝，非未知。
 * 5xx、网络中断、TypeError 等一律视为未知结果。
 */
export function isNetworkUnknownError(err: unknown): boolean {
  if (err instanceof ApiError) {
    if (err.status === 408 || err.status === 429) {
      return true
    }
    if (typeof err.status === 'number' && err.status >= 400 && err.status < 500) {
      return false
    }
    return true
  }
  return true
}

/**
 * 校验两个 payload 对象的深度一致性，防止相同 requestKey 发送不同 payload。
 */
export function isPayloadEqual(a: unknown, b: unknown): boolean {
  if (a === b) {
    return true
  }
  if (a == null || b == null) {
    return a === b
  }
  if (typeof a !== 'object' || typeof b !== 'object') {
    return false
  }
  if (Array.isArray(a) !== Array.isArray(b)) {
    return false
  }
  const keysA = Object.keys(a as Record<string, unknown>)
  const keysB = Object.keys(b as Record<string, unknown>)
  if (keysA.length !== keysB.length) {
    return false
  }
  for (const k of keysA) {
    if (!Object.prototype.hasOwnProperty.call(b, k)) {
      return false
    }
    if (!isPayloadEqual((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k])) {
      return false
    }
  }
  return true
}

/**
 * 严格校验 PendingAction 是否为合法的 8 类动作之一，且核对 action.issueId === expectedIssueId
 */
export function validAction(value: unknown, expectedIssueId?: string): value is PendingIssueAction {
  if (!isRecord(value)) {
    return false
  }
  if (!nonBlank(value.issueId) || !nonBlank(value.kind) || !nonBlank(value.requestKey)) {
    return false
  }
  if (expectedIssueId && value.issueId !== expectedIssueId) {
    return false
  }
  if (typeof value.expectedVersion !== 'string' || value.expectedVersion.trim() === '') {
    return false
  }
  if (typeof value.createdAt !== 'string' || value.createdAt.trim() === '') {
    return false
  }
  if (typeof value.isUnknown !== 'boolean') {
    return false
  }
  if (!isRecord(value.payload)) {
    return false
  }

  const p = value.payload as Record<string, unknown>

  switch (value.kind) {
    case 'TRANSITION':
      return nonBlank(p.toState)
    case 'RECOVER':
    case 'REOPEN':
      return true
    case 'BLOCK':
      return nonBlank(p.reason)
    case 'RESOLVE_UNKNOWN':
      return nonBlank(p.verification)
    case 'STOP':
      return p.detail === null || typeof p.detail === 'string'
    case 'RESET_BUDGET':
      return (
        nonBlank(p.state) &&
        typeof p.maxRuns === 'number' &&
        Number.isInteger(p.maxRuns) &&
        p.maxRuns > 0
      )
    case 'ACTIVITY':
      return (
        (p.kind === 'COMMENT' || p.kind === 'INSTRUCTION') &&
        nonBlank(p.body)
      )
    default:
      return false
  }
}

function nonBlank(value: unknown): value is string {
  return typeof value === 'string' && value.trim() !== ''
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value)
}
