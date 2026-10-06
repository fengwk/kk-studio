import { describe, expect, it } from 'vitest'
import {
  buildToolMessageView,
  shouldShowErrorNotice,
  toolDefaultExpanded,
  toolMessageContents,
} from '@/features/ai/runtime/thread-panel/messages/tool-message-view'
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
    contents: [],
    toolCallId: 'call-1',
    toolName: 'read',
    rendererKey: 'read',
    arguments: '{"path":"/app"}',
    ...overrides,
  }
}

function withContents(text: string): Partial<ToolDialogueMessage> {
  return { contents: [{ type: 'text', text }] }
}

describe('buildToolMessageView', () => {
  it('combines call/result facts and exposes contents for the shared shell', () => {
    const view = buildToolMessageView({
      message: message({ status: 'streaming' }),
      result: message({
        id: 'tool-result',
        phase: 'result',
        status: 'error',
        ...withContents('read failed'),
      }),
      expanded: false,
      hasCallRecordRenderer: false,
    })

    expect(view.visualState).toBe('error')
    expect(view.errorText).toBeUndefined()
    expect(view.hasError).toBe(true)
    expect(view.contents).toEqual([{ type: 'text', text: 'read failed' }])
    // 折叠后正文隐藏，失败摘要由宿主单独渲染，绝不随正文消失。
    expect(view.showBody).toBe(false)
    expect(view.hasBody).toBe(true)
  })

  it('keeps the ordered contents instead of flattening text and attachments', () => {
    const view = buildToolMessageView({
      message: message({
        phase: 'result',
        ...withContents(''),
        contents: [
          { type: 'text', text: 'before' },
          {
            type: 'resource',
            attachment: {
              type: 'image',
              name: 'shot.png',
              mime: 'image/png',
              data: 'data:image/png;base64,AAA',
            },
          },
          { type: 'text', text: 'after' },
          { type: 'json', value: '{"ok":true}' },
        ],
      }),
      expanded: false,
      hasCallRecordRenderer: false,
    })

    expect(view.contents.map((content) => content.type))
      .toEqual(['text', 'resource', 'text', 'json'])
    expect(view.hasBody).toBe(true)
  })

  it('distinguishes model argument streaming from a durable call awaiting execution', () => {
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
      expanded: true,
      hasCallRecordRenderer: false,
    })
    const durable = buildToolMessageView({
      message: message({
        toolName: 'edit',
        rendererKey: 'edit',
        arguments: argumentsJson,
        status: 'streaming',
        subjectEntryId: 'assistant-1',
      }),
      expanded: true,
      hasCallRecordRenderer: false,
    })

    // subjectEntryId 只在 durable call 上存在，能稳定区分参数流与执行等待态。
    expect(streaming.argumentsStreaming).toBe(true)
    expect(durable.argumentsStreaming).toBe(false)
    expect(streaming.hasCallPreview).toBe(true)
    expect(durable.hasCallPreview).toBe(true)
    expect(streaming.preview?.kind).toBe('edit')
  })

  it('exposes the call body only for tools that move parameters below the header', () => {
    const write = buildToolMessageView({
      message: message({
        toolName: 'write',
        rendererKey: 'write',
        arguments: '{"path":"a.txt","content":"hello"}',
      }),
      expanded: true,
      hasCallRecordRenderer: false,
    })
    const task = buildToolMessageView({
      message: message({
        toolName: 'task',
        rendererKey: 'task',
        arguments: '{"subagent_type":"explorer","prompt":"inspect"}',
      }),
      expanded: true,
      hasCallRecordRenderer: true,
    })
    const taskWithoutRenderer = buildToolMessageView({
      message: message({
        toolName: 'task',
        rendererKey: 'task',
        arguments: '{"subagent_type":"explorer","prompt":"inspect"}',
      }),
      expanded: true,
      hasCallRecordRenderer: false,
    })
    const read = buildToolMessageView({
      message: message({ arguments: '{"path":"/app"}' }),
      expanded: true,
      hasCallRecordRenderer: false,
    })

    expect(write.hasCallPreview).toBe(true)
    expect(write.hasCallRecordBody).toBe(false)
    expect(task.hasCallRecordBody).toBe(true)
    expect(task.hasBody).toBe(true)
    // 没有 renderer 时 task 正文无法渲染，不能伪装成可折叠正文。
    expect(taskWithoutRenderer.hasCallRecordBody).toBe(false)
    expect(taskWithoutRenderer.hasBody).toBe(false)
    // read 的参数留在 Header，正文只承载结果。
    expect(read.hasCallPreview).toBe(false)
    expect(read.hasCallRecordBody).toBe(false)
    expect(read.hasBody).toBe(false)
  })

  it('treats an empty argument object as no call body', () => {
    const view = buildToolMessageView({
      message: message({
        toolName: 'task',
        rendererKey: 'task',
        arguments: ' {} ',
      }),
      expanded: true,
      hasCallRecordRenderer: true,
    })

    expect(view.hasCallRecordBody).toBe(false)
    expect(view.hasBody).toBe(false)
  })

  it('prefers durable result contents over a stale streaming partial', () => {
    const call = message({
      status: 'streaming',
      partialContents: [{ type: 'text', text: 'streaming partial' }],
    })
    expect(toolMessageContents(call, undefined)).toEqual([
      { type: 'text', text: 'streaming partial' },
    ])

    const result = message({ id: 'r', phase: 'result', ...withContents('final') })
    expect(toolMessageContents(call, result)).toEqual([{ type: 'text', text: 'final' }])
    // result 存在但内容为空时也不回退旧 partial（避免旧 partial 盖过终态错误）。
    const emptyResult = message({ id: 'r2', phase: 'result', contents: [] })
    expect(toolMessageContents(call, emptyResult)).toEqual([])
  })

  it('keeps execution output streaming after the call entry is durable', () => {
    // 调用已持久化不代表日志结束；最终结果出现后才关闭尾部跟随。
    const call = message({
      toolName: 'bash',
      rendererKey: 'bash',
      subjectEntryId: 'assistant-1',
      status: 'streaming',
      partialContents: [{ type: 'text', text: 'running' }],
    })
    const streaming = buildToolMessageView({
      message: call,
      expanded: true,
      hasCallRecordRenderer: false,
    })
    expect(streaming.argumentsStreaming).toBe(false)
    expect(streaming.contentsStreaming).toBe(true)

    const completed = buildToolMessageView({
      message: call,
      result: message({
        phase: 'result',
        subjectEntryId: 'tool-result-1',
        status: 'done',
        ...withContents('completed'),
      }),
      expanded: true,
      hasCallRecordRenderer: false,
    })
    expect(completed.contentsStreaming).toBe(false)
    expect(completed.contents).toEqual([{ type: 'text', text: 'completed' }])
  })

  it('drops the stale partial error once a durable result exists', () => {
    const call = message({ status: 'streaming' })
    const view = buildToolMessageView({
      message: call,
      result: message({
        id: 'r',
        phase: 'result',
        status: 'streaming',
        partialErrorText: 'ignored partial error',
      }),
      expanded: true,
      hasCallRecordRenderer: false,
    })

    expect(view.errorText).toBeUndefined()
  })

  it('keeps a streaming partial error visible before the durable result arrives', () => {
    const view = buildToolMessageView({
      message: message({ status: 'streaming', partialErrorText: 'partial failed' }),
      expanded: true,
      hasCallRecordRenderer: false,
    })

    expect(view.errorText).toBe('partial failed')
    expect(view.hasError).toBe(true)
  })

  describe('toolVisualState', () => {
    it('treats a failed call without a result as error instead of pending', () => {
      // durable 调用失败但结果缺失（CANCELLED/UNKNOWN/FAILED）不能继续显示等待态。
      const view = buildToolMessageView({
        message: message({ phase: 'call', status: 'error', errorMessage: 'cancelled' }),
        expanded: false,
        hasCallRecordRenderer: false,
      })

      expect(view.visualState).toBe('error')
      expect(view.hasError).toBe(true)
      expect(view.errorText).toBe('cancelled')
    })

    it('treats call-only status done or undefined as pending', () => {
      const doneCall = buildToolMessageView({
        message: message({ phase: 'call', status: 'done' }),
        expanded: false,
        hasCallRecordRenderer: false,
      })
      const undefinedCall = buildToolMessageView({
        message: message({ phase: 'call', status: undefined }),
        expanded: false,
        hasCallRecordRenderer: false,
      })

      expect(doneCall.visualState).toBe('pending')
      expect(undefinedCall.visualState).toBe('pending')
    })

    it('treats call streaming as pending when no paired result exists', () => {
      const streamingCall = buildToolMessageView({
        message: message({ phase: 'call', status: 'streaming' }),
        expanded: false,
        hasCallRecordRenderer: false,
      })

      expect(streamingCall.visualState).toBe('pending')
    })

    it('overrides call streaming with success when a paired done result is present', () => {
      const view = buildToolMessageView({
        message: message({ phase: 'call', status: 'streaming' }),
        result: message({
          id: 'tool-result-1',
          phase: 'result',
          status: 'done',
          ...withContents('completed successfully'),
        }),
        expanded: false,
        hasCallRecordRenderer: false,
      })

      expect(view.visualState).toBe('success')
    })

    it('derives error state when paired with an error result', () => {
      const view = buildToolMessageView({
        message: message({ phase: 'call', status: 'streaming' }),
        result: message({
          id: 'tool-result-2',
          phase: 'result',
          status: 'error',
          errorMessage: 'execution failed',
          ...withContents('execution failed'),
        }),
        expanded: false,
        hasCallRecordRenderer: false,
      })

      expect(view.visualState).toBe('error')
      expect(view.errorText).toBe('execution failed')
    })

    it('shows a succeeded invocation result while its waiting sibling stays pending', () => {
      // 结果来自 invocation overlay，而不是 durable tool result；成功与等待必须分开着色。
      const succeeded = buildToolMessageView({
        message: message({ status: 'done', toolCallId: 'call-ok' }),
        result: message({
          id: 'transient-ok',
          phase: 'result',
          status: 'done',
          toolCallId: 'call-ok',
          ...withContents('listed files'),
        }),
        expanded: false,
        hasCallRecordRenderer: false,
      })
      const waiting = buildToolMessageView({
        message: message({
          id: 'call-ask',
          status: 'streaming',
          toolCallId: 'call-ask',
          approval: { required: true, decision: null, decisionId: null, reason: null },
        }),
        expanded: false,
        hasCallRecordRenderer: false,
      })

      expect(succeeded.visualState).toBe('success')
      expect(succeeded.contents).toEqual([{ type: 'text', text: 'listed files' }])
      expect(waiting.visualState).toBe('pending')
      expect(waiting.resultMessage).toBeUndefined()
    })

    it('derives success from a standalone result with missing status', () => {
      const view = buildToolMessageView({
        message: message({ phase: 'result', status: undefined, ...withContents('ok') }),
        expanded: false,
        hasCallRecordRenderer: false,
      })

      expect(view.visualState).toBe('success')
    })
  })
})

