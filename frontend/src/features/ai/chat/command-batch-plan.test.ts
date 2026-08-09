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
    environmentName: null,
    agentName: 'assistant',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
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
    environmentName: null,
    agentName: 'assistant',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    activeTools: [],
    yoloEnabled: false,
    ...overrides,
  }
}

describe('command batch replay identity (immutable user intent)', () => {
  it('keeps the identity stable when only the effective base changes (queued SET_* projection)', () => {
    // 场景：base=A、draft=B 已提交；queued SET_* 投影让下一次 snapshot 的
    // effectiveBase=B。用户没有做任何编辑：重试仍需命中原始 identity 并逐字节
    // replay 同一个 batch，而不是生成一个仅含 USER_MESSAGE 的 batch。
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
    // 不同的 base 会生成不同的 batch（settings diff），但 replay 决策依据的是
    // identity，因此重试时仍由 ORIGINAL batch 胜出。
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
    // 首次发送失败后通过绑定面板 replay（同一 thread、同一 content、target
    // draft = thread branch draft）必须逐字节复用同一 batch。
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
    // Identity 匹配：controller 的 replay 复用 ORIGINAL plan（cid-stable），
    // 而不是新构建的 batch（unused-fresh-id）。
    expect(boundPane.identity).toBe(firstSend.identity)
    expect(boundPane.batch.commands[0]?.clientCommandId).not.toBe(firstSend.batch.commands[0]?.clientCommandId)
  })
})
