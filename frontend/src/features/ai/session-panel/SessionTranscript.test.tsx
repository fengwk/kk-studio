import { describe, expect, it } from 'vitest'
import { isVisibleDialogueMessage } from '@/features/ai/session-panel/visibility'
import type { DialogueMessage } from '@/features/ai/session-events'

describe('isVisibleDialogueMessage', () => {
  it('keeps thinking-only assistant messages visible', () => {
    const message: DialogueMessage = {
      id: 'a1',
      role: 'assistant',
      runId: 'r1',
      text: '',
      thinking: 'plan',
      createdAt: '2026-07-18T00:00:00',
      status: 'done',
    }
    expect(isVisibleDialogueMessage(message)).toBe(true)
  })

  it('hides empty completed assistant rows', () => {
    const message: DialogueMessage = {
      id: 'a2',
      role: 'assistant',
      runId: 'r1',
      text: '   ',
      createdAt: '2026-07-18T00:00:00',
      status: 'done',
    }
    expect(isVisibleDialogueMessage(message)).toBe(false)
  })
})
