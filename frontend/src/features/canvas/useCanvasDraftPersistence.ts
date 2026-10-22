import { useCallback, useEffect, useState } from 'react'
import { applyDraftAcks as reduceDraftAcks, isNodeDirty, removeDraftField, type CanvasNodeDraft } from '@/features/canvas/canvas-drafts'
import {
  ackCanvasDrafts,
  deleteCanvasDraft,
  loadCanvasDrafts,
  saveCanvasDraft,
} from '@/features/canvas/canvas-draft-storage'
import type { CanvasScope } from '@/features/canvas/canvas-controller-scope'
import { getEditingSessionId } from '@/features/canvas/canvas-editing-session'
import {
  CanvasStorageUnavailableError,
  onCanvasStorageError,
} from '@/features/canvas/canvas-local-store'
import type { CanvasDraftAck } from '@/features/canvas/canvas-operation-storage'
import type { CanvasLocalState } from '@/features/canvas/types'
import type { CanvasSnapshotDTO } from '@/shared/api/generated/contracts'

const SAVE_FAILED_TOAST = '保存草稿失败，数据未保存'

type DraftPositions = Record<string, { x: number; y: number }>

interface DraftPersistenceInput {
  scope: CanvasScope
  canvasId: string | null
  view: CanvasLocalState['view']
  drafts: CanvasLocalState['drafts']
  snapshot: CanvasSnapshotDTO | undefined
}

/**
 * 画布本地草稿层：按 canvasId 从 IndexedDB 恢复草稿，按 scope/epoch 串行落盘并推进持久基线，处理 ACK 与远端删除。
 *
 * 落盘任务在序列化阶段读取最新的持久化目标，ACK 与写入共用同一条链，因此「前序写入 → ACK → 后续写入」严格有序，已确认的草稿不会被排队中的写入复活。
 */
