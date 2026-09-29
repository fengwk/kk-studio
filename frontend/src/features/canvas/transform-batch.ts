import { useCallback, useEffect, useRef } from 'react'
import { isNodeCompletelyOutsideGroup } from '@/features/canvas/group-membership'
import { groupIdFromFlowId } from '@/features/canvas/projection'
import type { CanvasDraftAck } from '@/features/canvas/canvas-operation-storage'
import type { CanvasPositionUpdate } from '@/features/canvas/types'
import type {
  CanvasCommandDTO,
  CanvasSnapshotDTO,
  CanvasTransformDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'

/** 拖拽结束后聚合 transform 批次提交的防抖窗口。 */
const TRANSFORM_FLUSH_DEBOUNCE_MS = 180

export interface CanvasTransformBatchOptions {
  snapshot: CanvasSnapshotDTO | undefined
  executeCommands: (commands: CanvasCommandDTO[], ack?: CanvasDraftAck[]) => Promise<unknown>
  setPositionDrafts: (
    updater: (current: Record<string, { x: number; y: number }>) => Record<string, { x: number; y: number }>,
  ) => void
  getDraftGeneration?: (nodeId: string) => number | undefined
}

export interface CanvasTransformBatchActions {
  moveNodes: (updates: CanvasPositionUpdate[]) => void
  commitTransforms: () => void
  /** 立即聚合当前 pending 提交；失败保留草稿与 pending 以便下次 commit 重试。 */
  flushTransforms: () => void
  /** 离开/切换画布时清理防抖定时器与 pending（positionDrafts 由调用方一并重置）。 */
  reset: () => void
}

interface PendingBatch {
  nodeTransforms: Map<UUIDString, { transform: CanvasTransformDTO; expectedTransform?: CanvasTransformDTO | null }>
  groupMoves: Map<UUIDString, { position: { x: number; y: number }; expectedTransform?: CanvasTransformDTO | null }>
  /** 每个被移动 node 的显式决策：拖出所属组 → groupId；拖回组内/无组 → null。 */
  ungroups: Map<UUIDString, UUIDString | null>
}

interface SubmittedBatch extends PendingBatch {
  commands: CanvasCommandDTO[]
  drafts: Record<string, { x: number; y: number }>
  acks: CanvasDraftAck[]
}

export function useCanvasTransformBatch(options: CanvasTransformBatchOptions): CanvasTransformBatchActions {
  const { snapshot, executeCommands, setPositionDrafts, getDraftGeneration } = options
  const pendingNodeTransformsRef = useRef<PendingBatch['nodeTransforms']>(new Map())
  const pendingGroupMovesRef = useRef<PendingBatch['groupMoves']>(new Map())
  const pendingUngroupsRef = useRef<PendingBatch['ungroups']>(new Map())
  const getDraftGenerationRef = useRef(getDraftGeneration)
  const timerRef = useRef<number | null>(null)
  const inFlightEpochRef = useRef<number | null>(null)
  const flushRequestedRef = useRef(false)
  const epochRef = useRef(0)

  useEffect(() => {
    getDraftGenerationRef.current = getDraftGeneration
  }, [getDraftGeneration])

  useEffect(() => () => {
    epochRef.current += 1
    flushRequestedRef.current = false
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current)
    }
  }, [])

  const reset = useCallback(() => {
    epochRef.current += 1
    flushRequestedRef.current = false
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current)
      timerRef.current = null
    }
    pendingNodeTransformsRef.current.clear()
    pendingGroupMovesRef.current.clear()
    pendingUngroupsRef.current.clear()
  }, [])

  const flushTransformsRef = useRef<() => void>(() => undefined)

  const scheduleTransformFlush = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current)
    }
    timerRef.current = window.setTimeout(() => {
      flushTransformsRef.current()
    }, TRANSFORM_FLUSH_DEBOUNCE_MS)
  }, [])

  const commitTransforms = useCallback(() => {
    scheduleTransformFlush()
  }, [scheduleTransformFlush])

  const flushTransforms = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current)
      timerRef.current = null
    }
    if (inFlightEpochRef.current === epochRef.current) {
      flushRequestedRef.current = true
      return
    }
    const batch = takePendingBatch(
      snapshot,
      pendingNodeTransformsRef.current,
      pendingGroupMovesRef.current,
      pendingUngroupsRef.current,
      getDraftGenerationRef.current,
    )
    if (batch.commands.length === 0) {
      return
    }
    const epoch = epochRef.current
    inFlightEpochRef.current = epoch
    flushRequestedRef.current = false
    let failed = false
    void executeCommands(batch.commands, batch.acks)
      .then(() => {
        if (epochRef.current !== epoch) {
          return
        }
        setPositionDrafts((current) => removeSubmittedDrafts(current, batch.drafts))
      })
      .catch(() => {
        if (epochRef.current !== epoch) {
          return
        }
        failed = true
        restorePending(batch, {
          nodeTransforms: pendingNodeTransformsRef.current,
          groupMoves: pendingGroupMovesRef.current,
          ungroups: pendingUngroupsRef.current,
        })
      })
      .finally(() => {
        if (epochRef.current !== epoch) {
          return
        }
        inFlightEpochRef.current = null
        const flushRequested = flushRequestedRef.current
        flushRequestedRef.current = false
        if ((!failed || flushRequested) && hasPendingTransforms(
          pendingNodeTransformsRef.current,
          pendingGroupMovesRef.current,
          pendingUngroupsRef.current,
        )) {
          scheduleTransformFlush()
        }
      })
  }, [executeCommands, scheduleTransformFlush, setPositionDrafts, snapshot])

  useEffect(() => {
    flushTransformsRef.current = flushTransforms
  }, [flushTransforms])

  const moveNodes = useCallback((updates: CanvasPositionUpdate[]) => {
    if (!snapshot) {
      return
    }
    const isOffline = typeof navigator !== 'undefined' && !navigator.onLine
    setPositionDrafts((current) => {
      const positionDrafts = { ...current }
      for (const update of updates) {
        if (update.kind === 'group') {
          const groupId = groupIdFromFlowId(update.id)
          if (!groupId) {
            continue
          }
          const group = snapshot.groups.find((item) => item.id === groupId)
          if (!group) {
            continue
          }
          const position = {
            x: update.transform.x,
            y: update.transform.y,
          }
          positionDrafts[update.id] = position
          pendingGroupMovesRef.current.set(groupId, {
            position,
            expectedTransform: isOffline ? group.transform : null,
          })
        } else {
          positionDrafts[update.id] = {
            x: update.transform.x,
            y: update.transform.y,
          }
          const nodeId = update.id as UUIDString
          const node = snapshot.nodes.find((item) => item.id === nodeId)
          pendingNodeTransformsRef.current.set(nodeId, {
            transform: update.transform,
            expectedTransform: isOffline ? node?.transform : null,
          })
          const group = node?.groupId
            ? snapshot.groups.find((item) => item.id === node.groupId)
            : null
          if (
            node?.groupId
            && group
            && isNodeCompletelyOutsideGroup(update.transform, group.transform)
          ) {
            pendingUngroupsRef.current.set(nodeId, node.groupId)
          } else {
            pendingUngroupsRef.current.set(nodeId, null)
          }
        }
      }
      return positionDrafts
    })
  }, [setPositionDrafts, snapshot])

  return { moveNodes, commitTransforms, flushTransforms, reset }
}

