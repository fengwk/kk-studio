import { describe, expect, it } from 'vitest'
import { parseTaskReceipt } from '@/features/chat/runtime/task-tool-parser'
import { buildThreadTimeline } from '@/features/chat/runtime/thread-timeline'
import type { ToolDialogueMessage } from '@/features/chat/runtime/thread-timeline-types'
import type {
  HarnessSessionEntryDTO,
  ToolInvocationDTO,
} from '@/shared/api/generated/contracts'

function entry(
  entryId: string,
  role: string,
  contents: Array<Record<string, unknown>>,
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 'session-task',
    parentEntryId: null,
    entryType: 'MESSAGE',
    payloadJson: JSON.stringify({
      message: { role, contents },
    }),
    createTime: 1785232800,
    usageCost: null,
  }
}

function toolInvocation(
  id: string,
  assistantEntryId: string,
  overrides: Partial<ToolInvocationDTO> = {},
): ToolInvocationDTO {
  return {
    id,
    threadId: 'thread-1',
    turnStartEntryId: 'turn-1',
    assistantEntryId,
    callIndex: 0,
    status: 'SUCCEEDED',
    attempt: 1,
    toolCallId: 'call-task-live',
    toolName: 'task',
    rendererKey: 'task',
    environmentId: null,
    requiredEnvironmentId: null,
    waitingForEnvironment: false,
    requiredEnvironmentName: null,
    environmentWaitFreshnessAt: null,
    argumentsJson: JSON.stringify({
      subagent_type: 'explorer',
      prompt: 'inspect the runtime',
    }),
    approvalJson: null,
    resultJson: null,
    errorJson: null,
    createTime: 1785232800,
    updateTime: 1785232801,
    ...overrides,
  }
}

describe('task canonical details projection', () => {
  it('projects durable detailsJson into canonical details and matches parseTaskReceipt', () => {
    const threadId = '00000000-0000-4000-8000-000000000001'
    const timeline = buildThreadTimeline(
      [
        entry('asst-entry', 'ASSISTANT', [
          {
            type: 'tool_call',
            toolCallId: 'call-task-1',
            toolName: 'task',
            rendererKey: 'task',
            argumentsJson: JSON.stringify({
              subagent_type: 'coder',
              prompt: 'fix the defect',
            }),
          },
        ]),
        entry('tool-result-entry', 'TOOL', [
          {
            type: 'tool_result',
            toolCallId: 'call-task-1',
            toolName: 'task',
            rendererKey: 'task',
            error: false,
            detailsJson: JSON.stringify({
              kind: 'task.accepted',
              thread_id: threadId,
            }),
            contents: [
              { type: 'text', text: 'Subagent accepted execution.' },
            ],
          },
        ]),
      ],
      [],
      [],
    )

    const resultMessage = timeline.messages.find(
      (m): m is ToolDialogueMessage => m.role === 'tool' && m.phase === 'result',
    )
    expect(resultMessage).toBeDefined()
    expect(resultMessage?.details).toEqual({
      kind: 'task.accepted',
      thread_id: threadId,
    })
    expect(resultMessage?.contents).toEqual([
      { type: 'text', text: 'Subagent accepted execution.' },
    ])

    const receipt = parseTaskReceipt(resultMessage?.details)
    expect(receipt).toEqual({
      status: 'accepted',
      threadId,
    })
  })

  it('keeps details undefined on missing or corrupt detailsJson without crashing', () => {
    const timeline = buildThreadTimeline(
      [
        entry('asst-entry', 'ASSISTANT', [
          {
            type: 'tool_call',
            toolCallId: 'call-task-corrupt',
            toolName: 'task',
            rendererKey: 'task',
            argumentsJson: '{}',
          },
        ]),
        entry('tool-corrupt-entry', 'TOOL', [
          {
            type: 'tool_result',
            toolCallId: 'call-task-corrupt',
            toolName: 'task',
            rendererKey: 'task',
            error: false,
            detailsJson: '{ invalid json syntax',
            contents: [{ type: 'text', text: 'plain text result' }],
          },
        ]),
      ],
      [],
      [],
    )

    const resultMessage = timeline.messages.find(
      (m): m is ToolDialogueMessage => m.role === 'tool' && m.phase === 'result',
    )
    expect(resultMessage).toBeDefined()
    expect(resultMessage?.details).toBeUndefined()
    expect(parseTaskReceipt(resultMessage?.details)).toBeNull()
  })

  it('projects live invocation resultJson with details into transient result', () => {
    const threadId = '00000000-0000-4000-8000-000000000002'
    const invocation = toolInvocation('inv-1', 'asst-live', {
      status: 'SUCCEEDED',
      resultJson: JSON.stringify({
        details: {
          kind: 'task.accepted',
          thread_id: threadId,
        },
        contents: [{ type: 'text', text: 'Live delegation accepted.' }],
      }),
    })

    const timeline = buildThreadTimeline(
      [
        entry('asst-live', 'ASSISTANT', [
          {
            type: 'tool_call',
            toolCallId: 'call-task-live',
            toolName: 'task',
            rendererKey: 'task',
            argumentsJson: invocation.argumentsJson,
          },
        ]),
      ],
      [],
      [invocation],
    )

    const transientResult = timeline.messages.find(
      (m): m is ToolDialogueMessage =>
        m.role === 'tool' && m.phase === 'result' && m.id.startsWith('transient:tool-result:'),
    )
    expect(transientResult).toBeDefined()
    expect(transientResult?.details).toEqual({
      kind: 'task.accepted',
      thread_id: threadId,
    })
    expect(parseTaskReceipt(transientResult?.details)).toEqual({
      status: 'accepted',
      threadId,
    })
  })
})
