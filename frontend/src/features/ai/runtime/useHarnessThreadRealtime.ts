import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useApplicationEvents } from '@/shared/app-events'
import {
  createRealtimeFrameScheduler,
  type RealtimeFrameScheduler,
  type RealtimeFrameSchedulerOptions,
} from '@/features/ai/runtime/realtime-frame-scheduler'
import {
  isFailedAttempt,
  isProcessOutputPartial,
  isRealtimeModelDeltaGap,
  isRealtimeToolStreamActive,
  mergeRealtimeModelStreamAfterCheckpoint,
  parseRealtimeModelCheckpoint,
  parseRealtimeModelDelta,
  parseRealtimeToolPartial,
  reduceRealtimeModelStream,
  reduceRealtimeToolStream,
  snapshotModelStream,
  snapshotToolStream,
  type RealtimeModelDelta,
  type RealtimeModelStream,
  type RealtimeToolStream,
} from '@/features/ai/runtime/thread-realtime-state'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import type {
  ModelAttemptFailureDTO,
  ModelInvocationDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'

export interface HarnessThreadRealtimeState {
  /** 当前 ModelInvocation attempt 的瞬态 model overlay（无则 null）。 */
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
  modelInvocation: ModelInvocationDTO | null,
  toolInvocations: ToolInvocationDTO[],
  modelAttemptFailures: readonly ModelAttemptFailureDTO[] = [],
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
  const invocationRef = useRef<ModelInvocationDTO | null>(modelInvocation)
  const toolInvocationsRef = useRef<ToolInvocationDTO[]>(toolInvocations)
  const modelAttemptFailuresRef = useRef<readonly ModelAttemptFailureDTO[]>(modelAttemptFailures)
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
    invocationRef,
    modelAttemptFailuresRef,
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

  useEffect(() => {
    invocationRef.current = modelInvocation
    toolInvocationsRef.current = toolInvocations
    modelAttemptFailuresRef.current = modelAttemptFailures
    const snapshot = snapshotModelStream(threadId, modelInvocation)
    snapshotModelStreamRef.current = snapshot
    const attemptFailed =
      modelInvocation != null
      && isFailedAttempt(modelInvocation.attempt, modelInvocation, modelAttemptFailures)
    if (attemptFailed) {
      clearModelRecovery()
    }
    const current = modelStreamRef.current
    let next = current
    if (snapshot == null) {
      clearModelRecovery()
      next = null
    } else if (snapshot.status !== 'streaming') {
      // 持久化终态边界：resultJson/errorJson 投影无条件
      // 取代任何更高 sequence 的 lossy realtime overlay（流式 seq8 绝不能压过
      // 持久化 seq7 的结果）。resultEntryId == null 时投影保持可见，直到
      // 持久化 Entry 到达；期间事件处理器 fence 拒绝后续 delta。
      clearModelRecovery()
      next = snapshot
    } else {
      const recovery = modelRecoveryRef.current
      if (
        !attemptFailed
        && recovery != null
        && recovery.threadId === snapshot.threadId
        && recovery.invocationId === snapshot.invocationId
        && recovery.attempt === snapshot.attempt
      ) {
        // checkpoint 响应竞态：以 snapshot 为基线顺序拼接在途缓冲的后缀，
        // 快照落后时保留已显示 overlay，绝不回退或跳过缺口。
        const target = recovery.pendingCheckpointSequence
          ?? Math.max(0, recovery.firstGapSequence - 1)
        const merged = mergeRealtimeModelStreamAfterCheckpoint(
          snapshot,
          current,
          [...recovery.buffered.values()],
          target,
        )
        next = hasVisibleModelOverlay(merged.stream) ? merged.stream : null
        dropBufferedThrough(recovery, next?.sequence ?? 0)
        const checkpointCovered = merged.stream.sequence >= target
        const suffixComplete = merged.missingSequence == null
        const overflowCovered =
          !recovery.overflowed || merged.stream.sequence >= recovery.highestObservedSequence
        if (checkpointCovered && suffixComplete && overflowCovered) {
          clearModelRecovery()
        } else {
          // 该 checkpoint 仍不足（或 snapshot 暂时落后）：保持 gap，等下一周期
          // checkpoint/终态/attempt 切换；不重复拉旧 snapshot，也不自动重试。
          recovery.pendingCheckpointSequence = null
          if (recovery.overflowed) {
            // 已丢弃的 suffix 只能由覆盖最高已观察 sequence 的 checkpoint 恢复。
            recovery.firstGapSequence = Math.max(
              recovery.firstGapSequence,
              recovery.highestObservedSequence + 1,
            )
          } else if (
            merged.missingSequence != null
            && merged.missingSequence > recovery.firstGapSequence - 1
          ) {
            // 只把缺口阈值向后推；首次跳号 current=2 / observed=5 的阈值仍是
            // firstGapSequence=5 => checkpoint>=4，绝不能弱化成 missing=3 => 2。
            recovery.firstGapSequence = merged.missingSequence + 1
          }
          recovery.requested = true
        }
      } else {
        if (recovery != null) {
          clearModelRecovery()
        }
        if (
          current == null
          || current.threadId !== snapshot.threadId
          || current.invocationId !== snapshot.invocationId
          || current.attempt !== snapshot.attempt
          || snapshot.sequence >= current.sequence
        ) {
          const hasVisibleOverlay =
            snapshot.sequence > 0
            || Boolean(snapshot.text)
            || Boolean(snapshot.thinking)
            || snapshot.toolCalls.length > 0
            || (
              current != null
              && current.threadId === snapshot.threadId
              && current.invocationId === snapshot.invocationId
              && current.attempt === snapshot.attempt
              && current.toolCalls.length > 0
            )
          next = hasVisibleOverlay
            ? mergeSnapshotToolCalls(snapshot, current)
            : null
        }
      }
    }
    const modelStreamDirty =
      !sameModelStreamNullable(modelStream, next)
      || !sameModelStreamNullable(modelStreamRef.current, next)
    if (modelStreamDirty) {
      modelStreamRef.current = next
      setModelStream(next)
      scheduler.cancelModel()
    }

    // 用持久化 snapshot 对账 tool overlay。ToolResult Entry 写入与 invocation 删除
    // 在同一事务原子提交：invocation 仍存在时，终态 resultJson/errorJson 以完整形式
    // 投影并取代部分片段；invocation 从 snapshot 消失时才删除流。RUNNING 的
    // invocation 保留同 attempt 的部分内容。
    const streams = new Map(toolStreamsRef.current)
    const activeIds = new Set<string>()
    for (const invocation of toolInvocations) {
      activeIds.add(invocation.id)
      const existing = streams.get(invocation.id)
      const seeded = snapshotToolStream(invocation, threadId)!
      if (invocation.resultJson != null || invocation.errorJson != null) {
        // 持久化终态投影优先于部分片段。
        streams.set(invocation.id, seeded)
      } else if (existing == null) {
        streams.set(invocation.id, seeded)
      } else if (!isRealtimeToolStreamActive(existing, invocation)) {
        streams.delete(invocation.id)
      }
    }
    for (const key of [...streams.keys()]) {
      if (!activeIds.has(key)) {
        streams.delete(key)
      }
    }
    // 已见 eventId 只作用于活跃的同 attempt invocation：attempt
    // 变化、终态 snapshot 和消失的 invocation 都会淘汰它们。
    const seenEventIds = new Map<string, Set<string>>()
    for (const invocation of toolInvocations) {
      if (invocation.resultJson == null && invocation.errorJson == null) {
        const key = `${threadId}:${invocation.id}:${invocation.attempt}`
        const existing = toolPartialSeenEventIdsRef.current.get(key)
        if (existing != null) {
          seenEventIds.set(key, existing)
        }
      }
    }
    toolPartialSeenEventIdsRef.current = seenEventIds
    // 检查 React state 或 ref 是否需要对账更新。若有更新则立即同步发布并解除 tool 脏标记，
    // 避免 snapshot 对账与未决帧之间丢失 partial。
    const toolStreamsDirty =
      !sameToolStreamMap(toolStreams, streams)
      || !sameToolStreamMap(toolStreamsRef.current, streams)
    if (toolStreamsDirty) {
      toolStreamsRef.current = streams
      setToolStreams(streams)
      scheduler.cancelTool()
    }
  }, [clearModelRecovery, modelAttemptFailures, modelInvocation, modelStream, scheduler, threadId, toolInvocations, toolStreams])

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
    // 协议 data 是 envelope 的 JSON 对象；回序列化为同一文本后，reducer 的严格解析保持不变。
    const handleRealtime = (data: unknown) => {
      const raw = JSON.stringify(data)
      const checkpoint = parseRealtimeModelCheckpoint(raw)
      if (checkpoint != null && checkpoint.threadId === threadId) {
        const durable = invocationRef.current
        if (
          !isActiveModelAttempt(
            durable,
            threadId,
            checkpoint.invocationId,
            checkpoint.attempt,
            modelAttemptFailuresRef.current,
          )
        ) {
          return
        }
        const current =
          modelStreamRef.current
          ?? snapshotModelStreamRef.current
          ?? snapshotModelStream(threadId, durable)
        const visibleSequence = current?.sequence ?? 0
        let recovery = modelRecoveryRef.current
        if (
          recovery != null
          && (
            recovery.invocationId !== checkpoint.invocationId
            || recovery.attempt !== checkpoint.attempt
          )
        ) {
          clearModelRecovery()
          recovery = null
        }
        if (recovery == null && checkpoint.sequence <= visibleSequence) {
          // 连续 delta overlay 已完整覆盖该 durable 水位（例如周期 checkpoint 仍
          // 停在已显示的 sequence）：无需创建恢复 episode，也无需无意义回读。
          return
        }
        const requiredSequence =
          recovery != null
            ? Math.max(0, recovery.firstGapSequence - 1)
            : visibleSequence
        if (checkpoint.sequence < requiredSequence) {
          // 尚未覆盖缺口（例如仅重复了已见的 durable 水位）：等待下一周期。
          return
        }
        if (recovery == null) {
          recovery = createModelRecovery(
            threadId,
            checkpoint.invocationId,
            checkpoint.attempt,
            visibleSequence + 1,
          )
          modelRecoveryRef.current = recovery
        }
        recovery.requested = true
        recovery.pendingCheckpointSequence = Math.max(
          recovery.pendingCheckpointSequence ?? 0,
          checkpoint.sequence,
        )
        // 真正的单飞锁是 refreshInFlight；pendingCheckpointSequence 只记录等待
        // snapshot 覆盖的水位。promise settled 后立即释放锁，不依赖 props 变化；
        // 同 sequence 的下一 checkpoint 事件也可再次触发（非自动重试）。
        if (!recovery.refreshInFlight) {
          recovery.refreshInFlight = true
          // 仅在 checkpoint 覆盖缺口/推进水位时回读一次；快照仍落后就等下一周期，
          // 不循环拉取旧 snapshot。旧 recovery/thread/attempt 的 settle 不得
          // 清掉新 recovery 的在途状态。
          void refetchSnapshot()
            .catch(() => undefined)
            .finally(() => {
              if (modelRecoveryRef.current === recovery) {
                recovery.refreshInFlight = false
              }
            })
        }
        return
      }
      const delta = parseRealtimeModelDelta(raw)
      if (delta != null && delta.threadId === threadId) {
        // 持久化终态 fence：终态 ModelInvocation（result/error/已挂接 result
        // Entry）拒绝所有迟到的 MODEL_DELTA。冻结的 checkpoint overlay 在
        // Entry 到达前保持可见，但终态输出永远不会再追加。
        const durable = invocationRef.current
        if (
          !isActiveModelAttempt(
            durable,
            threadId,
            delta.invocationId,
            delta.attempt,
            modelAttemptFailuresRef.current,
          )
        ) {
          return
        }
        const current = modelStreamRef.current
        const base =
          current != null
          && current.invocationId === delta.invocationId
          && current.attempt === delta.attempt
            ? current
            : (snapshotModelStreamRef.current ?? snapshotModelStream(threadId, durable))
        if (base == null) {
          return
        }
        let recovery = modelRecoveryRef.current
        if (
          recovery != null
          && (
            recovery.invocationId !== delta.invocationId
            || recovery.attempt !== delta.attempt
          )
        ) {
          clearModelRecovery()
          recovery = null
        }
        if (recovery == null && isRealtimeModelDeltaGap(base, delta)) {
          recovery = createModelRecovery(
            threadId,
            delta.invocationId,
            delta.attempt,
            delta.sequence,
          )
          modelRecoveryRef.current = recovery
        }
        if (recovery != null) {
          // gap 或 checkpoint 在途期间到达的 delta 一律按 sequence 入有界 suffix
          // 缓冲；snapshot 基线到达后再顺序拼接，绝不把缺口后的片段直接显示。
          bufferModelDelta(recovery, delta)
          if (!recovery.requested) {
            recovery.requested = true
            requestCatchup(threadId, delta.invocationId, delta.attempt)
          }
          return
        }
        const next = reduceRealtimeModelStream(base, delta)
        modelStreamRef.current = next
        scheduler.notifyModelDirty()
        return
      }
      const partial = parseRealtimeToolPartial(raw)
      if (partial == null || partial.threadId !== threadId) {
        return
      }
      // Snapshot 优先：只有 invocation 在持久化 snapshot 中仍然活跃时
      // （同 attempt、无终态 result/error）才聚合 partial。一旦终态
      // resultJson/errorJson 成为权威（ToolResult Entry 与 invocation 删除原子
      // 提交：Entry 落地即 invocation 消失），迟到的或重复的 lossy realtime partial
      // 绝不能追加到完整的终态投影上。
      const invocation = toolInvocationsRef.current.find(
        (item) => item.id === partial.invocationId,
      )
      if (
        invocation == null
        || invocation.attempt !== partial.attempt
        || invocation.resultJson != null
        || invocation.errorJson != null
      ) {
        return
      }
      const isProcessOutput = isProcessOutputPartial(partial.payload)
      if (!isProcessOutput) {
        // 事件 identity 去重 fence：只有普通非 process.output 的 partial 以生产端 eventId
        // 去重；process.output 依赖 offset 严格去重，不进入这里。
        const seenKey = `${threadId}:${partial.invocationId}:${partial.attempt}`
        let seenEventIds = toolPartialSeenEventIdsRef.current.get(seenKey)
        if (seenEventIds == null) {
          seenEventIds = new Set()
          toolPartialSeenEventIdsRef.current.set(seenKey, seenEventIds)
        }
        if (seenEventIds.has(partial.eventId)) {
          return
        }
        if (seenEventIds.size >= TOOL_PARTIAL_SEEN_EVENT_ID_LIMIT) {
          const oldest = seenEventIds.values().next().value
          if (oldest !== undefined) {
            seenEventIds.delete(oldest)
          }
        }
        seenEventIds.add(partial.eventId)
      }
      const streams = toolStreamsRef.current
      const current = streams.get(partial.invocationId) ?? null
      const next = reduceRealtimeToolStream(current, partial)
      if (next !== current) {
        streams.set(partial.invocationId, next)
        toolStreamsRef.current = streams
        scheduler.notifyToolDirty()
      }
    }

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
          const durable = invocationRef.current
          if (
            durable != null
            && durable.status === 'RUNNING'
            && isActiveModelAttempt(
              durable,
              subscription.threadId,
              durable.id,
              durable.attempt,
              modelAttemptFailuresRef.current,
            )
          ) {
            requestCatchup(subscription.threadId, durable.id, durable.attempt, true)
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

function sameToolStreamMap(
  left: ReadonlyMap<string, RealtimeToolStream>,
  right: ReadonlyMap<string, RealtimeToolStream>,
): boolean {
  if (left.size !== right.size) {
    return false
  }
  for (const [key, stream] of left) {
    const other = right.get(key)
    if (other == null || !sameToolStream(stream, other)) {
      return false
    }
  }
  return true
}

function sameToolStream(left: RealtimeToolStream, right: RealtimeToolStream): boolean {
  return left.threadId === right.threadId
    && left.invocationId === right.invocationId
    && left.attempt === right.attempt
    && left.toolCallId === right.toolCallId
    && left.text === right.text
    && left.error === right.error
    && left.errorText === right.errorText
    && sameProcessOutput(left.processOutput, right.processOutput)
}

function sameProcessOutput(
  left: RealtimeToolStream['processOutput'],
  right: RealtimeToolStream['processOutput'],
): boolean {
  if (left == null && right == null) {
    return true
  }
  if (left == null || right == null) {
    return false
  }
  return left.mode === right.mode
    && left.startOffset === right.startOffset
    && left.endOffset === right.endOffset
    && left.observedBytes === right.observedBytes
    && left.hasOmittedPrefix === right.hasOmittedPrefix
    && left.gapPending === right.gapPending
}

function mergeSnapshotToolCalls(
  snapshot: RealtimeModelStream,
  current: RealtimeModelStream | null,
): RealtimeModelStream {
  if (
    snapshot.toolCalls.length > 0
    || current == null
    || current.threadId !== snapshot.threadId
    || current.invocationId !== snapshot.invocationId
    || current.attempt !== snapshot.attempt
    || current.toolCalls.length === 0
  ) {
    return snapshot
  }
  return { ...snapshot, toolCalls: current.toolCalls }
}

function sameToolCallDrafts(
  left: RealtimeModelStream['toolCalls'],
  right: RealtimeModelStream['toolCalls'],
): boolean {
  if (left.length !== right.length) {
    return false
  }
  return left.every((item, index) => {
    const other = right[index]
    return other != null
      && item.index === other.index
      && item.id === other.id
      && item.name === other.name
      && item.argumentsJson === other.argumentsJson
  })
}

function sameModelStreamNullable(
  left: RealtimeModelStream | null,
  right: RealtimeModelStream | null,
): boolean {
  if (left === right) {
    return true
  }
  if (left == null || right == null) {
    return false
  }
  return sameModelStream(left, right)
}

function sameModelStream(left: RealtimeModelStream, right: RealtimeModelStream): boolean {
  return left.threadId === right.threadId
    && left.invocationId === right.invocationId
    && left.attempt === right.attempt
    && left.text === right.text
    && left.thinking === right.thinking
    && sameToolCallDrafts(left.toolCalls, right.toolCalls)
    && left.sequence === right.sequence
    && left.status === right.status
    && left.errorCode === right.errorCode
    && left.errorText === right.errorText
}

/** 每个 thread:invocation:attempt 保留的已见 eventId 上限；仅 best-effort 防重投递。 */
const TOOL_PARTIAL_SEEN_EVENT_ID_LIMIT = 256
/** gap / checkpoint 在途期间缓存的 delta suffix 硬上限；超限后清空并继续等 checkpoint。 */
const MODEL_GAP_BUFFER_MAX_DELTAS = 256
/** suffix 预算按 UTF-8 字节计，避免非 ASCII/emoji 按 UTF-16 code unit 低估真实体积。 */
const MODEL_GAP_BUFFER_MAX_BYTES = 512 * 1024
const UTF8_ENCODER = new TextEncoder()

interface ModelRecovery {
  threadId: string
  invocationId: string
  attempt: number
  /** 触发恢复的首个跳号 delta；null 表示只等待订阅 catchup checkpoint。 */
  firstGapSequence: number
  /** 已按 sequence 索引的待拼接 suffix。 */
  buffered: Map<number, RealtimeModelDelta>
  bufferedBytes: number
  highestObservedSequence: number
  /** 缓冲超限后不再信任已丢弃的 suffix，只能等待覆盖它的下一 checkpoint。 */
  overflowed: boolean
  /** 本恢复 episode 已发过 catchup（或已被订阅 catchup 覆盖）。 */
  requested: boolean
  /** 已接受、等待 snapshot 覆盖的 checkpoint 水位（拼接目标）。 */
  pendingCheckpointSequence: number | null
  /** 真正在途的 snapshot 回读；与 pending 水位分离，settled 后即释放。 */
  refreshInFlight: boolean
}

function createModelRecovery(
  threadId: string,
  invocationId: string,
  attempt: number,
  firstGapSequence: number,
): ModelRecovery {
  return {
    threadId,
    invocationId,
    attempt,
    firstGapSequence,
    buffered: new Map(),
    bufferedBytes: 0,
    highestObservedSequence: firstGapSequence - 1,
    overflowed: false,
    requested: false,
    pendingCheckpointSequence: null,
    refreshInFlight: false,
  }
}

function bufferModelDelta(recovery: ModelRecovery, delta: RealtimeModelDelta): void {
  if (recovery.buffered.has(delta.sequence)) {
    return
  }
  const size = deltaByteSize(delta)
  if (
    recovery.buffered.size >= MODEL_GAP_BUFFER_MAX_DELTAS
    || recovery.bufferedBytes + size > MODEL_GAP_BUFFER_MAX_BYTES
  ) {
    // 有界：丢弃已缓存 suffix 并保持 gap；后续只接受能覆盖全部已观察
    // sequence 的 checkpoint，绝不无界积累。
    recovery.buffered.clear()
    recovery.bufferedBytes = 0
    recovery.overflowed = true
    recovery.highestObservedSequence = Math.max(
      recovery.highestObservedSequence,
      delta.sequence,
    )
    return
  }
  recovery.buffered.set(delta.sequence, delta)
  recovery.bufferedBytes += size
  recovery.highestObservedSequence = Math.max(
    recovery.highestObservedSequence,
    delta.sequence,
  )
}

function dropBufferedThrough(recovery: ModelRecovery, sequence: number): void {
  for (const [bufferedSequence, delta] of [...recovery.buffered]) {
    if (bufferedSequence <= sequence) {
      recovery.buffered.delete(bufferedSequence)
      recovery.bufferedBytes = Math.max(0, recovery.bufferedBytes - deltaByteSize(delta))
    }
  }
}

function deltaByteSize(delta: RealtimeModelDelta): number {
  return UTF8_ENCODER.encode(delta.text).length
    + UTF8_ENCODER.encode(delta.toolCall?.id ?? '').length
    + UTF8_ENCODER.encode(delta.toolCall?.name ?? '').length
    + UTF8_ENCODER.encode(delta.toolCall?.argumentsJson ?? '').length
}

function hasVisibleModelOverlay(stream: RealtimeModelStream): boolean {
  return stream.sequence > 0
    || Boolean(stream.text)
    || Boolean(stream.thinking)
    || stream.toolCalls.length > 0
}

function isActiveModelAttempt(
  invocation: ModelInvocationDTO | null,
  threadId: string,
  invocationId: string,
  attempt: number,
  failures: readonly ModelAttemptFailureDTO[],
): invocation is ModelInvocationDTO {
  return invocation != null
    && invocation.threadId === threadId
    && invocation.id === invocationId
    && invocation.attempt === attempt
    && invocation.resultJson == null
    && invocation.errorJson == null
    && invocation.resultEntryId == null
    && !isFailedAttempt(attempt, invocation, failures)
}

function useRealtimeFrameScheduler(
  modelStreamRef: { readonly current: RealtimeModelStream | null },
  invocationRef: { readonly current: ModelInvocationDTO | null },
  modelAttemptFailuresRef: { readonly current: readonly ModelAttemptFailureDTO[] },
  toolStreamsRef: { readonly current: Map<string, RealtimeToolStream> },
  setModelStream: (stream: RealtimeModelStream | null) => void,
  setToolStreams: (streams: ReadonlyMap<string, RealtimeToolStream>) => void,
  schedulerOptions?: Partial<RealtimeFrameSchedulerOptions>,
): RealtimeFrameScheduler {
  const [scheduler] = useState(() =>
    createRealtimeFrameScheduler(schedulerOptions),
  )

  useEffect(() => {
    scheduler.setOnFlush(({ modelDirty, toolDirty }) => {
      if (modelDirty) {
        const current = modelStreamRef.current
        const durable = invocationRef.current
        const isTerminal =
          durable != null
          && (durable.resultJson != null || durable.errorJson != null || durable.resultEntryId != null)
        const isFailed =
          current != null
          && durable != null
          && isFailedAttempt(current.attempt, durable, modelAttemptFailuresRef.current)
        if (!isTerminal && !isFailed) {
          setModelStream(current)
        }
      }
      if (toolDirty) {
        setToolStreams(new Map(toolStreamsRef.current))
      }
    })
  }, [
    invocationRef,
    modelAttemptFailuresRef,
    modelStreamRef,
    scheduler,
    setModelStream,
    setToolStreams,
    toolStreamsRef,
  ])

  return scheduler
}
