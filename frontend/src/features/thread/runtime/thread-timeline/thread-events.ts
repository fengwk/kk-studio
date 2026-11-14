import { asRecord, getRecordList } from '@/features/thread/runtime/thread-timeline/payload-json'
import { getString } from '@/shared/lib/value'
import type {
  RealtimeModelStream,
} from '@/features/thread/runtime/thread-realtime/model-stream'
import type {
  RealtimeToolStream,
} from '@/features/thread/runtime/thread-realtime/tool-stream'
import type {
  HarnessActiveAssistantOutputDTO,
  HarnessGenerationFailureDTO,
  HarnessSessionEntryDTO,
  ToolInvocationDTO,
} from '@/shared/api/generated/contracts'
import type { TurnUsage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import type { ThreadEventRecord } from '@/features/thread/runtime/thread-timeline/thread-events-types'
import {
  accumulateUsage,
  compactJson,
  prettyJson,
  scalarText,
} from '@/features/thread/runtime/thread-timeline/thread-events-json'
import { projectEntryRecord } from '@/features/thread/runtime/thread-timeline/thread-events-entry'
import {
  projectActiveModelRecord,
  projectActiveToolRecord,
  projectAttemptFailureRecord,
} from '@/features/thread/runtime/thread-timeline/thread-events-active'

export type { ThreadEventRecord } from '@/features/thread/runtime/thread-timeline/thread-events-types'

export { eventStatusText } from '@/features/thread/runtime/thread-timeline/thread-events-types'

interface ThreadEventTimelineInput {
  entries: HarnessSessionEntryDTO[]
  activeAssistantOutput: HarnessActiveAssistantOutputDTO | null
  toolInvocations: ToolInvocationDTO[]
  generationFailures?: readonly HarnessGenerationFailureDTO[]
  modelStream?: RealtimeModelStream | null
  toolStreams?: ReadonlyMap<string, RealtimeToolStream> | null
}

/** durable Entry 扫描的副产品：Turn 线性映射与活跃 overlay 的物化身份集合。 */
interface DurableScan {
  records: ThreadEventRecord[]
  turnStartByEntryId: Map<string, string | null>
  turnNumberByTurnStart: Map<string, number>
  durableFailureIdentities: Set<string>
  durableToolResultIdentities: Set<string>
}

/**
 * 构建 Event 时间线：每个 durable Entry 恰好一条记录；synthetic 活跃 overlay
 * 锚定在所属 durable Entry 之后（找不到锚点追加末尾）。沿 entries 线性路径
 * 跟踪当前 TURN_START entryId 与 turnNumber，并把 Turn usage 挂起至该 Turn 的
 * TURN_END（读取该 Turn Assistant metadata 的完整 usage/cost）。
 *
 * snapshot 在 GenerationTerminalPending / ToolTerminalPending 窗口内会同时携带 durable
 * Entry 与尚未物化的活跃 overlay；按「Turn 内」的稳定 identity 去重，durable Entry
 * 是权威记录——旧 Turn 的 durable Entry 绝不能抑制新 Turn 相同
 * (attempt, sequence) / toolCallId 的活跃 overlay。
 */
export function buildThreadEventTimeline(
  input: ThreadEventTimelineInput,
): ThreadEventRecord[] {
  const {
    entries,
    activeAssistantOutput,
    toolInvocations,
    generationFailures = [],
    modelStream = null,
    toolStreams = null,
  } = input
  const scan = scanDurableEntries(entries)
  const turnOf = createTurnResolver(scan)
  const anchor = createOverlayAnchor(entries)
  projectActiveOverlays({
    anchor,
    scan,
    entries,
    activeAssistantOutput,
    toolInvocations,
    generationFailures,
    modelStream,
    toolStreams,
    turnOf,
  })
  return mergeAnchoredRecords(scan.records, anchor)
}

/** 逐条 durable Entry 投影，并记录 Turn 映射与已物化的失败/工具结果身份。 */
function scanDurableEntries(entries: readonly HarnessSessionEntryDTO[]): DurableScan {
  const scan: DurableScan = {
    records: [],
    turnStartByEntryId: new Map(),
    turnNumberByTurnStart: new Map(),
    durableFailureIdentities: new Set(),
    durableToolResultIdentities: new Set(),
  }
  let currentTurnStartEntryId: string | null = null
  let currentTurnNumber = 0
  let pendingUsage: TurnUsage | null = null
  for (const entry of entries) {
    if (entry.entryType === 'TURN_START') {
      currentTurnStartEntryId = entry.entryId
      currentTurnNumber += 1
      scan.turnNumberByTurnStart.set(currentTurnStartEntryId, currentTurnNumber)
      // 新 turn 开始：丢弃上一 turn 未关闭的残留 usage。
      pendingUsage = null
    }
    scan.turnStartByEntryId.set(entry.entryId, currentTurnStartEntryId)
    const payload = asRecord(entry.payload)
    scan.records.push(
      withRawPayload(
        projectEntryRecord(entry, payload, {
          turnStartEntryId: currentTurnStartEntryId,
          turnNumber: currentTurnNumber,
          pendingUsage,
        }),
        entry,
      ),
    )
    if (entry.entryType === 'MODEL_ATTEMPT_FAILURE') {
      recordDurableFailureIdentity(scan, payload, currentTurnStartEntryId)
      continue
    }
    if (entry.entryType === 'MESSAGE' || entry.entryType === 'CUSTOM_MESSAGE') {
      pendingUsage = recordMessageFacts(scan, entry, payload, currentTurnStartEntryId, pendingUsage)
    }
    if (entry.entryType === 'COMPACTION') {
      // 压缩结果是真实模型输出：其 canonical metadata 的 usage 同样归入该压缩 turn。
      pendingUsage = accumulateUsage(pendingUsage, payload, entry)
    }
    if (entry.entryType === 'TURN_END') {
      // usage 已发射进 TURN_END 记录；防止后续重复 TURN_END 复用。
      pendingUsage = null
    }
  }
  return scan
}

function recordDurableFailureIdentity(
  scan: DurableScan,
  payload: Record<string, unknown>,
  turnStartEntryId: string | null,
): void {
  const attempt = asRecord(payload.attempt)
  const attemptNumber = scalarText(attempt.attempt)
  const sequence = scalarText(attempt.sequence)
  if (attemptNumber && sequence) {
    scan.durableFailureIdentities.add(`${turnStartEntryId ?? ''}:${attemptNumber}:${sequence}`)
  }
}

/**
 * 一条 MESSAGE / CUSTOM_MESSAGE 的 Turn 事实：ASSISTANT usage 挂起，以及已物化的
 * tool_result 身份。返回更新后的挂起 usage。
 */
function recordMessageFacts(
  scan: DurableScan,
  entry: HarnessSessionEntryDTO,
  payload: Record<string, unknown>,
  turnStartEntryId: string | null,
  pendingUsage: TurnUsage | null,
): TurnUsage | null {
  const message = asRecord(payload.message)
  let nextUsage = pendingUsage
  // Turn usage 挂起：该 Turn 最后一个 ASSISTANT 的 metadata 在 TURN_END 发射。
  if (entry.entryType === 'MESSAGE' && getString(message.role) === 'ASSISTANT') {
    nextUsage = accumulateUsage(pendingUsage, payload, entry)
  }
  for (const content of getRecordList(message.contents)) {
    // 只有物化的 durable tool_result 才让活跃 tool overlay 退休：durable
    // assistant tool_call 只表示调用已记录，运行中的 invocation 必须继续展示。
    if (getString(content.type) === 'tool_result') {
      const toolCallId = getString(content.toolCallId)
      if (toolCallId) {
        scan.durableToolResultIdentities.add(`${turnStartEntryId ?? ''}:${toolCallId}`)
      }
    }
  }
  return nextUsage
}

/** 任一 entryId 所属 Turn 的 entryId 与 1-based 序号；未知 entry 为 (null, 0)。 */
function createTurnResolver(
  scan: DurableScan,
): (entryId: string | null) => { turnStartEntryId: string | null; turnNumber: number } {
  return (entryId) => {
    const turnStartEntryId = entryId == null ? null : (scan.turnStartByEntryId.get(entryId) ?? null)
    return {
      turnStartEntryId,
      turnNumber: turnStartEntryId == null
        ? 0
        : (scan.turnNumberByTurnStart.get(turnStartEntryId) ?? 0),
    }
  }
}

/** 活跃 overlay 的落点：所属 durable Entry 之后，找不到锚点时追加末尾。 */
interface OverlayAnchor {
  anchor(entryId: string | null, record: ThreadEventRecord): void
  anchored: Map<number, ThreadEventRecord[]>
  unanchored: ThreadEventRecord[]
}

function createOverlayAnchor(entries: readonly HarnessSessionEntryDTO[]): OverlayAnchor {
  const anchored = new Map<number, ThreadEventRecord[]>()
  const unanchored: ThreadEventRecord[] = []
  return {
    anchored,
    unanchored,
    anchor(entryId, record) {
      const index = entryId == null ? -1 : entries.findIndex((entry) => entry.entryId === entryId)
      if (index < 0) {
        unanchored.push(record)
        return
      }
      const group = anchored.get(index) ?? []
      group.push(record)
      anchored.set(index, group)
    },
  }
}

/** 活跃 overlay 投影：先失败记录，再 model generation，最后 tool invocation。 */
function projectActiveOverlays({
  anchor,
  scan,
  entries,
  activeAssistantOutput,
  toolInvocations,
  generationFailures,
  modelStream,
  toolStreams,
  turnOf,
}: {
  anchor: OverlayAnchor
  scan: DurableScan
  entries: readonly HarnessSessionEntryDTO[]
  activeAssistantOutput: HarnessActiveAssistantOutputDTO | null
  toolInvocations: ToolInvocationDTO[]
  generationFailures: readonly HarnessGenerationFailureDTO[]
  modelStream: RealtimeModelStream | null
  toolStreams: ReadonlyMap<string, RealtimeToolStream> | null
  turnOf: ReturnType<typeof createTurnResolver>
}): void {
  projectFailureOverlays(anchor, scan, generationFailures, turnOf)
  projectModelOverlay(anchor, entries, activeAssistantOutput, modelStream, turnOf)
  projectToolOverlays(anchor, scan, toolInvocations, toolStreams, turnOf)
}

function projectFailureOverlays(
  anchor: OverlayAnchor,
  scan: DurableScan,
  generationFailures: readonly HarnessGenerationFailureDTO[],
  turnOf: ReturnType<typeof createTurnResolver>,
): void {
  const orderedFailures = [...generationFailures].sort(
    (left, right) => left.attempt - right.attempt || left.failedAt - right.failedAt,
  )
  const seenFailures = new Set<string>()
  for (const failure of orderedFailures) {
    // 同 identity（generationId:attempt）只保留一条活跃失败记录。
    const identity = `${failure.generationId}:${failure.attempt}`
    if (seenFailures.has(identity)) {
      continue
    }
    seenFailures.add(identity)
    // durable MODEL_ATTEMPT_FAILURE Entry 已在同一 Turn 物化该失败时，跳过活跃 overlay。
    if (
      scan.durableFailureIdentities.has(
        `${failure.turnStartEntryId}:${failure.attempt}:${failure.sequence}`,
      )
    ) {
      continue
    }
    anchor.anchor(
      failure.turnStartEntryId,
      projectAttemptFailureRecord(failure, turnOf(failure.turnStartEntryId)),
    )
  }
}

function projectModelOverlay(
  anchor: OverlayAnchor,
  entries: readonly HarnessSessionEntryDTO[],
  activeAssistantOutput: HarnessActiveAssistantOutputDTO | null,
  modelStream: RealtimeModelStream | null,
  turnOf: ReturnType<typeof createTurnResolver>,
): void {
  // 活跃 model generation synthetic；resultEntryId 已对应现存 Entry 时消失。
  const modelMaterialized =
    activeAssistantOutput != null
    && activeAssistantOutput.resultEntryId != null
    && entries.some((entry) => entry.entryId === activeAssistantOutput.resultEntryId)
  if (activeAssistantOutput == null || modelMaterialized) {
    return
  }
  anchor.anchor(
    activeAssistantOutput.turnStartEntryId,
    projectActiveModelRecord(
      activeAssistantOutput,
      modelStream,
      turnOf(activeAssistantOutput.turnStartEntryId),
    ),
  )
}

function projectToolOverlays(
  anchor: OverlayAnchor,
  scan: DurableScan,
  toolInvocations: ToolInvocationDTO[],
  toolStreams: ReadonlyMap<string, RealtimeToolStream> | null,
  turnOf: ReturnType<typeof createTurnResolver>,
): void {
  for (const invocation of toolInvocations) {
    // 运行中的 invocation 在 durable assistant tool_call 物化后仍必须展示：只有
    // 同一 Turn 的 durable tool_result Entry 已物化时才跳过 terminal-pending
    // overlay；跨 Turn 复用相同 toolCallId 不误抑制。ToolResult Entry 写入与
    // invocation 删除原子提交，因此只需依据 durable Entry 与 invocation 存在性收敛。
    const materialized = scan.durableToolResultIdentities.has(
      `${scan.turnStartByEntryId.get(invocation.assistantEntryId) ?? ''}:${invocation.toolCallId}`,
    )
    if (materialized) {
      continue
    }
    anchor.anchor(
      invocation.assistantEntryId,
      projectActiveToolRecord(
        invocation,
        toolStreams?.get(invocation.id) ?? null,
        turnOf(invocation.assistantEntryId),
      ),
    )
  }
}

/** durable 记录顺序不变，各自锚定的活跃 overlay 紧随其后，未锚定的追加末尾。 */
function mergeAnchoredRecords(
  records: readonly ThreadEventRecord[],
  anchor: OverlayAnchor,
): ThreadEventRecord[] {
  const result: ThreadEventRecord[] = []
  for (let index = 0; index < records.length; index += 1) {
    result.push(records[index]!)
    const group = anchor.anchored.get(index)
    if (group) {
      result.push(...group)
    }
  }
  result.push(...anchor.unanchored)
  return result
}

/**
 * durable Entry 的 raw 事实：summary 为压缩后的 payload JSON，rawJson 为 pretty JSON。
 * 语义标题/kind 由各分支决定，绝不在此用 entryType 覆盖。
 */
function withRawPayload(
  record: ThreadEventRecord,
  entry: HarnessSessionEntryDTO,
): ThreadEventRecord {
  return {
    ...record,
    summary: compactJson(entry.payload),
    rawJson: prettyJson(entry.payload),
  }
}
