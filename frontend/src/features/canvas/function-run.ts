import { useCallback, useRef } from 'react'
import type { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/client'
import type {
  CanvasFunctionRunDTO,
  CanvasFunctionRunRequestDTO,
  CanvasSnapshotDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'
import {
  cancelCanvasFunctionRun,
  getCanvasFunctionRun,
  resolveCanvasFunctionRun,
  startCanvasFunctionRun,
} from '@/shared/api/studio-service'
import { queryKeys } from '@/shared/lib/query-keys'

const PENDING_RUN_STORAGE_PREFIX = 'kkstudio.canvas.pending-run:'
const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

export function isCanonicalUUID(value: unknown): value is UUIDString {
  return typeof value === 'string' && UUID_REGEX.test(value)
}

function getLocalStorageSafe(): Storage | null {
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
  canvasId: UUIDString
  nodeId: UUIDString
  request: CanvasFunctionRunRequestDTO
  basisRequestId: UUIDString | null
  createdAt: number
}

function getPendingRunStorageKey(canvasId: string, nodeId: string): string {
  return `${PENDING_RUN_STORAGE_PREFIX}${canvasId}:${nodeId}`
}

export function loadPendingFunctionRun(canvasId: string, nodeId: string): PendingFunctionRunAttempt | null {
  const storage = getLocalStorageSafe()
  if (!storage) {
    throw new Error('Local storage is unavailable')
  }
  const key = getPendingRunStorageKey(canvasId, nodeId)
  let raw: string | null
  try {
    raw = storage.getItem(key)
  } catch (err) {
    throw new Error(`Failed to read pending function run attempt: ${err instanceof Error ? err.message : String(err)}`, { cause: err })
  }
  if (raw === null) {
    return null
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch (err) {
    throw new Error(`Corrupted pending function run attempt (invalid JSON): ${err instanceof Error ? err.message : String(err)}`, { cause: err })
  }
  if (
    !parsed
    || typeof parsed !== 'object'
    || !isCanonicalUUID((parsed as Partial<PendingFunctionRunAttempt>).canvasId)
    || (parsed as Partial<PendingFunctionRunAttempt>).canvasId !== canvasId
    || !isCanonicalUUID((parsed as Partial<PendingFunctionRunAttempt>).nodeId)
    || (parsed as Partial<PendingFunctionRunAttempt>).nodeId !== nodeId
    || !(parsed as Partial<PendingFunctionRunAttempt>).request
    || typeof (parsed as Partial<PendingFunctionRunAttempt>).request !== 'object'
    || !isCanonicalUUID((parsed as Partial<PendingFunctionRunAttempt>).request?.requestId)
    || Object.keys((parsed as Partial<PendingFunctionRunAttempt>).request as object).length !== 1
    || typeof (parsed as Partial<PendingFunctionRunAttempt>).createdAt !== 'number'
    || !Number.isFinite((parsed as Partial<PendingFunctionRunAttempt>).createdAt)
    || ((parsed as Partial<PendingFunctionRunAttempt>).createdAt ?? 0) <= 0
    || ((parsed as Partial<PendingFunctionRunAttempt>).basisRequestId !== null
      && !isCanonicalUUID((parsed as Partial<PendingFunctionRunAttempt>).basisRequestId))
  ) {
    throw new Error('Corrupted pending function run attempt (schema validation failed)')
  }
  return parsed as PendingFunctionRunAttempt
}

export function savePendingFunctionRun(attempt: PendingFunctionRunAttempt): void {
  const storage = getLocalStorageSafe()
  if (!storage) {
    throw new Error('Local storage is unavailable')
  }
  if (
    !isCanonicalUUID(attempt.canvasId)
    || !isCanonicalUUID(attempt.nodeId)
    || !isCanonicalUUID(attempt.request?.requestId)
    || Object.keys(attempt.request).length !== 1
    || (attempt.basisRequestId !== null && !isCanonicalUUID(attempt.basisRequestId))
    || typeof attempt.createdAt !== 'number'
    || !Number.isFinite(attempt.createdAt)
    || attempt.createdAt <= 0
  ) {
    throw new Error('Invalid function run attempt payload: must contain canonical lowercase UUIDs')
  }
  const key = getPendingRunStorageKey(attempt.canvasId, attempt.nodeId)

  let rawExisting: string | null
  try {
    rawExisting = storage.getItem(key)
  } catch (err) {
    throw new Error(`Failed to check existing function run attempt: ${err instanceof Error ? err.message : String(err)}`, { cause: err })
  }
  if (rawExisting !== null) {
    const existing = loadPendingFunctionRun(attempt.canvasId, attempt.nodeId)
    if (existing && existing.request.requestId !== attempt.request.requestId) {
      throw new Error('Cannot overwrite conflicting pending function run attempt from another process or pane')
    }
  }

  const serialized = JSON.stringify(attempt)
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
): void {
  if (!isCanonicalUUID(expectedRequestId)) {
    return
  }
  const storage = getLocalStorageSafe()
  if (!storage) {
    return
  }
  const key = getPendingRunStorageKey(canvasId, nodeId)
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
    const existing = loadPendingFunctionRun(canvasId, nodeId)
    if (existing && existing.request.requestId === expectedRequestId) {
      storage.removeItem(key)
    }
  } catch {
    // 读失败或损坏时保留原始记录，绝不盲目删除未知项
    return
  }
}

export function discardPendingFunctionRun(canvasId: string, nodeId: string): void {
  const storage = getLocalStorageSafe()
  if (!storage) {
    return
  }
  try {
    storage.removeItem(getPendingRunStorageKey(canvasId, nodeId))
  } catch {
    // Ignore
  }
}

function isTerminalClientError(error: unknown): boolean {
  if (error instanceof ApiError) {
    const status = error.status
    // 408 (Request Timeout) 与 429 (Too Many Requests) 是非终态可重试状态
    // 403 (Forbidden), 409 (Conflict) 及其他 4xx (400, 404, 422 等) 属于不可重试的终态客户端错误
    return typeof status === 'number' && status >= 400 && status < 500 && status !== 408 && status !== 429
  }
  return false
}

export interface FunctionRunActions {
  startFunctionRun: (nodeId: UUIDString) => Promise<void>
  cancelFunctionRun: (nodeId: UUIDString, requestId: UUIDString) => Promise<void>
  resolveFunctionRun: (
    nodeId: UUIDString,
    requestId: UUIDString,
    resolution: 'RESUME' | 'FAILED' | 'CANCELLED',
    verification: string,
  ) => Promise<void>
}

/**
 * Function run 生命周期：本地 start/cancel 响应只做即时投影，绝对不做固定间隔轮询；
 * 权威收敛仍由 version 事件驱动的 Snapshot 负责；start 响应丢失时以匹配
 * requestId 的服务端 run 做 authoritative fallback，其余失败 toast。
 */
export function useCanvasFunctionRun(options: {
  canvasId: UUIDString | null
  queryClient: QueryClient
  setToast: (toast: string) => void
  flushFunctionConfig: (nodeId: UUIDString) => Promise<void>
}): FunctionRunActions {
  const { canvasId, queryClient, setToast, flushFunctionConfig } = options
  const inFlightNodesRef = useRef(new Set<string>())

  const publishRun = useCallback((run: CanvasFunctionRunDTO, basisRequestId: UUIDString | null) => {
    if (!canvasId) {
      return
    }
    queryClient.setQueryData<CanvasSnapshotDTO>(
      queryKeys.studio.canvas(canvasId),
      (current) => patchSnapshotRun(current, run, basisRequestId),
    )
    if (run.status === 'SUCCEEDED') {
      void queryClient.invalidateQueries({ queryKey: queryKeys.studio.canvas(canvasId) })
    }
  }, [canvasId, queryClient])

  /** 捕获该 node 当前缓存的 run.requestId（可 null）作为本地投影的 CAS basis。 */
  const readBasisRequestId = useCallback((nodeId: UUIDString): UUIDString | null => {
    if (!canvasId) {
      return null
    }
    const current = queryClient.getQueryData<CanvasSnapshotDTO>(queryKeys.studio.canvas(canvasId))
    return current?.nodes.find((node) => node.id === nodeId)?.run?.requestId ?? null
  }, [canvasId, queryClient])

  const startFunctionRun = useCallback(async (nodeId: UUIDString) => {
    if (!canvasId || inFlightNodesRef.current.has(nodeId)) {
      return
    }
    inFlightNodesRef.current.add(nodeId)
    try {
      // 1. 读取未决记录：若抛错（数据损坏或存储异常），fail-closed 阻断，保证 0 flush / 0 POST
      let existing: PendingFunctionRunAttempt | null = null
      try {
        existing = loadPendingFunctionRun(canvasId, nodeId)
      } catch (err) {
        console.error('[canvas] Failed to load pending function run attempt:', err)
        setToast('未决运行记录损坏或存储读取失败，已暂停生成以防数据覆盖。请排查存储或手动重置。')
        return
      }

      if (!existing) {
        try {
          await flushFunctionConfig(nodeId)
        } catch (error) {
          setToast(error instanceof Error ? error.message : '启动生成失败')
          return
        }
      }

      const attempt: PendingFunctionRunAttempt = existing ?? {
        canvasId,
        nodeId,
        request: { requestId: crypto.randomUUID() as UUIDString },
        basisRequestId: readBasisRequestId(nodeId),
        createdAt: Date.now(),
      }

      // 2. 发请求前必须成功持久化，读回校验或并发冲突失败 fail-closed 阻断
      try {
        savePendingFunctionRun(attempt)
      } catch (err) {
        console.error('[canvas] Failed to save pending function run attempt:', err)
        setToast('本地运行状态持久化失败，无法发起生成')
        return
      }

      const basisRequestId = attempt.basisRequestId
      const requestId = attempt.request.requestId
      try {
        const run = await startCanvasFunctionRun(canvasId, nodeId, attempt.request)
        clearPendingFunctionRun(canvasId, nodeId, requestId)
        publishRun(run, basisRequestId)
      } catch (error) {
        if (isTerminalClientError(error)) {
          clearPendingFunctionRun(canvasId, nodeId, requestId)
          setToast(error instanceof Error ? error.message : '启动生成失败')
          return
        }

        try {
          const current = await getCanvasFunctionRun(canvasId, nodeId)
          if (current.requestId === requestId) {
            clearPendingFunctionRun(canvasId, nodeId, requestId)
            publishRun(current, basisRequestId)
            return
          }
        } catch {
          // 对账失败保留原 attempt 供重试
        }
        setToast(error instanceof Error ? error.message : '启动生成失败')
      }
    } finally {
      inFlightNodesRef.current.delete(nodeId)
    }
  }, [canvasId, flushFunctionConfig, publishRun, readBasisRequestId, setToast])

  const cancelFunctionRun = useCallback(async (
    nodeId: UUIDString,
    requestId?: UUIDString,
  ) => {
    const targetRequestId = requestId ?? readBasisRequestId(nodeId)
    if (!canvasId || !targetRequestId || inFlightNodesRef.current.has(nodeId)) {
      return
    }
    inFlightNodesRef.current.add(nodeId)
    try {
      const run = await cancelCanvasFunctionRun(canvasId, nodeId, { requestId: targetRequestId })
      clearPendingFunctionRun(canvasId, nodeId, targetRequestId)
      publishRun(run, targetRequestId)
    } catch (error) {
      setToast(error instanceof Error ? error.message : '取消生成失败')
    } finally {
      inFlightNodesRef.current.delete(nodeId)
    }
  }, [canvasId, publishRun, readBasisRequestId, setToast])

  const resolveFunctionRun = useCallback(async (
    nodeId: UUIDString,
    requestId: UUIDString,
    resolution: 'RESUME' | 'FAILED' | 'CANCELLED',
    verification: string,
  ) => {
    if (!canvasId || !requestId || inFlightNodesRef.current.has(nodeId)) {
      return
    }
    inFlightNodesRef.current.add(nodeId)
    try {
      const run = await resolveCanvasFunctionRun(canvasId, nodeId, {
        requestId,
        resolution,
        verification,
      })
      clearPendingFunctionRun(canvasId, nodeId, requestId)
      publishRun(run, requestId)
    } catch (error) {
      setToast(error instanceof Error ? error.message : '核查确认失败')
    } finally {
      inFlightNodesRef.current.delete(nodeId)
    }
  }, [canvasId, publishRun, setToast])

  return { startFunctionRun, cancelFunctionRun, resolveFunctionRun }
}

/**
 * 把 Function run 按 basis-CAS 投影进快照节点（绝不覆盖 document version）。
 *
 * 调用方在发请求前捕获该 node 当前 run.requestId（可 null）作为 basis：start/fallback 捕获现有
 * requestId（终态 A 后再 start B 时 basis=A），cancel 以被取消的 requestId 为 basis。响应只有在
 * 缓存当前仍是 basis、缓存已是本响应 request、或缓存仍无 run 时才投影；缓存变为第三个 request C
 * 时视为过期，一律忽略并交由 version 事件权威收敛。
 */
export function patchSnapshotRun(
  snapshot: CanvasSnapshotDTO | undefined,
  run: CanvasFunctionRunDTO,
  basisRequestId: UUIDString | null,
): CanvasSnapshotDTO | undefined {
  if (!snapshot) {
    return snapshot
  }
  const nodeIndex = snapshot.nodes.findIndex((node) => node.id === run.nodeId)
  if (nodeIndex < 0) {
    return snapshot
  }
  const currentRun = snapshot.nodes[nodeIndex]?.run
  const currentRequestId = currentRun?.requestId ?? null
  const projects =
    currentRequestId === basisRequestId
    || currentRequestId === run.requestId
    || currentRequestId === null
  if (!projects) {
    return snapshot
  }
  if (
    currentRun?.requestId === run.requestId
    && currentRun.status === run.status
    && currentRun.stage === run.stage
    && currentRun.error === run.error
    && currentRun.updatedAt === run.updatedAt
  ) {
    return snapshot
  }
  const nodes = [...snapshot.nodes]
  nodes[nodeIndex] = { ...nodes[nodeIndex], run }
  return { ...snapshot, nodes }
}

