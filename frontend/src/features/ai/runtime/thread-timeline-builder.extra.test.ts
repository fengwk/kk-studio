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
          requestHeadEntryId: 'head-1',
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
      callIndex: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'bash',
      toolVersion: '1',
      rendererKey: 'bash',
      toolId: 'base.bash',
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

  it('projects empty USER/SYSTEM/TOOL entries and unknown non-message entries', () => {
    // 防御性 fallback：无内容的 USER/SYSTEM 消息投影为 empty_message 事件；
    // TOOL role 没有 tool_result 时同样投影 empty_message；未知 entryType
    // （此处 'CUSTOM' 与 'SOMETHING_ELSE'）投影为 unknown_entry，而不是静默丢弃。
    const timeline = buildThreadTimeline(
      [
        entry('empty-user', 'MESSAGE', { message: { role: 'USER', contents: [] } }),
        entry('empty-system', 'MESSAGE', {
          message: { role: 'SYSTEM', contents: [{ type: 'text', text: '' }] },
        }),
        entry('empty-tool', 'MESSAGE', {
          message: { role: 'TOOL', contents: [{ type: 'unexpected', text: 'x' }] },
        }),
        entry('unknown-type', 'CUSTOM', { arbitrary: true }),
        entry('unknown-other', 'SOMETHING_ELSE', { arbitrary: true }),
      ],
      [],
      [],
    )
    expect(timeline.messages.map((m) => [m.role, (m as { kind?: string }).kind])).toEqual([
      ['entry', 'empty_message'],
      ['entry', 'empty_message'],
      ['entry', 'empty_message'],
      ['entry', 'unknown_entry'],
      ['entry', 'unknown_entry'],
    ])
    // 两个 unknown Entry 的 title 展示各自的 entryType（含未知枚举原文）。
    expect(timeline.messages[3]).toMatchObject({ title: '未识别 Entry：CUSTOM' })
    expect(timeline.messages[4]).toMatchObject({ title: '未识别 Entry：SOMETHING_ELSE' })
    expect(timeline.messages[0]).toMatchObject({
      title: 'USER 消息',
      text: '该消息 Entry 没有可展示的文本、思考、工具调用或工具结果。',
    })
    // 无 text 且有 tool_call 的 ASSISTANT（含无 toolCallId 的残缺调用）：
    // tool call 正常投影，空 arguments 使用 queue 默认值，title 的 role 插值。
    const assistantOnlyCall = buildThreadTimeline(
      [
        entry('assistant-call', 'MESSAGE', {
          message: {
            role: 'ASSISTANT',
            contents: [{ type: 'tool_call', toolCallId: 'call-x', toolName: 'read', rendererKey: 'read' }],
          },
        }),
        entry(
          'tool-x',
          'MESSAGE',
          messagePayload('TOOL', [
            {
              type: 'tool_result',
              toolCallId: 'call-x',
              toolName: 'read',
              rendererKey: 'read',
              contents: [{ type: 'text', text: 'ok' }],
            },
          ]),
        ),
      ],
      [],
      [],
    )
    expect(assistantOnlyCall.messages).toMatchObject([
      { role: 'tool', phase: 'call', toolCallId: 'call-x', status: 'done' },
      { role: 'tool', phase: 'result', arguments: '', text: 'ok' },
    ])
  })

  it('projects ASSISTANT thinking-only messages and aborted checkpoints with thinking', () => {
    // ASSISTANT 只有 thinking 没有 text 时仍投影 assistant 消息（thinking 分支）；
    // ASSISTANT_ABORTED 的 thinking 内容同样保留。
    const timeline = buildThreadTimeline(
      [
        entry('thinking-only', 'MESSAGE', {
          message: {
            role: 'ASSISTANT',
            contents: [{ type: 'thinking', text: 'deep thought' }],
          },
        }),
        entry('aborted-thinking', 'ASSISTANT_ABORTED', {
          message: {
            role: 'ASSISTANT',
            contents: [
              { type: 'text', text: 'stopped text' },
              { type: 'thinking', text: 'stopped thinking' },
            ],
          },
        }),
        entry('aborted-empty', 'ASSISTANT_ABORTED', {
          message: { role: 'ASSISTANT', contents: [] },
        }),
      ],
      [],
      [],
    )
    expect(timeline.messages).toMatchObject([
      { role: 'assistant', text: '', thinking: 'deep thought', status: 'done' },
      {
        role: 'assistant',
        text: 'stopped text',
        thinking: 'stopped thinking',
        status: 'done',
        aborted: true,
      },
    ])
    // 无 text/thinking 的 aborted Entry 不投影任何消息。
    expect(timeline.messages).toHaveLength(2)
  })

  it('falls back to the default text when ASSISTANT_ERROR has no message', () => {
    const timeline = buildThreadTimeline(
      [entry('err', 'ASSISTANT_ERROR', { error: { code: 'UNKNOWN' }, attempt: null })],
      [],
      [],
    )
    expect(timeline.messages).toMatchObject([
      { id: 'err', role: 'assistant', text: '助手请求失败', status: 'error' },
    ])
  })

  it('falls back to unknown_entry when MODEL_ATTEMPT_FAILURE misses attempt or retryAt', () => {
    // durable failure 必须同时具备 attempt snapshot 与 retryAt 才投影为
    // model_attempt_failure；缺失其一都回退为可检查的 unknown_entry。
    const missingAttempt = buildThreadTimeline(
      [
        entry('f-miss-attempt', 'MODEL_ATTEMPT_FAILURE', {
          attempt: null,
          error: { code: 'TRANSIENT', message: 'x' },
          retryAt: '2026-01-01T00:00:05Z',
        }),
      ],
      [],
      [],
    )
    expect(missingAttempt.messages).toMatchObject([
      { role: 'entry', kind: 'unknown_entry', subjectEntryId: 'f-miss-attempt' },
    ])
    const missingRetry = buildThreadTimeline(
      [
        entry('f-miss-retry', 'MODEL_ATTEMPT_FAILURE', {
          attempt: { attempt: 1, sequence: 2, text: 'p', thinking: 't' },
          error: { code: 'TRANSIENT', message: 'x' },
          retryAt: null,
        }),
      ],
      [],
      [],
    )
    expect(missingRetry.messages).toMatchObject([
      { role: 'entry', kind: 'unknown_entry', subjectEntryId: 'f-miss-retry' },
    ])
    // 解析防御：attempt 快照必须通过完整校验（attempt<=0、sequence 非负整数、
    // text/thinking 为字符串、retryAt 为 string/number/数字数组），否则同样回退。
    const invalidAttempt = buildThreadTimeline(
      [
        entry('f-invalid-attempt', 'MODEL_ATTEMPT_FAILURE', {
          attempt: { attempt: 0, sequence: 2, text: 'p', thinking: 't' },
          error: { code: 'TRANSIENT', message: 'x' },
          retryAt: '2026-01-01T00:00:05Z',
        }),
      ],
      [],
      [],
    )
    expect(invalidAttempt.messages).toMatchObject([
      { role: 'entry', kind: 'unknown_entry', subjectEntryId: 'f-invalid-attempt' },
    ])
    const invalidText = buildThreadTimeline(
      [
        entry('f-invalid-text', 'MODEL_ATTEMPT_FAILURE', {
          attempt: { attempt: 1, sequence: 2, text: 123, thinking: 't' },
          error: { code: 'TRANSIENT', message: 'x' },
          retryAt: '2026-01-01T00:00:05Z',
        }),
      ],
      [],
      [],
    )
    expect(invalidText.messages).toMatchObject([
      { role: 'entry', kind: 'unknown_entry', subjectEntryId: 'f-invalid-text' },
    ])
    const retryArray = buildThreadTimeline(
      [
        entry('f-retry-array', 'MODEL_ATTEMPT_FAILURE', {
          attempt: { attempt: 1, sequence: 2, text: 'p', thinking: 't' },
          error: { code: 'TRANSIENT', message: 'x' },
          retryAt: [2026, 1, 1, 0, 0, 5],
        }),
      ],
      [],
      [],
    )
    expect(retryArray.messages).toMatchObject([
      {
        role: 'model_attempt_failure',
        retryAt: [2026, 1, 1, 0, 0, 5],
        nextAttempt: 2,
      },
    ])
    const invalidRetry = buildThreadTimeline(
      [
        entry('f-invalid-retry', 'MODEL_ATTEMPT_FAILURE', {
          attempt: { attempt: 1, sequence: 2, text: 'p', thinking: 't' },
          error: { code: 'TRANSIENT', message: 'x' },
          retryAt: { invalid: true },
        }),
      ],
      [],
      [],
    )
    expect(invalidRetry.messages).toMatchObject([
      { role: 'entry', kind: 'unknown_entry', subjectEntryId: 'f-invalid-retry' },
    ])
  })

  it('drops QUEUED CUSTOM_MESSAGE with blank contents and falls back unknown roles to user', () => {
    // QUEUED 命令提取防御：无文本内容不产生 queued message；非 SYSTEM 的
    // CUSTOM_MESSAGE role 按生产语义回退投影为 user（仅 SYSTEM 显式保留）。
    const timeline = buildThreadTimeline(
      [],
      [
        command('blank', '1', 'CUSTOM_MESSAGE', customMessagePayload('SYSTEM', ''), 'QUEUED'),
        command(
          'weird-role',
          '2',
          'CUSTOM_MESSAGE',
          { message: { role: 'AGENT', contents: [{ type: 'text', text: 'x' }] } },
          'QUEUED',
        ),
      ],
      [],
    )
    expect(timeline.messages).toEqual([])
    expect(timeline.queuedMessages).toMatchObject([
      { idempotencyKey: 'cid-weird-role', role: 'user', text: 'x', sequence: '2' },
    ])
    expect(timeline.hasPendingInputs).toBe(true)
  })

  it('projects unsupported message entries for unknown roles with or without role name', () => {
    // MESSAGE/CUSTOM_MESSAGE 出现非 USER/SYSTEM/ASSISTANT/TOOL 角色时投影为
    // unsupported_message；带角色名的标题带 role 插值，空角色用通用文案。
    const withRole = buildThreadTimeline(
      [
        entry('weird', 'MESSAGE', { message: { role: 'HUMAN', contents: [{ type: 'text', text: 'x' }] } }),
        entry('weird-custom', 'CUSTOM_MESSAGE', {
          message: { role: 'HUMAN', contents: [{ type: 'text', text: 'y' }] },
        }),
      ],
      [],
      [],
    )
    expect(withRole.messages).toMatchObject([
      {
        role: 'entry',
        kind: 'unsupported_message',
        title: '无法识别消息 Entry',
        text: '暂不支持的消息角色：HUMAN。原始 payload 可展开查看。',
        rawPayloadJson: JSON.stringify({
          message: { role: 'HUMAN', contents: [{ type: 'text', text: 'x' }] },
        }),
      },
      {
        role: 'entry',
        kind: 'unsupported_message',
        subjectEntryId: 'weird-custom',
      },
    ])
    const withoutRole = buildThreadTimeline(
      [entry('no-role', 'MESSAGE', { message: { contents: [{ type: 'text', text: 'x' }] } })],
      [],
      [],
    )
    expect(withoutRole.messages).toMatchObject([
      {
        role: 'entry',
        kind: 'unsupported_message',
        text: '消息角色或 payload 无效。原始 payload 可展开查看。',
      },
    ])
  })

  it('normalizes approval JSON defensively across shape and garbage inputs', () => {
    // parseApproval 的规范映射：未知 decision 归一为 null、非字符串/空白 reason
    // 归一为 null、字符串 decisionId 原样保留、required 缺失视为 false。
    expect(parseApproval(JSON.stringify({ required: true, decision: 'DENIED' }))).toEqual({
      required: true,
      decision: 'DENIED',
      decisionId: null,
      reason: null,
    })
    expect(parseApproval(JSON.stringify({ decision: 'ALLOW' }))).toEqual({
      required: false,
      decision: null,
      decisionId: null,
      reason: null,
    })
    expect(
      parseApproval(
        JSON.stringify({
          required: true,
          decision: 'ALLOWED',
          decisionId: 42,
          reason: '   ',
        }),
      ),
    ).toEqual({
      required: true,
      decision: 'ALLOWED',
      decisionId: null,
      reason: null,
    })
    expect(parseApproval('{"required":true}')).toEqual({
      required: true,
      decision: null,
      decisionId: null,
      reason: null,
    })
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
    requestHash: '0123456789abcdef'.repeat(4),
    payloadJson: JSON.stringify(payload),
    appliedTurnStartEntryId: null,
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
