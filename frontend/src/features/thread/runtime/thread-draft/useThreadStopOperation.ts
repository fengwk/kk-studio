import { useEffect, useRef, useState, type MutableRefObject } from 'react'
import { useApplicationEvents } from '@/shared/app-events'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { isConflictError } from '@/shared/api/client'
import { queryKeys } from '@/shared/lib/query-keys'
import { requestThreadSnapshotRefresh } from '@/features/thread/runtime/state/thread-snapshot-cache'
import type { TranslationKey } from '@/shared/i18n'
import {
  clearPendingStop,
  loadPendingStop,
  storePendingStop,
  type PendingStopOperation,
} from '@/features/thread/runtime/state/pending-stop-sidecar'
import { createStopRequestId } from '@/features/thread/command-batch-plan'
import type { HarnessStoppedThreadReceiptDTO } from '@/shared/api/generated/contracts'

/**
 * 由 stopThread（在每次复用前同步执行）与被动清理 effect 共享的 basis 栅栏：
 * 当且仅当权威 snapshot 证明 pending 操作的 basis 已变化（head 或 version 已
 * 前进 => 旧 Turn 已结束或 Thread 已前进）时，该操作会被失效。stopThread
 * 内部的同步失效可以关闭 snapshot 已前进但 effect 尚未 flush 的窗口——
 * 在新 Turn 上的下一次 Stop 必须派生全新 id，绝不能把旧 id 复用给更新后的 version。
 */
export function retireStaleStopPending(
  pending: PendingStopOperation | null,
  thread: { headEntryId: string; version: string } | null,
): PendingStopOperation | null {
  if (pending == null || thread == null) {
    return pending
  }
  if (
    thread.headEntryId !== pending.requestHeadEntryId
    || thread.version !== pending.basisVersion
  ) {
    return null
  }
  return pending
}

interface ThreadStopOperationInput {
  threadId: string
  thread: { headEntryId: string; version: string } | null
  bindingEpochRef: MutableRefObject<number>
  isStillBound: (originThreadId: string, originEpoch: number) => boolean
  setActionError: (message: string | null) => void
  setConflict: () => void
  requireBoundThread: (actionKey: TranslationKey) => boolean
  reportMutationError: (
    error: unknown,
    fallbackKey: TranslationKey,
    targetThreadId?: string,
  ) => void
  applyStopReceipts: (
    receipts: readonly HarnessStoppedThreadReceiptDTO[],
    boundThreadId: string,
    epoch: number,
  ) => Promise<boolean>
  /** Stop 已被接受：丢弃该 Thread 的未决消息（由调用方保证只在绑定未变时调用）。 */
  discardPendingMessage: () => void
}

/**
 * Thread 的 Stop 操作：按 Thread 持久化的精确 identity、含混重试与权威快照 basis 栅栏。
 *
 * <p>stopRequestId 一旦铸造就与原始 expectedVersion 绑定：网络结果未知时保留原操作逐字节
 * 重试，绝不用更新后的 snapshot 重新推导；snapshot 证明 basis 已移动（head 或 version
 * 前进）即退役该操作，使新 Turn 上的 Stop 使用全新 id。
 */
