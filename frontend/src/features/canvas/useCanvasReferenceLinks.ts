import { useCallback } from 'react'
import { currentCanvasSnapshot, publishDrafts, type CanvasScope } from '@/features/canvas/canvas-controller-scope'
import type { CanvasDraftAck } from '@/features/canvas/canvas-operation-storage'
import { readResourceReferences, withDraftReferences } from '@/features/canvas/canvas-references'
import { functionSupportsReferences } from '@/features/canvas/generation'
import type { CanvasResourceReference } from '@/features/canvas/types'
import type {
  CanvasCommandDTO,
  CanvasFunctionDefinitionDTO,
  CanvasSnapshotDTO,
} from '@/shared/api/generated/contracts'

interface ReferenceLinksInput {
  scope: CanvasScope
  snapshot: CanvasSnapshotDTO | undefined
  models: CanvasFunctionDefinitionDTO[] | undefined
  executeCommands: (commands: CanvasCommandDTO[], ack?: CanvasDraftAck[]) => Promise<CanvasSnapshotDTO>
  flushFunctionConfig: (nodeId: string) => Promise<unknown>
  setToast: (toast: string) => void
}

/**
 * 画布上的「引用边」：边不是独立实体，而是目标节点 function.args 中 canonical references 数组的派生投影。
 *
 * 增删边前先冲刷目标节点待提交的配置，避免与正在键入的 prompt/参数相互覆盖；本地函数草稿同步写入新的引用数组，随后以 SET_NODE_FUNCTION 提交。
 */
export function useCanvasReferenceLinks({
  scope,
  snapshot,
  models,
  executeCommands,
  flushFunctionConfig,
  setToast,
}: ReferenceLinksInput) {
  const { draftsRef } = scope

  /** 用新的引用数组同时更新本地函数草稿（若存在）并提交命令。 */
  const commitReferences = useCallback(async (
    targetNodeId: string,
    target: NonNullable<CanvasSnapshotDTO['nodes'][number]>,
    currentArgs: Record<string, unknown> | undefined,
    references: CanvasResourceReference[],
  ) => {
    const existingDraft = draftsRef.current[targetNodeId]
    if (existingDraft?.function) {
      publishDrafts(scope, {
        ...draftsRef.current,
        [targetNodeId]: withDraftReferences(existingDraft, references),
      })
    }
    await executeCommands([{
      type: 'SET_NODE_FUNCTION',
      nodeId: targetNodeId,
      expectedFunction: target.function,
      function: { name: target.function!.name, args: { ...currentArgs, references } },
    }]).catch(() => undefined)
  }, [draftsRef, executeCommands, scope])

  const createLink = useCallback(async (sourceNodeId: string, targetNodeId: string) => {
    await flushFunctionConfig(targetNodeId).catch(() => undefined)

    const current = currentCanvasSnapshot(scope, snapshot)
    const target = current?.nodes.find((n) => n.id === targetNodeId)
    if (!target || !target.function) {
      return
    }
    const currentFunction = draftsRef.current[targetNodeId]?.function ?? target.function
    if (!currentFunction) {
      return
    }
    const targetModel = models?.find((m) => m.name === currentFunction.name)
    if (!functionSupportsReferences(targetModel)) {
      return
    }
    const source = current?.nodes.find((n) => n.id === sourceNodeId)
    if (!source || source.resources.length === 0) {
      return
    }
    const sourceResource = source.resources[0]
    const policy = targetModel?.referencePolicy
    if (policy?.allowedKinds && sourceResource && !policy.allowedKinds.includes(sourceResource.kind)) {
      return
    }

    const existingReferences = readResourceReferences(currentFunction.args)
    if (existingReferences.some((ref) => ref.nodeId === sourceNodeId && ref.index === 0)) {
      return
    }
    if (existingReferences.length >= (policy?.maxReferences ?? Infinity)) {
      setToast('当前模型的参考资源数量已达到上限。')
      return
    }
    const kindLimit = sourceResource ? policy?.maxByKind?.[sourceResource.kind] : undefined
    if (sourceResource && kindLimit !== undefined) {
      const kindCount = existingReferences.filter((ref) => (
        current?.nodes.find((n) => n.id === ref.nodeId)?.resources[ref.index]?.kind === sourceResource.kind
      )).length
      if (kindCount >= kindLimit) {
        setToast('当前模型的该类参考资源数量已达到上限。')
        return
      }
    }

    await commitReferences(targetNodeId, target, currentFunction.args, [
      ...existingReferences,
      { type: 'resource', nodeId: sourceNodeId, index: 0 },
    ])
  }, [commitReferences, draftsRef, flushFunctionConfig, models, scope, setToast, snapshot])

  const deleteLink = useCallback(async (sourceNodeId: string, targetNodeId: string) => {
    await flushFunctionConfig(targetNodeId).catch(() => undefined)

    const target = currentCanvasSnapshot(scope, snapshot)?.nodes.find((n) => n.id === targetNodeId)
    if (!target || !target.function) {
      return
    }
    const currentFunction = draftsRef.current[targetNodeId]?.function ?? target.function
    if (!currentFunction) {
      return
    }
    // 删除边仅删匹配 source，保留其余索引及其他 source
    const remainingReferences = readResourceReferences(currentFunction.args)
      .filter((ref) => ref.nodeId !== sourceNodeId)
    await commitReferences(targetNodeId, target, currentFunction.args, remainingReferences)
  }, [commitReferences, draftsRef, flushFunctionConfig, scope, snapshot])

  return { createLink, deleteLink }
}
