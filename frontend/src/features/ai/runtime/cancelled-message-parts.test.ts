import { describe, expect, it } from 'vitest'
import {
  createTextPart,
  partsToMessageContents,
} from '@/features/ai/composer/composer-parts'
import {
  cancelledInputGoalText,
  cancelledInputMessageParts,
  prependCancelledMessages,
} from '@/features/ai/runtime/cancelled-message-parts'
import type { HarnessCancelledInputDTO } from '@/shared/api/contracts/ai-runtime'

/** Stop 回执里的 USER_MESSAGE：payloadJson 是 canonical ThreadCommandPayload JSON。 */
function cancelledMessage(
  sequence: string,
  contents: unknown[],
  idempotencyKey = `c${sequence}`,
): HarnessCancelledInputDTO {
  return {
    sequence,
    idempotencyKey,
    type: 'USER_MESSAGE',
    payloadJson: JSON.stringify({ message: { role: 'USER', contents } }),
  }
}

function cancelledGoal(sequence: string, text: string): HarnessCancelledInputDTO {
  return {
    sequence,
    idempotencyKey: `g${sequence}`,
    type: 'GOAL',
    payloadJson: JSON.stringify({ text }),
  }
}

describe('cancelled message composer recovery', () => {
  it('prepends ordered messages with exact boundaries and resubmittable resources', () => {
    const restored = prependCancelledMessages(
      [
        cancelledMessage('1', [{ type: 'text', text: 'first' }]),
        cancelledMessage('2', [{
          type: 'resource',
          blobId: '00000000-0000-0000-0000-000000000001',
          name: 'a.txt',
          preview: 'p',
        }]),
      ],
      [createTextPart('current')],
    )

    expect(partsToMessageContents(restored)).toEqual([
      { type: 'TEXT', text: 'first\n\n' },
      {
        type: 'RESOURCE',
        blobId: '00000000-0000-0000-0000-000000000001',
        name: 'a.txt',
        preview: 'p',
      },
      { type: 'TEXT', text: '\n\ncurrent' },
    ])
  })

  it('keeps the current draft reference untouched when nothing can be restored', () => {
    const current = [createTextPart('current')]

    // GOAL 与系统事实都不是 composer 草稿内容；只有 USER_MESSAGE 会回到输入框。
    expect(prependCancelledMessages([cancelledGoal('1', 'objective')], current)).toBe(current)
    expect(prependCancelledMessages([], current)).toBe(current)
  })

  it('preserves unsupported durable content as deterministic editable text', () => {
    const parts = cancelledInputMessageParts(cancelledMessage('1', [
      { type: 'tool_call', toolName: 'demo', argumentsJson: '{}' },
    ]))

    expect(partsToMessageContents(parts)).toEqual([
      {
        type: 'TEXT',
        text: '{"type":"tool_call","toolName":"demo","argumentsJson":"{}"}',
      },
    ])
  })

  it('preserves malformed payload text instead of silently dropping it', () => {
    expect(partsToMessageContents(cancelledInputMessageParts({
      sequence: '1',
      idempotencyKey: 'c1',
      type: 'USER_MESSAGE',
      payloadJson: 'not-json',
    }))).toEqual([{ type: 'TEXT', text: 'not-json' }])
  })

  it('maps GOAL payloads back to the goal editor text and ignores non-goal inputs', () => {
    expect(cancelledInputGoalText(cancelledGoal('1', 'objective'))).toBe('objective')
    // 空目标与消息输入都不产生 Goal 文本。
    expect(cancelledInputGoalText(cancelledGoal('2', ''))).toBeNull()
    expect(cancelledInputGoalText(cancelledMessage('3', [{ type: 'text', text: 'hi' }]))).toBeNull()
    // GOAL 输入不会污染 composer 草稿。
    expect(cancelledInputMessageParts(cancelledGoal('4', 'objective'))).toEqual([])
  })
})
