import { describe, expect, it } from 'vitest'
import {
  compactionCardTitleKey,
  compactionFailureReasonKey,
  latestRetainedAnchor,
  projectCompactionSeparator,
} from '@/features/thread/runtime/thread-timeline/compaction-turn'
import type { EntryType } from '@/shared/api/contracts/ai-runtime'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

function entry(
  entryId: string,
  entryType: EntryType,
  payload: Record<string, unknown>,
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 'session-1',
    parentEntryId: null,
    entryType,
    payload,
    createdAt: 1767225600000,
    usageCost: null,
  }
}

function turnStart(startEntryId: string, cutEntryId: string): HarnessSessionEntryDTO {
  return entry(startEntryId, 'TURN_START', {
    reason: 'COMPACTION',
    compaction: {
      trigger: 'MANUAL',
      execution: null,
      outputBudget: 1024,
      cutEntryId,
      childThreadId: `${startEntryId}-child`,
      joinInvocationId: `${startEntryId}-join`,
    },
  })
}

function turnEnd(startEntryId: string, outcome: string, continueModel = false): HarnessSessionEntryDTO {
  return entry(`${startEntryId}-end`, 'TURN_END', {
    turnStartEntryId: startEntryId,
    outcome,
    continueModel,
  })
}

describe('compaction card keys', () => {
  it('maps every lifecycle state to its own title key', () => {
    expect(compactionCardTitleKey('running')).toBe('ai.runtime.entry.compactionRunning')
    expect(compactionCardTitleKey('compacted')).toBe('ai.runtime.entry.compactionTitle')
    expect(compactionCardTitleKey('failed')).toBe('ai.runtime.entry.compactionFailed')
    expect(compactionCardTitleKey('stopped')).toBe('ai.runtime.entry.compactionStopped')
  })

  it('maps every durable error code to a short reason and falls back for unknown ones', () => {
    expect(compactionFailureReasonKey('COMPACTION_FAILED'))
      .toBe('ai.runtime.entry.compactionReason.failed')
    expect(compactionFailureReasonKey('COMPACTION_OUTPUT_TRUNCATED'))
      .toBe('ai.runtime.entry.compactionReason.truncated')
    expect(compactionFailureReasonKey('COMPACTION_CONTENT_FILTERED'))
      .toBe('ai.runtime.entry.compactionReason.filtered')
    expect(compactionFailureReasonKey('COMPACTION_INVALID_RESPONSE'))
      .toBe('ai.runtime.entry.compactionReason.invalidResponse')
    expect(compactionFailureReasonKey('COMPACTION_EMPTY_SUMMARY'))
      .toBe('ai.runtime.entry.compactionReason.emptySummary')
    expect(compactionFailureReasonKey('COMPACTION_BUDGET_EXCEEDED'))
      .toBe('ai.runtime.entry.compactionReason.budgetExceeded')
    expect(compactionFailureReasonKey('CONTEXT_LIMIT'))
      .toBe('ai.runtime.entry.compactionReason.contextLimit')
    expect(compactionFailureReasonKey('UNKNOWN_CODE'))
      .toBe('ai.runtime.entry.compactionReason.failed')
    expect(compactionFailureReasonKey(null))
      .toBe('ai.runtime.entry.compactionReason.failed')
  })
})

describe('latest retained anchor', () => {
  it('uses the last completed compaction and its first entry after the cut', () => {
    const entries = [
      entry('cut-1', 'TURN_END', {}),
      entry('retained-1', 'MESSAGE', {}),
      turnStart('c1', 'cut-1'),
      entry('summary-1', 'COMPACTION', { summaryText: 'first' }),
      turnEnd('c1', 'COMPLETED', true),
      entry('cut-2', 'TURN_END', {}),
      entry('retained-2', 'MESSAGE', {}),
      turnStart('c2', 'cut-2'),
      entry('summary-2', 'COMPACTION', { summaryText: 'second' }),
      turnEnd('c2', 'COMPLETED', true),
    ]

    expect(latestRetainedAnchor(entries)).toEqual({
      cutEntryId: 'cut-2',
      beforeEntryId: 'retained-2',
    })
  })

  it('ignores failed, stopped and still-open compactions', () => {
    const base = [
      entry('cut', 'TURN_END', {}),
      entry('retained', 'MESSAGE', {}),
    ]
    expect(latestRetainedAnchor(base)).toBeNull()
    expect(latestRetainedAnchor([
      ...base,
      turnStart('c1', 'cut'),
      entry('summary', 'COMPACTION', { summaryText: 'x' }),
      turnEnd('c1', 'FAILED'),
    ])).toBeNull()
    expect(latestRetainedAnchor([
      ...base,
      turnStart('c1', 'cut'),
      entry('summary', 'COMPACTION', { summaryText: 'x' }),
      turnEnd('c1', 'STOPPED'),
    ])).toBeNull()
    expect(latestRetainedAnchor([...base, turnStart('c1', 'cut')])).toBeNull()
  })

  it('returns null when the cut entry is not on the loaded path', () => {
    expect(latestRetainedAnchor([
      turnStart('c1', 'missing-cut'),
      entry('summary', 'COMPACTION', { summaryText: 'x' }),
      turnEnd('c1', 'COMPLETED', true),
    ])).toBeNull()
  })
})

describe('compaction separator', () => {
  it('is a stable entry whose text states the summarized boundary', () => {
    const separator = projectCompactionSeparator('cut-1', 1767225600000)
    expect(separator).toMatchObject({
      id: 'compaction-separator:cut-1',
      role: 'entry',
      kind: 'compaction_separator',
      text: '以上内容已压缩为摘要',
      subjectEntryId: null,
      createdAt: 1767225600000,
    })
  })
})
