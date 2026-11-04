import { describe, expect, it, beforeEach } from 'vitest'
import { interactionDraftStore } from './interaction-draft-store'

describe('interaction-draft-store', () => {
  beforeEach(() => {
    interactionDraftStore.resetForTesting()
  })

  it('preserves draft across updates and returns isolated draft objects', () => {
    const intId = 'int-test-1'
    interactionDraftStore.updateQuestionDraft(intId, 0, () => ({
      selectedLabels: ['1080P'],
      customText: '',
    }))

    const draft = interactionDraftStore.getDraft(intId)
    expect(draft.questionDrafts[0]?.selectedLabels).toEqual(['1080P'])

    // 更新第二题
    interactionDraftStore.updateQuestionDraft(intId, 1, () => ({
      selectedLabels: ['成片'],
      customText: '源文件',
    }))

    const updated = interactionDraftStore.getDraft(intId)
    expect(updated.questionDrafts[0]?.selectedLabels).toEqual(['1080P'])
    expect(updated.questionDrafts[1]?.selectedLabels).toEqual(['成片'])
    expect(updated.questionDrafts[1]?.customText).toBe('源文件')
  })

  it('freezes submission and records non-recoverable error', () => {
    const intId = 'int-test-2'
    interactionDraftStore.freezeSubmission(intId, 'sub-uuid-1', [['1080P']], false)

    let draft = interactionDraftStore.getDraft(intId)
    expect(draft.frozenSubmissionId).toBe('sub-uuid-1')
    expect(draft.frozenAnswers).toEqual([['1080P']])
    expect(draft.isSubmitting).toBe(true)

    interactionDraftStore.setSubmissionError(intId, 'Conflict 409', true)
    draft = interactionDraftStore.getDraft(intId)
    expect(draft.isSubmitting).toBe(false)
    expect(draft.error).toBe('Conflict 409')
    expect(draft.isNonRecoverable).toBe(true)
  })
})
