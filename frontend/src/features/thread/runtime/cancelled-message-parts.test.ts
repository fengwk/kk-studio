import { describe, expect, it } from 'vitest'
import {
  createTextPart,
  partsToMessageContents,
} from '@/features/thread/composer/composer-parts'
import {
  cancelledInputMessageParts,
  prependCancelledMessages,
} from '@/features/thread/runtime/cancelled-message-parts'
import type { HarnessCancelledInputDTO } from '@/shared/api/generated/contracts'

/** Stop 回执里的 USER_MESSAGE：payload 是 canonical ThreadCommandPayload JSON。 */
function cancelledMessage(
  sequence: string,
  contents: unknown[],
  idempotencyKey = `c${sequence}`,
): HarnessCancelledInputDTO {
  return {
    sequence,
    idempotencyKey,
    type: 'USER_MESSAGE',
    payload: { message: { role: 'USER', contents } },
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
      payload: 'not-json',
    }))).toEqual([{ type: 'TEXT', text: 'not-json' }])
  })

})
