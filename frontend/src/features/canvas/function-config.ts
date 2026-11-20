import { useCallback, useEffect, useRef } from 'react'
import type { CanvasDraftAck } from '@/features/canvas/canvas-operation-storage'
import type { CanvasFunctionConfig, PendingFunctionConfig } from '@/features/canvas/types'
import { configToFunctionArgs } from '@/features/canvas/generation'
import type {
  CanvasCommandDTO,
  CanvasFunctionDefinitionDTO,
  CanvasFunctionDTO,
  CanvasSnapshotDTO,
} from '@/shared/api/generated/contracts'

export interface FunctionConfigSyncOptions {
  executeCommands: (commands: CanvasCommandDTO[], ack?: CanvasDraftAck[]) => Promise<unknown>
  getSnapshot?: () => CanvasSnapshotDTO | undefined
  getModel?: (functionName: string) => CanvasFunctionDefinitionDTO | undefined
  onImmediateDraft?: (nodeId: string, functionName: string, config: CanvasFunctionConfig) => number
  getDraftGeneration?: (nodeId: string) => number | undefined
}

export interface FunctionConfigSync {
  scheduleFunctionConfig: (
    nodeId: string,
    functionName: string,
    config: CanvasFunctionConfig,
  ) => false | void
  flushFunctionConfig: (nodeId: string) => Promise<CanvasFunctionDTO | undefined>
  /** 冻结已有待保存项，删除完成前不接收新草稿或重复删除。 */
  lockFunctionConfigsForDeletion: (nodeIds: string[]) => (() => void) | undefined
  /** 离开/切换画布时清空草稿与定时器，不再为旧 flush 发送后续命令。 */
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

  const pendingFunctionConfigsRef = useRef(new Map<string, PendingItem>())
  const functionConfigTimersRef = useRef(new Map<string, number>())
  const functionConfigFlushesRef = useRef(new Map<string, Promise<CanvasFunctionDTO | undefined>>())
  const deletingNodeIdsRef = useRef(new Set<string>())

  const lockFunctionConfigsForDeletion = useCallback((nodeIds: string[]) => {
    const deleting = deletingNodeIdsRef.current
    if (nodeIds.some((id) => deleting.has(id))) {
      return undefined
    }
    nodeIds.forEach((id) => deleting.add(id))
    return () => nodeIds.forEach((id) => deleting.delete(id))
  }, [])

  const flushFunctionConfig = useCallback((nodeId: string): Promise<CanvasFunctionDTO | undefined> => {
    const timer = functionConfigTimersRef.current.get(nodeId)
    if (timer !== undefined) {
      window.clearTimeout(timer)
      functionConfigTimersRef.current.delete(nodeId)
    }
    const flushes = functionConfigFlushesRef.current
    const existing = flushes.get(nodeId)
    if (existing) {
      return existing
    }
    const pendingConfigs = pendingFunctionConfigsRef.current
    const trackedFlush = (async () => {
      let committedFunction: CanvasFunctionDTO | undefined
      while (true) {
        const pending = pendingConfigs.get(nodeId)
        if (!pending) {
          return committedFunction
        }
        const snapshot = getSnapshotRef.current?.()
        const node = snapshot?.nodes.find((item) => item.id === nodeId)
        const expectedFunction = node?.function ? { name: node.function.name, args: node.function.args } : null
        const model = getModelRef.current?.(pending.functionName)
        const nextFunction = {
          name: pending.functionName,
          args: configToFunctionArgs(pending.config, model),
        }
        await executeCommandsRef.current([{
          type: 'SET_NODE_FUNCTION',
          nodeId: pending.nodeId,
          expectedFunction,
          function: nextFunction,
        }], [{
          nodeId: pending.nodeId,
          field: 'function',
          generation: pending.generation,
        }])
        committedFunction = nextFunction
        if (pendingConfigs.get(nodeId) === pending) {
          pendingConfigs.delete(nodeId)
        }
      }
    })().finally(() => {
      flushes.delete(nodeId)
    })
    flushes.set(nodeId, trackedFlush)
    return trackedFlush
  }, [])

  const scheduleFunctionConfig = useCallback((
    nodeId: string,
    functionName: string,
    config: CanvasFunctionConfig,
  ) => {
    if (deletingNodeIdsRef.current.has(nodeId)
      || (getSnapshotRef.current && !getSnapshotRef.current()?.nodes.some((node) => node.id === nodeId))) {
      return false
    }
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
    pendingFunctionConfigsRef.current = new Map()
    functionConfigFlushesRef.current = new Map()
    deletingNodeIdsRef.current = new Set()
  }, [])

  return { scheduleFunctionConfig, flushFunctionConfig, lockFunctionConfigsForDeletion, resetPending }
}
