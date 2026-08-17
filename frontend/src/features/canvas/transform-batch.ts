import { useCallback, useEffect, useRef } from 'react'
import { isNodeCompletelyOutsideGroup } from '@/features/canvas/group-membership'
import { groupIdFromFlowId } from '@/features/canvas/projection'
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
  executeCommands: (commands: CanvasCommandDTO[]) => Promise<unknown>
  setPositionDrafts: (
    updater: (current: Record<string, { x: number; y: number }>) => Record<string, { x: number; y: number }>,
  ) => void
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
  nodeTransforms: Map<UUIDString, CanvasTransformDTO>
  groupMoves: Map<UUIDString, { x: number; y: number }>
  /** 每个被移动 node 的显式决策：拖出所属组 → groupId；拖回组内/无组 → null。 */
  ungroups: Map<UUIDString, UUIDString | null>
}

interface SubmittedBatch extends PendingBatch {
  commands: CanvasCommandDTO[]
  drafts: Record<string, { x: number; y: number }>
}

/**
 * Node/Group transform 的防抖批处理状态机。
 *
 * - moveNodes 只追加 pending 与 positionDrafts，commit 后 180ms 防抖聚合提交；
 * - flush 的 in-flight 占用按 epoch 归属：reset/卸载（epoch+1）后新 canvas 的 flush
 *   立即可开始；旧批次迟到的 finally 既不释放新 owner 的占用，也不调度旧 canvas 的批次；
 * - flush 期间的新移动进入下一批；成功只清理「提交时快照」对应的草稿（值未变化才删，
 *   期间被继续拖动的草稿保留）；
 * - 失败恢复 pending（不覆盖期间更新的键/决策）并保留草稿，等待下次 commit 重试，绝不自动重试；
 * - reset/卸载只清定时器与 pending，不触碰草稿。
 */
export function useCanvasTransformBatch(options: CanvasTransformBatchOptions): CanvasTransformBatchActions {
  const { snapshot, executeCommands, setPositionDrafts } = options
  const pendingNodeTransformsRef = useRef(new Map<UUIDString, CanvasTransformDTO>())
  const pendingGroupMovesRef = useRef(new Map<UUIDString, { x: number; y: number }>())
  const pendingUngroupsRef = useRef(new Map<UUIDString, UUIDString | null>())
  const timerRef = useRef<number | null>(null)
  const inFlightEpochRef = useRef<number | null>(null)
  const flushRequestedRef = useRef(false)
  const epochRef = useRef(0)

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
    // in-flight 占用按 epoch 归属：同一 epoch 内只允许一个批次在途；
    // reset/unmount（epoch+1）后旧批次不再占用，新 canvas 的 flush 立即放行。
    if (inFlightEpochRef.current === epochRef.current) {
      flushRequestedRef.current = true
      return
    }
    const batch = takePendingBatch(
      pendingNodeTransformsRef.current,
      pendingGroupMovesRef.current,
      pendingUngroupsRef.current,
    )
    if (batch.commands.length === 0) {
      return
    }
    const epoch = epochRef.current
    inFlightEpochRef.current = epoch
    flushRequestedRef.current = false
    let failed = false
    void executeCommands(batch.commands)
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
        // 只有当前归属的批次才释放占用并决定 drain；跨 epoch 的迟到 finally
        // 既不清除新 owner 的占用，也不为旧 canvas 调度 flush。
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
  }, [executeCommands, scheduleTransformFlush, setPositionDrafts])

  useEffect(() => {
    flushTransformsRef.current = flushTransforms
  }, [flushTransforms])

  const moveNodes = useCallback((updates: CanvasPositionUpdate[]) => {
    if (!snapshot) {
      return
    }
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
          pendingGroupMovesRef.current.set(groupId, position)
        } else {
          positionDrafts[update.id] = {
            x: update.transform.x,
            y: update.transform.y,
          }
          const nodeId = update.id as UUIDString
          pendingNodeTransformsRef.current.set(nodeId, update.transform)
          const node = snapshot.nodes.find((item) => item.id === nodeId)
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
            // 每次 node move 都记录显式决策：拖回组内/无组 → null，
            // 避免把「从未决策」与「决定不 ungroup」混淆。
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
  pendingNodeTransforms: Map<UUIDString, CanvasTransformDTO>,
  pendingGroupMoves: Map<UUIDString, { x: number; y: number }>,
  pendingUngroups: Map<UUIDString, UUIDString | null>,
): SubmittedBatch {
  const nodeTransforms = new Map(pendingNodeTransforms)
  const groupMoves = new Map(pendingGroupMoves)
  const ungroups = new Map(pendingUngroups)
  pendingNodeTransforms.clear()
  pendingGroupMoves.clear()
  pendingUngroups.clear()
  return { ...submissionOf(nodeTransforms, groupMoves, ungroups), nodeTransforms, groupMoves, ungroups }
}

function submissionOf(
  nodeTransforms: Map<UUIDString, CanvasTransformDTO>,
  groupMoves: Map<UUIDString, { x: number; y: number }>,
  ungroups: Map<UUIDString, UUIDString | null>,
): Pick<SubmittedBatch, 'commands' | 'drafts'> {
  const commands: CanvasCommandDTO[] = []
  const drafts: Record<string, { x: number; y: number }> = {}
  for (const [nodeId, transform] of nodeTransforms) {
    drafts[nodeId] = { x: transform.x, y: transform.y }
  }
  const updates = [...nodeTransforms].map(([nodeId, transform]) => ({
    nodeId,
    transform,
  }))
  for (const [groupId, position] of groupMoves) {
    commands.push({ type: 'MOVE_GROUP', groupId, ...position })
    drafts[`group:${groupId}`] = position
  }
  if (updates.length > 0) {
    commands.push({ type: 'UPDATE_NODE_TRANSFORMS', updates })
  }
  const ungroupedByGroup = new Map<UUIDString, UUIDString[]>()
  for (const [nodeId, groupId] of ungroups) {
    if (groupId === null) {
      continue
    }
    const memberNodeIds = ungroupedByGroup.get(groupId) ?? []
    memberNodeIds.push(nodeId)
    ungroupedByGroup.set(groupId, memberNodeIds)
  }
  for (const [groupId, memberNodeIds] of ungroupedByGroup) {
    commands.push({ type: 'UNGROUP', groupId, memberNodeIds })
  }
  return { commands, drafts }
}

function restorePending(
  batch: SubmittedBatch,
  targets: {
    nodeTransforms: Map<UUIDString, CanvasTransformDTO>
    groupMoves: Map<UUIDString, { x: number; y: number }>
    ungroups: Map<UUIDString, UUIDString | null>
  },
): void {
  for (const [nodeId, transform] of batch.nodeTransforms) {
    if (!targets.nodeTransforms.has(nodeId)) {
      targets.nodeTransforms.set(nodeId, transform)
    }
  }
  for (const [groupId, position] of batch.groupMoves) {
    if (!targets.groupMoves.has(groupId)) {
      targets.groupMoves.set(groupId, position)
    }
  }
  for (const [nodeId, groupId] of batch.ungroups) {
    // 期间已有新决策（含「拖回组内 → null」）时绝不覆盖。
    if (!targets.ungroups.has(nodeId)) {
      targets.ungroups.set(nodeId, groupId)
    }
  }
}

function hasPendingTransforms(
  pendingNodeTransforms: Map<UUIDString, CanvasTransformDTO>,
  pendingGroupMoves: Map<UUIDString, { x: number; y: number }>,
  pendingUngroups: Map<UUIDString, UUIDString | null>,
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
