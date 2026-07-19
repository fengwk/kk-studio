import { describe, expect, it } from 'vitest'
import type { HarnessSessionEntryDTO, HarnessThreadInputDTO, ThreadEventDTO } from '@/shared/api/contracts'
import { buildThreadTimeline } from '@/features/ai/thread-timeline-builder'

describe('thread timeline edge branches', () => {
  it('projects compaction, system messages, empty assistant failure, and artifacts', () => {
    const timeline = buildThreadTimeline(
      [
        entry('1', 'compaction', { summary: '压缩摘要' }),
        entry('2', 'message', messagePayload('SYSTEM', [{ type: 'text', text: '系统提示' }])),
        entry('3', 'message', messagePayload('USER', [])),
        entry('4', 'label', { label: 'ignored' }),
        entry(
          '5',
          'message',
          messagePayload('ASSISTANT', [
            { type: 'tool_call', toolCallId: 'call-a', argumentsJson: '{"q":1}' },
            { type: 'text', text: '调用工具' },
          ]),
        ),
        entry(
          '6',
          'message',
          messagePayload('TOOL', [
            {
              type: 'tool_result',
              toolCallId: 'call-a',
              toolName: 'search',
              error: true,
              contents: [
                { type: 'text', text: 'fail' },
                { type: 'json', json: '{"x":1}' },
                { type: 'artifact', artifactId: 'art-1', mediaType: 'image/png' },
                { type: 'artifact', artifactId: 'art-2', mediaType: 'audio/wav' },
                { type: 'artifact', artifactId: 'art-3', mediaType: 'video/mp4' },
                { type: 'artifact', artifactId: 'art-4', mediaType: 'application/pdf' },
                { type: 'artifact', mediaType: 'image/png' },
              ],
            },
          ]),
        ),
      ],
      [input('set', 'set_yolo', { yoloEnabled: true }, null)],
      [
        threadEvent('1', 'assistant_failed', null, { message: 'boom' }),
        threadEvent('2', 'tool_started', '7', { invocationId: 'missing' }),
        threadEvent('3', 'tool_delta_batch', '7', { partialResults: [{ toolCallId: 'missing' }] }),
        threadEvent('4', 'tool_completed', '7', { invocationId: 'missing' }),
        threadEvent('5', 'assistant_started', '8', {}),
        threadEvent('6', 'assistant_delta_batch', '8', { deltas: [{ kind: 'other', text: 'skip' }] }),
        threadEvent('7', 'assistant_delta_batch', '8', { deltas: [{ kind: 'text', text: '' }] }),
        threadEvent('8', 'assistant_delta_batch', '8', {
          deltas: [{ kind: 'thinking', text: '' }, { kind: 'thinking', text: 't' }],
        }),
        threadEvent('9', 'assistant_completed', '8', {}),
        threadEvent('10', 'tool_prepared', '8', { toolCallId: '', toolName: 'x' }),
        threadEvent('11', 'tool_prepared', '8', {
          toolCallId: 'c1',
          toolName: '',
          argumentsJson: '{}',
          invocationId: 'inv-1',
        }),
        threadEvent('12', 'tool_started', '8', { invocationId: 'inv-1' }),
        threadEvent('13', 'tool_delta_batch', '8', {
          invocationId: 'inv-1',
          partialResults: [
            {
              toolCallId: 'c1',
              error: true,
              contents: [{ type: 'text', text: 'partial' }, { type: 'artifact', artifactId: 'p1', mediaType: 'text/plain' }],
            },
          ],
        }),
        threadEvent('14', 'tool_completed', '8', { invocationId: 'inv-1', error: true }),
      ],
    )

    expect(timeline.messages.some((m) => m.role === 'system' && m.text === '压缩摘要')).toBe(true)
    expect(timeline.messages.some((m) => m.role === 'system' && m.text === '系统提示')).toBe(true)
    expect(timeline.messages.some((m) => m.role === 'assistant' && String(m.text).includes('boom'))).toBe(true)
    expect(timeline.messages.some((m) => m.role === 'tool' && m.status === 'error')).toBe(true)
    expect(timeline.messages.some((m) => m.role === 'tool' && m.attachments.length > 0)).toBe(true)
    expect(timeline.hasLiveProjection).toBe(false)
    expect(timeline.hasPendingInputs).toBe(false)
    expect(timeline.queuedMessages).toEqual([])
  })

  it('keeps pending input after applied marker is absent and ignores blank input text', () => {
    const timeline = buildThreadTimeline(
      [],
      [
        input('blank', 'user_message', messagePayload('USER', [{ type: 'text', text: '' }]), null),
        input('ok', 'user_message', messagePayload('USER', [{ type: 'text', text: '可见' }]), null),
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
        entry('custom-system', 'custom_message', messagePayload('SYSTEM', [{ type: 'text', text: 'durable system' }])),
        entry('custom-user', 'custom_message', messagePayload('USER', [{ type: 'text', text: 'durable user' }])),
      ],
      [
        input('queued-system', 'CUSTOM_MESSAGE', messagePayload('SYSTEM', [{ type: 'text', text: 'queued system' }]), null),
        input('queued-user', 'CUSTOM_MESSAGE', messagePayload('USER', [{ type: 'text', text: 'queued user' }]), null),
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
    sequence: 1,
    inputType,
    payloadJson: JSON.stringify(payload),
    clientMessageId: `cid-${inputId}`,
    status: 'QUEUED',
    appliedEntryId,
    resolvedAt: null,
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
