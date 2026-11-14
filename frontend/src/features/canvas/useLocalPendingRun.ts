import { useEffect, useState } from 'react'
import { inspectLocalPendingRun } from '@/features/canvas/function-run'

export interface LocalPendingRun {
  message: string
  raw: string | null
}

/**
 * 面板展示的本地未决运行记录：运行时已经捕获的错误优先，否则直接检查 localStorage。
 * 记录损坏时仍需让用户看到并手动确认丢弃，因此这里只读不清理；丢弃成功后由调用方置空。
 */
export function useLocalPendingRun(
  canvasId: string | null,
  nodeId: string,
  runtimeErrors: Record<string, LocalPendingRun> | undefined,
): [LocalPendingRun | null, (next: LocalPendingRun | null) => void] {
  const [state, setState] = useState<LocalPendingRun | null>(null)
  const runtimeError = runtimeErrors?.[nodeId]

  useEffect(() => {
    if (runtimeError) {
      setState(runtimeError)
      return
    }
    if (!canvasId) {
      return
    }
    const inspected = inspectLocalPendingRun(canvasId, nodeId)
    setState(inspected.error && inspected.raw !== null
      ? { message: inspected.error, raw: inspected.raw }
      : null)
  }, [canvasId, nodeId, runtimeError])

  return [state, setState]
}
