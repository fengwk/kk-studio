import { describe, expect, it } from 'vitest'
import { parseTaskReceipt } from '@/features/thread/runtime/task-tool-parser'
import { buildToolMessageView } from '@/features/thread/runtime/thread-panel/messages/tool-message-view'
import { buildThreadTimeline } from '@/features/thread/runtime/thread-timeline'
import type { ToolDialogueMessage } from '@/features/thread/runtime/thread-timeline-types'
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
    payload: {
      message: { role, contents },
    },
    createdAt: 1785232800000,
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
    approval: null,
    result: null,
    error: null,
    createdAt: 1785232800000,
    updatedAt: 1785232801000,
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

  it('projects live invocation result with details into transient result', () => {
    const threadId = '00000000-0000-4000-8000-000000000002'
    const invocation = toolInvocation('inv-1', 'asst-live', {
      status: 'SUCCEEDED',
      result: {
        details: {
          kind: 'task.accepted',
          thread_id: threadId,
        },
        contents: [{ type: 'text', text: 'Live delegation accepted.' }],
      },
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

describe('durable tool result status after invocation deletion', () => {
  it.each([
    ['UNKNOWN', 'unknown'],
    ['CANCELLED', 'cancelled'],
  ])('preserves canonical %s independently of error=true', (status, expected) => {
    // HistoryPayloadMapper 写入 toolResultMetadata.status；不需要仍存在 live invocation。
    const entry: HarnessSessionEntryDTO = {
      entryId: 'result', sessionId: 'session', parentEntryId: null,
      entryType: 'MESSAGE', createdAt: 1, usageCost: null,
      payload: {
        message: { role: 'TOOL', contents: [{
          type: 'tool_result', toolCallId: 'call', toolName: 'bash', rendererKey: 'bash',
          error: true, contents: [{ type: 'text', text: 'Original terminal result' }],
          detailsJson: '{}',
        }] },
        toolResultMetadata: {
          invocationId: 'deleted', assistantEntryId: 'assistant', toolCallId: 'call',
          callIndex: 0, status, synthetic: false, reason: null,
        },
      },
    }
    const result = buildThreadTimeline([entry], [], []).messages.find(
      (message) => message.role === 'tool',
    ) as ToolDialogueMessage
    expect(result.invocationStatus).toBe(status)
    expect(result.errorMessage).toBe('Original terminal result')
    expect(result.contents).toEqual([{ type: 'text', text: 'Original terminal result' }])
    expect(buildToolMessageView({
      message: result, expanded: true, hasCallRecordRenderer: false,
    }).executionState).toBe(expected)
  })
})