export function useThreadStopOperation({
  threadId,
  thread,
  bindingEpochRef,
  isStillBound,
  setActionError,
  setConflict,
  requireBoundThread,
  reportMutationError,
  applyStopReceipts,
  discardPendingMessage,
}: ThreadStopOperationInput) {
  const queryClient = useQueryClient()
  const applicationEvents = useApplicationEvents()
  const pendingStopRef = useRef<PendingStopOperation | null>(null)
  // 当含混的 Stop 操作尚待重试或失效时为 true：切换 Thread 时面板
  // 绝不能悄悄丢弃这次精确重试。
  const [stopReplayPending, setStopReplayPending] = useState(false)

  // 重新绑定时只加载当前 Thread 的精确 identity。
  useEffect(() => {
    const restoredStop = loadPendingStop(threadId)
    pendingStopRef.current = restoredStop
    setStopReplayPending(restoredStop != null)
  }, [threadId])

  const stopMutation = useMutation({
    mutationFn: ({
      operationThreadId,
      body,
    }: {
      operationThreadId: string
      body: { stopRequestId: string; expectedVersion: string }
    }) => harnessService.stopThread(operationThreadId, body),
  })

  function stopThread(): Promise<void> {
    if (!thread || !requireBoundThread('ai.runtime.action.stopFailed')) {
      return Promise.resolve()
    }
    setActionError(null)
    setConflict()
    const operationThreadId = threadId
    const operationEpoch = bindingEpochRef.current
    const isCurrentBound = () => isStillBound(operationThreadId, operationEpoch)
    // 同步 basis 栅栏：绝不复用 basis 已不再匹配「当前」渲染 snapshot 的待决操作
    //（head/version 已移动 => 旧 Turn 已结束或 Thread 已前进）。在这里——而不仅仅在
    // 被动清理 effect 中——退役，可以关闭「snapshot 已前进但 effect 尚未 flush」时
    // 调用 Stop 的窗口。
    const pending = retireStaleStopPending(pendingStopRef.current, thread)
    if (pending !== pendingStopRef.current) {
      pendingStopRef.current = pending
      setStopReplayPending(false)
      clearPendingStop(threadId)
    }
    // 含混重试：复用「精确」的先前操作（相同的 stopRequestId + 原始
    // expectedVersion + basis）。全新的 Stop 会针对当前 snapshot 铸造新操作；
    // 原始 expectedVersion 绝不会从更新的 snapshot 重新推导。
    const operation = pending ?? {
      stopRequestId: createStopRequestId(),
      expectedVersion: thread.version,
      requestHeadEntryId: thread.headEntryId,
      basisVersion: thread.version,
    }
    if (pending == null) {
      pendingStopRef.current = operation
      setStopReplayPending(true)
      storePendingStop(operationThreadId, operation)
    }
    return stopMutation
      .mutateAsync({
        operationThreadId,
        body: {
          stopRequestId: operation.stopRequestId,
          expectedVersion: operation.expectedVersion,
        },
      })
      .then(async (result) => {
        if (!isCurrentBound()) {
          // 导航门控正常情况下不会发生；若宿主强制重绑，保留旧 Thread sidecar，
          // 让用户返回后用同一 id replay，并从快照回执恢复草稿。
          await Promise.all([
            requestThreadSnapshotRefresh(queryClient, applicationEvents, operationThreadId),
            queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
          ])
          return
        }
        // Stop 成功（或服务器重放了完全相同的先前 Stop）：完整受影响集合的持久回执
        // 按 (threadId, stopRequestId) 原子幂等合并；子 Thread 草稿只写它自己的记录。
        const receipts = result.stoppedThreads ?? []
        // Stop 已成功：即便回执合并失败也只报「草稿未恢复」，绝不把它当成 Stop 失败。
        await applyStopReceipts(receipts, operationThreadId, operationEpoch)
        // await 期间宿主可能已强制重绑：A 的 late completion 只能清理 A 自己的 sidecar，
        // 绝不改动当前绑定的待决标记（那可能已经是 B 刚发起、结果未知的 Stop）。
        clearPendingStop(operationThreadId)
        if (isCurrentBound()) {
          pendingStopRef.current = null
          setStopReplayPending(false)
          discardPendingMessage()
        }
        await Promise.all([
          requestThreadSnapshotRefresh(queryClient, applicationEvents, operationThreadId),
          queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
        ])
      })
      .catch((error: unknown) => {
        const stillBound = isCurrentBound()
        if (isConflictError(error)) {
          // 已知 409：操作未被接受（version 过期 / 未静默 / id 被复用）。
          // 清除它；下一次 stop 会针对刷新的 snapshot 铸造新操作。
          clearPendingStop(operationThreadId)
          if (stillBound) {
            pendingStopRef.current = null
            setStopReplayPending(false)
          }
        }
        // 网络/不确定的失败会保留精确操作，用于逐字节重试。
        if (stillBound) {
          reportMutationError(error, 'ai.runtime.action.stopFailed', operationThreadId)
        }
      })
  }

  // 权威 snapshot 的 basis 协调：当存在含混的 Stop 操作时，一旦 snapshot 证明其 basis
  // 已变化（head 或 version 已移动 => 旧 Turn 已结束或 Thread 已前进），就退役该操作，
  // 使在新 Turn 上发起的 Stop 始终使用全新 id。该 effect 只在确有操作待决时起作用——
  // hook 初始挂载从不清理任何东西。stopThread 本身在每次复用前同步执行同一栅栏。
  useEffect(() => {
    const pending = retireStaleStopPending(pendingStopRef.current, thread ?? null)
    if (pending !== pendingStopRef.current) {
      pendingStopRef.current = pending
      setStopReplayPending(false)
      clearPendingStop(threadId)
    }
    // thread 每次 refetch 都是新的 snapshot 对象；只有其身份字段参与栅栏判定。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [thread?.headEntryId, thread?.version, threadId])

  return {
    stopThread,
    stopPending: stopMutation.isPending,
    stopReplayPending,
  }
}
