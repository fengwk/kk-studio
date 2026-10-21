import type { RealtimeFrameScheduler } from '@/features/chat/runtime/realtime-frame-scheduler'
import {
  isRealtimeModelDeltaGap,
  mergeRealtimeModelStreamAfterCheckpoint,
  parseRealtimeModelCheckpoint,
  parseRealtimeModelDelta,
  reduceRealtimeModelStream,
  snapshotModelStream,
  isFailedAttempt,
  type RealtimeModelStream,
} from '@/features/chat/runtime/thread-realtime/model-stream'
import {
  isProcessOutputPartial,
  isRealtimeToolStreamActive,
  parseRealtimeToolPartial,
  reduceRealtimeToolStream,
  snapshotToolStream,
  type RealtimeToolStream,
} from '@/features/chat/runtime/thread-realtime/tool-stream'
import {
  TOOL_PARTIAL_SEEN_EVENT_ID_LIMIT,
  bufferModelDelta,
  createModelRecovery,
  dropBufferedThrough,
  hasVisibleModelOverlay,
  isActiveModelAttempt,
  type ModelRecovery,
} from '@/features/chat/runtime/thread-realtime/model-recovery'
import {
  mergeSnapshotToolCalls,
  sameModelStreamNullable,
  sameToolStreamMap,
} from '@/features/chat/runtime/thread-realtime/stream-equality'
import type {
  ModelAttemptFailureDTO,
  ModelInvocationDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'

type Ref<T> = { current: T }

/**
 * Realtime overlay 的可变上下文：refs 是 overlay 的同步事实源（事件处理与 snapshot 对账
 * 都在 React commit 之外读写），state setter 只负责把 ref 发布给渲染。
 */
export interface RealtimeStreamContext {
  threadId: string
  scheduler: RealtimeFrameScheduler
  modelStreamRef: Ref<RealtimeModelStream | null>
  toolStreamsRef: Ref<Map<string, RealtimeToolStream>>
  snapshotModelStreamRef: Ref<RealtimeModelStream | null>
  invocationRef: Ref<ModelInvocationDTO | null>
  toolInvocationsRef: Ref<ToolInvocationDTO[]>
  modelAttemptFailuresRef: Ref<readonly ModelAttemptFailureDTO[]>
  modelRecoveryRef: Ref<ModelRecovery | null>
  toolPartialSeenEventIdsRef: Ref<Map<string, Set<string>>>
  clearModelRecovery: () => void
}

/** 持久化 snapshot 到达后对账 model/tool overlay：终态投影取代 lossy 增量。 */
export function reconcileSnapshotStreams(
  ctx: RealtimeStreamContext,
  input: {
    modelInvocation: ModelInvocationDTO | null
    toolInvocations: ToolInvocationDTO[]
    modelAttemptFailures: readonly ModelAttemptFailureDTO[]
    modelStream: RealtimeModelStream | null
    toolStreams: ReadonlyMap<string, RealtimeToolStream>
    setModelStream: (stream: RealtimeModelStream | null) => void
    setToolStreams: (streams: ReadonlyMap<string, RealtimeToolStream>) => void
  },
): void {
  const {
    threadId, scheduler, modelStreamRef, toolStreamsRef, snapshotModelStreamRef, invocationRef,
    toolInvocationsRef, modelAttemptFailuresRef, modelRecoveryRef, toolPartialSeenEventIdsRef,
    clearModelRecovery,
  } = ctx
  const {
    modelInvocation, toolInvocations, modelAttemptFailures, modelStream, toolStreams,
    setModelStream, setToolStreams,
  } = input
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
}

/**
 * 处理一条 lossy realtime 事件（MODEL_CHECKPOINT / MODEL_DELTA / TOOL_PARTIAL）：
 * 只更新瞬态 overlay 与恢复状态机，绝不写入持久事实。
 */
export function createRealtimeEventHandler(
  ctx: RealtimeStreamContext,
  deps: {
    refetchSnapshot: () => Promise<void>
    requestCatchup: (threadId: string, invocationId: string, attempt: number, force?: boolean) => void
  },
): (data: unknown) => void {
  const {
    threadId, scheduler, modelStreamRef, toolStreamsRef, snapshotModelStreamRef, invocationRef,
    toolInvocationsRef, modelAttemptFailuresRef, modelRecoveryRef, toolPartialSeenEventIdsRef,
    clearModelRecovery,
  } = ctx
  const { refetchSnapshot, requestCatchup } = deps
  // 协议 data 是 envelope 的 JSON 对象；回序列化为同一文本后，reducer 的严格解析保持不变。
  return (data: unknown) => {
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
}
