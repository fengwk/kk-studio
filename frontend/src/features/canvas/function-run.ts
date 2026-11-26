import { useCallback, useRef, useState, type Dispatch, type SetStateAction } from 'react'
import type { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/client'
import {
  cancelCanvasFunctionRun,
  getCanvasFunctionRun,
  resolveCanvasFunctionRun,
  startCanvasFunctionRun,
} from '@/features/canvas/canvas-api'
import { patchSnapshotRun } from '@/features/canvas/function-run-snapshot'
import {
  PendingRunCorruptError,
  clearPendingFunctionRun,
  discardPendingFunctionRun,
  getLocalStorageSafe,
  getPendingRunStorageKey,
  loadPendingFunctionRun,
  savePendingFunctionRun,
  type PendingFunctionRunAttempt,
} from '@/features/canvas/function-run-pending'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n, type InterpolationValues, type TranslationKey } from '@/shared/i18n'
import type {
  CanvasFunctionRunDTO,
  CanvasFunctionUnknownResolution,
  CanvasFunctionVersionDTO,
  CanvasSnapshotDTO,
} from '@/shared/api/generated/contracts'

/** 面板需要展示的本地未决记录异常，按 nodeId 索引。 */
type LocalPendingErrors = Record<string, { raw: string | null }>

type Translate = (key: TranslationKey, values?: InterpolationValues) => string

export interface FunctionRunActions {
  startFunctionRun: (nodeId: string) => Promise<void>
  cancelFunctionRun: (nodeId: string, requestId: string) => Promise<void>
  resolveFunctionRun: (
    nodeId: string,
    requestId: string,
    resolution: CanvasFunctionUnknownResolution,
    verification: string,
  ) => Promise<void>
  discardPendingRun: (nodeId: string, expectedRaw: string) => boolean
  localPendingErrors: LocalPendingErrors
  isNodeInFlight: (nodeId: string) => boolean
}

/**
 * Function run 生命周期：本地 start/cancel 响应只做即时投影，绝对不做固定间隔轮询；
 * 权威收敛仍由 version 事件驱动的 Snapshot 负责；start 响应丢失时以匹配
 * requestId 的服务端 run 做 authoritative fallback，其余失败 toast。
 *
 * 版本围栏：新 start 在点击瞬间同步捕获 resolveFunctionVersion 返回的目录版本（早于任何
 * flush/re-fetch await），并把 requestId 与该 expectedVersion 作为不可变请求持久化；
 * 重放只原样沿用持久化请求，绝不用最新目录版本替换。
 */
