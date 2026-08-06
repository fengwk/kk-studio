import { describe, expect, it } from 'vitest'
import {
  buildFirstSendMessagePlan,
  buildMessageBatchPlan,
} from '@/features/ai/chat/command-batch-plan'
import type { BranchDraft } from '@/features/ai/chat/branch-draft'
import type {
  HarnessBranchSettingsDTO,
  HarnessThreadDTO,
} from '@/shared/api/contracts/ai-runtime'

function settings(overrides: Partial<HarnessBranchSettingsDTO> = {}): HarnessBranchSettingsDTO {
  return {
    environmentId: null,
    agentName: 'assistant',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    thinkingLevel: 'off',
    activeTools: [],
    ...overrides,
  }
}

function thread(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    threadId: 't1',
    sessionId: 's1',
    headEntryId: 'root',
    yoloEnabled: false,
    nextCommandSequence: '1',
    revision: '0',
    status: 'IDLE',
    processing: false,
    branchSettings: settings(),
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

function draftOf(overrides: Partial<BranchDraft> = {}): BranchDraft {
  return {
    environmentId: null,
    agentName: 'assistant',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    thinkingLevel: 'off',
    activeTools: [],
    yoloEnabled: false,
    ...overrides,
  }
}

describe('command batch replay identity (immutable user intent)', () => {
  it('keeps the identity stable when only the effective base changes (queued SET_* projection)', () => {
    // Scenario: base=A, draft=B was submitted; the queued SET_* projection then makes the
    // next snapshot's effectiveBase=B. The user made NO edit: the retry must still hit the
    // original identity and replay the exact batch, not mint a USER_MESSAGE-only batch.
    const threadA = thread()
    const baseA = draftOf({ agentName: 'assistant' })
    const draftB = draftOf({ agentName: 'coder' })
    const before = buildMessageBatchPlan({
      thread: threadA,
      effectiveBase: baseA,
      draft: draftB,
      content: 'same intent',
    })
    const afterProjection = buildMessageBatchPlan({
      thread: threadA,
      effectiveBase: draftB,
      draft: draftB,
      content: 'same intent',
    })
    expect(before.identity).toBe(afterProjection.identity)
    // Different bases yield different batches (settings diff), but the replay decision uses
    // the identity, so the ORIGINAL batch wins for the retry.
    expect(before.batch).not.toEqual(afterProjection.batch)
  })

  it('changes identity on content edit, target draft edit, or thread change', () => {
    const t = thread()
    const base = draftOf()
    const draft = draftOf({ agentName: 'coder' })
    const original = buildMessageBatchPlan({
      thread: t,
      effectiveBase: base,
      draft,
      content: 'hello',
    })
    const editedContent = buildMessageBatchPlan({
      thread: t,
      effectiveBase: base,
      draft,
      content: 'hello!',
    })
    expect(editedContent.identity).not.toBe(original.identity)
    const editedDraft = buildMessageBatchPlan({
      thread: t,
      effectiveBase: base,
      draft: draftOf({ agentName: 'coder', yoloEnabled: true }),
      content: 'hello',
    })
    expect(editedDraft.identity).not.toBe(original.identity)
    const otherThread = buildMessageBatchPlan({
      thread: thread({ threadId: 't2' }),
      effectiveBase: base,
      draft,
      content: 'hello',
    })
    expect(otherThread.identity).not.toBe(original.identity)
  })

  it('shares the identity structure between the first-send and the bound-pane batch', () => {
    // A failed first send replayed through a bound pane (same thread, same content, target
    // draft = thread branch draft) must reuse the exact batch byte-for-byte.
    const created = thread({ threadId: 't-created', nextCommandSequence: '2' })
    const firstSend = buildFirstSendMessagePlan({
      thread: created,
      content: 'retry me',
      clientCommandId: 'cid-stable',
    })
    const boundPane = buildMessageBatchPlan({
      thread: created,
      effectiveBase: draftOf(),
      draft: draftOf(),
      content: 'retry me',
      createCommandId: () => 'unused-fresh-id',
    })
    // Identity matches: the controller replay then reuses the ORIGINAL plan (cid-stable)
    // instead of the freshly built batch (unused-fresh-id).
    expect(boundPane.identity).toBe(firstSend.identity)
    expect(boundPane.batch.commands[0]?.clientCommandId).not.toBe(firstSend.batch.commands[0]?.clientCommandId)
  })
})
