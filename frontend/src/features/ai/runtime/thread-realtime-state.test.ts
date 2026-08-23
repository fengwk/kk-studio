import { describe, expect, it } from 'vitest'
import {
  isRealtimeModelDeltaGap,
  isRealtimeToolStreamActive,
  parseRealtimeModelDelta,
  parseRealtimeToolPartial,
  parseStreamCheckpoint,
  parseToolErrorText,
  reduceRealtimeModelStream,
  reduceRealtimeToolStream,
  snapshotModelStream,
  snapshotToolStream,
} from '@/features/ai/runtime/thread-realtime-state'
import type {
  ModelInvocationDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { RealtimeToolStream } from '@/features/ai/runtime/thread-realtime-state'

describe('thread realtime state', () => {
  it('parses only sequenced model deltas', () => {
    expect(parseRealtimeModelDelta(event(1, 'TEXT_DELTA', 'hello'))).toMatchObject({
      threadId: '7',
      invocationId: '9',
      attempt: 1,
      sequence: 1,
      text: 'hello',
    })
    expect(parseRealtimeModelDelta(event(0, 'TEXT_DELTA', 'hello'))).toBeNull()
    expect(parseRealtimeModelDelta('not-json')).toBeNull()
  })

  it('applies only the next sequence and detects a missing delta', () => {
    const first = parseRealtimeModelDelta(event(1, 'TEXT_DELTA', 'one'))!
    const second = parseRealtimeModelDelta(event(2, 'THINKING_DELTA', 'plan'))!
    const gap = parseRealtimeModelDelta(event(4, 'TEXT_DELTA', 'four'))!
    const stream = reduceRealtimeModelStream(null, first)
    const advanced = reduceRealtimeModelStream(stream, second)

    expect(advanced).toMatchObject({ text: 'one', thinking: 'plan', sequence: 2 })
    expect(isRealtimeModelDeltaGap(advanced, gap)).toBe(true)
    expect(reduceRealtimeModelStream(advanced, gap)).toBe(advanced)
    expect(reduceRealtimeModelStream(advanced, first)).toBe(advanced)
  })

  it('advances sequence across tool-call deltas without creating transcript text', () => {
    const text = parseRealtimeModelDelta(event(1, 'TEXT_DELTA', 'one'))!
    const toolCall = parseRealtimeModelDelta(event(2, 'TOOL_CALL_DELTA', ''))!
    const thinking = parseRealtimeModelDelta(event(3, 'THINKING_DELTA', 'plan'))!

    const afterText = reduceRealtimeModelStream(null, text)
    const afterToolCall = reduceRealtimeModelStream(afterText, toolCall)
    const complete = reduceRealtimeModelStream(afterToolCall, thinking)

    expect(afterToolCall).toMatchObject({ sequence: 2, text: 'one', thinking: '', toolCalls: [] })
    expect(complete).toMatchObject({ sequence: 3, text: 'one', thinking: 'plan', toolCalls: [] })
  })

  it('accumulates arguments while reconciling cumulative tool-call identities', () => {
    const first = parseRealtimeModelDelta(toolCallEvent(1, {
      index: 0,
      id: 'call',
      name: 'wri',
      argumentsJson: '{"path":',
    }))!
    const second = parseRealtimeModelDelta(toolCallEvent(2, {
      index: 0,
      id: 'call-1',
      name: 'write',
      argumentsJson: '"App.java","content":"class App {}"}',
    }))!
    const stream = reduceRealtimeModelStream(reduceRealtimeModelStream(null, first), second)
    expect(stream.toolCalls).toEqual([
      {
        index: 0,
        id: 'call-1',
        name: 'write',
        argumentsJson: '{"path":"App.java","content":"class App {}"}',
      },
    ])
  })

  it('does not duplicate repeated full tool names or ids from provider partials', () => {
    const first = parseRealtimeModelDelta(toolCallEvent(1, {
      index: 0,
      id: 'call-1',
      name: 'edit',
      argumentsJson: '{"new_string":"one',
    }))!
    const second = parseRealtimeModelDelta(toolCallEvent(2, {
      index: 0,
      id: 'call-1',
      name: 'edit',
      argumentsJson: '\\ntwo"}',
    }))!

    const stream = reduceRealtimeModelStream(reduceRealtimeModelStream(null, first), second)
    expect(stream.toolCalls).toEqual([{
      index: 0,
      id: 'call-1',
      name: 'edit',
      argumentsJson: '{"new_string":"one\\ntwo"}',
    }])
  })

  it('mirrors the Java checkpoint codec: nullable string-only text/thinking with one non-empty', () => {
    expect(
      parseStreamCheckpoint('{"attempt":1,"sequence":0,"text":null,"thinking":"x"}'),
    ).toEqual({ attempt: 1, sequence: 0, text: '', thinking: 'x' })
    expect(
      parseStreamCheckpoint('{"attempt":1,"sequence":0,"text":"y","thinking":null}'),
    ).toEqual({ attempt: 1, sequence: 0, text: 'y', thinking: '' })
    // 与 Java record 构造函数一致：只有两侧均为空才拒绝，纯空白合法且不裁剪。
    expect(parseStreamCheckpoint('{"attempt":1,"sequence":0,"text":null,"thinking":null}')).toBeNull()
    expect(
      parseStreamCheckpoint('{"attempt":1,"sequence":0,"text":"  ","thinking":""}'),
    ).toEqual({ attempt: 1, sequence: 0, text: '  ', thinking: '' })
    // 非字符串类型视为格式错误（nullableText 拒绝数字/对象），不会取默认值。
    expect(parseStreamCheckpoint('{"attempt":1,"sequence":0,"text":5,"thinking":"x"}')).toBeNull()
    expect(parseStreamCheckpoint('{"attempt":1,"sequence":0,"text":null,"thinking":{"a":1}}')).toBeNull()
    expect(parseStreamCheckpoint('{"attempt":1,"sequence":0,"text":[],"thinking":"x"}')).toBeNull()
    // attempt/sequence 校验保持严格。
    expect(parseStreamCheckpoint('{"attempt":0,"sequence":0,"text":"x","thinking":null}')).toBeNull()
    expect(parseStreamCheckpoint('{"attempt":1,"sequence":-1,"text":"x","thinking":null}')).toBeNull()
  })

  it('parses only strict TOOL_PARTIAL envelopes into their canonical payload', () => {
    const partial = parseRealtimeToolPartial(toolPartialEvent({
      threadId: '7',
      subjectId: 'inv-t',
      attempt: 2,
      payload: { toolCallId: 'call-1', contents: [{ type: 'text', text: 'one' }], error: false },
    }))
    // 严格的 envelope 校验通过后，payload 原样暴露（聚合阶段再解释字段）。
    expect(partial).toEqual({
      threadId: '7',
      invocationId: 'inv-t',
      attempt: 2,
      payload: { toolCallId: 'call-1', contents: [{ type: 'text', text: 'one' }], error: false },
      createdAt: '2026-07-28T10:00:00Z',
    })

    // 非字符串输入与非法 JSON 不产生 partial。
    expect(parseRealtimeToolPartial(123)).toBeNull()
    expect(parseRealtimeToolPartial('not-json')).toBeNull()
    expect(parseRealtimeToolPartial('[1]')).toBeNull()

    // 每个 envelope 字段都参与严格校验：任何一项不合法都拒绝整个事件。
    const base = {
      threadId: '7',
      subjectKind: 'TOOL_INVOCATION',
      subjectId: 'inv-t',
      attempt: 1,
      type: 'TOOL_PARTIAL',
      payload: { toolCallId: 'call-1', contents: [] },
      createdAt: '2026-07-28T10:00:00Z',
    }
    expect(parseRealtimeToolPartial(JSON.stringify({ ...base, type: 'MODEL_DELTA' }))).toBeNull()
    expect(
      parseRealtimeToolPartial(JSON.stringify({ ...base, subjectKind: 'MODEL_INVOCATION' })),
    ).toBeNull()
    expect(parseRealtimeToolPartial(JSON.stringify({ ...base, threadId: '' }))).toBeNull()
    expect(parseRealtimeToolPartial(JSON.stringify({ ...base, subjectId: ' ' }))).toBeNull()
    expect(parseRealtimeToolPartial(JSON.stringify({ ...base, attempt: 0 }))).toBeNull()
    // createdAt 只要求非空字符串（MODEL_DELTA 才有日期解析校验）。
    expect(parseRealtimeToolPartial(JSON.stringify({ ...base, createdAt: '  ' }))).toBeNull()
    expect(parseRealtimeToolPartial(JSON.stringify({ ...base, payload: 'nope' }))).toBeNull()
  })

  it('rejects stream checkpoints that are absent, unparsable, or non-record', () => {
    expect(parseStreamCheckpoint(null)).toBeNull()
    expect(parseStreamCheckpoint('')).toBeNull()
    expect(parseStreamCheckpoint('not-json')).toBeNull()
    expect(parseStreamCheckpoint('[]')).toBeNull()
    expect(parseStreamCheckpoint('"str"')).toBeNull()
    // 非安全整数的 attempt/sequence 与负数 sequence 都被拒绝。
    expect(
      parseStreamCheckpoint('{"attempt":1.5,"sequence":0,"text":"x","thinking":null}'),
    ).toBeNull()
    expect(
      parseStreamCheckpoint('{"attempt":1,"sequence":0.5,"text":"x","thinking":null}'),
    ).toBeNull()
  })

  it('uses the invocation attempt for the empty base and tolerates stale checkpoint attempts', () => {
    const invocation = {
      id: 'inv-1',
      threadId: '7',
      turnStartEntryId: '1',
      basisHeadEntryId: '1',
      status: 'RUNNING',
      attempt: 3,
      streamCheckpointJson: '{"attempt":2,"sequence":5,"text":"stale","thinking":null}',
      resultJson: null,
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
    }
    expect(snapshotModelStream('7', invocation)).toMatchObject({
      attempt: 3,
      sequence: 0,
      text: '',
      thinking: '',
    })
  })

  it('projects terminal tool resultJson/errorJson while the durable result Entry is pending', () => {
    const base: ToolInvocationDTO = {
      id: 'inv-t',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'e-1',
      ordinal: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'web-search',
      toolVersion: null,
      rendererKey: 'web-search',
      toolType: 'PLATFORM',
      environment: null,
      argumentsJson: '{}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
    }
    // toolCallId 直接来自 invocation，绝不能从 argumentsJson 推测。
    const active = snapshotToolStream(base, '7')
    expect(active).toMatchObject({ toolCallId: 'call-1', text: '', error: false })

    const result = snapshotToolStream(
      {
        ...base,
        resultJson: JSON.stringify({
          toolCallId: 'call-1',
          contents: [
            { type: 'text', text: 'answer' },
            { type: 'resource', uri: 's3://bucket/a.png', mediaType: 'image/png', name: 'a.png' },
          ],
          error: null,
          details: null,
        }),
      },
      '7',
    )
    expect(result).toMatchObject({ text: 'answer', error: false })
    expect(result?.attachments).toEqual([
      expect.objectContaining({ data: 's3://bucket/a.png', mime: 'image/png', name: 'a.png' }),
    ])

    // ToolResult.error=true 必须投影（终止态的 error 结果不等于 "done"）。
    const failedResult = snapshotToolStream(
      {
        ...base,
        resultJson: JSON.stringify({
          toolCallId: 'call-1',
          contents: [{ type: 'text', text: 'partial output' }],
          error: true,
          details: null,
        }),
      },
      '7',
    )
    expect(failedResult).toMatchObject({ error: true, text: 'partial output' })

    const failed = snapshotToolStream(
      { ...base, errorJson: '{"message":"boom"}' },
      '7',
    )
    expect(failed).toMatchObject({ error: true, errorText: 'boom' })

    // ToolResult Entry 写入与 invocation 删除在同一事务原子提交：invocation 从
    // snapshot 消失时 overlay 才清除（durable Entry 的规范化去重由 timeline 负责）。
    expect(snapshotToolStream(null, '7')).toBeNull()
  })

  it('projects managed resources and tolerates invalid terminal payloads for tools', () => {
    const base: ToolInvocationDTO = {
      id: 'inv-t2',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'e-1',
      ordinal: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-2',
      toolName: 'web-search',
      toolVersion: null,
      rendererKey: 'web-search',
      toolType: 'PLATFORM',
      environment: null,
      argumentsJson: '{}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
    }
    // file:/s3: 托管资源投影为 attachment（含 downloadHref）；invalid result 回退
    // 到空文本 base；空白 resultJson 不算终态；非法 errorJson 原样暴露 message。
    const managed = snapshotToolStream(
      {
        ...base,
        resultJson: JSON.stringify({
          toolCallId: 'call-2',
          contents: [
            { type: 'text', text: 'ok' },
            {
              type: 'resource',
              uri: 'file:///tmp/a.png',
              mediaType: 'image/png',
              name: 'a.png',
              size: 10,
              sha256: 'a'.repeat(64),
            },
          ],
          error: false,
          details: null,
        }),
      },
      '7',
    )
    expect(managed).toMatchObject({ text: 'ok', error: false })
    expect(managed?.attachments).toEqual([
      expect.objectContaining({
        data: 'file:///tmp/a.png',
        mime: 'image/png',
        name: 'a.png',
        downloadHref: expect.stringContaining('/ai/runtime/resources/'),
      }),
    ])

    // 非法 result JSON 回退为空文本的活跃 base（仍标记为当前 invocation）。
    expect(snapshotToolStream({ ...base, resultJson: 'not-json' }, '7')).toMatchObject({
      toolCallId: 'call-2',
      text: '',
      error: false,
    })
    // 空白 errorJson 不算终止错误。
    expect(snapshotToolStream({ ...base, errorJson: ' ' }, '7')).toMatchObject({
      error: false,
    })
    // 非法 errorJson 原样作为 errorText 暴露。
    expect(snapshotToolStream({ ...base, errorJson: 'not-json' }, '7')).toMatchObject({
      error: true,
      errorText: 'not-json',
    })
  })

  it('ignores Resource contents in TOOL_PARTIAL chunks (the runtime forbids them)', () => {
    const partial = {
      threadId: '7',
      invocationId: 'inv-t',
      attempt: 1,
      payload: {
        toolCallId: 'call-1',
        contents: [
          { type: 'text', text: 'one' },
          { type: 'resource', uri: 'file:///tmp/r.bin', mediaType: 'application/octet-stream', name: 'r.bin' },
        ],
        error: null,
        details: null,
      },
      createdAt: '2026-07-28T10:00:00Z',
    }
    const stream = reduceRealtimeToolStream(null, partial)
    // text/json 分片会被聚合；resource 分片不会被从 partial 中投影出来。
    expect(stream.text).toBe('one')
    expect(stream.attachments).toBeUndefined()
  })

  it('replaces task.status heartbeat snapshots instead of appending them', () => {
    const status = (state: string, createdAt: string) => ({
      threadId: '7',
      invocationId: 'inv-task',
      attempt: 1,
      payload: {
        toolCallId: 'call-task',
        contents: [{ type: 'text', text: `{"kind":"task.status","state":"${state}"}\n` }],
        error: false,
        details: { kind: 'task.status', state },
      },
      createdAt,
    })

    const running = reduceRealtimeToolStream(
      null,
      status('running_model', '2026-07-28T10:00:00Z'),
    )
    const waiting = reduceRealtimeToolStream(
      running,
      status('waiting_approval', '2026-07-28T10:00:01Z'),
    )

    expect(waiting.text).toBe('{"kind":"task.status","state":"waiting_approval"}\n')
    expect(waiting.createdAt).toBe('2026-07-28T10:00:01Z')
  })

  it('reuses the task stream when a heartbeat only changes JSON order and transport time', () => {
    const partial = (text: string, createdAt: string) => ({
      threadId: '7',
      invocationId: 'inv-task',
      attempt: 1,
      payload: {
        toolCallId: 'call-task',
        contents: [{ type: 'text', text }],
        error: false,
        details: { kind: 'task.status' },
      },
      createdAt,
    })
    const first = reduceRealtimeToolStream(
      null,
      partial(
        '{"kind":"task.status","threadId":"8","subagentType":"coder",'
        + '"state":"running_model","depth":2,"turns":1,"toolCalls":0,'
        + '"lastActivity":"running","approvals":[]}\n',
        '2026-07-28T10:00:00Z',
      ),
    )
    const semanticallySame = reduceRealtimeToolStream(
      first,
      partial(
        '{ "approvals": [], "lastActivity": "running", "toolCalls": 0, "turns": 1,'
        + '"depth": 2, "state": "running_model", "subagentType": "coder",'
        + '"threadId": "8", "kind": "task.status" }\n',
        '2026-07-28T10:00:01Z',
      ),
    )

    expect(semanticallySame).toBe(first)
    expect(semanticallySame.createdAt).toBe('2026-07-28T10:00:00Z')
  })

  it('restores the stream from the single snapshot ModelInvocation checkpoint', () => {
    const invocation = {
      id: '9007199254740995',
      threadId: '7',
      turnStartEntryId: '1',
      basisHeadEntryId: '2',
      status: 'RUNNING',
      attempt: 1,
      streamCheckpointJson: '{"attempt":1,"text":"new","thinking":"","sequence":2}',
      resultJson: null,
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
    }
    const stream = snapshotModelStream('7', invocation)

    expect(stream).toMatchObject({
      invocationId: '9007199254740995',
      text: 'new',
      sequence: 2,
    })
    // 非法的 checkpoint JSON 视为不存在 -> RUNNING 保持空 base，确保
    // 第一条 realtime delta 不会丢失（checkpoint flush 不会提升 version）。
    expect(snapshotModelStream('7', { ...invocation, streamCheckpointJson: 'not-json' })).toMatchObject({
      attempt: 1,
      sequence: 0,
      text: '',
      thinking: '',
    })
    // 终止态结果但没有 resultEntryId：完整的持久 result 投影会替换
    // overlay（status 'done'）并一直保留，直到 resultEntryId 被设置。
    expect(
      snapshotModelStream('7', {
        ...invocation,
        resultJson:
          '{"text":"new","thinking":"","toolCalls":[],"stopReason":"stop","usage":{},"cost":{}}',
      }),
    ).toMatchObject({
      attempt: 1,
      sequence: 2,
      text: 'new',
      status: 'done',
    })
    expect(
      snapshotModelStream('7', {
        ...invocation,
        resultJson: null,
        errorJson: '{"kind":"INVALID_REQUEST","message":"terminal failure"}',
      }),
    ).toMatchObject({
      attempt: 1,
      sequence: 2,
      text: 'new',
      thinking: '',
      status: 'error',
      errorCode: 'INVALID_REQUEST',
      errorText: 'terminal failure',
    })
    // 设置 resultEntryId 后：持久 Entry 才是 transcript 的真实来源。
    expect(
      snapshotModelStream('7', {
        ...invocation,
        resultJson: '{"text":"new","thinking":""}',
        resultEntryId: '9',
      }),
    ).toBeNull()
  })

  it('returns no model overlay for a missing or foreign-thread invocation', () => {
    const invocation: ModelInvocationDTO = {
      id: 'inv-1',
      threadId: '7',
      turnStartEntryId: '1',
      basisHeadEntryId: '1',
      status: 'RUNNING',
      attempt: 1,
      streamCheckpointJson: null,
      resultJson: null,
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
    }
    expect(snapshotModelStream('7', null)).toBeNull()
    expect(snapshotModelStream('other', invocation)).toBeNull()
  })

  it('keeps frozen checkpoint text when the durable result payload is malformed', () => {
    const invocation: ModelInvocationDTO = {
      id: 'inv-1',
      threadId: '7',
      turnStartEntryId: '1',
      basisHeadEntryId: '1',
      status: 'RUNNING',
      attempt: 1,
      streamCheckpointJson: '{"attempt":1,"text":"frozen","thinking":"plan","sequence":4}',
      resultJson: '[not-json',
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
    }
    // result payload 非法时回退到冻结的 checkpoint，绝不是空或仅有 lossy realtime delta 的片段。
    expect(snapshotModelStream('7', invocation)).toMatchObject({
      attempt: 1,
      sequence: 4,
      text: 'frozen',
      thinking: 'plan',
      status: 'done',
      toolCalls: [],
    })
  })

  it('projects complete tool calls from a durable result and ignores malformed entries', () => {
    const invocation: ModelInvocationDTO = {
      id: 'inv-1',
      threadId: '7',
      turnStartEntryId: '1',
      basisHeadEntryId: '1',
      status: 'RUNNING',
      attempt: 2,
      streamCheckpointJson: null,
      resultJson: JSON.stringify({
        text: 'done',
        thinking: '',
        toolCalls: [
          { id: 'call-1', name: 'read', argumentsJson: '{"path":"a"}' },
          { id: 'call-2', name: 'bash', argumentsJson: '{"command":"ls"}' },
          // 缺少 id/name/arguments 的畸形条目被丢弃。
          { id: 'call-3', name: 'write', argumentsJson: null },
        ],
        stopReason: 'stop',
        usage: {},
        cost: {},
      }),
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
    }
    // 终止态 result 投影中的 toolCalls 使用条目自身的 index（数组下标）。
    expect(snapshotModelStream('7', invocation)).toMatchObject({
      attempt: 2,
      status: 'done',
      text: 'done',
      toolCalls: [
        { index: 0, id: 'call-1', name: 'read', argumentsJson: '{"path":"a"}' },
        { index: 1, id: 'call-2', name: 'bash', argumentsJson: '{"command":"ls"}' },
        // 只有全部字段为空才丢弃；argumentsJson 缺失会规范化为空串保留。
        { index: 2, id: 'call-3', name: 'write', argumentsJson: '' },
      ],
    })
  })

  it('keeps frozen checkpoint text when the durable result payload is malformed', () => {
    const invocation: ModelInvocationDTO = {
      id: 'inv-1',
      threadId: '7',
      turnStartEntryId: '1',
      basisHeadEntryId: '1',
      status: 'RUNNING',
      attempt: 1,
      streamCheckpointJson: '{"attempt":1,"text":"frozen","thinking":"plan","sequence":4}',
      resultJson: '[not-json',
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
    }
    // result payload 非法时回退到冻结的 checkpoint，绝不是空或仅有 lossy realtime delta 的片段。
    expect(snapshotModelStream('7', invocation)).toMatchObject({
      attempt: 1,
      sequence: 4,
      text: 'frozen',
      thinking: 'plan',
      status: 'done',
      toolCalls: [],
    })
  })

  it('keeps an empty streaming base when checkpoint json is blank and no terminal payload exists', () => {
    const invocation: ModelInvocationDTO = {
      id: 'inv-1',
      threadId: '7',
      turnStartEntryId: '1',
      basisHeadEntryId: '1',
      status: 'RUNNING',
      attempt: 1,
      streamCheckpointJson: '',
      resultJson: '',
      errorJson: ' ',
      resultEntryId: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
    }
    // 空白的 result/error 不算终止态；空 base 保证第一条 realtime delta 不丢失。
    expect(snapshotModelStream('7', invocation)).toMatchObject({
      attempt: 1,
      sequence: 0,
      text: '',
      thinking: '',
      status: 'streaming',
    })
  })

  it('parses TOOL_CALL_DELTA payloads strictly and accumulates only valid identity fragments', () => {
    // 空 fragment（index 合法但 id/name/arguments 全空）只推进 sequence，不创建 draft。
    const empty = parseRealtimeModelDelta(toolCallEvent(1, {
      index: 0,
      id: null,
      name: null,
      argumentsJson: null,
    }))!
    expect(empty.kind).toBe('TOOL_CALL_DELTA')
    expect(empty.text).toBe('')
    expect(empty.toolCall).toBeUndefined()
    expect(reduceRealtimeModelStream(null, empty)).toMatchObject({
      sequence: 1,
      toolCalls: [],
    })

    // index 缺失/负数与空白 id 都是格式错误，整个 delta 被拒绝。
    expect(parseRealtimeModelDelta(toolCallEvent(2, {
      index: -1,
      id: 'call-1',
      name: 'read',
      argumentsJson: '{}',
    }))).toBeNull()
    expect(parseRealtimeModelDelta(JSON.stringify({
      threadId: '7',
      subjectKind: 'MODEL_INVOCATION',
      subjectId: '9',
      attempt: 1,
      sequence: 2,
      type: 'MODEL_DELTA',
      payload: { kind: 'TOOL_CALL_DELTA', index: 0, id: '', name: 'read', argumentsJson: '{}' },
      createdAt: '2026-07-28T10:00:00Z',
    }))).toBeNull()

    // 未知 kind 的 payload 被拒绝。
    expect(parseRealtimeModelDelta(JSON.stringify({
      threadId: '7',
      subjectKind: 'MODEL_INVOCATION',
      subjectId: '9',
      attempt: 1,
      sequence: 2,
      type: 'MODEL_DELTA',
      payload: { kind: 'OTHER', text: 'x' },
      createdAt: '2026-07-28T10:00:00Z',
    }))).toBeNull()
  })

  it('reconciles cumulative tool-call identities: prefixes, grow-only, and sibling ordering', () => {
    const stream = reduceRealtimeModelStream(null, parseRealtimeModelDelta(toolCallEvent(1, {
      index: 1,
      id: 'call',
      name: 'bas',
      argumentsJson: '{"com',
    }))!)
    const advanced = reduceRealtimeModelStream(stream, parseRealtimeModelDelta(toolCallEvent(2, {
      index: 0,
      id: 'call-0',
      name: 'read',
      argumentsJson: '{"path":"a"}',
    }))!)
    const complete = reduceRealtimeModelStream(advanced, parseRealtimeModelDelta(toolCallEvent(3, {
      index: 1,
      id: 'call-1',
      name: 'bash',
      argumentsJson: 'mand":"ls"}',
    }))!)
    // index 0 先出现，即使它后到也按 index 排序展示。
    expect(complete.toolCalls).toEqual([
      { index: 0, id: 'call-0', name: 'read', argumentsJson: '{"path":"a"}' },
      { index: 1, id: 'call-1', name: 'bash', argumentsJson: '{"command":"ls"}' },
    ])
    // 同 index 的 id/name 是累积前缀：新值要么与旧值相同，要么延长旧值。
    const dupe = reduceRealtimeModelStream(complete, parseRealtimeModelDelta(toolCallEvent(4, {
      index: 1,
      id: 'call-1',
      name: 'bash',
      argumentsJson: ' --help',
    }))!)
    expect(dupe.toolCalls[1]).toMatchObject({ id: 'call-1', name: 'bash' })
    const grown = reduceRealtimeModelStream(dupe, parseRealtimeModelDelta(toolCallEvent(5, {
      index: 1,
      id: 'call-1-longer',
      name: 'bash-extra',
      argumentsJson: '',
    }))!)
    expect(grown.toolCalls[1]).toMatchObject({ id: 'call-1-longer', name: 'bash-extra' })
    // 不可调和的新前缀不能覆盖已积累的身份。
    const divergent = reduceRealtimeModelStream(grown, parseRealtimeModelDelta(toolCallEvent(6, {
      index: 1,
      id: 'other',
      name: 'other-name',
      argumentsJson: '',
    }))!)
    expect(divergent.toolCalls[1]).toMatchObject({ id: 'call-1-longer', name: 'bash-extra' })
  })

  it('replaces the tool overlay when the invocation attempt changes (retry)', () => {
    const partial = (attempt: number, text: string, createdAt: string) => ({
      threadId: '7',
      invocationId: 'inv-t',
      attempt,
      payload: {
        toolCallId: 'call-1',
        contents: [{ type: 'text', text }],
        error: null,
        details: null,
      },
      createdAt,
    })
    const first = reduceRealtimeToolStream(null, partial(1, 'old', '2026-07-28T10:00:00Z'))
    // 同 attempt 的后续分片正常追加；重试后整个 overlay 被替换，绝不混入旧 attempt 输出。
    const appended = reduceRealtimeToolStream(first, partial(1, '-new', '2026-07-28T10:00:01Z'))
    const retried = reduceRealtimeToolStream(appended, partial(2, 'fresh', '2026-07-28T10:00:02Z'))
    expect(appended).toMatchObject({ attempt: 1, text: 'old-new' })
    expect(retried).toMatchObject({ attempt: 2, text: 'fresh' })
    expect(retried.createdAt).toBe('2026-07-28T10:00:02Z')
  })

  it('joins text/json chunks and keeps error=true sticky across chunks', () => {
    const chunk = (text: unknown, error: unknown) => ({
      threadId: '7',
      invocationId: 'inv-t',
      attempt: 1,
      payload: {
        toolCallId: 'call-1',
        contents: [{ type: 'text', text }, { type: 'json', json: text }],
        error,
        details: null,
      },
      createdAt: '2026-07-28T10:00:00Z',
    })
    // 字符串 json 原样输出、对象 json 序列化输出；text 分片必须本身是
    // 字符串（非字符串被丢弃），同一 chunk 内的分片以 \n 分隔。
    const first = reduceRealtimeToolStream(null, chunk('{"a":1}', null))
    const second = reduceRealtimeToolStream(first, chunk({ b: 2 }, null))
    expect(first).toMatchObject({ text: '{"a":1}\n{"a":1}', error: false })
    // 第二个 chunk 的 text 分片不是字符串，只有 json 分片产出 {"b":2}。
    expect(second).toMatchObject({ text: '{"a":1}\n{"a":1}{"b":2}', error: false })

    // 任一 chunk 的 error=true 都会让 overlay 保持 error 状态。
    const failed = reduceRealtimeToolStream(second, chunk('boom', true))
    expect(failed).toMatchObject({ error: true })
  })

  it('falls back to an empty toolCallId when the partial payload lacks one', () => {
    const partial = {
      threadId: '7',
      invocationId: 'inv-t',
      attempt: 1,
      payload: { contents: [{ type: 'text', text: 'x' }], error: null, details: null },
      createdAt: '2026-07-28T10:00:00Z',
    }
    expect(reduceRealtimeToolStream(null, partial)).toMatchObject({
      toolCallId: '',
      text: 'x',
      error: false,
    })
  })

  it('treats task.status heartbeats as complete snapshots, replacing the previous frame', () => {
    const frame = (state: string, createdAt: string) => ({
      threadId: '7',
      invocationId: 'inv-task',
      attempt: 1,
      payload: {
        toolCallId: 'call-task',
        contents: [{ type: 'text', text: `{"kind":"task.status","state":"${state}"}\n` }],
        error: false,
        details: { kind: 'task.status' },
      },
      createdAt,
    })
    const first = reduceRealtimeToolStream(null, frame('running', '2026-07-28T10:00:00Z'))
    // 相同 task 状态的新心跳只更新时间，不重复追加文本。
    const same = reduceRealtimeToolStream(first, frame('running', '2026-07-28T10:00:01Z'))
    // 状态变化时替换为最新一帧（不是拼接旧帧）。
    const changed = reduceRealtimeToolStream(same, frame('waiting_approval', '2026-07-28T10:00:02Z'))
    expect(same).toMatchObject({
      text: '{"kind":"task.status","state":"running"}\n',
      createdAt: '2026-07-28T10:00:01Z',
    })
    expect(changed).toMatchObject({
      text: '{"kind":"task.status","state":"waiting_approval"}\n',
      createdAt: '2026-07-28T10:00:02Z',
    })
  })

  it('does not treat non-task or malformed task payloads as replaceable snapshots', () => {
    const first = reduceRealtimeToolStream(null, {
      threadId: '7',
      invocationId: 'inv-t',
      attempt: 1,
      payload: {
        toolCallId: 'call-1',
        contents: [{ type: 'text', text: 'first' }],
        error: false,
        details: null,
      },
      createdAt: '2026-07-28T10:00:00Z',
    })
    // details 缺失或 kind 非 task.status 的 chunk 仍是增量文本；错误 JSON
    // 不会被解析成 fingerprint，因此也按增量处理。
    const appended = reduceRealtimeToolStream(first, {
      threadId: '7',
      invocationId: 'inv-t',
      attempt: 1,
      payload: {
        toolCallId: 'call-1',
        contents: [{ type: 'text', text: '-more' }],
        error: false,
      },
      createdAt: '2026-07-28T10:00:01Z',
    })
    expect(appended).toMatchObject({ text: 'first-more' })
  })

  it('rejects non-string, non-record, and non-text deltas and tool partials at the parse boundary', () => {
    // 非字符串输入、顶层非 record 的 JSON、TEXT_DELTA 但 text 不是字符串，
    // 以及 TOOL_CALL_DELTA 的 id/name/arguments 类型不合法 —— 全部拒绝整个事件。
    expect(parseRealtimeModelDelta(123)).toBeNull()
    expect(parseRealtimeModelDelta('[1]')).toBeNull()
    expect(
      parseRealtimeModelDelta(JSON.stringify({
        ...JSON.parse(event(1, 'TEXT_DELTA', 'x')),
        payload: { kind: 'TEXT_DELTA', text: 5 },
      })),
    ).toBeNull()
    expect(
      parseRealtimeModelDelta(JSON.stringify({
        ...JSON.parse(event(1, 'TEXT_DELTA', 'x')),
        payload: { kind: 'TEXT_DELTA' },
      })),
    ).toBeNull()
    expect(
      parseRealtimeModelDelta(JSON.stringify({
        ...JSON.parse(toolCallEvent(1, { index: 0, id: 'c', name: 'r', argumentsJson: '{}' })),
        payload: { kind: 'TOOL_CALL_DELTA', index: 0, id: 7, name: 'r', argumentsJson: '{}' },
      })),
    ).toBeNull()
    // TOOL_PARTIAL 的 createdAt 只要求非空字符串（非日期字符串也接受）；
    // 非字符串 createdAt 与非法 JSON 同样拒绝。
    expect(parseRealtimeToolPartial(JSON.stringify({
      ...JSON.parse(toolPartialEvent({ payload: { contents: [] } })),
      createdAt: 123,
    }))).toBeNull()
    expect(parseRealtimeToolPartial('not-json')).toBeNull()
    // createdAt 为空白字符串：isNonBlankString 直接拒绝（不进入 Date.parse）。
    expect(parseRealtimeModelDelta(JSON.stringify({
      ...JSON.parse(event(1, 'TEXT_DELTA', 'x')),
      createdAt: '  ',
    }))).toBeNull()
    // createdAt 是非空字符串但 Date.parse 无法解析：toEpochMillis 返回 null → 拒绝。
    expect(parseRealtimeModelDelta(JSON.stringify({
      ...JSON.parse(event(1, 'TEXT_DELTA', 'x')),
      createdAt: 'not-a-date',
    }))).toBeNull()
    // createdAt 为非字符串（数字）也拒绝 —— toEpochMillis 的非字符串分支（L706）。
    expect(parseRealtimeModelDelta(JSON.stringify({
      ...JSON.parse(event(1, 'TEXT_DELTA', 'x')),
      createdAt: 42,
    }))).toBeNull()
    // 顶层是数组 JSON：非 record 拒绝（L97）。
    expect(parseRealtimeModelDelta('[{"a":1}]')).toBeNull()
  })

  it('merges tool-call identity fragments from an empty base and skips duplicate tail blocks', () => {
    // 新 draft 的 identity 从空字符串起步：第一块只有 name 时 id 保持空，
    // 后续同 index 的完整 id/name 以 grow-only 方式合并（L291 空 current 分支）。
    const first = parseRealtimeModelDelta(toolCallEvent(1, {
      index: 0,
      id: null,
      name: 'read',
      argumentsJson: '{"path":',
    }))!
    const second = parseRealtimeModelDelta(toolCallEvent(2, {
      index: 0,
      id: 'call-1',
      name: 'read',
      argumentsJson: '"a"}',
    }))!
    const stream = reduceRealtimeModelStream(reduceRealtimeModelStream(null, first), second)
    expect(stream.toolCalls).toEqual([
      { index: 0, id: 'call-1', name: 'read', argumentsJson: '{"path":"a"}' },
    ])
  })

  it('returns the invocation-attempt base and strict checkpoint error paths', () => {
    const baseInvocation = (overrides: Partial<ModelInvocationDTO> = {}): ModelInvocationDTO => ({
      id: 'inv-1',
      threadId: '7',
      turnStartEntryId: '1',
      basisHeadEntryId: '1',
      status: 'RUNNING',
      attempt: 1,
      streamCheckpointJson: null,
      resultJson: null,
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
      ...overrides,
    })
    // 数字/数组 updateTime 不是合法字符串：createdAt 回退到 createTime。
    expect(snapshotModelStream('7', baseInvocation({ updateTime: 123 as never }))).toMatchObject({
      attempt: 1,
      sequence: 0,
      text: '',
      thinking: '',
      createdAt: '2026-07-28T10:00:00Z',
    })
    // 合法的非终止 error payload：kind/message 均缺失时，errorCode 留空，
    // errorText 回退到原始 JSON（parseToolErrorText 的 fallback 分支）。
    expect(
      snapshotModelStream('7', baseInvocation({ errorJson: '{"foo":"bar"}' })),
    ).toMatchObject({
      status: 'error',
      errorCode: undefined,
      errorText: '{"foo":"bar"}',
    })
  })

  it('tolerates malformed terminal result/error payloads and non-record contents', () => {
    const baseInvocation = (overrides: Partial<ModelInvocationDTO> = {}): ModelInvocationDTO => ({
      id: 'inv-1',
      threadId: '7',
      turnStartEntryId: '1',
      basisHeadEntryId: '1',
      status: 'RUNNING',
      attempt: 1,
      streamCheckpointJson: '{"attempt":1,"text":"frozen","thinking":"plan","sequence":4}',
      resultJson: null,
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
      ...overrides,
    })
    // 终止态 result 的 JSON 是数组（非 record）：整体视为格式错误，回退冻结 checkpoint。
    expect(
      snapshotModelStream('7', baseInvocation({ resultJson: '[1]' })),
    ).toMatchObject({ text: 'frozen', thinking: 'plan', status: 'done' })
    // result toolCalls 含非 record 条目与全空条目：非 record 跳过、全空条目丢弃。
    expect(
      snapshotModelStream('7', baseInvocation({
        resultJson: JSON.stringify({
          text: 'done',
          thinking: '',
          toolCalls: [null, { id: '', name: '', argumentsJson: '' }, { id: 'c', name: 'r', argumentsJson: '{}' }],
        }),
      })),
    ).toMatchObject({
      text: 'done',
      toolCalls: [{ index: 2, id: 'c', name: 'r', argumentsJson: '{}' }],
    })
    // result toolCalls 不是数组：整体按空数组处理（parseCompletedToolCalls 非数组分支）。
    expect(
      snapshotModelStream('7', baseInvocation({
        resultJson: JSON.stringify({ text: 'x', thinking: '', toolCalls: 'nope' }),
      })),
    ).toMatchObject({ text: 'x', toolCalls: [] })
    // error payload 是数组：解析为 null，errorText 回退到原始 JSON。
    expect(
      snapshotModelStream('7', baseInvocation({ errorJson: '[1]' })),
    ).toMatchObject({ status: 'error', errorText: '[1]' })
    // error payload 是合法 record 但 message 为空：解析失败，同样回退原始 JSON。
    expect(
      snapshotModelStream('7', baseInvocation({ errorJson: '{"kind":"X","message":" "}' })),
    ).toMatchObject({ status: 'error', errorCode: undefined, errorText: '{"kind":"X","message":" "}' })
    // 非法 JSON 的 error payload：JSON.parse 抛错，回退原始 JSON。
    expect(
      snapshotModelStream('7', baseInvocation({ errorJson: 'not-json' })),
    ).toMatchObject({ status: 'error', errorCode: undefined, errorText: 'not-json' })
    // tool resultJson 是数组：非 record 回退为空文本 base（error 保持 false）。
    expect(
      snapshotToolStream(
        { ...toolInvocation(), resultJson: '[1]' } as ToolInvocationDTO,
        '7',
      ),
    ).toMatchObject({ text: '', error: false })
    // result contents 含非 record 条目：其余合法 text 仍聚合，非 record 跳过。
    expect(
      snapshotToolStream(
        {
          ...toolInvocation(),
          resultJson: JSON.stringify({
            toolCallId: 'call-1',
            contents: [null, { type: 'text', text: 'ok' }],
            error: false,
            details: null,
          }),
        },
        '7',
      ),
    ).toMatchObject({ text: 'ok', error: false })
    // 非 record 的 errorJson：错误文本原样暴露。
    expect(parseToolErrorText('[1]')).toBe('[1]')
    expect(parseToolErrorText('{"noMessage":1}')).toBe('{"noMessage":1}')
  })

  it('handles non-record tool partial chunks and json stringify fallbacks', () => {
    // contents 中的非 record 条目（数字/字符串）被跳过，只聚合合法 text/json 分片。
    const partial = (contents: unknown[]) => ({
      threadId: '7',
      invocationId: 'inv-t',
      attempt: 1,
      payload: { toolCallId: 'call-1', contents, error: null, details: null },
      createdAt: '2026-07-28T10:00:00Z',
    })
    expect(reduceRealtimeToolStream(null, partial([42, { type: 'text', text: 'a' }]))).toMatchObject({
      text: 'a',
      error: false,
    })
    // json 分片的序列化回退：null → ''、数字 → String、不可序列化对象 → ''。
    expect(reduceRealtimeToolStream(null, partial([
      { type: 'json', json: null },
      { type: 'json', json: 42 },
      { type: 'json', json: { a: 1 } },
    ]))).toMatchObject({ text: '42\n{"a":1}' })
    const cyclic: Record<string, unknown> = {}
    cyclic.self = cyclic
    expect(reduceRealtimeToolStream(null, partial([{ type: 'json', json: cyclic }]))).toMatchObject({
      text: '',
    })
    // 非字符串 createdAt 会命中 timestampString 的空回退（由 snapshotToolStream 使用）。
    expect(
      snapshotToolStream({ ...toolInvocation(), updateTime: 123 as never } as ToolInvocationDTO, '7'),
    ).toMatchObject({ createdAt: '2026-07-28T10:00:00Z' })
  })

  it('accepts a tool overlay only for the matching invocation attempt', () => {
    const invocation = (attempt: number) => ({
      id: 'inv-t',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'e-1',
      ordinal: 0,
      status: 'RUNNING',
      attempt,
      toolCallId: 'call-1',
      toolName: 'read',
      toolVersion: null,
      rendererKey: 'read',
      toolType: null,
      environment: null,
      argumentsJson: '{}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
    })
    const overlay: RealtimeToolStream = {
      threadId: '7',
      invocationId: 'inv-t',
      attempt: 2,
      toolCallId: 'call-1',
      text: 'partial',
      error: false,
      createdAt: '2026-07-28T10:00:00Z',
    }
    expect(isRealtimeToolStreamActive(overlay, invocation(2))).toBe(true)
    expect(isRealtimeToolStreamActive(overlay, invocation(1))).toBe(false)
    expect(isRealtimeToolStreamActive(null, invocation(2))).toBe(false)
    expect(isRealtimeToolStreamActive(overlay, null)).toBe(false)
  })
})

function event(
  sequence: number,
  kind: 'TEXT_DELTA' | 'THINKING_DELTA' | 'TOOL_CALL_DELTA',
  text: string,
): string {
  return JSON.stringify({
    threadId: '7',
    subjectKind: 'MODEL_INVOCATION',
    subjectId: '9',
    attempt: 1,
    sequence,
    type: 'MODEL_DELTA',
    payload: kind === 'TOOL_CALL_DELTA'
      ? { kind, index: 0, id: null, name: null, argumentsJson: null }
      : { kind, text },
    createdAt: '2026-07-28T10:00:00Z',
  })
}

function toolCallEvent(
  sequence: number,
  payload: { index: number; id: string | null; name: string | null; argumentsJson: string | null },
): string {
  return JSON.stringify({
    threadId: '7',
    subjectKind: 'MODEL_INVOCATION',
    subjectId: '9',
    attempt: 1,
    sequence,
    type: 'MODEL_DELTA',
    payload: { kind: 'TOOL_CALL_DELTA', ...payload },
    createdAt: '2026-07-28T10:00:00Z',
  })
}

function toolPartialEvent({
  threadId = '7',
  subjectId = 'inv-t',
  attempt = 1,
  payload,
}: {
  threadId?: string
  subjectId?: string
  attempt?: number
  payload: Record<string, unknown>
}): string {
  return JSON.stringify({
    threadId,
    subjectKind: 'TOOL_INVOCATION',
    subjectId,
    attempt,
    type: 'TOOL_PARTIAL',
    payload,
    createdAt: '2026-07-28T10:00:00Z',
  })
}

function toolInvocation(overrides: Partial<ToolInvocationDTO> = {}): ToolInvocationDTO {
  return {
    id: 'inv-t',
    modelInvocationId: 'inv-1',
    assistantEntryId: 'e-1',
    ordinal: 0,
    status: 'RUNNING',
    attempt: 1,
    toolCallId: 'call-1',
    toolName: 'web-search',
    toolVersion: null,
    rendererKey: 'web-search',
    toolType: 'PLATFORM',
    environment: null,
    argumentsJson: '{}',
    approvalJson: null,
    resultJson: null,
    errorJson: null,
    createTime: '2026-07-28T10:00:00Z',
    updateTime: '2026-07-28T10:00:00Z',
    ...overrides,
  }
}
