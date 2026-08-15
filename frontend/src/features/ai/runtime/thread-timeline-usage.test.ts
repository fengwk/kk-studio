import { describe, expect, it } from 'vitest'
import type { EntryType, HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'
import { buildThreadTimeline } from '@/features/ai/runtime/thread-timeline-builder'
import { aggregateBranchUsage } from '@/features/ai/runtime/thread-timeline/turn-usage'

/**
 * Turn usage 矩阵：usage 绝不紧跟 Assistant，必须在相应 TURN_END 之后投影为
 * TurnSummary；compaction / aborted / error / 未关闭 turn 不产生残留 summary。
 * TURN_END 本身不投影消息，因此「仅在 turn 关闭后出现且位于该 turn 全部消息之后」
 * 就是 TURN_END 后投影的可观察语义。
 */

function entry(
  entryId: string,
  entryType: EntryType,
  payload: Record<string, unknown>,
  createTime = '2026-07-28T10:00:00Z',
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 's1',
    parentEntryId: null,
    entryType,
    payloadJson: JSON.stringify(payload),
    createTime,
  }
}

function assistant(
  id: string,
  text: string,
  metadata?: Record<string, unknown>,
): HarnessSessionEntryDTO {
  const payload: Record<string, unknown> = {
    message: { role: 'ASSISTANT', contents: [{ type: 'text', text }] },
  }
  if (metadata) {
    payload.assistantMetadata = metadata
  }
  return entry(id, 'MESSAGE', payload)
}

function turnStart(id = 'turn-1', reason = 'USER_MESSAGE'): HarnessSessionEntryDTO {
  return entry(id, 'TURN_START', { reason })
}

function turnEnd(id = 'end-1', outcome = 'COMPLETED'): HarnessSessionEntryDTO {
  return entry(id, 'TURN_END', { outcome, continueModel: false })
}

function userMessage(text: string): HarnessSessionEntryDTO {
  return entry(
    `user-${text}`,
    'MESSAGE',
    { message: { role: 'USER', contents: [{ type: 'text', text }] } },
  )
}

function usageMetadata(
  input: number,
  output: number,
  cost = 0.001,
  extras: Record<string, unknown> = {},
) {
  return { usage: { inputTokens: input, outputTokens: output, ...extras }, cost }
}

