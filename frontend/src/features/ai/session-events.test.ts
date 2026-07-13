import { describe, expect, it } from 'vitest'
import type { AgentRunDTO, AgentSessionEventDTO } from '@/shared/api/contracts'
import { buildSessionTimeline, hasActiveRun } from '@/features/ai/session-events'

describe('session-events', () => {
  it('projects backend events into user and assistant messages', () => {
    const events: AgentSessionEventDTO[] = [
      event('e1', 'user_message', { content: 'hello' }, 'run-1'),
      event('e2', 'set_agent_info', { agentName: 'default-assistant' }, 'run-1'),
      event('e3', 'set_model_info', { provider: 'minimax', model: 'MiniMax-M2.7', variant: 'default' }, 'run-1'),
      event('e4', 'assistant_start', {}, 'run-1'),
      event('e5', 'assistant_delta', { textDelta: 'hi ' }, 'run-1'),
      event('e6', 'assistant_delta', { textDelta: 'there' }, 'run-1'),
      event('e7', 'assistant_end', { metadata: { finishReason: 'stop' } }, 'run-1'),
    ]

    const timeline = buildSessionTimeline(events)

    expect(timeline.runtimeContext).toEqual({
      agentName: 'default-assistant',
      provider: 'minimax',
      model: 'MiniMax-M2.7',
      variant: 'default',
    })
    expect(timeline.messages).toHaveLength(2)
    expect(timeline.messages[0]).toMatchObject({ role: 'user', text: 'hello', status: 'done' })
    expect(timeline.messages[1]).toMatchObject({ role: 'assistant', text: 'hi there', status: 'done' })
  })

  it('renders assistant errors as failed assistant messages', () => {
    const timeline = buildSessionTimeline([event('e1', 'assistant_error', { message: 'provider failed' }, 'run-1')])

    expect(timeline.messages[0]).toMatchObject({ role: 'assistant', text: 'provider failed', status: 'error' })
  })

  it('keeps thinking deltas out of user-visible assistant text', () => {
    const timeline = buildSessionTimeline([
      event('e1', 'assistant_start', {}, 'run-1'),
      event('e2', 'assistant_delta', { thinkingDelta: 'internal reasoning' }, 'run-1'),
      event('e3', 'assistant_delta', { textDelta: 'final answer' }, 'run-1'),
    ])

    expect(timeline.messages[0]).toMatchObject({ role: 'assistant', text: 'final answer', status: 'streaming' })
  })

  it('splits think tags out of streamed assistant text across chunk boundaries', () => {
    const timeline = buildSessionTimeline([
      event('e1', 'assistant_start', {}, 'run-1'),
      event('e2', 'assistant_delta', { textDelta: '<thi' }, 'run-1'),
      event('e3', 'assistant_delta', { textDelta: 'nk>draft plan' }, 'run-1'),
      event('e4', 'assistant_delta', { textDelta: '</th' }, 'run-1'),
      event('e5', 'assistant_delta', { textDelta: 'ink>\n\nfinal answer' }, 'run-1'),
      event('e6', 'assistant_end', {}, 'run-1'),
    ])

    expect(timeline.messages).toHaveLength(1)
    expect(timeline.messages[0]).toMatchObject({ role: 'assistant', text: 'final answer', status: 'done' })
  })

  it('keeps assistant attempts separate even inside the same run', () => {
    const timeline = buildSessionTimeline([
      event('e1', 'assistant_start', {}, 'run-1'),
      event('e2', 'assistant_delta', { textDelta: 'first answer' }, 'run-1'),
      event('e3', 'assistant_end', {}, 'run-1'),
      event('e4', 'assistant_start', {}, 'run-1'),
      event('e5', 'assistant_delta', { textDelta: 'second answer' }, 'run-1'),
      event('e6', 'assistant_end', {}, 'run-1'),
    ])

    expect(timeline.messages).toHaveLength(2)
    expect(timeline.messages[0]).toMatchObject({ role: 'assistant', text: 'first answer', status: 'done' })
    expect(timeline.messages[1]).toMatchObject({ role: 'assistant', text: 'second answer', status: 'done' })
  })

  it('drops assistant attempts that never produce visible text', () => {
    const timeline = buildSessionTimeline([
      event('e1', 'assistant_start', {}, 'run-1'),
      event('e2', 'assistant_delta', { thinkingDelta: 'hidden reasoning' }, 'run-1'),
      event('e3', 'assistant_end', {}, 'run-1'),
    ])

    expect(timeline.messages).toEqual([])
  })

  it('projects tool lifecycle events into visible tool messages', () => {
    // Tool execution must remain observable in the transcript, including arguments and streamed output.
    const timeline = buildSessionTimeline([
      event('e1', 'assistant_start', {}, 'run-1'),
      event('e2', 'assistant_delta', { textDelta: '我先查一下。' }, 'run-1'),
      event('e3', 'tool_start', { toolCallId: 'tool-1', toolName: 'web_search', arguments: '{"q":"上海天气"}' }, 'run-1'),
      event('e4', 'tool_delta', { toolCallId: 'tool-1', contentDeltas: [{ index: 0, contentDelta: { type: 'text', text: '晴 32C' } }] }, 'run-1'),
      event('e5', 'tool_end', { toolCallId: 'tool-1' }, 'run-1'),
      event('e6', 'assistant_end', {}, 'run-1'),
    ])

    expect(timeline.messages).toHaveLength(2)
    expect(timeline.messages[0]).toMatchObject({ role: 'assistant', text: '我先查一下。', status: 'done' })
    expect(timeline.messages[1]).toMatchObject({
      role: 'tool',
      toolCallId: 'tool-1',
      toolName: 'web_search',
      arguments: '{"q":"上海天气"}',
      text: '晴 32C',
      status: 'done',
    })
  })

  it('preserves tool media attachments for later rendering', () => {
    // Mixed text/media tool deltas must keep ordered attachments so the UI can render actual previews.
    const timeline = buildSessionTimeline([
      event('e1', 'tool_start', { toolCallId: 'tool-3', toolName: 'media_tool', arguments: '{}' }, 'run-1'),
      event(
        'e2',
        'tool_delta',
        {
          toolCallId: 'tool-3',
          contentDeltas: [
            { index: 0, contentDelta: { type: 'text', text: '生成完成' } },
            { index: 1, contentDelta: { type: 'image', name: 'cover.png', mime: 'image/png', data: 'aW1n' } },
            { index: 2, contentDelta: { type: 'audio', name: 'preview.mp3', mime: 'audio/mpeg', data: 'YXVkaW8=' } },
          ],
        },
        'run-1',
      ),
      event('e3', 'tool_end', { toolCallId: 'tool-3' }, 'run-1'),
    ])

    expect(timeline.messages).toHaveLength(1)
    expect(timeline.messages[0]).toMatchObject({
      role: 'tool',
      toolName: 'media_tool',
      text: '生成完成',
      status: 'done',
      attachments: [
        { type: 'image', name: 'cover.png', mime: 'image/png', data: 'aW1n' },
        { type: 'audio', name: 'preview.mp3', mime: 'audio/mpeg', data: 'YXVkaW8=' },
      ],
    })
  })

  it('keeps tool failures visible even when the tool never emitted output', () => {
    // Error-only tool paths should still produce a readable transcript node instead of disappearing.
    const timeline = buildSessionTimeline([
      event('e1', 'tool_error', { toolCallId: 'tool-2', message: 'tool timed out' }, 'run-1'),
    ])

    expect(timeline.messages).toHaveLength(1)
    expect(timeline.messages[0]).toMatchObject({
      role: 'tool',
      toolCallId: 'tool-2',
      toolName: 'Tool',
      text: 'tool timed out',
      errorMessage: 'tool timed out',
      status: 'error',
    })
  })

  it('detects active runs', () => {
    expect(hasActiveRun([run('queued')])).toBe(true)
    expect(hasActiveRun([run('running')])).toBe(true)
    expect(hasActiveRun([run('succeeded')])).toBe(false)
    expect(hasActiveRun([run('failed')])).toBe(false)
  })

  it('keeps timeline stable for missing payloads and fallback values', () => {
    const timeline = buildSessionTimeline([
      rawEvent('e1', 'user_message', null, null),
      rawEvent('e2', 'set_model_info', '{"provider":"minimax","model":"MiniMax-M2.7"}', 'run-1'),
      rawEvent('e3', 'set_model_info', '{"provider":123,"variant":"chat"}', 'run-1'),
      rawEvent('e4', 'assistant_start', '{}', null),
      rawEvent('e4', 'assistant_delta', '{"textDelta":123}', null),
      rawEvent('e5', 'assistant_error', '{}', 'run-error'),
      rawEvent('e6', 'assistant_end', '{"metadata":["not-record"]}', 'run-end'),
      rawEvent('e7', 'unknown_event', '{}', null),
    ])

    expect(timeline.runtimeContext).toEqual({ provider: 'minimax', model: 'MiniMax-M2.7', variant: 'chat' })
    expect(timeline.messages[0]).toMatchObject({ role: 'user', runId: null, text: '' })
    expect(timeline.messages[1]).toMatchObject({ role: 'assistant', text: 'Assistant failed', status: 'error' })
  })


  it('captures thinking deltas emitted alongside visible assistant text', () => {
    const timeline = buildSessionTimeline([
      event("e1", "assistant_start", {}, "run-1"),
      event("e2", "assistant_delta", { textDelta: "final " }, "run-1"),
      event("e3", "assistant_delta", { thinkingDelta: "verify " }, "run-1"),
      event("e4", "assistant_delta", { textDelta: "answer" }, "run-1"),
      event("e5", "assistant_delta", { thinkingDelta: "more thinking" }, "run-1"),
      event("e6", "assistant_end", {}, "run-1"),
    ])

    expect(timeline.messages).toHaveLength(1)
    expect(timeline.messages[0]).toMatchObject({
      role: "assistant",
      text: "final answer",
      thinking: "verify more thinking",
      status: "done",
    })
  })


  it('flushes thinking that arrives before the first text delta', () => {
    // Providers like MiniMax reasoning models stream thinking deltas first and
    // only start emitting text once the model commits to an answer. The early
    // thinking must still be preserved on the resulting message.
    const timeline = buildSessionTimeline([
      event("e1", "assistant_start", {}, "run-1"),
      event("e2", "assistant_delta", { thinkingDelta: "step 1 " }, "run-1"),
      event("e3", "assistant_delta", { thinkingDelta: "step 2 " }, "run-1"),
      event("e4", "assistant_delta", { textDelta: "final answer" }, "run-1"),
      event("e5", "assistant_delta", { thinkingDelta: "verify" }, "run-1"),
      event("e6", "assistant_end", {}, "run-1"),
    ])

    expect(timeline.messages).toHaveLength(1)
    expect(timeline.messages[0]).toMatchObject({
      role: "assistant",
      text: "final answer",
      thinking: "step 1 step 2 verify",
      status: "done",
    })
  })
})

function event(eventId: string, eventType: string, payload: Record<string, unknown>, runId: string | null): AgentSessionEventDTO {
  return rawEvent(eventId, eventType, JSON.stringify(payload), runId)
}

function rawEvent(eventId: string, eventType: string, payloadJson: string | null, runId: string | null): AgentSessionEventDTO {
  return {
    eventId,
    sessionId: 'session-1',
    parentEventId: 'root',
    runId,
    eventType,
    payloadJson,
    createTime: '2026-06-20T02:00:00',
  }
}


function run(status: string): AgentRunDTO {
  return {
    runId: `run-${status}`,
    sessionId: 'session-1',
    triggerEventId: 'event-1',
    status,
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:01:00',
  }
}
