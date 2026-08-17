import { describe, expect, it, vi } from 'vitest'
import { buildToolMessageView } from '@/features/ai/runtime/thread-panel/messages/tool-message-view'
import type { ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

function message(
  overrides: Partial<ToolDialogueMessage> = {},
): ToolDialogueMessage {
  return {
    id: 'tool-1',
    role: 'tool',
    subjectEntryId: null,
    createdAt: null,
    status: 'done',
    phase: 'call',
    text: '',
    toolCallId: 'call-1',
    toolName: 'read',
    rendererKey: 'read',
    arguments: '{"path":"/app"}',
    attachments: [],
    ...overrides,
  }
}

describe('buildToolMessageView', () => {
  it('combines call/result facts and keeps fully visible failures static', () => {
    const view = buildToolMessageView({
      message: message({ status: 'streaming' }),
      result: message({
        id: 'tool-result',
        phase: 'result',
        status: 'error',
        text: 'read failed',
      }),
      approvalPending: false,
      requestedExpanded: false,
      hasCustomRenderer: false,
    })

    expect(view.visualState).toBe('error')
    expect(view.context.text).toBe('read failed')
    expect(view.expandable).toBe(false)
    expect(view.showResult).toBe(true)
  })

  it('makes a quiet successful result expandable only while it is hidden', () => {
    const input = {
      message: message(),
      result: message({
        id: 'tool-result',
        phase: 'result' as const,
        text: 'line one\nline two',
      }),
      approvalPending: false,
      hasCustomRenderer: false,
    }
    const collapsed = buildToolMessageView({
      ...input,
      requestedExpanded: false,
    })
    const expanded = buildToolMessageView({
      ...input,
      requestedExpanded: true,
    })

    expect(collapsed.expandable).toBe(true)
    expect(collapsed.showResult).toBe(false)
    expect(expanded.expanded).toBe(true)
    expect(expanded.showResult).toBe(true)
  })

  it('delegates custom renderer expandability to its contribution resolver', () => {
    const isRendererExpandable = vi.fn(() => true)
    const call = message({
      toolName: 'task',
      rendererKey: 'task',
      arguments: '{"subagent_type":"explorer","prompt":"inspect"}',
    })
    const view = buildToolMessageView({
      message: call,
      approvalPending: false,
      requestedExpanded: false,
      hasCustomRenderer: true,
      isRendererExpandable,
    })

    expect(isRendererExpandable).toHaveBeenCalledWith(call, undefined)
    expect(view.expandable).toBe(true)
  })

  it('distinguishes model argument streaming from a durable edit awaiting execution', () => {
    const argumentsJson = JSON.stringify({
      path: 'App.java',
      old_string: 'one\ntwo\nthree\nfour\nfive\nsix',
      new_string: 'ONE\nTWO\nTHREE\nFOUR\nFIVE\nSIX',
    })
    const streaming = buildToolMessageView({
      message: message({
        toolName: 'edit',
        rendererKey: 'edit',
        arguments: argumentsJson,
        status: 'streaming',
        subjectEntryId: null,
      }),
      approvalPending: false,
      requestedExpanded: false,
      hasCustomRenderer: false,
    })
    const durable = buildToolMessageView({
      message: message({
        toolName: 'edit',
        rendererKey: 'edit',
        arguments: argumentsJson,
        status: 'streaming',
        subjectEntryId: 'assistant-1',
      }),
      approvalPending: false,
      requestedExpanded: false,
      hasCustomRenderer: false,
    })

    // subjectEntryId 只在 durable call 上存在，能稳定区分参数流与执行等待态。
    expect(streaming.context.argumentsStreaming).toBe(true)
    expect(streaming.expandable).toBe(true)
    expect(durable.context.argumentsStreaming).toBe(false)
    expect(durable.expandable).toBe(false)
  })
})
