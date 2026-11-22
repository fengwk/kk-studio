import { useCallback, useEffect, useRef } from 'react'
import type { QueryClient } from '@tanstack/react-query'
import type { CanvasNodeDraft } from '@/features/canvas/canvas-drafts'
import type { CanvasScope } from '@/features/canvas/canvas-controller-scope'
import { getEditingSessionId } from '@/features/canvas/canvas-editing-session'
import type { CanvasDraftAck } from '@/features/canvas/canvas-operation-storage'
import { useCanvasVersionEvents } from '@/features/canvas/canvas-version-events'
import {
  CanvasCommandConflictError,
  CanvasCommandQueue,
  CanvasQueueBlockedError,
} from '@/features/canvas/command-queue'
import type { CanvasLocalState } from '@/features/canvas/types'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import type {
  CanvasCommandDTO,
  CanvasConflictDTO,
  CanvasSnapshotDTO,
} from '@/shared/api/generated/contracts'

interface CommandQueueInput {
  scope: CanvasScope
  queryClient: QueryClient
  canvasId: string | null
  view: CanvasLocalState['view']
  snapshot: CanvasSnapshotDTO | undefined
  ackDurableDraftsForScope: (
    canvasId: string,
    editingSessionId: string,
    epoch: number,
    acks: CanvasDraftAck[],
  ) => Promise<void>
  applyDraftAcks: (acks: CanvasDraftAck[]) => void
}

/**
 * 画布命令队列的生命周期：快照就绪后为当前画布建立队列并精确重放刷新前未确认的操作，之后以权威快照替换队列基线，更高 revision 的应用事件触发重新拉取。
 *
 * 命令冲突把远端事实标注在对应节点的草稿上，等待用户对比后决定，绝不自动重试旧内容。
 */
