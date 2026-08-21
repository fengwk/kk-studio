import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useApplicationEvents } from '@/shared/app-events'
import {
  isRealtimeModelDeltaGap,
  isRealtimeToolStreamActive,
  parseRealtimeModelDelta,
  parseRealtimeToolPartial,
  reduceRealtimeModelStream,
  reduceRealtimeToolStream,
  snapshotModelStream,
  snapshotToolStream,
  type RealtimeModelStream,
  type RealtimeToolPartial,
  type RealtimeToolStream,
} from '@/features/ai/runtime/thread-realtime-state'
import { queryKeys } from '@/shared/lib/query-keys'
import type { ModelInvocationDTO, ToolInvocationDTO } from '@/shared/api/contracts/ai-runtime'
import type { ToolAttachment } from '@/features/ai/runtime/thread-timeline-types'

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
 * 2) 在其持久化 version 之后监听。Redis 支撑的 {@code realtime} MODEL_DELTA / TOOL_PARTIAL
 *    事件渲染为瞬态 overlay，直到持久化 Entries 到达；重连与流丢失后 PostgreSQL 仍是
 *    权威来源。Redis 永远不是持久化事实。
 */
export function useHarnessThreadRealtime(
  threadId: string,
  enabled: boolean,
  version: string | undefined,
  modelInvocation: ModelInvocationDTO | null,
  toolInvocations: ToolInvocationDTO[],
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
  const invocationRef = useRef<ModelInvocationDTO | null>(modelInvocation)
  const toolInvocationsRef = useRef<ToolInvocationDTO[]>(toolInvocations)
  const gapRef = useRef<{
    invocationId: string
    attempt: number
    sequence: number
  } | null>(null)
  // TOOL_PARTIAL 的有界精确去重指纹（Redis 可能重投递）：按
  // thread:invocation:attempt 分组，因此 attempt 变化/终态 snapshot 会自然淘汰它们。
  const toolPartialFingerprintsRef = useRef<Map<string, Set<string>>>(new Map())
  const subscriptionReady = enabled && version != null

  const { requestGapRecovery, clearRecoveryLoop } = useGapRecoveryLoop(
    queryClient,
    modelStreamRef,
    invocationRef,
  )

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
    const snapshot = snapshotModelStream(threadId, modelInvocation)
    const current = modelStreamRef.current
    let next = current
    if (snapshot == null) {
      gapRef.current = null
      next = null
    } else if (snapshot.status !== 'streaming') {
      // 持久化终态边界：resultJson/errorJson 投影无条件
      // 取代任何更高 sequence 的 Redis overlay（流式 seq8 绝不能压过
      // 持久化 seq7 的结果）。resultEntryId == null 时投影保持可见，直到
      // 持久化 Entry 到达；期间事件处理器 fence 拒绝后续 delta。
      next = snapshot
      const gap = gapRef.current
      if (
        gap != null
        && gap.invocationId === snapshot.invocationId
        && gap.attempt === snapshot.attempt
        && snapshot.sequence >= gap.sequence
      ) {
        gapRef.current = null
      }
    } else if (
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
      const gap = gapRef.current
      if (
        gap != null
        && gap.invocationId === snapshot.invocationId
        && gap.attempt === snapshot.attempt
        && snapshot.sequence >= gap.sequence
      ) {
        gapRef.current = null
      }
    }
    if (current != null && next != null && sameModelStream(current, next)) {
      next = current
    }
    modelStreamRef.current = next
    setModelStream(next)

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
    // 精确去重指纹只作用于活跃的同 attempt invocation：attempt
    // 变化、终态 snapshot 和消失的 invocation 都会淘汰它们。
    const fingerprints = new Map<string, Set<string>>()
    for (const invocation of toolInvocations) {
      if (invocation.resultJson == null && invocation.errorJson == null) {
        const key = `${threadId}:${invocation.id}:${invocation.attempt}`
        const existing = toolPartialFingerprintsRef.current.get(key)
        if (existing != null) {
          fingerprints.set(key, existing)
        }
      }
    }
    toolPartialFingerprintsRef.current = fingerprints
    // overlay map 内容未变时提前返回，避免该 effect 自己反复触发
    // 渲染（稳定的 query 派生输入让重跑成为 no-op）。
    if (!sameToolStreamMap(toolStreamsRef.current, streams)) {
      toolStreamsRef.current = streams
      setToolStreams(streams)
    }
  }, [modelInvocation, threadId, toolInvocations])

  useEffect(() => {
    // 只有 Thread 消失或订阅真正被禁用才清空 overlays；`subscription == null` 或
    // threadId 暂时不匹配只是订阅状态过渡（同一提交中 seed effect 刚播种、
    // subscription effect 即将落定）：保留 snapshot-seeded overlay，等下一 render
    // 的 subscription 就绪后通过应用级 Manager 建立订阅。
    if (!threadId || !subscriptionReady) {
      clearRecoveryLoop()
      modelStreamRef.current = null
      setModelStream(null)
      toolStreamsRef.current = new Map()
      setToolStreams(new Map())
      toolPartialFingerprintsRef.current = new Map()
      return undefined
    }
    if (subscription == null || subscription.threadId !== threadId) {
      // 过渡 render：订阅状态尚未就绪（或 threadId 暂不匹配）。保留
      // snapshot-seeded overlay，等待下一 render 建立订阅后继续消费。
      return undefined
    }

    const invalidateSnapshot = () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) })
    // 协议 data 是 delta envelope 的 JSON 对象；回序列化为同一文本后，
    // reducer 的严格解析保持不变。
    const handleRealtime = (data: unknown) => {
      const raw = JSON.stringify(data)
      const delta = parseRealtimeModelDelta(raw)
      if (delta != null && delta.threadId === threadId) {
        // 持久化终态 fence：终态 ModelInvocation（result/error/已挂接 result
        // Entry）拒绝所有迟到的 MODEL_DELTA。冻结的 checkpoint overlay 在
        // Entry 到达前保持可见，但终态输出永远不会再追加。
        const durable = invocationRef.current
        if (
          durable == null
          || durable.resultJson != null
          || durable.errorJson != null
          || durable.resultEntryId != null
        ) {
          return
        }
        const snapshot = snapshotModelStream(threadId, durable)
        if (
          snapshot == null
          || snapshot.invocationId !== delta.invocationId
          || snapshot.attempt !== delta.attempt
        ) {
          return
        }
        const current = modelStreamRef.current
        const base =
          current != null
          && current.invocationId === snapshot.invocationId
          && current.attempt === snapshot.attempt
            ? current
            : snapshot
        if (isRealtimeModelDeltaGap(base, delta)) {
          gapRef.current = {
            invocationId: delta.invocationId,
            attempt: delta.attempt,
            sequence: delta.sequence,
          }
          // 单飞恢复：同一时刻只做一次 refetch，带有限退避重新武装，
          // 直到 checkpoint 追上（既不是一次性，也不会每个 delta 并发）。
          requestGapRecovery(threadId, delta.invocationId, delta.attempt, delta.sequence)
          return
        }
        const next = reduceRealtimeModelStream(base, delta)
        modelStreamRef.current = next
        setModelStream(next)
        return
      }
      const partial = parseRealtimeToolPartial(raw)
      if (partial == null || partial.threadId !== threadId) {
        return
      }
      // Snapshot 优先：只有 invocation 在持久化 snapshot 中仍然活跃时
      // （同 attempt、无终态 result/error）才聚合 partial。一旦终态
      // resultJson/errorJson 成为权威（ToolResult Entry 与 invocation 删除原子
      // 提交：Entry 落地即 invocation 消失），迟到的或重复的 Redis partial
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
      // 精确去重 fence：Redis 可能重投递同一 TOOL_PARTIAL 事件；稳定
      // 指纹（规范化 payload + createdAt）不能让同一块追加两次。
      const fingerprintKey = `${threadId}:${partial.invocationId}:${partial.attempt}`
      let fingerprints = toolPartialFingerprintsRef.current.get(fingerprintKey)
      if (fingerprints == null) {
        fingerprints = new Set()
        toolPartialFingerprintsRef.current.set(fingerprintKey, fingerprints)
      }
      const fingerprint = fingerprintOf(partial)
      if (fingerprints.has(fingerprint)) {
        return
      }
      // 有界 FIFO：只淘汰最旧的指纹，保证最近 N 个保持去重
      // （整体清空会让立即重投递的事件再次追加）。
      if (fingerprints.size >= TOOL_PARTIAL_FINGERPRINT_LIMIT) {
        const oldest = fingerprints.values().next().value
        if (oldest !== undefined) {
          fingerprints.delete(oldest)
        }
      }
      fingerprints.add(fingerprint)
      const streams = toolStreamsRef.current
      const current = streams.get(partial.invocationId) ?? null
      const next = reduceRealtimeToolStream(current, partial)
      streams.set(partial.invocationId, next)
      toolStreamsRef.current = streams
      setToolStreams(new Map(streams))
    }

    // 应用级 manager 订阅：version/resync/subscribed/error 都触发 snapshot
    // 对账（subscribed 在首次订阅与每次重连重订阅后到达，关闭断线窗口）；
    // realtime 事件走 overlay reducer。连接与重连由单例 Connection 负责。
    const unsubscribe = applicationEvents.subscribe(
      { kind: 'thread', id: subscription.threadId },
      {
        onSubscribed: invalidateSnapshot,
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
      clearRecoveryLoop()
      toolPartialFingerprintsRef.current = new Map()
    }
  }, [
    applicationEvents,
    clearRecoveryLoop,
    queryClient,
    requestGapRecovery,
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
    && sameAttachments(left.attachments, right.attachments)
}

