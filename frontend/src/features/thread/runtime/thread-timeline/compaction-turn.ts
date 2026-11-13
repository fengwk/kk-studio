import { asRecord } from '@/features/thread/runtime/payload-json'
import type {
  CompactionCardFact,
  CompactionCardState,
  EntryEventDialogueMessage,
} from '@/features/thread/runtime/thread-timeline-types'
import { getString } from '@/shared/lib/value'
import { translate, type TranslationKey } from '@/shared/i18n'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

/**
 * 压缩回合的时间线投影：卡片随 TURN_START 立即出现，回合内的错误与摘要补充事实，
 * TURN_END 决定最终状态。卡片只在真实 Entry 事实到达时改变，不做任何推测。
 */
export interface CompactionTurnProjection {
  startEntryId: string
  summaryEntryId: string | null
  summaryText: string
  errorCode: string | null
  cutEntryId: string | null
  childThreadId: string | null
  message: EntryEventDialogueMessage
}

const COMPACTION_TITLE_KEYS: Record<CompactionCardState, TranslationKey> = {
  running: 'ai.runtime.entry.compactionRunning',
  compacted: 'ai.runtime.entry.compactionTitle',
  failed: 'ai.runtime.entry.compactionFailed',
  stopped: 'ai.runtime.entry.compactionStopped',
}

const COMPACTION_FAILURE_KEYS: Record<string, TranslationKey> = {
  COMPACTION_FAILED: 'ai.runtime.entry.compactionReason.failed',
  COMPACTION_OUTPUT_TRUNCATED: 'ai.runtime.entry.compactionReason.truncated',
  COMPACTION_CONTENT_FILTERED: 'ai.runtime.entry.compactionReason.filtered',
  COMPACTION_INVALID_RESPONSE: 'ai.runtime.entry.compactionReason.invalidResponse',
  COMPACTION_EMPTY_SUMMARY: 'ai.runtime.entry.compactionReason.emptySummary',
  COMPACTION_BUDGET_EXCEEDED: 'ai.runtime.entry.compactionReason.budgetExceeded',
  CONTEXT_LIMIT: 'ai.runtime.entry.compactionReason.contextLimit',
}

/** 压缩卡标题：状态是唯一输入，文案与 locale 无关地稳定映射。 */
export function compactionCardTitleKey(state: CompactionCardState): TranslationKey {
  return COMPACTION_TITLE_KEYS[state]
}

/**
 * 失败原因的用户文案：只按稳定错误码映射成短句，未知码回落为通用失败，
 * 不把 Provider 原始错误正文带进卡片标题。
 */
export function compactionFailureReasonKey(errorCode: string | null): TranslationKey {
  if (errorCode == null) {
    return COMPACTION_FAILURE_KEYS.COMPACTION_FAILED
  }
  return COMPACTION_FAILURE_KEYS[errorCode] ?? COMPACTION_FAILURE_KEYS.COMPACTION_FAILED
}

/** 打开中的压缩回合：卡片立即出现在回合开始的位置，状态为进行中。 */
export function beginCompactionTurn(entry: HarnessSessionEntryDTO): CompactionTurnProjection {
  const compaction = asRecord(asRecord(entry.payload).compaction)
  const fact: CompactionCardFact = {
    state: 'running',
    childThreadId: getString(compaction.childThreadId) || null,
    cutEntryId: getString(compaction.cutEntryId) || null,
    errorCode: null,
  }
  return {
    startEntryId: entry.entryId,
    summaryEntryId: null,
    summaryText: '',
    errorCode: null,
    cutEntryId: fact.cutEntryId,
    childThreadId: fact.childThreadId,
    message: {
      id: `entry:${entry.entryId}`,
      role: 'entry',
      kind: 'compaction',
      title: translate(compactionCardTitleKey('running')),
      text: '',
      subjectEntryId: entry.entryId,
      createdAt: entry.createdAt,
      status: 'done',
      compaction: fact,
    },
  }
}

