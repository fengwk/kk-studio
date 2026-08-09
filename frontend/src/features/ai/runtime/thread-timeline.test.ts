import { describe, expect, it } from 'vitest'
import type {
  EntryType,
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { RealtimeModelStream, RealtimeToolStream } from '@/features/ai/runtime/thread-realtime-state'
import { buildThreadTimeline, isThreadWorking } from '@/features/ai/runtime/thread-timeline'

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
      [],
    )

    expect(timeline.messages).toMatchObject([
      { role: 'entry', kind: 'root', title: '会话开始', status: 'done' },
      { role: 'user', text: '检查大纲', status: 'done' },
      { role: 'assistant', text: '结构完整。', thinking: '先梳理结构。', status: 'done' },
    ])
    expect(timeline.queuedMessages).toEqual([])
    expect(timeline.hasPendingInputs).toBe(false)
  })

  it('appends a transient assistant modelStream overlay after durable entries', () => {
    const stream: RealtimeModelStream = {
      threadId: 'thread-1',
      invocationId: 'model-1',
      attempt: 1,
      sequence: 1,
      text: '正在输出',
      thinking: '正在思考',
      createdAt: '2026-07-28T10:00:00Z',
      status: 'streaming',
    }
    const live = buildThreadTimeline([], [], [], stream)
    const durable = buildThreadTimeline(
      [
        entry(
          '2',
          'MESSAGE',
          messagePayload('ASSISTANT', [
            { type: 'thinking', text: '已完成思考' },
            { type: 'text', text: '正在输出' },
          ]),
        ),
      ],
      [],
      [],
      null,
    )

    expect(live.messages).toMatchObject([
      { role: 'assistant', text: '正在输出', thinking: '正在思考', status: 'streaming' },
    ])
    expect(durable.messages).toMatchObject([
      { role: 'assistant', text: '正在输出', thinking: '已完成思考', status: 'done' },
    ])
    expect(durable.messages).toHaveLength(1)
  })

  it('keeps non-canonical Entry types visible as an unknown durable event', () => {
    const malformed = {
      ...entry('1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'hidden' }])),
      entryType: 'message',
    } as unknown as HarnessSessionEntryDTO

    expect(buildThreadTimeline([malformed], [], []).messages).toMatchObject([
      {
        role: 'entry',
        kind: 'unknown_entry',
        title: '未识别 Entry：message',
        subjectEntryId: '1',
      },
    ])
  })

  it('keeps a blank raw payload inspectable through the durable Entry fallback', () => {
    const root = {
      ...entry('root', 'ROOT', {}),
      payloadJson: '',
    }

    const timeline = buildThreadTimeline([root], [], [])

    expect(timeline.messages).toMatchObject([
      { role: 'entry', kind: 'root', rawPayloadJson: '{}', subjectEntryId: 'root' },
    ])
  })

  it('keeps a blank Entry type inspectable instead of dropping it', () => {
    const malformed = {
      ...entry('unknown', 'ROOT', {}),
      entryType: '' as unknown as EntryType,
    }

    const timeline = buildThreadTimeline([malformed], [], [])

    expect(timeline.messages).toMatchObject([
      {
        role: 'entry',
        kind: 'unknown_entry',
        title: '未识别 Entry：未知类型',
        subjectEntryId: 'unknown',
      },
    ])
  })

  it('keeps QUEUED USER_MESSAGE inputs out of the transcript', () => {
    const timeline = buildThreadTimeline(
      [entry('2', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: '第一句' }]))],
      [command('in-2', '1', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: '排队中' }]), 'QUEUED')],
      [],
    )
    expect(timeline.messages).toMatchObject([{ role: 'user', text: '第一句' }])
    expect(timeline.queuedMessages).toMatchObject([
      { commandId: 'in-2', role: 'user', text: '排队中', sequence: '1' },
    ])
    expect(timeline.hasPendingInputs).toBe(true)
  })

  it('preserves backend mailbox order instead of sorting inputs in the frontend', () => {
    const first = command('in-2', '2', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: '后端第一条' }]), 'QUEUED')
    const second = command('in-1', '1', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: '后端第二条' }]), 'QUEUED')

    const timeline = buildThreadTimeline([], [first, second], [])

    expect(timeline.messages).toEqual([])
    expect(timeline.queuedMessages.map((message) => message.text)).toEqual(['后端第一条', '后端第二条'])
  })

  it('never bridges APPLIED inputs into the transcript; Entries are authoritative', () => {
    const timeline = buildThreadTimeline(
      [],
      [command('in-1', '1', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: '仍可见' }]), 'APPLIED')],
      [],
    )
    expect(timeline.messages).toEqual([])
    expect(timeline.queuedMessages).toEqual([])
    expect(timeline.hasPendingInputs).toBe(false)
  })

  it('dedupes applied USER_MESSAGE inputs once Entry appears', () => {
    const timeline = buildThreadTimeline(
      [entry('11', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: '已落库' }]))],
      [command('in-1', '1', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: '已落库' }]), 'APPLIED')],
      [],
    )
    expect(timeline.messages.filter((message) => message.role === 'user')).toHaveLength(1)
    expect(timeline.messages).toMatchObject([{ role: 'user', text: '已落库', id: '11' }])
    expect(timeline.hasPendingInputs).toBe(false)
  })

  it('keeps terminal assistant errors visible before a later successful response', () => {
    const timeline = buildThreadTimeline(
      [
        entry('1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: '问题' }])),
        entry('2', 'ASSISTANT_ERROR', { error: { kind: 'TRANSIENT', message: '第一次失败' } }),
        entry('3', 'ASSISTANT_ERROR', { error: { kind: 'TRANSIENT', message: '第二次失败' } }),
        entry('4', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: '最终成功' }])),
      ],
      [],
      [],
    )

    expect(timeline.messages).toMatchObject([
      { id: '1', role: 'user', text: '问题' },
      { id: '2', role: 'assistant', text: '第一次失败', status: 'error' },
      { id: '3', role: 'assistant', text: '第二次失败', status: 'error' },
      { id: '4', role: 'assistant', text: '最终成功', status: 'done' },
    ])
  })

  it('produces no messages for TURN_START and TURN_END control boundaries', () => {
    const timeline = buildThreadTimeline(
      [
        entry('0', 'ROOT', {}),
        entry('1', 'TURN_START', { reason: 'USER_MESSAGE', settings: {} }),
        entry('2', 'TURN_END', {
          turnStartEntryId: '1',
          outcome: 'COMPLETED',
          continueModel: false,
          reason: 'no-continuation',
          closeRequestId: null,
        }),
        entry('3', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'next turn' }])),
      ],
      [],
      [],
    )

    expect(timeline.messages.map((m) => m.id)).toEqual([
      'entry:0',
      '3',
    ])
    expect(timeline.messages.find((m) => m.role === 'entry' && m.kind === 'unknown_entry')).toBeUndefined()
  })

  it('projects durable TOOL results with correlated tool_call arguments', () => {
    const timeline = buildThreadTimeline(
      [
        entry(
          '40',
          'MESSAGE',
          messagePayload('ASSISTANT', [
            {
              type: 'tool_call',
              toolCallId: 'call-1',
              toolName: 'bash',
              rendererKey: 'shell-command',
              argumentsJson: '{"command":"ls"}',
            },
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
              rendererKey: 'shell-command',
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
      [],
    )
    expect(timeline.messages).toMatchObject([
      { role: 'assistant', text: 'run', status: 'done' },
      {
        role: 'tool',
        phase: 'call',
        toolName: 'bash',
        rendererKey: 'shell-command',
        arguments: '{"command":"ls"}',
        text: '',
        status: 'done',
      },
      {
        role: 'tool',
        phase: 'result',
        toolName: 'bash',
        rendererKey: 'shell-command',
        arguments: '{"command":"ls"}',
        text: 'ok\n{"nested":true,"n":1}\n[1,2]',
        status: 'done',
      },
    ])
  })

  it('overlays tool invocation status/approval/partial onto the durable tool call', () => {
    const invocations: ToolInvocationDTO[] = [
      {
        id: 'inv-1',
        modelInvocationId: 'm-1',
        assistantEntryId: '40',
        ordinal: 0,
        status: 'WAITING_APPROVAL',
        attempt: 1,
        toolCallId: 'call-1',
        toolName: 'bash',
        toolVersion: '1',
        rendererKey: 'bash',
        toolType: 'shell',
        environmentName: null,
        argumentsJson: '{"command":"ls"}',
        approvalJson: JSON.stringify({ required: true, decision: null, decisionId: null }),
        resultJson: null,
        errorJson: null,
        resultEntryId: null,
        createTime: '2026-07-28T10:00:00Z',
        updateTime: '2026-07-28T10:00:00Z',
      },
    ]
    const toolStreams = new Map<string, RealtimeToolStream>([
      ['inv-1', {
        threadId: 'thread-1',
        invocationId: 'inv-1',
        attempt: 1,
        toolCallId: 'call-1',
        text: 'streaming partial output',
        error: false,
        createdAt: '2026-07-28T10:00:01Z',
      }],
    ])
    const timeline = buildThreadTimeline(
      [
        entry(
          '40',
          'MESSAGE',
          messagePayload('ASSISTANT', [
            {
              type: 'tool_call',
              toolCallId: 'call-1',
              toolName: 'bash',
              rendererKey: 'bash',
              argumentsJson: '{"command":"ls"}',
            },
          ]),
        ),
      ],
      [],
      invocations,
      null,
      toolStreams,
    )

    const call = timeline.messages.find((m) => m.role === 'tool' && m.phase === 'call')
    expect(call).toMatchObject({
      role: 'tool',
      phase: 'call',
      status: 'streaming',
      invocationId: 'inv-1',
      partial: 'streaming partial output',
      approval: { required: true, decision: null, decisionId: null },
    })
  })

  it('projects overlays by durable identity, never by reused toolCallId on an older Entry', () => {
    const invocation: ToolInvocationDTO = {
      id: 'inv-2',
      modelInvocationId: 'm-2',
      assistantEntryId: '41',
      ordinal: 0,
      status: 'WAITING_APPROVAL',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'bash',
      toolVersion: '1',
      rendererKey: 'bash',
      toolType: 'shell',
      environmentName: null,
      argumentsJson: '{"command":"ls"}',
      approvalJson: JSON.stringify({ required: true, decision: null, decisionId: null }),
      resultJson: null,
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
    }
    const toolStreams = new Map<string, RealtimeToolStream>([
      ['inv-2', {
        threadId: 'thread-1',
        invocationId: 'inv-2',
        attempt: 1,
        toolCallId: 'call-1',
        text: 'second-turn partial output',
        error: false,
        createdAt: '2026-07-28T10:00:01Z',
      }],
    ])
    const timeline = buildThreadTimeline(
      [
        entry(
          '40',
          'MESSAGE',
          messagePayload('ASSISTANT', [
            {
              type: 'tool_call',
              toolCallId: 'call-1',
              toolName: 'bash',
              rendererKey: 'bash',
              argumentsJson: '{"command":"ls"}',
            },
          ]),
        ),
        entry(
          '41',
          'MESSAGE',
          messagePayload('ASSISTANT', [
            {
              type: 'tool_call',
              toolCallId: 'call-1',
              toolName: 'bash',
              rendererKey: 'bash',
              argumentsJson: '{"command":"ls"}',
            },
          ]),
        ),
      ],
      [],
      [invocation],
      null,
      toolStreams,
    )

    const calls = timeline.messages.filter((m) => m.role === 'tool' && m.phase === 'call')
    expect(calls).toHaveLength(2)
    // 两个持久调用在序号 0 处共用 toolCallId 'call-1'，但只有 invocation 真实所属的
    // Entry 才允许接收 streaming/approval/invocationId/partial。
    expect(calls[0]).toMatchObject({
      subjectEntryId: '40',
      toolCallId: 'call-1',
      status: 'done',
    })
    expect(calls[0]).not.toHaveProperty('invocationId')
    expect(calls[0]).not.toHaveProperty('partial')
    expect(calls[0]).not.toHaveProperty('approval')
    expect(calls[1]).toMatchObject({
      subjectEntryId: '41',
      toolCallId: 'call-1',
      status: 'streaming',
      invocationId: 'inv-2',
      partial: 'second-turn partial output',
      approval: { required: true, decision: null, decisionId: null },
    })
  })

  it('does not project an invocation whose toolCallId disagrees with the durable call', () => {
    const invocation: ToolInvocationDTO = {
      id: 'inv-3',
      modelInvocationId: 'm-3',
      assistantEntryId: '42',
      ordinal: 0,
      status: 'WAITING_APPROVAL',
      attempt: 1,
      toolCallId: 'call-other',
      toolName: 'bash',
      toolVersion: '1',
      rendererKey: 'bash',
      toolType: 'shell',
      environmentName: null,
      argumentsJson: '{"command":"ls"}',
      approvalJson: JSON.stringify({ required: true, decision: null, decisionId: null }),
      resultJson: null,
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
    }
    const timeline = buildThreadTimeline(
      [
        entry(
          '42',
          'MESSAGE',
          messagePayload('ASSISTANT', [
            {
              type: 'tool_call',
              toolCallId: 'call-1',
              toolName: 'bash',
              rendererKey: 'bash',
              argumentsJson: '{"command":"ls"}',
            },
          ]),
        ),
      ],
      [],
      [invocation],
    )
    expect(timeline.messages).toMatchObject([
      {
        role: 'tool',
        phase: 'call',
        subjectEntryId: '42',
        toolCallId: 'call-1',
        status: 'done',
      },
    ])
    const call = timeline.messages.find((m) => m.role === 'tool' && m.phase === 'call')
    expect(call).not.toHaveProperty('invocationId')
    expect(call).not.toHaveProperty('partial')
    expect(call).not.toHaveProperty('approval')
  })

  it('separates durable transcript from a later queued mailbox input', () => {
    const timeline = buildThreadTimeline(
      [
        entry('10', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: '先回答' }])),
      ],
      [
        command('in-2', '1', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: '第二句' }]), 'QUEUED'),
      ],
      [],
    )
    expect(timeline.messages).toMatchObject([{ role: 'assistant', text: '先回答' }])
    expect(timeline.queuedMessages).toMatchObject([{ role: 'user', text: '第二句' }])
  })

  it('preserves backend Entry path order when wall-clock timestamps regress', () => {
    const timeline = buildThreadTimeline(
      [
        { ...entry('1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'A' }])), createTime: [2026, 1, 1, 0, 0, 3] },
        { ...entry('2', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'B' }])), createTime: [2026, 1, 1, 0, 0, 1] },
        { ...entry('3', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'C' }])), createTime: [2026, 1, 1, 0, 0, 2] },
      ],
      [],
      [],
    )
    expect(timeline.messages.map((message) => message.text)).toEqual(['A', 'B', 'C'])
  })

  it('keeps multi-turn USER then ASSISTANT causality when child timestamps regress', () => {
    const timeline = buildThreadTimeline(
      [
        { ...entry('1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: '问题一' }])), createTime: '2026-01-01T00:00:03' },
        { ...entry('2', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: '回答一' }])), createTime: '2026-01-01T00:00:01' },
        { ...entry('3', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: '问题二' }])), createTime: '2026-01-01T00:00:06' },
        { ...entry('4', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: '回答二' }])), createTime: '2026-01-01T00:00:04' },
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
        { ...entry('1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'late-valid' }])), createTime: '2026-01-01T00:00:03' },
        { ...entry('2', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'invalid' }])), createTime: 'not-a-date' },
        { ...entry('3', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'early-valid' }])), createTime: '2026-01-01T00:00:01' },
      ],
      [],
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
        { status: 'MODEL_STREAMING', processing: false } as never,
        { messages: [], queuedMessages: [], hasPendingInputs: false },
      ),
    ).toBe(true)
  })
})

function entry(entryId: string, entryType: EntryType, payload: Record<string, unknown>): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 's1',
    parentEntryId: null,
    entryType,
    payloadJson: JSON.stringify(payload),
    createTime: '2026-01-01T00:00:00',
  }
}

function command(
  commandId: string,
  sequence: string,
  type: 'USER_MESSAGE' | 'CUSTOM_MESSAGE',
  payload: Record<string, unknown>,
  state: 'QUEUED' | 'APPLIED',
): HarnessThreadCommandDTO {
  return {
    commandId,
    threadId: 't1',
    sequence,
    type,
    state,
    clientCommandId: `cid-${commandId}`,
    payloadJson: JSON.stringify(payload),
    consumedTurnStartEntryId: null,
    cancelledAt: null,
    createTime: '2026-01-01T00:00:00',
  }
}

function messagePayload(role: string, contents: Array<Record<string, unknown>>) {
  return {
    message: { role, contents },
  }
}