export function useCanvasDraftPersistence({ scope, canvasId, view, drafts, snapshot }: DraftPersistenceInput) {
  const { setState, isMountedRef, canvasEpochRef, activeCanvasIdRef, draftsRef, positionsRef, targetDraftsRef, draftPersistChainRef, draftBaselineRef } = scope
  const [draftReloadToken, setDraftReloadToken] = useState(0)
  const [draftLoadEpoch, setDraftLoadEpoch] = useState(0)

  // 恢复 IndexedDB 中的持久化草稿（按 canvasId 严格隔离）
  useEffect(() => {
    if (!canvasId || view !== 'editor') {
      return
    }
    const currentEpoch = canvasEpochRef.current
    // 加载开始即撤销持久基线，加载完成前禁止删除旧草稿
    draftBaselineRef.current = { canvasId: null, drafts: {} }
    let cancelled = false
    void loadCanvasDrafts(canvasId).then((persisted) => {
      if (cancelled || canvasEpochRef.current !== currentEpoch || activeCanvasIdRef.current !== canvasId) {
        return
      }
      const mergedDrafts = { ...persisted, ...draftsRef.current }
      const mergedPositions = { ...positionsRef.current }
      for (const [id, draft] of Object.entries(mergedDrafts)) {
        if (draft.position && !mergedPositions[id]) {
          mergedPositions[id] = draft.position
        }
      }
      draftsRef.current = mergedDrafts
      positionsRef.current = mergedPositions
      targetDraftsRef.current = mergedDrafts
      draftBaselineRef.current = { canvasId, drafts: persisted }

      setState((current) => {
        if (current.canvasId !== canvasId) {
          return current
        }
        return {
          ...current,
          drafts: mergedDrafts,
          positionDrafts: mergedPositions,
        }
      })
      setDraftLoadEpoch((epoch) => epoch + 1)
    }).catch((err) => {
      if (cancelled || canvasEpochRef.current !== currentEpoch) {
        return
      }
      setState((current) => ({
        ...current,
        storageError: err instanceof Error ? err.message : String(err),
        toast: SAVE_FAILED_TOAST,
      }))
    })
    return () => {
      cancelled = true
    }
  }, [activeCanvasIdRef, canvasEpochRef, canvasId, draftBaselineRef, draftReloadToken, draftsRef, positionsRef, setState, targetDraftsRef, view])

  // 监听持久化存储异常
  useEffect(() => {
    return onCanvasStorageError((error) => {
      const toast = error instanceof CanvasStorageUnavailableError
        ? error.message
        : SAVE_FAILED_TOAST
      setState((current) => ({
        ...current,
        storageError: error.message,
        toast,
      }))
    })
  }, [setState])

  // 本地草稿持久化：按 scope/epoch 隔离，事务成功后推进 baseline
  const runDraftPersistence = useCallback(() => {
    const taskEpoch = canvasEpochRef.current
    const taskCanvasId = activeCanvasIdRef.current
    if (!taskCanvasId || view !== 'editor') {
      return
    }
    if (draftBaselineRef.current.canvasId !== taskCanvasId) {
      return
    }
    const isCurrentTask = () => (
      isMountedRef.current
      && canvasEpochRef.current === taskEpoch
      && activeCanvasIdRef.current === taskCanvasId
    )
    const canReport = () => isMountedRef.current && canvasEpochRef.current === taskEpoch
    const reportFailure = (err: unknown) => {
      const message = err instanceof Error ? err.message : String(err)
      if (canReport()) {
        setState((cur) => ({
          ...cur,
          storageError: message,
          toast: SAVE_FAILED_TOAST,
        }))
      }
    }

    draftPersistChainRef.current = draftPersistChainRef.current.then(async () => {
      if (!isCurrentTask()) {
        return
      }
      const currentBaseline = draftBaselineRef.current
      if (currentBaseline.canvasId !== taskCanvasId) {
        return
      }
      // 在序列化执行阶段动态获取最新权威草稿引用，避免在排队期间因捕获旧快照而复活已 ACK 的草稿
      const currentTarget = { ...targetDraftsRef.current }

      const toSave: Array<[string, CanvasNodeDraft]> = []
      for (const [nodeId, draft] of Object.entries(currentTarget)) {
        if (currentBaseline.drafts[nodeId] !== draft) {
          toSave.push([nodeId, draft])
        }
      }
      const toDelete = Object.keys(currentBaseline.drafts).filter((nodeId) => !(nodeId in currentTarget))

      if (toSave.length === 0 && toDelete.length === 0) {
        if (canReport()) {
          setState((cur) => cur.draftPersistPending ? { ...cur, draftPersistPending: false } : cur)
        }
        return
      }

      if (canReport()) {
        setState((cur) => cur.draftPersistPending ? cur : { ...cur, draftPersistPending: true })
      }

      let hadError = false
      for (const [nodeId, draft] of toSave) {
        if (!isCurrentTask()) {
          return
        }
        try {
          await saveCanvasDraft(taskCanvasId, nodeId, draft)
          currentBaseline.drafts[nodeId] = draft
        } catch (err) {
          hadError = true
          reportFailure(err)
          break
        }
      }

      if (!hadError) {
        for (const nodeId of toDelete) {
          if (!isCurrentTask()) {
            return
          }
          try {
            await deleteCanvasDraft(taskCanvasId, nodeId)
            delete currentBaseline.drafts[nodeId]
          } catch (err) {
            hadError = true
            reportFailure(err)
            break
          }
        }
      }

      if (canReport()) {
        setState((cur) => ({
          ...cur,
          draftPersistPending: false,
          storageError: hadError ? cur.storageError : null,
        }))
      }
    }).catch(() => {
      if (canReport()) {
        setState((cur) => ({ ...cur, draftPersistPending: false }))
      }
    })
  }, [activeCanvasIdRef, canvasEpochRef, draftBaselineRef, draftPersistChainRef, isMountedRef, setState, targetDraftsRef, view])

  useEffect(() => {
    runDraftPersistence()
  }, [canvasId, draftLoadEpoch, drafts, runDraftPersistence, view])

  // 远端删除探测：快照中不存在但本地有未提交草稿的节点，标记为 remote_deleted 保留草稿供救援
  useEffect(() => {
    if (!snapshot || !canvasId) {
      return
    }
    const remoteNodeIds = new Set(snapshot.nodes.map((node) => node.id))
    setState((current) => {
      let changed = false
      const nextDrafts = { ...current.drafts }
      for (const [id, draft] of Object.entries(current.drafts)) {
        if (!remoteNodeIds.has(id) && isNodeDirty(draft) && draft.conflict?.type !== 'remote_deleted') {
          changed = true
          nextDrafts[id] = {
            ...draft,
            conflict: {
              kind: 'TARGET_MISSING',
              type: 'remote_deleted',
              message: `节点「${draft.text?.name || '已删除节点'}」已在远端被删除，本地保留未保存草稿。`,
            },
          }
        }
      }
      if (!changed) {
        return current
      }
      draftsRef.current = nextDrafts
      targetDraftsRef.current = nextDrafts
      return {
        ...current,
        drafts: nextDrafts,
        conflictMessage: '部分节点已在远端被删除，本地保留未保存草稿。',
      }
    })
  }, [canvasId, draftsRef, setState, snapshot, targetDraftsRef])

  const ackDurableDraftsForScope = useCallback((
    targetCanvasId: string,
    targetSessionId: string,
    targetEpoch: number,
    acks: CanvasDraftAck[],
  ): Promise<void> => {
    if (acks.length === 0) {
      return Promise.resolve()
    }
    const isStillActive = () => (
      isMountedRef.current
      && canvasEpochRef.current === targetEpoch
      && activeCanvasIdRef.current === targetCanvasId
    )

    const ackTask = draftPersistChainRef.current.then(async () => {
      // 持久 ACK 针对原 identity：即使卸载或画布切换也必须 await 实际 IDB commit；写入失败则 reject，保留 operation
      await ackCanvasDrafts(targetCanvasId, acks, { editingSessionId: targetSessionId })

      // 内存 state / baseline 变更只能当 captured generation 仍 active，绝不改新 canvas
      if (!isStillActive()) {
        return
      }

      // 同一序列化边界内推进持久基线
      const baseline = draftBaselineRef.current
      if (baseline.canvasId === targetCanvasId) {
        for (const ack of acks) {
          const baseDraft = baseline.drafts[ack.nodeId]
          if (baseDraft) {
            const next = removeDraftField(baseDraft, ack.field === 'group' ? 'groupId' : ack.field, ack.generation)
            if (next) {
              baseline.drafts[ack.nodeId] = next
            } else {
              delete baseline.drafts[ack.nodeId]
            }
          }
        }
      }

      // 同步更新权威草稿、位置与持久化目标，防止后续排队的持久化任务复活已确认草稿
      const next = reduceDraftAcks(draftsRef.current, positionsRef.current, acks)
      if (next.changed) {
        draftsRef.current = next.drafts
        positionsRef.current = next.positions
        targetDraftsRef.current = next.drafts
        setState((current) => ({ ...current, drafts: next.drafts, positionDrafts: next.positions }))
      }
    })

    // 链序保证：前序 queued writes -> ACK -> 后续 writes
    draftPersistChainRef.current = ackTask.catch(() => undefined)
    return ackTask
  }, [activeCanvasIdRef, canvasEpochRef, draftBaselineRef, draftPersistChainRef, draftsRef, isMountedRef, positionsRef, setState, targetDraftsRef])

  const ackDurableDrafts = useCallback((acks: CanvasDraftAck[]): Promise<void> => {
    const activeCanvasId = activeCanvasIdRef.current
    if (!activeCanvasId) {
      return Promise.resolve()
    }
    return ackDurableDraftsForScope(activeCanvasId, getEditingSessionId(), canvasEpochRef.current, acks)
  }, [ackDurableDraftsForScope, activeCanvasIdRef, canvasEpochRef])

  /**
   * 按 operation/generation 精准清除渲染状态中的草稿：仅当草稿 generation 与冻结时一致才清除，
   * 旧 ACK 绝不清掉后来追加的本地输入。
   */
  const applyDraftAcks = useCallback((acks: CanvasDraftAck[]) => {
    if (acks.length === 0) {
      return
    }
    setState((current) => {
      const next = reduceDraftAcks(current.drafts, current.positionDrafts, acks)
      return next.changed ? { ...current, drafts: next.drafts, positionDrafts: next.positions } : current
    })
  }, [setState])

  const setPositionDrafts = useCallback((updater: (current: DraftPositions) => DraftPositions) => {
    const nextPositions = updater(positionsRef.current)
    if (nextPositions === positionsRef.current) {
      return
    }
    positionsRef.current = nextPositions
    const nextDrafts = { ...draftsRef.current }
    let changed = false
    for (const [id, pos] of Object.entries(nextPositions)) {
      const existing = nextDrafts[id]
      if (existing?.position?.x === pos.x && existing.position.y === pos.y) {
        continue
      }
      const base = existing ?? { generation: 0, updatedAt: Date.now() }
      nextDrafts[id] = {
        ...base,
        generation: (base.generation ?? 0) + 1,
        position: pos,
        updatedAt: Date.now(),
      }
      changed = true
    }
    for (const id of Object.keys(positionsRef.current)) {
      if (id in nextPositions) {
        continue
      }
      const existing = nextDrafts[id]
      if (!existing) {
        continue
      }
      const stripped = removeDraftField(existing, 'position')
      if (stripped) {
        nextDrafts[id] = stripped
      } else {
        delete nextDrafts[id]
      }
      changed = true
    }
    if (changed) {
      draftsRef.current = nextDrafts
      targetDraftsRef.current = nextDrafts
    }
    setState((current) => ({
      ...current,
      positionDrafts: nextPositions,
      drafts: draftsRef.current,
    }))
  }, [draftsRef, positionsRef, setState, targetDraftsRef])

  const dismissDraft = useCallback((nodeId: string) => {
    delete draftsRef.current[nodeId]
    delete positionsRef.current[nodeId]
    targetDraftsRef.current = { ...draftsRef.current }
    setState((current) => {
      const nextDrafts = { ...current.drafts }
      delete nextDrafts[nodeId]
      const nextPositions = { ...current.positionDrafts }
      delete nextPositions[nodeId]
      return {
        ...current,
        drafts: nextDrafts,
        positionDrafts: nextPositions,
        conflictMessage: Object.values(nextDrafts).some((d) => d.conflict) ? current.conflictMessage : null,
      }
    })
  }, [draftsRef, positionsRef, setState, targetDraftsRef])

  const reloadDrafts = useCallback(() => {
    setDraftReloadToken((token) => token + 1)
  }, [])

  return {
    reloadDrafts,
    runDraftPersistence,
    ackDurableDraftsForScope,
    ackDurableDrafts,
    applyDraftAcks,
    setPositionDrafts,
    dismissDraft,
  }
}
