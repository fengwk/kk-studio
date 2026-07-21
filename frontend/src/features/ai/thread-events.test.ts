import { describe, expect, it } from 'vitest'
import type { HarnessSessionEntryDTO, HarnessThreadInputDTO, ThreadEventDTO } from '@/shared/api/contracts'
import { buildThreadTimeline, isThreadWorking } from '@/features/ai/thread-events'

describe('thread timeline', () => {
  it('projects durable entries as the transcript baseline', () => {
    const timeline = buildThreadTimeline(
      [
        entry('1', 'root', {}),
        entry('2', 'message', messagePayload('USER', [{ type: 'text', text: '检查大纲' }])),
        entry('3', 'message', messagePayload('ASSISTANT', [
          { type: 'thinking', text: '先梳理结构。' },
          { type: 'text', text: '结构完整。' },
        ])),
      ],
      [],
      [],
    )

    expect(timeline.messages).toMatchObject([
      { role: 'user', text: '检查大纲', status: 'done' },
      { role: 'assistant', text: '结构完整。', thinking: '先梳理结构。', status: 'done' },
    ])
    expect(timeline.queuedMessages).toEqual([])
    expect(timeline.hasPendingInputs).toBe(false)
  })

  it('keeps QUEUED USER_MESSAGE inputs out of the transcript', () => {
    const timeline = buildThreadTimeline(
      [entry('2', 'message', messagePayload('USER', [{ type: 'text', text: '第一句' }]))],
      [input('in-2', 'user_message', messagePayload('USER', [{ type: 'text', text: '排队中' }]), null)],
      [],
    )
    expect(timeline.messages).toMatchObject([{ role: 'user', text: '第一句' }])
    expect(timeline.queuedMessages).toMatchObject([
      { inputId: 'in-2', role: 'user', text: '排队中' },
    ])
    expect(timeline.hasPendingInputs).toBe(true)
  })

  it('preserves backend mailbox order instead of sorting inputs in the frontend', () => {
    const first = input('in-2', 'user_message', messagePayload('USER', [{ type: 'text', text: '后端第一条' }]), null)
    const second = input('in-1', 'user_message', messagePayload('USER', [{ type: 'text', text: '后端第二条' }]), null)
    first.sequence = 2
    second.sequence = 1

    const timeline = buildThreadTimeline([], [first, second], [])

    expect(timeline.messages).toEqual([])
    expect(timeline.queuedMessages.map((message) => message.text)).toEqual(['后端第一条', '后端第二条'])
  })

  it('projects an APPLIED USER input when its Entry query is stale', () => {
    const timeline = buildThreadTimeline(
      [],
      [input('in-1', 'user_message', messagePayload('USER', [{ type: 'text', text: '仍可见' }]), '11')],
      [],
    )
    expect(timeline.messages).toMatchObject([{ role: 'user', text: '仍可见', id: 'input:in-1' }])
    expect(timeline.queuedMessages).toEqual([])
    expect(timeline.hasPendingInputs).toBe(false)
  })

  it('places a stale applied USER overlay at its INPUT_APPLIED journal position', () => {
    const timeline = buildThreadTimeline(
      [],
      [input('in-1', 'user_message', messagePayload('USER', [{ type: 'text', text: '先提问' }]), null)],
      [
        threadEvent('1', 'input_applied', '11', { inputId: 'in-1', sequence: 1, type: 'user_message' }),
        threadEvent('2', 'assistant_started', '12', {}),
        threadEvent('3', 'assistant_delta_batch', '12', { deltas: [{ kind: 'text', text: '再回答' }] }),
      ],
    )

    expect(timeline.messages).toMatchObject([
      { role: 'user', text: '先提问' },
      { role: 'assistant', text: '再回答' },
    ])
    expect(timeline.queuedMessages).toEqual([])
  })

  it('suppresses pending USER when input DTO is stale-null but INPUT_APPLIED points to present Entry', () => {
    const timeline = buildThreadTimeline(
      [entry('11', 'message', messagePayload('USER', [{ type: 'text', text: '已落库' }]))],
      [input('in-1', 'user_message', messagePayload('USER', [{ type: 'text', text: '已落库' }]), null)],
      [
        threadEvent('1', 'input_applied', '11', { inputId: 'in-1', sequence: 1, type: 'user_message' }),
      ],
    )
    expect(timeline.messages.filter((message) => message.role === 'user')).toHaveLength(1)
    expect(timeline.messages).toMatchObject([{ role: 'user', text: '已落库', id: '11' }])
    expect(timeline.hasPendingInputs).toBe(false)
  })

  it('dedupes applied USER_MESSAGE inputs once Entry appears', () => {
    const timeline = buildThreadTimeline(
      [entry('11', 'message', messagePayload('USER', [{ type: 'text', text: '已落库' }]))],
      [input('in-1', 'user_message', messagePayload('USER', [{ type: 'text', text: '已落库' }]), '11')],
      [],
    )
    expect(timeline.messages.filter((message) => message.role === 'user')).toHaveLength(1)
    expect(timeline.messages).toMatchObject([{ role: 'user', text: '已落库', id: '11' }])
    expect(timeline.hasPendingInputs).toBe(false)
  })

  it('projects live assistant deltas by subjectEntryId including thinking', () => {
    const timeline = buildThreadTimeline(
      [],
      [],
      [
        threadEvent('1', 'assistant_started', '10', {}),
        threadEvent('2', 'assistant_delta_batch', '10', {
          deltas: [{ kind: 'thinking', text: 'draft ' }, { kind: 'text', text: '<think>hidden</think>final ' }],
        }),
        threadEvent('3', 'assistant_delta_batch', '10', { deltas: [{ kind: 'text', text: 'answer' }] }),
      ],
    )

    expect(timeline.messages).toMatchObject([
      { role: 'assistant', text: 'final answer', thinking: 'draft ', status: 'streaming' },
    ])
    expect(timeline.hasLiveProjection).toBe(true)
  })

  it('keeps hasLiveProjection only for open stream work', () => {
    const live = buildThreadTimeline(
      [],
      [],
      [threadEvent('1', 'assistant_started', '10', {})],
    )
    expect(live.hasLiveProjection).toBe(true)

    const completed = buildThreadTimeline(
      [],
      [],
      [
        threadEvent('1', 'assistant_started', '10', {}),
        threadEvent('2', 'assistant_delta_batch', '10', { deltas: [{ kind: 'text', text: 'done' }] }),
        threadEvent('3', 'assistant_completed', '10', {}),
      ],
    )
    expect(completed.hasLiveProjection).toBe(false)
    expect(completed.messages).toMatchObject([{ role: 'assistant', status: 'done' }])

    const toolStreaming = buildThreadTimeline(
      [],
      [],
      [
        threadEvent('1', 'tool_prepared', '10', {
          toolCallId: 'call-1',
          toolName: 'bash',
          arguments: 'ls',
          invocationId: 'inv-1',
        }),
      ],
    )
    expect(toolStreaming.hasLiveProjection).toBe(true)

    const toolDone = buildThreadTimeline(
      [],
      [],
      [
        threadEvent('1', 'tool_prepared', '10', {
          toolCallId: 'call-1',
          toolName: 'bash',
          arguments: 'ls',
          invocationId: 'inv-1',
        }),
        threadEvent('2', 'tool_completed', '10', { invocationId: 'inv-1' }),
      ],
    )
    expect(toolDone.hasLiveProjection).toBe(false)
  })

  it('suppresses transient assistant events after subject Entry materializes', () => {
    const events = [
      threadEvent('1', 'assistant_started', '30', {}),
      threadEvent('2', 'assistant_delta_batch', '30', { deltas: [{ kind: 'text', text: '最终回答' }] }),
      threadEvent('3', 'assistant_completed', '30', {}),
    ]
    const before = buildThreadTimeline([], [], events)
    expect(before.messages).toMatchObject([{ role: 'assistant', text: '最终回答', status: 'done' }])

    const after = buildThreadTimeline(
      [entry('30', 'message', messagePayload('ASSISTANT', [{ type: 'text', text: '最终回答' }]))],
      [],
      events,
    )
    expect(after.messages).toMatchObject([{ role: 'assistant', text: '最终回答', status: 'done' }])
    expect(after.messages).toHaveLength(1)
  })

  it('keeps streamed Assistant until durable Entry materializes', () => {
    const timeline = buildThreadTimeline(
      [],
      [],
      [
        threadEvent('1', 'assistant_started', '99', {}),
        threadEvent('2', 'assistant_delta_batch', '99', { deltas: [{ kind: 'text', text: '尚未落库' }] }),
        threadEvent('3', 'assistant_completed', '99', {}),
      ],
    )
    expect(timeline.messages).toMatchObject([{ role: 'assistant', text: '尚未落库', status: 'done' }])
  })

  it('removes interrupted stream output when its failure has an automatic retry plan', () => {
    const timeline = buildThreadTimeline(
      [],
      [],
      [
        threadEvent('1', 'assistant_started', '99', {}),
        threadEvent('2', 'assistant_delta_batch', '99', { deltas: [{ kind: 'text', text: '临时片段' }] }),
        threadEvent('3', 'assistant_failed', '99', { retryScheduled: true }),
        threadEvent('4', 'thread_retry_scheduled', null, { retryAttempt: 1 }),
      ],
    )

    expect(timeline.messages).toEqual([])
    expect(timeline.hasLiveProjection).toBe(false)
  })

  it('correlates tool events and stringifies json partial/final content', () => {
    const timeline = buildThreadTimeline(
      [],
      [],
      [
        threadEvent('1', 'tool_prepared', '40', {
          toolCallId: 'call-1',
          toolName: 'bash',
          arguments: 'ls',
          invocationId: 'inv-1',
        }),
        threadEvent('2', 'tool_delta_batch', '40', {
          invocationId: 'inv-1',
          partialResults: [{
            toolCallId: 'call-1',
            contents: [
              { type: 'text', text: 'ok' },
              { type: 'json', json: { nested: true, n: 1 } },
              { type: 'json', json: [1, 2] },
            ],
          }],
        }),
        threadEvent('3', 'tool_completed', '40', { invocationId: 'inv-1' }),
      ],
    )
    expect(timeline.messages).toMatchObject([
      { role: 'tool', toolName: 'bash', text: 'ok\n{"nested":true,"n":1}\n[1,2]', status: 'done' },
    ])
  })

  it('suppresses tool stream once durable TOOL Entry exists', () => {
    const events = [
      threadEvent('1', 'tool_prepared', '50', {
        toolCallId: 'call-1',
        toolName: 'bash',
        arguments: 'ls',
        invocationId: 'inv-1',
      }),
      threadEvent('2', 'tool_completed', '50', { invocationId: 'inv-1' }),
    ]
    const timeline = buildThreadTimeline(
      [
        entry(
          '51',
          'message',
          messagePayload('TOOL', [
            {
              type: 'tool_result',
              toolCallId: 'call-1',
              toolName: 'bash',
              contents: [{ type: 'text', text: 'from entry' }],
            },
          ]),
        ),
      ],
      [],
      events,
    )
    expect(timeline.messages).toMatchObject([{ role: 'tool', text: 'from entry', status: 'done' }])
    expect(timeline.messages).toHaveLength(1)
  })

  it('separates backend journal projection from a later queued mailbox input', () => {
    const timeline = buildThreadTimeline(
      [],
      [
        {
          ...input('in-2', 'user_message', messagePayload('USER', [{ type: 'text', text: '第二句' }]), null),
          createTime: '2026-01-01T00:00:01',
        },
      ],
      [
        {
          ...threadEvent('1', 'assistant_started', '10', {}),
          createTime: '2026-01-01T00:00:03',
        },
        {
          ...threadEvent('2', 'assistant_delta_batch', '10', { deltas: [{ kind: 'text', text: '先回答' }] }),
          createTime: '2026-01-01T00:00:03',
        },
      ],
    )
    expect(timeline.messages).toMatchObject([{ role: 'assistant', text: '先回答' }])
    expect(timeline.queuedMessages).toMatchObject([{ role: 'user', text: '第二句' }])
  })

  it('preserves backend Entry path order when wall-clock timestamps regress', () => {
    const timeline = buildThreadTimeline(
      [
        {
          ...entry('1', 'message', messagePayload('USER', [{ type: 'text', text: 'A' }])),
          createTime: [2026, 1, 1, 0, 0, 3],
        },
        {
          ...entry('2', 'message', messagePayload('USER', [{ type: 'text', text: 'B' }])),
          createTime: [2026, 1, 1, 0, 0, 1],
        },
        {
          ...entry('3', 'message', messagePayload('USER', [{ type: 'text', text: 'C' }])),
          createTime: [2026, 1, 1, 0, 0, 2],
        },
      ],
      [],
      [],
    )
    expect(timeline.messages.map((message) => message.text)).toEqual(['A', 'B', 'C'])
  })

  it('keeps multi-turn USER then ASSISTANT causality when child timestamps regress', () => {
    const timeline = buildThreadTimeline(
      [
        {
          ...entry('1', 'message', messagePayload('USER', [{ type: 'text', text: '问题一' }])),
          createTime: '2026-01-01T00:00:03',
        },
        {
          ...entry('2', 'message', messagePayload('ASSISTANT', [{ type: 'text', text: '回答一' }])),
          createTime: '2026-01-01T00:00:01',
        },
        {
          ...entry('3', 'message', messagePayload('USER', [{ type: 'text', text: '问题二' }])),
          createTime: '2026-01-01T00:00:06',
        },
        {
          ...entry('4', 'message', messagePayload('ASSISTANT', [{ type: 'text', text: '回答二' }])),
          createTime: '2026-01-01T00:00:04',
        },
      ],
      [],
      [],
    )

    expect(timeline.messages.map((message) => [message.role, message.text])).toEqual([
      ['user', '问题一'],
      ['assistant', '回答一'],
      ['user', '问题二'],
      ['assistant', '回答二'],
    ])
  })

  it('does not inspect createTime when projecting the backend Entry path', () => {
    const timeline = buildThreadTimeline(
      [
        {
          ...entry('1', 'message', messagePayload('USER', [{ type: 'text', text: 'late-valid' }])),
          createTime: '2026-01-01T00:00:03',
        },
        {
          ...entry('2', 'message', messagePayload('USER', [{ type: 'text', text: 'invalid' }])),
          createTime: 'not-a-date',
        },
        {
          ...entry('3', 'message', messagePayload('USER', [{ type: 'text', text: 'early-valid' }])),
          createTime: '2026-01-01T00:00:01',
        },
      ],
      [],
      [],
    )
    expect(timeline.messages.map((message) => message.text)).toEqual(['late-valid', 'invalid', 'early-valid'])
  })

  it('derives working from processing, rendered pending inputs and live projection', () => {
    expect(
      isThreadWorking(
        { processing: true } as never,
        { messages: [], queuedMessages: [], hasPendingInputs: false, hasLiveProjection: false },
      ),
    ).toBe(true)
    expect(
      isThreadWorking(
        { processing: false } as never,
        { messages: [], queuedMessages: [], hasPendingInputs: true, hasLiveProjection: false },
      ),
    ).toBe(true)
    expect(
      isThreadWorking(
        { processing: false } as never,
        { messages: [], queuedMessages: [], hasPendingInputs: false, hasLiveProjection: true },
      ),
    ).toBe(true)
    // Stale raw input DTO with null appliedEntryId must not keep working once overlay is suppressed.
    expect(
      isThreadWorking(
        { processing: false } as never,
        { messages: [], queuedMessages: [], hasPendingInputs: false, hasLiveProjection: false },
      ),
    ).toBe(false)

    const suppressed = buildThreadTimeline(
      [entry('11', 'message', messagePayload('USER', [{ type: 'text', text: '已落库' }]))],
      [input('in-1', 'user_message', messagePayload('USER', [{ type: 'text', text: '已落库' }]), null)],
      [threadEvent('1', 'input_applied', '11', { inputId: 'in-1', sequence: 1, type: 'user_message' })],
    )
    expect(suppressed.hasPendingInputs).toBe(false)
    expect(suppressed.queuedMessages).toEqual([])
    expect(isThreadWorking({ processing: false } as never, suppressed)).toBe(false)
    expect(
      isThreadWorking(
        { status: 'FAILED', processing: false } as never,
        { messages: [], queuedMessages: [], hasPendingInputs: true, hasLiveProjection: true },
      ),
    ).toBe(false)
  })
})

