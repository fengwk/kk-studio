export interface AskUserOption {
  label: string
  description?: string
  recommended?: boolean
}

export interface AskUserQuestion {
  question: string
  multiple?: boolean
  options?: AskUserOption[]
}

export interface AskUserQuestionnaire {
  questions: AskUserQuestion[]
}

export interface QuestionDraft {
  selectedLabels: string[]
  customText: string
}

export interface InteractionDraft {
  interactionId: string
  questionDrafts: Record<number, QuestionDraft>
  frozenSubmissionId?: string
  frozenAnswers?: string[][]
  frozenDeclined?: boolean
  isSubmitting?: boolean
  error?: string | null
  isNonRecoverable?: boolean
}