export function useCanvasCommandQueue({
  scope,
  queryClient,
  canvasId,
  view,
  snapshot,
  ackDurableDraftsForScope,
  applyDraftAcks,
}: CommandQueueInput) {
  const { setState, canvasEpochRef, queueRef, queueCanvasIdRef, draftsRef, targetDraftsRef } = scope
  const { t } = useI18n()
  const pendingCommandCountRef = useRef(0)

  useEffect(() => {
    if (!snapshot || !canvasId) {
      return
    }
    if (queueRef.current && queueCanvasIdRef.current !== canvasId) {
      queueRef.current = null
      queueCanvasIdRef.current = null
    }
    if (queueRef.current) {
      const authoritative = queueRef.current.replaceSnapshot(snapshot)
      if (authoritative !== snapshot) {
        queryClient.setQueryData(queryKeys.studio.canvas(canvasId), authoritative)
      }
      return
    }
    const capturedSessionId = getEditingSessionId()
    const capturedEpoch = canvasEpochRef.current
    const queue = new CanvasCommandQueue(canvasId, {
      initialSnapshot: snapshot,
      editingSessionId: capturedSessionId,
      onSnapshot: (next) => {
        queryClient.setQueryData(queryKeys.studio.canvas(canvasId as string), next)
      },
      // 该 queue 专属的 ACK：严格捕获原 canvasId、editingSessionId 与 epoch
      onDraftAcks: (acks) => ackDurableDraftsForScope(canvasId, capturedSessionId, capturedEpoch, acks),
    })
    queueRef.current = queue
    queueCanvasIdRef.current = canvasId
    // 刷新 / 路由切换后，按冻结顺序精确重放原 idempotencyKey 与原始命令体。
    void queue.recover()
      .then((result) => {
        if (queueRef.current !== queue) {
          return
        }
        if (result.conflicted > 0) {
          setState((current) => ({
            ...current,
            conflictKind: 'recoverable',
            conflictMessage: t('canvas.conflict.replayConflict'),
          }))
        } else if (result.failed > 0) {
          setState((current) => ({
            ...current,
            conflictKind: 'recoverable',
            conflictMessage: t('canvas.conflict.replayFailed'),
          }))
        }
      })
      .catch(() => {
        if (queueRef.current !== queue) {
          return
        }
        setState((current) => ({
          ...current,
          conflictKind: 'recoverable',
          conflictMessage: t('canvas.conflict.recoveryFailed'),
        }))
      })
  }, [ackDurableDraftsForScope, canvasEpochRef, canvasId, queryClient, queueCanvasIdRef, queueRef, setState, snapshot, t])

  // 应用事件 WebSocket 订阅：更高 revision 直接刷新权威 Snapshot
  const refreshCanvasSnapshot = useCallback(() => {
    if (!canvasId) {
      return
    }
    void queryClient.invalidateQueries({ queryKey: queryKeys.studio.canvas(canvasId) })
  }, [canvasId, queryClient])

  useCanvasVersionEvents({
    canvasId,
    enabled: view === 'editor' && Boolean(snapshot),
    revision: snapshot?.document.revision ?? '0',
    onSnapshot: refreshCanvasSnapshot,
  })

  const executeCommands = useCallback(async (commands: CanvasCommandDTO[], ack?: CanvasDraftAck[]) => {
    const queue = queueRef.current
    if (!queue) {
      throw new Error('Canvas snapshot is not ready')
    }
    pendingCommandCountRef.current += 1
    setState((current) => ({ ...current, commandPending: true, conflictKind: null, conflictMessage: null }))
    try {
      return await queue.enqueue(commands, ack && ack.length > 0 ? { ack } : undefined)
    } catch (error) {
      if (queueRef.current !== queue) {
        throw error
      }
      if (error instanceof CanvasCommandConflictError) {
        setState((current) => {
          const nextDrafts = markConflicts(current.drafts, error.conflicts)
          draftsRef.current = nextDrafts
          targetDraftsRef.current = nextDrafts
          return {
            ...current,
            drafts: nextDrafts,
            conflictKind: 'terminal',
            conflictMessage: t('canvas.conflict.serverUpdated'),
            toast: t('canvas.toast.serverUpdated'),
          }
        })
      } else if (error instanceof CanvasQueueBlockedError) {
        setState((current) => ({
          ...current,
          conflictKind: 'recoverable',
          conflictMessage: t('canvas.conflict.queueBlocked'),
          toast: t('canvas.toast.queueBlocked'),
        }))
      } else {
        console.error('[canvas] Command batch failed:', error)
        setState((current) => ({
          ...current,
          toast: t('canvas.toast.operationFailed'),
        }))
      }
      throw error
    } finally {
      pendingCommandCountRef.current -= 1
      if (pendingCommandCountRef.current === 0) {
        setState((current) => ({ ...current, commandPending: false }))
      }
    }
  }, [draftsRef, queueRef, setState, t, targetDraftsRef])

  const retryRecovery = useCallback(async () => {
    const queue = queueRef.current
    if (!queue) {
      return undefined
    }
    try {
      const result = await queue.recover()
      if (queueRef.current !== queue) {
        return result
      }
      if (result.ackedDrafts.length > 0) {
        applyDraftAcks(result.ackedDrafts)
      }
      const conflictMessage = result.conflicted > 0
        ? t('canvas.conflict.replayConflict')
        : result.failed > 0 ? t('canvas.conflict.replayFailed') : null
      setState((current) => ({
        ...current,
        conflictKind: conflictMessage ? 'recoverable' : null,
        conflictMessage,
      }))
      return result
    } catch (error) {
      if (queueRef.current !== queue) {
        throw error
      }
      console.error('[canvas] Queue recovery failed:', error)
      setState((current) => ({
        ...current,
        conflictKind: 'recoverable',
        conflictMessage: t('canvas.conflict.recoveryFailed'),
        toast: t('canvas.toast.recoveryFailed'),
      }))
      throw error
    }
  }, [applyDraftAcks, queueRef, setState, t])

  return { executeCommands, retryRecovery }
}

/** 把服务端冲突标注到对应节点的草稿上，保留本地内容与远端事实供用户对比。 */
function markConflicts(
  drafts: Record<string, CanvasNodeDraft>,
  conflicts: CanvasConflictDTO[],
): Record<string, CanvasNodeDraft> {
  const nextDrafts = { ...drafts }
  const withConflict = (nodeId: string, conflict: NonNullable<CanvasNodeDraft['conflict']>) => {
    const existing = nextDrafts[nodeId] ?? { generation: 0, updatedAt: Date.now() }
    nextDrafts[nodeId] = { ...existing, conflict }
  }
  for (const conf of conflicts) {
    if (conf.type === 'STALE_NODE') {
      withConflict(conf.nodeId, {
        kind: 'STALE_NODE',
        remoteValue: conf.current,
        conflicts,
      })
    } else if (conf.type === 'TARGET_MISSING') {
      withConflict(conf.targetId, {
        kind: 'TARGET_MISSING',
        type: 'remote_deleted',
        conflicts,
      })
    } else if (conf.type === 'NODE_REFERENCED') {
      withConflict(conf.nodeId, {
        kind: 'NODE_REFERENCED',
        conflicts,
      })
    }
  }
  return nextDrafts
}
