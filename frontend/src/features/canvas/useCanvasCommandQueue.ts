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
import type {
  CanvasCommandDTO,
  CanvasConflictDTO,
  CanvasSnapshotDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'
import { queryKeys } from '@/shared/lib/query-keys'

const REPLAY_CONFLICT_MESSAGE = '部分未确认操作因服务端内容已变化而未能自动重放，请检查最新内容。'
const REPLAY_FAILED_MESSAGE = '部分未确认操作未能自动重放，请检查网络后重试。'
const RECOVERY_FAILED_MESSAGE = '待确认操作恢复失败，请检查网络后重试。'

interface CommandQueueInput {
  scope: CanvasScope
  queryClient: QueryClient
  canvasId: UUIDString | null
  view: CanvasLocalState['view']
  snapshot: CanvasSnapshotDTO | undefined
  ackDurableDraftsForScope: (
    canvasId: UUIDString,
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
          setState((current) => ({ ...current, conflictMessage: REPLAY_CONFLICT_MESSAGE }))
        } else if (result.failed > 0) {
          setState((current) => ({ ...current, conflictMessage: REPLAY_FAILED_MESSAGE }))
        }
      })
      .catch(() => {
        if (queueRef.current !== queue) {
          return
        }
        setState((current) => ({ ...current, conflictMessage: RECOVERY_FAILED_MESSAGE }))
      })
  }, [ackDurableDraftsForScope, canvasEpochRef, canvasId, queryClient, queueCanvasIdRef, queueRef, setState, snapshot])

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
    setState((current) => ({ ...current, commandPending: true, conflictMessage: null }))
    try {
      return await queue.enqueue(commands, ack && ack.length > 0 ? { ack } : undefined)
    } catch (error) {
      if (error instanceof CanvasCommandConflictError) {
        setState((current) => {
          const nextDrafts = markConflicts(current.drafts, error.conflicts)
          draftsRef.current = nextDrafts
          targetDraftsRef.current = nextDrafts
          return {
            ...current,
            drafts: nextDrafts,
            conflictMessage: error.message,
            toast: '画布已在其他位置更新，请检查最新内容后重试。',
          }
        })
      } else if (error instanceof CanvasQueueBlockedError) {
        setState((current) => ({
          ...current,
          conflictMessage: '存在未确认操作阻塞队列，请检查网络后点击重试。',
          toast: '队列受阻：存在未完成同步的修改，请重试。',
        }))
      } else {
        setState((current) => ({
          ...current,
          toast: error instanceof Error ? error.message : '画布操作失败',
        }))
      }
      throw error
    } finally {
      pendingCommandCountRef.current -= 1
      if (pendingCommandCountRef.current === 0) {
        setState((current) => ({ ...current, commandPending: false }))
      }
    }
  }, [draftsRef, queueRef, setState, targetDraftsRef])

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
        ? REPLAY_CONFLICT_MESSAGE
        : result.failed > 0 ? REPLAY_FAILED_MESSAGE : null
      setState((current) => ({ ...current, conflictMessage }))
      return result
    } catch (error) {
      if (queueRef.current !== queue) {
        throw error
      }
      setState((current) => ({
        ...current,
        conflictMessage: RECOVERY_FAILED_MESSAGE,
        toast: error instanceof Error ? error.message : '恢复失败',
      }))
      throw error
    }
  }, [applyDraftAcks, queueRef, setState])

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
    if (conf.kind === 'STALE_NODE') {
      withConflict(conf.nodeId, {
        kind: 'STALE_NODE',
        message: `节点在远端已被修改（${conf.group}），请对比后决定。`,
        remoteValue: conf.current,
        conflicts,
      })
    } else if (conf.kind === 'TARGET_MISSING') {
      withConflict(conf.targetId, {
        kind: 'TARGET_MISSING',
        type: 'remote_deleted',
        message: '目标在远端已被删除',
        conflicts,
      })
    } else if (conf.kind === 'NODE_REFERENCED') {
      withConflict(conf.nodeId, {
        kind: 'NODE_REFERENCED',
        message: `节点被其他节点引用 (${conf.referencingNodeIds.join(', ')})，请先解除引用`,
        conflicts,
      })
    }
  }
  return nextDrafts
}
