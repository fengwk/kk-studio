import { describe, expect, it } from 'vitest'
import type {
  EntryType,
  HarnessSessionEntryDTO,
  HarnessThreadInputDTO,
  ThreadInputType,
} from '@/shared/api/contracts'
import type { RealtimeModelStream } from '@/features/ai/thread-realtime-state'
import { buildThreadTimeline, isThreadWorking } from '@/features/ai/thread-timeline'

describe('thread timeline', () => {
  it('projects durable entries as the transcript baseline', () => {
    const timeline = buildThreadTimeline(
      [
        entry('1', 'ROOT', {}),
        entry('2', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: '检查大纲' }])),
        entry('3', 'MESSAGE', messagePayload('ASSISTANT', [
          { type: 'thinking', text: '先梳理结构。' },
          { type: 'text', text: '结构完整。' },
        ])),
      ],
      [],
    )

    expect(timeline.messages).toMatchObject([
      { role: 'user', text: '检查大纲', status: 'done' },
      { role: 'assistant', text: '结构完整。', thinking: '先梳理结构。', status: 'done' },
    ])
    expect(timeline.queuedMessages).toEqual([])
    expect(timeline.hasPendingInputs).toBe(false)
  })

  it('adds a transient assistant overlay until the matching durable response is available', () => {
    const stream: RealtimeModelStream = {
      threadId: 'thread-1',
      invocationId: 'model-1',
      attempt: 1,
      text: '正在输出',
      thinking: '正在思考',
      createdAt: '2026-07-28T10:00:00Z',
    }
    const live = buildThreadTimeline([], [], stream)
    const durable = buildThreadTimeline(
      [
        {
          ...entry(
            '2',
            'MESSAGE',
            messagePayload('ASSISTANT', [{ type: 'text', text: '正在输出' }]),
          ),
          createTime: '2026-07-28T10:00:01',
        },
      ],
      [],
      stream,
    )

    expect(live.messages).toMatchObject([
      { role: 'assistant', text: '正在输出', thinking: '正在思考', status: 'streaming' },
    ])
    expect(durable.messages).toMatchObject([{ role: 'assistant', text: '正在输出', status: 'done' }])
    expect(durable.messages).toHaveLength(1)
  })

  it('does not normalize non-canonical lowercase Entry types', () => {
    const malformed = {
      ...entry('1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'hidden' }])),
      entryType: 'message',
    } as unknown as HarnessSessionEntryDTO

    expect(buildThreadTimeline([malformed], []).messages).toEqual([])
  })

  it('keeps QUEUED USER_MESSAGE inputs out of the transcript', () => {
    const timeline = buildThreadTimeline(
      [entry('2', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: '第一句' }]))],
      [input('in-2', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: '排队中' }]), false)],
    )
    expect(timeline.messages).toMatchObject([{ role: 'user', text: '第一句' }])
    expect(timeline.queuedMessages).toMatchObject([
      { inputId: 'in-2', role: 'user', text: '排队中' },
    ])
    expect(timeline.hasPendingInputs).toBe(true)
  })

  it('preserves backend mailbox order instead of sorting inputs in the frontend', () => {
    const first = input('in-2', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: '后端第一条' }]), false)
    const second = input('in-1', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: '后端第二条' }]), false)
    first.sequence = 2
    second.sequence = 1

    const timeline = buildThreadTimeline([], [first, second])

    expect(timeline.messages).toEqual([])
    expect(timeline.queuedMessages.map((message) => message.text)).toEqual(['后端第一条', '后端第二条'])
  })

  it('never bridges APPLIED inputs into the transcript; Entries are authoritative', () => {
    const timeline = buildThreadTimeline(
      [],
      [input('in-1', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: '仍可见' }]), true)],
    )
    expect(timeline.messages).toEqual([])
    expect(timeline.queuedMessages).toEqual([])
    expect(timeline.hasPendingInputs).toBe(false)
  })

  it('dedupes applied USER_MESSAGE inputs once Entry appears', () => {
    const timeline = buildThreadTimeline(
      [entry('11', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: '已落库' }]))],
      [input('in-1', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: '已落库' }]), true)],
    )
    expect(timeline.messages.filter((message) => message.role === 'user')).toHaveLength(1)
    expect(timeline.messages).toMatchObject([{ role: 'user', text: '已落库', id: '11' }])
    expect(timeline.hasPendingInputs).toBe(false)
  })

  it('keeps terminal assistant errors visible before a later successful response', () => {
    const timeline = buildThreadTimeline(
      [
        entry('1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: '问题' }])),
        entry('2', 'ASSISTANT_ERROR', assistantErrorPayload('第一次失败')),
        entry('3', 'ASSISTANT_ERROR', assistantErrorPayload('第二次失败')),
        entry('4', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: '最终成功' }])),
      ],
      [],
    )

    expect(timeline.messages).toMatchObject([
      { id: '1', role: 'user', text: '问题' },
      { id: '2', role: 'assistant', text: '第一次失败', status: 'error' },
      { id: '3', role: 'assistant', text: '第二次失败', status: 'error' },
      { id: '4', role: 'assistant', text: '最终成功', status: 'done' },
    ])
  })

  it('projects durable TOOL results with correlated tool_call arguments', () => {
    const timeline = buildThreadTimeline(
      [
        entry(
          '40',
          'MESSAGE',
          messagePayload('ASSISTANT', [
            { type: 'tool_call', toolCallId: 'call-1', toolName: 'bash', argumentsJson: '{"command":"ls"}' },
            { type: 'text', text: 'run' },
          ]),
        ),
        entry(
          '41',
          'MESSAGE',
          messagePayload('TOOL', [
            {
              type: 'tool_result',
              toolCallId: 'call-1',
              toolName: 'bash',
              error: false,
              detailsJson: '{}',
              contents: [
                { type: 'text', text: 'ok' },
                { type: 'json', json: '{"nested":true,"n":1}' },
                { type: 'json', json: '[1,2]' },
              ],
            },
          ]),
        ),
      ],
      [],
    )
    expect(timeline.messages).toMatchObject([
      { role: 'assistant', text: 'run', status: 'done' },
      { role: 'tool', toolName: 'bash', arguments: '{"command":"ls"}', text: 'ok\n{"nested":true,"n":1}\n[1,2]', status: 'done' },
    ])
  })

  it('separates durable transcript from a later queued mailbox input', () => {
    const timeline = buildThreadTimeline(
      [
        entry('10', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: '先回答' }])),
      ],
      [
        {
          ...input('in-2', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: '第二句' }]), false),
          createTime: '2026-01-01T00:00:01',
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
          ...entry('1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'A' }])),
          createTime: [2026, 1, 1, 0, 0, 3],
        },
        {
          ...entry('2', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'B' }])),
          createTime: [2026, 1, 1, 0, 0, 1],
        },
        {
          ...entry('3', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'C' }])),
          createTime: [2026, 1, 1, 0, 0, 2],
        },
      ],
      [],
    )
    expect(timeline.messages.map((message) => message.text)).toEqual(['A', 'B', 'C'])
  })

  it('keeps multi-turn USER then ASSISTANT causality when child timestamps regress', () => {
    const timeline = buildThreadTimeline(
      [
        {
          ...entry('1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: '问题一' }])),
          createTime: '2026-01-01T00:00:03',
        },
        {
          ...entry('2', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: '回答一' }])),
          createTime: '2026-01-01T00:00:01',
        },
        {
          ...entry('3', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: '问题二' }])),
          createTime: '2026-01-01T00:00:06',
        },
        {
          ...entry('4', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: '回答二' }])),
          createTime: '2026-01-01T00:00:04',
        },
      ],
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
          ...entry('1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'late-valid' }])),
          createTime: '2026-01-01T00:00:03',
        },
        {
          ...entry('2', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'invalid' }])),
          createTime: 'not-a-date',
        },
        {
          ...entry('3', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'early-valid' }])),
          createTime: '2026-01-01T00:00:01',
        },
      ],
      [],
    )
    expect(timeline.messages.map((message) => message.text)).toEqual(['late-valid', 'invalid', 'early-valid'])
  })

  it('derives working from thread status/processing and pending queued inputs', () => {
    expect(
      isThreadWorking(
        { processing: true } as never,
        { messages: [], queuedMessages: [], hasPendingInputs: false },
      ),
    ).toBe(true)
    expect(
      isThreadWorking(
        { processing: false } as never,
        { messages: [], queuedMessages: [], hasPendingInputs: true },
      ),
    ).toBe(true)
    expect(
      isThreadWorking(
        { status: 'IDLE', processing: false } as never,
        { messages: [], queuedMessages: [], hasPendingInputs: false },
      ),
    ).toBe(false)
    expect(
      isThreadWorking(
        { status: 'RUNNABLE', processing: false } as never,
        { messages: [], queuedMessages: [], hasPendingInputs: false },
      ),
    ).toBe(true)
    expect(
      isThreadWorking(
        { status: 'WAITING', processing: false } as never,
        { messages: [], queuedMessages: [], hasPendingInputs: false },
      ),
    ).toBe(true)
    expect(
      isThreadWorking(
        { status: 'RUNNING', processing: false } as never,
        { messages: [], queuedMessages: [], hasPendingInputs: false },
      ),
    ).toBe(true)
  })
})

