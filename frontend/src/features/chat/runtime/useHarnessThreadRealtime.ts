import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useApplicationEvents } from '@/shared/app-events'
import type { RealtimeFrameSchedulerOptions } from '@/features/chat/runtime/realtime-frame-scheduler'
import {
  type RealtimeModelStream,
} from '@/features/chat/runtime/thread-realtime/model-stream'
import {
  type RealtimeToolStream,
} from '@/features/chat/runtime/thread-realtime/tool-stream'
import {
  isActiveModelGeneration,
  type ModelRecovery,
} from '@/features/chat/runtime/thread-realtime/model-recovery'
import {
  createRealtimeEventHandler,
  reconcileSnapshotStreams,
  type RealtimeStreamContext,
} from '@/features/chat/runtime/thread-realtime/realtime-streams'
import { useRealtimeFrameScheduler } from '@/features/chat/runtime/thread-realtime/useRealtimeFrameScheduler'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import type {
  HarnessActiveAssistantOutputDTO,
  HarnessGenerationFailureDTO,
  ToolInvocationDTO,
} from '@/shared/api/generated/contracts'

export interface HarnessThreadRealtimeState {
  /** 当前 generation 的瞬态 model overlay（无则 null）。 */
  modelStream: RealtimeModelStream | null
  /** 按 ToolInvocation id 索引的瞬态 tool 结果 overlay。 */
  toolStreams: ReadonlyMap<string, RealtimeToolStream>
}

/**
 * Snapshot 优先的 realtime 订阅。
 *
 * 1) 先加载权威的 PostgreSQL snapshot。
 * 2) 在其持久化 version 之后监听。lossy {@code realtime} MODEL_DELTA / TOOL_PARTIAL
 *    通知渲染为瞬态 overlay，直到持久化 Entries 到达；重连与通知丢失后 PostgreSQL 仍是
 *    权威来源。realtime notification 永远不是持久化事实。
 */
