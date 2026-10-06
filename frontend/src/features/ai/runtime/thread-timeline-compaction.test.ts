import { describe, expect, it } from 'vitest'
import {
  buildThreadTimeline,
} from '@/features/ai/runtime/thread-timeline-builder'
import type { EntryEventDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import type {
  EntryType,
  HarnessSessionEntryDTO,
} from '@/shared/api/contracts/ai-runtime'
import { FULL_COMPACTION_SUMMARY } from '@/test-support/resources/compaction-summary'

/**
 * 完整成功压缩的健康路径：
 * - 只以 COMPACTION.payload.summaryText 为源；
 * - TURN_START.reason=COMPACTION 且 compaction.phase=FULL/TURN_PREFIX；
 * - 匹配 TURN_END.turnStartEntryId 且 outcome=COMPLETED。
 * HISTORY、失败/取消/未关闭与不匹配形状都不产生卡片。
 */
describe('compaction summary projection', () => {
  it('projects one durable card for a FULL compaction closed as COMPLETED', () => {
    const timeline = buildThreadTimeline(
      [
        userEntry('before', 'entry-user-1'),
        ...compactionTurn('cs1', 'FULL', [compactionEntry('comp1', FULL_COMPACTION_SUMMARY)]),
        userEntry('after', 'entry-user-2'),
      ],
      [],
      [],
    )

    const cards = compactionCards(timeline.messages)
    expect(cards).toHaveLength(1)
    expect(cards[0]).toMatchObject({
      id: 'entry:comp1',
      role: 'entry',
      kind: 'compaction',
      title: '上下文已压缩',
      // 逐字保留完整摘要（含 Runtime 追加的保留标签与逐行路径）。
      text: FULL_COMPACTION_SUMMARY,
      subjectEntryId: 'comp1',
      status: 'done',
    })
    expect(cards[0].createdAt).toBe('2026-01-01T00:00:03')

    // 卡片留在原压缩回合的位置：此前对话之后、后续对话之前。
    expect(timeline.messages.map((message) =>
      message.role === 'entry' ? `entry:${message.kind}` : message.role)).toEqual([
      'user',
      'entry:compaction',
      'user',
    ])
  })

  it('projects TURN_PREFIX compaction that completed', () => {
    const timeline = buildThreadTimeline(
      [...compactionTurn('cs1', 'TURN_PREFIX', [compactionEntry('comp1', 'split summary')])],
      [],
      [],
    )

    expect(compactionCards(timeline.messages)).toMatchObject([
      { kind: 'compaction', text: 'split summary', subjectEntryId: 'comp1' },
    ])
  })

  it('never displays a HISTORY intermediate summary as success', () => {
    // HISTORY 只是中间 partial；即使 TURN_END COMPLETED 也不能冒充完整成功。
    const timeline = buildThreadTimeline(
      [...compactionTurn('cs1', 'HISTORY', [compactionEntry('comp1', 'partial summary')])],
      [],
      [],
    )

    expect(compactionCards(timeline.messages)).toEqual([])
  })

  it.each(['FAILED', 'STOPPED', 'CANCELLED'])(
    'hides a successful-shaped payload closed as %s',
    (outcome) => {
      const timeline = buildThreadTimeline(
        [
          ...compactionTurn('cs1', 'FULL', [compactionEntry('comp1', 'summary')], outcome),
        ],
        [],
        [],
      )

      expect(compactionCards(timeline.messages)).toEqual([])
    },
  )

  it('hides a compaction turn that never closed', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart('cs1', 'COMPACTION', 'FULL'),
        compactionEntry('comp1', 'unclosed summary'),
      ],
      [],
      [],
    )

    expect(compactionCards(timeline.messages)).toEqual([])
  })

  it('hides the payload when the TURN_END references another TURN_START', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart('cs1', 'COMPACTION', 'FULL'),
        compactionEntry('comp1', 'mismatched summary'),
        turnEnd('cs-other', 'COMPLETED'),
      ],
      [],
      [],
    )

    expect(compactionCards(timeline.messages)).toEqual([])
  })

  it('does not guess a phase when TURN_START lacks compaction metadata', () => {
    // 不静默把缺失/未知 phase 当作 FULL；只有真实 schema 的 FULL/TURN_PREFIX 才成功。
    const timeline = buildThreadTimeline(
      [
        entry('cs1', 'TURN_START', { reason: 'COMPACTION', settings: {} }),
        compactionEntry('comp1', 'summary'),
        turnEnd('cs1', 'COMPLETED'),
      ],
      [],
      [],
    )

    expect(compactionCards(timeline.messages)).toEqual([])
  })

  it('hides empty or non-string summaryText', () => {
    // CompactionPayload 保证非空 summaryText；空/未知形状不能伪装成成功。
    const timeline = buildThreadTimeline(
      [
        turnStart('cs1', 'COMPACTION', 'FULL'),
        entry('empty', 'COMPACTION', { summaryText: '   ' }),
        entry('wrong-shape', 'COMPACTION', { summaryText: 42 }),
        entry('missing', 'COMPACTION', {}),
        turnEnd('cs1', 'COMPLETED'),
      ],
      [],
      [],
    )

    expect(compactionCards(timeline.messages)).toEqual([])
  })

  it('keeps each complete success as its own card in scan order', () => {
    const timeline = buildThreadTimeline(
      [
        ...compactionTurn('cs1', 'FULL', [compactionEntry('comp1', 'first')]),
        userEntry('between', 'entry-user-1'),
        ...compactionTurn('cs2', 'FULL', [compactionEntry('comp2', 'second')]),
      ],
      [],
      [],
    )

    expect(timeline.messages.map((message) =>
      message.role === 'entry' ? `${message.kind}:${message.text}` : message.role)).toEqual([
      'compaction:first',
      'user',
      'compaction:second',
    ])
    // 卡片以摘要 Entry 的持久身份区分，刷新前后 key 稳定。
    expect(compactionCards(timeline.messages).map((message) => message.id))
      .toEqual(['entry:comp1', 'entry:comp2'])
  })

  it('keeps internal compaction streams, thinking and usage hidden', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart('cs1', 'COMPACTION', 'FULL'),
        entry('assistant', 'MESSAGE', {
          message: {
            role: 'ASSISTANT',
            contents: [
              { type: 'text', text: 'internal stream' },
              { type: 'thinking', text: 'internal thinking' },
            ],
          },
          assistantMetadata: {
            usage: { inputTokens: 10, outputTokens: 5, totalTokens: 15, costTotal: 0.01 },
          },
        }),
        compactionEntry('comp1', 'final summary'),
        turnEnd('cs1', 'COMPLETED'),
      ],
      [],
      [],
    )

    expect(timeline.messages).toMatchObject([
      { role: 'entry', kind: 'compaction', text: 'final summary' },
    ])
    expect(timeline.messages.some((message) => message.role === 'meta')).toBe(false)
    expect(timeline.messages.some((message) => message.role === 'assistant')).toBe(false)
  })
})

