import { describe, expect, it } from 'vitest'
import {
  buildFirstSendMessagePlan,
  buildMessageBatchPlan,
} from '@/features/ai/chat/command-batch-plan'
import {
  createAttachmentPart,
  createTextPart,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
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

function partsOf(...parts: ComposerPart[]): ComposerPart[] {
  return parts
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
      parts: partsOf(createTextPart('same intent')),
    })
    const afterProjection = buildMessageBatchPlan({
      thread: threadA,
      effectiveBase: draftB,
      draft: draftB,
      parts: partsOf(createTextPart('same intent')),
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
      parts: partsOf(createTextPart('hello')),
    })
    const editedContent = buildMessageBatchPlan({
      thread: t,
      effectiveBase: base,
      draft,
      parts: partsOf(createTextPart('hello!')),
    })
    expect(editedContent.identity).not.toBe(original.identity)
    const editedDraft = buildMessageBatchPlan({
      thread: t,
      effectiveBase: base,
      draft: draftOf({ agentName: 'coder', yoloEnabled: true }),
      parts: partsOf(createTextPart('hello')),
    })
    expect(editedDraft.identity).not.toBe(original.identity)
    const otherThread = buildMessageBatchPlan({
      thread: thread({ threadId: 't2' }),
      effectiveBase: base,
      draft,
      parts: partsOf(createTextPart('hello')),
    })
    expect(otherThread.identity).not.toBe(original.identity)
  })

  it('shares the identity structure between the first-send and the bound-pane batch', () => {
    // 首次发送失败后通过绑定面板 replay（同一 thread、同一 content、target
    // draft = thread branch draft）必须逐字节复用同一 batch。
    const created = thread({ threadId: 't-created', nextCommandSequence: '2' })
    const firstSend = buildFirstSendMessagePlan({
      thread: created,
      parts: partsOf(createTextPart('retry me')),
      clientCommandId: 'cid-stable',
    })
    const boundPane = buildMessageBatchPlan({
      thread: created,
      effectiveBase: draftOf(),
      draft: draftOf(),
      parts: partsOf(createTextPart('retry me')),
      createCommandId: () => 'unused-fresh-id',
    })
    // Identity 匹配：controller 的 replay 复用 ORIGINAL plan（cid-stable），
    // 而不是新构建的 batch（unused-fresh-id）。
    expect(boundPane.identity).toBe(firstSend.identity)
    expect(boundPane.batch.commands[0]?.clientCommandId).not.toBe(firstSend.batch.commands[0]?.clientCommandId)
  })
})

describe('ordered USER_MESSAGE contents serialization', () => {
  it('serializes text -> attachment -> text in order with distinct uploadIds', () => {
    const plan = buildMessageBatchPlan({
      thread: thread(),
      effectiveBase: draftOf(),
      draft: draftOf(),
      parts: partsOf(
        createTextPart('before'),
        createAttachmentPart('upload-1', 'a.png'),
        createTextPart('after'),
      ),
    })
    const command = plan.batch.commands[plan.batch.commands.length - 1]
    expect(command).toMatchObject({ type: 'USER_MESSAGE' })
    expect(command).not.toHaveProperty('role')
    expect(command).toHaveProperty('contents')
    const contents = (command as { contents: Array<Record<string, unknown>> }).contents
    expect(contents).toEqual([
      { type: 'TEXT', text: 'before' },
      { type: 'ATTACHMENT', uploadId: 'upload-1' },
      { type: 'TEXT', text: 'after' },
    ])
    // Identity 覆盖有序 contents：同一 uploadId 顺序不同则身份不同。
    const reordered = buildMessageBatchPlan({
      thread: thread(),
      effectiveBase: draftOf(),
      draft: draftOf(),
      parts: partsOf(
        createTextPart('after'),
        createAttachmentPart('upload-1', 'a.png'),
        createTextPart('before'),
      ),
    })
    expect(reordered.identity).not.toBe(plan.identity)
  })

  it('serializes text-only messages as ordered contents', () => {
    const plan = buildFirstSendMessagePlan({
      thread: thread(),
      parts: partsOf(createTextPart('hello')),
    })
    expect(plan.batch.commands[0]).toEqual({
      type: 'USER_MESSAGE',
      clientCommandId: expect.any(String) as string,
      contents: [{ type: 'TEXT', text: 'hello' }],
    })
    expect(plan.batch.commands[0]).not.toHaveProperty('content')
  })

  it('trims outer whitespace while preserving attachment order', () => {
    const plan = buildMessageBatchPlan({
      thread: thread(),
      effectiveBase: draftOf(),
      draft: draftOf(),
      parts: partsOf(
        createTextPart('  '),
        createTextPart('hello\n'),
        createAttachmentPart('upload-1', 'a.png'),
        createTextPart('   '),
      ),
    })
    const command = plan.batch.commands[plan.batch.commands.length - 1] as {
      contents?: Array<Record<string, unknown>>
    }
    // 只裁剪消息两端空白（textarea 时代 trim 语义）；attachment 前的换行属于内容。
    expect(command.contents).toEqual([
      { type: 'TEXT', text: 'hello\n' },
      { type: 'ATTACHMENT', uploadId: 'upload-1' },
    ])
  })

  it('supports attachment-only messages', () => {
    const plan = buildFirstSendMessagePlan({
      thread: thread(),
      parts: partsOf(createAttachmentPart('upload-9', 'clip.mp4')),
    })
    const command = plan.batch.commands[0] as { contents?: Array<Record<string, unknown>> }
    expect(command.contents).toEqual([{ type: 'ATTACHMENT', uploadId: 'upload-9' }])
  })
})
