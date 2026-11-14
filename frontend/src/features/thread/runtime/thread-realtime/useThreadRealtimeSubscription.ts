import { useEffect } from 'react'
import type { QueryClient } from '@tanstack/react-query'
import type { useApplicationEvents } from '@/shared/app-events'
import type { RealtimeFrameScheduler } from '@/features/thread/runtime/realtime-frame-scheduler'
import { queryKeys } from '@/shared/lib/query-keys'
import { isActiveModelGeneration } from '@/features/thread/runtime/thread-realtime/model-recovery'
import {
  createRealtimeEventHandler,
  type RealtimeStreamContext,
} from '@/features/thread/runtime/thread-realtime/realtime-streams'
import type {
  HarnessActiveAssistantOutputDTO,
  HarnessGenerationFailureDTO,
} from '@/shared/api/generated/contracts'

type Ref<T> = { current: T }

interface ThreadRealtimeSubscriptionInput {
  threadId: string
  subscription: { threadId: string; version: string } | null
  subscriptionReady: boolean
  applicationEvents: ReturnType<typeof useApplicationEvents>
  queryClient: QueryClient
  requestCatchup: (
    threadId: string,
    invocationId: string,
    attempt: number,
    force?: boolean,
  ) => void
  scheduler: RealtimeFrameScheduler
  streamContext: RealtimeStreamContext
  clearModelRecovery: () => void
  activeOutputRef: Ref<HarnessActiveAssistantOutputDTO | null>
  generationFailuresRef: Ref<readonly HarnessGenerationFailureDTO[]>
  toolPartialSeenEventIdsRef: Ref<Map<string, Set<string>>>
  /** Thread 消失或订阅被禁用时清空 overlays 的 refs 与 state。 */
  clearOverlays: () => void
}

/**
 * 应用级 realtime 订阅的生命周期。
 *
 * version/resync/subscribed/error 都触发 snapshot 对账（subscribed 在首次订阅与每次
 * 重连重订阅后到达，关闭断线窗口）；realtime 事件走 overlay reducer。连接与重连由单例
 * Connection 负责。过渡 render（订阅尚未就绪）保留 snapshot-seeded overlay。
 */
export function useThreadRealtimeSubscription({
  threadId,
  subscription,
  subscriptionReady,
  applicationEvents,
  queryClient,
  requestCatchup,
  scheduler,
  streamContext,
  clearModelRecovery,
  activeOutputRef,
  generationFailuresRef,
  toolPartialSeenEventIdsRef,
  clearOverlays,
}: ThreadRealtimeSubscriptionInput): void {
  useEffect(() => {
    // 只有 Thread 消失或订阅真正被禁用才清空 overlays；`subscription == null` 或
    // threadId 暂时不匹配只是订阅状态过渡（同一提交中 seed effect 刚播种、
    // subscription effect 即将落定）：保留 snapshot-seeded overlay，等下一 render
    // 的 subscription 就绪后通过应用级 Manager 建立订阅。
    if (!threadId || !subscriptionReady) {
      clearModelRecovery()
      scheduler.cancel()
      clearOverlays()
      return undefined
    }
    if (subscription == null || subscription.threadId !== threadId) {
      // 过渡 render：订阅状态尚未就绪（或 threadId 暂不匹配）。保留
      // snapshot-seeded overlay，等待下一 render 建立订阅后继续消费。
      return undefined
    }

    const refetchSnapshot = (): Promise<void> =>
      queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) })
    // 订阅/version/resync/error 的对账是 best-effort；失败只由下一次 durable
    // 信号重试，绝不能变成 unhandled rejection。
    const invalidateSnapshot = (): Promise<void> =>
      refetchSnapshot().catch(() => undefined)
    const handleRealtime = createRealtimeEventHandler(streamContext, {
      refetchSnapshot,
      requestCatchup,
    })
    // 应用级 manager 订阅：version/resync/subscribed/error 都触发 snapshot
    // 对账（subscribed 在首次订阅与每次重连重订阅后到达，关闭断线窗口）；
    // realtime 事件走 overlay reducer。连接与重连由单例 Connection 负责。
    const unsubscribe = applicationEvents.subscribe(
      { kind: 'thread', id: subscription.threadId },
      {
        onSubscribed: () => {
          invalidateSnapshot()
          // 新订阅（含每次重连）若仍有 RUNNING model，只请求一次 best-effort
          // 追帧；202 不表示 owner 在线，后续完全由 checkpoint/终态驱动。
          const durable = activeOutputRef.current
          if (
            durable != null
            && durable.status === 'RUNNING'
            && isActiveModelGeneration(
              durable,
              durable.generationId,
              durable.attempt,
              generationFailuresRef.current,
            )
          ) {
            requestCatchup(subscription.threadId, durable.generationId, durable.attempt, true)
          }
        },
        onEvent: (name, data) => {
          if (name === 'version') {
            invalidateSnapshot()
          } else if (name === 'realtime') {
            handleRealtime(data)
          }
        },
        onResync: invalidateSnapshot,
        onError: invalidateSnapshot,
      },
    )
    return () => {
      unsubscribe()
      clearModelRecovery()
      scheduler.cancel()
      toolPartialSeenEventIdsRef.current = new Map()
    }
    // 其余依赖都是本 Thread 生命周期内稳定的 ref 与 useCallback（与拆分前的 hook 相同）。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    applicationEvents,
    clearModelRecovery,
    queryClient,
    requestCatchup,
    scheduler,
    streamContext,
    subscription,
    subscriptionReady,
    threadId,
  ])
}