function compactionCards(messages: ReturnType<typeof buildThreadTimeline>['messages']) {
  return messages.filter(
    (message): message is EntryEventDialogueMessage =>
      message.role === 'entry' && message.kind === 'compaction',
  )
}

function compactionTurn(
  startEntryId: string,
  phase: string,
  body: HarnessSessionEntryDTO[],
  outcome = 'COMPLETED',
): HarnessSessionEntryDTO[] {
  return [turnStart(startEntryId, 'COMPACTION', phase), ...body, turnEnd(startEntryId, outcome)]
}

function turnStart(startEntryId: string, reason: string, phase: string): HarnessSessionEntryDTO {
  return entry(startEntryId, 'TURN_START', {
    reason,
    settings: {
      agentName: 'coder',
      model: { providerName: 'p', modelName: 'm', variant: 'high' },
      environmentName: null,
    },
    ownerThreadId: 'thread-1',
    contextWindow: 200000,
    maxOutputTokens: 8192,
    compaction: {
      phase,
      trigger: 'THRESHOLD',
      executionModel: { providerName: 'p', modelName: 'm', variant: 'high' },
      cutEntryId: 'cut-1',
      turnPrefixStartEntryId: phase === 'FULL' ? null : 'prefix-1',
      historyCompactionEntryId: null,
    },
  })
}

function turnEnd(startEntryId: string, outcome: string): HarnessSessionEntryDTO {
  return entry('end', 'TURN_END', { turnStartEntryId: startEntryId, outcome, continueModel: false })
}

function compactionEntry(entryId: string, summaryText: string): HarnessSessionEntryDTO {
  return entry(entryId, 'COMPACTION', { summaryText })
}

function userEntry(text: string, entryId: string): HarnessSessionEntryDTO {
  return entry(entryId, 'MESSAGE', {
    message: { role: 'USER', contents: [{ type: 'text', text }] },
  })
}

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
    payloadJson: JSON.stringify(payload),
    createTime: `2026-01-01T00:00:0${entryId === 'assistant' ? '2' : '3'}`,
  }
}