export function useCanvasFunctionRun(options: {
  canvasId: string
  viewId?: string
  queryClient: QueryClient
  setToast: (toast: string) => void
  flushFunctionConfig: (nodeId: string) => Promise<unknown>
  /** 解析该节点当前将被执行函数在用户所见目录中的版本；缺失时返回 null。 */
  resolveFunctionVersion: (nodeId: string) => CanvasFunctionVersionDTO | null
}): FunctionRunActions {
  const { canvasId, viewId, queryClient, setToast, flushFunctionConfig, resolveFunctionVersion } = options
  const { t } = useI18n()
  const inFlightNodesRef = useRef(new Set<string>())
  const [localPendingErrors, setLocalPendingErrors] = useState<LocalPendingErrors>({})

  const isNodeInFlight = useCallback((nodeId: string): boolean => {
    return inFlightNodesRef.current.has(nodeId)
  }, [])

  const discardPendingRun = useCallback((nodeId: string, expectedRaw: string): boolean => {
    // 1. 禁止在 flight 时 discard
    if (inFlightNodesRef.current.has(nodeId)) {
      return false
    }

    try {
      const deleted = discardPendingFunctionRun(canvasId, nodeId, expectedRaw, viewId)
      if (deleted) {
        forgetPendingError(setLocalPendingErrors, nodeId)
      }
      return deleted
    } catch (err) {
      console.error('[canvas] Failed to discard pending function run attempt:', err)
      setToast(t('canvas.toast.runStorageUnreadable'))
      return false
    }
  }, [canvasId, setToast, t, viewId])

  const publishRun = useCallback((run: CanvasFunctionRunDTO, basisRequestId: string | null) => {
    queryClient.setQueryData<CanvasSnapshotDTO>(
      queryKeys.studio.canvas(canvasId),
      (current) => patchSnapshotRun(current, run, basisRequestId),
    )
    if (run.status === 'SUCCEEDED') {
      void queryClient.invalidateQueries({ queryKey: queryKeys.studio.canvas(canvasId) })
    }
  }, [canvasId, queryClient])

  /** 捕获该 node 当前缓存的 run.requestId（可 null）作为本地投影的 CAS basis。 */
  const readBasisRequestId = useCallback((nodeId: string): string | null => {
    const current = queryClient.getQueryData<CanvasSnapshotDTO>(queryKeys.studio.canvas(canvasId))
    return current?.nodes.find((node) => node.id === nodeId)?.run?.requestId ?? null
  }, [canvasId, queryClient])

  const startFunctionRun = useCallback(async (nodeId: string) => {
    if (inFlightNodesRef.current.has(nodeId)) {
      return
    }
    inFlightNodesRef.current.add(nodeId)
    try {
      // 1. 点击瞬间同步捕获目录版本：早于任何 await，之后目录推进也不替换。
      const clickedVersion = captureClickedVersion(resolveFunctionVersion, nodeId)

      // 2. 读取未决记录。坏缓存按本次读到的 raw 确认后丢弃，且绝不在本次点击补版本或自动启动；
      //    存储读取失败则保留原始字节。两种情况都 0 flush 0 POST。
      let existing: PendingFunctionRunAttempt | null
      try {
        existing = loadPendingFunctionRun(canvasId, nodeId, viewId)
      } catch (err) {
        reportPendingLoadFailure(err, {
          canvasId,
          viewId,
          nodeId,
          setLocalPendingErrors,
          setToast,
          translate: t,
        })
        return
      }
      forgetPendingError(setLocalPendingErrors, nodeId)

      // 重放：整个请求（requestId + expectedVersion）原样沿用持久化值。
      let attempt = existing
      if (!attempt) {
        if (!clickedVersion) {
          // 缺定义：不得 flush、不得生成 requestId、不得 POST。
          setToast(t('canvas.toast.runCatalogVersionUnknown'))
          return
        }
        try {
          await flushFunctionConfig(nodeId)
        } catch (error) {
          console.error('[canvas] Failed to flush function config before run:', error)
          setToast(t('canvas.toast.runStartFailed'))
          return
        }
        attempt = {
          canvasId,
          nodeId,
          request: {
            requestId: crypto.randomUUID(),
            // 只使用点击瞬间复制的副本：绝不在 flush 之后重新读取目录版本。
            expectedVersion: clickedVersion,
          },
          basisRequestId: readBasisRequestId(nodeId),
          createdAt: Date.now(),
        }
      }

      // 3. 发请求前必须成功持久化，读回校验或并发冲突失败 fail-closed 阻断。
      try {
        savePendingFunctionRun(attempt, viewId)
      } catch (err) {
        console.error('[canvas] Failed to save pending function run attempt:', err)
        setToast(t('canvas.toast.runPendingSaveFailed'))
        return
      }

      await submitStartAttempt({ canvasId, viewId, nodeId, attempt, queryClient, publishRun, setToast, translate: t })
    } finally {
      inFlightNodesRef.current.delete(nodeId)
    }
  }, [canvasId, flushFunctionConfig, publishRun, queryClient, readBasisRequestId, resolveFunctionVersion, setToast, t, viewId])

  const cancelFunctionRun = useCallback(async (
    nodeId: string,
    requestId?: string,
  ) => {
    const targetRequestId = requestId ?? readBasisRequestId(nodeId)
    if (!targetRequestId || inFlightNodesRef.current.has(nodeId)) {
      return
    }
    inFlightNodesRef.current.add(nodeId)
    try {
      const run = await cancelCanvasFunctionRun(canvasId, nodeId, { requestId: targetRequestId })
      clearPendingFunctionRun(canvasId, nodeId, targetRequestId, viewId)
      publishRun(run, targetRequestId)
    } catch (error) {
      console.error('[canvas] Failed to cancel function run:', error)
      setToast(t('canvas.toast.runCancelFailed'))
    } finally {
      inFlightNodesRef.current.delete(nodeId)
    }
  }, [canvasId, publishRun, readBasisRequestId, setToast, t, viewId])

  const resolveFunctionRun = useCallback(async (
    nodeId: string,
    requestId: string,
    resolution: CanvasFunctionUnknownResolution,
    verification: string,
  ) => {
    if (!requestId || inFlightNodesRef.current.has(nodeId)) {
      return
    }
    inFlightNodesRef.current.add(nodeId)
    try {
      const run = await resolveCanvasFunctionRun(canvasId, nodeId, {
        requestId,
        resolution,
        verification,
      })
      clearPendingFunctionRun(canvasId, nodeId, requestId, viewId)
      publishRun(run, requestId)
    } catch (error) {
      console.error('[canvas] Failed to resolve function run:', error)
      setToast(t('canvas.toast.runResolveFailed'))
    } finally {
      inFlightNodesRef.current.delete(nodeId)
    }
  }, [canvasId, publishRun, setToast, t, viewId])

  return {
    startFunctionRun,
    cancelFunctionRun,
    resolveFunctionRun,
    discardPendingRun,
    localPendingErrors,
    isNodeInFlight,
  }
}

function forgetPendingError(
  setLocalPendingErrors: Dispatch<SetStateAction<LocalPendingErrors>>,
  nodeId: string,
): void {
  setLocalPendingErrors((prev) => {
    if (!prev[nodeId]) {
      return prev
    }
    const next = { ...prev }
    delete next[nodeId]
    return next
  })
}