export function useHarnessThreadRealtime(
  threadId: string,
  enabled: boolean,
  version: string | undefined,
  activeAssistantOutput: HarnessActiveAssistantOutputDTO | null,
  toolInvocations: ToolInvocationDTO[],
  generationFailures: readonly HarnessGenerationFailureDTO[] = [],
  schedulerOptions?: Partial<RealtimeFrameSchedulerOptions>,
): HarnessThreadRealtimeState {
  const queryClient = useQueryClient()
  const applicationEvents = useApplicationEvents()
  const [modelStream, setModelStream] = useState<RealtimeModelStream | null>(null)
  const [toolStreams, setToolStreams] = useState<ReadonlyMap<string, RealtimeToolStream>>(
    () => new Map(),
  )
  const [subscription, setSubscription] = useState<{
    threadId: string
    version: string
  } | null>(null)
  const modelStreamRef = useRef<RealtimeModelStream | null>(null)
  const toolStreamsRef = useRef<Map<string, RealtimeToolStream>>(new Map())
  const snapshotModelStreamRef = useRef<RealtimeModelStream | null>(null)
  const activeOutputRef = useRef<HarnessActiveAssistantOutputDTO | null>(activeAssistantOutput)
  const toolInvocationsRef = useRef<ToolInvocationDTO[]>(toolInvocations)
  const generationFailuresRef = useRef<readonly HarnessGenerationFailureDTO[]>(generationFailures)
  const modelRecoveryRef = useRef<ModelRecovery | null>(null)
  const catchupInFlightRef = useRef<{
    key: string
    promise: Promise<void>
  } | null>(null)
  // TOOL_PARTIAL 的有界精确去重（realtime notification 可能重投递）：按
  // thread:invocation:attempt 分组、以生产端 eventId 为唯一键，因此 attempt 变化/终态
  // snapshot 会自然淘汰它们；256 上限只是 best-effort 窗口，绝不吞掉新的合法 eventId。
  const toolPartialSeenEventIdsRef = useRef<Map<string, Set<string>>>(new Map())
  const subscriptionReady = enabled && version != null

  const scheduler = useRealtimeFrameScheduler(
    modelStreamRef,
    activeOutputRef,
    generationFailuresRef,
    toolStreamsRef,
    setModelStream,
    setToolStreams,
    schedulerOptions,
  )

  const clearModelRecovery = useCallback(() => {
    modelRecoveryRef.current = null
  }, [])

  /**
   * 每个 thread:invocation:attempt 只保留一次在途 catchup；响应只是 202
   * best-effort ack，不据此推断 owner 在线或推进 checkpoint，也不自动重试。
   */
  const requestCatchup = useCallback((
    catchupThreadId: string,
    invocationId: string,
    attempt: number,
    force = false,
  ) => {
    const key = `${catchupThreadId}:${invocationId}:${attempt}`
    if (!force && catchupInFlightRef.current?.key === key) {
      return
    }
    const entry: { key: string; promise: Promise<void> } = {
      key,
      promise: Promise.resolve(),
    }
    entry.promise = harnessService
      .requestStreamCatchup(catchupThreadId, { invocationId, attempt })
      .catch(() => undefined)
      .finally(() => {
        if (catchupInFlightRef.current === entry) {
          catchupInFlightRef.current = null
        }
      })
    catchupInFlightRef.current = entry
  }, [])

  useEffect(() => {
    return () => {
      scheduler.cancel()
    }
  }, [scheduler])

  useEffect(() => {
    setSubscription((current) => {
      if (!subscriptionReady || version == null) {
        return null
      }
      return current?.threadId === threadId ? current : { threadId, version }
    })
  }, [version, subscriptionReady, threadId])


  // 只携带稳定 ref 与回调：随 Thread / scheduler 变化重建即可。
  const streamContext = useMemo<RealtimeStreamContext>(
    () => ({
      threadId,
      scheduler,
      modelStreamRef,
      toolStreamsRef,
      snapshotModelStreamRef,
      activeOutputRef,
      toolInvocationsRef,
      generationFailuresRef,
      modelRecoveryRef,
      toolPartialSeenEventIdsRef,
      clearModelRecovery,
    }),
    [threadId, scheduler, clearModelRecovery],
  )

  useEffect(() => {
    activeOutputRef.current = activeAssistantOutput
    toolInvocationsRef.current = toolInvocations
    generationFailuresRef.current = generationFailures
    reconcileSnapshotStreams(streamContext, {
      activeAssistantOutput,
      toolInvocations,
      generationFailures,
      modelStream,
      toolStreams,
      setModelStream,
      setToolStreams,
    })
  }, [generationFailures, activeAssistantOutput, modelStream, streamContext, toolInvocations, toolStreams])

  useEffect(() => {
    // 只有 Thread 消失或订阅真正被禁用才清空 overlays；`subscription == null` 或
    // threadId 暂时不匹配只是订阅状态过渡（同一提交中 seed effect 刚播种、
    // subscription effect 即将落定）：保留 snapshot-seeded overlay，等下一 render
    // 的 subscription 就绪后通过应用级 Manager 建立订阅。
    if (!threadId || !subscriptionReady) {
      clearModelRecovery()
      scheduler.cancel()
      modelStreamRef.current = null
      setModelStream(null)
      toolStreamsRef.current = new Map()
      setToolStreams(new Map())
      toolPartialSeenEventIdsRef.current = new Map()
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

  const visibleModelStream = modelStream?.threadId === threadId ? modelStream : null
  const visibleToolStreams =
    toolStreams.size > 0
      ? new Map(
          [...toolStreams.entries()].filter(
            ([, stream]) => stream.threadId === threadId,
          ),
        )
      : toolStreams
  return {
    modelStream: visibleModelStream,
    toolStreams: visibleToolStreams,
  }
}
