import { describe, expect, it } from 'vitest'
import { isTaskToolRendererExpandable } from '@/features/ai/runtime/thread-panel/messages/task-tool-display'
import type { ToolRendererMessage } from '@/platform/extensions/types'

function message(
  overrides: Partial<ToolRendererMessage> = {},
): ToolRendererMessage {
  return {
    rendererKey: 'task',
    phase: 'call',
    text: '',
    toolCallId: 'call-task',
    toolName: 'task',
    arguments: '{"subagent_type":"explorer","prompt":"inspect"}',
    attachments: [],
    ...overrides,
  }
}

describe('isTaskToolRendererExpandable', () => {
  it('uses task-owned call/result semantics instead of a host tool-name branch', () => {
    // call 阶段有参数可供展开查看
    expect(isTaskToolRendererExpandable(message(), undefined)).toBe(true)

    // accepted 收据紧凑展示，无需展开切换
    const receiptResult = message({
      phase: 'result',
      status: 'done',
      arguments: '',
      text: '{"thread_id":"child-101","status":"accepted"}',
    })
    expect(isTaskToolRendererExpandable(undefined, receiptResult)).toBe(false)

    // 降级场景：超出五行的普通文本被截断，允许展开
    const longFallbackText = Array.from(
      { length: 7 },
      (_, index) => `line-${index + 1}`,
    ).join('\n')
    expect(isTaskToolRendererExpandable(undefined, {
      ...receiptResult,
      text: longFallbackText,
    })).toBe(true)

    // 降级场景：error 状态默认展示完整错误不截断，无需展开
    expect(isTaskToolRendererExpandable(undefined, {
      ...receiptResult,
      status: 'error',
      text: 'failed to start subagent',
    })).toBe(false)
  })
})