/** 只复制定义指纹：之后目录对象即使被原地改写也绝不污染本次请求。缺失定义时返回 null。 */
function captureClickedVersion(
  resolveFunctionVersion: (nodeId: string) => CanvasFunctionVersionDTO | null,
  nodeId: string,
): CanvasFunctionVersionDTO | null {
  const resolvedVersion = resolveFunctionVersion(nodeId)
  return resolvedVersion === null
    ? null
    : { definitionFingerprint: resolvedVersion.definitionFingerprint }
}

/** 未决记录读取失败：坏缓存按同 raw 精确丢弃并提示，存储读取失败保留原始字节供面板手动放弃。 */
function reportPendingLoadFailure(
  err: unknown,
  context: {
    canvasId: string
    viewId?: string
    nodeId: string
    setLocalPendingErrors: Dispatch<SetStateAction<LocalPendingErrors>>
    setToast: (toast: string) => void
    translate: Translate
  },
): void {
  const { canvasId, viewId, nodeId, setLocalPendingErrors, setToast, translate } = context
  const message = err instanceof Error ? err.message : String(err)
  console.error('[canvas] Failed to load pending function run attempt:', message)

  if (err instanceof PendingRunCorruptError) {
    // 只按本次 load 得到的同一份 raw 确认删除：记录已被其它标签页替换或删除失败时
    // 保留现状，绝不盲删，也绝不在本次点击自动启动。
    let discarded = false
    try {
      discarded = discardPendingFunctionRun(canvasId, nodeId, err.raw, viewId)
    } catch (discardError) {
      console.error('[canvas] Failed to discard invalid pending function run attempt:', discardError)
    }
    if (discarded) {
      forgetPendingError(setLocalPendingErrors, nodeId)
      setToast(translate('canvas.toast.runPendingCorruptDiscarded'))
    } else {
      setLocalPendingErrors((prev) => ({
        ...prev,
        [nodeId]: { raw: err.raw },
      }))
      setToast(translate('canvas.toast.runPendingCorruptNotDiscarded'))
    }
    return
  }

  setLocalPendingErrors((prev) => ({
    ...prev,
    [nodeId]: { raw: readRawPendingRun(canvasId, nodeId, viewId) },
  }))
  setToast(translate('canvas.toast.runPendingUnreadable'))
}

/** 读取原始字节用于面板展示；存储不可用时返回 null（面板此时仅展示读取失败提示）。 */
function readRawPendingRun(canvasId: string, nodeId: string, viewId?: string): string | null {
  try {
    const storage = getLocalStorageSafe()
    return storage ? storage.getItem(getPendingRunStorageKey(canvasId, nodeId, viewId)) : null
  } catch {
    return null
  }
}

/** 已持久化的请求只按原样提交；任何失败都不自动重提、不换 requestId。 */
async function submitStartAttempt(context: {
  canvasId: string
  viewId?: string
  nodeId: string
  attempt: PendingFunctionRunAttempt
  queryClient: QueryClient
  publishRun: (run: CanvasFunctionRunDTO, basisRequestId: string | null) => void
  setToast: (toast: string) => void
  translate: Translate
}): Promise<void> {
  const { canvasId, viewId, nodeId, attempt, queryClient, publishRun, setToast, translate } = context
  const basisRequestId = attempt.basisRequestId
  const requestId = attempt.request.requestId
  try {
    const run = await startCanvasFunctionRun(canvasId, nodeId, attempt.request)
    clearPendingFunctionRun(canvasId, nodeId, requestId, viewId)
    publishRun(run, basisRequestId)
  } catch (error) {
    // 409 表示本次接纳被服务端拒绝：可能是目录版本围栏（目录已变化），也可能是运行状态
    // 冲突（例如已有 active request）。两种情况下都清掉该 pending、提示刷新目录/状态后
    // 由用户重新确认，绝不自动重提、绝不换 requestId / 换版本。
    if (error instanceof ApiError && error.status === 409) {
      clearPendingFunctionRun(canvasId, nodeId, requestId, viewId)
      void queryClient.invalidateQueries({ queryKey: queryKeys.studio.canvasFunctions })
      setToast(translate('canvas.toast.runRequestRejected'))
      return
    }
    if (isTerminalClientError(error)) {
      clearPendingFunctionRun(canvasId, nodeId, requestId, viewId)
      console.error('[canvas] Function run start was rejected:', error)
      setToast(translate('canvas.toast.runStartFailed'))
      return
    }

    try {
      const current = await getCanvasFunctionRun(canvasId, nodeId)
      if (current.requestId === requestId) {
        clearPendingFunctionRun(canvasId, nodeId, requestId, viewId)
        publishRun(current, basisRequestId)
        return
      }
    } catch {
      // 对账失败保留原 attempt 供重试
    }
    console.error('[canvas] Function run start failed:', error)
    setToast(translate('canvas.toast.runStartFailed'))
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
