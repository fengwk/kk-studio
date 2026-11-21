import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useApplicationEvents } from '@/shared/app-events'
import type { RealtimeFrameSchedulerOptions } from '@/features/thread/runtime/thread-realtime/realtime-frame-scheduler'
import {
  snapshotModelStream,
  type RealtimeModelStream,
} from '@/features/thread/runtime/thread-realtime/model-stream'
import {
  type RealtimeToolStream,
} from '@/features/thread/runtime/thread-realtime/tool-stream'
import type { ModelRecovery } from '@/features/thread/runtime/thread-realtime/model-recovery'
import {
  reconcileSnapshotStreams,
  type RealtimeStreamContext,
} from '@/features/thread/runtime/thread-realtime/realtime-streams'
import { useThreadRealtimeSubscription } from '@/features/thread/runtime/thread-realtime/useThreadRealtimeSubscription'
import { useRealtimeFrameScheduler } from '@/features/thread/runtime/thread-realtime/useRealtimeFrameScheduler'
import { request } from '@/shared/api/client'
import type {
  HarnessActiveAssistantOutputDTO,
  HarnessGenerationFailureDTO,
  ToolInvocationDTO,
} from '@/shared/api/generated/contracts'

interface HarnessThreadRealtimeState {
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
    entry.promise = request('POST /api/harness/builtin/threads/{threadId}/stream-catchup', {
      path: { threadId: catchupThreadId },
      body: { invocationId, attempt },
    })
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

  // 清空 overlays：Thread 消失或订阅被禁用时把 refs 与 state 一起归零。
  const clearOverlays = useCallback(() => {
    modelStreamRef.current = null
    setModelStream(null)
    toolStreamsRef.current = new Map()
    setToolStreams(new Map())
    toolPartialSeenEventIdsRef.current = new Map()
  }, [])

  useThreadRealtimeSubscription({
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
  })

  const visibleModelStream = useMemo(() => {
    if (activeAssistantOutput?.status === 'SUCCEEDED') {
      // Use the successful snapshot on the same render as the Entries, before reconciliation.
      return snapshotModelStream(threadId, activeAssistantOutput, modelStream)
    }
    return modelStream?.threadId === threadId && modelStream.invocationId === activeAssistantOutput?.generationId
      ? modelStream : null
  }, [activeAssistantOutput, modelStream, threadId])
  // toolStreams 可能仍持有上一个 Thread 的条目；过滤结果按输入身份缓存，使
  // 未变化时下游 timeline/events 的 useMemo 与 effect 保持稳定引用。
  const visibleToolStreams = useMemo(
    () => (toolStreams.size > 0
      ? new Map(
          [...toolStreams.entries()].filter(
            ([, stream]) => stream.threadId === threadId,
          ),
        )
      : toolStreams),
    [threadId, toolStreams],
  )
  return {
    modelStream: visibleModelStream,
    toolStreams: visibleToolStreams,
  }
}
