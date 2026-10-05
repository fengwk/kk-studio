import { beforeEach, describe, expect, it } from 'vitest'

import { createMockIDBFactory } from '@/features/canvas/__tests__/mock-idb'

import type {
  HarnessCancelledInputDTO,
  HarnessStoppedThreadReceiptDTO,
} from '@/shared/api/contracts/ai-runtime'

import {
  createAttachmentPart,
  createResourcePart,
  createTextPart,
  partsToText,
} from '@/features/ai/composer/composer-parts'
import {
  applyStopReceipt,
  loadThreadDraft,
  restoreThreadDraftParts,
  restoreThreadGoalText,
  saveThreadDraftParts,
  saveThreadGoalText,
  type ThreadDraftRecord,
} from '@/features/ai/runtime/thread-draft-store'

/**
 * 绑定 Thread 草稿记录（thread-draft-store）的行为契约。
 *
 * 这里覆盖的是「刷新恢复 / 多标签并发 / Stop 回执重放」下用户可见的事实：草稿与 Goal 文本的
 * 持久化边界、失败回填的判定、以及 (threadId, stopRequestId) 的原子幂等合并。
 * 全部用例使用 test-setup 注入的 Mock IndexedDB（每个用例都是全新 factory，因此连接与写链都是空的）。
 */

const BLOB_ID = '00000000-0000-0000-0000-000000000001'

let threadSeq = 0
let threadId = ''

beforeEach(() => {
  threadId = nextThreadId('main')
})

function nextThreadId(label: string): string {
  threadSeq += 1
  return `thread-draft-${label}-${threadSeq}`
}

/** canonical ThreadCommandPayload JSON：{message:<USER AgentMessage>}。 */
function cancelledUserMessage(sequence: number, text: string): HarnessCancelledInputDTO {
  return {
    sequence: String(sequence),
    idempotencyKey: `idempotency-${sequence}`,
    type: 'USER_MESSAGE',
    payloadJson: JSON.stringify({
      message: { role: 'USER', contents: [{ type: 'text', text }] },
    }),
  }
}

/** durable USER 内容的 resource 形态：回填后必须仍可再次发送。 */
function cancelledUserResource(sequence: number, name: string): HarnessCancelledInputDTO {
  return {
    sequence: String(sequence),
    idempotencyKey: `idempotency-${sequence}`,
    type: 'USER_MESSAGE',
    payloadJson: JSON.stringify({
      message: {
        role: 'USER',
        contents: [{ type: 'resource', blobId: BLOB_ID, name, preview: 'preview' }],
      },
    }),
  }
}

function cancelledGoal(sequence: number, text: string): HarnessCancelledInputDTO {
  return {
    sequence: String(sequence),
    idempotencyKey: `idempotency-${sequence}`,
    type: 'GOAL',
    payloadJson: JSON.stringify({ text }),
  }
}

function stopReceipt(
  forThreadId: string,
  stopRequestId: string,
  cancelledInputs: HarnessCancelledInputDTO[],
): HarnessStoppedThreadReceiptDTO {
  return {
    threadId: forThreadId,
    stopRequestId,
    stoppedTurnEndEntryId: 'turn-end-entry',
    cancelledCommandCount: cancelledInputs.length,
    cancelledInputs,
  }
}

async function readRecord(forThreadId: string): Promise<ThreadDraftRecord> {
  const record = await loadThreadDraft(forThreadId)
  expect(record).not.toBeNull()
  if (record == null) {
    throw new Error(`thread draft record is missing: ${forThreadId}`)
  }
  return record
}

function occurrences(haystack: string, needle: string): number {
  return haystack.split(needle).length - 1
}