function sameAttachments(
  left: ToolAttachment[] | undefined,
  right: ToolAttachment[] | undefined,
): boolean {
  if (left == null && right == null) {
    return true
  }
  if (left == null || right == null || left.length !== right.length) {
    return false
  }
  return left.every(
    (item, index) =>
      item.data === right[index]?.data
      && item.mime === right[index]?.mime
      && item.name === right[index]?.name
      && item.type === right[index]?.type,
  )
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

const RECOVERY_BACKOFF_BASE_MS = 200
const RECOVERY_BACKOFF_MAX_MS = 2000
const RECOVERY_MAX_ATTEMPTS = 8
const TOOL_PARTIAL_FINGERPRINT_LIMIT = 256

interface GapRecovery {
  threadId: string
  invocationId: string
  attempt: number
  gapSequence: number
  attempts: number
}

/**
 * 单飞 sequence 缺口恢复循环（KISS）：同一时刻一次 snapshot refetch，带有限
 * 退避，直到 checkpoint 追上、invocation 终态化或 attempt 变化。
 * 绝不会为每个 delta 并发 refetch，也不会在一次过期 refetch 后冻结；循环在
 * 卸载/切换 Thread 时取消。
 *
 * 稳定回调：latest-function ref 转发打破 schedule -> run -> schedule
 * 循环，因此订阅 effect 不会在重渲染时重新注册。
 */
function useGapRecoveryLoop(
  queryClient: QueryClient,
  modelStreamRef: { current: RealtimeModelStream | null },
  invocationRef: { current: ModelInvocationDTO | null },
): {
  requestGapRecovery: (
    recoveryThreadId: string,
    invocationId: string,
    attempt: number,
    gapSequence: number,
  ) => void
  clearRecoveryLoop: () => void
} {
  const recoveryRef = useRef<GapRecovery | null>(null)
  const recoveryBusyRef = useRef(false)
  const recoveryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const scheduleRecoveryTickRef = useRef<() => void>(() => undefined)
  const runRecoveryTickRef = useRef<() => Promise<void>>(async () => undefined)

  const scheduleRecoveryTick = useCallback(() => {
    if (recoveryBusyRef.current || recoveryTimerRef.current != null) {
      return
    }
    const recovery = recoveryRef.current
    if (recovery == null) {
      return
    }
    const delay = Math.min(
      RECOVERY_BACKOFF_BASE_MS * 2 ** recovery.attempts,
      RECOVERY_BACKOFF_MAX_MS,
    )
    recoveryTimerRef.current = setTimeout(() => {
      recoveryTimerRef.current = null
      void runRecoveryTickRef.current()
    }, delay)
  }, [])

  const runRecoveryTick = useCallback(async () => {
    const recovery = recoveryRef.current
    if (recovery == null) {
      return
    }
    recoveryBusyRef.current = true
    try {
      let refetchFailed = false
      try {
        // 使用 CAPTURED 的恢复 thread：本 tick 在途期间 hook 闭包的 threadId 可能
        // 已切换到另一个 Thread。
        await queryClient.invalidateQueries({
          queryKey: queryKeys.threads.snapshot(recovery.threadId),
        })
      } catch {
        // 失败的 refetch 不能冻结循环：异常逃逸时没有定时器被武装，
        // 因此计入 attempt 并继续按下面的退避重试。
        refetchFailed = true
      }
      if (recoveryRef.current === recovery) {
        // Generation fence 已通过：只有捕获的 recovery 可以被修改/清除。失去
        // fence 的旧 tick 会落入共享的重新武装出口，那里会调度
        // CURRENT 的 recovery（可能在本 tick 忙时被安装）。
        let terminal = false
        if (!refetchFailed) {
          // 已追上：reconcile effect 已把 overlay 刷新到超过最高缺口。
          const stream = modelStreamRef.current
          if (
            stream != null
            && stream.threadId === recovery.threadId
            && stream.invocationId === recovery.invocationId
            && stream.attempt === recovery.attempt
            && stream.sequence >= recovery.gapSequence
          ) {
            terminal = true
          } else {
            // 过期（Thread/attempt 变化）或持久化终态：停止并清理。
            const invocation = invocationRef.current
            if (
              invocation == null
              || invocation.threadId !== recovery.threadId
              || invocation.attempt !== recovery.attempt
              || invocation.resultJson != null
              || invocation.errorJson != null
              || invocation.resultEntryId != null
            ) {
              terminal = true
            }
          }
        }
        if (terminal) {
          recoveryRef.current = null
        } else {
          recovery.attempts += 1
          if (recovery.attempts > RECOVERY_MAX_ATTEMPTS) {
            // 放弃该缺口；后续 delta 或 snapshot 刷新会重新武装恢复。
            recoveryRef.current = null
          }
        }
      }
    } finally {
      recoveryBusyRef.current = false
    }
    // 单一重新武装出口。只有 busy 标志清除后才重新武装（否则
    // scheduleRecoveryTick 会提前返回）；本 tick 忙时安装的新 recovery
    // 也会在这里被武装。
    if (recoveryRef.current != null) {
      scheduleRecoveryTickRef.current()
    }
  }, [queryClient, modelStreamRef, invocationRef])

  const requestGapRecovery = useCallback(
    (
      recoveryThreadId: string,
      invocationId: string,
      attempt: number,
      gapSequence: number,
    ) => {
      const recovery = recoveryRef.current
      if (
        recovery != null
        && recovery.threadId === recoveryThreadId
        && recovery.invocationId === invocationId
        && recovery.attempt === attempt
      ) {
        recovery.gapSequence = Math.max(recovery.gapSequence, gapSequence)
        return
      }
      recoveryRef.current = {
        threadId: recoveryThreadId,
        invocationId,
        attempt,
        gapSequence,
        attempts: 0,
      }
      scheduleRecoveryTickRef.current()
    },
    [],
  )

  const clearRecoveryLoop = useCallback(() => {
    if (recoveryTimerRef.current != null) {
      clearTimeout(recoveryTimerRef.current)
      recoveryTimerRef.current = null
    }
    recoveryRef.current = null
  }, [])

  // Latest-function 转发（commit 之后）：定时器只在 layout/effect
  // 之后触发，所以 refs 始终持有当前 tick 实现。
  useEffect(() => {
    scheduleRecoveryTickRef.current = scheduleRecoveryTick
    runRecoveryTickRef.current = runRecoveryTick
  }, [runRecoveryTick, scheduleRecoveryTick])

  return { requestGapRecovery, clearRecoveryLoop }
}

function fingerprintOf(partial: RealtimeToolPartial): string {
  return `${partial.createdAt}|${JSON.stringify(partial.payload)}`
}
