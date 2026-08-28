import { describe, expect, it } from 'vitest'
import type {
  EntryType,
  HarnessSessionEntryDTO,
  ModelAttemptFailureDTO,
  ModelInvocationDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'
import {
  buildThreadEventTimeline,
  eventStatusText,
  type ThreadEventRecord,
} from '@/features/ai/runtime/thread-events'
import type { RealtimeModelStream } from '@/features/ai/runtime/thread-realtime-state'

/**
 * Event 投影矩阵（冻结方案）：每个 durable Entry 恰好一条 ThreadEventRecord；
 * 全部 kind/status、turnNumber/turnStartEntryId 线性跟踪、TURN_END 携带该 Turn
 * Assistant metadata 的完整 usage/cost、活跃 invocation synthetic 的物化消失、
 * 跨 Turn 同 identity 不误去重。
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

function messagePayload(role: string, contents: Array<Record<string, unknown>>, metadata?: Record<string, unknown>) {
  const payload: Record<string, unknown> = { message: { role, contents } }
  if (metadata) {
    payload.assistantMetadata = metadata
  }
  return payload
}

function modelInvocation(status: string, attempt: number, overrides: Partial<ModelInvocationDTO> = {}): ModelInvocationDTO {
  return {
    id: 'model-1',
    threadId: 'thread-1',
    turnStartEntryId: 'turn-1',
    basisHeadEntryId: 'head-1',
    status,
    attempt,
    streamCheckpointJson: null,
    resultJson: null,
    errorJson: null,
    resultEntryId: null,
    createTime: '2026-07-28T10:00:00Z',
    updateTime: '2026-07-28T10:00:00Z',
    ...overrides,
  }
}

function attemptFailure(overrides: Partial<ModelAttemptFailureDTO> = {}): ModelAttemptFailureDTO {
  return {
    modelInvocationId: 'model-1',
    turnStartEntryId: 'turn-1',
    basisHeadEntryId: 'head-1',
    attempt: 1,
    sequence: '3',
    text: 'partial answer',
    thinking: 'partial thinking',
    errorCode: 'TRANSIENT',
    errorMessage: 'try later',
    failedAt: '2026-07-28T10:00:00Z',
    retryAt: '2026-07-28T10:00:05Z',
    ...overrides,
  }
}

function toolInvocation(overrides: Partial<ToolInvocationDTO> = {}): ToolInvocationDTO {
  return {
    id: 'inv-1',
    modelInvocationId: 'model-1',
    assistantEntryId: 'assistant-1',
    ordinal: 0,
    status: 'RUNNING',
    attempt: 1,
    toolCallId: 'call-1',
    toolName: 'bash',
    toolVersion: '1',
    rendererKey: 'bash',
    toolId: 'base.bash',
    toolBackend: 'HOST',
    environment: null,
    argumentsJson: '{"command":"ls"}',
    approvalJson: null,
    resultJson: null,
    errorJson: null,
    createTime: '2026-07-28T10:00:00Z',
    updateTime: '2026-07-28T10:00:00Z',
    ...overrides,
  }
}

function modelStream(overrides: Partial<RealtimeModelStream> = {}): RealtimeModelStream {
  return {
    threadId: 'thread-1',
    invocationId: 'model-1',
    attempt: 2,
    sequence: 9,
    text: 'hello',
    thinking: '',
    toolCalls: [],
    createdAt: '2026-07-28T10:00:06Z',
    status: 'streaming',
    ...overrides,
  }
}

function build(
  entries: HarnessSessionEntryDTO[],
  overrides: Partial<Parameters<typeof buildThreadEventTimeline>[0]> = {},
): ThreadEventRecord[] {
  return buildThreadEventTimeline({
    entries,
    modelInvocation: null,
    toolInvocations: [],
    ...overrides,
  })
}

describe('buildThreadEventTimeline', () => {
  it('projects every durable Entry kind exactly once with turn identity and valid rawJson', () => {
    const entries: HarnessSessionEntryDTO[] = [
      entry('root-1', 'ROOT', {}),
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('user-1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'hi' }])),
      entry('custom-message-1', 'CUSTOM_MESSAGE', {
        message: { role: 'SYSTEM', contents: [{ type: 'text', text: 'custom' }] },
      }),
      entry('fail-1', 'MODEL_ATTEMPT_FAILURE', {
        attempt: { attempt: 1, sequence: 2, text: 'p', thinking: '' },
        error: { code: 'TRANSIENT', message: 'boom' },
        retryAt: '2026-07-28T10:00:05Z',
      }),
      entry('error-1', 'ASSISTANT_ERROR', { error: { message: 'failed' } }),
      entry('aborted-1', 'ASSISTANT_ABORTED', {
        message: { role: 'ASSISTANT', contents: [{ type: 'text', text: 'partial' }] },
      }),
      entry('compact-1', 'COMPACTION', { reason: 'COMPACTION' }),
      entry('end-1', 'TURN_END', { outcome: 'COMPLETED', reason: 'no-continuation' }),
      entry('custom-1', 'CUSTOM', { contributorId: 'c1', customType: 'note', schemaVersion: 1, data: {} }),
      entry('unknown-1', 'UNKNOWN_TYPE' as EntryType, {}),
    ]

    const events = build(entries)

    expect(events.map((event) => event.id)).toEqual([
      'entry:root-1',
      'entry:turn-1',
      'entry:user-1',
      'entry:custom-message-1',
      'entry:fail-1',
      'entry:error-1',
      'entry:aborted-1',
      'entry:compact-1',
      'entry:end-1',
      'entry:custom-1',
      'entry:unknown-1',
    ])
    // 每个 durable Entry 恰好一条记录。
    expect(events).toHaveLength(entries.length)
    expect(events.map((event) => event.kind)).toEqual([
      'ROOT',
      'TURN_START',
      'USER_MESSAGE',
      'CUSTOM_MESSAGE',
      'MODEL_ATTEMPT_FAILURE',
      'ASSISTANT_ERROR',
      'ASSISTANT_ABORTED',
      'COMPACTION',
      'TURN_END',
      'CUSTOM',
      'CUSTOM',
    ])
    expect(events.map((event) => event.status)).toEqual([
      'completed',
      'completed',
      'completed',
      'completed',
      'failed',
      'failed',
      'stopped',
      'completed',
      'completed',
      'completed',
      'completed',
    ])
    // 线性扫描：ROOT 在第一个 TURN_START 之前 → turn 0；其余都在 turn 1。
    expect(events[0]!.turnNumber).toBe(0)
    expect(events[0]!.turnStartEntryId).toBeNull()
    for (const event of events.slice(1)) {
      expect(event.turnNumber).toBe(1)
      expect(event.turnStartEntryId).toBe('turn-1')
    }
    expect(events.map((event) => event.title)).toEqual([
      'ROOT',
      'TURN_START',
      'MESSAGE',
      'CUSTOM_MESSAGE',
      'MODEL_ATTEMPT_FAILURE',
      'ASSISTANT_ERROR',
      'ASSISTANT_ABORTED',
      'COMPACTION',
      'TURN_END',
      'CUSTOM',
      'UNKNOWN_TYPE',
    ])
    expect(events[2]!.summary).toBe(JSON.stringify(messagePayload('USER', [{ type: 'text', text: 'hi' }])))
    expect(JSON.parse(events[2]!.rawJson!)).toEqual(messagePayload('USER', [{ type: 'text', text: 'hi' }]))
    for (const event of events) {
      expect(() => JSON.parse(event.rawJson!)).not.toThrow()
    }
  })

  it('classifies MESSAGE roles into USER_MESSAGE / ASSISTANT_MESSAGE / TOOL_CALL / TOOL_RESULT / CUSTOM_MESSAGE', () => {
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('user-1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'prompt' }])),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: 'reply' }])),
      entry('assistant-tool-1', 'MESSAGE', messagePayload('ASSISTANT', [
        { type: 'text', text: 'running' },
        { type: 'tool_call', toolCallId: 'call-1', toolName: 'bash', rendererKey: 'bash', argumentsJson: '{"command":"ls"}' },
      ])),
      entry('tool-1', 'MESSAGE', messagePayload('TOOL', [
        { type: 'tool_result', toolCallId: 'call-1', toolName: 'bash', rendererKey: 'bash', contents: [{ type: 'text', text: 'ok' }] },
      ])),
      entry('system-1', 'MESSAGE', messagePayload('SYSTEM', [{ type: 'text', text: 'system note' }])),
      entry('weird-1', 'MESSAGE', messagePayload('SOMETHING', [{ type: 'text', text: 'odd' }])),
    ]
    const events = build(entries)
    expect(events.map((event) => event.kind)).toEqual([
      'TURN_START',
      'USER_MESSAGE',
      'ASSISTANT_MESSAGE',
      'TOOL_CALL',
      'TOOL_RESULT',
      'CUSTOM_MESSAGE',
      'CUSTOM_MESSAGE',
    ])
    expect(events.map((event) => event.title)).toEqual([
      'TURN_START',
      'MESSAGE',
      'MESSAGE',
      'MESSAGE',
      'MESSAGE',
      'MESSAGE',
      'MESSAGE',
    ])
    expect(events[3]!.summary).toContain('"tool_call"')
    expect(events[3]!.summary).toContain('bash')
    expect(events[4]!.summary).toContain('"tool_result"')
    expect(events[4]!.summary).toContain('call-1')
  })

  it('tracks turnNumber/turnStartEntryId across multiple turns', () => {
    const entries = [
      entry('root-1', 'ROOT', {}),
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('user-1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'a' }])),
      entry('end-1', 'TURN_END', { outcome: 'COMPLETED' }),
      entry('turn-2', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('user-2', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'b' }])),
      entry('end-2', 'TURN_END', { outcome: 'COMPLETED' }),
    ]
    const events = build(entries)
    expect(events.map((event) => `${event.id}:${event.turnNumber}:${event.turnStartEntryId}`)).toEqual([
      'entry:root-1:0:null',
      'entry:turn-1:1:turn-1',
      'entry:user-1:1:turn-1',
      'entry:end-1:1:turn-1',
      'entry:turn-2:2:turn-2',
      'entry:user-2:2:turn-2',
      'entry:end-2:2:turn-2',
    ])
  })

  it('projects TURN_END with the turn usage summary and full details from that turn Assistant metadata', () => {
    const usage = {
      usage: {
        inputTokens: 10,
        outputTokens: 20,
        cacheReadTokens: 5,
        cacheWriteTokens: 3,
        cacheWriteLongTokens: 2,
        reasoningTokens: 7,
        providerTotalTokens: 44,
      },
      cost: 0.00125,
    }
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('user-1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'a' }])),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: 'r' }], usage)),
      entry('end-1', 'TURN_END', { outcome: 'COMPLETED' }),
    ]
    const events = build(entries)
    const turnEnd = events[3]!
    expect(turnEnd.kind).toBe('TURN_END')
    expect(turnEnd.title).toBe('TURN_END')
    expect(turnEnd.summary).toBe(JSON.stringify({ outcome: 'COMPLETED' }))
    expect(JSON.parse(turnEnd.rawJson!)).toEqual({ outcome: 'COMPLETED' })
  })

  it('omits zero cache segments from the TURN_END summary but keeps input/output', () => {
    const usage = {
      usage: { inputTokens: 100, outputTokens: 200, cacheReadTokens: 0, cacheWriteTokens: 0, cacheWriteLongTokens: 0, reasoningTokens: 0, providerTotalTokens: 300 },
      cost: { currency: 'USD', total: '0.005' },
    }
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: 'r' }], usage)),
      entry('end-1', 'TURN_END', { outcome: 'COMPLETED' }),
    ]
    const events = build(entries)
    expect(events[2]!.title).toBe('TURN_END')
    expect(events[2]!.summary).toBe(JSON.stringify({ outcome: 'COMPLETED' }))
  })

  it('keeps TURN_END summary as the bare outcome when the turn has no usage', () => {
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('user-1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'a' }])),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: 'r' }])),
      entry('end-1', 'TURN_END', { outcome: 'COMPLETED' }),
    ]
    const events = build(entries)
    expect(events[3]!.title).toBe('TURN_END')
    expect(events[3]!.summary).toBe(JSON.stringify({ outcome: 'COMPLETED' }))
  })

  it('anchors the active model invocation as a single synthetic record with mapped status', () => {
    const entries = [
      entry('root-1', 'ROOT', {}),
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('user-1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'go' }])),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: 'ok' }])),
      entry('end-1', 'TURN_END', { outcome: 'COMPLETED' }),
    ]
    const stream = modelStream({ sequence: 5, text: 'ok', thinking: '' })
    const events = build(entries, { modelInvocation: modelInvocation('RUNNING', 2), modelStream: stream })

    // 活跃 model 事件锚定在 turn start 之后，且只出现一条（delta token 不逐条成行）。
    expect(events.map((event) => event.id)).toEqual([
      'entry:root-1',
      'entry:turn-1',
      'active:model:model-1',
      'entry:user-1',
      'entry:assistant-1',
      'entry:end-1',
    ])
    const modelEvent = events[2]!
    expect(modelEvent.source).toBe('active-model')
    expect(modelEvent.kind).toBe('ACTIVE_MODEL_INVOCATION')
    expect(modelEvent.status).toBe('running')
    expect(modelEvent.rawJson).toBeNull()
    expect(modelEvent.entryId).toBeNull()
    expect(modelEvent.turnNumber).toBe(1)
    expect(modelEvent.details).toEqual(
      expect.arrayContaining([
        { label: '状态', value: '运行中' },
        { label: '尝试次数', value: '2' },
        { label: '已流式字符数', value: '2' },
      ]),
    )
  })

  it('maps active model statuses to the five frozen states', () => {
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('user-1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'go' }])),
    ]
    const byStatus = (raw: string) =>
      build(entries, { modelInvocation: modelInvocation(raw, 1) }).find(
        (event) => event.source === 'active-model',
      )!.status
    expect(byStatus('READY')).toBe('pending')
    expect(byStatus('DISPATCHING')).toBe('pending')
    expect(byStatus('RUNNING')).toBe('running')
    expect(byStatus('SUCCEEDED')).toBe('completed')
    expect(byStatus('FAILED')).toBe('failed')
    expect(byStatus('CANCELLED')).toBe('stopped')
    // realtime stream error 覆盖 invocation 状态。
    const streamError = build(entries, {
      modelInvocation: modelInvocation('RUNNING', 1),
      modelStream: modelStream({ status: 'error' }),
    })
    expect(streamError.find((event) => event.source === 'active-model')!.status).toBe('failed')
  })

  it('drops the active model invocation when its resultEntryId already exists in entries', () => {
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('user-1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'go' }])),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: 'ok' }])),
      entry('end-1', 'TURN_END', { outcome: 'COMPLETED' }),
    ]
    const materialized = build(entries, { modelInvocation: modelInvocation('SUCCEEDED', 1, { resultEntryId: 'end-1' }) })
    expect(materialized.some((event) => event.source === 'active-model')).toBe(false)
    const pending = build(entries, { modelInvocation: modelInvocation('SUCCEEDED', 1, { resultEntryId: 'end-404' }) })
    expect(pending.some((event) => event.source === 'active-model')).toBe(true)
  })

  it('projects active tool invocation with mapped status anchored after its assistant entry', () => {
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [
        { type: 'text', text: 'run' },
        { type: 'tool_call', toolCallId: 'call-1', toolName: 'bash', rendererKey: 'bash', argumentsJson: '{}' },
      ])),
      entry('end-1', 'TURN_END', { outcome: 'COMPLETED' }),
    ]
    const invocation = toolInvocation({ status: 'WAITING_APPROVAL' })
    const events = build(entries, { toolInvocations: [invocation] })

    expect(events.map((event) => event.id)).toEqual([
      'entry:turn-1',
      'entry:assistant-1',
      'active:tool:inv-1',
      'entry:end-1',
    ])
    const toolEvent = events[2]!
    expect(toolEvent.kind).toBe('ACTIVE_TOOL_INVOCATION')
    expect(toolEvent.status).toBe('pending')
    expect(toolEvent.summary).toContain('bash')
    expect(toolEvent.details).toEqual(
      expect.arrayContaining([
        { label: '工具', value: 'bash' },
        { label: '工具调用 ID', value: 'call-1' },
      ]),
    )

    // errorJson / stream error → failed；resultJson → completed。
    expect(build(entries, { toolInvocations: [toolInvocation({ errorJson: '{}' })] })[2]!.status).toBe('failed')
    expect(build(entries, { toolInvocations: [toolInvocation({ resultJson: '{}' })] })[2]!.status).toBe('completed')
  })

  it('dedups active attempt failures and anchors them before the model event', () => {
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: 'ok' }])),
    ]
    const failure = attemptFailure({ attempt: 1 })
    const events = build(
      entries,
      {
        modelInvocation: modelInvocation('RUNNING', 2),
        modelAttemptFailures: [failure, failure, attemptFailure({ attempt: 1, sequence: '4' })],
        modelStream: modelStream(),
      },
    )

    const ids = events.map((event) => event.id)
    // 同 identity 去重：活跃失败记录只有一条。
    expect(ids.filter((id) => id.startsWith('active:attempt-failure:')).length).toBe(1)
    expect(events.find((event) => event.source === 'attempt-failure')!.status).toBe('failed')
    // 失败在 model 事件之前（按 attempt 排序）。
    expect(ids.indexOf('active:attempt-failure:model-1:1')).toBeLessThan(
      ids.indexOf('active:model:model-1'),
    )
  })

  it('scopes failure dedup identity to the turn (same attempt/sequence across turns)', () => {
    // 旧 Turn（turn-1）已有 durable failure (attempt 1, sequence 3)；新 Turn（turn-2）
    // 的活跃 overlay 复用相同的 attempt/sequence 数字 —— 带 Turn 的身份不得误抑制。
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('fail-1', 'MODEL_ATTEMPT_FAILURE', {
        attempt: { attempt: 1, sequence: 3, text: 'p', thinking: '' },
        error: { code: 'TRANSIENT', message: 'boom' },
        retryAt: '2026-07-28T10:00:05Z',
      }),
      entry('end-1', 'TURN_END', { outcome: 'COMPLETED' }),
      entry('turn-2', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-2', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: 'ok' }])),
    ]
    const newTurnFailure = attemptFailure({
      modelInvocationId: 'model-2',
      turnStartEntryId: 'turn-2',
      attempt: 1,
      sequence: '3',
    })
    const events = build(entries, { modelAttemptFailures: [newTurnFailure] })
    expect(events.map((event) => event.id)).toEqual([
      'entry:turn-1',
      'entry:fail-1',
      'entry:end-1',
      'entry:turn-2',
      'active:attempt-failure:model-2:1',
      'entry:assistant-2',
    ])
  })

  it('skips active failure overlays already materialized in the same turn', () => {
    // ModelTerminalPending 窗口内 durable Entry 与活跃 overlay 同时存在；
    // 相同 Turn 的 (attempt, sequence) identity 只保留 durable 权威记录。
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('fail-1', 'MODEL_ATTEMPT_FAILURE', {
        attempt: { attempt: 1, sequence: 3, text: 'p', thinking: '' },
        error: { code: 'TRANSIENT', message: 'boom' },
        retryAt: '2026-07-28T10:00:05Z',
      }),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: 'ok' }])),
    ]
    const materialized = attemptFailure({ attempt: 1, sequence: '3' })
    const pending = attemptFailure({ attempt: 2, sequence: '7' })
    const events = build(
      entries,
      { modelInvocation: modelInvocation('RUNNING', 3), modelAttemptFailures: [materialized, pending] },
    )
    expect(events.map((event) => event.id)).toEqual([
      'entry:turn-1',
      'active:attempt-failure:model-1:2',
      'active:model:model-1',
      'entry:fail-1',
      'entry:assistant-1',
    ])
  })

  it('keeps running tool overlays visible while only the durable tool_call exists', () => {
    // durable assistant tool_call 只是调用记录：结果尚未物化时，运行中的 tool
    // invocation 必须继续作为活跃 overlay 展示。
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [
        { type: 'text', text: 'run' },
        { type: 'tool_call', toolCallId: 'call-1', toolName: 'bash', rendererKey: 'bash', argumentsJson: '{}' },
      ])),
    ]
    const invocation = toolInvocation({ status: 'RUNNING' })
    const events = build(entries, { toolInvocations: [invocation] })
    expect(events.map((event) => event.id)).toEqual([
      'entry:turn-1',
      'entry:assistant-1',
      'active:tool:inv-1',
    ])
    expect(events[2]!.status).toBe('running')
  })

  it('scopes tool result dedup to the turn (same toolCallId across turns)', () => {
    // turn-1 的 durable tool_result 已物化 call-1；turn-2 复用同一 toolCallId 的
    // 新 invocation 仍在运行 —— 跨 Turn 绝不能因旧 Turn 的结果被误抑制。
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [
        { type: 'tool_call', toolCallId: 'call-1', toolName: 'bash', rendererKey: 'bash', argumentsJson: '{}' },
      ])),
      entry('tool-1', 'MESSAGE', messagePayload('TOOL', [
        { type: 'tool_result', toolCallId: 'call-1', toolName: 'bash', rendererKey: 'bash', contents: [{ type: 'text', text: 'ok' }] },
      ])),
      entry('end-1', 'TURN_END', { outcome: 'COMPLETED' }),
      entry('turn-2', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-2', 'MESSAGE', messagePayload('ASSISTANT', [
        { type: 'tool_call', toolCallId: 'call-1', toolName: 'bash', rendererKey: 'bash', argumentsJson: '{}' },
      ])),
    ]
    const newTurnInvocation = toolInvocation({
      id: 'inv-2',
      assistantEntryId: 'assistant-2',
      toolCallId: 'call-1',
      status: 'RUNNING',
    })
    const events = build(entries, { toolInvocations: [newTurnInvocation] })
    expect(events.map((event) => event.id)).toEqual([
      'entry:turn-1',
      'entry:assistant-1',
      'entry:tool-1',
      'entry:end-1',
      'entry:turn-2',
      'entry:assistant-2',
      'active:tool:inv-2',
    ])
  })

  it('suppresses tool overlays once a durable tool_result is materialized in the same turn', () => {
    const assistantToolCall = {
      type: 'tool_call' as const,
      toolCallId: 'call-1',
      toolName: 'bash',
      rendererKey: 'bash',
      argumentsJson: '{}',
    }
    // durable tool_result 已物化（同 Turn）：durable Entry 是权威记录，overlay 消失。
    const durable = build(
      [
        entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
        entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [assistantToolCall])),
        entry('tool-1', 'MESSAGE', messagePayload('TOOL', [
          { type: 'tool_result', toolCallId: 'call-1', contents: [{ type: 'text', text: 'ok' }] },
        ])),
      ],
      { toolInvocations: [toolInvocation()] },
    )
    expect(durable.some((event) => event.source === 'active-tool')).toBe(false)

    // durable tool_result 尚未物化：terminal-pending overlay 继续展示。ToolResult
    // Entry 写入与 invocation 删除原子提交，因此直到 durable Entry 落地
    // （invocation 随 snapshot 消失）才有权威记录。
    const pending = build(
      [
        entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
        entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [assistantToolCall])),
      ],
      { toolInvocations: [toolInvocation({ resultJson: '{"toolCallId":"call-1","contents":[]}' })] },
    )
    expect(pending.some((event) => event.source === 'active-tool')).toBe(true)
  })

  it('falls back to appending overlay records whose anchor entry is missing', () => {
    const entries = [entry('root-1', 'ROOT', {})]
    const events = build(
      entries,
      {
        modelInvocation: modelInvocation('RUNNING', 1),
        toolInvocations: [toolInvocation()],
        modelAttemptFailures: [attemptFailure()],
      },
    )
    expect(events.map((event) => event.id)).toEqual([
      'entry:root-1',
      'active:attempt-failure:model-1:1',
      'active:model:model-1',
      'active:tool:inv-1',
    ])
    // 锚点缺失的 synthetic 记录 turn 身份为空。
    expect(events[1]!.turnNumber).toBe(0)
    expect(events[1]!.turnStartEntryId).toBeNull()
  })

  it('keeps malformed payload JSON inspectable through compact/pretty fallbacks', () => {
    // payloadJson 不是合法 JSON：compactJson 折叠为空格归一文本、prettyJson 原样返回，
    // 事件仍然投影（审计视图不丢信息），且 summary/rawJson 不抛异常。
    const events = build([
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      { ...entry('bad-1', 'MESSAGE', {}), payloadJson: 'not-json' },
    ])
    const bad = events[1]!
    expect(bad.kind).toBe('CUSTOM_MESSAGE')
    // compactJson 对非法 JSON 回退为空格折叠文本；prettyJson 原样返回。
    expect(bad.summary).toBe('not-json')
    expect(bad.rawJson).toBe('not-json')
    // 空白 payload 视为空对象（parsePayload 兜底），仍能正常投影。
    const blank = build([{ ...entry('blank-1', 'MESSAGE', {}), payloadJson: '' }])[0]!
    expect(blank.kind).toBe('CUSTOM_MESSAGE')
    // parsePayload 对空字符串兜底为空对象：compactJson 输出 '{}' 作为可展示摘要。
    expect(blank.summary).toBe('{}')
  })

  it('maps unknown active statuses to pending and stream errors to failed', () => {
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('user-1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'go' }])),
    ]
    // 未知 invocation 状态冻结为 pending（不做猜测）。
    const unknown = build(entries, { modelInvocation: modelInvocation('STRANGE', 1) }).find(
      (event) => event.source === 'active-model',
    )!
    expect(unknown.status).toBe('pending')
    // 活跃 tool overlay 的 stream error 优先于 invocation 状态：failed。
    const entriesTool = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [
        { type: 'tool_call', toolCallId: 'call-1', toolName: 'bash', rendererKey: 'bash', argumentsJson: '{}' },
      ])),
    ]
    const toolStream = {
      threadId: 'thread-1',
      invocationId: 'inv-1',
      attempt: 1,
      toolCallId: 'call-1',
      text: '',
      error: true,
      errorText: 'boom',
      createdAt: '2026-07-28T10:00:00Z',
    }
    const failed = build(entriesTool, {
      toolInvocations: [toolInvocation({ status: 'RUNNING' })],
      toolStreams: new Map([['inv-1', toolStream]]),
    }).find((event) => event.source === 'active-tool')!
    expect(failed.status).toBe('failed')
    // tool_result 无正文时 summary 回退到空文本占位（不是 undefined）。
    const emptyResult = build([
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [
        { type: 'tool_call', toolCallId: 'call-1', toolName: 'bash', rendererKey: 'bash', argumentsJson: '{}' },
      ])),
      entry('tool-1', 'MESSAGE', messagePayload('TOOL', [
        { type: 'tool_result', toolCallId: 'call-1', contents: [{ type: 'text', text: '' }] },
      ])),
    ])
    // durable Entry 的 summary 是压缩后的原始 payload JSON（toolResultText 只用于
    // 详情摘要文本，tool_result 无正文时详情行 value 为空串而非 undefined）。
    expect(emptyResult[2]!.summary).toContain('"tool_result"')
    expect(
      emptyResult[2]!.details.find((row) => row.label === '工具调用 ID')!.value,
    ).toBe('call-1')
  })

  it('compares active failure timestamps across string, number, array, and invalid forms', () => {
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: 'ok' }])),
    ]
    const failures = [
      // 数字时间戳（epoch millis）：走得比字符串/数组都小（L745 数字分支）。
      attemptFailure({ attempt: 2, sequence: '5', failedAt: 1, retryAt: 2 }),
      // 字符串时间戳：数值上排在数字之后。
      attemptFailure({ attempt: 2, sequence: '6', failedAt: '2026-07-28T10:00:00Z' }),
      // 数组时间戳（[y,m,d,...]）：按数组首元素数值参与比较（L751-753）。
      attemptFailure({ attempt: 2, sequence: '7', failedAt: [2026, 7, 28, 10, 0, 0], retryAt: [2026, 7, 28] }),
      // 无法解析的字符串：按 0 参与比较，始终排最前（L754 fallback）。
      attemptFailure({ attempt: 2, sequence: '8', failedAt: 'not-a-date' }),
      // null 时间戳：同样按 0 处理（L754 fallback），与无法解析的字符串并列。
      attemptFailure({ attempt: 2, sequence: '9', failedAt: null as never, retryAt: null as never }),
    ]
    const events = build(entries, {
      modelAttemptFailures: failures,
      modelInvocation: modelInvocation('RUNNING', 3),
    })
    // 同 attempt 按 failedAt 稳定排序：不可解析/空值最先，其次数字、字符串、数组。
    // attempt 相同 → 5 个失败共享同一 identity，只保留一条活跃 overlay（去重语义）。
    const attemptFailures = events.filter((event) => event.source === 'attempt-failure')
    expect(attemptFailures.map((event) => event.id)).toEqual([
      'active:attempt-failure:model-1:2',
    ])
    // 去重保留的是排序后第一条（failedAt 不可解析、时间戳按 0 处理的那条）。
    expect(
      attemptFailures[0]!.details.find((row) => row.label === '序号')!.value,
    ).toBe('8')
  })

  it('keeps the full compact payload JSON in the summary and leaves overflow to CSS', () => {
    const long = 'x'.repeat(300)
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('user-1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: long }])),
    ]
    const events = build(entries)
    expect(events[1]!.summary).toContain(long)
    expect(events[1]!.summary.endsWith('…')).toBe(false)
    expect(JSON.parse(events[1]!.summary)).toEqual(messagePayload('USER', [{ type: 'text', text: long }]))
  })

  it('strictly handles CUSTOM entries using contributorId only and ignores legacy pluginId in semantic projection', () => {
    // 意图：验证 CUSTOM entry 严格仅消费 contributorId / customType，不再支持旧 wire 字段 pluginId 作为语义来源；
    // 旧 payload 中的 pluginId 绝不作为 contributorId / customType 兼容回退，但 rawJson 完整保真保留原样。
    const entries = [
      entry('custom-full', 'CUSTOM', {
        contributorId: 'goal-contributor',
        customType: 'goal-status',
        schemaVersion: 1,
        data: {},
      }),
      entry('custom-legacy-plugin', 'CUSTOM', {
        pluginId: 'legacy-plugin',
        schemaVersion: 1,
        data: {},
      }),
    ]

    const events = build(entries)
    expect(events).toHaveLength(2)

    // 标准 Contributor CUSTOM Entry 正常投影
    expect(events[0]!.kind).toBe('CUSTOM')
    expect(events[0]!.title).toBe('CUSTOM')
    expect(events[0]!.rawJson).toContain('goal-contributor')
    expect(events[0]!.rawJson).toContain('goal-status')

    // 旧 wire 仅含 pluginId 时：仍然作为 CUSTOM entry 投影，rawJson 完整无损，但语义层不再做 pluginId 转换
    expect(events[1]!.kind).toBe('CUSTOM')
    expect(events[1]!.title).toBe('CUSTOM')
    expect(events[1]!.rawJson).toContain('legacy-plugin')
    expect(JSON.parse(events[1]!.rawJson!)).toEqual({
      pluginId: 'legacy-plugin',
      schemaVersion: 1,
      data: {},
    })
  })

  it('projects tool invocation with DECLARATIVE backend correctly', () => {
    // 意图：验证 DECLARATIVE toolBackend 的 ToolInvocation 正常被 timeline/event 系统投影。
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
    ]
    const invocation = toolInvocation({
      id: 'inv-decl',
      toolName: 'create_goal',
      toolId: 'base.goal.create',
      toolBackend: 'DECLARATIVE',
      rendererKey: 'tool',
      status: 'RUNNING',
    })

    const events = build(entries, { toolInvocations: [invocation] })
    const activeTool = events.find((e) => e.id === 'active:tool:inv-decl')
    expect(activeTool).toBeDefined()
    expect(activeTool?.title).toBe('ACTIVE_TOOL_INVOCATION')
    expect(activeTool?.summary).toBe('create_goal · 运行中')
  })
})

describe('eventStatusText', () => {
  it('localizes the five frozen states', () => {
    expect(eventStatusText('pending')).toBe('等待中')
    expect(eventStatusText('running')).toBe('运行中')
    expect(eventStatusText('completed')).toBe('已完成')
    expect(eventStatusText('failed')).toBe('失败')
    expect(eventStatusText('stopped')).toBe('已停止')
  })
})
