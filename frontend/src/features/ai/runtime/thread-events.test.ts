import { describe, expect, it } from 'vitest'
import type {
  EntryType,
  HarnessSessionEntryDTO,
  ModelAttemptFailureDTO,
  ModelInvocationDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'
import {
  buildThreadEvents,
  eventStatusText,
} from '@/features/ai/runtime/thread-events'
import type { RealtimeModelStream } from '@/features/ai/runtime/thread-realtime-state'

/**
 * Event 投影矩阵：durable Entry 全类型 + 活跃 model/tool/failure overlay。
 * 覆盖失败 attempt、tool、aborted/error/compaction/active invocation；
 * Provider delta token 绝不逐条成行（活跃 model 始终是单条事件）。
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

function messagePayload(role: string, contents: Array<Record<string, unknown>>) {
  return { message: { role, contents } }
}

function modelInvocation(status: string, attempt: number): ModelInvocationDTO {
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
    toolType: 'shell',
    environmentName: null,
    argumentsJson: '{"command":"ls"}',
    approvalJson: null,
    resultJson: null,
    errorJson: null,
    resultEntryId: null,
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
    createdAt: '2026-07-28T10:00:06Z',
    status: 'streaming',
    ...overrides,
  }
}

describe('buildThreadEvents', () => {
  it('projects every durable Entry type in order with valid raw payload JSON', () => {
    const payload = { message: { role: 'USER', contents: [{ type: 'text', text: 'hi' }] } }
    const entries: HarnessSessionEntryDTO[] = [
      entry('root-1', 'ROOT', {}),
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('user-1', 'MESSAGE', payload),
      entry('custom-1', 'CUSTOM_MESSAGE', { message: { role: 'SYSTEM', contents: [{ type: 'text', text: 'custom' }] } }),
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
      entry('custom-2', 'CUSTOM', { kind: 'anything' }),
      entry('unknown-1', 'UNKNOWN_TYPE' as EntryType, {}),
    ]

    const events = buildThreadEvents(entries, null, [], [])

    expect(events.map((event) => event.id)).toEqual([
      'entry:root-1',
      'entry:turn-1',
      'entry:user-1',
      'entry:custom-1',
      'entry:fail-1',
      'entry:error-1',
      'entry:aborted-1',
      'entry:compact-1',
      'entry:end-1',
      'entry:custom-2',
      'entry:unknown-1',
    ])
    expect(events.every((event) => event.source === 'entry')).toBe(true)
    // 原始 payload JSON 原样保留且有效。
    expect(events[2]!.payloadJson).toBe(JSON.stringify(payload))
    for (const event of events) {
      expect(() => JSON.parse(event.payloadJson!)).not.toThrow()
    }
    // 标题区分类型。
    expect(events[0]!.title).toBe('根节点')
    expect(events[1]!.title).toBe('回合开始')
    expect(events[7]!.title).toBe('上下文压缩')
    expect(events[9]!.title).toBe('自定义')
    expect(events[10]!.title).toContain('UNKNOWN_TYPE')
  })

  it('anchors the active model invocation as a single event (no per-delta rows)', () => {
    const entries = [
      entry('root-1', 'ROOT', {}),
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('user-1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'go' }])),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: 'ok' }])),
      entry('end-1', 'TURN_END', { outcome: 'COMPLETED' }),
    ]
    const stream = modelStream({ sequence: 5, text: 'ok', thinking: '' })

    const events = buildThreadEvents(entries, modelInvocation('RUNNING', 2), [], [], stream)

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
    expect(modelEvent.source).toBe('model')
    expect(modelEvent.payloadJson).toBeNull()
    expect(modelEvent.details).toEqual(
      expect.arrayContaining([
        { label: '状态', value: '运行中' },
        { label: '尝试次数', value: '2' },
        { label: '已流式字符数', value: '2' },
      ]),
    )
  })

  it('dedups active attempt failures and anchors them before the model event', () => {
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: 'ok' }])),
    ]
    const failure = attemptFailure({ attempt: 1 })
    const events = buildThreadEvents(
      entries,
      modelInvocation('RUNNING', 2),
      [],
      [failure, failure, attemptFailure({ attempt: 1, sequence: '4' })],
      modelStream(),
    )

    const ids = events.map((event) => event.id)
    // 同 identity 去重：活跃失败记录只有一条。
    expect(ids.filter((id) => id.startsWith('active:attempt-failure:')).length).toBe(1)
    // 失败在 model 事件之前（按 attempt 排序）。
    expect(ids.indexOf('active:attempt-failure:model-1:1')).toBeLessThan(
      ids.indexOf('active:model:model-1'),
    )
  })

  it('projects active tool invocation anchored after its assistant entry', () => {
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: 'run' }])),
      entry('end-1', 'TURN_END', { outcome: 'COMPLETED' }),
    ]
    const invocation = toolInvocation({ status: 'WAITING_APPROVAL' })

    const events = buildThreadEvents(entries, null, [invocation], [])

    expect(events.map((event) => event.id)).toEqual([
      'entry:turn-1',
      'entry:assistant-1',
      'active:tool:inv-1',
      'entry:end-1',
    ])
    const toolEvent = events[2]!
    expect(toolEvent.source).toBe('tool')
    expect(toolEvent.text).toContain('bash')
    expect(toolEvent.text).toContain('等待审批')
    expect(toolEvent.details).toEqual(
      expect.arrayContaining([
        { label: '工具', value: 'bash' },
        { label: '工具调用 ID', value: 'call-1' },
      ]),
    )
  })

  it('falls back to appending overlay events whose anchor entry is missing', () => {
    const entries = [entry('root-1', 'ROOT', {})]
    const events = buildThreadEvents(
      entries,
      modelInvocation('RUNNING', 1),
      [toolInvocation()],
      [attemptFailure()],
    )
    expect(events.map((event) => event.id)).toEqual([
      'entry:root-1',
      'active:attempt-failure:model-1:1',
      'active:model:model-1',
      'active:tool:inv-1',
    ])
  })

  it('keeps aborted/error terminal states visible as entry events with status text', () => {
    const entries = [
      entry('aborted-1', 'ASSISTANT_ABORTED', {
        message: { role: 'ASSISTANT', contents: [{ type: 'text', text: 'partial' }] },
      }),
      entry('error-1', 'ASSISTANT_ERROR', { error: { message: 'boom' } }),
    ]
    const events = buildThreadEvents(entries, null, [], [])
    expect(events[0]!.title).toBe('助手已停止')
    expect(events[0]!.text).toContain('停止')
    expect(events[1]!.title).toBe('助手错误')
    expect(events[1]!.text).toBe('boom')
  })

  it('keeps compaction turns visible as entries (transcript suppresses them, events do not)', () => {
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'COMPACTION' }),
      entry('inner-1', 'MESSAGE', messagePayload('ASSISTANT', [{ type: 'text', text: 'summarized' }])),
      entry('end-1', 'TURN_END', { outcome: 'COMPLETED' }),
    ]
    const events = buildThreadEvents(entries, null, [], [])
    expect(events.map((event) => event.id)).toEqual([
      'entry:turn-1',
      'entry:inner-1',
      'entry:end-1',
    ])
  })

  it('reads numeric attempt values in durable failure summaries', () => {
    // durable payload 的 attempt.attempt / attempt.sequence 是 JSON number。
    const entries = [
      entry('fail-1', 'MODEL_ATTEMPT_FAILURE', {
        attempt: { attempt: 3, sequence: 9, text: 'p', thinking: '' },
        error: { code: 'TRANSIENT', message: 'boom' },
        retryAt: '2026-07-28T10:00:05Z',
      }),
    ]
    const events = buildThreadEvents(entries, null, [], [])
    expect(events[0]!.text).toBe('attempt 3 · TRANSIENT')
  })

  it('skips active failure overlays already materialized as durable entries', () => {
    // ModelTerminalPending 窗口内 durable Entry 与活跃 overlay 同时存在；
    // 相同 (attempt, sequence) identity 只保留 durable 权威记录。
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
    const events = buildThreadEvents(
      entries,
      modelInvocation('RUNNING', 3),
      [],
      [materialized, pending],
    )
    expect(events.map((event) => event.id)).toEqual([
      'entry:turn-1',
      'active:attempt-failure:model-1:2',
      'active:model:model-1',
      'entry:fail-1',
      'entry:assistant-1',
    ])
  })

  it('skips active tool overlays already materialized as durable tool entries', () => {
    // ToolTerminalPending 窗口内 durable TOOL Entry 与活跃 toolInvocations 同时存在；
    // 相同 toolCallId 只保留 durable 权威记录。
    const entries = [
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [
        { type: 'text', text: 'run' },
        { type: 'tool_call', toolCallId: 'call-1', toolName: 'bash', argumentsJson: '{}' },
      ])),
      entry('tool-1', 'MESSAGE', messagePayload('TOOL', [
        { type: 'tool_result', toolCallId: 'call-1', contents: [{ type: 'text', text: 'ok' }] },
      ])),
    ]
    const invocation = toolInvocation({ toolCallId: 'call-1' })
    const events = buildThreadEvents(entries, null, [invocation], [])
    expect(events.map((event) => event.id)).toEqual([
      'entry:assistant-1',
      'entry:tool-1',
    ])
  })

  it('scopes failure dedup identity to the turn (same attempt/sequence across turns)', () => {
    // 旧 Turn（turn-1）已有 durable failure (attempt 1, sequence 3)；新 Turn（turn-2）
    // 的活跃 overlay 复用相同的 attempt/sequence 数字 —— 全局 identity 会误抑制，
    // 带 Turn 的身份必须让新 Turn 的活跃失败继续展示。
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
    const events = buildThreadEvents(entries, null, [], [newTurnFailure])
    expect(events.map((event) => event.id)).toEqual([
      'entry:turn-1',
      'entry:fail-1',
      'entry:end-1',
      'entry:turn-2',
      'active:attempt-failure:model-2:1',
      'entry:assistant-2',
    ])
  })

  it('keeps running tool overlays visible while only the durable tool_call exists', () => {
    // durable assistant tool_call 只是调用记录：结果尚未物化时，运行中的 tool
    // invocation 必须继续作为活跃 overlay 展示。
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [
        { type: 'text', text: 'run' },
        { type: 'tool_call', toolCallId: 'call-1', toolName: 'bash', argumentsJson: '{}' },
      ])),
    ]
    const invocation = toolInvocation({ status: 'RUNNING' })
    const events = buildThreadEvents(entries, null, [invocation], [])
    expect(events.map((event) => event.id)).toEqual([
      'entry:turn-1',
      'entry:assistant-1',
      'active:tool:inv-1',
    ])
    expect(events[2]!.text).toContain('运行中')
  })

  it('scopes tool result dedup to the turn (same toolCallId across turns)', () => {
    // turn-1 的 durable tool_result 已物化 call-1；turn-2 复用同一 toolCallId 的
    // 新 invocation 仍在运行 —— 跨 Turn 绝不能因旧 Turn 的结果被误抑制。
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [
        { type: 'tool_call', toolCallId: 'call-1', toolName: 'bash', argumentsJson: '{}' },
      ])),
      entry('tool-1', 'MESSAGE', messagePayload('TOOL', [
        { type: 'tool_result', toolCallId: 'call-1', contents: [{ type: 'text', text: 'ok' }] },
      ])),
      entry('end-1', 'TURN_END', { outcome: 'COMPLETED' }),
      entry('turn-2', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-2', 'MESSAGE', messagePayload('ASSISTANT', [
        { type: 'tool_call', toolCallId: 'call-1', toolName: 'bash', argumentsJson: '{}' },
      ])),
    ]
    const newTurnInvocation = toolInvocation({
      id: 'inv-2',
      assistantEntryId: 'assistant-2',
      toolCallId: 'call-1',
      status: 'RUNNING',
    })
    const events = buildThreadEvents(entries, null, [newTurnInvocation], [])
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

  it('suppresses tool overlays when the DTO resultEntryId already points to an existing entry', () => {
    // durable result Entry 已存在但 payload 不含可解析的 toolCallId：仅凭
    // resultEntryId 指向已存在的 Entry 也必须跳过 terminal-pending overlay。
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [
        { type: 'tool_call', toolCallId: 'call-1', toolName: 'bash', argumentsJson: '{}' },
      ])),
      entry('tool-1', 'MESSAGE', messagePayload('TOOL', [
        { type: 'tool_result', contents: [{ type: 'text', text: 'ok' }] },
      ])),
    ]
    const invocation = toolInvocation({ resultEntryId: 'tool-1' })
    const events = buildThreadEvents(entries, null, [invocation], [])
    expect(events.map((event) => event.id)).toEqual([
      'entry:turn-1',
      'entry:assistant-1',
      'entry:tool-1',
    ])
  })

  it('keeps tool overlays when resultEntryId points to a missing entry', () => {
    const entries = [
      entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
      entry('assistant-1', 'MESSAGE', messagePayload('ASSISTANT', [
        { type: 'tool_call', toolCallId: 'call-1', toolName: 'bash', argumentsJson: '{}' },
      ])),
    ]
    const invocation = toolInvocation({ resultEntryId: 'tool-404' })
    const events = buildThreadEvents(entries, null, [invocation], [])
    expect(events.map((event) => event.id)).toEqual([
      'entry:turn-1',
      'entry:assistant-1',
      'active:tool:inv-1',
    ])
  })
})

describe('eventStatusText', () => {
  it('localizes known statuses and passes unknown values through', () => {
    expect(eventStatusText('RUNNING')).toBe('运行中')
    expect(eventStatusText('waiting_approval')).toBe('等待审批')
    expect(eventStatusText('streaming')).toBe('流式中')
    expect(eventStatusText('SOMETHING_NEW')).toBe('SOMETHING_NEW')
    expect(eventStatusText('')).toBe('')
  })
})
