import { useSyncExternalStore } from 'react'
import type { InteractionDraft, QuestionDraft } from './questionnaire-types'

const draftMap = new Map<string, InteractionDraft>()
const listeners = new Set<() => void>()

function notifyListeners() {
  for (const listener of listeners) {
    listener()
  }
}

export const interactionDraftStore = {
  getDraft(interactionId: string): InteractionDraft {
    let draft = draftMap.get(interactionId)
    if (!draft) {
      draft = {
        interactionId,
        questionDrafts: {},
      }
      draftMap.set(interactionId, draft)
    }
    return draft
  },

  updateQuestionDraft(
    interactionId: string,
    questionIndex: number,
    updater: (prev: QuestionDraft) => QuestionDraft,
  ) {
    const draft = this.getDraft(interactionId)
    const prevQ = draft.questionDrafts[questionIndex] || {
      selectedLabels: [],
      customText: '',
    }
    const nextQ = updater(prevQ)
    draft.questionDrafts = {
      ...draft.questionDrafts,
      [questionIndex]: nextQ,
    }
    // 当修改选项时清除旧的错误提示
    draft.error = null
    draftMap.set(interactionId, { ...draft })
    notifyListeners()
  },

  freezeSubmission(
    interactionId: string,
    submissionId: string,
    answers?: string[][],
    declined?: boolean,
  ) {
    const draft = this.getDraft(interactionId)
    draft.frozenSubmissionId = submissionId
    draft.frozenAnswers = answers
    draft.frozenDeclined = declined
    draft.isSubmitting = true
    draft.error = null
    draftMap.set(interactionId, { ...draft })
    notifyListeners()
  },

  setSubmissionError(
    interactionId: string,
    errorMessage: string,
    isNonRecoverable = false,
  ) {
    const draft = this.getDraft(interactionId)
    draft.isSubmitting = false
    draft.error = errorMessage
    draft.isNonRecoverable = isNonRecoverable
    draftMap.set(interactionId, { ...draft })
    notifyListeners()
  },

  /**
   * 409 回读确认事项仍待处理：解除只读围栏并作废被拒的提交身份，草稿保留供重新确认。
   */
  markConflictStillPending(interactionId: string, errorMessage: string) {
    const draft = this.getDraft(interactionId)
    draft.isSubmitting = false
    draft.error = errorMessage
    draft.isNonRecoverable = false
    draft.frozenSubmissionId = undefined
    draft.frozenAnswers = undefined
    draft.frozenDeclined = undefined
    draftMap.set(interactionId, { ...draft })
    notifyListeners()
  },

  setSubmitting(interactionId: string, isSubmitting: boolean) {
    const draft = this.getDraft(interactionId)
    draft.isSubmitting = isSubmitting
    draftMap.set(interactionId, { ...draft })
    notifyListeners()
  },

  clearDraft(interactionId: string) {
    draftMap.delete(interactionId)
    notifyListeners()
  },

  subscribe(listener: () => void) {
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
    }
  },

  /** 仅用于单元测试清理环境 */
  resetForTesting() {
    draftMap.clear()
    notifyListeners()
  },
}

export function useInteractionDraft(interactionId: string): InteractionDraft {
  return useSyncExternalStore(
    interactionDraftStore.subscribe,
    () => interactionDraftStore.getDraft(interactionId),
    () => interactionDraftStore.getDraft(interactionId),
  )
}
