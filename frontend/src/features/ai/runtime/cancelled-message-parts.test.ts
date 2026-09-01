import { describe, expect, it } from 'vitest'
import {
  createTextPart,
  partsToMessageContents,
} from '@/features/ai/composer/composer-parts'
import {
  messageJsonToParts,
  prependCancelledMessages,
} from '@/features/ai/runtime/cancelled-message-parts'

describe('cancelled message composer recovery', () => {
  it('prepends ordered messages with exact boundaries and resubmittable resources', () => {
    const restored = prependCancelledMessages(
      [
        {
          sequence: '1',
          idempotencyKey: 'c1',
          messageJson:
            '{"role":"USER","contents":[{"type":"text","text":"first"}]}',
        },
        {
          sequence: '2',
          idempotencyKey: 'c2',
          messageJson:
            '{"role":"USER","contents":[{"type":"resource","blobId":"00000000-0000-0000-0000-000000000001","name":"a.txt","preview":"p"}]}',
        },
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

  it('preserves unsupported durable content as deterministic editable text', () => {
    const parts = messageJsonToParts(
      '{"role":"USER","contents":[{"type":"tool_call","toolName":"demo","argumentsJson":"{}"}]}',
    )

    expect(partsToMessageContents(parts)).toEqual([
      {
        type: 'TEXT',
        text: '{"type":"tool_call","toolName":"demo","argumentsJson":"{}"}',
      },
    ])
  })

  it('preserves malformed payload text instead of silently dropping it', () => {
    expect(partsToMessageContents(messageJsonToParts('not-json'))).toEqual([
      { type: 'TEXT', text: 'not-json' },
    ])
  })
})
