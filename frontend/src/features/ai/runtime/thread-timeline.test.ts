import { describe, expect, it } from 'vitest'
import type {
  EntryType,
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
  ModelAttemptFailureDTO,
  ModelInvocationDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { RealtimeModelStream, RealtimeToolStream } from '@/features/ai/runtime/thread-realtime-state'
import { buildThreadTimeline, isThreadWorking } from '@/features/ai/runtime/thread-timeline'

describe('thread timeline', () => {
  it.each([
    'READY', 'WAITING_APPROVAL', 'WAITING_INPUT', 'DISPATCHING', 'RUNNING',
    'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN',
  ])('preserves durable %s and frozen environment facts at the sole overlay boundary', (status) => {
    const tool = invocation('inv-status', 'assistant-status', 'call-status', {
      status, requiredEnvironmentId: 'frozen-route', requiredEnvironmentName: 'archlinux',
      waitingForEnvironment: status === 'READY', environmentWaitFreshnessAt: 1791417600,
    })
    const timeline = buildThreadTimeline([
      entry('assistant-status', 'MESSAGE', messagePayload('ASSISTANT', [{
        type: 'tool_call', toolCallId: 'call-status', toolName: 'bash', rendererKey: 'bash',
        argumentsJson: '{"command":"ls"}',
      }])),
    ], [], [tool])
    const call = timeline.messages.find((m) => m.role === 'tool' && m.phase === 'call')
    expect(call).toMatchObject({
      invocationId: tool.id, invocationStatus: status,
      requiredEnvironmentId: 'frozen-route', requiredEnvironmentName: 'archlinux',
      waitingForEnvironment: status === 'READY', environmentWaitFreshnessAt: 1791417600,
    })
    if (status !== 'RUNNING') {
      expect(call?.status).not.toBe('streaming')
    }
  })

  it('projects durable entries as the transcript baseline', () => {
    const timeline = buildThreadTimeline(
      [
        entry('1', 'ROOT', { settings: {
          agentName: 'JIJI', model: { providerName: 'provider', modelName: 'model', variant: 'default' },
          environmentName: null,
        } }),
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
      { role: 'entry', kind: 'root', title: '会话开始 · Agent: JIJI · 模型: provider/model (default) · 环境: 无', status: 'done' },
      { role: 'user', text: '检查大纲', status: 'done' },
      { role: 'assistant', text: '结构完整。', thinking: '先梳理结构。', status: 'done' },
    ])
    expect(timeline.queuedMessages).toEqual([])
    expect(timeline.hasPendingInputs).toBe(false)
  })

  it('projects committed settings snapshots before messages, never compaction settings', () => {
    // 测试意图：配置事件来自持久快照，固定字段顺序且有稳定的复合 ID。
    const initial = {
      agentName: 'coding', model: { providerName: 'p', modelName: 'm1', variant: 'default' },
      environmentName: null,
    }
    const changed = {
      agentName: 'reviewer', model: { providerName: 'p', modelName: 'm2', variant: 'high' },
      environmentName: 'local',
    }
    const entries = [
      entry('root', 'ROOT', { settings: initial }),
      entry('compact', 'TURN_START', { reason: 'COMPACTION', settings: changed }),
      entry('compact-end', 'TURN_END', {}),
      entry('turn', 'TURN_START', { reason: 'CONTINUATION', settings: changed }),
      entry('failed', 'ASSISTANT_ERROR', { error: { message: 'failed' } }),
      entry('same', 'TURN_START', { reason: 'INPUT', settings: changed }),
      entry('user', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: 'next input' }])),
    ]
    const projected = buildThreadTimeline(entries, [], []).messages
    expect(projected.map(({ id }) => id)).toEqual([
      'entry:root', 'entry:turn:agent', 'entry:turn:model', 'entry:turn:environment',
      'failed', 'user',
    ])
    expect(projected.slice(1, 4).map((message) => message.role === 'entry' ? message.title : '')).toEqual([
      'Agent 改为 reviewer', '模型改为 p/m2 (high)', '环境改为 local',
    ])
    expect(buildThreadTimeline(entries, [], []).messages).toEqual(projected)
  })

  it('clears the diff baseline on malformed snapshots and recovers without invented changes', () => {
    // 测试意图：损坏快照中断差值链；下一完整快照只重建基准，不虚构变更。
    const settings = {
      agentName: 'coding', model: { providerName: 'p', modelName: 'm', variant: 'default' },
      environmentName: 'local',
    }
    const projected = buildThreadTimeline([
      entry('root', 'ROOT', { settings }),
      entry('broken', 'TURN_START', { reason: 'INPUT', settings: { agentName: 'reviewer' } }),
      entry('recovered', 'TURN_START', { reason: 'CONTINUATION', settings }),
      entry('detached', 'TURN_START', { reason: 'INPUT', settings: { ...settings, environmentName: null } }),
    ], [], []).messages
    expect(projected.map(({ id }) => id)).toEqual([
      'entry:root', 'entry:broken', 'entry:detached:environment',
    ])
    expect(projected[1]).toMatchObject({ kind: 'invalid_settings', title: '配置快照不可解析' })
    expect(projected[2]).toMatchObject({ title: '环境已解除' })
  })

  it('appends a transient assistant modelStream overlay after durable entries', () => {
    const stream: RealtimeModelStream = {
      threadId: 'thread-1',
      invocationId: 'model-1',
      attempt: 1,
      sequence: 1,
      text: '正在输出',
      thinking: '正在思考',
      toolCalls: [],
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

  it('projects streaming tool-call argument fragments before durable assistant tool_call entries exist', () => {
    const stream: RealtimeModelStream = {
      threadId: 'thread-1',
      invocationId: 'model-1',
      attempt: 1,
      sequence: 4,
      text: '',
      thinking: '',
      toolCalls: [
        {
          index: 0,
          id: 'call-write',
          name: 'write',
          argumentsJson: '{"path":"App.java","content":"class App {}"}',
        },
      ],
      createdAt: '2026-07-28T10:00:00Z',
      status: 'streaming',
    }

    const timeline = buildThreadTimeline([], [], [], stream)

    expect(timeline.messages).toMatchObject([
      {
        role: 'tool',
        phase: 'call',
        toolName: 'write',
        rendererKey: 'write',
        arguments: '{"path":"App.java","content":"class App {}"}',
        status: 'streaming',
      },
    ])
  })

  it('projects active failed attempts before the current realtime attempt', () => {
    const stream: RealtimeModelStream = {
      threadId: 'thread-1',
      invocationId: 'model-1',
      attempt: 2,
      sequence: 1,
      text: 'current output',
      thinking: 'current plan',
      toolCalls: [],
      createdAt: '2026-07-28T10:00:03Z',
      status: 'streaming',
    }
    const timeline = buildThreadTimeline(
      [entry('user-1', 'MESSAGE', messagePayload('USER', [{ type: 'text', text: '请求' }]))],
      [],
      [],
      stream,
      null,
      [modelAttemptFailure()],
    )

    expect(timeline.messages).toMatchObject([
      {
        role: 'user',
        text: '请求',
      },
      {
        role: 'model_attempt_failure',
        attempt: 1,
        sequence: '3',
        text: 'partial answer',
        thinking: 'partial thinking',
        errorCode: 'TRANSIENT',
        errorMessage: 'try later',
        retryAt: '2026-07-28T10:00:05Z',
        nextAttempt: 2,
      },
      {
        role: 'assistant',
        text: 'current output',
        thinking: 'current plan',
      },
    ])
    expect(timeline.messages.map((message) => message.role)).toEqual([
      'user',
      'model_attempt_failure',
      'assistant',
    ])
  })

  it('suppresses the stale realtime overlay once the same attempt is durably failed', () => {
    const stale: RealtimeModelStream = {
      threadId: 'thread-1',
      invocationId: 'model-1',
      attempt: 1,
      sequence: 3,
      text: 'partial answer',
      thinking: 'partial thinking',
      toolCalls: [],
      createdAt: '2026-07-28T10:00:00Z',
      status: 'streaming',
    }

    const timeline = buildThreadTimeline(
      [],
      [],
      [],
      stale,
      null,
      [modelAttemptFailure()],
    )

    expect(timeline.messages).toMatchObject([
      {
        role: 'model_attempt_failure',
        attempt: 1,
        text: 'partial answer',
        thinking: 'partial thinking',
      },
    ])
    expect(timeline.messages).toHaveLength(1)
  })

  it('does not let a malformed snapshot failure hide the current realtime overlay', () => {
    const current: RealtimeModelStream = {
      threadId: 'thread-1',
      invocationId: 'model-1',
      attempt: 1,
      sequence: 3,
      text: 'current output',
      thinking: '',
      toolCalls: [],
      createdAt: '2026-07-28T10:00:00Z',
      status: 'streaming',
    }
    const malformed = modelAttemptFailure({
      sequence: 3 as unknown as ModelAttemptFailureDTO['sequence'],
    })

    const timeline = buildThreadTimeline([], [], [], current, null, [malformed])

    expect(timeline.messages).toMatchObject([
      {
        role: 'assistant',
        text: 'current output',
      },
    ])
    expect(timeline.messages).toHaveLength(1)
  })

  it('only marks a failure as live while its next Provider attempt is still pending', () => {
    const failure = modelAttemptFailure()
    const pending = buildThreadTimeline(
      [],
      [],
      [],
      null,
      null,
      [failure],
      modelInvocation('READY', 1),
    )
    const running = buildThreadTimeline(
      [],
      [],
      [],
      null,
      null,
      [failure],
      modelInvocation('RUNNING', 2),
    )

    expect(pending.messages[0]).toMatchObject({
      role: 'model_attempt_failure',
      modelInvocationId: 'model-1',
      nextAttempt: 2,
    })
    expect(running.messages[0]).toMatchObject({
      role: 'model_attempt_failure',
      nextAttempt: 2,
    })
    expect(running.messages[0]).not.toHaveProperty('modelInvocationId')
  })

  it('projects a terminal pending attempt as partial content plus a separate error', () => {
    const terminal: RealtimeModelStream = {
      threadId: 'thread-1',
      invocationId: 'model-1',
      attempt: 2,
      sequence: 7,
      text: 'terminal partial',
      thinking: 'terminal thinking',
      toolCalls: [],
      createdAt: '2026-07-28T10:00:00Z',
      status: 'error',
      errorCode: 'INVALID_REQUEST',
      errorText: 'terminal failure',
    }

    const timeline = buildThreadTimeline(
      [],
      [],
      [],
      terminal,
      null,
      [],
      modelInvocation('FAILED', 2),
    )

    expect(timeline.messages).toMatchObject([
      {
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

  it('keeps the terminal barrier visible when its attempt was already recorded as failed', () => {
    const terminal: RealtimeModelStream = {
      threadId: 'thread-1',
      invocationId: 'model-1',
      attempt: 1,
      sequence: 0,
      text: '',
      thinking: '',
      toolCalls: [],
      createdAt: '2026-07-28T10:00:06Z',
      status: 'error',
      errorCode: 'INVALID_REQUEST',
      errorText: 'retry budget exhausted',
    }

    const timeline = buildThreadTimeline(
      [],
      [],
      [],
      terminal,
      null,
      [modelAttemptFailure()],
      modelInvocation('FAILED', 1),
    )

    expect(timeline.messages.map((message) => message.role)).toEqual([
      'model_attempt_failure',
      'assistant',
    ])
    expect(timeline.messages[1]).toMatchObject({
      role: 'assistant',
      text: 'retry budget exhausted',
      status: 'error',
    })
  })

  it('projects a pending cancellation checkpoint like the durable aborted Entry', () => {
    const cancelled: RealtimeModelStream = {
      threadId: 'thread-1',
      invocationId: 'model-1',
      attempt: 1,
      sequence: 4,
      text: 'stopped partial',
      thinking: 'stopped thinking',
      toolCalls: [],
      createdAt: '2026-07-28T10:00:00Z',
      status: 'error',
      errorCode: 'CANCELLED',
      errorText: 'stopped by user',
    }

    const timeline = buildThreadTimeline(
      [],
      [],
      [],
      cancelled,
      null,
      [],
      modelInvocation('CANCELLED', 1),
    )

    expect(timeline.messages).toMatchObject([
      {
        role: 'assistant',
        text: 'stopped partial',
        thinking: 'stopped thinking',
        status: 'done',
        aborted: true,
      },
    ])
  })

  it('keeps multiple active failed attempts ordered and separate', () => {
    const failures = [
      modelAttemptFailure({
        attempt: 2,
        sequence: '4',
        text: 'second partial',
        thinking: 'second thinking',
        errorMessage: 'second error',
      }),
      modelAttemptFailure(),
      modelAttemptFailure(),
    ]

    const timeline = buildThreadTimeline([], [], [], null, null, failures)
    const projected = timeline.messages.filter(
      (message) => message.role === 'model_attempt_failure',
    )

    expect(projected.map((message) => message.attempt)).toEqual([1, 2])
    expect(projected.map((message) => message.text)).toEqual([
      'partial answer',
      'second partial',
    ])
    expect(projected[0]).not.toBe(projected[1])
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

  it('keeps a blank payload Entry inspectable through the durable Entry fallback', () => {
    const root = {
      ...entry('root', 'ROOT', {}),
      payloadJson: '',
    }

    const timeline = buildThreadTimeline([root], [], [])

    expect(timeline.messages).toMatchObject([
      { role: 'entry', kind: 'invalid_settings', subjectEntryId: 'root' },
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
      { idempotencyKey: 'cid-in-2', role: 'user', text: '排队中', sequence: '1' },
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

  it('projects control boundaries as no entry rows plus the TURN_END footer meta', () => {
    const settings = {
      agentName: 'JIJI', model: { providerName: 'provider', modelName: 'model', variant: 'default' },
      environmentName: null,
    }
    const timeline = buildThreadTimeline(
      [
        entry('0', 'ROOT', { settings }),
        entry('1', 'TURN_START', { reason: 'INPUT', settings }),
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

    // TURN_START 不投影；TURN_END 恒定投影一条携带真实 Entry id 的页脚 meta
    // （回合 footer 的“从此处分支”只认这个 id，与 usage 是否存在无关）。
    expect(timeline.messages.map((m) => m.id)).toEqual([
      'entry:0',
      'meta-turn-end-2',
      '3',
    ])
    expect(timeline.messages[1]?.role).toBe('meta')
    // endEntryId 由 usage-owner 加在共享类型上；合并后可直接读字段。
    expect((timeline.messages[1] as { endEntryId?: string } | undefined)?.endEntryId).toBe('2')
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
        contents: [],
        status: 'done',
      },
      {
        role: 'tool',
        phase: 'result',
        toolName: 'bash',
        rendererKey: 'shell-command',
        arguments: '{"command":"ls"}',
        contents: [
          { type: 'text', text: 'ok' },
          { type: 'json', value: '{"nested":true,"n":1}' },
          { type: 'json', value: '[1,2]' },
        ],
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
        callIndex: 0,
        status: 'WAITING_APPROVAL',
        attempt: 1,
        toolCallId: 'call-1',
        toolName: 'bash',
        rendererKey: 'bash',
        environment: null,
        argumentsJson: '{"command":"ls"}',
        approvalJson: JSON.stringify({ required: true, decision: null, decisionId: null }),
        resultJson: null,
        errorJson: null,
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
      status: 'done',
      invocationStatus: 'WAITING_APPROVAL',
      invocationId: 'inv-1',
      partialContents: [{ type: 'text', text: 'streaming partial output' }],
      approval: { required: true, decision: null, decisionId: null },
    })
  })

  it('projects overlays by durable identity, never by reused toolCallId on an older Entry', () => {
    const invocation: ToolInvocationDTO = {
      id: 'inv-2',
      modelInvocationId: 'm-2',
      assistantEntryId: '41',
      callIndex: 0,
      status: 'WAITING_APPROVAL',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'bash',
      rendererKey: 'bash',
      environment: null,
      argumentsJson: '{"command":"ls"}',
      approvalJson: JSON.stringify({ required: true, decision: null, decisionId: null }),
      resultJson: null,
      errorJson: null,
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
    expect(calls[0]).not.toHaveProperty('partialContents')
    expect(calls[0]).not.toHaveProperty('approval')
    expect(calls[1]).toMatchObject({
      subjectEntryId: '41',
      toolCallId: 'call-1',
      status: 'done',
      invocationStatus: 'WAITING_APPROVAL',
      invocationId: 'inv-2',
      partialContents: [{ type: 'text', text: 'second-turn partial output' }],
      approval: { required: true, decision: null, decisionId: null },
    })
  })

  it('does not project an invocation whose toolCallId disagrees with the durable call', () => {
    const invocation: ToolInvocationDTO = {
      id: 'inv-3',
      modelInvocationId: 'm-3',
      assistantEntryId: '42',
      callIndex: 0,
      status: 'WAITING_APPROVAL',
      attempt: 1,
      toolCallId: 'call-other',
      toolName: 'bash',
      rendererKey: 'bash',
      environment: null,
      argumentsJson: '{"command":"ls"}',
      approvalJson: JSON.stringify({ required: true, decision: null, decisionId: null }),
      resultJson: null,
      errorJson: null,
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

  it('projects terminal invocation error overlay and drops partial on attempt mismatch', () => {
    // overlay 与 invocation 的 attempt 不一致时视为陈旧（不投影 partial）；终态
    // errorJson 存在时 status 投影为 error，partial 错误文案来自 errorText。
    const invocations: ToolInvocationDTO[] = [
      invocation('inv-term', '40', 'call-1', {
        status: 'FAILED',
        attempt: 1,
        resultJson: null,
        errorJson: JSON.stringify({ message: 'tool crashed' }),
        approvalJson: null,
      }),
    ]
    const staleStream: RealtimeToolStream = {
      threadId: 'thread-1',
      invocationId: 'inv-term',
      attempt: 2,
      toolCallId: 'call-1',
      text: 'stale partial',
      error: false,
      createdAt: '2026-07-28T10:00:01Z',
    }
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
      new Map([['inv-term', staleStream]]),
    )
    const call = timeline.messages.find((m) => m.role === 'tool' && m.phase === 'call')
    expect(call).toMatchObject({
      role: 'tool',
      phase: 'call',
      status: 'error',
      invocationId: 'inv-term',
    })
    // attempt 不一致的 overlay 不投影 partial / partialErrorText（builder 显式
    // 赋 undefined own property，因此用 toBeUndefined 断言）。
    expect(call?.partialContents).toBeUndefined()
    expect(call?.partialErrorText).toBeUndefined()
  })

  it('projects terminal success via resultJson and drops the stale streaming overlay', () => {
    // resultJson 已物化的终态 overlay：status 投影为 done，partial 字段消失。
    const invocations: ToolInvocationDTO[] = [
      invocation('inv-done', '40', 'call-1', {
        status: 'COMPLETED',
        attempt: 1,
        resultJson: JSON.stringify({ contents: [{ type: 'text', text: 'ok' }], error: false }),
        errorJson: null,
        approvalJson: null,
      }),
    ]
    const liveStream: RealtimeToolStream = {
      threadId: 'thread-1',
      invocationId: 'inv-done',
      attempt: 1,
      toolCallId: 'call-1',
      text: 'streaming partial',
      error: false,
      createdAt: '2026-07-28T10:00:01Z',
    }
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
      new Map([['inv-done', liveStream]]),
    )
    const call = timeline.messages.find((m) => m.role === 'tool' && m.phase === 'call')
    expect(call).toMatchObject({
      role: 'tool',
      phase: 'call',
      status: 'done',
      invocationId: 'inv-done',
    })
    // 终态 result 到达后，同 attempt 的旧 partial 不再覆盖最终正文。
    expect(call?.partialContents).toBeUndefined()
    expect(timeline.messages.find((message) => message.role === 'tool' && message.phase === 'result'))
      .toMatchObject({ contents: [{ type: 'text', text: 'ok' }], status: 'done' })
  })

  it('deduplicates tool invocations by assistantEntryId:callIndex identity', () => {
    // 同一持久身份（assistantEntryId:callIndex）的重复 invocation 只取第一条：
    // 第二条不得改写第一条的 overlay（保持第一条的 approval 状态）。
    const duplicates: ToolInvocationDTO[] = [
      invocation('inv-a', '40', 'call-1', {
        status: 'WAITING_APPROVAL',
        attempt: 1,
        approvalJson: JSON.stringify({ required: true, decision: null, decisionId: null }),
      }),
      invocation('inv-b', '40', 'call-1', {
        status: 'RUNNING',
        attempt: 1,
        approvalJson: null,
      }),
    ]
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
      duplicates,
    )
    const call = timeline.messages.find((m) => m.role === 'tool' && m.phase === 'call')
    expect(call).toMatchObject({
      invocationId: 'inv-a',
      approval: { required: true, decision: null, decisionId: null },
    })
    // 第二条 invocation 未命中（byDurableIdentity 去重），overlay 无 partial。
    expect(call?.partialContents).toBeUndefined()
  })

  it('skips overlay when invocation rendererKey disagrees with the durable call', () => {
    // 只有 rendererKey 也精确匹配的 invocation 才允许投影：不同 renderer 的
    // invocation 不能覆盖 durable tool call 的展示身份。
    const mismatched: ToolInvocationDTO[] = [
      invocation('inv-key', '40', 'call-1', {
        status: 'WAITING_APPROVAL',
        attempt: 1,
        rendererKey: 'other-renderer',
        approvalJson: JSON.stringify({ required: true, decision: null, decisionId: null }),
      }),
    ]
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
      mismatched,
    )
    const call = timeline.messages.find((m) => m.role === 'tool' && m.phase === 'call')
    expect(call).toMatchObject({ subjectEntryId: '40', toolCallId: 'call-1', status: 'done' })
    expect(call).not.toHaveProperty('invocationId')
    expect(call).not.toHaveProperty('approval')
    expect(timeline.messages.filter((m) => m.role === 'tool' && m.phase === 'result')).toEqual([])
  })

  it('shows a succeeded sibling result while another tool in the batch is still waiting', () => {
    // 同批兄弟仍 WAITING_APPROVAL 时，已成功调用必须按自身 resultJson 展示，不能一起 pending。
    const timeline = buildThreadTimeline(
      [
        entry('40', 'MESSAGE', messagePayload('ASSISTANT', [
          toolCallContent('call-ok', 0),
          toolCallContent('call-ask', 1),
        ])),
      ],
      [],
      [
        invocation('inv-ok', '40', 'call-ok', {
          status: 'SUCCEEDED',
          callIndex: 0,
          resultJson: JSON.stringify({
            contents: [
              { type: 'text', text: 'listed files' },
              { type: 'resource', uri: 'file:///tmp/out.txt', mediaType: 'text/plain', name: 'out.txt', size: 4, sha256: 'a'.repeat(64) },
            ],
            error: false,
          }),
          environmentId: 'env-must-not-be-thread',
        }),
        invocation('inv-ask', '40', 'call-ask', {
          status: 'WAITING_APPROVAL',
          callIndex: 1,
          toolCallId: 'call-ask',
          approvalJson: JSON.stringify({ required: true, decision: null, decisionId: null }),
        }),
      ],
      null,
      null,
      [],
      modelInvocation('RUNNING', 1),
    )
    const tools = timeline.messages.filter((message) => message.role === 'tool')
    expect(tools).toMatchObject([
      { phase: 'call', toolCallId: 'call-ok', status: 'done', threadId: 'thread-1' },
      {
        phase: 'result',
        toolCallId: 'call-ok',
        contents: [
          { type: 'text', text: 'listed files' },
          expect.objectContaining({ type: 'resource' }),
        ],
        status: 'done',
      },
      { phase: 'call', toolCallId: 'call-ask', status: 'done', invocationStatus: 'WAITING_APPROVAL', threadId: 'thread-1' },
    ])
    // 有序内容：文本在前、资源在后，不被压平成「全部文本 + 全部附件」。
    expect(tools[1]?.role === 'tool' ? tools[1].contents : []).toEqual([
      { type: 'text', text: 'listed files' },
      expect.objectContaining({
        type: 'resource',
        attachment: expect.objectContaining({ name: 'out.txt', mime: 'text/plain' }),
      }),
    ])
    const waiting = tools.find((message) => message.toolCallId === 'call-ask')
    expect(waiting?.role === 'tool' ? waiting.partialContents : ['present']).toBeUndefined()
    expect(tools.filter((message) => message.phase === 'result')).toHaveLength(1)
  })

  it('projects failed, error-result, and unmatched invocations without treating argument completion as success', () => {
    // error=true 即使 invocation SUCCEEDED 也是错误；FAILED/UNKNOWN/CANCELLED 无正文同样是错误。
    // 身份不匹配或没有终态证据时保持 pending，不能把 durable call.status=done 当成成功。
    const failed = buildThreadTimeline(
      [assistantCalls(['call-error', 'call-failed', 'call-unknown', 'call-cancelled', 'call-live'])],
      [],
      [
        invocation('inv-error', '40', 'call-error', {
          status: 'SUCCEEDED',
          callIndex: 0,
          resultJson: JSON.stringify({ contents: [{ type: 'text', text: 'rejected' }], error: true }),
        }),
        invocation('inv-failed', '40', 'call-failed', {
          status: 'FAILED',
          callIndex: 1,
          toolCallId: 'call-failed',
        }),
        invocation('inv-unknown', '40', 'call-unknown', {
          status: 'UNKNOWN',
          callIndex: 2,
          toolCallId: 'call-unknown',
          errorJson: JSON.stringify({ message: 'lost' }),
        }),
        invocation('inv-cancelled', '40', 'call-cancelled', {
          status: 'CANCELLED',
          callIndex: 3,
          toolCallId: 'call-cancelled',
        }),
        invocation('inv-live', '40', 'call-live', {
          status: 'RUNNING',
          callIndex: 4,
          toolCallId: 'call-live',
        }),
      ],
    )
    expect(failed.messages.filter((message) => message.role === 'tool')).toMatchObject([
      { toolCallId: 'call-error', phase: 'call', status: 'error' },
      {
        toolCallId: 'call-error',
        phase: 'result',
        status: 'error',
        contents: [{ type: 'text', text: 'rejected' }],
      },
      { toolCallId: 'call-failed', phase: 'call', status: 'error' },
      { toolCallId: 'call-failed', phase: 'result', status: 'error' },
      { toolCallId: 'call-unknown', phase: 'call', status: 'error' },
      { toolCallId: 'call-unknown', phase: 'result', status: 'error' },
      { toolCallId: 'call-cancelled', phase: 'call', status: 'error' },
      { toolCallId: 'call-cancelled', phase: 'result', status: 'error' },
      { toolCallId: 'call-live', phase: 'call', status: 'streaming' },
    ])

    const mismatched = buildThreadTimeline(
      [assistantCalls(['call-1'])],
      [],
      [invocation('inv-other-entry', 'other-entry', 'call-1', {
        status: 'SUCCEEDED',
        resultJson: JSON.stringify({ contents: [{ type: 'text', text: 'wrong call' }], error: false }),
      })],
    )
    expect(mismatched.messages.filter((message) => message.role === 'tool')).toMatchObject([
      { phase: 'call', status: 'done' },
    ])
  })

  it('keeps a reused toolCallId on the current assistant independent of an older result', () => {
    // 历史 assistant A 的 result 不能按 toolCallId 屏蔽当前 assistant B 的 transient result。
    const timeline = buildThreadTimeline(
      [
        entry('A', 'MESSAGE', messagePayload('ASSISTANT', [toolCallContent('x', 0)])),
        entry('A-result', 'MESSAGE', {
          ...messagePayload('TOOL', [{
            type: 'tool_result',
            toolCallId: 'x',
            toolName: 'bash',
            rendererKey: 'bash',
            error: false,
            contents: [{ type: 'text', text: 'old output' }],
          }]),
          toolResultMetadata: {
            assistantEntryId: 'A',
            toolCallId: 'x',
            callIndex: 0,
            status: 'SUCCEEDED',
            synthetic: false,
            reason: null,
          },
        }),
        entry('B', 'MESSAGE', messagePayload('ASSISTANT', [toolCallContent('x', 0)])),
      ],
      [],
      [invocation('inv-b', 'B', 'x', {
        status: 'SUCCEEDED',
        resultJson: JSON.stringify({ contents: [{ type: 'text', text: 'current output' }], error: false }),
      })],
    )
    const current = timeline.messages.filter((message) =>
      message.role === 'tool' && (message.subjectEntryId === 'B' || message.callIdentity === 'B:0'),
    )
    expect(current).toMatchObject([
      { phase: 'call', callIdentity: 'B:0', status: 'done' },
      {
        phase: 'result',
        callIdentity: 'B:0',
        contents: [{ type: 'text', text: 'current output' }],
        status: 'done',
      },
    ])
    expect(timeline.messages.filter((message) => message.role === 'tool' && message.phase === 'result')
      .map((message) => message.contents)).toEqual([
      [{ type: 'text', text: 'old output' }],
      [{ type: 'text', text: 'current output' }],
    ])
  })

  it('does not exchange results between reused ids or different call indexes', () => {
    // 两条历史复用和同批两个 callIndex 都必须各自配对，不能拿相邻结果。
    const timeline = buildThreadTimeline(
      [
        entry('A', 'MESSAGE', messagePayload('ASSISTANT', [
          toolCallContent('x', 0),
          toolCallContent('x', 1),
        ])),
        entry('A-result-0', 'MESSAGE', {
          ...messagePayload('TOOL', [toolResult('x', 'first output')]),
          toolResultMetadata: toolResultMetadata('A', 'x', 0),
        }),
        entry('A-result-1', 'MESSAGE', {
          ...messagePayload('TOOL', [toolResult('x', 'second output')]),
          toolResultMetadata: toolResultMetadata('A', 'x', 1),
        }),
        entry('B', 'MESSAGE', messagePayload('ASSISTANT', [
          toolCallContent('x', 0),
          toolCallContent('x', 1),
        ])),
        entry('B-result', 'MESSAGE', {
          ...messagePayload('TOOL', [toolResult('x', 'current first')]),
          toolResultMetadata: toolResultMetadata('B', 'x', 0),
        }),
      ],
      [],
      [
        invocation('inv-b0', 'B', 'x', {
          callIndex: 0,
          status: 'SUCCEEDED',
          resultJson: JSON.stringify({ contents: [{ type: 'text', text: 'stale transient' }], error: false }),
        }),
        invocation('inv-b1', 'B', 'x', {
          callIndex: 1,
          status: 'SUCCEEDED',
          resultJson: JSON.stringify({ contents: [{ type: 'text', text: 'current second' }], error: false }),
          errorJson: JSON.stringify({ code: 'TOOL_FAILED', message: 'disk full' }),
        }),
      ],
    )
    expect(timeline.messages.filter((message) => message.role === 'tool')).toMatchObject([
      { phase: 'call', callIdentity: 'A:0' },
      { phase: 'call', callIdentity: 'A:1' },
      { phase: 'result', callIdentity: 'A:0', contents: [{ type: 'text', text: 'first output' }] },
      { phase: 'result', callIdentity: 'A:1', contents: [{ type: 'text', text: 'second output' }] },
      { phase: 'call', callIdentity: 'B:0' },
      { phase: 'call', callIdentity: 'B:1', status: 'error', errorMessage: 'disk full' },
      {
        phase: 'result',
        callIdentity: 'B:1',
        contents: [{ type: 'text', text: 'current second' }],
        status: 'error',
        errorMessage: 'disk full',
      },
      { phase: 'result', callIdentity: 'B:0', contents: [{ type: 'text', text: 'current first' }] },
    ])
    expect(timeline.messages.some((message) =>
      message.role === 'tool' && message.id.startsWith('transient:tool-result:inv-b0'))).toBe(false)
    expect(timeline.messages.filter((message) =>
      message.role === 'tool'
      && message.contents.some((content) => content.type === 'text' && content.text === 'first output'),
    )).toHaveLength(1)
  })

  it('replaces a transient invocation result with the durable result and keeps a later success', () => {
    // durable tool result 到达后是唯一输出；同 attempt 的旧 partial 错误不能盖过最终成功。
    const timeline = buildThreadTimeline(
      [
        assistantCalls(['call-1']),
        entry('41', 'MESSAGE', {
          ...messagePayload('TOOL', [{
            type: 'tool_result',
            toolCallId: 'call-1',
            toolName: 'bash',
            rendererKey: 'bash',
            error: false,
            contents: [{ type: 'text', text: 'durable final' }],
          }]),
          toolResultMetadata: toolResultMetadata('40', 'call-1', 0),
        }),
      ],
      [],
      [invocation('inv-1', '40', 'call-1', {
        status: 'SUCCEEDED',
        resultJson: JSON.stringify({ contents: [{ type: 'text', text: 'transient ok' }], error: false }),
      })],
      null,
      new Map([['inv-1', {
        threadId: 'thread-1',
        invocationId: 'inv-1',
        attempt: 1,
        toolCallId: 'call-1',
        text: 'stale partial',
        error: true,
        errorText: 'temporary',
        createdAt: '2026-07-28T10:00:01Z',
      }]]),
    )
    const tools = timeline.messages.filter((message) => message.role === 'tool')
    expect(tools).toMatchObject([
      { phase: 'call', status: 'done', callIdentity: '40:0' },
      {
        phase: 'result',
        contents: [{ type: 'text', text: 'durable final' }],
        status: 'done',
        callIdentity: '40:0',
      },
    ])
    expect(tools[0]?.role === 'tool' ? tools[0].partial : 'present').toBeUndefined()
    expect(tools[0]?.role === 'tool' ? tools[0].partialErrorText : 'present').toBeUndefined()
    expect(tools.filter((message) => message.phase === 'result')).toHaveLength(1)
    expect(tools.some((message) => message.id.startsWith('transient:tool-result:'))).toBe(false)
  })

  it('keeps a terminal model stream with content as a done assistant message', () => {
    // modelStream status='done'（持久终止态 resultJson 投影）走非 streaming 分支：
    // 有文本/思考时投影为 done assistant，而不是丢弃。
    const done: RealtimeModelStream = {
      threadId: 'thread-1',
      invocationId: 'model-1',
      attempt: 1,
      sequence: 5,
      text: 'final answer',
      thinking: '',
      toolCalls: [],
      createdAt: '2026-07-28T10:00:00Z',
      status: 'done',
    }
    const timeline = buildThreadTimeline([], [], [], done)
    expect(timeline.messages).toMatchObject([
      {
        role: 'assistant',
        text: 'final answer',
        status: 'done',
      },
    ])
  })

  it('projects an approval overlay with decision, reason, and non-required flag', () => {
    // 审批投影：required=true 且决策已落地时保留 decision/reason；required=false
    // 的审批条目不投影（builder 只输出 required 的 approval）。
    const invocations: ToolInvocationDTO[] = [
      invocation('inv-decided', '40', 'call-1', {
        status: 'COMPLETED',
        attempt: 1,
        approvalJson: JSON.stringify({
          required: true,
          decision: 'ALLOWED',
          decisionId: 'dec-1',
          reason: 'user approved',
        }),
      }),
    ]
    const decided = buildThreadTimeline(
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
    )
    expect(decided.messages.find((m) => m.role === 'tool')).toMatchObject({
      approval: {
        required: true,
        decision: 'ALLOWED',
        decisionId: 'dec-1',
        reason: 'user approved',
      },
    })
    const notRequired = buildThreadTimeline(
      [
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
      [invocation('inv-free', '41', 'call-1', {
        status: 'RUNNING',
        attempt: 1,
        approvalJson: JSON.stringify({ required: false, decision: null }),
      })],
    )
    const free = notRequired.messages.find((m) => m.role === 'tool')
    // required=false 的审批不投影 approval（builder 显式赋 undefined own property）。
    expect(free?.approval).toBeUndefined()
  })

  it('derives working from thread status/processing and pending queued inputs', () => {
    expect(isThreadWorking(
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
    idempotencyKey: `cid-${label}`,
    payloadJson: JSON.stringify(payload),
    cancelledAt: null,
    createTime: '2026-01-01T00:00:00',
  }
}

function messagePayload(role: string, contents: Array<Record<string, unknown>>) {
  return {
    message: { role, contents },
  }
}

function toolResultMetadata(
  assistantEntryId: string,
  toolCallId: string,
  callIndex: number,
): Record<string, unknown> {
  return {
    assistantEntryId,
    toolCallId,
    callIndex,
    status: 'SUCCEEDED',
    synthetic: false,
    reason: null,
  }
}

function toolResult(toolCallId: string, text: string): Record<string, unknown> {
  return {
    type: 'tool_result',
    toolCallId,
    toolName: 'bash',
    rendererKey: 'bash',
    error: false,
    contents: [{ type: 'text', text }],
  }
}

function toolCallContent(toolCallId: string, _callIndex: number): Record<string, unknown> {
  return {
    type: 'tool_call',
    toolCallId,
    toolName: 'bash',
    rendererKey: 'bash',
    argumentsJson: '{"command":"ls"}',
  }
}

function assistantCalls(toolCallIds: string[]): HarnessSessionEntryDTO {
  return entry('40', 'MESSAGE', messagePayload(
    'ASSISTANT',
    toolCallIds.map((toolCallId, index) => toolCallContent(toolCallId, index)),
  ))
}

function modelAttemptFailure(
  overrides: Partial<ModelAttemptFailureDTO> = {},
): ModelAttemptFailureDTO {
  return {
    modelInvocationId: 'model-1',
    turnStartEntryId: 'turn-1',
    requestHeadEntryId: 'head-1',
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

function modelInvocation(status: string, attempt: number): ModelInvocationDTO {
  return {
    id: 'model-1',
    threadId: 'thread-1',
    turnStartEntryId: 'turn-1',
    requestHeadEntryId: 'head-1',
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

function invocation(
  id: string,
  assistantEntryId: string,
  toolCallId: string,
  overrides: Partial<ToolInvocationDTO> = {},
): ToolInvocationDTO {
  return {
    id,
    modelInvocationId: `m-${id}`,
    assistantEntryId,
    callIndex: 0,
    status: 'RUNNING',
    attempt: 1,
    toolCallId,
    toolName: 'bash',
    rendererKey: 'bash',
    environmentId: null,
    requiredEnvironmentId: null,
    waitingForEnvironment: false,
    requiredEnvironmentName: null,
    environmentWaitFreshnessAt: null,
    argumentsJson: '{"command":"ls"}',
    approvalJson: null,
    resultJson: null,
    errorJson: null,
    createTime: '2026-07-28T10:00:00Z',
    updateTime: '2026-07-28T10:00:00Z',
    ...overrides,
  }
}