describe('Turn usage after TURN_END', () => {
  it('projects the TurnSummary only after the turn is closed, as the turn last message', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart(),
        userMessage('问题'),
        assistant('assistant-1', '回答', usageMetadata(10, 20, 0.001, {
          reasoningTokens: 3,
          providerTotalTokens: 33,
        })),
        turnEnd(),
      ],
      [],
      [],
    )

    const ids = timeline.messages.map((message) => message.id)
    const usageIndex = ids.indexOf('meta-usage-entry-assistant-1')
    expect(usageIndex).toBeGreaterThan(0)
    // TURN_END 是控制边界（不投影消息）：summary 位于整个已关闭 turn 的最后。
    expect(usageIndex).toBe(ids.length - 1)
    const usage = timeline.messages[usageIndex]
    expect(usage).toMatchObject({
      role: 'meta',
      kind: 'turn_usage',
      subjectEntryId: 'assistant-1',
      status: 'done',
    })
    expect(usage?.text).toContain('↑10')
    expect(usage?.text).toContain('↓20')
    expect(usage).toMatchObject({
      turnUsage: {
        input: 10,
        output: 20,
        cacheRead: 0,
        cacheWrite: 0,
        reasoning: 3,
        providerTotal: 33,
        cost: 0.001,
      },
      details: {
        reasoning: 3,
        providerTotal: 33,
      },
    })
  })

  it('places the summary after tool results of the same turn', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart(),
        userMessage('run'),
        entry('assistant-1', 'MESSAGE', {
          message: {
            role: 'ASSISTANT',
            contents: [
              { type: 'text', text: '调用工具' },
              {
                type: 'tool_call',
                toolCallId: 'call-1',
                toolName: 'bash',
                rendererKey: 'bash',
                argumentsJson: '{"command":"ls"}',
              },
            ],
          },
          assistantMetadata: usageMetadata(10, 20),
        }),
        entry('tool-1', 'MESSAGE', {
          message: {
            role: 'TOOL',
            contents: [
              {
                type: 'tool_result',
                toolCallId: 'call-1',
                toolName: 'bash',
                rendererKey: 'bash',
                contents: [{ type: 'text', text: 'ok' }],
              },
            ],
          },
        }),
        turnEnd(),
      ],
      [],
      [],
    )

    const ids = timeline.messages.map((message) => message.id)
    const usageIndex = ids.indexOf('meta-usage-entry-assistant-1')
    expect(usageIndex).toBe(ids.length - 1)
    expect(ids[usageIndex - 1]).toBe('tool-1:tool-result:call-1:0')
  })

  it('keeps each turn usage attached to its own TURN_END across multiple turns', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart('turn-1'),
        userMessage('a'),
        assistant('assistant-1', 'first', usageMetadata(10, 20)),
        turnEnd('end-1'),
        turnStart('turn-2'),
        userMessage('b'),
        assistant('assistant-2', 'second', usageMetadata(30, 40)),
        turnEnd('end-2'),
      ],
      [],
      [],
    )

    const ids = timeline.messages.map((message) => message.id)
    const firstUsage = ids.indexOf('meta-usage-entry-assistant-1')
    const secondUsage = ids.indexOf('meta-usage-entry-assistant-2')
    expect(firstUsage).toBeGreaterThanOrEqual(0)
    expect(secondUsage).toBeGreaterThan(firstUsage)
    // 第二个 summary 是整个 timeline 的最后一条消息。
    expect(secondUsage).toBe(ids.length - 1)
    expect(ids[firstUsage + 1]).toBe('user-b')
  })

  it('does not project usage for aborted or error turns without assistant metadata', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart(),
        userMessage('a'),
        entry('aborted-1', 'ASSISTANT_ABORTED', {
          message: { role: 'ASSISTANT', contents: [{ type: 'text', text: 'partial' }] },
        }),
        turnEnd('STOPPED'),
        entry('turn-2', 'TURN_START', { reason: 'USER_MESSAGE' }),
        userMessage('b'),
        entry('error-1', 'ASSISTANT_ERROR', { error: { message: 'boom' } }),
        entry('end-2', 'TURN_END', { outcome: 'FAILED' }),
      ],
      [],
      [],
    )

    expect(timeline.messages.some((message) => message.role === 'meta')).toBe(false)
  })

  it('does not leak usage from an unclosed turn into the next TURN_END', () => {
    // 上一 turn 的 assistant 已带 usage，但该 turn 没有 TURN_END（快照截断）：
    // 新 TURN_START 必须丢弃残留，下一个 TURN_END 不再投影旧 usage。
    const timeline = buildThreadTimeline(
      [
        turnStart('turn-1'),
        userMessage('a'),
        assistant('assistant-1', '回答', usageMetadata(10, 20)),
        turnStart('turn-2'),
        userMessage('b'),
        assistant('assistant-2', '回答 2', usageMetadata(5, 6)),
        turnEnd('end-2'),
      ],
      [],
      [],
    )

    const ids = timeline.messages.map((message) => message.id)
    expect(ids.filter((id) => id.startsWith('meta-usage-entry-'))).toEqual([
      'meta-usage-entry-assistant-2', // 仅第二个（已关闭）turn 的 summary
    ])
  })

  it('does not project usage inside compaction turns', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart('turn-1', 'COMPACTION'),
        userMessage('summary'),
        assistant('assistant-1', 'compact answer', usageMetadata(100, 50)),
        turnEnd('end-1'),
        turnStart('turn-2'),
        userMessage('real'),
        assistant('assistant-2', 'real answer', usageMetadata(1, 2)),
        turnEnd('end-2'),
      ],
      [],
      [],
    )

    const ids = timeline.messages.map((message) => message.id)
    expect(ids.filter((id) => id.startsWith('meta-usage-entry-'))).toEqual([
      'meta-usage-entry-assistant-2', // 仅真实 turn 的 summary
    ])
    // 真实 turn 的 summary 位于整个 timeline 的最后（紧随其 TURN_END）。
    expect(ids.indexOf('meta-usage-entry-assistant-2')).toBe(ids.length - 1)
  })

  it('does not project a summary when usage/cost are all zero', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart(),
        userMessage('a'),
        assistant('assistant-1', '回答', usageMetadata(0, 0, 0)),
        turnEnd(),
      ],
      [],
      [],
    )
    expect(timeline.messages.some((message) => message.role === 'meta')).toBe(false)
  })

  it('aggregates only TURN_END summaries, excluding compaction and an incomplete turn', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart('compact', 'COMPACTION'),
        assistant('assistant-compact', 'summary', usageMetadata(100, 50, 1)),
        turnEnd('compact-end'),
        turnStart('turn-1'),
        assistant('assistant-1', 'done', usageMetadata(10, 20, 0.125, {
          cacheReadTokens: 5,
          reasoningTokens: 2,
          providerTotalTokens: 37,
        })),
        turnEnd('end-1'),
        turnStart('turn-2'),
        assistant('assistant-2', 'not closed', usageMetadata(30, 40, 0.5)),
      ],
      [],
      [],
    )

    expect(aggregateBranchUsage(timeline.messages)).toEqual({
      input: 10,
      output: 20,
      cacheRead: 5,
      cacheWrite: 0,
      reasoning: 2,
      providerTotal: 37,
      cost: 0.125,
    })
  })
})