function takePendingBatch(
  snapshot: CanvasSnapshotDTO | undefined,
  pendingNodeTransforms: PendingBatch['nodeTransforms'],
  pendingGroupMoves: PendingBatch['groupMoves'],
  pendingUngroups: PendingBatch['ungroups'],
  getDraftGeneration?: (nodeId: string) => number | undefined,
): SubmittedBatch {
  const nodeTransforms = new Map(pendingNodeTransforms)
  const groupMoves = new Map(pendingGroupMoves)
  const ungroups = new Map(pendingUngroups)
  pendingNodeTransforms.clear()
  pendingGroupMoves.clear()
  pendingUngroups.clear()
  return {
    ...submissionOf(snapshot, nodeTransforms, groupMoves, ungroups, getDraftGeneration),
    nodeTransforms,
    groupMoves,
    ungroups,
  }
}

function submissionOf(
  snapshot: CanvasSnapshotDTO | undefined,
  nodeTransforms: PendingBatch['nodeTransforms'],
  groupMoves: PendingBatch['groupMoves'],
  ungroups: PendingBatch['ungroups'],
  getDraftGeneration?: (nodeId: string) => number | undefined,
): Pick<SubmittedBatch, 'commands' | 'drafts' | 'acks'> {
  const commands: CanvasCommandDTO[] = []
  const drafts: Record<string, { x: number; y: number }> = {}
  const acks: CanvasDraftAck[] = []

  for (const [nodeId, item] of nodeTransforms) {
    drafts[nodeId] = { x: item.transform.x, y: item.transform.y }
    const generation = getDraftGeneration?.(nodeId) ?? 0
    acks.push({ nodeId, field: 'position', generation })
    commands.push({
      type: 'UPDATE_NODE_TRANSFORM',
      nodeId,
      transform: item.transform,
      ...(item.expectedTransform ? { expectedTransform: item.expectedTransform } : {}),
    })
  }

  for (const [groupId, item] of groupMoves) {
    drafts[`group:${groupId}`] = item.position
    const group = snapshot?.groups.find((g) => g.id === groupId)
    const transform: CanvasTransformDTO = group
      ? { ...group.transform, x: item.position.x, y: item.position.y }
      : { x: item.position.x, y: item.position.y, width: 240, height: 160 }
    commands.push({
      type: 'UPDATE_GROUP_TRANSFORM',
      groupId,
      transform,
      ...(item.expectedTransform ? { expectedTransform: item.expectedTransform } : {}),
    })
  }

  for (const [nodeId, groupId] of ungroups) {
    if (groupId === null) {
      continue
    }
    commands.push({
      type: 'SET_NODE_GROUP',
      nodeId,
      expectedGroupId: groupId,
      groupId: null,
    })
  }

  return { commands, drafts, acks }
}

