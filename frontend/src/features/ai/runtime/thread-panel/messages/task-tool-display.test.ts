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
    expect(isTaskToolRendererExpandable(message(), undefined)).toBe(true)

    const shortResult = message({
      phase: 'result',
      status: 'done',
      arguments: '',
      text:
        '<task id="101" state="completed">\n'
        + '<task_result>short report</task_result>\n</task>',
    })
    expect(isTaskToolRendererExpandable(undefined, shortResult)).toBe(false)

    const longReport = Array.from(
      { length: 7 },
      (_, index) => `report-${index + 1}`,
    ).join('\n')
    expect(isTaskToolRendererExpandable(undefined, {
      ...shortResult,
      text:
        '<task id="101" state="completed">\n<task_result>\n'
        + `${longReport}\n</task_result>\n</task>`,
    })).toBe(true)

    expect(isTaskToolRendererExpandable(undefined, {
      ...shortResult,
      status: 'error',
      text:
        '<task id="102" state="error">\n'
        + '<task_error>failed</task_error>\n</task>',
    })).toBe(false)
  })
})
