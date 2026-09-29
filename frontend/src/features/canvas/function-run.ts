import { useCallback } from 'react'
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
  if (typeof window === 'undefined') {
    return null
  }
  try {
    const raw = window.localStorage.getItem(getPendingRunStorageKey(canvasId, nodeId))
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw) as PendingFunctionRunAttempt
    return parsed && parsed.request?.requestId ? parsed : null
  } catch {
    return null
  }
}

export function savePendingFunctionRun(attempt: PendingFunctionRunAttempt): void {
  if (typeof window === 'undefined') {
    return
  }
  try {
    window.localStorage.setItem(
      getPendingRunStorageKey(attempt.canvasId, attempt.nodeId),
      JSON.stringify(attempt),
    )
  } catch {
    // LocalStorage quota or blocked
  }
}

export function clearPendingFunctionRun(canvasId: string, nodeId: string): void {
  if (typeof window === 'undefined') {
    return
  }
  try {
    window.localStorage.removeItem(getPendingRunStorageKey(canvasId, nodeId))
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
    if (!canvasId) {
      return
    }
    try {
      await flushFunctionConfig(nodeId)
    } catch (error) {
      setToast(error instanceof Error ? error.message : '启动生成失败')
      return
    }

    // 检查是否存在未决请求（例如提交后丢响应、断网或页面刷新残留）：
    // 若存在，必须复用冻结的完整 attempt（原 requestId 与 requestDTO），绝不生成新 key 重新发起新任务！
    const existing = loadPendingFunctionRun(canvasId, nodeId)
    const attempt: PendingFunctionRunAttempt = existing ?? {
      canvasId,
      nodeId,
      request: { requestId: crypto.randomUUID() as UUIDString },
      basisRequestId: readBasisRequestId(nodeId),
      createdAt: Date.now(),
    }

    // 发请求前必须在 localStorage 持久化冻结请求
    savePendingFunctionRun(attempt)

    const basisRequestId = attempt.basisRequestId
    const requestId = attempt.request.requestId
    try {
      const run = await startCanvasFunctionRun(canvasId, nodeId, attempt.request)
      // 成功获得确定响应：清除 pending 记录
      clearPendingFunctionRun(canvasId, nodeId)
      publishRun(run, basisRequestId)
    } catch (error) {
      // 明确 409 或 4xx 客户端错误终态：清除 pending 记录，不再重试
      if (isTerminalClientError(error)) {
        clearPendingFunctionRun(canvasId, nodeId)
        setToast(error instanceof Error ? error.message : '启动生成失败')
        return
      }

      // 未知失败（网络断开、超时 408/429、5xx 等）：保留原 identity
      try {
        const current = await getCanvasFunctionRun(canvasId, nodeId)
        if (current.requestId === requestId) {
          clearPendingFunctionRun(canvasId, nodeId)
          publishRun(current, basisRequestId)
          return
        }
      } catch {
        // Preserve the original start error when reconciliation is unavailable.
      }
      // 不得在仅 GET 无结果时生成新 key，保留原 attempt 供重载 / 重试同 key 复用
      setToast(error instanceof Error ? error.message : '启动生成失败')
    }
  }, [canvasId, flushFunctionConfig, publishRun, readBasisRequestId, setToast])

  const cancelFunctionRun = useCallback(async (
    nodeId: UUIDString,
    requestId?: UUIDString,
  ) => {
    const targetRequestId = requestId ?? readBasisRequestId(nodeId)
    if (!canvasId || !targetRequestId) {
      return
    }
    try {
      const run = await cancelCanvasFunctionRun(canvasId, nodeId, { requestId: targetRequestId })
      clearPendingFunctionRun(canvasId, nodeId)
      // cancel 以被取消的 requestId 为 basis：只允许更新该 request，不得覆盖更新的 request。
      publishRun(run, targetRequestId)
    } catch (error) {
      setToast(error instanceof Error ? error.message : '取消生成失败')
    }
  }, [canvasId, publishRun, readBasisRequestId, setToast])

  const resolveFunctionRun = useCallback(async (
    nodeId: UUIDString,
    requestId: UUIDString,
    resolution: 'RESUME' | 'FAILED' | 'CANCELLED',
    verification: string,
  ) => {
    if (!canvasId || !requestId) {
      return
    }
    try {
      const run = await resolveCanvasFunctionRun(canvasId, nodeId, {
        requestId,
        resolution,
        verification,
      })
      clearPendingFunctionRun(canvasId, nodeId)
      publishRun(run, requestId)
    } catch (error) {
      setToast(error instanceof Error ? error.message : '核查确认失败')
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