function restorePending(
  batch: SubmittedBatch,
  targets: {
    nodeTransforms: PendingBatch['nodeTransforms']
    groupMoves: PendingBatch['groupMoves']
    ungroups: PendingBatch['ungroups']
  },
): void {
  for (const [nodeId, item] of batch.nodeTransforms) {
    if (!targets.nodeTransforms.has(nodeId)) {
      targets.nodeTransforms.set(nodeId, item)
    }
  }
  for (const [groupId, item] of batch.groupMoves) {
    if (!targets.groupMoves.has(groupId)) {
      targets.groupMoves.set(groupId, item)
    }
  }
  for (const [nodeId, groupId] of batch.ungroups) {
    if (!targets.ungroups.has(nodeId)) {
      targets.ungroups.set(nodeId, groupId)
    }
  }
}

function hasPendingTransforms(
  pendingNodeTransforms: PendingBatch['nodeTransforms'],
  pendingGroupMoves: PendingBatch['groupMoves'],
  pendingUngroups: PendingBatch['ungroups'],
): boolean {
  return pendingNodeTransforms.size > 0
    || pendingGroupMoves.size > 0
    || pendingUngroups.size > 0
}

function removeSubmittedDrafts(
  current: Record<string, { x: number; y: number }>,
  submitted: Record<string, { x: number; y: number }>,
): Record<string, { x: number; y: number }> {
  const next = { ...current }
  for (const [id, position] of Object.entries(submitted)) {
    const draft = next[id]
    if (draft?.x === position.x && draft.y === position.y) {
      delete next[id]
    }
  }
  return next
}
