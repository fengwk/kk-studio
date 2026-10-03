import { useCallback, useEffect, useRef } from 'react'
import type { CanvasDraftAck } from '@/features/canvas/canvas-operation-storage'
import type { CanvasFunctionConfig, PendingFunctionConfig } from '@/features/canvas/types'
import { configToFunctionArgs } from '@/features/canvas/generation'
import type {
  CanvasCommandDTO,
  CanvasFunctionDefinitionDTO,
  CanvasSnapshotDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'

export interface FunctionConfigSyncOptions {
  executeCommands: (commands: CanvasCommandDTO[], ack?: CanvasDraftAck[]) => Promise<unknown>
  getSnapshot?: () => CanvasSnapshotDTO | undefined
  getModel?: (functionName: string) => CanvasFunctionDefinitionDTO | undefined
  onImmediateDraft?: (nodeId: UUIDString, functionName: string, config: CanvasFunctionConfig) => number
  getDraftGeneration?: (nodeId: UUIDString) => number | undefined
}

export interface FunctionConfigSync {
  scheduleFunctionConfig: (
    nodeId: UUIDString,
    functionName: string,
    config: CanvasFunctionConfig,
  ) => void
  flushFunctionConfig: (nodeId: UUIDString) => Promise<void>
  /** 离开/切换画布时清空未提交草稿与定时器（不中断进行中的 flush 循环）。 */
  resetPending: () => void
}

interface PendingItem extends PendingFunctionConfig {
  generation: number
}

/**
 * Function config 的 debounce/flush 专用 hook。
 *
 * 依据 docs/canvas-project.md：
 * - 每次编辑瞬间立即写入持久草稿层并推进世代，即使在 320ms 防抖窗口内硬刷新也绝不丢失；
 * - 防抖仅控制网络发送，不控制本地持久化；
 * - flush 时携带 { nodeId, field: 'function', generation } ACK，通过同一事实源精确确认与清理。
 */
export function useFunctionConfigSync(
  options: FunctionConfigSyncOptions,
): FunctionConfigSync {
  const { executeCommands, getSnapshot, getModel, onImmediateDraft, getDraftGeneration } = options

  const executeCommandsRef = useRef(executeCommands)
  const getSnapshotRef = useRef(getSnapshot)
  const getModelRef = useRef(getModel)
  const onImmediateDraftRef = useRef(onImmediateDraft)
  const getDraftGenerationRef = useRef(getDraftGeneration)

  useEffect(() => {
    executeCommandsRef.current = executeCommands
    getSnapshotRef.current = getSnapshot
    getModelRef.current = getModel
    onImmediateDraftRef.current = onImmediateDraft
    getDraftGenerationRef.current = getDraftGeneration
  }, [executeCommands, getSnapshot, getModel, onImmediateDraft, getDraftGeneration])

  const pendingFunctionConfigsRef = useRef(new Map<UUIDString, PendingItem>())
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
        const snapshot = getSnapshotRef.current?.()
        const node = snapshot?.nodes.find((item) => item.id === nodeId)
        const expectedFunction = node?.function ? { name: node.function.name, args: node.function.args } : null
        const model = getModelRef.current?.(pending.functionName)
        const args = configToFunctionArgs(pending.config, model)
        await executeCommandsRef.current([{
          type: 'SET_NODE_FUNCTION',
          nodeId: pending.nodeId,
          expectedFunction,
          function: {
            name: pending.functionName,
            args,
          },
        }], [{
          nodeId: pending.nodeId,
          field: 'function',
          generation: pending.generation,
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
  }, [])

  const scheduleFunctionConfig = useCallback((
    nodeId: UUIDString,
    functionName: string,
    config: CanvasFunctionConfig,
  ) => {
    // 每次编辑立即进持久草稿层，获取新递增的世代号
    const generation = onImmediateDraftRef.current?.(nodeId, functionName, config)
      ?? getDraftGenerationRef.current?.(nodeId)
      ?? 0

    pendingFunctionConfigsRef.current.set(nodeId, {
      nodeId,
      functionName,
      config,
      generation,
    })
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