describe('toolDefaultExpanded', () => {
  it('keeps write/edit/bash/task/ask_user bodies visible by default', () => {
    for (const toolName of ['write', 'edit', 'bash', 'task', 'ask_user']) {
      expect(toolDefaultExpanded({ toolName, contents: [], hasError: false })).toBe(true)
    }
  })

  it('keeps read/grep/find/LSP/unknown results collapsed by default', () => {
    for (const toolName of [
      'read',
      'grep',
      'find',
      'lsp_goto_definition',
      'mcp__server__tool',
      'custom',
    ]) {
      expect(toolDefaultExpanded({ toolName, contents: [], hasError: false })).toBe(false)
    }
    // read 的非图片结果（含未知附件的 resource）保持收起，只有图片预览默认展开。
    expect(toolDefaultExpanded({
      toolName: 'read',
      contents: [{ type: 'text', text: 'file body' }],
      hasError: false,
    })).toBe(false)
    expect(toolDefaultExpanded({
      toolName: 'read',
      contents: [{
        type: 'resource',
        attachment: { type: 'file', name: 'x.bin', mime: '', data: 'blob:x' },
      }],
      hasError: false,
    })).toBe(false)
    expect(toolDefaultExpanded({
      toolName: 'read',
      contents: [{
        type: 'resource',
        attachment: { type: 'image', name: 'shot.png', mime: 'image/png', data: 'blob:x' },
      }],
      hasError: false,
      mediaDefault: true,
    })).toBe(true)
  })

  it('expands failing results that would otherwise hide the failure body', () => {
    expect(toolDefaultExpanded({
      toolName: 'mcp__server__tool',
      contents: [{ type: 'text', text: 'boom' }],
      hasError: true,
    })).toBe(true)
    // 没有正文可展示时不需要展开。
    expect(toolDefaultExpanded({
      toolName: 'mcp__server__tool',
      contents: [],
      hasError: true,
    })).toBe(false)
  })

  it('lets a meaningful error body win over the read media default', () => {
    // 失败优先于 read 的媒体默认值：错误文本结果默认展开，不让 read 分支把错误藏起来。
    expect(toolDefaultExpanded({
      toolName: 'read',
      contents: [{ type: 'text', text: 'file appears to be binary' }],
      hasError: true,
    })).toBe(true)
    // 空白/空 Text 不构成可展开正文：只保留错误态，不展开空框。
    expect(toolDefaultExpanded({
      toolName: 'read',
      contents: [{ type: 'text', text: '  \n ' }],
      hasError: true,
    })).toBe(false)
    expect(toolDefaultExpanded({
      toolName: 'mcp__server__tool',
      contents: [{ type: 'text', text: '\n' }],
      hasError: true,
    })).toBe(false)
  })
})

describe('shouldShowErrorNotice', () => {
  it('always shows the summary when the body is collapsed', () => {
    expect(shouldShowErrorNotice({
      showBody: false,
      bodyTexts: ['boom'],
      errorNotice: 'boom',
    })).toBe(true)
  })

  it('never repeats a failure already present in the visible body', () => {
    expect(shouldShowErrorNotice({
      showBody: true,
      bodyTexts: ['boom'],
      errorNotice: 'boom',
    })).toBe(false)
    expect(shouldShowErrorNotice({
      showBody: true,
      bodyTexts: ['Error: boom at line 3'],
      errorNotice: 'boom',
    })).toBe(false)
    // 正文完全是别的内容时仍需摘要。
    expect(shouldShowErrorNotice({
      showBody: true,
      bodyTexts: ['listed 3 files'],
      errorNotice: 'boom',
    })).toBe(true)
    expect(shouldShowErrorNotice({
      showBody: true,
      bodyTexts: ['listed 3 files'],
      errorNotice: '   ',
    })).toBe(false)
  })
})
