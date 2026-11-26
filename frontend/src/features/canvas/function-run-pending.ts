import { canvasViewKey } from './canvas-view-key'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import type {
  CanvasFunctionRunRequestDTO,
  CanvasFunctionVersionDTO,
} from '@/shared/api/generated/contracts'

/**
 * Function run 的本地未决记录持久化（localStorage）。
 *
 * 记录在发请求前完整冻结（requestId + 点击瞬间的目录版本 + basis + createdAt），
 * 之后只允许原样重放或按同一份 raw 精确删除；任何读取失败都保留原始字节。
 */

const PENDING_RUN_STORAGE_PREFIX = 'kk-studio.canvas.pending-run:'

const DEFINITION_FINGERPRINT = /^[0-9a-f]{64}$/

/**
 * 未决记录里的目录版本来自 localStorage，不经生成契约约束，
 * 必须拒绝缺失、未知字段与非 canonical 指纹。
 */
function isValidCanvasFunctionVersion(value: unknown): value is CanvasFunctionVersionDTO {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return false
  }
  const candidate = value as Record<string, unknown>
  return Object.keys(candidate).length === 1
    && typeof candidate.definitionFingerprint === 'string'
    && DEFINITION_FINGERPRINT.test(candidate.definitionFingerprint)
}

/** 存储读取失败（localStorage 不可用 / getItem 抛错）：原始字节必须保留，绝不删除。 */
class PendingRunStorageError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options)
    this.name = 'PendingRunStorageError'
  }
}

/**
 * 存在但结构非法（含缺少合法 expectedVersion 的记录）：调用方必须明确丢弃该坏缓存。
 *
 * raw 是本次 load 实际读到的原始字符串：丢弃必须按同一份 raw 确认（复用
 * discardPendingFunctionRun），绝不按 key 盲目删除——记录可能已被其它标签页替换。
 */
export class PendingRunCorruptError extends Error {
  readonly raw: string

  constructor(message: string, raw: string, options?: ErrorOptions) {
    super(message, options)
    this.name = 'PendingRunCorruptError'
    this.raw = raw
  }
}

export function getLocalStorageSafe(): Storage | null {
  if (typeof window === 'undefined') {
    return null
  }
  try {
    return window.localStorage ?? null
  } catch {
    return null
  }
}

export interface PendingFunctionRunAttempt {
  canvasId: string
  nodeId: string
  request: CanvasFunctionRunRequestDTO
  basisRequestId: string | null
  createdAt: number
}

export function getPendingRunStorageKey(canvasId: string, nodeId: string, viewId?: string): string {
  return `${PENDING_RUN_STORAGE_PREFIX}${canvasViewKey(canvasId, viewId)}:${nodeId}`
}

/** request 必须恰好是 {requestId, expectedVersion} 且两者都合法；未知字段一律拒绝。 */
function isValidPendingRequest(value: unknown): value is CanvasFunctionRunRequestDTO {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return false
  }
  const request = value as Record<string, unknown>
  const keys = Object.keys(request)
  return keys.length === 2
    && keys.includes('requestId')
    && keys.includes('expectedVersion')
    && isCanonicalUuid(request.requestId)
    && isValidCanvasFunctionVersion(request.expectedVersion)
}

/** 未决记录是严格存储对象：字段集合必须与 PendingFunctionRunAttempt 完全一致。 */
const PENDING_ATTEMPT_FIELDS = ['canvasId', 'nodeId', 'request', 'basisRequestId', 'createdAt'] as const

function hasExactFields(value: Record<string, unknown>, allowed: readonly string[]): boolean {
  const keys = Object.keys(value)
  return keys.length === allowed.length && allowed.every((field) => keys.includes(field))
}

