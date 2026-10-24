import { describe, expect, it } from 'vitest'
import { buildThreadTimeline } from '../thread-timeline'
import type { ToolDialogueMessage } from '../thread-timeline-types'
import { buildToolMessageView } from '../thread-panel/messages/tool-message-view'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

describe('durable tool result status after invocation deletion', () => {
  it.each([
    ['UNKNOWN', 'unknown'],
    ['CANCELLED', 'cancelled'],
  ])('preserves canonical %s independently of error=true', (status, expected) => {
    // HistoryPayloadMapper 写入 toolResultMetadata.status；不需要仍存在 live invocation。
    const entry: HarnessSessionEntryDTO = {
      entryId: 'result', sessionId: 'session', parentEntryId: null,
      entryType: 'MESSAGE', createTime: 1, usageCost: null,
      payloadJson: JSON.stringify({
        message: { role: 'TOOL', contents: [{
          type: 'tool_result', toolCallId: 'call', toolName: 'bash', rendererKey: 'bash',
          error: true, contents: [{ type: 'text', text: 'Original terminal result' }],
          detailsJson: '{}',
        }] },
        toolResultMetadata: {
          invocationId: 'deleted', assistantEntryId: 'assistant', toolCallId: 'call',
          callIndex: 0, status, synthetic: false, reason: null,
        },
      }),
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
