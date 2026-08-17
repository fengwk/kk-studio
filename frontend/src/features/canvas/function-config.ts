import { useCallback, useRef } from 'react'
import type { PendingFunctionConfig } from '@/features/canvas/types'
import type {
  CanvasCommandDTO,
  CanvasFunctionConfigDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'

export interface FunctionConfigSync {
  scheduleFunctionConfig: (
    nodeId: UUIDString,
    modelKey: string,
    config: CanvasFunctionConfigDTO,
  ) => void
  flushFunctionConfig: (nodeId: UUIDString) => Promise<void>
  /** 离开/切换画布时清空未提交草稿与定时器（不中断进行中的 flush 循环）。 */
  resetPending: () => void
}

/**
 * Function config 的 debounce/flush 专用 hook。
 *
 * - schedule：320ms 防抖聚合同节点的最新草稿；
 * - flush：并发去重（同一节点只跑一个 flush 循环），失败保留草稿以便重试，成功后按
 *   提交时的 pending 对象精确清除；start 流程先 flush 再发 run 请求，保证配置先落库。
 */
export function useFunctionConfigSync(
  executeCommands: (commands: CanvasCommandDTO[]) => Promise<unknown>,
): FunctionConfigSync {
  const pendingFunctionConfigsRef = useRef(new Map<UUIDString, PendingFunctionConfig>())
  const functionConfigTimersRef = useRef(new Map<UUIDString, number>())
  const functionConfigFlushesRef = useRef(new Map<UUIDString, Promise<void>>())

  const flushFunctionConfig = useCallback((nodeId: UUIDString): Promise<void> => {
    const timer = functionConfigTimersRef.current.get(nodeId)
    if (timer !== undefined) {
      window.clearTimeout(timer)
      functionConfigTimersRef.current.delete(nodeId)
    }
    const existing = functionConfigFlushesRef.current.get(nodeId)
    if (existing) {
      return existing
    }
    const trackedFlush = (async () => {
      while (true) {
        const pending = pendingFunctionConfigsRef.current.get(nodeId)
        if (!pending) {
          return
        }
        await executeCommands([{
          type: 'UPDATE_FUNCTION',
          nodeId: pending.nodeId,
          modelKey: pending.modelKey,
          configJson: JSON.stringify(pending.config),
        }])
        if (pendingFunctionConfigsRef.current.get(nodeId) === pending) {
          pendingFunctionConfigsRef.current.delete(nodeId)
        }
      }
    })().finally(() => {
      if (functionConfigFlushesRef.current.get(nodeId) === trackedFlush) {
        functionConfigFlushesRef.current.delete(nodeId)
      }
    })
    functionConfigFlushesRef.current.set(nodeId, trackedFlush)
    return trackedFlush
  }, [executeCommands])

  const scheduleFunctionConfig = useCallback((
    nodeId: UUIDString,
    modelKey: string,
    config: CanvasFunctionConfigDTO,
  ) => {
    pendingFunctionConfigsRef.current.set(nodeId, { nodeId, modelKey, config })
    const existing = functionConfigTimersRef.current.get(nodeId)
    if (existing !== undefined) {
      window.clearTimeout(existing)
    }
    const timer = window.setTimeout(() => {
      functionConfigTimersRef.current.delete(nodeId)
      void flushFunctionConfig(nodeId).catch(() => undefined)
    }, 320)
    functionConfigTimersRef.current.set(nodeId, timer)
  }, [flushFunctionConfig])

  const resetPending = useCallback(() => {
    for (const timer of functionConfigTimersRef.current.values()) {
      window.clearTimeout(timer)
    }
    functionConfigTimersRef.current.clear()
    pendingFunctionConfigsRef.current.clear()
  }, [])

  return { scheduleFunctionConfig, flushFunctionConfig, resetPending }
}