export function loadPendingFunctionRun(canvasId: string, nodeId: string, viewId?: string): PendingFunctionRunAttempt | null {
  const storage = getLocalStorageSafe()
  if (!storage) {
    throw new PendingRunStorageError('Local storage is unavailable')
  }
  const key = getPendingRunStorageKey(canvasId, nodeId, viewId)
  let raw: string | null
  try {
    raw = storage.getItem(key)
  } catch (err) {
    throw new PendingRunStorageError(`Failed to read pending function run attempt: ${err instanceof Error ? err.message : String(err)}`, { cause: err })
  }
  if (raw === null) {
    return null
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch (err) {
    throw new PendingRunCorruptError(`Corrupted pending function run attempt (invalid JSON): ${err instanceof Error ? err.message : String(err)}`, raw, { cause: err })
  }
  if (
    !parsed
    || typeof parsed !== 'object'
    || Array.isArray(parsed)
    || !hasExactFields(parsed as Record<string, unknown>, PENDING_ATTEMPT_FIELDS)
    || !isCanonicalUuid((parsed as Partial<PendingFunctionRunAttempt>).canvasId)
    || (parsed as Partial<PendingFunctionRunAttempt>).canvasId !== canvasId
    || !isCanonicalUuid((parsed as Partial<PendingFunctionRunAttempt>).nodeId)
    || (parsed as Partial<PendingFunctionRunAttempt>).nodeId !== nodeId
    || !isValidPendingRequest((parsed as Partial<PendingFunctionRunAttempt>).request)
    || typeof (parsed as Partial<PendingFunctionRunAttempt>).createdAt !== 'number'
    || !Number.isFinite((parsed as Partial<PendingFunctionRunAttempt>).createdAt)
    || ((parsed as Partial<PendingFunctionRunAttempt>).createdAt ?? 0) <= 0
    || ((parsed as Partial<PendingFunctionRunAttempt>).basisRequestId !== null
      && !isCanonicalUuid((parsed as Partial<PendingFunctionRunAttempt>).basisRequestId))
  ) {
    throw new PendingRunCorruptError('Corrupted pending function run attempt (schema validation failed)', raw)
  }
  return parsed as PendingFunctionRunAttempt
}

export function savePendingFunctionRun(attempt: PendingFunctionRunAttempt, viewId?: string): void {
  const storage = getLocalStorageSafe()
  if (!storage) {
    throw new PendingRunStorageError('Local storage is unavailable')
  }
  if (
    !hasExactFields(attempt as unknown as Record<string, unknown>, PENDING_ATTEMPT_FIELDS)
    || !isCanonicalUuid(attempt.canvasId)
    || !isCanonicalUuid(attempt.nodeId)
    || !isValidPendingRequest(attempt.request)
    || (attempt.basisRequestId !== null && !isCanonicalUuid(attempt.basisRequestId))
    || typeof attempt.createdAt !== 'number'
    || !Number.isFinite(attempt.createdAt)
    || attempt.createdAt <= 0
  ) {
    throw new Error('Invalid function run attempt payload: must contain canonical lowercase UUIDs and a complete expectedVersion')
  }
  const key = getPendingRunStorageKey(attempt.canvasId, attempt.nodeId, viewId)

  let rawExisting: string | null
  try {
    rawExisting = storage.getItem(key)
  } catch (err) {
    throw new PendingRunStorageError(`Failed to check existing function run attempt: ${err instanceof Error ? err.message : String(err)}`, { cause: err })
  }
  const serialized = JSON.stringify(attempt)
  if (rawExisting !== null) {
    // 完整序列比较：若已有记录且完整序列化内容不一致（包括不同 requestId，或同 requestId 但不同 basis / createdAt），一律拒绝覆盖
    if (rawExisting !== serialized) {
      throw new Error('Cannot overwrite conflicting or modified pending function run attempt')
    }
  }

  try {
    storage.setItem(key, serialized)
  } catch (err) {
    throw new Error(`Failed to persist function run attempt: ${err instanceof Error ? err.message : String(err)}`, { cause: err })
  }
  const readback = storage.getItem(key)
  if (readback !== serialized) {
    throw new Error('Failed to verify persisted function run attempt (readback mismatch)')
  }
}

export function clearPendingFunctionRun(
  canvasId: string,
  nodeId: string,
  expectedRequestId: string,
  viewId?: string,
): void {
  if (!isCanonicalUuid(expectedRequestId)) {
    return
  }
  const storage = getLocalStorageSafe()
  if (!storage) {
    return
  }
  const key = getPendingRunStorageKey(canvasId, nodeId, viewId)
  let raw: string | null
  try {
    raw = storage.getItem(key)
  } catch {
    return
  }
  if (raw === null) {
    return
  }
  try {
    const existing = loadPendingFunctionRun(canvasId, nodeId, viewId)
    if (existing && existing.request.requestId === expectedRequestId) {
      storage.removeItem(key)
    }
  } catch {
    // 读失败或损坏时保留原始记录，绝不盲目删除未知项
    return
  }
}

export function discardPendingFunctionRun(
  canvasId: string,
  nodeId: string,
  expectedRaw: string,
  viewId?: string,
): boolean {
  const storage = getLocalStorageSafe()
  if (!storage) {
    throw new Error('无法读取浏览器存储，请检查存储权限')
  }
  const key = getPendingRunStorageKey(canvasId, nodeId, viewId)
  let currentRaw: string | null
  try {
    currentRaw = storage.getItem(key)
  } catch (err) {
    throw new Error(`无法读取浏览器存储: ${err instanceof Error ? err.message : String(err)}`, { cause: err })
  }
  if (currentRaw === null) {
    return true
  }
  // 仅删除经用户看到且确认的同 raw 记录；stale 确认不可删除新记录
  if (currentRaw !== expectedRaw) {
    return false
  }
  try {
    storage.removeItem(key)
    return true
  } catch (err) {
    throw new Error(`无法写入浏览器存储: ${err instanceof Error ? err.message : String(err)}`, { cause: err })
  }
}

export interface LocalPendingRunState {
  error: string | null
  raw: string | null
  attempt: PendingFunctionRunAttempt | null
}

export function inspectLocalPendingRun(canvasId: string, nodeId: string, viewId?: string): LocalPendingRunState {
  const storage = getLocalStorageSafe()
  if (!storage) {
    return { error: '无法读取浏览器存储，请检查存储权限', raw: null, attempt: null }
  }
  const key = getPendingRunStorageKey(canvasId, nodeId, viewId)
  let raw: string | null
  try {
    raw = storage.getItem(key)
  } catch (err) {
    return {
      error: `无法读取浏览器存储: ${err instanceof Error ? err.message : String(err)}`,
      raw: null,
      attempt: null,
    }
  }
  if (raw === null) {
    return { error: null, raw: null, attempt: null }
  }
  try {
    const attempt = loadPendingFunctionRun(canvasId, nodeId, viewId)
    return { error: null, raw, attempt }
  } catch (err) {
    return {
      error: err instanceof Error ? err.message : String(err),
      raw,
      attempt: null,
    }
  }
}