describe('thread draft store', () => {
  it('persists parts and goal text across a save/load round-trip and drops attachment parts', async () => {
    // 测试意图：草稿记录是刷新恢复的唯一事实源；attachment part 依赖页面内上传注册表，落盘时被过滤。
    expect(await loadThreadDraft(threadId)).toBeNull()

    const text = createTextPart('hello')
    const resource = createResourcePart(BLOB_ID, 'report.pdf', 'preview')
    const attachment = createAttachmentPart('upload-1', 'local.png')
    await saveThreadDraftParts(threadId, [text, attachment, resource])

    const record = await readRecord(threadId)
    expect(record.parts).toEqual([text, resource])
    expect(record.parts.some((part) => part.type === 'attachment')).toBe(false)
    expect(record.goalText).toBeNull()
    expect(record.appliedStopRequestIds).toEqual([])

    await saveThreadGoalText(threadId, 'goal text')
    const withGoal = await readRecord(threadId)
    expect(withGoal.goalText).toBe('goal text')
    expect(withGoal.parts).toEqual([text, resource])
  })

  it('keeps parts, goal text and applied receipt ids independent across saves', async () => {
    // 测试意图：三者同属一条记录但语义独立；任一次保存都不得清掉另外两个字段。
    await saveThreadDraftParts(threadId, [createTextPart('draft')])
    await saveThreadGoalText(threadId, 'goal')
    await applyStopReceipt(
      stopReceipt(threadId, 'stop-1', [cancelledGoal(1, 'receipt goal')]),
    )

    // 保存 Goal 文本不影响 composer 草稿与已合并回执身份。
    await saveThreadGoalText(threadId, 'edited goal')
    const afterGoalSave = await readRecord(threadId)
    expect(afterGoalSave.goalText).toBe('edited goal')
    expect(partsToText(afterGoalSave.parts)).toBe('draft')
    expect(afterGoalSave.appliedStopRequestIds).toEqual(['stop-1'])

    // 保存 composer 草稿不影响 Goal 文本与已合并回执身份。
    await saveThreadDraftParts(threadId, [createTextPart('edited draft')])
    const afterPartsSave = await readRecord(threadId)
    expect(partsToText(afterPartsSave.parts)).toBe('edited draft')
    expect(afterPartsSave.goalText).toBe('edited goal')
    expect(afterPartsSave.appliedStopRequestIds).toEqual(['stop-1'])
  })

  it('restores failed message parts only into an empty draft, idempotently', async () => {
    // 测试意图：失败消息回填只允许写进「没有可发送内容」的草稿；已有非空草稿绝不覆盖，重复回填只保留一份。
    const failedParts = [createTextPart('failed message')]
    const untouchedThreadId = nextThreadId('untouched')

    // 记录不存在即没有内容。
    expect(await restoreThreadDraftParts(untouchedThreadId, failedParts)).toBe(true)
    expect(partsToText((await readRecord(untouchedThreadId)).parts)).toBe('failed message')

    // 只有空白文本的草稿同样没有可发送内容。
    await saveThreadDraftParts(threadId, [createTextPart('   ')])
    expect(await restoreThreadDraftParts(threadId, failedParts)).toBe(true)
    expect(partsToText((await readRecord(threadId)).parts)).toBe('failed message')

    // 已存在不同的非空草稿：不得覆盖，返回 false。
    expect(await restoreThreadDraftParts(threadId, [createTextPart('typed later')])).toBe(false)
    expect(partsToText((await readRecord(threadId)).parts)).toBe('failed message')

    // 同一草稿重复回填：幂等为 true，记录里只有一份。
    expect(await restoreThreadDraftParts(threadId, failedParts)).toBe(true)
    const record = await readRecord(threadId)
    expect(record.parts).toHaveLength(1)
    expect(partsToText(record.parts)).toBe('failed message')
  })

  it('restores failed goal text only into an empty goal editor, idempotently', async () => {
    // 测试意图：Goal 编辑区回填语义与 composer 草稿一致：空可写、不同不覆盖、相同幂等。
    expect(await restoreThreadGoalText(threadId, 'failed goal')).toBe(true)
    expect((await readRecord(threadId)).goalText).toBe('failed goal')

    expect(await restoreThreadGoalText(threadId, 'typed later')).toBe(false)
    expect((await readRecord(threadId)).goalText).toBe('failed goal')

    expect(await restoreThreadGoalText(threadId, 'failed goal')).toBe(true)
    expect((await readRecord(threadId)).goalText).toBe('failed goal')
  })

  it('merges a stop receipt into the existing draft and goal editor', async () => {
    // 测试意图：回执一旦应用，取消的 USER_MESSAGE 前置于草稿、GOAL 进入 Goal 编辑区，并记录回执身份。
    await saveThreadDraftParts(threadId, [createTextPart('draft')])
    const merged = await applyStopReceipt(
      stopReceipt(threadId, 'stop-1', [
        cancelledUserMessage(1, 'cancelled message'),
        cancelledGoal(2, 'cancelled goal'),
      ]),
    )

    expect(merged.parts).toEqual([
      expect.objectContaining({ type: 'text', text: 'cancelled message\n\ndraft' }),
    ])
    expect(merged.goalText).toBe('cancelled goal')

    const record = await readRecord(threadId)
    expect(partsToText(record.parts)).toBe('cancelled message\n\ndraft')
    expect(record.goalText).toBe('cancelled goal')
    expect(record.appliedStopRequestIds).toEqual(['stop-1'])
  })

  it('restores durable resource content from a cancelled message as a resource part', async () => {
    // 测试意图：取消消息携带的 durable resource 回填后仍是可再次发送的 resource part，不降级成文本。
    const merged = await applyStopReceipt(
      stopReceipt(threadId, 'stop-resource', [cancelledUserResource(1, 'report.pdf')]),
    )

    expect(merged.parts).toEqual([
      expect.objectContaining({
        type: 'resource',
        blobId: BLOB_ID,
        name: 'report.pdf',
        preview: 'preview',
      }),
    ])
  })

  it('applies the same stop receipt only once and never duplicates the restored text', async () => {
    // 测试意图：(threadId, stopRequestId) 是回执身份；Stop 响应与 snapshot 双通道投递同一回执时不得重复回填。
    const receipt = stopReceipt(threadId, 'stop-1', [cancelledUserMessage(1, 'cancelled message')])

    const first = await applyStopReceipt(receipt)
    expect(partsToText(first.parts)).toBe('cancelled message')

    // 重复回执返回同一份记录，供尚未读到它的标签页同步，且不重复追加。
    const second = await applyStopReceipt(receipt)
    expect(second.appliedStopRequestIds).toEqual(['stop-1'])
    expect(partsToText(second.parts)).toBe('cancelled message')
    expect(occurrences(partsToText(second.parts), 'cancelled message')).toBe(1)

    const record = await readRecord(threadId)
    expect(record.appliedStopRequestIds).toEqual(['stop-1'])
    expect(partsToText(record.parts)).toBe('cancelled message')
    expect(occurrences(partsToText(record.parts), 'cancelled message')).toBe(1)
  })

  it('merges a child thread receipt into the child record only', async () => {
    // 测试意图：子 Thread 的 Stop 回执写它自己的记录，绝不进入父 Thread 的输入框。
    const parentThreadId = nextThreadId('parent')
    const childThreadId = nextThreadId('child')
    await saveThreadDraftParts(parentThreadId, [createTextPart('parent draft')])

    const childRecord = await applyStopReceipt(
      stopReceipt(childThreadId, 'stop-child', [cancelledUserMessage(1, 'child cancelled')]),
    )
    expect(partsToText(childRecord.parts)).toBe('child cancelled')

    const parent = await readRecord(parentThreadId)
    expect(partsToText(parent.parts)).toBe('parent draft')
    expect(parent.goalText).toBeNull()
    expect(parent.appliedStopRequestIds).toEqual([])

    const child = await readRecord(childThreadId)
    expect(partsToText(child.parts)).toBe('child cancelled')
    expect(child.appliedStopRequestIds).toEqual(['stop-child'])
  })

  it('ignores any caller-supplied draft snapshot and merges on the persisted record', async () => {
    // 测试意图：跨标签页场景下调用方的 draft 快照可能已经过期；合并只能以持久记录为事实源，
    // 绝不用过期整份快照覆盖另一个标签页已经落盘的新输入。
    await saveThreadDraftParts(threadId, [createTextPart('newer draft from another tab')])

    const merged = await applyStopReceipt(
      stopReceipt(threadId, 'stop-record-only', [cancelledUserMessage(1, 'cancelled')]),
    )

    expect(partsToText(merged.parts)).toBe('cancelled\n\nnewer draft from another tab')
    expect(partsToText((await readRecord(threadId)).parts)).toBe('cancelled\n\nnewer draft from another tab')
  })

  it('lets two tabs apply the same receipt with their own pending edits without duplication or loss', async () => {
    // 测试意图：两个标签页各自已有编辑并同时投递同一回执时，只有一个标签页完成合并，
    // 另一个拿到同一份记录用于同步；最终记录既包含双方编辑，也只包含一份回填内容。
    await saveThreadDraftParts(threadId, [createTextPart('tab A edit')])

    const [tabA, tabB] = await Promise.all([
      applyStopReceipt(stopReceipt(threadId, 'stop-shared', [cancelledUserMessage(1, 'cancelled once')])),
      applyStopReceipt(stopReceipt(threadId, 'stop-shared', [cancelledUserMessage(1, 'cancelled once')])),
    ])
    // 两个标签页看到同一份记录：内容只回填一次。
    expect(occurrences(partsToText(tabA.parts), 'cancelled once')).toBe(1)
    expect(occurrences(partsToText(tabB.parts), 'cancelled once')).toBe(1)

    // 标签页 B 在同步到回执内容后继续编辑（同链保存），不得丢掉回填内容。
    await saveThreadDraftParts(threadId, [
      createTextPart('tab B edit'),
      createTextPart('\n\ncancelled once'),
    ])

    const record = await readRecord(threadId)
    const text = partsToText(record.parts)
    expect(occurrences(text, 'cancelled once')).toBe(1)
    expect(text).toContain('tab B edit')
    expect(record.appliedStopRequestIds).toEqual(['stop-shared'])
  })

  it('serializes concurrent receipt merges for the same thread', async () => {
    // 测试意图：两个标签页同时合并不同回执时读改写必须串行，否则后写者会丢掉先写者的回填。
    await Promise.all([
      applyStopReceipt(stopReceipt(threadId, 'stop-a', [cancelledUserMessage(1, 'message A')])),
      applyStopReceipt(stopReceipt(threadId, 'stop-b', [cancelledUserMessage(2, 'message B')])),
    ])

    const record = await readRecord(threadId)
    expect(record.appliedStopRequestIds).toEqual(['stop-a', 'stop-b'])
    const text = partsToText(record.parts)
    expect(occurrences(text, 'message A')).toBe(1)
    expect(occurrences(text, 'message B')).toBe(1)
  })

  it('rejects when IndexedDB cannot be opened instead of reporting an empty draft', async () => {
    // 测试意图：存储资源不可用必须显式失败，绝不能以 null 静默冒充「没有草稿」。
    Object.defineProperty(window, 'indexedDB', {
      configurable: true,
      writable: true,
      value: createMockIDBFactory({ shouldFailOpen: true }),
    })

    await expect(loadThreadDraft(threadId)).rejects.toThrow()
    await expect(saveThreadDraftParts(threadId, [createTextPart('draft')])).rejects.toThrow()
  })

  it('rejects when the IndexedDB transaction fails so callers can surface it', async () => {
    // 测试意图：事务失败不得被吞掉成「保存成功」；调用方需要据此向用户暴露草稿未落盘。
    Object.defineProperty(window, 'indexedDB', {
      configurable: true,
      writable: true,
      value: createMockIDBFactory({ shouldFailTransaction: true }),
    })

    await expect(saveThreadDraftParts(threadId, [createTextPart('draft')])).rejects.toThrow()
  })

  it('applies a receipt to the record cleared by the queued send-time draft clear', async () => {
    // 测试意图：发送时的草稿清空是排队写入；紧随其后的回执合并必须基于清空后的记录，
    // 否则已发送的消息会被当作未消费草稿重新回填到输入框。
    await saveThreadDraftParts(threadId, [createTextPart('sent draft')])

    const cleared = saveThreadDraftParts(threadId, [])
    const merged = applyStopReceipt(
      stopReceipt(threadId, 'stop-after-send', [cancelledUserMessage(1, 'cancelled after send')]),
    )

    await merged
    await cleared

    const text = partsToText((await readRecord(threadId)).parts)
    expect(text).toBe('cancelled after send')
    expect(occurrences(text, 'sent draft')).toBe(0)
  })
})