/** 回合内的失败审计与摘要结果；其它 Entry 由 builder 屏蔽，不产生独立对话消息。 */
export function recordCompactionTurnEntry(
  turn: CompactionTurnProjection,
  entry: HarnessSessionEntryDTO,
): void {
  if (entry.entryType === 'ASSISTANT_ERROR') {
    const code = getString(asRecord(asRecord(entry.payload).error).code)
    if (code !== '') {
      turn.errorCode = code
    }
    return
  }
  if (entry.entryType !== 'COMPACTION') {
    return
  }
  const summaryText = getString(asRecord(entry.payload).summaryText)
  if (summaryText.trim() !== '') {
    turn.summaryEntryId = entry.entryId
    turn.summaryText = summaryText
  }
}

/**
 * 关闭压缩回合：COMPLETED 且继续 owner model 并带摘要才是已压缩；
 * STOPPED 是用户停止；其余关闭都按失败呈现，保证卡片不会停留在进行中。
 *
 * 卡片对象已经在 TURN_START 时进入 messages，因此这里原地更新而不是替换引用。
 */
export function closeCompactionTurn(
  turn: CompactionTurnProjection,
  turnEndPayload: Record<string, unknown>,
): void {
  const outcome = getString(turnEndPayload.outcome)
  const continueModel = turnEndPayload.continueModel === true
  let state: CompactionCardState
  if (outcome === 'STOPPED') {
    state = 'stopped'
  } else if (outcome === 'COMPLETED' && continueModel && turn.summaryEntryId != null) {
    state = 'compacted'
  } else {
    state = 'failed'
  }
  const fact: CompactionCardFact = {
    state,
    childThreadId: turn.childThreadId,
    cutEntryId: turn.cutEntryId,
    errorCode: state === 'failed' ? turn.errorCode : null,
  }
  turn.message.title = translate(compactionCardTitleKey(state))
  turn.message.text = state === 'compacted' ? turn.summaryText : ''
  turn.message.compaction = fact
}

export interface CompactionRetainedAnchor {
  cutEntryId: string
  beforeEntryId: string
}

/**
 * 最新一次成功压缩的保留段起点：`cutEntryId` 之后路径上的第一条 Entry。
 * 只有 COMPLETED、continueModel=true 且带摘要的压缩会移动保留段；失败/停止不移动。
 */
export function latestRetainedAnchor(
  entries: readonly HarnessSessionEntryDTO[],
): CompactionRetainedAnchor | null {
  const positions = new Map(entries.map((entry, index) => [entry.entryId, index]))
  let open: { startEntryId: string; cutEntryId: string } | null = null
  let anchor: CompactionRetainedAnchor | null = null
  for (const entry of entries) {
    if (entry.entryType === 'TURN_START') {
      const payload = asRecord(entry.payload)
      const cutEntryId = getString(asRecord(payload.compaction).cutEntryId)
      open = getString(payload.reason) === 'COMPACTION' && cutEntryId !== ''
        ? { startEntryId: entry.entryId, cutEntryId }
        : null
      continue
    }
    if (entry.entryType !== 'TURN_END' || open == null) {
      continue
    }
    const payload = asRecord(entry.payload)
    if (getString(payload.turnStartEntryId) !== open.startEntryId) {
      continue
    }
    if (getString(payload.outcome) === 'COMPLETED' && payload.continueModel === true) {
      const cutIndex = positions.get(open.cutEntryId)
      const next = cutIndex != null ? entries[cutIndex + 1] : undefined
      if (next != null) {
        anchor = { cutEntryId: open.cutEntryId, beforeEntryId: next.entryId }
      }
    }
    open = null
  }
  return anchor
}

/** 保留段分隔线：只陈述“上面的内容已经被压缩成摘要”，不伪造回合或 Entry 身份。 */
export function projectCompactionSeparator(
  cutEntryId: string,
  createdAt: number,
): EntryEventDialogueMessage {
  const label = translate('ai.runtime.entry.compactionSeparator')
  return {
    id: `compaction-separator:${cutEntryId}`,
    role: 'entry',
    kind: 'compaction_separator',
    title: label,
    text: label,
    subjectEntryId: null,
    createdAt,
    status: 'done',
  }
}