function entry(entryId: string, entryType: string, payload: Record<string, unknown>): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: '1',
    parentEntryId: null,
    entryType,
    payloadJson: JSON.stringify(payload),
    createTime: '2026-01-01T00:00:00',
  }
}

function input(
  inputId: string,
  inputType: string,
  payload: Record<string, unknown>,
  appliedEntryId: string | null,
): HarnessThreadInputDTO {
  return {
    inputId,
    threadId: '1',
    sequence: Number(inputId.replace(/\D/g, '') || 1),
    inputType: inputType.toUpperCase() as HarnessThreadInputDTO['inputType'],
    payloadJson: JSON.stringify(payload),
    clientMessageId: `cid-${inputId}`,
    status: appliedEntryId ? 'APPLIED' : 'QUEUED',
    appliedEntryId,
    resolvedAt: appliedEntryId ? '2026-01-01T00:00:01' : null,
    cancelledByStopId: null,
    createTime: '2026-01-01T00:00:00',
  }
}

function threadEvent(
  eventId: string,
  eventType: string,
  subjectEntryId: string | null,
  payload: Record<string, unknown>,
): ThreadEventDTO {
  return {
    eventId,
    threadId: '1',
    subjectEntryId,
    eventType,
    payloadJson: JSON.stringify(payload),
    createTime: '2026-01-01T00:00:00',
  }
}

function messagePayload(role: string, contents: Array<Record<string, unknown>>) {
  return { message: { role, contents }, assistantMetadata: null }
}
