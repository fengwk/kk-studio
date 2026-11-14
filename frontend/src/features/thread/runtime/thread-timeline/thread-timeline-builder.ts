import { asRecord } from '@/features/thread/runtime/thread-timeline/payload-json'
import { getString } from '@/shared/lib/value'
import type {
  RealtimeModelStream,
} from '@/features/thread/runtime/thread-realtime/model-stream'
import type {
  RealtimeToolStream,
} from '@/features/thread/runtime/thread-realtime/tool-stream'
import type {
  DialogueMessage,
  QueuedThreadMessage,
  ThreadTimeline,
} from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import {
  projectDurableEntry,
  type EntryProjectionContext,
} from '@/features/thread/runtime/thread-timeline/entry-projection'
import {
  beginCompactionTurn,
  closeCompactionTurn,
  latestRetainedAnchor,
  projectCompactionSeparator,
  recordCompactionTurnEntry,
  type CompactionTurnProjection,
} from '@/features/thread/runtime/thread-timeline/compaction-turn'
import { projectInvocationOverlays } from '@/features/thread/runtime/thread-timeline/invocation-overlay-projection'
import { projectGenerationFailures } from '@/features/thread/runtime/thread-timeline/failure-projection'
import {
  isProjectableGenerationFailure,
  projectModelStream,
} from '@/features/thread/runtime/thread-timeline/model-stream-projection'
import { extractQueuedMessage } from '@/features/thread/runtime/thread-timeline/queued-message-projection'
import type {
  HarnessActiveAssistantOutputDTO,
  HarnessGenerationFailureDTO,
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
  ToolInvocationDTO,
} from '@/shared/api/generated/contracts'

/**
 * Thread transcript 投影：
 * 持久路径的 Entries 是 transcript 的唯一权威来源；
 * 仅 QUEUED USER_MESSAGE 命令会作为可编辑的装饰性 overlay 渲染；NOTIFICATION 等系统事实
 * 与 CUSTOM_MESSAGE 编排产物只出现在持久 transcript 中，不进入可编辑队列、草稿与上下键历史；
 * 活动 generation 的 typed activeAssistantOutput 以及 ToolInvocation 的 partial，作为
 * 瞬态 overlay 渲染，直到对应的持久 Entry 到达为止。
 */
export function buildThreadTimeline(
  entries: HarnessSessionEntryDTO[],
  queuedCommands: HarnessThreadCommandDTO[],
  toolInvocations: ToolInvocationDTO[],
  modelStream: RealtimeModelStream | null = null,
  toolStreams: ReadonlyMap<string, RealtimeToolStream> | null = null,
  generationFailures: readonly HarnessGenerationFailureDTO[] = [],
  activeAssistantOutput: HarnessActiveAssistantOutputDTO | null = null,
): ThreadTimeline {
  const messages: DialogueMessage[] = []
  // Turn usage 挂起上下文：summary 在相应 TURN_END 之后投影。
  const projectionContext: EntryProjectionContext = { pendingTurnSummary: null, lastSettings: null }
  const durableToolArguments = new Map<string, string[]>()
  const summary = projectDurableEntries(entries, messages, durableToolArguments, projectionContext)
  const queuedMessages = projectQueuedMessages(queuedCommands)

  projectInvocationOverlays(
    messages,
    toolInvocations,
    toolStreams,
    toolInvocations[0]?.threadId,
  )

  if (!summary.latestTurnIsCompaction) {
    projectGenerationFailures(messages, generationFailures, activeAssistantOutput)
    const streamAttemptFailed = isStreamAttemptFailed(modelStream, generationFailures)
    if (modelStream != null) {
      projectModelStream(messages, modelStream, streamAttemptFailed, activeAssistantOutput)
    }
  }

  return {
    messages,
    queuedMessages,
    hasPendingInputs: queuedMessages.length > 0,
  }
}

interface DurableProjectionSummary {
  /** 最新 Turn 是否压缩回合：压缩回合内的失败/流式 overlay 不进入对话视图。 */
  latestTurnIsCompaction: boolean
}

/**
 * 顺序投影 durable Entries：压缩回合的卡片在一开始出现、回合内事实在关闭时定稿，
 * 其余 Entry 走通用 durable 投影。
 */
function projectDurableEntries(
  entries: readonly HarnessSessionEntryDTO[],
  messages: DialogueMessage[],
  durableToolArguments: Map<string, string[]>,
  projectionContext: EntryProjectionContext,
): DurableProjectionSummary {
  let inCompactionTurn = false
  let latestTurnIsCompaction = false
  // 压缩回合的生命周期投影：卡片在 TURN_START 立即出现，回合内事实在关闭时定稿。
  let compactionTurn: CompactionTurnProjection | null = null
  // 最新成功压缩的保留段起点：分隔线只出现一次，且在首条保留 Entry 之前。
  const retainedAnchor = latestRetainedAnchor(entries)
  let separatorPending = retainedAnchor != null

  for (const entry of entries) {
    if (
      separatorPending
      && retainedAnchor != null
      && entry.entryId === retainedAnchor.beforeEntryId
    ) {
      messages.push(projectCompactionSeparator(retainedAnchor.cutEntryId, entry.createdAt))
      separatorPending = false
    }
    if (entry.entryType === 'TURN_START') {
      const payload = asRecord(entry.payload)
      latestTurnIsCompaction = getString(payload.reason) === 'COMPACTION'
      inCompactionTurn = latestTurnIsCompaction
      compactionTurn = latestTurnIsCompaction ? beginCompactionTurn(entry) : null
      if (compactionTurn != null) {
        messages.push(compactionTurn.message)
      }
      projectDurableEntry(entry, messages, durableToolArguments, projectionContext)
      continue
    }
    if (inCompactionTurn) {
      if (compactionTurn != null && entry.entryType === 'TURN_END') {
        const payload = asRecord(entry.payload)
        if (getString(payload.turnStartEntryId) === compactionTurn.startEntryId) {
          closeCompactionTurn(compactionTurn, payload)
          inCompactionTurn = false
          compactionTurn = null
        }
      } else if (compactionTurn != null) {
        recordCompactionTurnEntry(compactionTurn, entry)
      }
      continue
    }
    projectDurableEntry(entry, messages, durableToolArguments, projectionContext)
  }
  return { latestTurnIsCompaction }
}

/**
 * 可编辑队列只承载人类输入：NOTIFICATION（系统结果事实）、CUSTOM_MESSAGE（编排产物）
 * 与配置命令都不进入。
 */
function projectQueuedMessages(
  queuedCommands: readonly HarnessThreadCommandDTO[],
): QueuedThreadMessage[] {
  const queuedMessages: QueuedThreadMessage[] = []
  for (const command of queuedCommands) {
    if (command.state !== 'QUEUED' || command.type !== 'USER_MESSAGE') {
      continue
    }
    const queuedMessage = extractQueuedMessage(command.payload)
    if (!queuedMessage) {
      continue
    }
    queuedMessages.push({
      idempotencyKey: command.idempotencyKey,
      role: queuedMessage.role,
      text: queuedMessage.text,
      sequence: command.sequence,
    })
  }
  return queuedMessages
}

/** 当前实时 model stream 的 attempt 是否已被记录为失败（同一 generation/attempt）。 */
function isStreamAttemptFailed(
  modelStream: RealtimeModelStream | null,
  generationFailures: readonly HarnessGenerationFailureDTO[],
): boolean {
  return modelStream != null
    && generationFailures.some(
      (failure) =>
        isProjectableGenerationFailure(failure)
        && failure.generationId === modelStream.invocationId
        && failure.attempt === modelStream.attempt,
    )
}
