import { describe, expect, it } from 'vitest'
import type { EntryType } from '@/shared/api/contracts/ai-runtime'
import { createBuiltinExecution } from '@/shared/api/contracts/ai-runtime'
import { setLocale } from '@/shared/i18n'
import {
  buildThreadTimeline,
  parseApproval,
} from '@/features/thread/runtime/thread-timeline-builder'
import { aggregateEntryUsage } from '@/features/thread/runtime/thread-timeline/turn-usage'
import type {
  RealtimeToolStream,
} from '@/features/thread/runtime/thread-realtime/tool-stream'
import type {
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
  ToolInvocationDTO,
} from '@/shared/api/generated/contracts'

describe('thread timeline edge branches', () => {
  it('projects custom user messages, empty user content, tool resources, and assistant errors', () => {
    const timeline = buildThreadTimeline(
      [
        entry('2', 'CUSTOM_MESSAGE', customMessagePayload('USER', '系统提示')),
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
                  uri: 'blob-upload:00000000-0000-4000-8000-000000000001',
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

    expect(timeline.messages.some((m) => m.role === 'user' && m.text === '系统提示')).toBe(true)
    expect(timeline.messages.some((m) => m.role === 'assistant' && String(m.text).includes('boom'))).toBe(true)
    expect(timeline.messages.some((m) => m.role === 'tool' && m.status === 'error')).toBe(true)
    // 工具结果保留有序内容：资源作为 contents 里的 resource 项，而不是扁平附件列表。
    expect(timeline.messages.some((m) =>
      m.role === 'tool'
      && m.contents.some((content) => content.type === 'resource'),
    )).toBe(true)
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
        retryAt: Date.parse('2026-01-01T00:00:05Z'),
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

  it('keeps durable custom messages in transcript while queued custom messages stay out of the editable queue', () => {
    // 契约：CUSTOM_MESSAGE 是编排产物而非人类输入，只出现在持久 transcript；
    // 可编辑队列/上下键历史只承载 USER_MESSAGE，并按 decoration 顺序稳定排列。
    const timeline = buildThreadTimeline(
      [
        entry('custom-first', 'CUSTOM_MESSAGE', customMessagePayload('USER', 'durable first')),
        entry('custom-user', 'CUSTOM_MESSAGE', customMessagePayload('USER', 'durable user')),
      ],
      [
        command('queued-custom', '1', 'CUSTOM_MESSAGE', customMessagePayload('USER', 'queued custom'), 'QUEUED'),
        command('queued-first', '2', 'USER_MESSAGE', customMessagePayload('USER', 'queued first'), 'QUEUED'),
        command('queued-user', '3', 'USER_MESSAGE', customMessagePayload('USER', 'queued user'), 'QUEUED'),
      ],
      [],
    )

    expect(timeline.messages).toMatchObject([
      { role: 'user', text: 'durable first' },
      { role: 'user', text: 'durable user' },
    ])
    expect(timeline.queuedMessages).toMatchObject([
      { role: 'user', text: 'queued first', sequence: '2' },
      { role: 'user', text: 'queued user', sequence: '3' },
    ])
    expect(timeline.queuedMessages.some((message) => message.text === 'queued custom')).toBe(false)
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
      { role: 'user', contents: [{ type: 'resource', attachment: { blobId, name: 'input.png' } }] },
      { role: 'tool', phase: 'call' },
      {
        role: 'tool',
        phase: 'result',
        contents: [{
          type: 'resource',
          attachment: {
            type: 'file',
            name: 'result.txt',
            blobId,
            preview: 'excerpt',
          },
        }],
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
        createdAt: Date.parse('2026-01-01T00:00:00Z'),
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
          generationId: 'model-1',
          turnStartEntryId: 'compaction-start',
          requestHeadEntryId: 'head-1',
          attempt: 1,
          sequence: '1',
          text: 'hidden',
          thinking: 'hidden',
          errorCode: 'ERROR',
          errorMessage: 'hidden',
          failedAt: 1767225600000,
          retryAt: 1767225601000,
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
        createdAt: Date.parse('2026-01-01T00:00:00Z'),
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
        createdAt: Date.parse('2026-01-01T00:00:00Z'),
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
      threadId: 'thread-1',
      turnStartEntryId: 'turn-1',
      assistantEntryId: 'assistant-1',
      callIndex: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'bash',
      rendererKey: 'bash',
      environmentId: null,
      requiredEnvironmentId: null,
      waitingForEnvironment: false,
      requiredEnvironmentName: null,
      environmentWaitFreshnessAt: null,
      argumentsJson: '{"command":"ls"}',
      approval: null,
      result: null,
      error: null,
      createdAt: 1767225600000,
      updatedAt: 1767225600000,
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
          {
            ...messagePayload('TOOL', [
              {
                type: 'tool_result',
                toolCallId: 'call-1',
                toolName: 'bash',
                rendererKey: 'bash',
                contents: [{ type: 'text', text: 'ok' }],
              },
            ]),
            toolResultMetadata: {
              assistantEntryId: 'assistant-1',
              toolCallId: 'call-1',
              callIndex: 0,
              status: 'SUCCEEDED',
              synthetic: false,
              reason: null,
            },
          },
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
          createdAt: Date.parse('2026-01-01T00:00:00Z'),
        }],
      ]),
    )
    // 只有 phase 'call' 的 tool 消息收到 invocationId/partial；result 消息保持原样。
    const result = timeline.messages.find(
      (message) => message.role === 'tool' && message.phase === 'result',
    )
    expect(result).toMatchObject({
      toolCallId: 'call-1',
      contents: [{ type: 'text', text: 'ok' }],
    })
    expect(result).not.toHaveProperty('invocationId')

    // parseApproval 的防御分支：null / 非法 JSON / 非 record 都返回 null。
    expect(parseApproval(null)).toBeNull()
    expect(parseApproval('not-json')).toBeNull()
    expect(parseApproval('[1]')).toBeNull()
  })

  it('projects empty USER/CUSTOM_MESSAGE/TOOL entries and unknown non-message entries', () => {
    // 防御性 fallback：无内容的 USER/CUSTOM_MESSAGE 消息投影为 empty_message 事件；
    // TOOL role 没有 tool_result 时同样投影 empty_message；未知 entryType
    // （此处两个未知名称）投影为 unknown_entry，而不是静默丢弃。
    const unknownEntry = entry('unknown-other', 'NOTIFICATION', { arbitrary: true })
    Reflect.set(unknownEntry, 'entryType', 'SOMETHING_ELSE')
    const timeline = buildThreadTimeline(
      [
        entry('empty-user', 'MESSAGE', { message: { role: 'USER', contents: [] } }),
        entry('empty-custom', 'CUSTOM_MESSAGE', {
          message: { role: 'USER', contents: [{ type: 'text', text: '' }] },
        }),
        entry('empty-tool', 'MESSAGE', {
          message: { role: 'TOOL', contents: [{ type: 'unexpected', text: 'x' }] },
        }),
        entry('unknown-type', 'UNKNOWN_TYPE' as EntryType, { arbitrary: true }),
        unknownEntry,
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
    expect(timeline.messages[3]).toMatchObject({ title: '未识别 Entry：UNKNOWN_TYPE' })
    expect(timeline.messages[4]).toMatchObject({ title: '未识别 Entry：SOMETHING_ELSE' })
    expect(timeline.messages[0]).toMatchObject({
      title: '此消息没有可展示内容。',
      text: '',
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
      { role: 'tool', phase: 'result', arguments: '', contents: [{ type: 'text', text: 'ok' }] },
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
    // text/thinking 为字符串、retryAt 为规范 instant 文本），否则同样回退。
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
    // payload 是历史 codec JSON：节点内时间只有规范 instant 文本一种形态，
    // 数组、数字与非规范文本都回退为 unknown_entry。
    const nonCanonicalRetry = buildThreadTimeline(
      [
        entry('f-retry-array', 'MODEL_ATTEMPT_FAILURE', {
          attempt: { attempt: 1, sequence: 2, text: 'p', thinking: 't' },
          error: { code: 'TRANSIENT', message: 'x' },
          retryAt: [2026, 1, 1, 0, 0, 5],
        }),
        entry('f-retry-number', 'MODEL_ATTEMPT_FAILURE', {
          attempt: { attempt: 1, sequence: 2, text: 'p', thinking: 't' },
          error: { code: 'TRANSIENT', message: 'x' },
          retryAt: 1767225605000,
        }),
        entry('f-retry-text', 'MODEL_ATTEMPT_FAILURE', {
          attempt: { attempt: 1, sequence: 2, text: 'p', thinking: 't' },
          error: { code: 'TRANSIENT', message: 'x' },
          retryAt: '2026-01-01T00:00:05.12Z',
        }),
      ],
      [],
      [],
    )
    expect(nonCanonicalRetry.messages).toMatchObject([
      { role: 'entry', kind: 'unknown_entry', subjectEntryId: 'f-retry-array' },
      { role: 'entry', kind: 'unknown_entry', subjectEntryId: 'f-retry-number' },
      { role: 'entry', kind: 'unknown_entry', subjectEntryId: 'f-retry-text' },
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

  it('keeps queued custom messages out of the queue and pending inputs, while durable custom messages fall back to user', () => {
    // QUEUED CUSTOM_MESSAGE 不是人类输入：既不进入可编辑队列，也不代表待处理的用户输入。
    // 持久 CUSTOM_MESSAGE 的未知 role 统一投影为 user。
    const timeline = buildThreadTimeline(
      [entry('durable-custom', 'CUSTOM_MESSAGE', { message: { role: 'AGENT', contents: [{ type: 'text', text: 'x' }] } })],
      [
        command('blank', '1', 'CUSTOM_MESSAGE', customMessagePayload('USER', ''), 'QUEUED'),
        command(
          'weird-role',
          '2',
          'CUSTOM_MESSAGE',
          { message: { role: 'AGENT', contents: [{ type: 'text', text: 'queued x' }] } },
          'QUEUED',
        ),
      ],
      [],
    )
    expect(timeline.messages).toMatchObject([{ role: 'user', text: 'x' }])
    expect(timeline.queuedMessages).toEqual([])
    expect(timeline.hasPendingInputs).toBe(false)
  })

  it('projects unsupported message entries for unknown roles with or without role name', () => {
    // MESSAGE 出现非 USER/ASSISTANT/TOOL 角色（如 HUMAN 等未知角色）时投影为
    // unsupported_message；带角色名的标题带 role 插值，空角色用通用文案。
    const withRole = buildThreadTimeline(
      [
        entry('weird', 'MESSAGE', { message: { role: 'HUMAN', contents: [{ type: 'text', text: 'x' }] } }),
        entry('system-role', 'MESSAGE', {
          message: { role: 'SYSTEM', contents: [{ type: 'text', text: 'y' }] },
        }),
      ],
      [],
      [],
    )
    expect(withRole.messages).toMatchObject([
      {
        role: 'entry',
        kind: 'unsupported_message',
        title: '无法识别消息',
        text: '暂不支持的消息角色：HUMAN。',
      },
      {
        role: 'entry',
        kind: 'unsupported_message',
        title: '无法识别消息',
        text: '暂不支持的消息角色：SYSTEM。',
        subjectEntryId: 'system-role',
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
        text: '消息角色或数据无效。',
      },
    ])
  })

  it('projects localized empty assistant and other messages without losing their identity', () => {
    // 两种角色和两种语言走同一真实投影，且不改变完成语义。
    const entries = [
      entry('empty-assistant', 'MESSAGE', {
        message: { role: 'ASSISTANT', contents: [] },
        assistantMetadata: {
          usage: { inputTokens: 10, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 },
          decodeDurationMillis: 1000,
        },
      }),
      entry('empty-user', 'MESSAGE', { message: { role: 'USER', contents: [] } }),
      entry('end', 'TURN_END', { outcome: 'COMPLETED' }),
    ]
    const zh = buildThreadTimeline(entries, [], []).messages
    expect(zh).toMatchObject([
      { id: 'entry:empty-assistant', subjectEntryId: 'empty-assistant', kind: 'empty_message', title: '返回内容为空', text: '' },
      { id: 'entry:empty-user', subjectEntryId: 'empty-user', kind: 'empty_message', title: '此消息没有可展示内容。', text: '' },
      { role: 'meta', endEntryId: 'end', turnUsage: { input: 10, output: 0, decodeTokens: 0 } },
    ])
    try {
      setLocale('en-US')
      const en = buildThreadTimeline(entries, [], []).messages
      expect(en).toMatchObject([
        { title: 'Empty response', text: '' },
        { title: 'This message has no displayable content.', text: '' },
        { role: 'meta', endEntryId: 'end', turnUsage: { input: 10, output: 0, decodeTokens: 0 } },
      ])
    } finally {
      setLocale('zh-CN')
    }
  })

  it('normalizes approval JSON defensively across shape and garbage inputs', () => {
    // parseApproval 的规范映射：未知 decision 归一为 null、非字符串/空白 reason
    // 归一为 null、字符串 decisionId 原样保留、required 缺失视为 false。
    expect(parseApproval({ required: true, decision: 'DENIED' })).toEqual({
      required: true,
      decision: 'DENIED',
      decisionId: null,
      reason: null,
      editReview: null,
    })
    expect(parseApproval({ decision: 'ALLOW' })).toEqual({
      required: false,
      decision: null,
      decisionId: null,
      reason: null,
      editReview: null,
    })
    expect(
      parseApproval(
        {
          required: true,
          decision: 'ALLOWED',
          decisionId: 42,
          reason: '   ',
        },
      ),
    ).toEqual({
      required: true,
      decision: 'ALLOWED',
      decisionId: null,
      reason: null,
      editReview: null,
    })
    expect(parseApproval('{"required":true}')).toBeNull()
  })
})

function entry(entryId: string, entryType: EntryType, payload: Record<string, unknown>): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 's1',
    parentEntryId: null,
    entryType,
    payload,
    createdAt: 1767225600000,
    usageCost: null,
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
    payload,
    cancelledAt: null,
    createdAt: 1767225600000,
  }
}

function messagePayload(role: string, contents: Array<Record<string, unknown>>) {
  return {
    message: { role, contents },
  }
}

function customMessagePayload(role: 'USER', text: string) {
  return { message: { role, contents: [{ type: 'text', text }] } }
}

/**
 * Turn usage 矩阵：usage 绝不紧跟 Assistant，必须在相应 TURN_END 之后投影为
 * TurnSummary；compaction / aborted / error / 未关闭 turn 不产生对话残留 summary。
 * TURN_END 本身不投影消息，因此「仅在 turn 关闭后出现且位于该 turn 全部消息之后」
 * 就是 TURN_END 后投影的可观察语义。
 *
 * 费用是读取投影：费用由服务端定价并只来自 Entry.usageCost 读取投影。
 * Entry.usageCost -> assistantMetadata 的接线由 branch owner 在 entry-projection 完成。
 */
describe('Turn usage after TURN_END', () => {
  function entry(
    entryId: string,
    entryType: EntryType,
    payload: Record<string, unknown>,
    createdAt = 1785232800000,
  ): HarnessSessionEntryDTO {
    return {
      entryId,
      sessionId: 's1',
      parentEntryId: null,
      entryType,
      payload,
      createdAt,
      usageCost: null,
    }
  }

  function assistant(
    id: string,
    text: string,
    metadata?: unknown,
  ): HarnessSessionEntryDTO {
    const payload: Record<string, unknown> = {
      message: { role: 'ASSISTANT', contents: [{ type: 'text', text }] },
    }
    if (metadata !== undefined) {
      payload.assistantMetadata = metadata
    }
    return entry(id, 'MESSAGE', payload)
  }

  function turnStart(id = 'turn-1', reason = 'USER_MESSAGE'): HarnessSessionEntryDTO {
    return entry(id, 'TURN_START', { reason, settings: {
      agentName: 'coding',
      execution: createBuiltinExecution('m', 'default'),
      environmentName: null,
    } })
  }

  function turnEnd(id = 'end-1', outcome = 'COMPLETED'): HarnessSessionEntryDTO {
    return entry(id, 'TURN_END', { outcome, continueModel: false })
  }

  function userMessage(text: string): HarnessSessionEntryDTO {
    return entry(
      `user-${text}`,
      'MESSAGE',
      { message: { role: 'USER', contents: [{ type: 'text', text }] } },
    )
  }

  function usageMetadata(
    input: number,
    output: number,
    extras: Record<string, unknown> = {},
  ) {
    return {
      usage: {
        inputTokens: input,
        outputTokens: output,
        cacheReadTokens: 0,
        cacheWriteTokens: 0,
        ...extras,
      },
    }
  }

  it('projects the TurnSummary only after the turn is closed, as the turn last message', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart(),
        userMessage('问题'),
        assistant('assistant-1', '回答', usageMetadata(10, 20, {
          reasoningTokens: 3,
          providerTotalTokens: 33,
        })),
        turnEnd(),
      ],
      [],
      [],
    )

    const ids = timeline.messages.map((message) => message.id)
    const usageIndex = ids.indexOf('meta-usage-entry-assistant-1')
    expect(usageIndex).toBeGreaterThan(0)
    // TURN_END 是控制边界（不投影消息）：summary 位于整个已关闭 turn 的最后。
    expect(usageIndex).toBe(ids.length - 1)
    const usage = timeline.messages[usageIndex]
    expect(usage).toMatchObject({
      role: 'meta',
      kind: 'turn_usage',
      subjectEntryId: 'assistant-1',
      status: 'done',
    })
    if (!usage || usage.role !== 'meta') {
      throw new Error('Expected turn usage meta message')
    }
    expect(usage.text).toContain('↑10')
    expect(usage.text).toContain('↓20')
    expect(usage).toMatchObject({
      turnUsage: {
        input: 10,
        output: 20,
        cacheRead: 0,
        cacheWrite: 0,
        reasoning: 3,
        providerTotal: 33,
        // 费用只来自 Entry.usageCost 读取投影
        cost: null,
      },
      details: {
        reasoning: 3,
        providerTotal: 33,
      },
    })
  })

  it('places the summary after tool results of the same turn', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart(),
        userMessage('run'),
        entry('assistant-1', 'MESSAGE', {
          message: {
            role: 'ASSISTANT',
            contents: [
              { type: 'text', text: '调用工具' },
              {
                type: 'tool_call',
                toolCallId: 'call-1',
                toolName: 'bash',
                rendererKey: 'bash',
                argumentsJson: '{"command":"ls"}',
              },
            ],
          },
          assistantMetadata: usageMetadata(10, 20),
        }),
        entry('tool-1', 'MESSAGE', {
          message: {
            role: 'TOOL',
            contents: [
              {
                type: 'tool_result',
                toolCallId: 'call-1',
                toolName: 'bash',
                rendererKey: 'bash',
                contents: [{ type: 'text', text: 'ok' }],
              },
            ],
          },
        }),
        turnEnd(),
      ],
      [],
      [],
    )

    const ids = timeline.messages.map((message) => message.id)
    const usageIndex = ids.indexOf('meta-usage-entry-assistant-1')
    expect(usageIndex).toBe(ids.length - 1)
    expect(ids[usageIndex - 1]).toBe('tool-1:tool-result:call-1:0')
  })

  it('keeps each turn usage attached to its own TURN_END across multiple turns', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart('turn-1'),
        userMessage('a'),
        assistant('assistant-1', 'first', usageMetadata(10, 20)),
        turnEnd('end-1'),
        turnStart('turn-2'),
        userMessage('b'),
        assistant('assistant-2', 'second', usageMetadata(30, 40)),
        turnEnd('end-2'),
      ],
      [],
      [],
    )

    const ids = timeline.messages.map((message) => message.id)
    const firstUsage = ids.indexOf('meta-usage-entry-assistant-1')
    const secondUsage = ids.indexOf('meta-usage-entry-assistant-2')
    expect(firstUsage).toBeGreaterThanOrEqual(0)
    expect(secondUsage).toBeGreaterThan(firstUsage)
    // 第二个 summary 是整个 timeline 的最后一条消息。
    expect(secondUsage).toBe(ids.length - 1)
    expect(ids[firstUsage + 1]).toBe('user-b')
  })

  it('does not project usage for aborted or error turns without assistant metadata', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart(),
        userMessage('a'),
        entry('aborted-1', 'ASSISTANT_ABORTED', {
          message: { role: 'ASSISTANT', contents: [{ type: 'text', text: 'partial' }] },
        }),
        turnEnd('end-1', 'STOPPED'),
        entry('turn-2', 'TURN_START', { reason: 'USER_MESSAGE' }),
        userMessage('b'),
        entry('error-1', 'ASSISTANT_ERROR', { error: { message: 'boom' } }),
        entry('end-2', 'TURN_END', { outcome: 'FAILED' }),
      ],
      [],
      [],
    )

    // 两个回合都拿不到 assistant metadata：不得投影 usage 摘要（id 前缀 meta-usage-），
    // 但 TURN_END 页脚 meta 仍然存在并各自携带真实 TURN_END Entry id。
    const metas = timeline.messages.filter((message) => message.role === 'meta')
    expect(metas.map((message) => message.id)).toEqual([
      'meta-turn-end-end-1',
      'meta-turn-end-end-2',
    ])
    expect(metas.map((message) => (message as { endEntryId?: string }).endEntryId)).toEqual([
      'end-1',
      'end-2',
    ])
    expect(metas.map((message) => message.text)).toEqual(['-', '-'])
    for (const meta of metas) {
      expect(meta).not.toHaveProperty('turnUsage')
      expect(meta).not.toHaveProperty('details')
    }
    expect(timeline.messages).toContainEqual(expect.objectContaining({
      id: 'error-1', role: 'assistant', status: 'error', text: 'boom',
    }))
  })

  it.each(['FAILED', 'STOPPED', 'COMPLETED'])('uses a placeholder without usage for %s', (outcome) => {
    const timeline = buildThreadTimeline([turnStart(), turnEnd('end-1', outcome)], [], [])
    expect(timeline.messages.at(-1)).toMatchObject({
      text: '-', endEntryId: 'end-1', kind: 'turn_usage',
    })
    expect(timeline.messages.at(-1)).not.toHaveProperty('turnUsage')
  })

  // 失败不抹掉本轮已经发生的真实用量，也不把真实零读数变成缺失占位。
  it.each([[10, 20], [0, 0]])('retains actual usage for a failed turn (%i, %i)', (input, output) => {
    const timeline = buildThreadTimeline([
      turnStart(),
      {
        ...assistant('assistant-1', 'partial', usageMetadata(input, output)),
        usageCost: { currency: 'USD', amount: '0' },
      },
      entry('error-1', 'ASSISTANT_ERROR', { error: { message: 'boom' } }),
      turnEnd('failed-end', 'FAILED'),
    ], [], [])
    const footer = timeline.messages.at(-1)
    expect(footer).toMatchObject({
      endEntryId: 'failed-end',
      turnUsage: { input, output, cost: { currency: 'USD', amount: '0' } },
    })
    if (!footer || footer.role !== 'meta') {
      throw new Error('Expected turn usage meta message')
    }
    expect(footer.text).toContain(`↑${input}`)
    expect(footer.text).toContain(`↓${output}`)
    expect(footer.text).not.toContain('FAILED')
  })

  it('does not leak usage from an unclosed turn into the next TURN_END', () => {
    // 上一 turn 的 assistant 已带 usage，但该 turn 没有 TURN_END（快照截断）：
    // 新 TURN_START 必须丢弃残留，下一个 TURN_END 投影当前 usage。
    const timeline = buildThreadTimeline(
      [
        turnStart('turn-1'),
        userMessage('a'),
        assistant('assistant-1', '回答', usageMetadata(10, 20)),
        turnStart('turn-2'),
        userMessage('b'),
        assistant('assistant-2', '回答 2', usageMetadata(5, 6)),
        turnEnd('end-2'),
      ],
      [],
      [],
    )

    const ids = timeline.messages.map((message) => message.id)
    expect(ids.filter((id) => id.startsWith('meta-usage-entry-'))).toEqual([
      'meta-usage-entry-assistant-2', // 仅第二个（已关闭）turn 的 summary
    ])
  })

  it('does not project usage inside compaction turns', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart('turn-1', 'COMPACTION'),
        userMessage('summary'),
        assistant('assistant-1', 'compact answer', usageMetadata(100, 50)),
        turnEnd('end-1'),
        turnStart('turn-2'),
        userMessage('real'),
        assistant('assistant-2', 'real answer', usageMetadata(1, 2)),
        turnEnd('end-2'),
      ],
      [],
      [],
    )

    const ids = timeline.messages.map((message) => message.id)
    expect(ids.filter((id) => id.startsWith('meta-usage-entry-'))).toEqual([
      'meta-usage-entry-assistant-2', // 仅真实 turn 的 summary
    ])
    // 真实 turn 的 summary 位于整个 timeline 的最后（紧随其 TURN_END）。
    expect(ids.indexOf('meta-usage-entry-assistant-2')).toBe(ids.length - 1)
  })

  it('preserves a reported all-zero usage summary', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart(),
        userMessage('a'),
        assistant('assistant-1', '回答', usageMetadata(0, 0)),
        turnEnd(),
      ],
      [],
      [],
    )
    // 真实零与未报告不同；summary 仍携带真实 TURN_END 身份。
    const metas = timeline.messages.filter((message) => message.role === 'meta')
    expect(metas.map((message) => message.id)).toEqual(['meta-usage-entry-assistant-1'])
    expect(metas[0]).toMatchObject({ turnUsage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } })
    expect(metas.map((message) => (message as { endEntryId?: string }).endEntryId)).toEqual(['end-1'])
  })

  // usage 结构非法时投影 unknown turnUsage
  it('propagates unknown when usage metadata structure is non-canonical', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart(),
        userMessage('a'),
        assistant('assistant-1', '回答', {
          usage: { invalidField: 123 },
        }),
        turnEnd(),
      ],
      [],
      [],
    )
    const metaWithUsage = timeline.messages.find(
      (message) => message.role === 'meta' && message.turnUsage != null,
    )
    expect(metaWithUsage).toBeDefined()
    expect(metaWithUsage?.role === 'meta' ? metaWithUsage.turnUsage : undefined).toMatchObject({
      input: null,
      output: null,
      cacheRead: null,
      cacheWrite: null,
      cost: null,
    })
    expect(timeline.messages.filter((message) => message.role === 'meta').map((message) => message.id))
      .toEqual(['meta-usage-entry-assistant-1'])
  })

  it.each([false, true])('propagates invalid model metadata through turn projection in either order (%s)', (reverse) => {
    const known = assistant('known', 'known', usageMetadata(10, 2))
    known.usageCost = { currency: 'USD', amount: '0.05' }
    const unknown = assistant('unknown', 'unknown', [])
    const outputs = reverse ? [unknown, known] : [known, unknown]
    const timeline = buildThreadTimeline([turnStart(), ...outputs, turnEnd()], [], [])
    const summary = timeline.messages.find((message) => message.role === 'meta' && message.turnUsage != null)
    expect(summary?.role === 'meta' ? summary.turnUsage : undefined).toMatchObject({
      input: null, output: null, cacheRead: null, cacheWrite: null, cost: null,
    })
  })

  it('omits turn usage summary for manual assistant messages without assistantMetadata', () => {
    const timeline = buildThreadTimeline(
      [
        turnStart(),
        userMessage('a'),
        assistant('assistant-manual', '人工回复'),
        turnEnd(),
      ],
      [],
      [],
    )
    // 无模型 metadata 的人工 ASSISTANT 不计，不投影任何 turnUsage meta message
    expect(timeline.messages.some((message) =>
      message.role === 'meta' && message.turnUsage != null)).toBe(false)
    expect(timeline.messages.filter((message) => message.role === 'meta').map((message) => message.id))
      .toEqual(['meta-turn-end-end-1'])
  })

  it('still projects a summary when only reasoning/cache are nonzero', () => {
    // 输出全为 reasoning 的调用仍产生摘要；reasoning 是 output 子集，不重复累加。
    const timeline = buildThreadTimeline(
      [
        turnStart(),
        userMessage('a'),
        assistant('assistant-1', '回答', usageMetadata(0, 2, {
          reasoningTokens: 2,
          cacheReadTokens: 1,
        })),
        turnEnd(),
      ],
      [],
      [],
    )
    const usage = timeline.messages.find(
      (message) => message.role === 'meta' && message.kind === 'turn_usage',
    )
    expect(usage).toMatchObject({
      turnUsage: { input: 0, output: 2, cacheRead: 1, cacheWrite: 0, reasoning: 2, cost: null },
    })
  })

  it('counts facts from an unclosed turn for the footer while hiding its conversation summary', () => {
    // 对话摘要只在 TURN_END 之后出现；而 footer 的累计用量是事实派生，不因回合未关闭而漏计。
    const entries = [
      turnStart('turn-1'),
      userMessage('a'),
      {
        ...assistant('assistant-1', '回答', usageMetadata(10, 20)),
        usageCost: { currency: 'USD', amount: '0.25' },
      },
    ]
    const timeline = buildThreadTimeline(entries, [], [])
    expect(timeline.messages.some((message) => message.role === 'meta')).toBe(false)

    const aggregated = aggregateEntryUsage(entries)
    expect(aggregated?.input).toBe(10)
    expect(aggregated?.cost).toEqual({ currency: 'USD', amount: '0.25' })
  })

  // compaction 回合里真实发生的 usage 也必须进入累计，不能因内容被隐藏而漏计
  it('includes compaction turn facts in the footer aggregate', () => {
    const entries = [
      turnStart('compact', 'COMPACTION'),
      {
        ...assistant('assistant-compact', 'summary', usageMetadata(100, 50)),
        usageCost: { currency: 'USD', amount: '1' },
      },
      turnEnd('compact-end'),
      turnStart('turn-1'),
      {
        ...assistant('assistant-1', 'done', usageMetadata(10, 20)),
        usageCost: { currency: 'USD', amount: '0.125' },
      },
      turnEnd('end-1'),
    ]

    const aggregated = aggregateEntryUsage(entries)
    expect(aggregated).toMatchObject({ input: 110, output: 70 })
    expect(aggregated?.cost).toEqual({ currency: 'USD', amount: '1.125' })
  })
})
