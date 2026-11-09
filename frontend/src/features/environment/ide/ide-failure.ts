import { ApiError, isNotFoundError } from '@/shared/api/client'
import type { IdeViewStatus } from '@/features/environment/ide/ide-view-state'

/** 服务端冻结的结构化 IDE 失败原因；客户端只读 reason，不解析 message/detail。 */
export const IDE_FAILURE_REASONS = [
  'DAEMON_NOT_READY',
  'VIEW_GONE',
  'VIEW_OWNED_BY_OTHER_SESSION',
  'STALE_BINDING',
  'CLAIM_CONFLICT',
  'IDE_CAPACITY_EXCEEDED',
  'IDE_OUTCOME_UNKNOWN',
  'IDE_CLEANUP_FAILED',
] as const

export type IdeFailureReason = (typeof IDE_FAILURE_REASONS)[number]

const REASON_SET = new Set<string>(IDE_FAILURE_REASONS)

export function ideFailureReason(error: unknown): IdeFailureReason | null {
  if (!(error instanceof ApiError) || error.reason == null || !REASON_SET.has(error.reason)) {
    return null
  }
  return error.reason as IdeFailureReason
}

/** 结果可能已执行：只有结构化 IDE_OUTCOME_UNKNOWN、超时、网络错误或 5xx/无状态。 */
export function isUnknownIdeFailure(error: unknown): boolean {
  if (ideFailureReason(error) === 'IDE_OUTCOME_UNKNOWN') {
    return true
  }
  if (error instanceof ApiError) {
    if (error.status == null || error.status >= 500) {
      return true
    }
    return error.code === 'timeout' || error.code === 'network_error'
  }
  return true
}

export function isViewGone(error: unknown): boolean {
  return ideFailureReason(error) === 'VIEW_GONE' || isNotFoundError(error)
}

export function isDaemonNotReady(error: unknown): boolean {
  return ideFailureReason(error) === 'DAEMON_NOT_READY'
}

export function isOutcomeUnknown(error: unknown): boolean {
  return ideFailureReason(error) === 'IDE_OUTCOME_UNKNOWN' || isUnknownIdeFailure(error)
}

function hasSensitivePath(value: string): boolean {
  return /\/(?:home|var|tmp|srv|Users)\/|[A-Za-z]:\\/.test(value)
}

/** 展示用错误文本；只使用服务端 detail 的去敏结果，不外泄宿主路径。 */
export function safeIdeError(error: unknown): string {
  if (error instanceof ApiError && typeof error.detail === 'string'
    && error.detail.length > 0 && !hasSensitivePath(error.detail)) {
    return error.detail
  }
  return error instanceof Error && !hasSensitivePath(error.message) ? error.message : ''
}

/**
 * 把结构化失败映射到恢复状态。STALE/OUTCOME_UNKNOWN 先进入 reconciling，
 * 由纯读权威快照决定下一动作；任何分支都不做定时重试或抢占。
 */
export function failureStatus(error: unknown): {
  status: IdeViewStatus
  message?: string
} {
  const reason = ideFailureReason(error)
  switch (reason) {
    case 'DAEMON_NOT_READY':
      return { status: 'waiting-ready' }
    case 'VIEW_GONE':
      return { status: 'gone' }
    case 'VIEW_OWNED_BY_OTHER_SESSION':
      return { status: 'detached' }
    case 'STALE_BINDING':
    case 'IDE_OUTCOME_UNKNOWN':
      return { status: 'reconciling' }
    case 'IDE_CLEANUP_FAILED':
      return { status: 'error', message: safeIdeError(error) || 'IDE_CLEANUP_FAILED' }
    case 'CLAIM_CONFLICT':
      return { status: 'error', message: 'CLAIM_CONFLICT' }
    case 'IDE_CAPACITY_EXCEEDED':
      return { status: 'error', message: safeIdeError(error) || 'IDE_CAPACITY_EXCEEDED' }
    default:
      if (isUnknownIdeFailure(error)) {
        return { status: 'reconciling' }
      }
      return { status: 'error', message: safeIdeError(error) }
  }
}
