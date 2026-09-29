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

/**
 * 冻结的完整请求结构，发送前落盘到 issue-scoped 侧车。
 * 包含 requestKey、expectedVersion、payload 和 unknown 标记。
 */
export interface PendingIssueAction<P = Record<string, unknown>> {
  issueId: string
  kind: IssueActionKind
  requestKey: string
  expectedVersion: string
  payload: P
  createdAt: string
  isUnknown: boolean
}

export function pendingActionStorageKey(issueId: string): string {
  return `${STORAGE_PREFIX}${issueId}`
}

/**
 * 发送前持久化到侧车。Storage 写入失败时必须 fail-closed（抛出异常），禁止无侧车保护直接发送网络请求。
 */
export function storePendingAction(
  issueId: string,
  action: PendingIssueAction,
  storage: Storage = localStorage,
): void {
  if (!issueId || !validAction(action)) {
    throw new Error('Invalid pending action payload or issueId')
  }
  try {
    storage.setItem(pendingActionStorageKey(issueId), JSON.stringify(action))
  } catch (err) {
    throw new Error(
      `Failed to persist pending action to sidecar storage: ${
        err instanceof Error ? err.message : String(err)
      }`,
      { cause: err },
    )
  }
}

/**
 * 从侧车加载挂起动作。从存储恢复时，上一次页面的未完成请求一律视为 UNKNOWN。
 */
export function loadPendingAction(
  issueId: string,
  storage: Storage = localStorage,
): PendingIssueAction | null {
  if (!issueId) {
    return null
  }
  const key = pendingActionStorageKey(issueId)
  try {
    const raw = storage.getItem(key)
    if (raw == null) {
      return null
    }
    const parsed: unknown = JSON.parse(raw)
    if (!validAction(parsed)) {
      storage.removeItem(key)
      return null
    }
    // load 恢复时未完成一律置为 unknown
    return {
      ...parsed,
      isUnknown: true,
    }
  } catch {
    try {
      storage.removeItem(key)
    } catch {
      // 存储异常清理
    }
    return null
  }
}

/**
 * 清除侧车挂起动作。
 * 异步成功只能清除自己的 identity（matchingRequestKey 校验）。若 storage 中当前挂起的 key 已不同，拒绝清理。
 */
export function clearPendingAction(
  issueId: string,
  matchingRequestKey?: string,
  storage: Storage = localStorage,
): boolean {
  if (!issueId) {
    return false
  }
  const key = pendingActionStorageKey(issueId)
  try {
    if (matchingRequestKey) {
      const raw = storage.getItem(key)
      if (raw == null) {
        return true
      }
      const parsed: unknown = JSON.parse(raw)
      if (isRecord(parsed) && parsed.requestKey !== matchingRequestKey) {
        // 请求 key 不匹配，拒绝清除他人挂起的动作
        return false
      }
    }
    storage.removeItem(key)
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

function validAction(value: unknown): value is PendingIssueAction {
  if (!isRecord(value)) {
    return false
  }
  return (
    nonBlank(value.issueId) &&
    nonBlank(value.kind) &&
    nonBlank(value.requestKey) &&
    typeof value.expectedVersion === 'string' &&
    value.payload !== undefined &&
    typeof value.createdAt === 'string' &&
    typeof value.isUnknown === 'boolean'
  )
}

function nonBlank(value: unknown): value is string {
  return typeof value === 'string' && value.trim() !== ''
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value)
}
