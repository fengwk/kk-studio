import { describe, expect, it } from 'vitest'
import type {
  EntryType,
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'
import {
  buildThreadTimeline,
  parseApproval,
} from '@/features/ai/runtime/thread-timeline-builder'
import type { RealtimeToolStream } from '@/features/ai/runtime/thread-realtime-state'

describe('thread timeline edge branches', () => {
  it('projects system messages, empty user content, tool resources, and assistant errors', () => {
    const timeline = buildThreadTimeline(
      [
        entry('2', 'MESSAGE', messagePayload('SYSTEM', [{ type: 'text', text: '系统提示' }])),
        entry('3', 'MESSAGE', messagePayload('USER', [])),
        entry(
          '5',
          'MESSAGE',
          messagePayload('ASSISTANT', [
            {
              type: 'tool_call',
              toolCallId: 'call-a',
              toolName: 'search',
              rendererKey: 'search',
              argumentsJson: '{"q":1}',
            },
            { type: 'text', text: '调用工具' },
          ]),
        ),
        entry(
          '6',
          'MESSAGE',
          messagePayload('TOOL', [
            {
              type: 'tool_result',
              toolCallId: 'call-a',
              toolName: 'search',
              rendererKey: 'search',
              error: true,
              detailsJson: '{"code":"FAILED"}',
              contents: [
                { type: 'text', text: 'fail' },
                { type: 'json', json: '{"x":1}' },
                {
                  type: 'resource',
                  uri: 'data:image/png;base64,aW1n',
                  mediaType: 'image/png',
                  name: 'a.png',
                },
                {
                  type: 'resource',
                  uri: 'data:audio/wav;base64,YR4=',
                  mediaType: 'audio/wav',
                  name: 'a.wav',
                },
                {
                  type: 'resource',
                  uri: 'data:video/mp4;base64,AAAAAAAA',
                  mediaType: 'video/mp4',
                  name: 'a.mp4',
                },
                {
                  type: 'resource',
                  uri: 'file:///tmp/a.pdf',
                  mediaType: 'application/pdf',
                  name: 'a.pdf',
                },
              ],
            },
          ]),
        ),
        entry('7', 'ASSISTANT_ERROR', { error: { kind: 'TRANSIENT', message: 'boom' } }),
      ],
      [command('applied', '1', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: 'done' }]), 'APPLIED')],
      [],
    )

    expect(timeline.messages.some((m) => m.role === 'system' && m.text === '系统提示')).toBe(true)
    expect(timeline.messages.some((m) => m.role === 'assistant' && String(m.text).includes('boom'))).toBe(true)
    expect(timeline.messages.some((m) => m.role === 'tool' && m.status === 'error')).toBe(true)
    expect(timeline.messages.some((m) => m.role === 'tool' && m.attachments.length > 0)).toBe(true)
    expect(timeline.hasPendingInputs).toBe(false)
    expect(timeline.queuedMessages).toEqual([])
  })

  it('restores a durable MODEL_ATTEMPT_FAILURE after refresh', () => {
    const timeline = buildThreadTimeline(
      [
        entry('failure-1', 'MODEL_ATTEMPT_FAILURE', {
          attempt: {
            attempt: 1,
            sequence: 2,
            text: 'durable partial',
            thinking: 'durable thinking',
          },
          error: { code: 'TRANSIENT', message: 'provider down' },
          retryAt: '2026-01-01T00:00:05Z',
        }),
      ],
      [],
      [],
    )

    expect(timeline.messages).toMatchObject([
      {
        id: 'failure-1',
        role: 'model_attempt_failure',
        sequence: '2',
        text: 'durable partial',
        thinking: 'durable thinking',
        errorCode: 'TRANSIENT',
        errorMessage: 'provider down',
        retryAt: '2026-01-01T00:00:05Z',
        nextAttempt: 2,
      },
    ])
  })

  it('projects terminal attempt partial and error separately without retry copy', () => {
    const timeline = buildThreadTimeline(
      [
        entry('terminal-error', 'ASSISTANT_ERROR', {
          error: { code: 'INVALID_REQUEST', message: 'terminal failure' },
          attempt: {
            attempt: 2,
            sequence: 7,
            text: 'terminal partial',
            thinking: 'terminal thinking',
          },
        }),
      ],
      [],
      [],
    )

    expect(timeline.messages).toMatchObject([
      {
        id: 'terminal-error',
        role: 'model_attempt_failure',
        attempt: 2,
        sequence: '7',
        text: 'terminal partial',
        thinking: 'terminal thinking',
        errorCode: 'INVALID_REQUEST',
        errorMessage: 'terminal failure',
        retryAt: null,
        nextAttempt: null,
      },
    ])
  })

  it('keeps ASSISTANT_ERROR without an attempt as the ordinary assistant error', () => {
    const timeline = buildThreadTimeline(
      [
        entry('planning-error', 'ASSISTANT_ERROR', {
          error: { code: 'PLANNING_FAILED', message: 'planning failure' },
          attempt: null,
        }),
      ],
      [],
      [],
    )

    expect(timeline.messages).toMatchObject([
      {
        id: 'planning-error',
        role: 'assistant',
        text: 'planning failure',
        status: 'error',
      },
    ])
    expect(timeline.messages[0]?.role).toBe('assistant')
  })

  it('keeps pending input after applied marker is absent and ignores blank input text', () => {
    const timeline = buildThreadTimeline(
      [],
      [
        command('blank', '1', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: '' }]), 'QUEUED'),
        command('ok', '2', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: '可见' }]), 'QUEUED'),
      ],
      [],
    )
    expect(timeline.messages).toEqual([])
    expect(timeline.queuedMessages).toMatchObject([{ role: 'user', text: '可见' }])
    expect(timeline.hasPendingInputs).toBe(true)
  })

  it('keeps durable custom messages in transcript and queued messages in decoration order', () => {
    const timeline = buildThreadTimeline(
      [
        entry('custom-system', 'CUSTOM_MESSAGE', customMessagePayload('SYSTEM', 'durable system')),
        entry('custom-user', 'CUSTOM_MESSAGE', customMessagePayload('USER', 'durable user')),
      ],
      [
        command('queued-system', '1', 'CUSTOM_MESSAGE', customMessagePayload('SYSTEM', 'queued system'), 'QUEUED'),
        command('queued-user', '2', 'CUSTOM_MESSAGE', customMessagePayload('USER', 'queued user'), 'QUEUED'),
      ],
      [],
    )

    expect(timeline.messages).toMatchObject([
      { role: 'system', text: 'durable system' },
      { role: 'user', text: 'durable user' },
    ])
    expect(timeline.queuedMessages).toMatchObject([
      { role: 'system', text: 'queued system' },
      { role: 'user', text: 'queued user' },
    ])
  })

  it('projects durable blob resources from lowercase runtime message contents', () => {
    const blobId = '0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01'
    const timeline = buildThreadTimeline(
      [
        entry(
          'user-resource',
          'MESSAGE',
          messagePayload('USER', [{ type: 'resource', blobId, name: 'input.png', preview: null }]),
        ),
        entry(
          'assistant-call',
          'MESSAGE',
          messagePayload('ASSISTANT', [
            {
              type: 'tool_call',
              toolCallId: 'call-resource',
              toolName: 'read',
              rendererKey: 'read',
              argumentsJson: '{}',
            },
          ]),
        ),
        entry(
          'tool-resource',
          'MESSAGE',
          messagePayload('TOOL', [
            {
              type: 'tool_result',
              toolCallId: 'call-resource',
              toolName: 'read',
              rendererKey: 'read',
              error: false,
              detailsJson: '{}',
              contents: [
                { type: 'resource', blobId, name: 'result.txt', preview: 'excerpt' },
              ],
            },
          ]),
        ),
      ],
      [],
      [],
    )

    expect(timeline.messages).toMatchObject([
      { role: 'user', attachments: [{ blobId, name: 'input.png' }] },
      { role: 'tool', phase: 'call' },
      {
        role: 'tool',
        phase: 'result',
        attachments: [{ blobId, name: 'result.txt', preview: 'excerpt' }],
      },
    ])
  })

  it('suppresses the entire compaction turn and its realtime model overlay', () => {
    const timeline = buildThreadTimeline(
      [
        entry('stopped-start', 'TURN_START', { reason: 'COMPACTION', settings: {} }),
        entry('aborted', 'ASSISTANT_ABORTED', {
          message: {
            role: 'ASSISTANT',
            contents: [{ type: 'text', text: 'internal partial summary' }],
          },
        }),
        entry('stopped-end', 'TURN_END', {
          turnStartEntryId: 'stopped-start',
          outcome: 'STOPPED',
          continueModel: false,
          reason: 'USER_STOP',
          closeRequestId: 'stop-1',
        }),
        entry('completed-start', 'TURN_START', { reason: 'COMPACTION', settings: {} }),
        entry('compaction', 'COMPACTION', {
          phase: 'FULL',
          trigger: 'THRESHOLD',
          complete: true,
          summaryText: 'internal summary',
        }),
        entry('completed-end', 'TURN_END', {
          turnStartEntryId: 'completed-start',
          outcome: 'COMPLETED',
          continueModel: false,
          reason: null,
          closeRequestId: null,
        }),
      ],
      [],
      [],
      {
        threadId: 't1',
        invocationId: 'm1',
        attempt: 0,
        sequence: 1,
        text: 'internal realtime summary',
        thinking: '',
        toolCalls: [],
        createdAt: '2026-01-01T00:00:00',
        status: 'streaming',
      },
    )
    expect(timeline.messages).toEqual([])
    expect(timeline.queuedMessages).toEqual([])
    expect(timeline.hasPendingInputs).toBe(false)
  })

  it('does not show snapshot failures for a compaction turn', () => {
    const timeline = buildThreadTimeline(
      [
        entry('compaction-start', 'TURN_START', { reason: 'COMPACTION', settings: {} }),
        entry('compaction-end', 'TURN_END', {
          turnStartEntryId: 'compaction-start',
          outcome: 'COMPLETED',
          continueModel: false,
        }),
      ],
      [],
      [],
      null,
      null,
      [
        {
          modelInvocationId: 'model-1',
          turnStartEntryId: 'compaction-start',
          basisHeadEntryId: 'head-1',
          attempt: 1,
          sequence: '1',
          text: 'hidden',
          thinking: 'hidden',
          errorCode: 'ERROR',
          errorMessage: 'hidden',
          failedAt: '2026-01-01T00:00:00Z',
          retryAt: '2026-01-01T00:00:01Z',
        },
      ],
    )

    expect(timeline.messages).toEqual([])
  })

  it('deduplicates realtime streaming tool-call drafts against the durable tool_call message', () => {
    // 活跃 attempt 的流式 toolCall draft 与已物化的 durable assistant tool_call
    // 拥有相同 toolCallId：draft 不得产生第二条 tool call 消息（L244-250 跳过分支）。
    const timeline = buildThreadTimeline(
      [
        entry(
          'assistant-1',
          'MESSAGE',
          messagePayload('ASSISTANT', [
            {
              type: 'tool_call',
              toolCallId: 'call-dedup',
              toolName: 'bash',
              rendererKey: 'bash',
              argumentsJson: '{"command":"ls"}',
            },
          ]),
        ),
      ],
      [],
      [],
      {
        threadId: 't1',
        invocationId: 'm1',
        attempt: 1,
        sequence: 3,
        text: '',
        thinking: '',
        toolCalls: [
          { index: 0, id: 'call-dedup', name: 'bash', argumentsJson: '{"command":"ls"}' },
        ],
        createdAt: '2026-01-01T00:00:00',
        status: 'streaming',
      },
    )
    const toolCalls = timeline.messages.filter(
      (message) => message.role === 'tool' && message.phase === 'call',
    )
    expect(toolCalls).toHaveLength(1)
    expect(toolCalls[0]).toMatchObject({
      subjectEntryId: 'assistant-1',
      toolCallId: 'call-dedup',
      status: 'done',
    })
    // 不同 id 的 draft 仍然会追加（existing 集合按 toolCallId 精确匹配）。
    const extra = buildThreadTimeline(
      [],
      [],
      [],
      {
        threadId: 't1',
        invocationId: 'm1',
        attempt: 1,
        sequence: 3,
        text: '',
        thinking: '',
        toolCalls: [
          { index: 0, id: 'call-new', name: 'write', argumentsJson: '{}' },
        ],
        createdAt: '2026-01-01T00:00:00',
        status: 'streaming',
      },
    )
    expect(
      extra.messages.filter((message) => message.role === 'tool' && message.phase === 'call'),
    ).toHaveLength(1)
  })

  it('skips overlay projection for non-tool-call messages and malformed approvals', () => {
    // durable tool result 消息（phase 'result'）不在 invocation overlay 匹配范围
    // （L313-314 只处理 phase 'call' 的 tool 消息），不会因此抛出或篡改。
    const invocation: ToolInvocationDTO = {
      id: 'inv-1',
      modelInvocationId: 'm-1',
      assistantEntryId: 'assistant-1',
      ordinal: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'bash',
      toolVersion: '1',
      rendererKey: 'bash',
      toolType: 'shell',
      environment: null,
      argumentsJson: '{"command":"ls"}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      createTime: '2026-01-01T00:00:00',
      updateTime: '2026-01-01T00:00:00',
    }
    const timeline = buildThreadTimeline(
      [
        entry(
          'assistant-1',
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
          'tool-1',
          'MESSAGE',
          messagePayload('TOOL', [
            {
              type: 'tool_result',
              toolCallId: 'call-1',
              toolName: 'bash',
              rendererKey: 'bash',
              contents: [{ type: 'text', text: 'ok' }],
            },
          ]),
        ),
      ],
      [],
      [invocation],
      null,
      new Map<string, RealtimeToolStream>([
        ['inv-1', {
          threadId: 't1',
          invocationId: 'inv-1',
          attempt: 1,
          toolCallId: 'call-1',
          text: 'partial',
          error: false,
          createdAt: '2026-01-01T00:00:00',
        }],
      ]),
    )
    // 只有 phase 'call' 的 tool 消息收到 invocationId/partial；result 消息保持原样。
    const result = timeline.messages.find(
      (message) => message.role === 'tool' && message.phase === 'result',
    )
    expect(result).toMatchObject({ toolCallId: 'call-1', text: 'ok' })
    expect(result).not.toHaveProperty('invocationId')

    // parseApproval 的防御分支：null / 非法 JSON / 非 record 都返回 null。
    expect(parseApproval(null)).toBeNull()
    expect(parseApproval('not-json')).toBeNull()
    expect(parseApproval('[1]')).toBeNull()
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
  label: string,
  sequence: string,
  type: 'USER_MESSAGE' | 'CUSTOM_MESSAGE',
  payload: Record<string, unknown>,
  state: 'QUEUED' | 'APPLIED',
): HarnessThreadCommandDTO {
  return {
    threadId: 't1',
    sequence,
    type,
    state,
    clientCommandId: `cid-${label}`,
    requestHash: '0123456789abcdef'.repeat(4),
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

function customMessagePayload(role: 'SYSTEM' | 'USER', text: string) {
  return { message: { role, contents: [{ type: 'text', text }] } }
}
