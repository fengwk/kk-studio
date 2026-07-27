import { describe, expect, it } from 'vitest'
import type {
  EntryType,
  HarnessSessionEntryDTO,
  HarnessThreadInputDTO,
  ThreadInputType,
} from '@/shared/api/contracts'
import { buildThreadTimeline } from '@/features/ai/thread-timeline-builder'

describe('thread timeline edge branches', () => {
  it('projects system messages, empty user content, tool artifacts, and assistant errors', () => {
    const timeline = buildThreadTimeline(
      [
        entry('2', 'MESSAGE', messagePayload('SYSTEM', [{ type: 'text', text: '系统提示' }])),
        entry('3', 'MESSAGE', messagePayload('USER', [])),
        entry(
          '5',
          'MESSAGE',
          messagePayload('ASSISTANT', [
            { type: 'tool_call', toolCallId: 'call-a', toolName: 'search', argumentsJson: '{"q":1}' },
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
              error: true,
              detailsJson: '{"code":"FAILED"}',
              contents: [
                { type: 'text', text: 'fail' },
                { type: 'json', json: '{"x":1}' },
                { type: 'artifact', artifactId: 'art-1', mediaType: 'image/png', preview: null },
                { type: 'artifact', artifactId: 'art-2', mediaType: 'audio/wav', preview: null },
                { type: 'artifact', artifactId: 'art-3', mediaType: 'video/mp4', preview: null },
                { type: 'artifact', artifactId: 'art-4', mediaType: 'application/pdf', preview: null },
              ],
            },
          ]),
        ),
        entry('7', 'ASSISTANT_ERROR', { error: { kind: 'TRANSIENT', message: 'boom' } }),
      ],
      [input('set', 'SET_YOLO', { yoloEnabled: true }, false)],
    )

    expect(timeline.messages.some((m) => m.role === 'system' && m.text === '系统提示')).toBe(true)
    expect(timeline.messages.some((m) => m.role === 'assistant' && String(m.text).includes('boom'))).toBe(true)
    expect(timeline.messages.some((m) => m.role === 'tool' && m.status === 'error')).toBe(true)
    expect(timeline.messages.some((m) => m.role === 'tool' && m.attachments.length > 0)).toBe(true)
    expect(timeline.hasPendingInputs).toBe(false)
    expect(timeline.queuedMessages).toEqual([])
  })

  it('keeps pending input after applied marker is absent and ignores blank input text', () => {
    const timeline = buildThreadTimeline(
      [],
      [
        input('blank', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: '' }]), false),
        input('ok', 'USER_MESSAGE', messagePayload('USER', [{ type: 'text', text: '可见' }]), false),
      ],
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
        input('queued-system', 'CUSTOM_MESSAGE', customMessagePayload('SYSTEM', 'queued system'), false),
        input('queued-user', 'CUSTOM_MESSAGE', customMessagePayload('USER', 'queued user'), false),
      ],
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

  it('projects turn usage from durable assistantMetadata', () => {
    const assistantPayload = {
      message: {
        role: 'ASSISTANT',
        contents: [{ type: 'text', text: 'a1' }],
      },
      assistantMetadata: {
        stopReason: 'COMPLETED',
        usage: {
          inputTokens: 100,
          outputTokens: 50,
          cacheReadTokens: 0,
          cacheWriteTokens: 0,
          cacheWriteLongTokens: 0,
          reasoningTokens: 0,
          providerTotalTokens: 150,
        },
        cost: {
          currency: 'USD',
          input: '0.01',
          output: '0.002',
          cacheRead: '0',
          cacheWrite: '0',
          cacheWriteLong: '0',
          reasoning: '0',
          total: '0.012',
        },
      },
    }
    const timeline = buildThreadTimeline(
      [
        entry('10', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'q1' }])),
        entry('11', 'MESSAGE', assistantPayload),
        entry('12', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'q2' }])),
        entry('13', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: 'a2' }])),
      ],
      [],
    )
    const roles = timeline.messages.map((m) =>
      m.role === 'meta' ? `meta:${m.kind}` : `${m.role}:${'text' in m ? m.text : ''}`,
    )
    expect(roles).toEqual([
      'user:q1',
      'assistant:a1',
      'meta:turn_usage',
      'user:q2',
      'assistant:a2',
    ])
    expect(timeline.messages[2]?.text).toContain('$0.012')
  })

  it('projects durable usage for a tool-call-only assistant entry', () => {
    const payload = messagePayload('ASSISTANT', [
      { type: 'tool_call', toolCallId: 'call-only', toolName: 'read', argumentsJson: '{"path":"README.md"}' },
    ])
    payload.assistantMetadata = {
      ...payload.assistantMetadata!,
      usage: { ...payload.assistantMetadata!.usage, inputTokens: 10, outputTokens: 2, providerTotalTokens: 12 },
      cost: { ...payload.assistantMetadata!.cost, total: '0.001' },
    }

    const timeline = buildThreadTimeline([entry('tool-only', 'MESSAGE', payload)], [])

    expect(timeline.messages).toMatchObject([
      { role: 'meta', kind: 'turn_usage', subjectEntryId: 'tool-only' },
    ])
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
    sequence: 1,
    inputType,
    payloadJson: JSON.stringify(payload),
    clientMessageId: `cid-${inputId}`,
    status: applied ? 'APPLIED' : 'QUEUED',
    resolvedAt: null,
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

function customMessagePayload(role: 'SYSTEM' | 'USER', text: string) {
  return { message: { role, contents: [{ type: 'text', text }] } }
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
