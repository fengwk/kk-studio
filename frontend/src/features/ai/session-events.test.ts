import { describe, expect, it } from 'vitest'
import type { HarnessRunDTO, HarnessSessionEntryDTO, RunEventDTO } from '@/shared/api/contracts'
import { buildSessionTimeline, hasActiveRun } from '@/features/ai/session-events'

describe('session-events', () => {
  it('projects durable entries as the transcript baseline', () => {
    const timeline = buildSessionTimeline([
      entry('snapshot', 'agent_snapshot', { snapshot: { modelId: 'MiniMax-M2.7', variant: 'default' } }),
      entry('user', 'message', messagePayload('USER', [{ type: 'text', text: '检查大纲' }])),
      entry('assistant', 'message', messagePayload('ASSISTANT', [
        { type: 'thinking', text: '先梳理结构。' },
        { type: 'text', text: '结构完整。' },
      ])),
    ], [])

    expect(timeline.runtimeContext).toEqual({ model: 'MiniMax-M2.7', variant: 'default' })
    expect(timeline.messages).toMatchObject([
      { role: 'user', text: '检查大纲', status: 'done' },
      { role: 'assistant', text: '结构完整。', thinking: '先梳理结构。', status: 'done' },
    ])
  })

  it('uses active run events only for the live assistant projection', () => {
    const timeline = buildSessionTimeline([], [
      runEvent('started', 'assistant_started', {}),
      runEvent('delta-1', 'assistant_delta_batch', { deltas: [{ kind: 'thinking', text: 'draft ' }, { kind: 'text', text: '<think>hidden</think>final ' }] }),
      runEvent('delta-2', 'assistant_delta_batch', { deltas: [{ kind: 'text', text: 'answer' }] }),
    ])

    expect(timeline.messages).toMatchObject([
      { role: 'assistant', text: 'final answer', thinking: 'draft ', status: 'streaming' },
    ])
  })

  it('does not duplicate an Assistant that has already materialized as a durable Entry', () => {
    const timeline = buildSessionTimeline([
      entry('assistant', 'message', messagePayload('ASSISTANT', [{ type: 'text', text: '已持久化回答' }])),
    ], [
      runEvent('started', 'assistant_started', {}),
      runEvent('old-delta', 'assistant_delta_batch', { deltas: [{ kind: 'text', text: '已持久化回答' }] }),
      runEvent('completed', 'assistant_completed', {}),
      runEvent('next-started', 'assistant_started', {}),
      runEvent('next-delta', 'assistant_delta_batch', { deltas: [{ kind: 'text', text: '新的实时回答' }] }),
    ])

    expect(timeline.messages).toMatchObject([
      { role: 'assistant', text: '已持久化回答', status: 'done' },
      { role: 'assistant', text: '新的实时回答', status: 'streaming' },
    ])
  })

  it('keeps a streamed Assistant when completion arrives before durable Entry materialization', () => {
    const timeline = buildSessionTimeline([], [
      runEvent('started', 'assistant_started', {}),
      runEvent('delta', 'assistant_delta_batch', { deltas: [{ kind: 'text', text: '尚未落库' }] }),
      runEvent('completed', 'assistant_completed', {}),
    ])

    expect(timeline.messages).toMatchObject([
      { role: 'assistant', text: '尚未落库', status: 'done' },
    ])
  })

  it('dedupes the streamed Assistant once the durable Entry arrives', () => {
    const events = [
      runEvent('started', 'assistant_started', {}),
      runEvent('delta', 'assistant_delta_batch', { deltas: [{ kind: 'text', text: '最终回答' }] }),
      runEvent('completed', 'assistant_completed', {}),
    ]
    const before = buildSessionTimeline([], events)
    expect(before.messages).toMatchObject([{ role: 'assistant', text: '最终回答', status: 'done' }])

    const after = buildSessionTimeline([
      entry('assistant', 'message', messagePayload('ASSISTANT', [{ type: 'text', text: '最终回答' }])),
    ], events)
    expect(after.messages).toMatchObject([
      { role: 'assistant', text: '最终回答', status: 'done' },
    ])
    expect(after.messages).toHaveLength(1)
  })

  it('does not consume a durable Assistant match on an earlier failed retry attempt', () => {
    const timeline = buildSessionTimeline([
      entry('assistant', 'message', messagePayload('ASSISTANT', [{ type: 'text', text: '最终成功' }])),
    ], [
      runEvent('attempt-1-start', 'assistant_started', {}, 1),
      runEvent('attempt-1-delta', 'assistant_delta_batch', { deltas: [{ kind: 'text', text: '旧半成品' }] }, 2),
      runEvent('attempt-1-failed', 'assistant_failed', { message: 'retrying' }, 3),
      runEvent('attempt-2-start', 'assistant_started', {}, 4),
      runEvent('attempt-2-delta', 'assistant_delta_batch', { deltas: [{ kind: 'text', text: '最终成功' }] }, 5),
      runEvent('attempt-2-completed', 'assistant_completed', {}, 6),
    ])

    expect(timeline.messages).toMatchObject([
      { role: 'assistant', text: '最终成功', status: 'done' },
    ])
    expect(timeline.messages).toHaveLength(1)
  })

  it('keeps failed terminal run details from persisted Run Events without a live stream', () => {
    const timeline = buildSessionTimeline([
      entry('user', 'message', messagePayload('USER', [{ type: 'text', text: '继续' }])),
    ], [
      runEvent('started', 'assistant_started', {}),
      runEvent('partial', 'assistant_delta_batch', { deltas: [{ kind: 'text', text: '半成品' }] }),
      runEvent('failed', 'assistant_failed', { message: 'provider timeout' }),
      runEvent('run-failed', 'run_failed', { message: 'run failed' }),
    ])

    expect(timeline.messages).toMatchObject([
      { role: 'user', text: '继续' },
      { role: 'assistant', text: '半成品\nprovider timeout', status: 'error' },
    ])
  })

  it('projects durable system, compaction, error and media variants while ignoring non-dialogue Entries', () => {
    const timeline = buildSessionTimeline([
      entry('compaction', 'compaction', { summary: '上下文已压缩' }),
      entry('empty-compaction', 'compaction', { summary: '' }),
      entry('system', 'message', messagePayload('SYSTEM', [{ type: 'json', json: '{"mode":"review"}' }])),
      entry('assistant-thinking', 'message', messagePayload('ASSISTANT', [{ type: 'thinking', text: '仅思考' }])),
      entry('tool-result', 'message', messagePayload('TOOL', [{
        type: 'tool_result',
        toolCallId: 'call-2',
        toolName: 'media_tool',
        contents: [
          { type: 'artifact', artifactId: 'audio-1', mediaType: 'audio/mpeg', preview: null },
          { type: 'artifact', artifactId: 'video-1', mediaType: 'video/mp4', preview: null },
          { type: 'artifact', artifactId: 'binary-1', mediaType: 'application/octet-stream', preview: null },
        ],
        error: true,
        detailsJson: '{}',
      }])),
      entry('label', 'label', { label: 'ignored' }),
    ], [])

    expect(timeline.messages).toMatchObject([
      { role: 'system', text: '上下文已压缩' },
      { role: 'system', text: '{"mode":"review"}' },
      { role: 'assistant', text: '', thinking: '仅思考' },
      {
        role: 'tool',
        toolCallId: 'call-2',
        status: 'error',
        errorMessage: '工具执行失败。',
        attachments: [
          { type: 'audio', data: '/api/artifacts/audio-1' },
          { type: 'video', data: '/api/artifacts/video-1' },
          { type: 'file', mime: 'application/octet-stream', data: '/api/artifacts/binary-1' },
        ],
      },
    ])
  })

  it('marks streamed assistant failures with and without a started attempt', () => {
    const absent = { ...runEvent('absent', 'assistant_failed', { message: 'provider unavailable' }), runId: 'other' }
    const timeline = buildSessionTimeline([], [
      runEvent('started', 'assistant_started', {}),
      runEvent('partial', 'assistant_delta_batch', { deltas: [{ kind: 'text', text: 'partial' }, { kind: 'unknown', text: 'ignored' }] }),
      runEvent('failed', 'assistant_failed', { message: 'network failed' }),
      absent,
    ])

    expect(timeline.messages).toMatchObject([
      { role: 'assistant', text: 'partial\nnetwork failed', status: 'error' },
      { role: 'assistant', text: 'provider unavailable', status: 'error' },
    ])
  })

  it('retains valid live tool output while ignoring malformed or duplicate tool events', () => {
    const timeline = buildSessionTimeline([], [
      runEvent('missing-call', 'tool_prepared', { toolName: 'ignored' }),
      runEvent('prepared', 'tool_prepared', { toolCallId: 'call-3', toolName: '', arguments: '{}' }),
      runEvent('duplicate', 'tool_prepared', { toolCallId: 'call-3', toolName: 'changed' }),
      runEvent('unknown-start', 'tool_started', { toolCallId: 'unknown' }),
      runEvent('unknown-partial', 'tool_delta_batch', { partialResults: [{ toolCallId: 'unknown', contents: [] }] }),
      runEvent('partial', 'tool_delta_batch', {
        partialResults: [{
          toolCallId: 'call-3',
          contents: [
            { type: 'text', text: 'first' },
            { type: 'text', text: 'second' },
            { type: 'artifact', artifactId: 'image-1', mediaType: 'image/png' },
          ],
          error: true,
        }],
      }),
      runEvent('completed', 'tool_completed', { toolCallId: 'call-3', error: true }),
    ])

    expect(timeline.messages).toMatchObject([{
      role: 'tool',
      toolCallId: 'call-3',
      toolName: 'Tool',
      text: 'first\nsecond',
      errorMessage: '工具执行失败。',
      status: 'error',
      attachments: [{ type: 'image', data: '/api/artifacts/image-1' }],
    }])
  })

  it('projects durable tool results and resolves persisted artifacts through the artifact endpoint', () => {
    const timeline = buildSessionTimeline([
      entry('tool-result', 'message', messagePayload('TOOL', [{
        type: 'tool_result',
        toolCallId: 'call-1',
        toolName: 'image_tool',
        contents: [{ type: 'text', text: '生成完成' }, { type: 'artifact', artifactId: '9001', mediaType: 'image/png', preview: null }],
        error: false,
        detailsJson: '{}',
      }])),
    ], [])

    expect(timeline.messages).toMatchObject([{
      role: 'tool',
      toolCallId: 'call-1',
      toolName: 'image_tool',
      text: '生成完成',
      status: 'done',
      attachments: [{ type: 'image', mime: 'image/png', data: '/api/artifacts/9001' }],
    }])
  })

  it('carries durable Assistant tool-call arguments into the matching Tool result', () => {
    const timeline = buildSessionTimeline([
      entry('assistant-call', 'message', messagePayload('ASSISTANT', [{
        type: 'tool_call',
        toolCallId: 'call-1',
        toolName: 'read',
        argumentsJson: '{"path":"README.md"}',
      }])),
      entry('tool-result', 'message', messagePayload('TOOL', [{
        type: 'tool_result',
        toolCallId: 'call-1',
        toolName: 'read',
        contents: [{ type: 'text', text: 'done' }],
        error: false,
        detailsJson: '{}',
      }])),
    ], [])

    expect(timeline.messages).toMatchObject([
      { role: 'tool', toolCallId: 'call-1', arguments: '{"path":"README.md"}', text: 'done' },
    ])
  })

  it('does not duplicate a tool cycle that has already materialized as a durable result Entry', () => {
    const timeline = buildSessionTimeline([
      entry('tool-result', 'message', messagePayload('TOOL', [{
        type: 'tool_result',
        toolCallId: 'call-1',
        toolName: 'read',
        contents: [{ type: 'text', text: 'durable result' }],
        error: false,
        detailsJson: '{}',
      }])),
    ], [
      runEvent('prepared', 'tool_prepared', { invocationId: 'inv-1', toolCallId: 'call-1', toolName: 'read', arguments: '{}' }),
      runEvent('started', 'tool_started', { invocationId: 'inv-1', toolCallId: 'call-1' }),
      runEvent('completed', 'tool_completed', { invocationId: 'inv-1', status: 'SUCCEEDED', error: false }),
    ])

    expect(timeline.messages).toMatchObject([
      { role: 'tool', toolCallId: 'call-1', text: 'durable result', status: 'done' },
    ])
    expect(timeline.messages).toHaveLength(1)
  })

  it('projects prepared tool progress and partial results while a run is active', () => {
    const timeline = buildSessionTimeline([], [
      runEvent('prepared', 'tool_prepared', { invocationId: 'inv-1', toolCallId: 'call-1', toolName: 'web_search', arguments: '{"q":"上海天气"}' }),
      runEvent('started', 'tool_started', { invocationId: 'inv-1', toolCallId: 'call-1' }),
      runEvent('partial', 'tool_delta_batch', { invocationId: 'inv-1', partialResults: [{ toolCallId: 'call-1', contents: [{ type: 'text', text: '晴 32C' }], error: false, details: {} }] }),
      runEvent('completed', 'tool_completed', { invocationId: 'inv-1', status: 'SUCCEEDED', error: false }),
    ])

    expect(timeline.messages).toMatchObject([{
      role: 'tool',
      toolCallId: 'call-1',
      toolName: 'web_search',
      arguments: '{"q":"上海天气"}',
      text: '晴 32C',
      status: 'done',
    }])
  })

  it('ignores malformed entry and event payloads without corrupting the timeline', () => {
    const timeline = buildSessionTimeline([
      { ...entry('bad-entry', 'message', {}), payloadJson: '{bad' },
    ], [
      { ...runEvent('bad-event', 'assistant_delta_batch', {}), payloadJson: '{bad' },
    ])

    expect(timeline).toEqual({ messages: [], runtimeContext: {} })
  })

  it('treats queued, running and waiting-tools runs as active', () => {
    expect(hasActiveRun([run('QUEUED')])).toBe(true)
    expect(hasActiveRun([run('RUNNING')])).toBe(true)
    expect(hasActiveRun([run('WAITING_TOOLS')])).toBe(true)
    expect(hasActiveRun([run('SUCCEEDED')])).toBe(false)
  })
})

function entry(sessionEntryId: string, entryType: string, payload: Record<string, unknown>): HarnessSessionEntryDTO {
  return {
    sessionEntryId,
    sessionId: '1',
    parentEntryId: null,
    runId: entryType === 'agent_snapshot' ? null : '2',
    entryType,
    payloadJson: JSON.stringify(payload),
    createTime: '2026-06-20T02:00:00',
  }
}

function messagePayload(role: string, contents: Record<string, unknown>[]) {
  return { message: { role, contents }, assistantMetadata: role === 'ASSISTANT' ? {} : null }
}

function runEvent(eventId: string, type: string, payload: Record<string, unknown>, sequence = eventId.length): RunEventDTO {
  return {
    eventId,
    runId: '2',
    sequence,
    type,
    payloadJson: JSON.stringify(payload),
    createTime: '2026-06-20T02:00:00',
  }
}

function run(status: string): HarnessRunDTO {
  return {
    runId: '2',
    sessionId: '1',
    triggerEntryId: '3',
    status,
    turnIndex: 0,
    attempt: 1,
    eventSequence: 0,
    nextAttemptAt: null,
    cancelRequestedAt: null,
    startedAt: null,
    finishedAt: null,
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:00:00',
  }
}
