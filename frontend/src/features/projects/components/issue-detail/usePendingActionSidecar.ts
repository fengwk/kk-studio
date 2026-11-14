import { useEffect, useState } from 'react'
import {
  loadPendingAction,
  type LoadPendingActionResult,
} from '../../pending-action-sidecar'

function safeLoadPendingAction(issueId: string | null): LoadPendingActionResult {
  if (!issueId) {
    return { type: 'NONE' }
  }
  try {
    return loadPendingAction(issueId)
  } catch (err) {
    return {
      type: 'STORAGE_ERROR',
      error: err instanceof Error ? err.message : String(err),
    }
  }
}

interface UsePendingActionSidecarInput {
  isOpen: boolean
  issueId: string | null
}

/**
 * Issue 写操作侧车的读取状态。
 *
 * <p>挂载、打开或切换 Issue 时重新读取；关闭后立刻回到 NONE，避免把上一个 Issue 的
 * 未决操作展示到新上下文。写入侧（落盘与清理）由执行器负责，这里只承载读结果。
 */
export function usePendingActionSidecar({ isOpen, issueId }: UsePendingActionSidecarInput) {
  const [pendingActionResult, setPendingActionResult] = useState<LoadPendingActionResult>(
    () => safeLoadPendingAction(issueId),
  )

  useEffect(() => {
    if (!issueId || !isOpen) {
      setPendingActionResult({ type: 'NONE' })
      return
    }
    setPendingActionResult(safeLoadPendingAction(issueId))
  }, [issueId, isOpen])

  return {
    pendingActionResult,
    setPendingActionResult,
    pendingUnknownAction:
      pendingActionResult.type === 'VALID' ? pendingActionResult.action : null,
    corruptActionError: pendingActionResult.type === 'CORRUPT' ? pendingActionResult.error : null,
    storageLoadError:
      pendingActionResult.type === 'STORAGE_ERROR' ? pendingActionResult.error : null,
  }
}
