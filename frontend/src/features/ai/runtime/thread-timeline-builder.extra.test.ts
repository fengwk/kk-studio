import { describe, expect, it } from 'vitest'
import type {
  EntryType,
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
} from '@/shared/api/contracts/ai-runtime'
import { buildThreadTimeline } from '@/features/ai/runtime/thread-timeline-builder'

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

  it('does not emit any dialogue message for TURN_START / TURN_END even if user-visible content sneaks in', () => {
    const timeline = buildThreadTimeline(
      [
        entry('turn-start', 'TURN_START', { reason: 'USER_MESSAGE', settings: {} }),
        entry('turn-end', 'TURN_END', {
          turnStartEntryId: 'turn-start',
          outcome: 'COMPLETED',
          continueModel: false,
          reason: 'no-continuation',
          closeRequestId: null,
        }),
      ],
      [],
      [],
    )
    expect(timeline.messages).toEqual([])
    expect(timeline.queuedMessages).toEqual([])
    expect(timeline.hasPendingInputs).toBe(false)
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

function customMessagePayload(role: 'SYSTEM' | 'USER', text: string) {
  return { message: { role, contents: [{ type: 'text', text }] } }
}