function entry(entryId: string, entryType: EntryType, payload: Record<string, unknown>): HarnessSessionEntryDTO {
  return {
    entryId,
    parentEntryId: null,
    entryType,
    payloadJson: JSON.stringify(payload),
    createTime: '2026-01-01T00:00:00',
  }
}

function input(
  inputId: string,
  inputType: ThreadInputType,
  payload: Record<string, unknown>,
  applied: boolean,
): HarnessThreadInputDTO {
  return {
    inputId,
    threadId: '1',
    sequence: Number(inputId.replace(/\D/g, '') || 1),
    inputType,
    payloadJson: JSON.stringify(payload),
    clientMessageId: `cid-${inputId}`,
    status: applied ? 'APPLIED' : 'QUEUED',
    resolvedAt: applied ? '2026-01-01T00:00:01' : null,
    createTime: '2026-01-01T00:00:00',
  }
}

function messagePayload(role: string, contents: Array<Record<string, unknown>>) {
  return {
    message: { role, contents },
    assistantMetadata: role === 'ASSISTANT'
      ? zeroAssistantMetadata(contents.some((content) => content.type === 'tool_call') ? 'TOOL_CALLS' : 'COMPLETED')
      : null,
  }
}

function zeroAssistantMetadata(stopReason: 'COMPLETED' | 'TOOL_CALLS') {
  return {
    stopReason,
    usage: {
      inputTokens: 0,
      outputTokens: 0,
      cacheReadTokens: 0,
      cacheWriteTokens: 0,
      cacheWriteLongTokens: 0,
      reasoningTokens: 0,
      providerTotalTokens: 0,
    },
    cost: {
      currency: 'USD',
      input: '0',
      output: '0',
      cacheRead: '0',
      cacheWrite: '0',
      cacheWriteLong: '0',
      reasoning: '0',
      total: '0',
    },
  }
}

function assistantErrorPayload(message: string) {
  return {
    error: {
      kind: 'TRANSIENT',
      message,
    },
  }
}
