import { describe, expect, it } from 'vitest'
import { aggregateBranchUsage } from '@/features/ai/runtime/thread-timeline/turn-usage'
import type {
  DialogueMessage,
  MetaDialogueMessage,
  TurnUsage,
} from '@/features/ai/runtime/thread-timeline-types'

function usageMessage(id: string, turnUsage: TurnUsage): MetaDialogueMessage {
  return {
    id,
    role: 'meta',
    kind: 'turn_usage',
    subjectEntryId: id,
    text: 'usage',
    turnUsage,
    createdAt: null,
    status: 'done',
  }
}

describe('aggregateBranchUsage', () => {
  it('sums every completed Turn usage field on the current branch', () => {
    const messages: DialogueMessage[] = [
      {
        id: 'user-1',
        role: 'user',
        subjectEntryId: 'user-1',
        text: 'hello',
        createdAt: null,
      },
      usageMessage('usage-1', {
        input: 10,
        output: 2,
        cacheRead: 3,
        cacheWrite: 4,
        reasoning: 5,
        providerTotal: 24,
        cost: 0.125,
      }),
      usageMessage('usage-2', {
        input: 20,
        output: 7,
        cacheRead: 11,
        cacheWrite: 13,
        reasoning: 17,
        providerTotal: 68,
        cost: 0.375,
      }),
    ]

    expect(aggregateBranchUsage(messages)).toEqual({
      input: 30,
      output: 9,
      cacheRead: 14,
      cacheWrite: 17,
      reasoning: 22,
      providerTotal: 92,
      cost: 0.5,
    })
  })

  it('returns null when the branch has no TURN_END usage summary', () => {
    expect(aggregateBranchUsage([
      {
        id: 'assistant-1',
        role: 'assistant',
        subjectEntryId: 'assistant-1',
        text: 'still running',
        createdAt: null,
      },
    ])).toBeNull()
  })
})
