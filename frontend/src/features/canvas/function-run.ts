import { useCallback } from 'react'
import type { QueryClient } from '@tanstack/react-query'
import type {
  CanvasFunctionRunDTO,
  CanvasSnapshotDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'
import {
  cancelCanvasFunctionRun,
  getCanvasFunctionRun,
  startCanvasFunctionRun,
} from '@/shared/api/studio-service'
import { queryKeys } from '@/shared/lib/query-keys'

export interface FunctionRunActions {
  startFunctionRun: (nodeId: UUIDString) => Promise<void>
  cancelFunctionRun: (nodeId: UUIDString, requestId: UUIDString) => Promise<void>
}

/**
 * Function run 生命周期：本地 start/cancel 响应只做即时投影，绝对不做固定间隔轮询；
 * 权威收敛仍由 version 事件驱动的 changes/snapshot 负责；start 响应丢失时以匹配
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
    const requestId = crypto.randomUUID()
    // 发请求前捕获 basis；fallback 与 start 沿用同一 basis，避免旧快照被错误替换。
    const basisRequestId = readBasisRequestId(nodeId)
    try {
      const run = await startCanvasFunctionRun(canvasId, nodeId, {
        requestId,
      })
      publishRun(run, basisRequestId)
    } catch (error) {
      try {
        const current = await getCanvasFunctionRun(canvasId, nodeId)
        if (current.requestId === requestId) {
          publishRun(current, basisRequestId)
          return
        }
      } catch {
        // Preserve the original start error when reconciliation is unavailable.
      }
      setToast(error instanceof Error ? error.message : '启动生成失败')
    }
  }, [canvasId, flushFunctionConfig, publishRun, readBasisRequestId, setToast])

  const cancelFunctionRun = useCallback(async (
    nodeId: UUIDString,
    requestId: UUIDString,
  ) => {
    if (!canvasId || !requestId) {
      return
    }
    try {
      const run = await cancelCanvasFunctionRun(canvasId, nodeId, { requestId })
      // cancel 以被取消的 requestId 为 basis：只允许更新该 request，不得覆盖更新的 request。
      publishRun(run, requestId)
    } catch (error) {
      setToast(error instanceof Error ? error.message : '取消生成失败')
    }
  }, [canvasId, publishRun, setToast])

  return { startFunctionRun, cancelFunctionRun }
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

